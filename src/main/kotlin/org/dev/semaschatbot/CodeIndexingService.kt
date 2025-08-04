package org.dev.semaschatbot

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiField
import com.intellij.psi.util.PsiTreeUtil
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * 코드 조각을 나타내는 데이터 클래스입니다.
 * @param id 고유 식별자
 * @param filePath 파일 경로
 * @param fileName 파일 이름
 * @param content 코드 내용
 * @param type 코드 타입 (CLASS, METHOD, FIELD, FILE 등)
 * @param startLine 시작 라인 번호
 * @param endLine 끝 라인 번호
 * @param signature 메서드/클래스 시그니처
 * @param summary 코드 요약
 */
data class CodeChunk(
    val id: String,
    val filePath: String,
    val fileName: String,
    val content: String,
    val type: CodeType,
    val startLine: Int,
    val endLine: Int,
    val signature: String = "",
    val summary: String = ""
)

/**
 * 코드 타입을 나타내는 열거형입니다.
 */
enum class CodeType {
    FILE,           // 전체 파일
    CLASS,          // 클래스
    METHOD,         // 메서드/함수
    FIELD,          // 필드/변수
    INTERFACE,      // 인터페이스
    ENUM,           // 열거형
    ANNOTATION,     // 어노테이션
    COMMENT,        // 주석
    IMPORT,         // 임포트
    UNKNOWN         // 기타
}

/**
 * 프로젝트의 소스코드를 스캔하고 인덱싱하는 서비스입니다.
 * 각 코드 조각을 분석하여 검색 가능한 형태로 변환합니다.
 */
@Service(Service.Level.PROJECT)
class CodeIndexingService(private val project: Project) {
    
    private val codeChunks = ConcurrentHashMap<String, CodeChunk>()
    private val supportedExtensions = setOf("java", "kt", "js", "ts", "vue", "sql", "xml", "yml", "yaml", "json")
    
    /**
     * 프로젝트 전체를 스캔하여 코드 조각들을 인덱싱합니다.
     * @return 인덱싱된 코드 조각의 개수
     */
    fun indexProject(): Int {
        codeChunks.clear()
        val projectBaseDir = project.baseDir ?: return 0
        
        var chunkCount = 0
        VfsUtil.processFilesRecursively(projectBaseDir) { file ->
            if (shouldIndexFile(file)) {
                val chunks = indexFile(file)
                chunks.forEach { chunk ->
                    codeChunks[chunk.id] = chunk
                    chunkCount++
                }
            }
            true
        }
        
        return chunkCount
    }
    
    /**
     * 특정 파일을 인덱싱합니다.
     * @param file 인덱싱할 파일
     * @return 생성된 코드 조각 리스트
     */
    private fun indexFile(file: VirtualFile): List<CodeChunk> {
        val chunks = mutableListOf<CodeChunk>()
        
        try {
            // PSI 접근은 read-action 내에서만 가능
            ApplicationManager.getApplication().runReadAction {
                val psiFile = PsiManager.getInstance(project).findFile(file) ?: return@runReadAction
                
                // 안전한 파일 내용 읽기
                val content = try {
                    String(file.contentsToByteArray())
                } catch (e: Exception) {
                    "// 파일 내용을 읽을 수 없습니다: ${e.message}"
                }
                
                val fileName = file.name
                val filePath = file.path
                
                // 전체 파일을 하나의 chunk로 추가
                chunks.add(createFileChunk(filePath, fileName, content))
                
                // PSI를 이용하여 세부 요소들 추출
                when (file.extension?.lowercase()) {
                    "java", "kt" -> {
                        chunks.addAll(extractJavaKotlinElements(psiFile, filePath, fileName))
                    }
                    "js", "ts" -> {
                        chunks.addAll(extractJavaScriptElements(psiFile, filePath, fileName))
                    }
                    "vue" -> {
                        chunks.addAll(extractVueElements(psiFile, filePath, fileName))
                    }
                    "sql" -> {
                        chunks.addAll(extractSqlElements(psiFile, filePath, fileName))
                    }
                    else -> {
                        // 기타 파일은 라인별로 처리
                        chunks.addAll(extractGenericElements(psiFile, filePath, fileName))
                    }
                }
            }
            
        } catch (e: Exception) {
            // 오프셋 관련 오류는 자세한 정보를 포함하여 로깅
            val errorMessage = when {
                e.message?.contains("Wrong offset") == true -> 
                    "파일 인덱싱 중 오프셋 오류 발생: ${file.path} - ${e.message} (파일 크기: ${file.length} bytes)"
                e.message?.contains("Read access is allowed") == true ->
                    "파일 인덱싱 중 스레딩 오류 발생: ${file.path} - ${e.message}"
                e.message?.contains("must not be null") == true ->
                    "파일 인덱싱 중 null 텍스트 오류 발생: ${file.path} - ${e.message}"
                else -> 
                    "파일 인덱싱 중 오류 발생: ${file.path} - ${e.message}"
            }
            println(errorMessage)
            
            // 오류 발생 시에도 최소한 파일 전체 chunk는 생성하려고 시도
            try {
                val content = String(file.contentsToByteArray())
                chunks.add(createFileChunk(file.path, file.name, content))
            } catch (fallbackException: Exception) {
                println("파일 전체 chunk 생성도 실패: ${file.path} - ${fallbackException.message}")
            }
        }
        
        return chunks
    }
    
    /**
     * 파일 전체를 하나의 chunk로 생성합니다.
     */
    private fun createFileChunk(filePath: String, fileName: String, content: String): CodeChunk {
        val lines = content.lines()
        return CodeChunk(
            id = generateId(filePath, "FILE", 0),
            filePath = filePath,
            fileName = fileName,
            content = content,
            type = CodeType.FILE,
            startLine = 1,
            endLine = lines.size,
            signature = fileName,
            summary = "전체 파일: $fileName (${lines.size}줄)"
        )
    }
    
    /**
     * Java/Kotlin 파일에서 클래스, 메서드, 필드를 추출합니다.
     */
    private fun extractJavaKotlinElements(psiFile: PsiFile, filePath: String, fileName: String): List<CodeChunk> {
        val chunks = mutableListOf<CodeChunk>()
        
        // 클래스들 추출
        PsiTreeUtil.findChildrenOfType(psiFile, PsiClass::class.java).forEach { psiClass ->
            try {
                // PSI 요소가 유효한지 확인
                if (psiClass.isValid && psiClass.name != null) {
                    val classChunk = createClassChunk(psiClass, filePath, fileName)
                    chunks.add(classChunk)
                    
                    // 메서드들 추출
                    psiClass.methods.forEach { method ->
                        try {
                            if (method.isValid && method.name != null) {
                                chunks.add(createMethodChunk(method, filePath, fileName))
                            }
                        } catch (e: Exception) {
                            println("메서드 처리 중 오류: ${method.name} - ${e.message}")
                        }
                    }
                    
                    // 필드들 추출
                    psiClass.fields.forEach { field ->
                        try {
                            if (field.isValid && field.name != null) {
                                chunks.add(createFieldChunk(field, filePath, fileName))
                            }
                        } catch (e: Exception) {
                            println("필드 처리 중 오류: ${field.name} - ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                println("클래스 처리 중 오류: ${psiClass.name} - ${e.message}")
            }
        }
        
        return chunks
    }
    
    /**
     * 클래스 chunk를 생성합니다.
     */
    private fun createClassChunk(psiClass: PsiClass, filePath: String, fileName: String): CodeChunk {
        val document = psiClass.containingFile.viewProvider.document
        val (startLine, endLine) = getSafeLineNumbers(document, psiClass.textOffset, psiClass.textLength)
        
        // 안전한 텍스트 접근
        val content = try {
            psiClass.text ?: "// 클래스 내용을 읽을 수 없습니다."
        } catch (e: Exception) {
            "// 클래스 내용을 읽을 수 없습니다: ${e.message}"
        }
        
        return CodeChunk(
            id = generateId(filePath, "CLASS", startLine),
            filePath = filePath,
            fileName = fileName,
            content = content,
            type = CodeType.CLASS,
            startLine = startLine,
            endLine = endLine,
            signature = psiClass.qualifiedName ?: psiClass.name ?: "Unknown",
            summary = "클래스: ${psiClass.name}"
        )
    }
    
    /**
     * 메서드 chunk를 생성합니다.
     */
    private fun createMethodChunk(psiMethod: PsiMethod, filePath: String, fileName: String): CodeChunk {
        val document = psiMethod.containingFile.viewProvider.document
        val (startLine, endLine) = getSafeLineNumbers(document, psiMethod.textOffset, psiMethod.textLength)
        
        // 안전한 텍스트 접근
        val content = try {
            psiMethod.text ?: "// 메서드 내용을 읽을 수 없습니다."
        } catch (e: Exception) {
            "// 메서드 내용을 읽을 수 없습니다: ${e.message}"
        }
        
        // 안전한 시그니처 생성 (타입 정보 접근 시 예외 처리)
        val signature = try {
            val parameters = psiMethod.parameterList.parameters.joinToString { param ->
                try {
                    param.type.presentableText
                } catch (e: Exception) {
                    "Unknown"
                }
            }
            "${psiMethod.name}($parameters)"
        } catch (e: Exception) {
            psiMethod.name ?: "Unknown"
        }
        
        return CodeChunk(
            id = generateId(filePath, "METHOD", startLine),
            filePath = filePath,
            fileName = fileName,
            content = content,
            type = CodeType.METHOD,
            startLine = startLine,
            endLine = endLine,
            signature = signature,
            summary = "메서드: ${psiMethod.name}"
        )
    }
    
    /**
     * 필드 chunk를 생성합니다.
     */
    private fun createFieldChunk(psiField: PsiField, filePath: String, fileName: String): CodeChunk {
        val document = psiField.containingFile.viewProvider.document
        val (startLine, endLine) = getSafeLineNumbers(document, psiField.textOffset, psiField.textLength)
        
        // 안전한 텍스트 접근
        val content = try {
            psiField.text ?: "// 필드 내용을 읽을 수 없습니다."
        } catch (e: Exception) {
            "// 필드 내용을 읽을 수 없습니다: ${e.message}"
        }
        
        // 안전한 시그니처 생성 (타입 정보 접근 시 예외 처리)
        val signature = try {
            val typeName = try {
                psiField.type.presentableText
            } catch (e: Exception) {
                "Unknown"
            }
            "${psiField.name}: $typeName"
        } catch (e: Exception) {
            psiField.name ?: "Unknown"
        }
        
        return CodeChunk(
            id = generateId(filePath, "FIELD", startLine),
            filePath = filePath,
            fileName = fileName,
            content = content,
            type = CodeType.FIELD,
            startLine = startLine,
            endLine = endLine,
            signature = signature,
            summary = "필드: ${psiField.name}"
        )
    }
    
    /**
     * JavaScript/TypeScript 요소들을 추출합니다. (향후 구현)
     */
    private fun extractJavaScriptElements(psiFile: PsiFile, filePath: String, fileName: String): List<CodeChunk> {
        // TODO: JavaScript/TypeScript 파싱 구현
        return extractGenericElements(psiFile, filePath, fileName)
    }
    
    /**
     * Vue 파일 요소들을 추출합니다. (향후 구현)
     */
    private fun extractVueElements(psiFile: PsiFile, filePath: String, fileName: String): List<CodeChunk> {
        // TODO: Vue 파일 파싱 구현
        return extractGenericElements(psiFile, filePath, fileName)
    }
    
    /**
     * SQL 파일 요소들을 추출합니다. (향후 구현)
     */
    private fun extractSqlElements(psiFile: PsiFile, filePath: String, fileName: String): List<CodeChunk> {
        // TODO: SQL 파싱 구현
        return extractGenericElements(psiFile, filePath, fileName)
    }
    
    /**
     * 일반적인 텍스트 기반 요소들을 추출합니다.
     */
    private fun extractGenericElements(psiFile: PsiFile, filePath: String, fileName: String): List<CodeChunk> {
        val chunks = mutableListOf<CodeChunk>()
        
        // 안전한 텍스트 접근
        val content = try {
            psiFile.text ?: ""
        } catch (e: Exception) {
            ""
        }
        
        if (content.isEmpty()) {
            return chunks
        }
        
        val lines = content.lines()
        
        // 의미 있는 코드 블록들을 찾아서 chunk로 생성
        var currentChunk = StringBuilder()
        var chunkStartLine = 1
        var lineCount = 0
        
        lines.forEachIndexed { index, line ->
            val lineNumber = index + 1
            val trimmedLine = line.trim()
            
            if (trimmedLine.isNotEmpty()) {
                if (currentChunk.isEmpty()) {
                    chunkStartLine = lineNumber
                }
                currentChunk.appendLine(line)
                lineCount++
                
                // 일정 라인 수마다 chunk 생성
                if (lineCount >= 10) {
                    chunks.add(createGenericChunk(
                        filePath, fileName, currentChunk.toString().trim(),
                        chunkStartLine, lineNumber
                    ))
                    currentChunk.clear()
                    lineCount = 0
                }
            }
        }
        
        // 남은 내용 처리
        if (currentChunk.isNotEmpty()) {
            chunks.add(createGenericChunk(
                filePath, fileName, currentChunk.toString().trim(),
                chunkStartLine, lines.size
            ))
        }
        
        return chunks
    }
    
    /**
     * 일반적인 chunk를 생성합니다.
     */
    private fun createGenericChunk(filePath: String, fileName: String, content: String, startLine: Int, endLine: Int): CodeChunk {
        return CodeChunk(
            id = generateId(filePath, "GENERIC", startLine),
            filePath = filePath,
            fileName = fileName,
            content = content,
            type = CodeType.UNKNOWN,
            startLine = startLine,
            endLine = endLine,
            signature = "$fileName:$startLine-$endLine",
            summary = "코드 블록: $fileName ($startLine-$endLine 줄)"
        )
    }
    
    /**
     * 안전한 라인 번호를 계산합니다. 오프셋 오류를 방지합니다.
     * @param document 대상 문서
     * @param textOffset PSI 요소의 시작 오프셋
     * @param textLength PSI 요소의 길이
     * @return Pair<시작라인, 끝라인>
     */
    private fun getSafeLineNumbers(document: Document?, textOffset: Int, textLength: Int): Pair<Int, Int> {
        if (document == null) {
            return Pair(1, 1)
        }
        
        val documentLength = document.textLength
        
        // 오프셋이 문서 범위를 초과하는 경우 안전한 값으로 조정
        val safeStartOffset = textOffset.coerceIn(0, documentLength - 1)
        val safeEndOffset = (textOffset + textLength).coerceIn(0, documentLength)
        
        try {
            val startLine = document.getLineNumber(safeStartOffset) + 1
            val endLine = document.getLineNumber(safeEndOffset) + 1
            return Pair(startLine, endLine)
        } catch (e: Exception) {
            // 예외 발생 시 기본값 반환
            return Pair(1, 1)
        }
    }
    
    /**
     * 파일이 인덱싱 대상인지 확인합니다.
     */
    private fun shouldIndexFile(file: VirtualFile): Boolean {
        if (file.isDirectory) return false
        
        val extension = file.extension?.lowercase() ?: return false
        if (!supportedExtensions.contains(extension)) return false
        
        // 제외할 디렉토리/파일 패턴
        val path = file.path.lowercase()
        val excludePatterns = listOf(
            "/build/", "/target/", "/out/", "/.gradle/", "/.idea/",
            "/node_modules/", "/dist/", "/.git/", "/bin/"
        )
        
        return excludePatterns.none { path.contains(it) }
    }
    
    /**
     * 고유 ID를 생성합니다.
     */
    private fun generateId(filePath: String, type: String, line: Int): String {
        return "${filePath.hashCode()}_${type}_$line"
    }
    
    /**
     * 인덱싱된 모든 코드 조각을 반환합니다.
     */
    fun getAllCodeChunks(): Collection<CodeChunk> = codeChunks.values
    
    /**
     * 특정 타입의 코드 조각들을 반환합니다.
     */
    fun getCodeChunksByType(type: CodeType): List<CodeChunk> {
        return codeChunks.values.filter { it.type == type }
    }
    
    /**
     * 파일명으로 코드 조각들을 검색합니다.
     */
    fun searchByFileName(fileName: String): List<CodeChunk> {
        return codeChunks.values.filter { it.fileName.contains(fileName, ignoreCase = true) }
    }
    
    /**
     * 키워드로 코드 조각들을 검색합니다. (단순 텍스트 매칭)
     */
    fun searchByKeyword(keyword: String): List<CodeChunk> {
        return codeChunks.values.filter { 
            it.content.contains(keyword, ignoreCase = true) ||
            it.signature.contains(keyword, ignoreCase = true) ||
            it.summary.contains(keyword, ignoreCase = true)
        }
    }
    
    /**
     * 인덱싱 통계 정보를 반환합니다.
     */
    fun getIndexingStats(): Map<String, Int> {
        val stats = mutableMapOf<String, Int>()
        stats["total_chunks"] = codeChunks.size
        
        CodeType.values().forEach { type ->
            stats[type.name.lowercase()] = codeChunks.values.count { it.type == type }
        }
        
        return stats
    }
} 