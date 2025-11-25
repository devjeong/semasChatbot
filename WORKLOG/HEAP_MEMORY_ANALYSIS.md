# Heap 메모리 부족 및 성능 저하 원인 분석 보고서

## 📋 요구사항 요약

### 문제점
- 플러그인 실행 시 heap 메모리 부족 오류 발생
- 플러그인 실행 속도가 현저하게 느려짐
- 대규모 프로젝트에서 문제가 더 심각하게 발생

### 목표
- 메모리 사용 패턴 분석
- 메모리 부족의 근본 원인 식별
- 성능 저하 원인 파악
- 개선 방안 제시

---

## 🔍 메모리 사용 패턴 분석

### 1. **CodeIndexingService의 무제한 메모리 사용**

#### 문제점
```67:69:src/main/kotlin/org/dev/semaschatbot/CodeIndexingService.kt
private val codeChunks = ConcurrentHashMap<String, CodeChunk>()
private val supportedExtensions = setOf("java", "kt", "js", "ts", "vue", "sql", "xml", "yml", "yaml", "json")
private val invertedIndex = ConcurrentHashMap<String, MutableSet<String>>()
```

**메모리 사용량 추정:**
- **codeChunks**: 모든 코드 청크를 메모리에 보관
  - 대규모 프로젝트(예: 1000개 파일, 평균 10개 청크/파일) = **10,000개 청크**
  - 각 CodeChunk는 content, filePath, fileName, signature 등 포함
  - 평균 청크 크기: **2-5KB** (content 포함)
  - **총 메모리: 20-50MB** (청크만)

- **invertedIndex**: 모든 토큰에 대한 역색인
  - 각 토큰마다 Set<String> (청크 ID 목록) 저장
  - 대규모 프로젝트에서 **수만~수십만 개의 토큰** 생성 가능
  - **총 메모리: 50-200MB** (인덱스만)

**총 예상 메모리 사용량: 70-250MB** (인덱싱 데이터만)

#### 영향
- 프로젝트가 커질수록 메모리 사용량이 선형적으로 증가
- GC 압박 증가로 인한 성능 저하
- OutOfMemoryError 발생 가능성 증가

---

### 2. **ChatService의 반복적인 전체 청크 로드**

#### 문제점
```2697:2715:src/main/kotlin/org/dev/semaschatbot/ChatService.kt
fun searchRelevantCode(query: String, limit: Int = 5): List<CodeChunk> {
    val allChunks = codeIndexingService.getAllCodeChunks()
    
    if (allChunks.isEmpty()) {
        return emptyList()
    }
    
    val queryTerms = extractSearchTerms(query)
    
    // 각 코드 조각에 대해 관련성 점수를 계산
    val scoredChunks = allChunks.map { chunk ->
        val score = calculateRelevanceScore(chunk, queryTerms)
        Pair(chunk, score)
    }.filter { it.second > 0 } // 점수가 0인 것은 제외
      .sortedByDescending { it.second } // 점수 높은 순으로 정렬
      .take(limit) // 상위 N개만 선택
    
    return scoredChunks.map { it.first }
}
```

**메모리 사용 패턴:**
- `getAllCodeChunks()` 호출 시 **모든 청크를 메모리에 로드**
- `searchRelevantCode()`: 전체 청크를 메모리에 로드하여 순차 검색
- `analyzeIndexedDirectories()`: 전체 청크를 메모리에 로드하여 디렉토리 분석
- `findRelatedDirectories()`: 전체 청크를 메모리에 로드하여 관련 디렉토리 검색
- `suggestPackagePaths()`: 전체 청크를 메모리에 로드하여 패키지 분석
- `buildProjectStructureInfo()`: 전체 청크를 메모리에 로드하여 구조 정보 구축

**총 5개 이상의 메서드에서 동시에 전체 청크를 메모리에 로드**

#### 영향
- 검색 시마다 전체 청크 컬렉션을 메모리에 복사
- 여러 검색이 동시에 실행되면 메모리 사용량이 배수로 증가
- GC 빈도 증가로 인한 성능 저하

---

### 3. **파일 내용 전체 메모리 로드**

#### 문제점
```208:222:src/main/kotlin/org/dev/semaschatbot/CodeIndexingService.kt
private fun readFileContentStreaming(file: VirtualFile): String {
    val charset = try { file.charset } catch (_: Exception) { Charsets.UTF_8 }
    file.inputStream.use { input ->
        input.reader(charset).buffered().use { reader ->
            val buffer = CharArray(8192)
            val sb = StringBuilder()
            while (true) {
                val n = reader.read(buffer)
                if (n <= 0) break
                sb.append(buffer, 0, n)
            }
            return sb.toString()
        }
    }
}
```

**메모리 사용 패턴:**
- 스트리밍이라고 하지만 **결국 전체 파일 내용을 StringBuilder에 저장**
- 대용량 파일(예: 10MB)의 경우 **10MB의 메모리를 한 번에 사용**
- 인덱싱 중 여러 파일을 동시에 처리하면 메모리 사용량이 급증

#### 영향
- 대용량 파일 인덱싱 시 메모리 피크 발생
- 여러 파일 동시 인덱싱 시 OutOfMemoryError 가능성

---

### 4. **검색 시 중복 캐시 생성**

#### 문제점
```618:647:src/main/kotlin/org/dev/semaschatbot/CodeIndexingService.kt
val fileContentCache = HashMap<String, String>()
val fileLowerCache = HashMap<String, String>()
return codeChunks.values.filter { chunk ->
    // 1) content 직접 검사
    val hasDirect = if (chunk.content.isNotEmpty()) {
        chunk.content.contains(keyword, ignoreCase = true)
    } else false
    if (hasDirect) return@filter true
    // 2) 시그니처/요약 검사
    if (chunk.signature.contains(keyword, ignoreCase = true)) return@filter true
    if (chunk.summary.contains(keyword, ignoreCase = true)) return@filter true
    // 3) 범위 참조가 있는 경우, 파일 본문에서 구간만 검사 (fallback, content가 비어있을 때만)
    if (chunk.content.isEmpty() && chunk.startOffset >= 0 && chunk.endOffset > chunk.startOffset) {
        val fileText = fileContentCache.getOrPut(chunk.filePath) {
            // FILE chunk에서 본문 조회 (있을 경우만)
            val fileChunk = codeChunks.values.firstOrNull { it.filePath == chunk.filePath && it.type == CodeType.FILE }
            fileChunk?.content ?: ""
        }
        if (fileText.isNotEmpty()) {
            val fileLower = fileLowerCache.getOrPut(chunk.filePath) { fileText.lowercase() }
            val start = chunk.startOffset.coerceAtMost(fileText.length)
            val end = chunk.endOffset.coerceIn(start, fileText.length)
            if (start < end) {
                val sliceLower = fileLower.substring(start, end)
                return@filter sliceLower.contains(lower)
            }
        }
    }
    false
}
```

**메모리 사용 패턴:**
- 검색 시마다 `fileContentCache`와 `fileLowerCache` 생성
- FILE chunk의 전체 내용을 다시 메모리에 로드
- 대용량 파일의 경우 캐시 크기가 수 MB에 달할 수 있음

#### 영향
- 검색 시마다 추가 메모리 사용
- 여러 검색이 동시에 실행되면 캐시가 중복 생성

---

### 5. **RealTimeIndexingService의 초기 인덱싱**

#### 문제점
```91:113:src/main/kotlin/org/dev/semaschatbot/RealTimeIndexingService.kt
private fun performInitialIndexing() {
    // 백그라운드 스레드에서 인덱싱 수행
    ApplicationManager.getApplication().executeOnPooledThread {
        try {
            println("[RealTimeIndexingService] 초기 인덱싱을 시작합니다...")
            
            val startTime = System.currentTimeMillis()
            val chunkCount = indexingService.indexProject()
            val endTime = System.currentTimeMillis()
            
            totalFilesProcessed.set(chunkCount.toLong())
            
            println("[RealTimeIndexingService] 초기 인덱싱 완료: $chunkCount 개 코드 조각 (${endTime - startTime}ms)")
            
            // 해시 캐시 정리
            hashService.cleanupHashCache()
            
        } catch (e: Exception) {
            println("[RealTimeIndexingService] 초기 인덱싱 중 오류 발생: ${e.message}")
            e.printStackTrace()
        }
    }
}
```

**메모리 사용 패턴:**
- 프로젝트 시작 시 **전체 프로젝트를 한 번에 인덱싱**
- 대규모 프로젝트의 경우 수만 개의 파일을 동시에 처리
- 인덱싱 중 메모리 사용량이 급증

#### 영향
- 프로젝트 시작 시 메모리 부족 발생 가능
- 인덱싱 중 GC 압박으로 인한 UI 프리징

---

## 📊 메모리 사용량 추정

### 대규모 프로젝트 시나리오 (1000개 파일 기준)

| 구성 요소 | 예상 메모리 사용량 |
|---------|------------------|
| codeChunks (10,000개 청크) | 20-50 MB |
| invertedIndex (토큰 역색인) | 50-200 MB |
| 검색 시 임시 캐시 (5개 메서드) | 50-100 MB |
| 파일 내용 로드 (인덱싱 중) | 20-50 MB |
| **총 예상 메모리** | **140-400 MB** |

### 실제 사용량이 더 높을 수 있는 이유
1. **객체 오버헤드**: JVM 객체 헤더, 참조 등
2. **문자열 중복**: 같은 문자열이 여러 곳에 저장
3. **GC 오버헤드**: 메모리 단편화
4. **동시 실행**: 여러 검색/인덱싱 작업이 동시에 실행될 때

---

## 🎯 성능 저하 원인

### 1. **GC 압박**
- 메모리 사용량이 많아지면 GC 빈도 증가
- Full GC 발생 시 애플리케이션 일시 정지
- **영향**: UI 프리징, 응답 지연

### 2. **메모리 단편화**
- 대량의 작은 객체 생성/삭제로 인한 메모리 단편화
- **영향**: 메모리 할당 실패, OutOfMemoryError

### 3. **캐시 미스**
- 메모리가 부족하면 OS가 스왑 메모리 사용
- **영향**: 디스크 I/O로 인한 극심한 성능 저하

### 4. **순차 검색 오버헤드**
- `searchRelevantCode()`에서 모든 청크를 순차적으로 검색
- **영향**: 검색 시간이 청크 수에 비례하여 증가

---

## ✅ 개선 방안

### 1. **LRU 캐시 도입**
- 자주 사용되는 청크만 메모리에 보관
- 오래된 청크는 디스크에 저장하거나 제거
- **예상 효과**: 메모리 사용량 50-70% 감소

### 2. **지연 로딩 (Lazy Loading)**
- 청크의 content를 필요할 때만 로드
- 인덱스에는 메타데이터만 저장
- **예상 효과**: 초기 메모리 사용량 60-80% 감소

### 3. **검색 최적화**
- 역색인을 활용한 빠른 검색
- 전체 청크를 메모리에 로드하지 않고 인덱스만 사용
- **예상 효과**: 검색 시간 80-90% 단축, 메모리 사용량 감소

### 4. **배치 처리 개선**
- 인덱싱을 더 작은 배치로 분할
- 각 배치 처리 후 GC 유도
- **예상 효과**: 메모리 피크 감소, 안정성 향상

### 5. **메모리 제한 설정**
- 최대 청크 수 제한
- 메모리 사용량 모니터링 및 경고
- **예상 효과**: OutOfMemoryError 방지

### 6. **파일 내용 스트리밍 개선**
- 필요한 부분만 읽기
- 큰 파일은 청크 단위로 처리
- **예상 효과**: 대용량 파일 처리 시 메모리 사용량 감소

---

## 📝 결론

### 주요 원인
1. **무제한 메모리 사용**: 모든 코드 청크를 메모리에 보관
2. **반복적인 전체 로드**: 검색 시마다 전체 청크를 메모리에 로드
3. **대용량 파일 처리**: 파일 전체를 메모리에 로드
4. **중복 캐시 생성**: 검색 시마다 임시 캐시 생성

### 우선순위별 개선 사항
1. **높음**: LRU 캐시 도입, 지연 로딩
2. **중간**: 검색 최적화, 배치 처리 개선
3. **낮음**: 메모리 제한 설정, 파일 내용 스트리밍 개선

### 예상 개선 효과
- **메모리 사용량**: 50-70% 감소
- **검색 성능**: 80-90% 향상
- **안정성**: OutOfMemoryError 방지

---

## 🔧 다음 단계

1. **즉시 적용 가능한 개선**
   - 검색 최적화 (역색인 활용)
   - 배치 처리 개선

2. **중기 개선**
   - LRU 캐시 도입
   - 지연 로딩 구현

3. **장기 개선**
   - 디스크 기반 인덱싱
   - 메모리 사용량 모니터링 시스템

