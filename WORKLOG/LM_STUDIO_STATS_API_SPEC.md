# LM Studio 통계 전송 API 기능명세서

## 📋 개요

LM Studio 모델을 사용하여 LLM 응답을 받은 후, 응답에 대한 통계 정보를 서버로 전송하는 기능을 구현합니다.

### 목적
- LM Studio 모델 사용량 추적 및 분석
- 사용자별 모델 사용 통계 수집
- 서버에서 중앙 집중식 통계 관리

### 범위
- LM Studio 모델 사용 시에만 동작
- 응답 완료 후 자동으로 통계 전송
- 비동기 처리로 사용자 경험에 영향 없음

---

## 🎯 요구사항

### 기능 요구사항

1. **통계 정보 수집**
   - 사용자 아이디 (userId)
   - 사용 모델명 (modelId)
   - 입력 토큰 수 (inputTokens)
   - 출력 토큰 수 (outputTokens)
   - 총 토큰 수 (totalTokens)
   - 응답 시간 (responseTime, 밀리초)

2. **서버 API 호출**
   - HTTP POST 방식
   - JSON 형식으로 데이터 전송
   - 서버 IP는 설정 가능
   - 비동기 처리 (사용자 경험에 영향 없음)

3. **에러 처리**
   - 네트워크 오류 시 재시도 (최대 3회)
   - 실패해도 사용자에게 영향 없음 (백그라운드 처리)
   - 로깅을 통한 디버깅 지원

---

## 📐 API 스펙

### 엔드포인트

**URL**: `{서버_IP}/api/lm-studio/stats`

**Method**: `POST`

**Content-Type**: `application/json`

### 요청 본문 (Request Body)

```json
{
  "userId": 123,
  "modelId": "llama-3.1-8b-instruct",
  "inputTokens": 150,
  "outputTokens": 250,
  "totalTokens": 400,
  "responseTime": 1250
}
```

#### 필드 설명

| 필드명 | 타입 | 필수 | 설명 |
|--------|------|------|------|
| `userId` | integer | ✅ | 로그인한 사용자 ID (로그인하지 않은 경우 null) |
| `modelId` | string | ✅ | 사용한 LM Studio 모델 ID |
| `inputTokens` | integer | ✅ | 입력 토큰 수 |
| `outputTokens` | integer | ✅ | 출력 토큰 수 |
| `totalTokens` | integer | ✅ | 총 토큰 수 (inputTokens + outputTokens) |
| `responseTime` | integer | ✅ | 응답 시간 (밀리초) |

### 응답 (Response)

#### 성공 응답 (HTTP 200 OK)

```json
{
  "success": true,
  "message": "통계 정보가 저장되었습니다."
}
```

#### 실패 응답 (HTTP 400 Bad Request)

```json
{
  "success": false,
  "message": "잘못된 요청 형식입니다.",
  "error": "필수 필드가 누락되었습니다."
}
```

#### 실패 응답 (HTTP 500 Internal Server Error)

```json
{
  "success": false,
  "message": "서버 오류가 발생했습니다.",
  "error": "데이터베이스 연결 실패"
}
```

---

## 🔧 구현 계획

### Phase 1: 데이터 모델 설계

#### Task 1.1: LM Studio 통계 데이터 모델 생성

```kotlin
/**
 * LM Studio 통계 정보를 담는 데이터 클래스
 */
data class LmStudioStats(
    val userId: Int?,
    val modelId: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    val responseTime: Long  // 밀리초
)
```

**파일 위치**: `src/main/kotlin/org/dev/semaschatbot/LmStudioStats.kt`

---

### Phase 2: LM Studio API 응답 파싱 개선

#### Task 2.1: LmStudioClient 응답 파싱 개선

**목표**: LM Studio API 응답에서 토큰 정보와 응답 시간을 추출합니다.

**현재 문제점**:
- `sendChatRequest()` 메서드가 토큰 정보를 추출하지 않음
- 응답 시간을 측정하지 않음
- `usage` 필드를 파싱하지 않음

**개선 방안**:

1. **응답 데이터 클래스 생성**
```kotlin
/**
 * LM Studio API 응답을 담는 데이터 클래스
 */
data class LmStudioResponse(
    val content: String,
    val usage: LmStudioUsage?,
    val modelId: String
)

data class LmStudioUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)
```

2. **sendChatRequest 메서드 수정**
```kotlin
/**
 * LM Studio API에 채팅 요청을 보내고 응답을 반환합니다.
 * @param userMessage 사용자 입력 메시지
 * @param systemMessage 시스템 프롬프트
 * @param modelId 사용할 LLM 모델의 ID
 * @return LmStudioResponse 객체 (토큰 정보 포함)
 */
fun sendChatRequest(
    userMessage: String, 
    systemMessage: String, 
    modelId: String = "default-model"
): LmStudioResponse? {
    val startTime = System.currentTimeMillis()
    
    // ... 기존 요청 로직 ...
    
    val responseBody = response.body?.string() ?: return null
    val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
    
    val choices = jsonResponse.getAsJsonArray("choices")
    val content = if (choices.size() > 0) {
        choices.get(0).asJsonObject
            .getAsJsonObject("message")
            .get("content").asString
    } else {
        return null
    }
    
    // usage 정보 추출
    val usage = jsonResponse.getAsJsonObject("usage")?.let {
        LmStudioUsage(
            promptTokens = it.get("prompt_tokens")?.asInt ?: 0,
            completionTokens = it.get("completion_tokens")?.asInt ?: 0,
            totalTokens = it.get("total_tokens")?.asInt ?: 0
        )
    }
    
    val responseTime = System.currentTimeMillis() - startTime
    
    return LmStudioResponse(
        content = content,
        usage = usage,
        modelId = modelId
    )
}
```

**파일 위치**: `src/main/kotlin/org/dev/semaschatbot/LmStudioClient.kt` (수정)

---

### Phase 3: 통계 전송 API 클라이언트 구현

#### Task 3.1: LmStudioStatsApiClient 클래스 생성

**목표**: 서버로 통계 정보를 전송하는 API 클라이언트를 구현합니다.

**구현 내용**:

```kotlin
/**
 * LM Studio 통계 정보를 서버로 전송하는 API 클라이언트
 */
class LmStudioStatsApiClient(
    private var serverBaseUrl: String = "http://192.168.18.53"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    
    /**
     * 서버 기본 URL 설정
     */
    fun setServerBaseUrl(url: String) {
        serverBaseUrl = url.trim().removeSuffix("/")
    }
    
    /**
     * LM Studio 통계 정보를 서버로 전송합니다.
     * 
     * @param stats 통계 정보
     * @return 전송 성공 여부
     */
    fun sendStats(stats: LmStudioStats): Boolean {
        return try {
            val requestBodyMap = mapOf(
                "userId" to (stats.userId ?: 0),
                "modelId" to stats.modelId,
                "inputTokens" to stats.inputTokens,
                "outputTokens" to stats.outputTokens,
                "totalTokens" to stats.totalTokens,
                "responseTime" to stats.responseTime
            )
            
            val requestBodyJson = gson.toJson(requestBodyMap)
            val request = Request.Builder()
                .url("$serverBaseUrl/api/lm-studio/stats")
                .post(RequestBody.create("application/json".toMediaTypeOrNull(), requestBodyJson))
                .build()
            
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            println("[LmStudioStatsApiClient] 통계 전송 실패: ${e.message}")
            false
        }
    }
    
    /**
     * LM Studio 통계 정보를 서버로 전송합니다. (비동기, 재시도 포함)
     * 
     * @param stats 통계 정보
     * @param maxRetries 최대 재시도 횟수 (기본값: 3)
     */
    fun sendStatsAsync(stats: LmStudioStats, maxRetries: Int = 3) {
        Thread {
            var success = false
            for (attempt in 1..maxRetries) {
                success = sendStats(stats)
                if (success) {
                    println("[LmStudioStatsApiClient] 통계 전송 성공 (시도 $attempt)")
                    break
                } else {
                    if (attempt < maxRetries) {
                        Thread.sleep(1000 * attempt) // 지수 백오프
                    }
                }
            }
            if (!success) {
                println("[LmStudioStatsApiClient] 통계 전송 실패 (모든 재시도 실패)")
            }
        }.start()
    }
}
```

**파일 위치**: `src/main/kotlin/org/dev/semaschatbot/LmStudioStatsApiClient.kt`

---

### Phase 4: ChatService 통합

#### Task 4.1: ChatService에 통계 전송 로직 추가

**목표**: LM Studio 모델 사용 시 응답 완료 후 자동으로 통계를 전송합니다.

**구현 위치**: `ChatService.kt`

**수정 내용**:

1. **LmStudioStatsApiClient 인스턴스 추가**
```kotlin
private val lmStudioStatsApiClient = LmStudioStatsApiClient()
```

2. **LM Studio 응답 처리 시 통계 전송**
```kotlin
// LM Studio API 호출 완료 시
onComplete = {
    ApplicationManager.getApplication().invokeLater {
        loadingIndicator?.isVisible = false
        
        val responseTime = System.currentTimeMillis() - startTime
        val responseText = accumulatedResponse.toString()
        
        // LM Studio 응답에서 토큰 정보 추출 (응답 개선 후)
        val estimatedInputTokens = prompt.length / 4
        val estimatedOutputTokens = responseText.length / 4
        
        // 통계 정보 생성
        val currentUserId = try {
            userService.getCurrentUser()?.id
        } catch (e: Exception) {
            null
        }
        
        val stats = LmStudioStats(
            userId = currentUserId,
            modelId = modelId,
            inputTokens = estimatedInputTokens,
            outputTokens = estimatedOutputTokens,
            totalTokens = estimatedInputTokens + estimatedOutputTokens,
            responseTime = responseTime
        )
        
        // 비동기로 통계 전송 (사용자 경험에 영향 없음)
        lmStudioStatsApiClient.sendStatsAsync(stats)
        
        // 기존 사용량 측정 로직
        userService.recordApiCall(true, responseTime)
        userService.recordTokens(estimatedInputTokens, estimatedOutputTokens)
        
        // ... 기존 로직 계속 ...
    }
}
```

3. **작업 모드에서도 통계 전송**
```kotlin
// executeTaskWithSelectedModel에서 LM Studio 사용 시
val result = if (isGeminiModel(modelId)) {
    // Gemini API 호출
    // ...
} else {
    // LM Studio API 호출
    val startTime = System.currentTimeMillis()
    val lmResponse = apiClient.sendChatRequest(
        userMessage = prompt,
        systemMessage = systemMessage,
        modelId = modelId
    )
    
    if (lmResponse != null) {
        val responseTime = System.currentTimeMillis() - startTime
        
        // 통계 정보 생성 및 전송
        val stats = LmStudioStats(
            userId = currentUserId,
            modelId = modelId,
            inputTokens = lmResponse.usage?.promptTokens ?: (prompt.length / 4),
            outputTokens = lmResponse.usage?.completionTokens ?: (lmResponse.content.length / 4),
            totalTokens = lmResponse.usage?.totalTokens ?: ((prompt.length + lmResponse.content.length) / 4),
            responseTime = responseTime
        )
        
        // 비동기로 통계 전송
        lmStudioStatsApiClient.sendStatsAsync(stats)
        
        lmResponse.content
    } else {
        "오류: LM Studio API 응답이 null입니다."
    }
}
```

---

## 📊 데이터 흐름

### 1. LM Studio API 호출
```
사용자 입력 → ChatService → LmStudioClient.sendChatRequest()
→ LM Studio 서버 → 응답 수신 (토큰 정보 포함)
```

### 2. 통계 정보 수집
```
응답 수신 → 토큰 정보 추출 → 응답 시간 계산 → LmStudioStats 객체 생성
```

### 3. 통계 전송
```
LmStudioStats 객체 → LmStudioStatsApiClient.sendStatsAsync()
→ 서버 API 호출 → 성공/실패 처리
```

---

## 🔍 상세 구현 사항

### 1. 토큰 정보 추출

#### 방법 1: API 응답에서 추출 (권장)
LM Studio API 응답에 `usage` 필드가 포함되어 있는 경우:

```json
{
  "choices": [...],
  "usage": {
    "prompt_tokens": 150,
    "completion_tokens": 250,
    "total_tokens": 400
  }
}
```

#### 방법 2: 추정 (폴백)
API 응답에 `usage` 필드가 없는 경우:
- 입력 토큰: `prompt.length / 4` (대략적인 추정)
- 출력 토큰: `response.length / 4` (대략적인 추정)
- 총 토큰: `inputTokens + outputTokens`

### 2. 응답 시간 측정

```kotlin
val startTime = System.currentTimeMillis()
// API 호출
val response = apiClient.sendChatRequest(...)
val responseTime = System.currentTimeMillis() - startTime
```

### 3. 서버 URL 설정

서버 IP는 설정 가능하도록 구현:
- 기본값: `http://192.168.18.53`
- 설정 파일 또는 UI를 통해 변경 가능
- `LmStudioStatsApiClient.setServerBaseUrl()` 메서드 사용

### 4. 에러 처리

- 네트워크 오류: 재시도 (최대 3회, 지수 백오프)
- 서버 오류: 로깅만 하고 사용자에게 영향 없음
- 파싱 오류: 추정값 사용

---

## 🧪 테스트 계획

### 단위 테스트

1. **LmStudioStats 데이터 클래스 테스트**
   - 객체 생성 및 필드 검증
   - null 값 처리

2. **LmStudioStatsApiClient 테스트**
   - 서버 URL 설정
   - 통계 전송 성공/실패 케이스
   - 재시도 로직 검증

3. **LmStudioClient 응답 파싱 테스트**
   - usage 정보 추출
   - 토큰 정보 파싱
   - 폴백 로직 검증

### 통합 테스트

1. **전체 워크플로우 테스트**
   - LM Studio API 호출 → 통계 수집 → 서버 전송
   - 실제 서버 API와의 통신 검증

2. **에러 처리 테스트**
   - 네트워크 오류 시 재시도
   - 서버 오류 시 사용자 경험 영향 없음 확인

---

## 📝 구현 우선순위

### High Priority
1. **Phase 1**: 데이터 모델 설계
2. **Phase 2**: LM Studio API 응답 파싱 개선
3. **Phase 3**: 통계 전송 API 클라이언트 구현

### Medium Priority
4. **Phase 4**: ChatService 통합

---

## 🔄 기존 코드와의 호환성

### 주의사항

1. **LmStudioClient.sendChatRequest() 반환 타입 변경**
   - 현재: `String?` 반환
   - 변경 후: `LmStudioResponse?` 반환
   - **호환성 유지**: 기존 코드에서 `response.content`로 접근 가능

2. **기존 사용량 측정 로직 유지**
   - `userService.recordApiCall()` 계속 사용
   - `userService.recordTokens()` 계속 사용
   - 새로운 통계 전송은 추가 기능으로 구현

---

## 📅 예상 작업 시간

- **Phase 1**: 1시간
- **Phase 2**: 2-3시간
- **Phase 3**: 2-3시간
- **Phase 4**: 2-3시간

**총 예상 시간**: 7-10시간

---

## 🔍 리스크 및 대응 방안

### 리스크 1: LM Studio API 응답에 usage 필드가 없는 경우
- **대응**: 추정값 사용 (문자열 길이 기반)
- **검증**: 다양한 LM Studio 모델에서 테스트

### 리스크 2: 서버 API가 준비되지 않은 경우
- **대응**: 실패해도 사용자 경험에 영향 없음 (백그라운드 처리)
- **검증**: 서버 API 구현 전에도 클라이언트 코드는 동작 가능

### 리스크 3: 네트워크 지연으로 인한 성능 저하
- **대응**: 비동기 처리로 사용자 경험에 영향 없음
- **검증**: 타임아웃 설정 및 재시도 로직 검증

---

## 📚 참고 자료

- 기존 `AuthApiClient.kt` 구조 참고
- 기존 `LmStudioClient.kt` 응답 파싱 로직 참고
- OpenAI API 스펙 (LM Studio는 OpenAI 호환)

---

**작성일**: 2024-01-XX
**작성자**: AI Assistant
**버전**: 1.0

