package org.dev.semaschatbot

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import java.awt.Color
import java.util.regex.Pattern
import javax.swing.border.EmptyBorder
import javax.swing.border.CompoundBorder
import javax.swing.border.LineBorder
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Dimension
import java.util.Properties
import java.io.InputStream
import java.io.File
import javax.swing.*

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

/**
 * 제안된 코드 변경 사항을 관리하는 데이터 클래스입니다.
 * @param originalCode 원본 코드 조각
 * @param modifiedCode LLM이 제안한 수정된 코드 조각
 * @param document 변경이 적용될 문서
 * @param startOffset 원본 코드의 시작 오프셋
 * @param endOffset 원본 코드의 끝 오프셋
 */
data class PendingChange(
    val originalCode: String,
    val modifiedCode: String,
    val document: Document,
    val startOffset: Int,
    val endOffset: Int
)

/**
 * 전체 파일 수정 제안을 관리하는 데이터 클래스입니다.
 * @param originalContent 원본 파일 전체 내용
 * @param modifiedContent LLM이 제안한 수정된 파일 전체 내용
 * @param document 변경이 적용될 문서
 * @param fileName 파일 이름
 * @param virtualFile 파일의 VirtualFile 객체
 */
data class PendingFileChange(
    val originalContent: String,
    val modifiedContent: String,
    val document: Document,
    val fileName: String,
    val virtualFile: com.intellij.openapi.vfs.VirtualFile?
)

/**
 * 부분 수정 사항을 관리하는 데이터 클래스입니다.
 * @param lineNumber 수정할 라인 번호 (1-based)
 * @param originalLine 원본 라인 내용
 * @param modifiedLine 수정된 라인 내용
 * @param operation 수정 유형 (REPLACE, INSERT, DELETE)
 */
data class LineChange(
    val lineNumber: Int,
    val originalLine: String,
    val modifiedLine: String,
    val operation: ChangeOperation
)

/**
 * 커서 위치에서의 코드 삽입을 관리하는 데이터 클래스입니다.
 * @param insertLine 코드를 삽입할 라인 번호 (1-based)
 * @param generatedCode LLM이 생성한 새로운 코드
 * @param document 변경이 적용될 문서
 * @param insertOffset 삽입할 위치의 오프셋
 */
data class PendingCodeInsertion(
    val insertLine: Int,
    val generatedCode: String,
    val document: com.intellij.openapi.editor.Document,
    val insertOffset: Int
)

/**
 * 수정 유형을 나타내는 열거형입니다.
 */
enum class ChangeOperation {
    REPLACE,  // 라인 교체
    INSERT,   // 라인 삽입
    DELETE    // 라인 삭제
}

/**
 * 파일 템플릿 타입을 나타내는 열거형입니다.
 */
enum class FileTemplateType {
    JAVA_CLASS,         // Java 클래스
    JAVA_INTERFACE,     // Java 인터페이스
    JAVA_ENUM,          // Java 열거형
    VUE_COMPONENT,      // Vue 컴포넌트
    XML_CONFIG,         // XML 설정 파일
    JSON_CONFIG,        // JSON 설정 파일
    KOTLIN_CLASS,       // Kotlin 클래스
    PLAIN_TEXT,         // 일반 텍스트 파일
    CUSTOM             // 사용자 정의
}

/**
 * 새 파일 생성 제안을 관리하는 데이터 클래스입니다.
 * @param filePath 생성할 파일의 전체 경로
 * @param fileName 파일 이름
 * @param content 파일 내용
 * @param templateType 템플릿 타입
 * @param packageName 패키지명 (Java/Kotlin의 경우)
 * @param className 클래스명 (있는 경우)
 * @param directory 디렉토리 경로
 */
data class PendingFileCreation(
    val filePath: String,
    val fileName: String,
    val content: String,
    val templateType: FileTemplateType,
    val packageName: String? = null,
    val className: String? = null,
    val directory: String
)

/**
 * 외부 파일 수정 제안을 관리하는 데이터 클래스입니다.
 * @param filePath 수정할 파일의 경로
 * @param originalContent 원본 파일 내용
 * @param modifiedContent 수정된 파일 내용
 * @param fileName 파일 이름
 * @param virtualFile 파일의 VirtualFile 객체 (열려있지 않은 경우 null)
 */
data class PendingExternalFileEdit(
    val filePath: String,
    val originalContent: String,
    val modifiedContent: String,
    val fileName: String,
    val virtualFile: com.intellij.openapi.vfs.VirtualFile? = null
)

@Service(Service.Level.PROJECT)
class ChatService(private val project: Project) {

    private val apiClient = LmStudioClient()
    private val geminiClient = GeminiClient()
    // 실시간 인덱싱 서비스의 CodeIndexingService 인스턴스 사용
    private val realTimeIndexingService = project.getService(RealTimeIndexingService::class.java)
    private val codeIndexingService: CodeIndexingService
        get() = realTimeIndexingService.getIndexingService()
    var systemMessage: String = """
        <prompt>
            <persona>
            당신은 해당 주제에 대해 가장 객관적이고 신뢰성 있는 정보를 제공하는 **IT 커리어 및 개발자 역량 분야의 전문 리서처**입니다. 당신의 임무는 복잡한 정보를 비전문가도 쉽게 이해할 수 있도록 명확하게 핵심만 요약하여 설명하는 것입니다.
            </persona>
            
            <primary_goal>
            제공된 `<context>`를 **훌륭한 답변을 생성하기 위한 핵심 가이드라인으로 삼고**, 당신의 전문 지식과 리서치 능력을 활용하여, 사용자의 요청인 **"시니어 개발자의 핵심 역할과 필요 역량"**에 대해, 아래의 모든 지시를 충족하는 상세한 답변을 Markdown 형식으로 생성하는 것이 당신의 임무입니다.
            </primary_goal>
            
            <context>
            - 기술적 깊이와 복잡한 문제 해결 능력: 단순히 코딩을 넘어 시스템 전체 관점에서 문제를 정의하고 최적의 기술적 해결책을 설계 및 제시하는 능력.
            - 코드 품질 및 아키텍처 설계 능력: 유지보수와 확장이 용이한 시스템 아키텍처를 설계하고, 코드 리뷰와 표준화를 통해 팀 전체의 코드 품질을 책임지는 역할.
            - 소프트 스킬 및 리더십: 명확한 커뮤니케이션, 팀원 멘토링, 비즈니스 요구사항 이해를 바탕으로 프로젝트와 팀을 성공적으로 이끄는 능력.
            </context>
            
            <workflow>
            1.  **핵심 분석:** 사용자의 질문 의도와 제공된 `<context>`의 핵심 **가이드라인**을 분석합니다.
            2.  **답변 구조 설계:** 답변을 가장 논리적인 순서(예: 시니어 개발자 정의 -> 핵심 역량 3가지 상세 설명 -> 종합 결론)로 구조화합니다.
            3.  **최종 답변 작성:** 위 설계에 따라, 각 항목에 대한 상세하고 명확한 설명을 담은 최종 답변을 생성합니다.
            </workflow>
            
            <instructions>
            - 기술 리딩 및 멘토링 방법에 대해 구체적으로 설명해주세요.
            - 코드 품질 및 성능 최적화를 위한 구체적인 활동들을 포함해주세요.
            - 복잡한 문제를 해결하는 접근 방식에 대해 설명해주세요.
            - 답변은 반드시 두괄식으로 핵심 결론부터 제시해주십시오.
            - 모든 정보는 객관적인 사실에 기반하여 설명해주십시오.
            </instructions>
            
            <constraints>
            - 절대 `<context>`에 명시된 **가이드라인**과 동떨어진 내용을 생성하지 마세요.
            - 확인되지 않은 정보나 개인적인 추측, 의견은 답변에 포함하지 마세요.
            </constraints>
        </prompt>
        """.trimIndent()

    var chatPanel: JPanel? = null
    var scrollPane: JScrollPane? = null
    var loadingIndicator: JLabel? = null
    var fileInfoLabel: JLabel? = null

    private var selectedCode: String? = null
    private var selectedFileInfo: String? = null
    private var selectedStartOffset: Int? = null
    private var selectedEndOffset: Int? = null
    private var selectedDocument: Document? = null

    // 커서 위치 기반 코드 생성을 위한 컨텍스트 변수들
    private var cursorLine: Int? = null
    private var currentLineText: String? = null
    private var cursorFileInfo: String? = null
    private var fullFileContent: String? = null
    private var cursorFileName: String? = null

    // 여러 개의 동시 변경 제안을 관리하기 위한 리스트
    val pendingChanges = mutableListOf<PendingChange>()
    
    // 전체 파일 변경 제안을 관리하기 위한 변수
    private var pendingFileChange: PendingFileChange? = null

    // 커서 위치 코드 삽입 제안을 관리하기 위한 변수
    private var pendingCodeInsertion: PendingCodeInsertion? = null

    // 새 파일 생성 제안을 관리하기 위한 변수
    private var pendingFileCreation: PendingFileCreation? = null

    // 외부 파일 수정 제안을 관리하기 위한 변수
    private var pendingExternalFileEdit: PendingExternalFileEdit? = null
    
    // 작업 모드 관련 변수
    private var currentTaskSession: org.dev.semaschatbot.task.TaskSession? = null
    private var taskStateMachine: org.dev.semaschatbot.task.TaskExecutionStateMachine? = null
    private var taskHistoryManager: org.dev.semaschatbot.task.TaskHistoryManager? = null
    private var taskHistoryFile: java.io.File? = null

    // 사용자 서비스 (회원인증 및 사용량 관리)
    private val userService = UserService(project)

    // DB 스키마 정보
    private var dbSchema: String? = null

    // 모델 선택 상태 (Gemini 또는 LM Studio)
    @Volatile
    private var selectedModelId: String = "default-model"

    fun setSelectedModel(modelId: String) {
        println("[ChatService] setSelectedModel 호출: '$modelId'")
        selectedModelId = modelId
        println("[ChatService] selectedModelId 업데이트 완료: '$selectedModelId'")
    }

    fun getSelectedModel(): String = selectedModelId
    
    /**
     * 선택된 모델이 Gemini 모델인지 확인합니다.
     * @return Gemini 모델 여부
     */
    private fun isGeminiModel(modelId: String): Boolean {
        val isGemini = modelId.startsWith("💎") || modelId.startsWith("gemini-")
        println("[ChatService] isGeminiModel 체크: '$modelId' -> $isGemini (startsWith('💎'): ${modelId.startsWith("💎")}, startsWith('gemini-'): ${modelId.startsWith("gemini-")})")
        return isGemini
    }
    
    /**
     * 모델 ID에서 실제 Gemini 모델명을 추출합니다.
     * @param modelId 선택된 모델 ID (예: "💎 gemini-1.5-flash")
     * @return 실제 모델명 (예: "gemini-1.5-flash")
     */
    private fun extractGeminiModelId(modelId: String): String {
        return if (modelId.startsWith("💎")) {
            // "💎 " 또는 "💎"로 시작하는 경우 처리
            val cleaned = modelId.replace(Regex("^💎\\s*"), "").trim()
            println("[ChatService] 모델명 추출: '$modelId' -> '$cleaned'")
            cleaned
        } else if (modelId.startsWith("gemini-")) {
            modelId
        } else {
            modelId
        }
    }

    fun listLmStudioModels(): List<String> {
        return try {
            val future = ApplicationManager.getApplication().executeOnPooledThread<List<String>> {
                apiClient.listModels()
            }
            future.get()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // 서버 URL 관리 (기본값: 192.168.18.53)
    // 주의: 서버 기본 URL은 호스트만 포함하며, 포트는 각 서비스별로 다릅니다.
    // - LM Studio: {서버URL}:7777/v1
    // - Gemini API: {서버URL}:5000/api/gemini
    @Volatile
    private var serverBaseUrl: String = "http://192.168.18.53"
    
    /**
     * 서버 기본 URL을 설정합니다.
     * 이 URL은 LM Studio, Gemini API 프록시, 그리고 인증 API의 기본 주소로 사용됩니다.
     * 포트는 포함하지 않으며, 각 서비스별로 자동으로 추가됩니다.
     * @param url 새로운 서버 기본 URL (예: "http://192.168.18.53")
     */
    fun setServerBaseUrl(url: String) {
        var cleanedUrl = url.trim().removeSuffix("/")
        
        // 포트가 포함된 경우 제거 (호스트만 저장)
        // 예: http://192.168.18.53:5000 -> http://192.168.18.53
        val portPattern = Regex(":\\d+$")
        cleanedUrl = cleanedUrl.replace(portPattern, "")
        
        serverBaseUrl = cleanedUrl
        
        // LM Studio URL 자동 업데이트: {서버URL}:7777/v1
        val lmStudioUrl = "$cleanedUrl:7777/v1"
        apiClient.setBaseUrl(lmStudioUrl)
        
        // Gemini Client에 서버 URL 설정 (포트 5000 포함)
        val geminiServerUrl = "$cleanedUrl:5000"
        geminiClient.setServerBaseUrl(geminiServerUrl)
        
        // UserService의 AuthApiClient에도 서버 URL 동기화
        try {
            val userService = project.getService(UserService::class.java)
            userService?.updateServerUrl(cleanedUrl)
        } catch (e: Exception) {
            println("[ChatService] UserService 업데이트 실패: ${e.message}")
        }
        
        // 설정 저장
        saveServerSettings()
        println("[ChatService] 서버 URL이 변경되었습니다: $cleanedUrl")
        println("[ChatService] LM Studio URL: $lmStudioUrl")
        println("[ChatService] Gemini API URL: $geminiServerUrl/api/gemini")
        println("[ChatService] 인증 API URL: $cleanedUrl:5000/api/auth")
    }
    
    /**
     * 현재 설정된 서버 기본 URL을 반환합니다.
     * @return 현재 서버 기본 URL
     */
    fun getServerBaseUrl(): String {
        return serverBaseUrl
    }
    
    /**
     * 현재 설정된 LM Studio URL을 반환합니다.
     * @return 현재 LM Studio URL ({서버URL}:7777/v1)
     */
    fun getLmStudioUrl(): String {
        return apiClient.getBaseUrl()
    }
    
    /**
     * 서버 설정을 파일에 저장합니다.
     */
    private fun saveServerSettings() {
        try {
            val configFile = File(project.basePath ?: System.getProperty("user.home"), ".semas-chatbot/server.properties")
            configFile.parentFile?.mkdirs()
            val props = Properties()
            props.setProperty("server.baseUrl", serverBaseUrl)
            configFile.outputStream().use { props.store(it, "Server Settings") }
        } catch (e: Exception) {
            println("서버 설정 저장 오류: ${e.message}")
        }
    }
    
    /**
     * 서버 설정을 파일에서 로드합니다.
     */
    private fun loadServerSettings() {
        try {
            val configFile = File(project.basePath ?: System.getProperty("user.home"), ".semas-chatbot/server.properties")
            if (configFile.exists()) {
                val props = Properties()
                configFile.inputStream().use { props.load(it) }
                val savedUrl = props.getProperty("server.baseUrl", "")
                if (savedUrl.isNotBlank()) {
                    // 포트가 포함된 경우 제거 (호스트만 저장)
                    var cleanedUrl = savedUrl.trim().removeSuffix("/")
                    val portPattern = Regex(":\\d+$")
                    cleanedUrl = cleanedUrl.replace(portPattern, "")
                    
                    serverBaseUrl = cleanedUrl
                    // LM Studio URL 자동 구성: {서버URL}:7777/v1
                    val lmStudioUrl = "$cleanedUrl:7777/v1"
                    apiClient.setBaseUrl(lmStudioUrl)
                    // Gemini Client에 서버 URL 설정 (포트 5000 포함)
                    val geminiServerUrl = "$cleanedUrl:5000"
                    geminiClient.setServerBaseUrl(geminiServerUrl)
                    
                    // UserService의 AuthApiClient에도 서버 URL 동기화
                    try {
                        val userService = project.getService(UserService::class.java)
                        userService?.updateServerUrl(cleanedUrl)
                    } catch (e: Exception) {
                        println("[ChatService] UserService 업데이트 실패: ${e.message}")
                    }
                }
            } else {
                // 기본값 설정
                val lmStudioUrl = "$serverBaseUrl:7777/v1"
                apiClient.setBaseUrl(lmStudioUrl)
                // Gemini는 포트 5000 사용
                val geminiServerUrl = "$serverBaseUrl:5000"
                geminiClient.setServerBaseUrl(geminiServerUrl)
                
                // UserService의 AuthApiClient에도 서버 URL 동기화
                try {
                    val userService = project.getService(UserService::class.java)
                    userService?.updateServerUrl(serverBaseUrl)
                } catch (e: Exception) {
                    println("[ChatService] UserService 업데이트 실패: ${e.message}")
                }
            }
        } catch (e: Exception) {
            println("서버 설정 로드 오류: ${e.message}")
            // 오류 발생 시 기본값 사용
            val lmStudioUrl = "$serverBaseUrl:7777/v1"
            apiClient.setBaseUrl(lmStudioUrl)
            // Gemini는 포트 5000 사용
            val geminiServerUrl = "$serverBaseUrl:5000"
            geminiClient.setServerBaseUrl(geminiServerUrl)
        }
    }

    // Gemini API 설정 관리
    @Volatile
    private var geminiApiKey: String = ""
    
    /**
     * Gemini API Key를 반환합니다.
     * @return API Key
     */
    fun getGeminiApiKey(): String {
        return geminiApiKey
    }
    
    /**
     * Gemini API Key를 설정합니다.
     * 주의: 이 메서드는 런타임에 API Key를 변경할 때만 사용됩니다.
     * 일반적으로는 config.properties 파일에서 자동으로 로드됩니다.
     * @param apiKey API Key
     */
    fun setGeminiApiKey(apiKey: String) {
        geminiApiKey = apiKey.trim()
        geminiClient.setApiKey(geminiApiKey)
    }
    
    /**
     * Gemini 설정을 config.properties 파일에서 로드합니다.
     * config.properties는 빌드 시점에 리소스로 포함되므로 런타임에 수정할 수 없습니다.
     */
    private fun loadGeminiSettings() {
        try {
            // 리소스 파일에서 config.properties 읽기
            val inputStream = ChatService::class.java.classLoader.getResourceAsStream("config.properties")
                ?: ChatService::class.java.getResourceAsStream("/config.properties")
            
            if (inputStream != null) {
                val props = Properties()
                inputStream.use { props.load(it) }
                geminiApiKey = props.getProperty("gemini.apiKey", "").trim()
                
                if (geminiApiKey.isNotBlank()) {
                    geminiClient.setApiKey(geminiApiKey)
                    println("[ChatService] Gemini API Key가 config.properties에서 로드되었습니다.")
                } else {
                    println("[ChatService] 경고: config.properties에 gemini.apiKey가 설정되지 않았습니다.")
                }
            } else {
                println("[ChatService] 경고: config.properties 파일을 찾을 수 없습니다.")
            }
        } catch (e: Exception) {
            println("Gemini 설정 로드 오류: ${e.message}")
            e.printStackTrace()
        }
    }
    
    init {
        // 초기화 시 설정 로드 (순서 중요: 서버 URL 먼저, 그 다음 Gemini)
        loadServerSettings()
        loadGeminiSettings()
    }

    /**
     * 현재 로그인 상태를 반환합니다.
     * @return 로그인 여부
     */
    fun isUserAuthenticated(): Boolean {
        return userService.isLoggedIn()
    }

    /**
     * 로그인 상태를 초기화합니다.
     */
    fun resetAuthentication() {
        userService.logout()
        sendMessage("로그아웃되었습니다. 다시 로그인해주세요.", isUser = false)
    }

    /**
     * 로그인이 필요한지 확인합니다.
     * @return 로그인이 필요한 경우 true
     */
    fun requiresAuthentication(): Boolean {
        return !userService.isLoggedIn()
    }
    
    /**
     * 현재 로그인한 사용자 정보를 반환합니다.
     */
    fun getCurrentUser(): User? {
        return userService.getCurrentUser()
    }
    
    /**
     * UserService 인스턴스를 반환합니다.
     */
    fun getUserService(): UserService {
        return userService
    }

    /**
     * 사용자가 에디터에서 선택한 코드와 파일 정보를 컨텍스트로 설정합니다.
     * @param code 선택된 코드
     * @param fileInfo 파일 정보
     */
    fun setSelectionContext(code: String, fileInfo: String) {
        try {
            println("[ChatService] setSelectionContext 호출: 파일=$fileInfo, 코드 길이=${code.length}자")
            
            // 입력 검증
            if (code.isBlank()) {
                println("[ChatService] 경고: 선택된 코드가 비어있습니다.")
                return
            }
            
            selectedCode = code
            selectedFileInfo = fileInfo
            
            // 현재 선택 영역의 오프셋 정보도 저장
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            if (editor != null) {
                val selectionModel = editor.selectionModel
                if (selectionModel.hasSelection()) {
                    selectedStartOffset = selectionModel.selectionStart
                    selectedEndOffset = selectionModel.selectionEnd
                    selectedDocument = editor.document
                    println("[ChatService] 선택 오프셋 저장: $selectedStartOffset-$selectedEndOffset")
                } else {
                    // 선택 영역이 없어도 코드는 저장 (이전 선택 정보 유지)
                    println("[ChatService] 현재 에디터에 선택 영역이 없지만 코드는 저장했습니다.")
                }
            } else {
                println("[ChatService] 현재 활성 에디터가 없습니다.")
            }
            
            ApplicationManager.getApplication().invokeLater {
                fileInfoLabel?.text = "선택된 파일: $fileInfo"
                fileInfoLabel?.isVisible = true
                fileInfoLabel?.toolTipText = "선택된 코드: ${code.take(100)}..."
            }
            
            println("[ChatService] 선택 컨텍스트 설정 완료")
            
        } catch (e: Exception) {
            println("[ChatService] setSelectionContext 오류: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 커서 위치 기반 코드 생성을 위한 컨텍스트를 설정합니다.
     * @param cursorLine 현재 커서가 있는 라인 번호
     * @param currentLineText 현재 라인의 텍스트
     * @param fileInfo 파일 정보
     * @param fullFileContent 전체 파일 내용
     * @param totalLines 전체 라인 수
     * @param fileName 파일 이름
     */
    fun setCursorContext(
        cursorLine: Int,
        currentLineText: String,
        fileInfo: String,
        fullFileContent: String,
        totalLines: Int,
        fileName: String
    ) {
        this.cursorLine = cursorLine
        this.currentLineText = currentLineText
        this.cursorFileInfo = fileInfo
        this.fullFileContent = fullFileContent
        this.cursorFileName = fileName
        
        ApplicationManager.getApplication().invokeLater {
            fileInfoLabel?.text = "커서 위치: $fileInfo"
            fileInfoLabel?.isVisible = true
        }
        
        sendMessage("💡 커서 위치에서 새로운 코드를 생성할 수 있습니다. 원하는 기능을 설명해주세요!", isUser = false)
    }

    /**
     * 설정된 선택 컨텍스트를 초기화합니다.
     */
    private fun clearSelectionContext() {
        selectedCode = null
        selectedFileInfo = null
        selectedStartOffset = null
        selectedEndOffset = null
        selectedDocument = null
        ApplicationManager.getApplication().invokeLater {
            fileInfoLabel?.isVisible = false
        }
    }

    /**
     * 선택 컨텍스트를 초기화합니다. (외부에서 호출 가능)
     */
    fun resetSelectionContext() {
        clearSelectionContext()
    }

    /**
     * 적용된 코드 영역에 대해 포맷팅을 수행합니다.
     * @param document 문서
     * @param startOffset 시작 오프셋
     * @param endOffset 끝 오프셋
     */
    private fun formatCodeRange(document: Document, startOffset: Int, endOffset: Int) {
        try {
            // PsiFile 가져오기
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
            if (psiFile != null) {
                // 파일 확장자에 따라 포맷팅 적용 여부 결정
                val fileName = psiFile.name.lowercase()
                val isJavaScriptFile = fileName.endsWith(".js") || 
                                     fileName.endsWith(".jsx") || 
                                     fileName.endsWith(".ts") || 
                                     fileName.endsWith(".tsx") ||
                                     fileName.endsWith(".vue")
                val isJavaFile = fileName.endsWith(".java") || fileName.endsWith(".kt")
                
                if (isJavaScriptFile || isJavaFile) {
                    ApplicationManager.getApplication().invokeLater {
                        WriteCommandAction.runWriteCommandAction(project) {
                            // IntelliJ의 내장 포맷터를 사용하여 특정 범위 포맷팅
                            val codeStyleManager = CodeStyleManager.getInstance(project)
                            codeStyleManager.reformatText(psiFile, startOffset, endOffset)
                            
                            // JavaScript/TypeScript 파일의 경우 ESLint 실행 시도
                            if (isJavaScriptFile) {
                                tryRunESLint(psiFile)
                            } else {
                                sendMessage("📐 코드 포맷팅이 적용되었습니다.", isUser = false)
                            }
                        }
                    }
                } else {
                    sendMessage("💡 해당 파일 형식은 자동 포맷팅을 지원하지 않습니다.", isUser = false)
                }
            }
        } catch (e: Exception) {
            sendMessage("⚠️ 포맷팅 중 오류가 발생했습니다: ${e.message}", isUser = false)
        }
    }

    /**
     * ESLint를 실행하여 코드를 포맷팅합니다.
     * @param psiFile 대상 파일
     */
    private fun tryRunESLint(psiFile: PsiFile) {
        try {
            val virtualFile = psiFile.virtualFile
            if (virtualFile != null) {
                val filePath = virtualFile.path
                val projectPath = project.basePath
                
                // ESLint가 프로젝트에 설치되어 있는지 확인
                val eslintPaths = listOf(
                    "$projectPath/node_modules/.bin/eslint",
                    "$projectPath/node_modules/.bin/eslint.cmd"
                )
                
                val eslintPath = eslintPaths.find { File(it).exists() }
                
                if (eslintPath != null) {
                    // ESLint 실행
                    val processBuilder = ProcessBuilder(eslintPath, "--fix", filePath)
                    processBuilder.directory(File(projectPath ?: "."))
                    
                    Thread {
                        try {
                            val process = processBuilder.start()
                            val exitCode = process.waitFor()
                            
                            ApplicationManager.getApplication().invokeLater {
                                if (exitCode == 0) {
                                    // 파일 새로고침하여 ESLint 변경사항 반영
                                    virtualFile.refresh(false, false)
                                    sendMessage("📐 ESLint 포맷팅이 적용되었습니다.", isUser = false)
                                } else {
                                    sendMessage("📐 IntelliJ 포맷팅이 적용되었습니다. (ESLint 실행 실패)", isUser = false)
                                }
                            }
                        } catch (e: Exception) {
                            ApplicationManager.getApplication().invokeLater {
                                sendMessage("📐 IntelliJ 포맷팅이 적용되었습니다. (ESLint 실행 중 오류: ${e.message})", isUser = false)
                            }
                        }
                    }.start()
                } else {
                    sendMessage("📐 IntelliJ 포맷팅이 적용되었습니다. (ESLint가 설치되지 않음)", isUser = false)
                }
            }
        } catch (e: Exception) {
            sendMessage("📐 IntelliJ 포맷팅이 적용되었습니다. (ESLint 확인 중 오류: ${e.message})", isUser = false)
        }
    }

    /**
     * 설정된 커서 컨텍스트를 초기화합니다.
     */
    private fun clearCursorContext() {
        cursorLine = null
        currentLineText = null
        cursorFileInfo = null
        fullFileContent = null
        cursorFileName = null
        ApplicationManager.getApplication().invokeLater {
            fileInfoLabel?.isVisible = false
        }
    }

    /**
     * 현재 활성화된 에디터의 전체 파일 내용을 가져와서 컨텍스트로 설정합니다.
     */
    fun setFullFileContext() {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        val virtualFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        
        if (editor != null && virtualFile != null) {
            val document = editor.document
            val fullContent = document.text
            val fileName = virtualFile.name
            val fileInfo = "${fileName} (전체 파일: ${fullContent.lines().size}줄)"
            
            selectedCode = fullContent
            selectedFileInfo = fileInfo
            
            ApplicationManager.getApplication().invokeLater {
                fileInfoLabel?.text = "분석된 파일: $fileInfo"
                fileInfoLabel?.isVisible = true
            }
            
            sendMessage("전체 파일이 분석되었습니다: $fileInfo", isUser = false)
        } else {
            sendMessage("활성화된 에디터가 없습니다.", isUser = false)
        }
    }

    /**
     * 프로젝트 전체를 인덱싱합니다.
     */
    fun indexProject() {
        sendMessage("🔍 프로젝트 인덱싱을 시작합니다...", isUser = false)
        
        val startTime = System.currentTimeMillis()
        
        object : SwingWorker<Int, Void>() {
            override fun doInBackground(): Int {
                return codeIndexingService.indexProject()
            }
            
            override fun done() {
                try {
                    val chunkCount = get()
                    val stats = codeIndexingService.getIndexingStats()
                    val indexingTime = System.currentTimeMillis() - startTime
                    
                    // 사용량 측정: 인덱싱 기록
                    val indexedFiles = stats["file"] ?: 0
                    userService.recordIndexing(indexedFiles, chunkCount, indexingTime)
                    
                    val statsMessage = buildString {
                        appendLine("✅ 프로젝트 인덱싱이 완료되었습니다!")
                        appendLine("📊 인덱싱 통계:")
                        appendLine("  • 전체 코드 조각: ${stats["total_chunks"]}")
                        appendLine("  • 파일: ${stats["file"]}")
                        appendLine("  • 클래스: ${stats["class"]}")
                        appendLine("  • 메서드: ${stats["method"]}")
                        appendLine("  • 필드: ${stats["field"]}")
                        appendLine("💡 이제 코드베이스 전체를 참조하여 더 정확한 답변을 제공할 수 있습니다!")
                    }
                    
                    sendMessage(statsMessage, isUser = false)
                } catch (e: Exception) {
                    sendMessage("❌ 인덱싱 중 오류가 발생했습니다: ${e.message}", isUser = false)
                }
            }
        }.execute()
    }

    /**
     * 로그인 성공 시 자동으로 실행되는 프로젝트 인덱싱입니다.
     * 실시간 인덱싱 서비스를 시작하고 진행 상황을 상세히 보고합니다.
     */
    fun startAutoIndexing() {
        object : SwingWorker<Boolean, String>() {
            override fun doInBackground(): Boolean {
                publish("🔍 프로젝트 파일을 스캔하고 있습니다...")
                Thread.sleep(500) // UI 업데이트를 위한 짧은 지연
                
                publish("📂 지원되는 파일 확장자: java, kt, js, ts, vue, sql, xml, yml, yaml, json, css")
                Thread.sleep(500)
                
                publish("⚙️ PSI 트리를 분석하여 코드 구조를 파악합니다...")
                Thread.sleep(500)
                
                // 실시간 인덱싱 서비스 시작 (이미 시작되어 있다면 스킵)
                if (!realTimeIndexingService.isActive()) {
                    realTimeIndexingService.startRealTimeIndexing()
                }
                
                // 초기 인덱싱이 완료될 때까지 잠시 대기
                Thread.sleep(2000)
                
                publish("🔧 인덱싱 통계를 생성하고 있습니다...")
                Thread.sleep(300)
                
                publish("🔄 실시간 인덱싱이 활성화되었습니다. 파일 변경사항이 자동으로 반영됩니다!")
                Thread.sleep(300)
                
                return true
            }
            
            override fun process(chunks: List<String>) {
                // 진행 상황 메시지들을 실시간으로 전송
                chunks.forEach { message ->
                    sendMessage(message, isUser = false)
                }
            }
            
            override fun done() {
                try {
                    get() // 결과 확인
                    val stats = codeIndexingService.getIndexingStats()
                    
                    val completionMessage = buildString {
                        appendLine("🎉 자동 프로젝트 인덱싱이 완료되었습니다!")
                        appendLine("")
                        appendLine("📊 최종 인덱싱 결과:")
                        appendLine("  ✓ 전체 코드 조각: ${stats["total_chunks"]}개")
                        appendLine("  ✓ 파일: ${stats["file"]}개")
                        appendLine("  ✓ 클래스: ${stats["class"]}개")
                        appendLine("  ✓ 메서드: ${stats["method"]}개")
                        appendLine("  ✓ 필드: ${stats["field"]}개")
                        appendLine("")
                        appendLine("💡 이제 프로젝트 코드베이스를 기반으로 한 질문에 정확하게 답변할 수 있습니다!")
                        appendLine("🚀 프로젝트에 관한 궁금한 점을 언제든 물어보세요!")
                        appendLine("")
                        appendLine("⚡ 실시간 모드: 파일을 수정하면 자동으로 최신 코드가 반영됩니다!")
                    }
                    
                    sendMessage(completionMessage, isUser = false)
                } catch (e: Exception) {
                    sendMessage("❌ 자동 인덱싱 중 오류가 발생했습니다: ${e.message}", isUser = false)
                    sendMessage("🔧 수동으로 '프로젝트 인덱싱' 버튼을 눌러 다시 시도해보세요.", isUser = false)
                }
            }
        }.execute()
    }



    /**
     * 메신저 스타일의 채팅 UI에 메시지를 추가합니다.
     * @param message 표시할 메시지
     * @param isUser 사용자가 보낸 메시지인지 여부 (true: 우측, false: 좌측)
     */
    fun sendMessage(message: String, isUser: Boolean = true) {
        ApplicationManager.getApplication().invokeLater {
            chatPanel?.let { panel ->
                val messagePanel = createMessagePanel(message, isUser)
                // 메시지 패널 간 간격 추가
                if (panel.componentCount > 0) {
                    panel.add(Box.createVerticalStrut(8))
                }
                panel.add(messagePanel)
                panel.revalidate()
                panel.repaint()
                
                // 스크롤을 맨 아래로 이동 (약간의 지연 후 실행)
                ApplicationManager.getApplication().invokeLater {
                    scrollPane?.let { scroll ->
                        scroll.validate()
                        val scrollBar = scroll.verticalScrollBar
                        scrollBar.value = scrollBar.maximum
                        
                        // 확실한 스크롤 이동을 위한 추가 처리
                        javax.swing.SwingUtilities.invokeLater {
                            scrollBar.value = scrollBar.maximum
                            focusLastMessage()
                        }
                    }
                }
            }
        }
    }

    /**
     * 메신저 스타일의 메시지 패널을 생성합니다.
     * @param message 메시지 텍스트
     * @param isUser 사용자 메시지 여부 (true: 우측, false: 좌측)
     * @return 스타일이 적용된 메시지 패널
     */
    private fun createMessagePanel(message: String, isUser: Boolean): JPanel {
        val containerPanel = JPanel(BorderLayout())
        containerPanel.background = Color.WHITE
        containerPanel.border = EmptyBorder(0, 0, 0, 0)
        
        val messageWrapper = JPanel(FlowLayout(if (isUser) FlowLayout.RIGHT else FlowLayout.LEFT, 7, 0))
        messageWrapper.background = Color.WHITE
        messageWrapper.border = EmptyBorder(0, 0, 0, 0)
        
        val messagePanel = JPanel(BorderLayout())
        val messageText = JTextArea(message)

        if (isUser) {
            // 사용자 메시지 (우측, 파란색)
            messagePanel.background = Color(52, 152, 219)
            messageText.background = Color(52, 152, 219)
            messageText.foreground = Color.WHITE
            messagePanel.border = CompoundBorder(
                LineBorder(Color(41, 128, 185), 1, true),
                EmptyBorder(6, 10, 6, 10)
            )
        } else {
            // AI 메시지 (좌측, 회색)
            messagePanel.background = Color(236, 240, 241)
            messageText.background = Color(236, 240, 241)
            messageText.foreground = Color(44, 62, 80)
            messagePanel.border = CompoundBorder(
                LineBorder(Color(189, 195, 199), 1, true),
                EmptyBorder(6, 10, 6, 10)
            )
        }
        
        messageText.font = Font("SansSerif", Font.PLAIN, 13)
        messageText.lineWrap = true
        messageText.wrapStyleWord = true
        messageText.isEditable = false
        messageText.isOpaque = true
        
        // 메시지 텍스트 크기 계산을 위한 임시 설정
        messageText.columns = 0
        messageText.rows = 0
        
        messagePanel.add(messageText, BorderLayout.CENTER)
        
        // AI 메시지이고 코드 블록이 포함된 경우 적용 버튼 추가
        var hasApplyButton = false
        if (!isUser && hasCodeBlock(message)) {
            val buttonPanel = createApplyButtonPanel(message)
            messagePanel.add(buttonPanel, BorderLayout.NORTH)
            hasApplyButton = true
        }
        
        // 텍스트 내용에 따른 동적 크기 계산
        val textMetrics = messageText.getFontMetrics(messageText.font)
        val maxWidth = 450  // 최대 너비 확대
        val minWidth = 100
        val maxHeight = 400  // 메시지 패널 최대 높이 제한
        val buttonHeight = if (hasApplyButton) 28 else 0  // 버튼 높이 추가
        
        // 실제 JTextArea의 래핑을 시뮬레이션하여 정확한 줄 수 계산
        val explicitLines = message.split('\n')
        var totalLines = 0
        var maxLineWidth = 0
        
        for (line in explicitLines) {
            if (line.isEmpty()) {
                totalLines += 1
                continue
            }
            
            val lineWidth = textMetrics.stringWidth(line)
            maxLineWidth = maxOf(maxLineWidth, lineWidth)
            
            val availableWidth = maxWidth - 30 // 패딩 제외
            
            if (lineWidth <= availableWidth) {
                totalLines += 1
            } else {
                // 단어 단위 래핑 시뮬레이션
                val words = line.split(' ')
                var currentLineWidth = 0
                var currentLines = 1
                
                for (word in words) {
                    val wordWidth = textMetrics.stringWidth("$word ")
                    if (currentLineWidth + wordWidth > availableWidth) {
                        currentLines++
                        currentLineWidth = wordWidth
                    } else {
                        currentLineWidth += wordWidth
                    }
                }
                totalLines += currentLines
            }
        }
        
        val lineHeight = textMetrics.height
        val totalHeight = totalLines * lineHeight + 20 + buttonHeight  // 버튼 높이 포함
        val actualWidth = (maxLineWidth + 30).coerceIn(minWidth, maxWidth)
        val actualHeight = totalHeight.coerceAtMost(maxHeight + buttonHeight)  // 최대 높이 제한에도 버튼 높이 포함
        
        // 긴 메시지의 경우 스크롤 가능하도록 JScrollPane 사용
        if (totalLines * lineHeight + 20 > maxHeight) {  // 텍스트만의 높이로 판단
            val scrollPane = JBScrollPane(messageText)
            scrollPane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            scrollPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            scrollPane.border = null
            scrollPane.isOpaque = false
            scrollPane.viewport.isOpaque = false
            
            // 기존 텍스트만 제거하고 스크롤 패널로 교체
            messagePanel.remove(messageText)
            messagePanel.add(scrollPane, BorderLayout.CENTER)
            
            // 버튼이 있는 경우 다시 추가
            if (hasApplyButton) {
                val buttonPanel = createApplyButtonPanel(message)
                messagePanel.add(buttonPanel, BorderLayout.NORTH)
            }
            
            // 스크롤 패널 크기 설정 (버튼 높이 제외)
            val scrollHeight = maxHeight - buttonHeight - 20
            scrollPane.preferredSize = Dimension(actualWidth - 20, scrollHeight)
            scrollPane.maximumSize = Dimension(actualWidth - 20, scrollHeight)
        }
        
        // 패널 크기 조정 - 내용에 맞게 동적으로 설정하되 최대 높이 제한
        messagePanel.preferredSize = Dimension(actualWidth, actualHeight)
        messagePanel.maximumSize = Dimension(maxWidth, actualHeight)
        messagePanel.minimumSize = Dimension(minWidth, actualHeight)
        
        // 컨테이너 패널도 동일한 높이로 설정하되 최대 높이 제한
        containerPanel.preferredSize = Dimension(Int.MAX_VALUE, actualHeight)
        containerPanel.maximumSize = Dimension(Int.MAX_VALUE, actualHeight)

        messageWrapper.add(messagePanel)
        containerPanel.add(messageWrapper, BorderLayout.CENTER)
        
        return containerPanel
    }

    /**
     * 메시지에 코드 블록이 포함되어 있는지 확인합니다.
     * @param message 확인할 메시지
     * @return 코드 블록이 포함되어 있으면 true
     */
    private fun hasCodeBlock(message: String): Boolean {
        val codeBlockPattern = Pattern.compile("```[a-zA-Z]*\\s*[\\s\\S]*?```", Pattern.MULTILINE)
        return codeBlockPattern.matcher(message).find()
    }

    /**
     * 적용 버튼 패널을 생성합니다.
     * @param message 코드 블록이 포함된 메시지
     * @return 적용 버튼이 포함된 패널
     */
    private fun createApplyButtonPanel(message: String): JPanel {
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 3, 3))
        buttonPanel.background = Color(236, 240, 241)
        buttonPanel.preferredSize = Dimension(450, 28)  // 메시지 패널 전체 너비에 맞춤
        buttonPanel.maximumSize = Dimension(450, 28)
        buttonPanel.minimumSize = Dimension(100, 28)
        
        // 포맷팅 옵션 체크박스
        val formatCheckBox = JCheckBox("포맷팅", true)
        formatCheckBox.font = Font("SansSerif", Font.PLAIN, 9)
        formatCheckBox.background = Color(236, 240, 241)
        formatCheckBox.foreground = Color(44, 62, 80)
        formatCheckBox.toolTipText = "코드 적용 후 자동 포맷팅 실행"
        formatCheckBox.preferredSize = Dimension(55, 22)
        
        val applyButton = JButton("적용")
        applyButton.font = Font("SansSerif", Font.BOLD, 10)
        applyButton.foreground = Color.WHITE
        applyButton.background = Color(52, 152, 219)
        applyButton.border = CompoundBorder(
            LineBorder(Color(41, 128, 185), 1, true),
            EmptyBorder(3, 8, 3, 8)
        )
        applyButton.isFocusPainted = false
        applyButton.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        applyButton.preferredSize = Dimension(50, 22)
        
        applyButton.addActionListener {
            applyCodeFromMessage(message, formatCheckBox.isSelected)
        }
        
        buttonPanel.add(formatCheckBox)
        buttonPanel.add(applyButton)
        return buttonPanel
    }

    /**
     * 메시지에서 코드 블록을 추출하고 원본 선택 영역과 교체합니다.
     * @param message 코드 블록이 포함된 메시지
     * @param applyFormatting 포맷팅 적용 여부
     */
    private fun applyCodeFromMessage(message: String, applyFormatting: Boolean = true) {
        // 저장된 선택 영역 정보가 있는지 확인
        if (selectedDocument == null || selectedStartOffset == null || selectedEndOffset == null) {
            sendMessage("❌ 원본 선택 영역 정보가 없습니다. 'Send Selection to Chat'으로 선택한 코드에만 적용할 수 있습니다.", isUser = false)
            return
        }

        // 메시지에서 첫 번째 코드 블록 추출
        val codeBlockPattern = Pattern.compile("```[a-zA-Z]*\\s*([\\s\\S]*?)```", Pattern.MULTILINE)
        val matcher = codeBlockPattern.matcher(message)
        
        if (matcher.find()) {
            val newCode = matcher.group(1).trim()
            val document = selectedDocument!!
            val startOffset = selectedStartOffset!!
            val endOffset = selectedEndOffset!!
            
            ApplicationManager.getApplication().runWriteAction {
                WriteCommandAction.runWriteCommandAction(project) {
                    // 문서가 여전히 유효한지 확인
                    if (startOffset <= document.textLength && endOffset <= document.textLength) {
                        document.replaceString(startOffset, endOffset, newCode)
                        
                        // 에디터가 있다면 새로 삽입된 코드 영역을 선택
                        val editor = FileEditorManager.getInstance(project).selectedTextEditor
                        if (editor != null && editor.document == document) {
                            val newEndOffset = startOffset + newCode.length
                            editor.selectionModel.setSelection(startOffset, newEndOffset)
                            editor.contentComponent.requestFocus()
                        }
                        
                        // 코드 분석 재시작
                        ApplicationManager.getApplication().invokeLater {
                            DaemonCodeAnalyzerEx.getInstanceEx(project).restart()
                        }
                        
                        sendMessage("✅ 코드가 성공적으로 적용되었습니다.", isUser = false)
                        
                        // 포맷팅 옵션이 활성화된 경우 적용된 코드 영역에 포맷팅 적용
                        if (applyFormatting) {
                            val newEndOffset = startOffset + newCode.length
                            formatCodeRange(document, startOffset, newEndOffset)
                        }
                        
                        // 적용 완료 후 선택 컨텍스트 초기화
                        clearSelectionContext()
                    } else {
                        sendMessage("❌ 문서가 변경되어 적용할 수 없습니다. 다시 코드를 선택해주세요.", isUser = false)
                    }
                }
            }
        } else {
            sendMessage("❌ 메시지에서 유효한 코드 블록을 찾을 수 없습니다.", isUser = false)
        }
    }

    /**
     * 사용자 입력 유형을 분류합니다.
     */
    private enum class UserInputType { 
        GENERAL_QUESTION,  // 일반 질문
        RAG_QUESTION,      // RAG 기반 질문
        NEW_SOURCE,        // 신규 소스 작성
        INSTRUCTION,       // 코드 수정/개선 지시
        CURSOR_CODE_GENERATION,  // 커서 위치 코드 생성
        EXTERNAL_FILE_EDIT       // 외부 파일 수정
    }

    /**
     * 선택된 코드가 전체 파일인지 확인합니다.
     */
    private fun isFullFileSelected(): Boolean {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return false
        val document = editor.document
        val fullContent = document.text
        return selectedCode == fullContent || selectedCode?.lines()?.size ?: 0 > 50 // 50줄 이상이면 전체 파일로 간주
    }

    /**
     * LLM에 채팅 요청을 보냅니다.
     * 입력 유형에 따라 분기하여 처리하며, 특히 'INSTRUCTION'의 경우 코드 변경 제안 로직을 수행합니다.
     * @param userInput 사용자의 입력 메시지
     */
    fun sendChatRequestToLLM(userInput: String) {
        // 로그인 체크
        if (!isUserAuthenticated()) {
            sendMessage("❌ 로그인이 필요합니다. 로그인해주세요.", isUser = false)
            return
        }
        
        // 작업 모드 감지
        if (isTaskModeRequest(userInput)) {
            enterTaskMode(userInput)
            return
        }
        
        // 사용량 측정: 메시지 기록
        userService.recordMessage(userInput.length)
        val codeContext = selectedCode  // 선택된 영역만 사용
        val fileContext = selectedFileInfo
        val editor = FileEditorManager.getInstance(project).selectedTextEditor

        // 디버깅: 선택된 코드 상태 확인
        println("[ChatService] sendChatRequestToLLM 호출")
        println("[ChatService] selectedCode 상태: ${if (codeContext != null) "있음 (${codeContext.length}자)" else "없음"}")
        println("[ChatService] selectedFileInfo: $fileContext")
        println("[ChatService] 사용자 입력: ${userInput.take(100)}...")

        sendMessage(userInput, isUser = true)

        val inputType = classifyInput(userInput)
        println("[ChatService] 입력 타입 분류: $inputType")
        val prompt = when {
            inputType == UserInputType.RAG_QUESTION -> {
                // RAG 기반 질문 처리
                val relevantChunks = searchRelevantCode(userInput, 5)
                val contextCode = buildString {
                    // 선택된 코드가 있으면 먼저 포함
                    if (codeContext != null) {
                        appendLine("=== 사용자가 선택한 코드 (파일: $fileContext) ===")
                        appendLine("```")
                        appendLine(codeContext.take(2000)) // 선택된 코드는 최대 2000자까지
                        if (codeContext.length > 2000) appendLine("... (코드가 길어서 일부만 표시)")
                        appendLine("```")
                        appendLine()
                    }
                    
                    // 관련 코드 조각들 추가
                    if (relevantChunks.isNotEmpty()) {
                        appendLine("다음은 질문과 관련된 프로젝트 코드입니다:")
                        appendLine()
                        relevantChunks.forEachIndexed { index, chunk ->
                            appendLine("=== 참조 코드 ${index + 1}: ${chunk.fileName} (${chunk.type.name}) ===")
                            appendLine("위치: ${chunk.filePath}:${chunk.startLine}-${chunk.endLine}")
                            appendLine("시그니처: ${chunk.signature}")
                            appendLine()
                            appendLine("```")
                            appendLine(chunk.content.take(1000)) // 너무 긴 코드는 잘라서 표시
                            if (chunk.content.length > 1000) appendLine("... (코드가 길어서 일부만 표시)")
                            appendLine("```")
                            appendLine()
                        }
                    } else {
                        if (codeContext == null) {
                            append("관련 코드를 찾을 수 없습니다.")
                        }
                    }
                }
                
                """
                아래 제공된 프로젝트 코드 컨텍스트와 사용자 질문을 바탕으로 답변하세요. 출력은 반드시 다음 Markdown 템플릿만 사용합니다.

                템플릿:

                ✅ 질문 요약 : {사용자 질문 요점}

                ✅ 응답 : {응답 내용 (1000자 이내 요약, 정리)}

                ✅ 출처 : {출처 (파일명, 라인 위치)}

                규칙:
                - 한국어로만 작성합니다.
                - '질문 요약'에는 사용자의 질문 핵심을 1~2문장으로 요약합니다.
                - '응답'은 1000자 이내로 간결하게 요약하고, 필요시 불릿을 사용할 수 있습니다.
                - '출처'는 아래 참조 코드의 파일명과 라인 범위를 쉼표로 나열합니다. 형식: 파일명:시작라인-끝라인
                - 참조 코드가 없거나 라인 정보를 알 수 없으면 '해당 없음'으로 표기합니다.
                - 템플릿 외의 여분 텍스트는 출력하지 않습니다.

                컨텍스트:
                $contextCode

                사용자 질문: $userInput
                """.trimIndent()
            }
            inputType == UserInputType.NEW_SOURCE -> {
                // 신규 소스 작성
                val relevantChunks = searchRelevantCode(userInput, 5)
                val contextCode = buildString {
                    // 선택된 코드가 있으면 먼저 포함
                    if (codeContext != null) {
                        appendLine("=== 사용자가 선택한 참조 코드 (파일: $fileContext) ===")
                        appendLine("```")
                        appendLine(codeContext.take(2000))
                        if (codeContext.length > 2000) appendLine("... (코드가 길어서 일부만 표시)")
                        appendLine("```")
                        appendLine()
                    }
                    
                    // 관련 코드 조각들 추가
                    if (relevantChunks.isNotEmpty()) {
                        appendLine("다음은 질문과 관련된 프로젝트 코드입니다:")
                        appendLine()
                        relevantChunks.forEachIndexed { index, chunk ->
                            appendLine("=== 참조 코드 ${index + 1}: ${chunk.fileName} (${chunk.type.name}) ===")
                            appendLine("위치: ${chunk.filePath}:${chunk.startLine}-${chunk.endLine}")
                            appendLine("시그니처: ${chunk.signature}")
                            appendLine()
                            appendLine("```")
                            appendLine(chunk.content.take(1000)) // 너무 긴 코드는 잘라서 표시
                            if (chunk.content.length > 1000) appendLine("... (코드가 길어서 일부만 표시)")
                            appendLine("```")
                            appendLine()
                        }
                    } else {
                        if (codeContext == null) {
                            append("관련 코드를 찾을 수 없습니다.")
                        }
                    }
                }

                """
                아래 제공된 프로젝트 코드 컨텍스트와 사용자 질문을 바탕으로 답변하세요. 출력은 반드시 다음 Markdown 템플릿만 사용합니다.

                템플릿:

                ✅ 질문 요약 : {사용자 질문 요점}

                ✅ 응답 : {응답 내용 (1000자 이내 요약, 정리)}

                ✅ 출처 : {출처 (파일명, 라인 위치)}

                규칙:
                - 한국어로만 작성합니다.
                - '질문 요약'에는 사용자의 질문 핵심을 1~2문장으로 요약합니다.
                - '응답'은 1000자 이내로 간결하게 요약하고, 필요시 불릿을 사용할 수 있습니다.
                - '출처'는 아래 참조 코드의 파일명과 라인 범위를 쉼표로 나열합니다. 형식: 파일명:시작라인-끝라인
                - 참조 코드가 없거나 라인 정보를 알 수 없으면 '해당 없음'으로 표기합니다.
                - 템플릿 외의 여분 텍스트는 출력하지 않습니다.

                컨텍스트:
                $contextCode

                사용자 질문: $userInput
                """.trimIndent()
            }
            inputType == UserInputType.INSTRUCTION -> {
                // 코드 수정/개선 지시 처리
                if (codeContext == null) {
                    sendMessage("❌ 수정할 코드가 선택되지 않았습니다. 먼저 'Send Selection to Chat'으로 코드를 선택해주세요.", isUser = false)
                    return
                }
                
                println("[ChatService] INSTRUCTION 타입 처리: 선택된 코드 길이=${codeContext.length}자, 파일=$fileContext")
                
                // 선택된 코드를 명확하게 포함하는 프롬프트 생성
                """
                사용자가 선택한 코드를 수정/개선해달라고 요청했습니다.

                선택된 코드 (파일: $fileContext):
                ```
                $codeContext
                ```

                사용자 요청: $userInput

                아래 지시사항을 따라 수정된 코드를 제공하세요:

                1. 원본 코드를 분석하고 사용자의 요청을 이해하세요.
                2. 수정된 코드를 다음 형식으로 제공하세요:
                   [Modified]
                   ```코드언어
                   수정된 코드 전체
                   ```
                3. 코드 블록 내에 수정된 코드만 포함하고, 설명이나 주석은 코드 블록 밖에 작성하세요.
                4. 사용자의 요청을 정확히 반영하여 코드를 수정하세요.
                5. 코드 스타일과 구조는 원본과 일관성을 유지하세요.
                6. 한국어로 설명을 제공하세요.

                주의사항:
                - 반드시 [Modified] 태그로 시작해야 합니다.
                - 수정된 코드는 코드 블록(```) 안에 포함되어야 합니다.
                - 원본 코드의 컨텍스트를 유지하면서 요청된 변경사항만 적용하세요.
                """.trimIndent()
            }
            else -> {
                // 그 외의 경우, 일반적인 프롬프트 사용 - RAG 기반으로 관련 코드 참조
                val relevantChunks = searchRelevantCode(userInput, 4)
                val contextCode = buildString {
                    // 선택된 코드가 있으면 먼저 포함
                    if (codeContext != null) {
                        appendLine("=== 사용자가 선택한 코드 (파일: $fileContext) ===")
                        appendLine("```")
                        appendLine(codeContext.take(2000))
                        if (codeContext.length > 2000) appendLine("... (코드가 길어서 일부만 표시)")
                        appendLine("```")
                        appendLine()
                    }
                    
                    // 관련 코드 조각들 추가
                    if (relevantChunks.isNotEmpty()) {
                        appendLine("다음은 질문과 관련된 프로젝트 코드 참조:")
                        appendLine()
                        relevantChunks.forEachIndexed { index, chunk ->
                            appendLine("=== 참조 코드 ${index + 1}: ${chunk.fileName} (${chunk.type.name}) ===")
                            appendLine("위치: ${chunk.filePath}:${chunk.startLine}-${chunk.endLine}")
                            appendLine("시그니처: ${chunk.signature}")
                            appendLine()
                            appendLine("```")
                            appendLine(chunk.content.take(700)) // 일반 질문에는 적당한 길이로
                            if (chunk.content.length > 700) appendLine("... (코드가 길어서 일부만 표시)")
                            appendLine("```")
                            appendLine()
                        }
                    } else {
                        if (codeContext == null) {
                            append("관련된 프로젝트 코드를 찾을 수 없어 일반적인 지식을 기반으로 답변합니다.")
                        }
                    }
                }
                
                """
                아래 제공된 컨텍스트와 사용자 질문을 바탕으로 답변하세요. 출력은 반드시 다음 Markdown 템플릿만 사용합니다.

                템플릿:

                ✅ 질문 요약 : {사용자 질문 요점}

                ✅ 응답 : {응답 내용 (1000자 이내 요약, 정리)}

                ✅ 출처 : {출처 (파일명, 라인 위치)}

                규칙:
                - 한국어로만 작성합니다.
                - '질문 요약'에는 사용자의 질문 핵심을 1~2문장으로 요약합니다.
                - '응답'은 1000자 이내로 간결하게 요약하고, 필요시 불릿을 사용할 수 있습니다.
                - '출처'는 컨텍스트에 포함된 코드 참조의 파일명과 라인 범위를 쉼표로 나열합니다. 형식: 파일명:시작라인-끝라인
                - 참조 코드가 없거나 라인 정보를 알 수 없으면 '해당 없음'으로 표기합니다.
                - 템플릿 외의 여분 텍스트는 출력하지 않습니다.

                컨텍스트:
                $contextCode

                사용자 질문: $userInput
                """.trimIndent()
            }
        }

        // 디버깅: 생성된 프롬프트 확인 (처음 500자만)
        println("[ChatService] 생성된 프롬프트 (처음 500자):")
        println(prompt.take(500))
        println("[ChatService] 프롬프트 전체 길이: ${prompt.length}자")
        if (codeContext != null) {
            val codeIncluded = prompt.contains(codeContext.take(100))
            println("[ChatService] 선택된 코드가 프롬프트에 포함되어 있는지: $codeIncluded")
        }

        ApplicationManager.getApplication().invokeLater { loadingIndicator?.isVisible = true }

        // 스트리밍 모드: 첫 델타가 도착하면 패널을 생성하고, 이후 델타는 누적 업데이트합니다.
        val initialPanelRef = arrayOfNulls<JPanel>(1)
        val initialTextAreaRef = arrayOfNulls<JTextArea>(1)
        val accumulatedResponse = StringBuilder()
        val startTime = System.currentTimeMillis()

        // 선택된 모델이 Gemini 모델인지 확인하여 클라이언트 선택
        val isGemini = isGeminiModel(selectedModelId)
        val actualGeminiModelId = if (isGemini) extractGeminiModelId(selectedModelId) else null
        
        // 디버깅: 모델 선택 정보 출력
        println("[ChatService] 선택된 모델: $selectedModelId")
        println("[ChatService] Gemini 모델 여부: $isGemini")
        println("[ChatService] 실제 Gemini 모델 ID: $actualGeminiModelId")
        println("[ChatService] Gemini API Key 존재 여부: ${geminiApiKey.isNotBlank()}")
        
        if (isGemini && actualGeminiModelId != null) {
            // Gemini 모델 선택 시 API Key 확인
            if (geminiApiKey.isBlank()) {
                ApplicationManager.getApplication().invokeLater {
                    loadingIndicator?.isVisible = false
                    sendMessage("❌ Gemini 모델을 사용하려면 config.properties 파일에 gemini.apiKey를 설정해주세요.\n설정 위치: src/main/resources/config.properties", isUser = false)
                    clearCursorContext()
                }
                return
            }
            
            // Gemini API 사용
            // 현재 로그인한 사용자 ID 가져오기
            val currentUserId = try {
                userService.getCurrentUser()?.id
            } catch (e: Exception) {
                null
            }
            
            println("[ChatService] Gemini API 호출 시작: modelId=$actualGeminiModelId${if (currentUserId != null) ", userId=$currentUserId" else ""}")
            geminiClient.sendChatRequestStream(
                userMessage = prompt,
                systemMessage = systemMessage,
                modelId = actualGeminiModelId,
                userId = currentUserId,
                onDelta = { delta ->
                    ApplicationManager.getApplication().invokeLater {
                        // 응답 누적
                        accumulatedResponse.append(delta)
                        
                        val existingPanel = initialPanelRef[0]
                        val existingText = initialTextAreaRef[0]
                        if (existingPanel == null || existingText == null) {
                            // 첫 델타 수신 시 패널 생성
                            chatPanel?.let { panel ->
                                val messagePanel = createMessagePanel(delta, false)
                                if (panel.componentCount > 0) {
                                    panel.add(Box.createVerticalStrut(8))
                                }
                                panel.add(messagePanel)
                                panel.revalidate()
                                panel.repaint()
                                scrollToBottom()
                                initialPanelRef[0] = messagePanel
                                initialTextAreaRef[0] = findTextArea(messagePanel)
                                focusMessagePanel(messagePanel)
                            }
                        } else {
                            // 이후 델타는 누적하고, 전체 텍스트 기준으로 버블을 재생성하여 크기를 정확히 맞춤
                            val newText = existingText.text + delta
                            rebuildMessagePanel(existingPanel, newText) { newPanel ->
                                initialPanelRef[0] = newPanel
                                initialTextAreaRef[0] = findTextArea(newPanel)
                                focusMessagePanel(newPanel)
                            }
                            scrollToBottom()
                        }
                    }
                },
                onComplete = {
                    ApplicationManager.getApplication().invokeLater {
                        loadingIndicator?.isVisible = false
                        
                        // 사용량 측정: API 호출 성공 기록
                        val responseTime = System.currentTimeMillis() - startTime
                        val responseText = accumulatedResponse.toString()
                        val estimatedInputTokens = prompt.length / 4
                        val estimatedOutputTokens = responseText.length / 4
                        userService.recordApiCall(true, responseTime)
                        userService.recordTokens(estimatedInputTokens, estimatedOutputTokens)
                        
                        // INSTRUCTION 타입인 경우 응답을 파싱하여 처리
                        if (inputType == UserInputType.INSTRUCTION && editor != null) {
                            val fullResponse = accumulatedResponse.toString()
                            println("[ChatService] INSTRUCTION 응답 완료, 파싱 시작: ${fullResponse.take(200)}...")
                            handleInstructionResponse(fullResponse, editor)
                        }
                        
                        clearCursorContext()
                    }
                },
                onError = { e ->
                    ApplicationManager.getApplication().invokeLater {
                        loadingIndicator?.isVisible = false
                        
                        // 사용량 측정: API 호출 실패 기록
                        val responseTime = System.currentTimeMillis() - startTime
                        userService.recordApiCall(false, responseTime)
                        
                        sendMessage("Gemini API 오류가 발생했습니다: ${e.message}", isUser = false)
                        clearCursorContext()
                    }
                }
            )
        } else {
            // LM Studio 사용 (기존 로직)
            println("[ChatService] LM Studio API 호출 (Gemini 모델이 아님): selectedModelId='$selectedModelId'")
            apiClient.sendChatRequestStream(
            userMessage = prompt,
            systemMessage = systemMessage,
            modelId = selectedModelId,
            onDelta = { delta ->
                ApplicationManager.getApplication().invokeLater {
                    // 응답 누적
                    accumulatedResponse.append(delta)
                    
                    val existingPanel = initialPanelRef[0]
                    val existingText = initialTextAreaRef[0]
                    if (existingPanel == null || existingText == null) {
                        // 첫 델타 수신 시 패널 생성
                        chatPanel?.let { panel ->
                            val messagePanel = createMessagePanel(delta, false)
                            if (panel.componentCount > 0) {
                                panel.add(Box.createVerticalStrut(8))
                            }
                            panel.add(messagePanel)
                            panel.revalidate()
                            panel.repaint()
                            scrollToBottom()
                            initialPanelRef[0] = messagePanel
                            initialTextAreaRef[0] = findTextArea(messagePanel)
                            focusMessagePanel(messagePanel)
                        }
                    } else {
                        // 이후 델타는 누적하고, 전체 텍스트 기준으로 버블을 재생성하여 크기를 정확히 맞춤
                        val newText = existingText.text + delta
                        rebuildMessagePanel(existingPanel, newText) { newPanel ->
                            initialPanelRef[0] = newPanel
                            initialTextAreaRef[0] = findTextArea(newPanel)
                            focusMessagePanel(newPanel)
                        }
                        scrollToBottom()
                    }
                }
            },
            onComplete = {
                ApplicationManager.getApplication().invokeLater {
                    loadingIndicator?.isVisible = false
                    
                    // 사용량 측정: API 호출 성공 기록
                    val responseTime = System.currentTimeMillis() - startTime
                    val responseText = accumulatedResponse.toString()
                    // 간단한 토큰 추정 (실제로는 API 응답에서 가져와야 함)
                    val estimatedInputTokens = prompt.length / 4  // 대략적인 추정
                    val estimatedOutputTokens = responseText.length / 4
                    userService.recordApiCall(true, responseTime)
                    userService.recordTokens(estimatedInputTokens, estimatedOutputTokens)
                    
                    // INSTRUCTION 타입인 경우 응답을 파싱하여 처리
                    if (inputType == UserInputType.INSTRUCTION && editor != null) {
                        val fullResponse = accumulatedResponse.toString()
                        println("[ChatService] INSTRUCTION 응답 완료, 파싱 시작: ${fullResponse.take(200)}...")
                        handleInstructionResponse(fullResponse, editor)
                    }
                    
                    // 선택 컨텍스트는 유지하여 적용 버튼에서 사용할 수 있도록 함
                    clearCursorContext()
                }
            },
            onError = { e ->
                ApplicationManager.getApplication().invokeLater {
                    loadingIndicator?.isVisible = false
                    
                    // 사용량 측정: API 호출 실패 기록
                    val responseTime = System.currentTimeMillis() - startTime
                    userService.recordApiCall(false, responseTime)
                    
                    sendMessage("오류가 발생했습니다: ${e.message}", isUser = false)
                    // 선택 컨텍스트는 유지하여 적용 버튼에서 사용할 수 있도록 함
                    clearCursorContext()
                }
            }
        )
        }
    }

    /**
     * LLM의 전체 파일 수정 제안 응답을 파싱하고 처리합니다.
     * @param response LLM 응답 문자열
     * @param editor 현재 활성화된 에디터
     */
    private fun handleFullFileInstructionResponse(response: String, editor: Editor) {
        val document = editor.document
        val virtualFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        
        if (virtualFile == null) {
            sendMessage("파일 정보를 가져올 수 없습니다.", isUser = false)
            return
        }
        
        val pattern = Pattern.compile("\\[FileChanges\\](.*)", Pattern.DOTALL)
        val matcher = pattern.matcher(response)

        if (matcher.find()) {
            val changesContent = matcher.group(1).trim()
            
            try {
                val lineChanges = parseLineChanges(changesContent)
                if (lineChanges.isEmpty()) {
                    sendMessage("수정할 내용이 없습니다.", isUser = false)
                    return
                }
                
                val originalContent = document.text
                val modifiedContent = applyLineChanges(originalContent, lineChanges)
                
                // PendingFileChange 객체 생성 및 저장
                val fileChange = PendingFileChange(
                    originalContent = originalContent,
                    modifiedContent = modifiedContent,
                    document = document,
                    fileName = virtualFile.name,
                    virtualFile = virtualFile
                )
                
                pendingFileChange = fileChange
                
                // 전체 파일 diff 창 표시
                ApplicationManager.getApplication().invokeLater {
                    showFullFileDiffWindow(originalContent, modifiedContent, fileChange)
                    sendMessage("${lineChanges.size}개의 변경사항이 감지되었습니다.", isUser = false)
                }
                
            } catch (e: Exception) {
                sendMessage("변경사항을 파싱하는 중 오류가 발생했습니다: ${e.message}", isUser = false)
                sendMessage("받은 응답:\n$response", isUser = false)
            }
            
        } else {
            sendMessage("파일 변경 제안을 파싱할 수 없습니다. 받은 응답:\n$response", isUser = false)
        }
    }
    
    /**
     * LLM 응답에서 라인 변경사항을 파싱합니다.
     * @param changesContent 변경사항 텍스트
     * @return 파싱된 LineChange 리스트
     */
    private fun parseLineChanges(changesContent: String): List<LineChange> {
        val changes = mutableListOf<LineChange>()
        val lines = changesContent.lines().filter { it.trim().isNotEmpty() }
        
        for (line in lines) {
            val parts = line.split(":", limit = 4)
            if (parts.size >= 3) {
                try {
                    val operation = ChangeOperation.valueOf(parts[0].trim().uppercase())
                    val lineNumber = parts[1].trim().toInt()
                    val originalLine = if (parts.size > 2) parts[2] else ""
                    val modifiedLine = if (parts.size > 3) parts[3] else ""
                    
                    changes.add(LineChange(lineNumber, originalLine, modifiedLine, operation))
                } catch (e: Exception) {
                    // 파싱 실패한 라인은 무시하고 계속 진행
                    sendMessage("라인 파싱 실패: $line", isUser = false)
                }
            }
        }
        
        return changes.sortedBy { it.lineNumber }
    }
    
    /**
     * 원본 파일에 라인 변경사항을 적용하여 수정된 파일을 생성합니다.
     * @param originalContent 원본 파일 내용
     * @param lineChanges 적용할 변경사항 리스트
     * @return 수정된 파일 내용
     */
    private fun applyLineChanges(originalContent: String, lineChanges: List<LineChange>): String {
        val originalLines = originalContent.lines().toMutableList()
        val modifiedLines = originalLines.toMutableList()
        
        // 라인 번호가 큰 것부터 처리하여 인덱스 변경 문제 방지
        val sortedChanges = lineChanges.sortedByDescending { it.lineNumber }
        
        for (change in sortedChanges) {
            val index = change.lineNumber - 1 // 0-based 인덱스로 변환
            
            when (change.operation) {
                ChangeOperation.REPLACE -> {
                    if (index in 0 until modifiedLines.size) {
                        modifiedLines[index] = change.modifiedLine
                    }
                }
                ChangeOperation.INSERT -> {
                    if (index >= 0 && index <= modifiedLines.size) {
                        modifiedLines.add(index + 1, change.modifiedLine)
                    }
                }
                ChangeOperation.DELETE -> {
                    if (index in 0 until modifiedLines.size) {
                        modifiedLines.removeAt(index)
                    }
                }
            }
        }
        
        return modifiedLines.joinToString("\n")
    }

    private fun scrollToBottom() {
        scrollPane?.let { scroll ->
            scroll.validate()
            val scrollBar = scroll.verticalScrollBar
            // 1) 즉시 최대값으로 이동
            scrollBar.value = scrollBar.maximum

            // 2) 마지막 메시지 컴포넌트가 보이도록 뷰포트 스크롤
            val panel = chatPanel
            if (panel != null && panel.componentCount > 0) {
                val last = panel.getComponent(panel.componentCount - 1)
                val bounds = last.bounds
                javax.swing.SwingUtilities.invokeLater {
                    scroll.viewport.scrollRectToVisible(bounds)
                    scrollBar.value = scrollBar.maximum
                }
            } else {
                javax.swing.SwingUtilities.invokeLater { scrollBar.value = scrollBar.maximum }
            }

            // 3) 스트리밍 레이아웃 지연을 고려해 한 번 더 시도
            val timer = javax.swing.Timer(60) { _ ->
                val sb = scroll.verticalScrollBar
                sb.value = sb.maximum
                val p = chatPanel
                if (p != null && p.componentCount > 0) {
                    val lastComp = p.getComponent(p.componentCount - 1)
                    scroll.viewport.scrollRectToVisible(lastComp.bounds)
                }
            }
            timer.isRepeats = false
            timer.start()
        }
    }

    private fun findTextArea(container: java.awt.Component): JTextArea? {
        if (container is JTextArea) return container
        if (container is java.awt.Container) {
            for (child in container.components) {
                val found = findTextArea(child)
                if (found != null) return found
            }
        }
        return null
    }

    private fun focusMessagePanel(messagePanel: JPanel) {
        val scroll = scrollPane ?: return
        val ta = findTextArea(messagePanel)
        if (ta != null) {
            ta.caretPosition = ta.text.length
            ta.requestFocusInWindow()
        }
        javax.swing.SwingUtilities.invokeLater {
            scroll.viewport.scrollRectToVisible(messagePanel.bounds)
            val sb = scroll.verticalScrollBar
            sb.value = sb.maximum
        }
    }

    private fun focusLastMessage() {
        val panel = chatPanel ?: return
        val scroll = scrollPane ?: return
        if (panel.componentCount == 0) return
        val last = panel.getComponent(panel.componentCount - 1)
        val ta = findTextArea(last)
        if (ta != null) {
            ta.caretPosition = ta.text.length
            ta.requestFocusInWindow()
        }
        javax.swing.SwingUtilities.invokeLater {
            scroll.viewport.scrollRectToVisible(last.bounds)
            val sb = scroll.verticalScrollBar
            sb.value = sb.maximum
        }
    }

    private fun adjustMessagePanelSize(textArea: JTextArea, messagePanel: JPanel) {
        val textMetrics = textArea.getFontMetrics(textArea.font)
        val maxWidth = 450
        val minWidth = 100
        val maxHeight = 400

        val explicitLines = textArea.text.split('\n')
        var totalLines = 0
        var maxLineWidth = 0
        for (line in explicitLines) {
            if (line.isEmpty()) {
                totalLines += 1
                continue
            }
            val lineWidth = textMetrics.stringWidth(line)
            maxLineWidth = maxOf(maxLineWidth, lineWidth)

            val availableWidth = maxWidth - 30
            if (lineWidth <= availableWidth) {
                totalLines += 1
            } else {
                val words = line.split(' ')
                var currentLineWidth = 0
                var currentLines = 1
                for (word in words) {
                    val wordWidth = textMetrics.stringWidth("$word ")
                    if (currentLineWidth + wordWidth > availableWidth) {
                        currentLines++
                        currentLineWidth = wordWidth
                    } else {
                        currentLineWidth += wordWidth
                    }
                }
                totalLines += currentLines
            }
        }

        val lineHeight = textMetrics.height
        val totalHeight = (totalLines * lineHeight + 20).coerceAtMost(maxHeight)
        val actualWidth = (maxLineWidth + 30).coerceIn(minWidth, maxWidth)

        messagePanel.preferredSize = Dimension(actualWidth, totalHeight)
        messagePanel.maximumSize = Dimension(maxWidth, totalHeight)
        messagePanel.minimumSize = Dimension(minWidth, totalHeight)
        messagePanel.revalidate()
        messagePanel.repaint()
    }

    private fun rebuildMessagePanel(oldPanel: JPanel, newText: String, onReplaced: (JPanel) -> Unit) {
        val parent = oldPanel.parent as? java.awt.Container ?: return
        val parentPanel = parent as? JPanel
        val index = parent.components.indexOf(oldPanel)
        // 기존 패널 제거
        parent.remove(oldPanel)

        // 새 패널 생성(텍스트 전체 반영)
        val newPanel = createMessagePanel(newText, false)
        parent.add(newPanel, index)

        parent.revalidate()
        parent.repaint()

        onReplaced(newPanel)
    }

    /**
     * LLM의 코드 수정 제안 응답을 파싱하고 처리합니다.
     * @param response LLM 응답 문자열
     * @param editor 현재 활성화된 에디터
     */
    private fun handleInstructionResponse(response: String, editor: Editor) {
        val document = editor.document
        val pattern = Pattern.compile("\\[Modified\\](.*)", Pattern.DOTALL)
        val matcher = pattern.matcher(response)

        if (matcher.find()) {
            var modifiedCode = matcher.group(1).trim()
            
            // 코드 블록 형태 (```language ... ```) 처리
            val codeBlockPattern = Pattern.compile("```(?:[a-zA-Z]+\\s*)?([\\s\\S]*?)```", Pattern.DOTALL)
            val codeBlockMatcher = codeBlockPattern.matcher(modifiedCode)
            if (codeBlockMatcher.find()) {
                modifiedCode = codeBlockMatcher.group(1).trim()
            }
            
            val originalCode = selectedCode ?: run {
                println("[ChatService] handleInstructionResponse: selectedCode가 null입니다.")
                sendMessage("❌ 원본 선택 영역 정보가 없습니다. 'Send Selection to Chat'으로 선택한 코드에만 적용할 수 있습니다.", isUser = false)
                return
            }
            
            println("[ChatService] handleInstructionResponse: 원본 코드 길이=${originalCode.length}자, 수정된 코드 길이=${modifiedCode.length}자")

            // 저장된 오프셋 정보 우선 사용, 없으면 파일에서 찾기
            val startOffset = selectedStartOffset
            val endOffset = selectedEndOffset
            
            if (startOffset != null && endOffset != null && selectedDocument == document) {
                // 저장된 오프셋 사용
                println("[ChatService] handleInstructionResponse: 저장된 오프셋 사용: $startOffset-$endOffset")
                
                val change = PendingChange(originalCode, modifiedCode, document, startOffset, endOffset)
                pendingChanges.add(change)

                // UI 스레드에서 하이라이트 및 Line Marker 즉시 업데이트
                ApplicationManager.getApplication().invokeLater {
                    // PSI와 문서 동기화
                    PsiDocumentManager.getInstance(project).commitDocument(document)
                    // 하이라이트 추가
                    addHighlight(editor, startOffset, endOffset)
                    // 특정 파일에 대해 코드 분석 요청
                    val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
                    if (psiFile != null) {
                        DaemonCodeAnalyzerEx.getInstanceEx(project).restart()
                    }
                    // 새 diff 창 띄우기 (선택 영역만 비교, 버튼 포함)
                    showDiffWindow(originalCode, modifiedCode, change)
                }
                sendMessage("✅ 코드 수정 제안을 받았습니다. diff 창에서 확인 후 '적용' 또는 '거절'을 선택해주세요.", isUser = false)
            } else {
                // 파일에서 찾기 (fallback)
                val fileText = document.text
                val foundOffset = fileText.indexOf(originalCode)
                if (foundOffset != -1) {
                    println("[ChatService] handleInstructionResponse: 파일에서 코드 찾음: $foundOffset")
                    val foundEndOffset = foundOffset + originalCode.length
                    
                    val change = PendingChange(originalCode, modifiedCode, document, foundOffset, foundEndOffset)
                    pendingChanges.add(change)

                    ApplicationManager.getApplication().invokeLater {
                        PsiDocumentManager.getInstance(project).commitDocument(document)
                        addHighlight(editor, foundOffset, foundEndOffset)
                        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
                        if (psiFile != null) {
                            DaemonCodeAnalyzerEx.getInstanceEx(project).restart()
                        }
                        showDiffWindow(originalCode, modifiedCode, change)
                    }
                    sendMessage("✅ 코드 수정 제안을 받았습니다. diff 창에서 확인 후 '적용' 또는 '거절'을 선택해주세요.", isUser = false)
                } else {
                    println("[ChatService] handleInstructionResponse: 원본 코드를 파일에서 찾을 수 없음")
                    sendMessage("❌ 원본 코드를 현재 파일에서 찾을 수 없습니다. 파일이 변경되었거나 코드가 정확히 일치하지 않습니다.", isUser = false)
                    sendMessage("💡 수정된 코드:\n```\n$modifiedCode\n```", isUser = false)
                }
            }
        } else {
            sendMessage("수정 제안을 파싱할 수 없습니다. 받은 응답:\n$response", isUser = false)
        }
    }

    /**
     * LLM의 커서 위치 코드 생성 응답을 파싱하고 처리합니다.
     * @param response LLM 응답 문자열
     * @param editor 현재 활성화된 에디터
     */
    private fun handleCursorCodeGenerationResponse(response: String, editor: Editor) {
        val document = editor.document
        val pattern = Pattern.compile("\\[NewCode\\](.*)", Pattern.DOTALL)
        val matcher = pattern.matcher(response)

        if (matcher.find()) {
            var generatedCode = matcher.group(1).trim()
            
            // 코드 블록 형태 (```language ... ```) 처리
            val codeBlockPattern = Pattern.compile("```(?:[a-zA-Z]+\\s*)?([\\s\\S]*?)```", Pattern.DOTALL)
            val codeBlockMatcher = codeBlockPattern.matcher(generatedCode)
            if (codeBlockMatcher.find()) {
                generatedCode = codeBlockMatcher.group(1).trim()
            }
            
            val currentCursorLine = cursorLine ?: return
            
            // 커서 위치의 라인 시작 오프셋 계산 (새 코드를 삽입할 위치)
            val insertLineIndex = currentCursorLine - 1 // 0-based index
            val insertOffset = if (insertLineIndex < document.lineCount) {
                document.getLineEndOffset(insertLineIndex)
            } else {
                document.textLength
            }

            val codeInsertion = PendingCodeInsertion(
                insertLine = currentCursorLine,
                generatedCode = generatedCode,
                document = document,
                insertOffset = insertOffset
            )
            
            pendingCodeInsertion = codeInsertion

            // 새 코드 삽입 diff 창 표시 (원본은 빈값, 수정에는 새 코드)
            ApplicationManager.getApplication().invokeLater {
                showCodeInsertionDiffWindow("", generatedCode, codeInsertion)
                sendMessage("새로운 코드가 생성되었습니다. diff 창에서 확인 후 '적용' 또는 '거절'을 선택해주세요.", isUser = false)
            }
        } else {
            sendMessage("코드 생성 제안을 파싱할 수 없습니다. 받은 응답:\n$response", isUser = false)
        }
    }

    /**
     * 원본과 수정된 코드를 Git-like diff 창으로 보여주며, 적용/거절 버튼을 포함합니다.
     * @param originalCode 원본 코드
     * @param modifiedCode 수정된 코드
     * @param change 적용/거절할 PendingChange 객체
     */
    private fun showDiffWindow(originalCode: String, modifiedCode: String, change: PendingChange) {
        val diffContentFactory = DiffContentFactory.getInstance()
        val leftContent = diffContentFactory.create(originalCode)
        val rightContent = diffContentFactory.create(modifiedCode)

        val diffRequest = SimpleDiffRequest(
            "선택 영역 변경 비교",  // 창 제목
            leftContent,           // 왼쪽: 원본 선택 영역
            rightContent,          // 오른쪽: 수정 영역
            "Original Selection",  // 왼쪽 라벨
            "Modified Selection"   // 오른쪽 라벨
        )

        // 커스텀 대화상자로 diff 창 표시
        showCustomDiffDialog(diffRequest, change)
    }

    /**
     * 적용/거절 버튼이 있는 커스텀 diff 대화상자를 표시합니다.
     */
    private fun showCustomDiffDialog(diffRequest: SimpleDiffRequest, change: PendingChange) {
        ApplicationManager.getApplication().invokeLater {
            val dialog = object : com.intellij.openapi.ui.DialogWrapper(project) {
                private var diffPanel: com.intellij.diff.DiffRequestPanel? = null
                
                init {
                    title = "코드 변경 제안"
                    init()
                }

                override fun createCenterPanel(): javax.swing.JComponent? {
                    // DialogWrapper의 disposable을 부모로 사용하여 메모리 누수 방지
                    diffPanel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
                    diffPanel?.setRequest(diffRequest)
                    return diffPanel?.component
                }

                override fun createActions(): Array<javax.swing.Action> {
                    val applyAction = object : javax.swing.AbstractAction("적용") {
                        override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                            applyChange(change)
                            sendMessage("코드 변경이 적용되었습니다.", isUser = false)
                            close(OK_EXIT_CODE)
                        }
                    }

                    val rejectAction = object : javax.swing.AbstractAction("거절") {
                        override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                            rejectChange(change)
                            sendMessage("코드 변경이 거절되었습니다.", isUser = false)
                            close(CANCEL_EXIT_CODE)
                        }
                    }

                    val cancelAction = object : javax.swing.AbstractAction("취소") {
                        override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                            close(CANCEL_EXIT_CODE)
                        }
                    }

                    return arrayOf(applyAction, rejectAction, cancelAction)
                }

                override fun getPreferredSize(): java.awt.Dimension {
                    return java.awt.Dimension(800, 600)
                }

                override fun dispose() {
                    // 명시적으로 부모의 dispose를 호출하여 리소스 정리
                    super.dispose()
                }
            }

            dialog.show()
        }
    }

    /**
     * 제안된 변경 사항을 에디터에 적용합니다.
     * @param change 적용할 PendingChange 객체
     */
    fun applyChange(change: PendingChange) {
        // WriteCommandAction을 사용하여 문서 변경
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            change.document.replaceString(change.startOffset, change.endOffset, change.modifiedCode)
        }
        pendingChanges.remove(change)
        
        // 사용량 측정: 코드 수정 기록
        val modifiedLines = change.modifiedCode.lines().size
        userService.recordCodeModification(1, modifiedLines)
        
        ApplicationManager.getApplication().invokeLater {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            editor?.markupModel?.removeAllHighlighters() // 하이라이터 제거
            com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart()
        }
    }

    /**
     * 제안된 변경 사항을 거절합니다.
     * @param change 거절할 PendingChange 객체
     */
    fun rejectChange(change: PendingChange) {
        pendingChanges.remove(change)
        ApplicationManager.getApplication().invokeLater {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            editor?.markupModel?.removeAllHighlighters() // 하이라이터 제거
            com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart()
        }
    }

    /**
     * 에디터의 특정 영역에 하이라이트를 추가합니다.
     */
    private fun addHighlight(editor: Editor, startOffset: Int, endOffset: Int) {
        val textAttributes = TextAttributes().apply {
            backgroundColor = Color(JBColor.YELLOW.red, JBColor.YELLOW.green, JBColor.YELLOW.blue, 100)
            effectColor = JBColor.GRAY
            effectType = com.intellij.openapi.editor.markup.EffectType.BOXED
        }
        editor.markupModel.addRangeHighlighter(
            startOffset,
            endOffset,
            HighlighterLayer.SELECTION - 1,
            textAttributes,
            HighlighterTargetArea.EXACT_RANGE
        )
    }
    
    /**
     * 전체 파일의 원본과 수정된 내용을 diff 창으로 보여주며, 적용/거절 버튼을 포함합니다.
     * @param originalContent 원본 파일 전체 내용
     * @param modifiedContent 수정된 파일 전체 내용
     * @param fileChange 적용/거절할 PendingFileChange 객체
     */
    private fun showFullFileDiffWindow(originalContent: String, modifiedContent: String, fileChange: PendingFileChange) {
        val diffContentFactory = DiffContentFactory.getInstance()
        val leftContent = diffContentFactory.create(originalContent)
        val rightContent = diffContentFactory.create(modifiedContent)

        val diffRequest = SimpleDiffRequest(
            "전체 파일 수정 제안: ${fileChange.fileName}",  // 창 제목
            leftContent,           // 왼쪽: 원본 파일
            rightContent,          // 오른쪽: 수정된 파일
            "Original File",       // 왼쪽 라벨
            "Modified File"        // 오른쪽 라벨
        )

        // 커스텀 대화상자로 diff 창 표시
        showCustomFullFileDiffDialog(diffRequest, fileChange)
    }

    /**
     * 전체 파일 수정을 위한 적용/거절 버튼이 있는 커스텀 diff 대화상자를 표시합니다.
     */
    private fun showCustomFullFileDiffDialog(diffRequest: SimpleDiffRequest, fileChange: PendingFileChange) {
        ApplicationManager.getApplication().invokeLater {
            val dialog = object : com.intellij.openapi.ui.DialogWrapper(project) {
                private var diffPanel: com.intellij.diff.DiffRequestPanel? = null
                
                init {
                    title = "전체 파일 수정 제안: ${fileChange.fileName}"
                    init()
                }

                override fun createCenterPanel(): javax.swing.JComponent? {
                    // DialogWrapper의 disposable을 부모로 사용하여 메모리 누수 방지
                    diffPanel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
                    diffPanel?.setRequest(diffRequest)
                    return diffPanel?.component
                }

                override fun createActions(): Array<javax.swing.Action> {
                    val applyAction = object : javax.swing.AbstractAction("전체 적용") {
                        override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                            applyFullFileChange(fileChange)
                            sendMessage("전체 파일이 성공적으로 수정되었습니다.", isUser = false)
                            close(OK_EXIT_CODE)
                        }
                    }

                    val rejectAction = object : javax.swing.AbstractAction("거절") {
                        override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                            rejectFullFileChange()
                            sendMessage("전체 파일 수정이 거절되었습니다.", isUser = false)
                            close(CANCEL_EXIT_CODE)
                        }
                    }

                    val cancelAction = object : javax.swing.AbstractAction("취소") {
                        override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                            close(CANCEL_EXIT_CODE)
                        }
                    }

                    return arrayOf(applyAction, rejectAction, cancelAction)
                }

                override fun getPreferredSize(): java.awt.Dimension {
                    return java.awt.Dimension(1000, 700) // 전체 파일이므로 더 큰 창
                }

                override fun dispose() {
                    // 명시적으로 부모의 dispose를 호출하여 리소스 정리
                    super.dispose()
                }
            }

            dialog.show()
        }
    }

    /**
     * 전체 파일 변경 사항을 에디터에 적용합니다.
     * @param fileChange 적용할 PendingFileChange 객체
     */
    private fun applyFullFileChange(fileChange: PendingFileChange) {
        // WriteCommandAction을 사용하여 전체 문서 교체
        WriteCommandAction.runWriteCommandAction(project) {
            fileChange.document.setText(fileChange.modifiedContent)
        }
        
        // 사용량 측정: 코드 수정 기록
        val modifiedLines = fileChange.modifiedContent.lines().size
        userService.recordCodeModification(1, modifiedLines)
        
        // 저장 후 정리
        pendingFileChange = null
        ApplicationManager.getApplication().invokeLater {
            com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart()
        }
    }

    /**
     * 전체 파일 변경 사항을 거절하고 원본으로 복구합니다.
     */
    private fun rejectFullFileChange() {
        val fileChange = pendingFileChange ?: return
        
        // WriteCommandAction을 사용하여 원본 문서로 복구
        WriteCommandAction.runWriteCommandAction(project) {
            fileChange.document.setText(fileChange.originalContent)
        }
        
        // 정리
        pendingFileChange = null
        ApplicationManager.getApplication().invokeLater {
            com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart()
        }
    }

    /**
     * 코드 삽입을 위한 diff 창을 표시합니다. 원본은 빈값, 수정에는 새로운 코드가 표시됩니다.
     * @param originalCode 원본 코드 (빈값)
     * @param newCode 생성된 새로운 코드
     * @param codeInsertion 적용/거절할 PendingCodeInsertion 객체
     */
    private fun showCodeInsertionDiffWindow(originalCode: String, newCode: String, codeInsertion: PendingCodeInsertion) {
        val diffContentFactory = DiffContentFactory.getInstance()
        val leftContent = diffContentFactory.create(originalCode)
        val rightContent = diffContentFactory.create(newCode)

        val diffRequest = SimpleDiffRequest(
            "새 코드 삽입 - 라인 ${codeInsertion.insertLine}",  // 창 제목
            leftContent,           // 왼쪽: 빈값 (원본 없음)
            rightContent,          // 오른쪽: 새로 생성된 코드
            "Original (Empty)",    // 왼쪽 라벨
            "New Code"             // 오른쪽 라벨
        )

        // 커스텀 대화상자로 diff 창 표시
        showCustomCodeInsertionDiffDialog(diffRequest, codeInsertion)
    }

    /**
     * 코드 삽입을 위한 적용/거절 버튼이 있는 커스텀 diff 대화상자를 표시합니다.
     */
    private fun showCustomCodeInsertionDiffDialog(diffRequest: SimpleDiffRequest, codeInsertion: PendingCodeInsertion) {
        ApplicationManager.getApplication().invokeLater {
            val dialog = object : com.intellij.openapi.ui.DialogWrapper(project) {
                private var diffPanel: com.intellij.diff.DiffRequestPanel? = null
                
                init {
                    title = "새 코드 삽입 제안 - 라인 ${codeInsertion.insertLine}"
                    init()
                }

                override fun createCenterPanel(): javax.swing.JComponent? {
                    // DialogWrapper의 disposable을 부모로 사용하여 메모리 누수 방지
                    diffPanel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
                    diffPanel?.setRequest(diffRequest)
                    return diffPanel?.component
                }

                override fun createActions(): Array<javax.swing.Action> {
                    val applyAction = object : javax.swing.AbstractAction("적용") {
                        override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                            applyCodeInsertion(codeInsertion)
                            sendMessage("새 코드가 성공적으로 삽입되었습니다.", isUser = false)
                            close(OK_EXIT_CODE)
                        }
                    }

                    val rejectAction = object : javax.swing.AbstractAction("거절") {
                        override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                            rejectCodeInsertion()
                            sendMessage("코드 삽입이 거절되었습니다.", isUser = false)
                            close(CANCEL_EXIT_CODE)
                        }
                    }

                    val cancelAction = object : javax.swing.AbstractAction("취소") {
                        override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                            close(CANCEL_EXIT_CODE)
                        }
                    }

                    return arrayOf(applyAction, rejectAction, cancelAction)
                }

                override fun getPreferredSize(): java.awt.Dimension {
                    return java.awt.Dimension(800, 600)
                }

                override fun dispose() {
                    // 명시적으로 부모의 dispose를 호출하여 리소스 정리
                    super.dispose()
                }
            }

            dialog.show()
        }
    }

    /**
     * 생성된 코드를 커서 위치에 삽입합니다.
     * @param codeInsertion 적용할 PendingCodeInsertion 객체
     */
    private fun applyCodeInsertion(codeInsertion: PendingCodeInsertion) {
        // WriteCommandAction을 사용하여 문서에 코드 삽입
        WriteCommandAction.runWriteCommandAction(project) {
            val insertText = "\n${codeInsertion.generatedCode}"
            codeInsertion.document.insertString(codeInsertion.insertOffset, insertText)
        }
        
        // 정리
        pendingCodeInsertion = null
        ApplicationManager.getApplication().invokeLater {
            com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart()
        }
    }

    /**
     * 코드 삽입을 거절합니다.
     */
    private fun rejectCodeInsertion() {
        // 단순히 정리만 수행
        pendingCodeInsertion = null
    }

    /**
     * 사용자 입력을 분류하여 적절한 처리 타입을 결정합니다.
     * @param userInput 사용자 입력 문자열
     * @return UserInputType 열거형 값
     */
    private fun classifyInput(userInput: String): UserInputType {
        val input = userInput.lowercase().trim()
        
        // 1) LLM 기반 사전 분류 시도 (NEW_SOURCE / RAG_QUESTION / GENERAL_QUESTION)
        try {
            val llmType = classifyWithLLM(userInput)
            if (llmType != null) {
                return llmType
            }
        } catch (_: Exception) {
            // 네트워크 오류나 파싱 오류 시 무시하고 휴리스틱으로 폴백
        }
        /**
        
        // 파일 경로/위치 질문 먼저 감지 (우선순위 높음)
        // 단, 파일 생성/수정 동사가 함께 있으면 질문이 아닌 작업 요청으로 분류
        val filePathQuestionKeywords = listOf(
            "경로 알려", "위치 알려", "어디 있", "어디에 있", "파일 찾",
            "파일 경로", "파일 위치", "파일이 어디", "file path", "file location",
            "경로 뭐", "위치 뭐", "어디야", "찾아줘"
        )
        
        val filePathQuestionPatterns = listOf(
            ".*\\.(java|kt|vue|xml|json).*경로.*[?알려뭐어디]", 
            ".*\\.(java|kt|vue|xml|json).*위치.*[?알려뭐어디]",
            ".*\\.(java|kt|vue|xml|json).*어디.*[?있는지]",
            ".*경로.*\\.(java|kt|vue|xml|json).*[?알려뭐]",
            ".*위치.*\\.(java|kt|vue|xml|json).*[?알려뭐]",
            ".*어디.*\\.(java|kt|vue|xml|json).*[?있는지]"
        )
        
        val hasFilePathQuestion = filePathQuestionKeywords.any { keyword ->
            input.contains(keyword)
        } || filePathQuestionPatterns.any { pattern ->
            Regex(pattern).containsMatchIn(input)
        }
        
        // 파일 생성/수정 동사가 있으면 질문이 아님
        val hasActionVerb = listOf("생성", "만들", "작성", "create", "make", "generate", "write", "수정", "변경", "편집", "modify", "edit", "change", "update").any { verb ->
            input.contains(verb)
        }
        
        if (hasFilePathQuestion && !hasActionVerb) {
            return UserInputType.RAG_QUESTION
        }

        // 새 파일 생성 요청 감지
        val fileCreationKeywords = listOf(
            "파일 생성", "파일 만들어", "새 파일", "파일 작성", "파일 만들",
            "만들어줘", "만들어달라", "생성해줘", "작성해줘",
            "create file", "new file", "generate file", "make file"
        )

        val fileCreationPatterns = listOf(
            ".*\\.java.*생성", ".*\\.kt.*생성", ".*\\.vue.*생성", ".*\\.xml.*생성", ".*\\.json.*생성",
            ".*\\.java.*만들", ".*\\.kt.*만들", ".*\\.vue.*만들", ".*\\.xml.*만들", ".*\\.json.*만들",
            ".*\\.java.*작성", ".*\\.kt.*작성", ".*\\.vue.*작성", ".*\\.xml.*작성", ".*\\.json.*작성",
            ".*생성.*\\.java", ".*생성.*\\.kt", ".*생성.*\\.vue", ".*생성.*\\.xml", ".*생성.*\\.json",
            ".*만들.*\\.java", ".*만들.*\\.kt", ".*만들.*\\.vue", ".*만들.*\\.xml", ".*만들.*\\.json",
            ".*작성.*\\.java", ".*작성.*\\.kt", ".*작성.*\\.vue", ".*작성.*\\.xml", ".*작성.*\\.json",
            ".*(java|kt|vue|xml|json)\\s+파일.*만들", ".*(java|kt|vue|xml|json)\\s+파일.*생성", ".*(java|kt|vue|xml|json)\\s+파일.*작성"
        )

        val hasFileCreationKeyword = fileCreationKeywords.any { keyword ->
            input.contains(keyword)
        }

        val hasFileCreationPattern = fileCreationPatterns.any { pattern ->
            Regex(pattern).containsMatchIn(input)
        }

        // 파일 생성 관련 동사가 명시적으로 포함된 경우만 파일 생성으로 분류
        val hasCreationVerb = listOf("생성", "만들", "작성", "create", "make", "generate", "write").any { verb ->
            input.contains(verb)
        }

        // 파일 확장자와 생성 동사가 함께 있는 경우도 파일 생성으로 분류
        val hasFileExtensionWithCreation = Regex(".*\\.(java|kt|vue|xml|json).*").containsMatchIn(input) && hasCreationVerb

        if (hasFileCreationKeyword || hasFileCreationPattern || hasFileExtensionWithCreation) {
            return UserInputType.FILE_CREATION
        }

        // 외부 파일 수정 요청 감지 (수정 동사가 명시적으로 포함된 경우만)
        val externalFileKeywords = listOf(
            "파일 수정", "파일 변경", "파일 편집",
            "modify file", "edit file", "change file", "update file"
        )

        val pathPatterns = listOf(
            ".*[/\\\\].*\\.(java|kt|vue|xml|json).*", // 경로가 포함된 파일명
            ".*ㄱ/.*", // Unix 스타일 경로(주석 풀 때 ㄱ 제거 필요)
            ".*\\\\.*" // Windows 스타일 경로
        )

        val hasExternalFileKeyword = externalFileKeywords.any { keyword ->
            input.contains(keyword)
        }

        val hasPathPattern = pathPatterns.any { pattern ->
            Regex(pattern).containsMatchIn(input)
        }

        // 수정 관련 동사가 명시적으로 포함된 경우만 외부 파일 수정으로 분류
        val hasModificationVerb = listOf("수정", "변경", "편집", "modify", "edit", "change", "update", "fix").any { verb ->
            input.contains(verb)
        }

        if ((hasExternalFileKeyword || (hasPathPattern && hasModificationVerb)) && selectedCode == null) {
            return UserInputType.EXTERNAL_FILE_EDIT
        }

        // 커서 위치 기반 코드 생성 요청 감지
        if (cursorLine != null && (
            input.contains("생성") || input.contains("만들어") || input.contains("작성") || 
            input.contains("추가") || input.contains("create") || input.contains("generate") ||
            input.contains("코드") && (input.contains("새로") || input.contains("new"))
        )) {
            return UserInputType.CURSOR_CODE_GENERATION
        }
        
        // 코드 수정/개선 지시 감지 (선택된 코드가 있는 경우)
        if (selectedCode != null && (
            input.contains("수정") || input.contains("개선") || input.contains("바꿔") || 
            input.contains("변경") || input.contains("고쳐") || input.contains("refactor") ||
            input.contains("modify") || input.contains("change") || input.contains("fix")
        )) {
            return UserInputType.INSTRUCTION
        }
        **/

        // 코드베이스 관련 질문 키워드 감지
        val codebaseQuestionKeywords = listOf(
            "어떻게", "어디서", "무엇", "언제", "왜",
            "how", "where", "what", "when", "why",
            "함수", "메서드", "클래스", "변수", "필드",
            "구현", "작동", "동작", "처리", "사용",
            "프로젝트", "코드", "파일", "로직",
            "explain", "show", "find", "search"
        )
        
        val hasCodebaseKeyword = codebaseQuestionKeywords.any { keyword ->
            input.contains(keyword)
        }
        
        // 질문형 패턴 감지
        val questionPatterns = listOf(
            "\\?$", "\\?\\s*$",  // 물음표로 끝남
            "^어떻게", "^어디", "^무엇", "^언제", "^왜",
            "^how", "^where", "^what", "^when", "^why"
        )
        
        val hasQuestionPattern = questionPatterns.any { pattern ->
            Regex(pattern).containsMatchIn(input)
        }
        
        // 코드베이스 관련 질문으로 분류
        if (hasCodebaseKeyword || hasQuestionPattern) {
            return UserInputType.RAG_QUESTION
        }
        
        // 기본값은 일반 질문
        return UserInputType.GENERAL_QUESTION
    }

    // LLM 기반 입력 분류 헬퍼: LLM이 정확히 하나의 토큰을 반환하도록 강제
    // 반환: FILE_CREATION / CURSOR_CODE_GENERATION / RAG_QUESTION / GENERAL_QUESTION 중 하나, 실패 시 null
    private fun classifyWithLLM(userInput: String): UserInputType? {
        val systemPrompt = """
            당신은 입력의 의도를 분류하는 분류기입니다.
            아래 세 가지 중 하나만 출력하세요. 다른 말은 절대 하지 마세요.
            NEW_SOURCE  |  RAG_QUESTION  |  GENERAL_QUESTION

            분류 기준:
            - NEW_SOURCE: 새 파일/클래스/함수/컴포넌트 생성이나 새로운 기능 추가 요청
            - RAG_QUESTION: 현재 프로젝트 코드/파일/함수/경로/구현에 대해 묻는 질문
            - GENERAL_QUESTION: 프로젝트 특정 코드 맥락 없이 일반 지식/조언/설명

            출력 형식: 위 토큰 중 정확히 하나만. 공백/마크다운/설명 불가.
        """.trimIndent()

        val response = try {
            val future = ApplicationManager.getApplication().executeOnPooledThread<String?> {
                apiClient.sendChatRequest(userInput, systemPrompt)
            }
            future.get()
        } catch (_: Exception) { null } ?: return null
        val token = response.trim().uppercase()
        return when (token) {
            "RAG_QUESTION" -> UserInputType.RAG_QUESTION
            "GENERAL_QUESTION" -> UserInputType.GENERAL_QUESTION
            //"NEW_SOURCE" -> mapNewSourceToConcreteType(userInput)
            "NEW_SOURCE" -> UserInputType.NEW_SOURCE
            else -> null
        }
    }

   /* // NEW_SOURCE를 실제 처리 타입으로 구체화
    private fun mapNewSourceToConcreteType(userInput: String): UserInputType {
        val s = userInput.lowercase()

        val creationVerbs = listOf(
            "생성", "만들", "작성", "추가", "implement", "create", "generate", "add", "scaffold",
            "보일러", "boilerplate", "template", "템플릿", "from scratch"
        )
        val mentionsCreation = creationVerbs.any { s.contains(it) }

        val cursorHints = listOf("여기에", "현재 위치", "커서 위치", "이 줄", "cursor", "at the cursor")
        val mentionsCursor = cursorHints.any { s.contains(it) } || (cursorLine != null && mentionsCreation)

        val fileExtPattern = Regex("\\b[\\w-]+\\.(kt|java|xml|json|yml|yaml|vue|tsx|ts|js)\\b")
        val mentionsExplicitFile = fileExtPattern.containsMatchIn(s) || (s.contains("파일") && mentionsCreation)

        return when {
            mentionsCursor -> UserInputType.CURSOR_CODE_GENERATION
            mentionsExplicitFile -> UserInputType.FILE_CREATION
            else -> if (cursorLine != null) UserInputType.CURSOR_CODE_GENERATION else UserInputType.FILE_CREATION
        }
    }*/
    
    /**
     * 인덱싱된 코드에서 사용자 질문과 관련된 코드 조각들을 검색합니다.
     * @param query 검색 쿼리 (사용자 질문)
     * @param limit 반환할 최대 결과 수
     * @return 관련성 높은 순으로 정렬된 코드 조각 리스트
     */
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
    
    /**
     * 검색 쿼리에서 핵심 검색어들을 추출합니다.
     * @param query 원본 쿼리
     * @return 검색어 리스트
     */
    private fun extractSearchTerms(query: String): List<String> {
        val stopWords = setOf(
            "어떻게", "어디서", "무엇을", "언제", "왜", "그", "이", "그것", "그런", "이런",
            "하는", "있는", "되는", "되어", "에서", "에게", "으로", "를", "을", "가", "이", "은", "는",
            "how", "where", "what", "when", "why", "the", "a", "an", "is", "are", "was", "were",
            "do", "does", "did", "can", "could", "should", "would", "will", "have", "has", "had"
        )
        
        return query.lowercase()
            .split(Regex("\\W+")) // 단어가 아닌 문자로 분할
            .filter { it.length > 2 && !stopWords.contains(it) } // 불용어 제거 및 짧은 단어 제거
            .distinct()
    }
    
    /**
     * 코드 조각과 검색어들 간의 관련성 점수를 계산합니다.
     * @param chunk 코드 조각
     * @param queryTerms 검색어 리스트
     * @return 관련성 점수 (0~100)
     */
    private fun calculateRelevanceScore(chunk: CodeChunk, queryTerms: List<String>): Double {
        if (queryTerms.isEmpty()) return 0.0
        
        var score = 0.0
        val content = chunk.content.lowercase()
        val signature = chunk.signature.lowercase()
        val summary = chunk.summary.lowercase()
        val fileName = chunk.fileName.lowercase()
        
        for (term in queryTerms) {
            val termLower = term.lowercase()
            
            // 시그니처에서 발견되면 높은 점수
            if (signature.contains(termLower)) {
                score += 15.0
            }
            
            // 파일명에서 발견되면 중간 점수
            if (fileName.contains(termLower)) {
                score += 10.0
            }
            
            // 요약에서 발견되면 중간 점수
            if (summary.contains(termLower)) {
                score += 8.0
            }
            
            // 코드 내용에서 발견되면 기본 점수
            if (content.contains(termLower)) {
                score += 5.0
            }
            
            // 정확한 단어 매치에 대한 보너스
            if (content.contains("\\b$termLower\\b".toRegex())) {
                score += 3.0
            }
        }
        
        // 코드 타입에 따른 가중치
        val typeWeight = when (chunk.type) {
            CodeType.CLASS -> 1.2
            CodeType.METHOD -> 1.1
            CodeType.INTERFACE -> 1.1
            CodeType.FILE -> 0.8
            else -> 1.0
        }
        
        return score * typeWeight
    }
    
    /**
     * 프로젝트 인덱싱 정보를 기반으로 적절한 파일 경로를 추천합니다.
     * @param fileName 생성할 파일 이름
     * @param fileExtension 파일 확장자
     * @return 추천 경로 리스트 (관련성 높은 순으로 정렬)
     */
    fun suggestFilePaths(fileName: String, fileExtension: String): List<String> {
        val suggestions = mutableListOf<String>()
        
        // 1. 인덱싱된 파일들에서 동일한 확장자의 파일들이 위치한 디렉토리 분석
        val indexedDirectories = analyzeIndexedDirectories(fileExtension)
        
        // 2. 파일명에서 클래스/컴포넌트명 추출하여 관련 파일들과 비슷한 위치 찾기
        val relatedDirectories = findRelatedDirectories(fileName, fileExtension)
        
        // 3. 인덱싱 기반 추천 경로들 (우선순위 높음)
        suggestions.addAll(indexedDirectories)
        suggestions.addAll(relatedDirectories)
        
        // 4. 기본 프로젝트 구조 기반 추천 (fallback)
        val fallbackSuggestions = getFallbackSuggestions(fileName, fileExtension)
        suggestions.addAll(fallbackSuggestions)
        
        return suggestions.distinct()
    }
    
    /**
     * 인덱싱된 파일들을 분석하여 동일한 확장자의 파일들이 주로 위치하는 디렉토리를 찾습니다.
     * @param fileExtension 파일 확장자
     * @return 추천 디렉토리 리스트 (프로젝트 루트 기준 상대 경로, 빈도수 높은 순)
     */
    private fun analyzeIndexedDirectories(fileExtension: String): List<String> {
        val allChunks = codeIndexingService.getAllCodeChunks()
        val directoryFrequency = mutableMapOf<String, Int>()
        
        // 동일한 확장자의 파일들이 위치한 디렉토리 분석
        allChunks.filter { chunk ->
            chunk.type == CodeType.FILE && 
            chunk.fileName.endsWith(".$fileExtension", ignoreCase = true)
        }.forEach { chunk ->
            // 프로젝트 루트 기준 상대 경로로 변환
            val absoluteDirectory = chunk.filePath.substringBeforeLast('/')
            val relativeDirectory = toProjectRelativePath(absoluteDirectory)
            directoryFrequency[relativeDirectory] = directoryFrequency.getOrDefault(relativeDirectory, 0) + 1
        }
        
        // 빈도수가 높은 순으로 정렬하여 상위 5개 디렉토리 반환
        return directoryFrequency.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { "${it.key}/" }
            .map { it.replace("//", "/") } // 중복 슬래시 제거
    }
    
    /**
     * 파일명과 관련된 파일들이 위치하는 디렉토리를 찾습니다.
     * @param fileName 생성할 파일 이름
     * @param fileExtension 파일 확장자
     * @return 관련 디렉토리 리스트 (프로젝트 루트 기준 상대 경로)
     */
    private fun findRelatedDirectories(fileName: String, fileExtension: String): List<String> {
        val allChunks = codeIndexingService.getAllCodeChunks()
        val relatedDirectories = mutableSetOf<String>()
        
        // 파일명에서 키워드 추출 (클래스명, 컴포넌트명 등)
        val keywords = extractFileNameKeywords(fileName)
        
        // 관련 키워드가 포함된 파일들의 디렉토리 찾기
        allChunks.forEach { chunk ->
            if (chunk.type == CodeType.FILE) {
                val hasRelatedKeyword = keywords.any { keyword ->
                    chunk.fileName.contains(keyword, ignoreCase = true) ||
                    chunk.signature.contains(keyword, ignoreCase = true) ||
                    chunk.content.contains(keyword, ignoreCase = true)
                }
                
                if (hasRelatedKeyword) {
                    // 프로젝트 루트 기준 상대 경로로 변환
                    val absoluteDirectory = chunk.filePath.substringBeforeLast('/')
                    val relativeDirectory = toProjectRelativePath(absoluteDirectory)
                    relatedDirectories.add("$relativeDirectory/".replace("//", "/"))
                }
            }
        }
        
        return relatedDirectories.toList()
    }
    
    /**
     * 파일명에서 키워드를 추출합니다.
     * @param fileName 파일명
     * @return 키워드 리스트
     */
    private fun extractFileNameKeywords(fileName: String): List<String> {
        val baseName = fileName.substringBeforeLast('.')
        val keywords = mutableListOf<String>()
        
        // CamelCase 분할 (예: UserService -> [User, Service])
        val camelCaseWords = baseName.split(Regex("(?=[A-Z])")).filter { it.isNotEmpty() }
        keywords.addAll(camelCaseWords)
        
        // 언더스코어/하이픈 분할
        keywords.addAll(baseName.split(Regex("[-_]")).filter { it.isNotEmpty() })
        
        // 전체 파일명도 포함
        keywords.add(baseName)
        
        return keywords.distinct().filter { it.length > 2 }
    }
    
    /**
     * 패키지 구조를 분석하여 적절한 패키지 경로를 추천합니다.
     * @param className 클래스명
     * @param fileExtension 파일 확장자
     * @return 추천 패키지 경로 리스트
     */
    fun suggestPackagePaths(className: String, fileExtension: String): List<String> {
        if (fileExtension !in listOf("java", "kt")) {
            return emptyList()
        }
        
        val allChunks = codeIndexingService.getAllCodeChunks()
        val packagePatterns = mutableMapOf<String, Int>()
        
        // 기존 클래스들의 패키지 패턴 분석
        allChunks.filter { it.type == CodeType.CLASS }.forEach { chunk ->
            val packageName = extractPackageName(chunk.filePath)
            if (packageName != null) {
                packagePatterns[packageName] = packagePatterns.getOrDefault(packageName, 0) + 1
            }
        }
        
        // 클래스명에서 유추되는 패키지 구조
        val suggestedPackages = mutableListOf<String>()
        
        // 일반적인 패키지 패턴들
        val commonPatterns = when {
            className.endsWith("Service") -> listOf("service", "services")
            className.endsWith("Controller") -> listOf("controller", "controllers", "web")
            className.endsWith("Repository") -> listOf("repository", "repositories", "dao")
            className.endsWith("Entity") -> listOf("entity", "entities", "model", "domain")
            className.endsWith("Config") -> listOf("config", "configuration")
            className.endsWith("Component") -> listOf("component", "components")
            className.endsWith("Util") || className.endsWith("Utils") -> listOf("util", "utils")
            else -> listOf("common", "core")
        }
        
        // 기존 패키지 중에서 패턴과 매칭되는 것들 찾기
        packagePatterns.keys.forEach { existingPackage ->
            commonPatterns.forEach { pattern ->
                if (existingPackage.contains(pattern)) {
                    suggestedPackages.add(existingPackage)
                }
            }
        }
        
        // 빈도수가 높은 패키지들도 추가
        suggestedPackages.addAll(
            packagePatterns.entries
                .sortedByDescending { it.value }
                .take(3)
                .map { it.key }
        )
        
        return suggestedPackages.distinct()
    }
    
    /**
     * 기본 프로젝트 구조를 기반으로 한 fallback 제안입니다.
     * @param fileName 파일명
     * @param fileExtension 파일 확장자
     * @return fallback 경로 리스트 (프로젝트 루트 기준 상대 경로)
     */
    private fun getFallbackSuggestions(fileName: String, fileExtension: String): List<String> {
        val suggestions = mutableListOf<String>()
        val projectRoots = ProjectRootManager.getInstance(project).contentSourceRoots
        
        for (root in projectRoots) {
            when (fileExtension.lowercase()) {
                "java" -> {
                    val javaDir = findOrCreateSubDirectory(root, "src/main/java")
                    if (javaDir != null) {
                        val relativePath = toProjectRelativePath("${javaDir.path}/$fileName")
                        suggestions.add(relativePath)
                    }
                }
                "kt" -> {
                    val kotlinDir = findOrCreateSubDirectory(root, "src/main/kotlin")
                    if (kotlinDir != null) {
                        val relativePath = toProjectRelativePath("${kotlinDir.path}/$fileName")
                        suggestions.add(relativePath)
                    }
                }
                "vue" -> {
                    val componentsDir = findOrCreateSubDirectory(root, "src/components")
                    val viewsDir = findOrCreateSubDirectory(root, "src/views")
                    if (componentsDir != null) {
                        val relativePath = toProjectRelativePath("${componentsDir.path}/$fileName")
                        suggestions.add(relativePath)
                    }
                    if (viewsDir != null) {
                        val relativePath = toProjectRelativePath("${viewsDir.path}/$fileName")
                        suggestions.add(relativePath)
                    }
                }
                "xml" -> {
                    val resourcesDir = findOrCreateSubDirectory(root, "src/main/resources")
                    if (resourcesDir != null) {
                        val relativePath = toProjectRelativePath("${resourcesDir.path}/$fileName")
                        suggestions.add(relativePath)
                    }
                }
                "json" -> {
                    val rootRelativePath = toProjectRelativePath("${root.path}/$fileName")
                    suggestions.add(rootRelativePath)
                    val configDir = findOrCreateSubDirectory(root, "config")
                    if (configDir != null) {
                        val relativePath = toProjectRelativePath("${configDir.path}/$fileName")
                        suggestions.add(relativePath)
                    }
                }
                else -> {
                    val rootRelativePath = toProjectRelativePath("${root.path}/$fileName")
                    suggestions.add(rootRelativePath)
                }
            }
        }
        
        return suggestions
    }
    
    /**
     * 프로젝트 구조 정보를 구축하여 LLM에게 제공합니다.
     * @return 프로젝트 구조 정보 문자열
     */
    private fun buildProjectStructureInfo(): String {
        val allChunks = codeIndexingService.getAllCodeChunks()
        if (allChunks.isEmpty()) {
            return "현재 프로젝트는 인덱싱되지 않았습니다. 기본 구조를 사용하세요."
        }
        
        val structureBuilder = StringBuilder()
        structureBuilder.appendLine("=== 현재 프로젝트 구조 정보 ===")
        structureBuilder.appendLine()
        
        // 1. 프로젝트 통계
        val stats = codeIndexingService.getIndexingStats()
        structureBuilder.appendLine("📊 프로젝트 통계:")
        structureBuilder.appendLine("  • 전체 파일: ${stats["file"] ?: 0}개")
        structureBuilder.appendLine("  • Java/Kotlin 클래스: ${stats["class"] ?: 0}개")
        structureBuilder.appendLine("  • 메서드: ${stats["method"] ?: 0}개")
        structureBuilder.appendLine()
        
        // 2. 디렉토리 구조 분석
        val directoryStructure = analyzeDirectoryStructure(allChunks)
        structureBuilder.appendLine("📁 주요 디렉토리 구조:")
        directoryStructure.forEach { (dir, count) ->
            structureBuilder.appendLine("  • $dir ($count 파일)")
        }
        structureBuilder.appendLine()
        
        // 3. 패키지 구조 분석 (Java/Kotlin)
        val packageStructure = analyzePackageStructure(allChunks)
        if (packageStructure.isNotEmpty()) {
            structureBuilder.appendLine("📦 기존 패키지 구조:")
            packageStructure.forEach { (pkg, count) ->
                structureBuilder.appendLine("  • $pkg ($count 클래스)")
            }
            structureBuilder.appendLine()
        }
        
        // 4. 네이밍 패턴 분석
        val namingPatterns = analyzeNamingPatterns(allChunks)
        if (namingPatterns.isNotEmpty()) {
            structureBuilder.appendLine("🏷️ 기존 네이밍 패턴:")
            namingPatterns.forEach { pattern ->
                structureBuilder.appendLine("  • $pattern")
            }
            structureBuilder.appendLine()
        }
        
        return structureBuilder.toString()
    }
    
    /**
     * 디렉토리 구조를 분석합니다.
     * @param chunks 인덱싱된 코드 조각들
     * @return 디렉토리별 파일 수 맵
     */
    private fun analyzeDirectoryStructure(chunks: Collection<CodeChunk>): Map<String, Int> {
        return chunks.filter { it.type == CodeType.FILE }
            .groupingBy { chunk ->
                val dir = chunk.filePath.substringBeforeLast('/')
                // 프로젝트 루트 제거하고 상대 경로로 변환
                dir.substringAfterLast("semasChatbot")
                    .removePrefix("/")
                    .ifEmpty { "프로젝트 루트" }
            }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
            .take(10)
            .toMap()
    }
    
    /**
     * 패키지 구조를 분석합니다.
     * @param chunks 인덱싱된 코드 조각들
     * @return 패키지별 클래스 수 맵
     */
    private fun analyzePackageStructure(chunks: Collection<CodeChunk>): Map<String, Int> {
        return chunks.filter { it.type == CodeType.CLASS }
            .mapNotNull { chunk ->
                extractPackageName(chunk.filePath)
            }
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
            .take(8)
            .toMap()
    }
    
    /**
     * 네이밍 패턴을 분석합니다.
     * @param chunks 인덱싱된 코드 조각들
     * @return 네이밍 패턴 리스트
     */
    private fun analyzeNamingPatterns(chunks: Collection<CodeChunk>): List<String> {
        val patterns = mutableListOf<String>()
        val classChunks = chunks.filter { it.type == CodeType.CLASS }
        
        // 일반적인 접미사 패턴 분석
        val suffixCounts = mutableMapOf<String, Int>()
        classChunks.forEach { chunk ->
            val className = chunk.signature.substringAfterLast('.')
            val commonSuffixes = listOf("Service", "Controller", "Repository", "Entity", "Config", "Component", "Util", "Manager", "Handler", "Provider")
            commonSuffixes.forEach { suffix ->
                if (className.endsWith(suffix)) {
                    suffixCounts[suffix] = suffixCounts.getOrDefault(suffix, 0) + 1
                }
            }
        }
        
        suffixCounts.filter { it.value >= 2 }
            .toList()
            .sortedByDescending { it.second }
            .forEach { (suffix, count) ->
                patterns.add("${suffix} 클래스 (${count}개)")
            }
        
        return patterns
    }
    
    /**
     * 여러 경로 제안 중에서 가장 적절한 경로를 선택합니다.
     * @param suggestions 경로 제안 리스트
     * @param className 클래스명
     * @param fileExtension 파일 확장자
     * @return 선택된 최적 경로
     */
    private fun selectBestPath(suggestions: List<String>, className: String, fileExtension: String): String? {
        if (suggestions.isEmpty()) return null
        if (suggestions.size == 1) return suggestions.first()
        
        // 클래스명 기반 우선순위 점수 계산
        val scoredSuggestions = suggestions.map { path ->
            var score = 0.0
            
            // 패키지 네이밍 패턴과 매칭되는지 확인
            when {
                className.endsWith("Service") && path.contains("service") -> score += 10.0
                className.endsWith("Controller") && (path.contains("controller") || path.contains("web")) -> score += 10.0
                className.endsWith("Repository") && (path.contains("repository") || path.contains("dao")) -> score += 10.0
                className.endsWith("Entity") && (path.contains("entity") || path.contains("model") || path.contains("domain")) -> score += 10.0
                className.endsWith("Config") && path.contains("config") -> score += 10.0
                className.endsWith("Component") && path.contains("component") -> score += 10.0
                className.endsWith("Util") && path.contains("util") -> score += 10.0
            }
            
            // 파일 확장자와 디렉토리 구조 매칭
            when (fileExtension.lowercase()) {
                "java" -> if (path.contains("src/main/java")) score += 5.0
                "kt" -> if (path.contains("src/main/kotlin")) score += 5.0
                "vue" -> if (path.contains("components") || path.contains("views")) score += 5.0
                "xml" -> if (path.contains("resources")) score += 5.0
            }
            
            // 경로 깊이 - 적당한 깊이가 좋음 (너무 얕거나 깊지 않은)
            val depth = path.count { it == '/' }
            score += when (depth) {
                in 3..5 -> 3.0
                in 6..8 -> 1.0
                else -> 0.0
            }
            
            Pair(path, score)
        }
        
        // 가장 높은 점수의 경로 반환
        return scoredSuggestions
            .sortedByDescending { it.second }
            .firstOrNull()?.first
    }
    
    /**
     * 주어진 루트 디렉토리에서 하위 디렉토리를 찾습니다.
     * @param root 루트 디렉토리
     * @param relativePath 상대 경로
     * @return 찾은 디렉토리 또는 null
     */
    private fun findOrCreateSubDirectory(root: VirtualFile, relativePath: String): VirtualFile? {
        val pathParts = relativePath.split("/")
        var current = root
        
        for (part in pathParts) {
            val child = current.findChild(part)
            if (child != null && child.isDirectory) {
                current = child
            } else {
                return null // 디렉토리가 존재하지 않음
            }
        }
        
        return current
    }
    
    /**
     * 파일 경로의 유효성을 검증합니다.
     * @param filePath 검증할 파일 경로
     * @return 유효성 검증 결과와 에러 메시지
     */
    fun validateFilePath(filePath: String): Pair<Boolean, String?> {
        try {
            val file = File(filePath)
            val parentDir = file.parentFile
            
            // 부모 디렉토리가 존재하는지 확인
            if (parentDir != null && !parentDir.exists()) {
                return Pair(false, "부모 디렉토리가 존재하지 않습니다: ${parentDir.path}")
            }
            
            // 파일이 이미 존재하는지 확인
            if (file.exists()) {
                return Pair(false, "파일이 이미 존재합니다: $filePath")
            }
            
            // 파일명이 유효한지 확인
            if (file.name.isEmpty() || file.name.contains(Regex("[<>:\"|?*]"))) {
                return Pair(false, "유효하지 않은 파일명입니다: ${file.name}")
            }
            
            // 쓰기 권한이 있는지 확인
            if (parentDir != null && !parentDir.canWrite()) {
                return Pair(false, "디렉토리에 쓰기 권한이 없습니다: ${parentDir.path}")
            }
            
            return Pair(true, null)
        } catch (e: Exception) {
            return Pair(false, "경로 검증 중 오류 발생: ${e.message}")
        }
    }
    
    /**
     * 파일 확장자를 기반으로 적절한 템플릿 타입을 결정합니다.
     * @param fileName 파일 이름
     * @return 추천되는 템플릿 타입
     */
    fun determineTemplateType(fileName: String): FileTemplateType {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        
        return when (extension) {
            "java" -> {
                when {
                    fileName.contains("Interface", ignoreCase = true) -> FileTemplateType.JAVA_INTERFACE
                    fileName.contains("Enum", ignoreCase = true) -> FileTemplateType.JAVA_ENUM
                    else -> FileTemplateType.JAVA_CLASS
                }
            }
            "kt" -> FileTemplateType.KOTLIN_CLASS
            "vue" -> FileTemplateType.VUE_COMPONENT
            "xml" -> FileTemplateType.XML_CONFIG
            "json" -> FileTemplateType.JSON_CONFIG
            else -> FileTemplateType.PLAIN_TEXT
        }
    }
    
    /**
     * Java/Kotlin 파일의 패키지명을 경로에서 추출합니다.
     * @param filePath 파일 경로
     * @return 패키지명 또는 null
     */
    fun extractPackageName(filePath: String): String? {
        try {
            val normalizedPath = filePath.replace("\\", "/")
            
            // src/main/java 또는 src/main/kotlin 패턴 찾기
            val javaIndex = normalizedPath.indexOf("src/main/java/")
            val kotlinIndex = normalizedPath.indexOf("src/main/kotlin/")
            
            val baseIndex = when {
                javaIndex >= 0 -> javaIndex + "src/main/java/".length
                kotlinIndex >= 0 -> kotlinIndex + "src/main/kotlin/".length
                else -> return null
            }
            
            val packagePath = normalizedPath.substring(baseIndex)
            val packageDir = packagePath.substringBeforeLast('/')
            
            return if (packageDir.isNotEmpty()) {
                packageDir.replace('/', '.')
            } else {
                null
            }
        } catch (e: Exception) {
            return null
        }
    }
    
    /**
     * 지정된 경로의 파일을 열어서 내용을 읽습니다.
     * @param filePath 읽을 파일의 경로
     * @return 파일 내용과 VirtualFile 객체의 Pair, 실패시 null
     */
    fun readExternalFile(filePath: String): Pair<String, VirtualFile>? {
        try {
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath.replace("\\", "/"))
            if (virtualFile != null && virtualFile.exists() && !virtualFile.isDirectory) {
                val charset = try { virtualFile.charset } catch (_: Exception) { Charsets.UTF_8 }
                val content = virtualFile.inputStream.use { input ->
                    input.reader(charset).buffered().use { reader ->
                        val buf = CharArray(8192)
                        val sb = StringBuilder()
                        while (true) {
                            val n = reader.read(buf)
                            if (n <= 0) break
                            sb.append(buf, 0, n)
                        }
                        sb.toString()
                    }
                }
                return Pair(content, virtualFile)
            }
        } catch (e: Exception) {
            sendMessage("파일을 읽는 중 오류가 발생했습니다: ${e.message}", isUser = false)
        }
        return null
    }
    
    /**
     * 프로젝트 루트 경로를 가져옵니다.
     * @return 프로젝트 루트 절대 경로
     */
    private fun getProjectRootPath(): String? {
        return project.basePath
    }
    
    /**
     * 상대 경로를 프로젝트 루트 기준 절대 경로로 변환합니다.
     * @param relativePath 상대 경로
     * @return 절대 경로
     */
    private fun resolveProjectPath(relativePath: String): String? {
        val projectRoot = getProjectRootPath() ?: return null
        val normalizedRelativePath = relativePath.replace("\\", "/")
        
        // 이미 절대 경로인 경우 (프로젝트 루트로 시작하는 경우)
        if (normalizedRelativePath.startsWith(projectRoot)) {
            return normalizedRelativePath
        }
        
        // 상대 경로를 프로젝트 루트 기준으로 변환
        return "$projectRoot/$normalizedRelativePath".replace("//", "/")
    }
    
    /**
     * 절대 경로를 프로젝트 루트 기준 상대 경로로 변환합니다.
     * @param absolutePath 절대 경로
     * @return 상대 경로
     */
    private fun toProjectRelativePath(absolutePath: String): String {
        val projectRoot = getProjectRootPath() ?: return absolutePath
        val normalizedAbsolutePath = absolutePath.replace("\\", "/")
        val normalizedProjectRoot = projectRoot.replace("\\", "/")
        
        return if (normalizedAbsolutePath.startsWith(normalizedProjectRoot)) {
            normalizedAbsolutePath.removePrefix(normalizedProjectRoot).removePrefix("/")
        } else {
            normalizedAbsolutePath
        }
    }
    
    /**
     * 새로운 파일을 생성합니다.
     * @param filePath 생성할 파일의 경로 (프로젝트 루트 기준 상대 경로 또는 절대 경로)
     * @param content 파일 내용
     * @return 성공시 생성된 VirtualFile, 실패시 null
     */
    fun createNewFile(filePath: String, content: String): VirtualFile? {
        try {
            // 디버깅 로그 추가
            sendMessage("🔧 파일 생성 시작: $filePath", isUser = false)
            
            // 프로젝트 루트 기준 절대 경로로 변환
            val absolutePath = resolveProjectPath(filePath)
            if (absolutePath == null) {
                sendMessage("❌ 프로젝트 루트 경로를 찾을 수 없습니다.", isUser = false)
                return null
            }
            
            sendMessage("🔧 절대 경로: $absolutePath", isUser = false)
            
            val normalizedPath = absolutePath.replace("\\", "/")
            val parentPath = normalizedPath.substringBeforeLast('/')
            val fileName = normalizedPath.substringAfterLast('/')
            
            sendMessage("🔧 부모 경로: $parentPath", isUser = false)
            sendMessage("🔧 파일명: $fileName", isUser = false)
            
            // 부모 디렉토리 생성 또는 찾기
            val parentDir = createDirectoryIfNotExists(parentPath)
            if (parentDir != null) {
                sendMessage("🔧 부모 디렉토리 확인: ${parentDir.path}", isUser = false)
                
                return WriteCommandAction.runWriteCommandAction<VirtualFile>(project) {
                    try {
                        // 파일명만 확인 (전체 경로가 아님)
                        val cleanFileName = fileName.substringAfterLast('/')
                        val existingFile = parentDir.findChild(cleanFileName)
                        
                        if (existingFile != null && existingFile.exists()) {
                            sendMessage("⚠️ 파일이 이미 존재합니다: $cleanFileName (경로: ${existingFile.path})", isUser = false)
                            return@runWriteCommandAction existingFile
                        }
                        
                        sendMessage("🔧 새 파일 생성 중: $cleanFileName", isUser = false)
                        val newFile = parentDir.createChildData(this, cleanFileName)
                        newFile.setBinaryContent(content.toByteArray(Charsets.UTF_8))
                        sendMessage("✅ 파일이 성공적으로 생성되었습니다: ${newFile.path}", isUser = false)
                        newFile
                    } catch (e: Exception) {
                        sendMessage("❌ 파일 생성 중 오류가 발생했습니다: ${e.message}", isUser = false)
                        null
                    }
                }
            } else {
                sendMessage("❌ 부모 디렉토리를 생성할 수 없습니다: $parentPath", isUser = false)
            }
        } catch (e: Exception) {
            sendMessage("❌ 파일 생성 중 전체 오류가 발생했습니다: ${e.message}", isUser = false)
        }
        return null
    }
    
    /**
     * 디렉토리가 존재하지 않으면 생성합니다.
     * @param dirPath 디렉토리 경로 (절대 경로)
     * @return 생성되거나 존재하는 디렉토리, 실패시 null
     */
    private fun createDirectoryIfNotExists(dirPath: String): VirtualFile? {
        try {
            val normalizedPath = dirPath.replace("\\", "/")
            val existingDir = LocalFileSystem.getInstance().findFileByPath(normalizedPath)
            
            if (existingDir != null && existingDir.isDirectory) {
                return existingDir
            }
            
            // 부모 디렉토리부터 차례로 생성
            val pathParts = normalizedPath.split("/").filter { it.isNotEmpty() }
            var currentPath = if (normalizedPath.startsWith("/")) "/" else ""
            var currentDir: VirtualFile? = null
            
            // Windows 드라이브 문자 처리 (예: C:)
            if (pathParts.isNotEmpty() && pathParts[0].contains(":")) {
                currentPath = "${pathParts[0]}/"
                currentDir = LocalFileSystem.getInstance().findFileByPath(pathParts[0] + "/")
                
                for (i in 1 until pathParts.size) {
                    val part = pathParts[i]
                    currentPath += part
                    val existingPartDir = LocalFileSystem.getInstance().findFileByPath(currentPath)
                    
                    if (existingPartDir != null && existingPartDir.isDirectory) {
                        currentDir = existingPartDir
                    } else {
                        currentDir = WriteCommandAction.runWriteCommandAction<VirtualFile>(project) {
                            try {
                                currentDir?.createChildDirectory(this, part)
                            } catch (e: Exception) {
                                null
                            }
                        }
                        if (currentDir == null) break
                    }
                    currentPath += "/"
                }
            } else {
                // Unix 스타일 경로 처리
                for (part in pathParts) {
                    currentPath += if (currentPath.endsWith("/")) part else "/$part"
                    val existingPartDir = LocalFileSystem.getInstance().findFileByPath(currentPath)
                    
                    if (existingPartDir != null && existingPartDir.isDirectory) {
                        currentDir = existingPartDir
                    } else {
                        currentDir = WriteCommandAction.runWriteCommandAction<VirtualFile>(project) {
                            try {
                                val parentDir = LocalFileSystem.getInstance().findFileByPath(currentPath.substringBeforeLast('/'))
                                parentDir?.createChildDirectory(this, part)
                            } catch (e: Exception) {
                                null
                            }
                        }
                        if (currentDir == null) break
                    }
                }
            }
            
            return currentDir
        } catch (e: Exception) {
            sendMessage("디렉토리 생성 중 오류가 발생했습니다: ${e.message}", isUser = false)
            return null
        }
    }
    
    /**
     * 외부 파일을 수정합니다.
     * @param filePath 수정할 파일의 경로
     * @param newContent 새로운 파일 내용
     * @return 수정 성공 여부
     */
    fun modifyExternalFile(filePath: String, newContent: String): Boolean {
        try {
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath.replace("\\", "/"))
            if (virtualFile != null && virtualFile.exists() && !virtualFile.isDirectory) {
                return WriteCommandAction.runWriteCommandAction<Boolean>(project) {
                    try {
                        virtualFile.setBinaryContent(newContent.toByteArray(virtualFile.charset))
                        true
                    } catch (e: Exception) {
                        sendMessage("파일 수정 중 오류가 발생했습니다: ${e.message}", isUser = false)
                        false
                    }
                }
            }
        } catch (e: Exception) {
            sendMessage("파일 수정 중 오류가 발생했습니다: ${e.message}", isUser = false)
        }
        return false
    }
    
    /**
     * 파일을 IDE에서 열어줍니다.
     * @param virtualFile 열 파일
     */
    fun openFileInEditor(virtualFile: VirtualFile) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val descriptor = OpenFileDescriptor(project, virtualFile)
                FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
            } catch (e: Exception) {
                sendMessage("파일을 에디터에서 여는 중 오류가 발생했습니다: ${e.message}", isUser = false)
            }
        }
    }
    
    /**
     * 파일 경로로부터 VirtualFile을 가져옵니다.
     * @param filePath 파일 경로
     * @return VirtualFile 객체 또는 null
     */
    fun getVirtualFileByPath(filePath: String): VirtualFile? {
        val normalizedPath = filePath.replace("\\", "/")
        return LocalFileSystem.getInstance().findFileByPath(normalizedPath)
    }
    
    /**
     * 프로젝트 내의 모든 소스 파일을 스캔합니다.
     * @return 파일 경로 리스트
     */
    fun scanProjectFiles(): List<String> {
        val files = mutableListOf<String>()
        val projectRoots = ProjectRootManager.getInstance(project).contentSourceRoots
        
        for (root in projectRoots) {
            scanDirectory(root, files)
        }
        
        return files
    }
    
    /**
     * 디렉토리를 재귀적으로 스캔하여 파일 목록을 수집합니다.
     * @param dir 스캔할 디렉토리
     * @param files 파일 목록을 저장할 리스트
     */
    private fun scanDirectory(dir: VirtualFile, files: MutableList<String>) {
        try {
            if (dir.isDirectory) {
                for (child in dir.children) {
                    if (child.isDirectory) {
                        scanDirectory(child, files)
                    } else {
                        val extension = child.extension?.lowercase()
                        if (extension in listOf("java", "kt", "vue", "xml", "json", "js", "ts", "sql", "yml", "yaml")) {
                            files.add(child.path)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 접근 권한이 없는 디렉토리는 무시
        }
    }
    
    /**
     * 파일 템플릿을 생성합니다.
     * @param templateType 템플릿 타입
     * @param className 클래스명 (필요한 경우)
     * @param packageName 패키지명 (필요한 경우)
     * @return 생성된 템플릿 내용
     */
    fun generateFileTemplate(templateType: FileTemplateType, className: String? = null, packageName: String? = null): String {
        return when (templateType) {
            FileTemplateType.JAVA_CLASS -> generateJavaClassTemplate(className, packageName)
            FileTemplateType.JAVA_INTERFACE -> generateJavaInterfaceTemplate(className, packageName)
            FileTemplateType.JAVA_ENUM -> generateJavaEnumTemplate(className, packageName)
            FileTemplateType.KOTLIN_CLASS -> generateKotlinClassTemplate(className, packageName)
            FileTemplateType.VUE_COMPONENT -> generateVueComponentTemplate(className)
            FileTemplateType.XML_CONFIG -> generateXmlConfigTemplate()
            FileTemplateType.JSON_CONFIG -> generateJsonConfigTemplate()
            FileTemplateType.PLAIN_TEXT -> ""
            FileTemplateType.CUSTOM -> ""
        }
    }
    
    /**
     * Java 클래스 템플릿을 생성합니다.
     */
    private fun generateJavaClassTemplate(className: String?, packageName: String?): String {
        val actualClassName = className ?: "NewClass"
        val packageDeclaration = if (packageName != null) "package $packageName;\n\n" else ""
        
        return """$packageDeclaration/**
 * $actualClassName 클래스입니다.
 * 
 * @author Generated by AI Assistant
 */
public class $actualClassName {
    
    /**
     * 기본 생성자입니다.
     */
    public $actualClassName() {
        // 초기화 코드를 여기에 작성하세요
    }
    
    /**
     * 메인 메서드입니다.
     * 
     * @param args 명령행 인수
     */
    public static void main(String[] args) {
        System.out.println("Hello from $actualClassName!");
    }
}"""
    }
    
    /**
     * Java 인터페이스 템플릿을 생성합니다.
     */
    private fun generateJavaInterfaceTemplate(interfaceName: String?, packageName: String?): String {
        val actualInterfaceName = interfaceName ?: "NewInterface"
        val packageDeclaration = if (packageName != null) "package $packageName;\n\n" else ""
        
        return """$packageDeclaration/**
 * $actualInterfaceName 인터페이스입니다.
 * 
 * @author Generated by AI Assistant
 */
public interface $actualInterfaceName {
    
    /**
     * 예시 메서드입니다.
     * 
     * @return 처리 결과
     */
    boolean process();
    
    /**
     * 기본 메서드 예시입니다.
     * 
     * @return 기본값
     */
    default String getDefaultValue() {
        return "default";
    }
}"""
    }
    
    /**
     * Java 열거형 템플릿을 생성합니다.
     */
    private fun generateJavaEnumTemplate(enumName: String?, packageName: String?): String {
        val actualEnumName = enumName ?: "NewEnum"
        val packageDeclaration = if (packageName != null) "package $packageName;\n\n" else ""
        
        return """$packageDeclaration/**
 * $actualEnumName 열거형입니다.
 * 
 * @author Generated by AI Assistant
 */
public enum $actualEnumName {
    
    /**
     * 첫 번째 값
     */
    VALUE1("value1"),
    
    /**
     * 두 번째 값
     */
    VALUE2("value2"),
    
    /**
     * 세 번째 값
     */
    VALUE3("value3");
    
    private final String value;
    
    /**
     * 생성자입니다.
     * 
     * @param value 값
     */
    $actualEnumName(String value) {
        this.value = value;
    }
    
    /**
     * 값을 반환합니다.
     * 
     * @return 값
     */
    public String getValue() {
        return value;
    }
}"""
    }
    
    /**
     * Kotlin 클래스 템플릿을 생성합니다.
     */
    private fun generateKotlinClassTemplate(className: String?, packageName: String?): String {
        val actualClassName = className ?: "NewClass"
        val packageDeclaration = if (packageName != null) "package $packageName\n\n" else ""
        
        return """$packageDeclaration/**
 * $actualClassName 클래스입니다.
 * 
 * @author Generated by AI Assistant
 */
class $actualClassName {
    
    /**
     * 예시 프로퍼티입니다.
     */
    var exampleProperty: String = "default"
    
    /**
     * 예시 메서드입니다.
     * 
     * @param input 입력값
     * @return 처리 결과
     */
    fun exampleMethod(input: String): String {
        return "Processed: ${'$'}input"
    }
    
    companion object {
        /**
         * 메인 함수입니다.
         */
        @JvmStatic
        fun main(args: Array<String>) {
            println("Hello from $actualClassName!")
        }
    }
}"""
    }
    
    /**
     * Vue 컴포넌트 템플릿을 생성합니다.
     */
    private fun generateVueComponentTemplate(componentName: String?): String {
        val actualComponentName = componentName?.replace(".vue", "") ?: "NewComponent"
        
        return """<template>
  <div class="${actualComponentName.lowercase()}">
    <h1>{{ title }}</h1>
    <p>{{ message }}</p>
    <button @click="handleClick">클릭하세요</button>
  </div>
</template>

<script>
export default {
  name: '$actualComponentName',
  
  data() {
    return {
      title: '$actualComponentName 컴포넌트',
      message: '안녕하세요! 이것은 새로운 Vue 컴포넌트입니다.'
    }
  },
  
  mounted() {
    console.log('$actualComponentName 컴포넌트가 마운트되었습니다.')
  },
  
  methods: {
    handleClick() {
      this.message = '버튼이 클릭되었습니다!'
      this.${'$'}emit('click', { component: '$actualComponentName' })
    }
  }
}
</script>

<style scoped>
.${actualComponentName.lowercase()} {
  padding: 20px;
  border: 1px solid #ddd;
  border-radius: 8px;
  max-width: 400px;
  margin: 0 auto;
}

h1 {
  color: #2c3e50;
  text-align: center;
}

p {
  color: #7f8c8d;
  text-align: center;
}

button {
  display: block;
  margin: 10px auto;
  padding: 10px 20px;
  background-color: #3498db;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
}

button:hover {
  background-color: #2980b9;
}
</style>"""
    }
    
    /**
     * XML 설정 파일 템플릿을 생성합니다.
     */
    private fun generateXmlConfigTemplate(): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <!-- 설정 정보를 여기에 작성하세요 -->
    
    <settings>
        <property name="example.setting" value="default_value" />
        <property name="debug.enabled" value="false" />
    </settings>
    
    <database>
        <connection>
            <url>jdbc:mysql://localhost:3306/database</url>
            <username>user</username>
            <password>password</password>
        </connection>
    </database>
    
    <logging>
        <level>INFO</level>
        <file>application.log</file>
    </logging>
    
</configuration>"""
    }
    
    /**
     * JSON 설정 파일 템플릿을 생성합니다.
     */
    private fun generateJsonConfigTemplate(): String {
        return """{
  "name": "새로운 설정",
  "version": "1.0.0",
  "description": "이것은 새로운 JSON 설정 파일입니다.",
  "settings": {
    "debug": false,
    "environment": "development",
    "port": 8080
  },
  "database": {
    "host": "localhost",
    "port": 3306,
    "name": "database",
    "credentials": {
      "username": "user",
      "password": "password"
    }
  },
  "features": {
    "authentication": true,
    "logging": true,
    "caching": false
  },
  "paths": {
    "static": "/static",
    "uploads": "/uploads",
    "logs": "/logs"
  }
}"""
    }
    
    /**
     * 클래스명을 파일명에서 추출합니다.
     * @param fileName 파일명
     * @return 클래스명
     */
    fun extractClassName(fileName: String): String {
        return fileName.substringBeforeLast('.').split("/").last()
            .split(Regex("[-_]"))
            .joinToString("") { it.replaceFirstChar { char -> char.uppercaseChar() } }
    }
    
    /**
     * LLM의 파일 생성 응답을 파싱하고 처리합니다.
     * @param response LLM 응답 문자열
     */
    private fun handleFileCreationResponse(response: String) {
        try {
            val pattern = Pattern.compile("\\[FileCreation\\](.*)", Pattern.DOTALL)
            val matcher = pattern.matcher(response)
            
            if (matcher.find()) {
                val content = matcher.group(1).trim()
                val lines = content.lines()
                
                var filePath: String? = null
                var fileName: String? = null
                var templateType: FileTemplateType? = null
                var className: String? = null
                var packageName: String? = null
                var fileContent: String? = null
                var isContentSection = false
                val contentBuilder = StringBuilder()
                
                for (line in lines) {
                    when {
                        line.startsWith("FILE_PATH:") -> filePath = line.substringAfter("FILE_PATH:").trim()
                        line.startsWith("FILE_NAME:") -> fileName = line.substringAfter("FILE_NAME:").trim()
                        line.startsWith("TEMPLATE_TYPE:") -> {
                            val typeStr = line.substringAfter("TEMPLATE_TYPE:").trim()
                            templateType = try {
                                FileTemplateType.valueOf(typeStr)
                            } catch (e: Exception) {
                                FileTemplateType.CUSTOM
                            }
                        }
                        line.startsWith("CLASS_NAME:") -> className = line.substringAfter("CLASS_NAME:").trim()
                        line.startsWith("PACKAGE_NAME:") -> packageName = line.substringAfter("PACKAGE_NAME:").trim()
                        line.startsWith("CONTENT:") -> {
                            isContentSection = true
                            continue
                        }
                        isContentSection -> {
                            contentBuilder.appendLine(line)
                        }
                    }
                }
                
                fileContent = contentBuilder.toString().trim()
                
                if (fileName != null && fileContent != null) {
                    sendMessage("🔧 LLM 응답 파싱 완료 - 파일명: $fileName", isUser = false)
                    
                    val extension = fileName.substringAfterLast('.', "")
                    val actualClassName = className ?: extractClassName(fileName)
                    
                    sendMessage("🔧 파일 확장자: $extension, 클래스명: $actualClassName", isUser = false)
                    
                    // 파일 경로가 지정되지 않은 경우 인덱싱 기반 스마트 추천
                    val actualFilePath = filePath ?: run {
                        sendMessage("🔧 파일 경로가 지정되지 않음, 스마트 추천 시작", isUser = false)
                        
                        val suggestions = suggestFilePaths(fileName, extension)
                        sendMessage("🔧 경로 제안들: $suggestions", isUser = false)
                        
                        val bestPath = selectBestPath(suggestions, actualClassName, extension)
                        sendMessage("🔧 최적 경로 선택: $bestPath", isUser = false)
                        
                        val finalPath = bestPath ?: fileName
                        sendMessage("🔧 최종 파일 경로: $finalPath", isUser = false)
                        finalPath
                    }
                    
                    sendMessage("🔧 실제 사용할 파일 경로: $actualFilePath", isUser = false)
                    
                    // 패키지명이 지정되지 않은 경우 인덱싱 기반 스마트 추천
                    val actualPackageName = packageName ?: run {
                        val extractedFromPath = extractPackageName(actualFilePath)
                        if (extractedFromPath != null) {
                            extractedFromPath
                        } else {
                            // 인덱싱 정보를 활용한 패키지 추천
                            val suggestedPackages = suggestPackagePaths(actualClassName, extension)
                            suggestedPackages.firstOrNull()
                        }
                    }
                    
                    // 템플릿 타입이 지정되지 않은 경우 파일명에서 결정
                    val actualTemplateType = templateType ?: determineTemplateType(fileName)
                    
                    val fileCreation = PendingFileCreation(
                        filePath = actualFilePath,
                        fileName = fileName,
                        content = fileContent,
                        templateType = actualTemplateType,
                        packageName = actualPackageName,
                        className = actualClassName,
                        directory = actualFilePath.substringBeforeLast('/')
                    )
                    
                    pendingFileCreation = fileCreation
                    
                    // 파일 생성 확인 창 표시
                    ApplicationManager.getApplication().invokeLater {
                        showFileCreationDiffWindow(fileCreation)
                        sendMessage("새 파일 생성 제안이 준비되었습니다. 확인 후 적용하실 수 있습니다.", isUser = false)
                    }
                } else {
                    sendMessage("파일 생성 정보가 불완전합니다. 파일명과 내용이 필요합니다.", isUser = false)
                }
            } else {
                sendMessage("파일 생성 응답을 파싱할 수 없습니다.\n받은 응답:\n$response", isUser = false)
            }
        } catch (e: Exception) {
            sendMessage("파일 생성 응답 처리 중 오류가 발생했습니다: ${e.message}", isUser = false)
        }
    }
    
    /**
     * LLM의 외부 파일 수정 응답을 파싱하고 처리합니다.
     * @param response LLM 응답 문자열
     */
    private fun handleExternalFileEditResponse(response: String) {
        try {
            val pattern = Pattern.compile("\\[ExternalFileEdit\\](.*)", Pattern.DOTALL)
            val matcher = pattern.matcher(response)
            
            if (matcher.find()) {
                val content = matcher.group(1).trim()
                val lines = content.lines()
                
                var filePath: String? = null
                var operation: String? = null
                var fileContent: String? = null
                var isContentSection = false
                val contentBuilder = StringBuilder()
                
                for (line in lines) {
                    when {
                        line.startsWith("FILE_PATH:") -> filePath = line.substringAfter("FILE_PATH:").trim()
                        line.startsWith("OPERATION:") -> operation = line.substringAfter("OPERATION:").trim()
                        line.startsWith("CONTENT:") -> {
                            isContentSection = true
                            continue
                        }
                        isContentSection -> {
                            contentBuilder.appendLine(line)
                        }
                    }
                }
                
                fileContent = contentBuilder.toString().trim()
                
                if (filePath != null && fileContent != null) {
                    when (operation) {
                        "CREATE_NEW" -> {
                            // 새 파일 생성
                            val fileName = filePath.substringAfterLast('/')
                            val templateType = determineTemplateType(fileName)
                            val className = extractClassName(fileName)
                            val packageName = extractPackageName(filePath)
                            
                            val fileCreation = PendingFileCreation(
                                filePath = filePath,
                                fileName = fileName,
                                content = fileContent,
                                templateType = templateType,
                                packageName = packageName,
                                className = className,
                                directory = filePath.substringBeforeLast('/')
                            )
                            
                            pendingFileCreation = fileCreation
                            
                            ApplicationManager.getApplication().invokeLater {
                                showFileCreationDiffWindow(fileCreation)
                                sendMessage("새 파일 생성 제안이 준비되었습니다.", isUser = false)
                            }
                        }
                        "MODIFY_EXISTING" -> {
                            // 기존 파일 수정
                            val fileData = readExternalFile(filePath)
                            if (fileData != null) {
                                val (originalContent, virtualFile) = fileData
                                val fileName = virtualFile.name
                                
                                val externalFileEdit = PendingExternalFileEdit(
                                    filePath = filePath,
                                    originalContent = originalContent,
                                    modifiedContent = fileContent,
                                    fileName = fileName,
                                    virtualFile = virtualFile
                                )
                                
                                pendingExternalFileEdit = externalFileEdit
                                
                                ApplicationManager.getApplication().invokeLater {
                                    showExternalFileEditDiffWindow(originalContent, fileContent, externalFileEdit)
                                    sendMessage("파일 수정 제안이 준비되었습니다.", isUser = false)
                                }
                            } else {
                                sendMessage("파일을 읽을 수 없습니다: $filePath", isUser = false)
                            }
                        }
                        else -> {
                            sendMessage("알 수 없는 작업 유형입니다: $operation", isUser = false)
                        }
                    }
                } else {
                    sendMessage("외부 파일 편집 정보가 불완전합니다.", isUser = false)
                }
            } else {
                sendMessage("외부 파일 편집 응답을 파싱할 수 없습니다.\n받은 응답:\n$response", isUser = false)
            }
        } catch (e: Exception) {
            sendMessage("외부 파일 편집 응답 처리 중 오류가 발생했습니다: ${e.message}", isUser = false)
        }
    }
    
    /**
     * 파일 생성을 위한 diff 창을 표시합니다.
     * @param fileCreation 파일 생성 정보
     */
    private fun showFileCreationDiffWindow(fileCreation: PendingFileCreation) {
        val diffContentFactory = DiffContentFactory.getInstance()
        val leftContent = diffContentFactory.create("") // 새 파일이므로 왼쪽은 빈 내용
        val rightContent = diffContentFactory.create(fileCreation.content)

        val diffRequest = SimpleDiffRequest(
            "새 파일 생성: ${fileCreation.fileName}",
            leftContent,
            rightContent,
            "없음 (새 파일)",
            "새 파일 내용"
        )

        showCustomFileCreationDiffDialog(diffRequest, fileCreation)
    }
    
    /**
     * 외부 파일 수정을 위한 diff 창을 표시합니다.
     * @param originalContent 원본 내용
     * @param modifiedContent 수정된 내용
     * @param externalFileEdit 외부 파일 편집 정보
     */
    private fun showExternalFileEditDiffWindow(originalContent: String, modifiedContent: String, externalFileEdit: PendingExternalFileEdit) {
        val diffContentFactory = DiffContentFactory.getInstance()
        val leftContent = diffContentFactory.create(originalContent)
        val rightContent = diffContentFactory.create(modifiedContent)

        val diffRequest = SimpleDiffRequest(
            "외부 파일 수정: ${externalFileEdit.fileName}",
            leftContent,
            rightContent,
            "원본 파일",
            "수정된 파일"
        )

        showCustomExternalFileEditDiffDialog(diffRequest, externalFileEdit)
    }
    
    /**
     * 파일 생성을 위한 커스텀 diff 대화상자를 표시합니다.
     */
    private fun showCustomFileCreationDiffDialog(diffRequest: SimpleDiffRequest, fileCreation: PendingFileCreation) {
        ApplicationManager.getApplication().invokeLater {
            val dialog = object : com.intellij.openapi.ui.DialogWrapper(project) {
                private var diffPanel: com.intellij.diff.DiffRequestPanel? = null
                
                init {
                    title = "새 파일 생성: ${fileCreation.fileName}"
                    init()
                }

                override fun createCenterPanel(): javax.swing.JComponent? {
                    diffPanel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
                    diffPanel?.setRequest(diffRequest)
                    return diffPanel?.component
                }

                override fun createActions(): Array<javax.swing.Action> {
                    val createAction = object : javax.swing.AbstractAction("파일 생성") {
                        override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                            applyFileCreation(fileCreation)
                            sendMessage("파일이 성공적으로 생성되었습니다: ${fileCreation.filePath}", isUser = false)
                            close(OK_EXIT_CODE)
                        }
                    }

                    val cancelAction = object : javax.swing.AbstractAction("취소") {
                        override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                            rejectFileCreation()
                            sendMessage("파일 생성이 취소되었습니다.", isUser = false)
                            close(CANCEL_EXIT_CODE)
                        }
                    }

                    return arrayOf(createAction, cancelAction)
                }

                override fun getPreferredSize(): java.awt.Dimension {
                    return java.awt.Dimension(800, 600)
                }
            }

            dialog.show()
        }
    }
    
    /**
     * 외부 파일 수정을 위한 커스텀 diff 대화상자를 표시합니다.
     */
    private fun showCustomExternalFileEditDiffDialog(diffRequest: SimpleDiffRequest, externalFileEdit: PendingExternalFileEdit) {
        ApplicationManager.getApplication().invokeLater {
            val dialog = object : com.intellij.openapi.ui.DialogWrapper(project) {
                private var diffPanel: com.intellij.diff.DiffRequestPanel? = null
                
                init {
                    title = "외부 파일 수정: ${externalFileEdit.fileName}"
                    init()
                }

                override fun createCenterPanel(): javax.swing.JComponent? {
                    diffPanel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
                    diffPanel?.setRequest(diffRequest)
                    return diffPanel?.component
                }

                override fun createActions(): Array<javax.swing.Action> {
                    val applyAction = object : javax.swing.AbstractAction("적용") {
                        override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                            applyExternalFileEdit(externalFileEdit)
                            sendMessage("파일이 성공적으로 수정되었습니다: ${externalFileEdit.filePath}", isUser = false)
                            close(OK_EXIT_CODE)
                        }
                    }

                    val cancelAction = object : javax.swing.AbstractAction("취소") {
                        override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                            rejectExternalFileEdit()
                            sendMessage("파일 수정이 취소되었습니다.", isUser = false)
                            close(CANCEL_EXIT_CODE)
                        }
                    }

                    return arrayOf(applyAction, cancelAction)
                }

                override fun getPreferredSize(): java.awt.Dimension {
                    return java.awt.Dimension(800, 600)
                }
            }

            dialog.show()
        }
    }
    
    /**
     * 파일 생성을 적용합니다.
     */
    private fun applyFileCreation(fileCreation: PendingFileCreation) {
        val createdFile = createNewFile(fileCreation.filePath, fileCreation.content)
        if (createdFile != null) {
            openFileInEditor(createdFile)
        }
        pendingFileCreation = null
    }
    
    /**
     * 파일 생성을 거절합니다.
     */
    private fun rejectFileCreation() {
        pendingFileCreation = null
    }
    
    /**
     * 외부 파일 수정을 적용합니다.
     */
    private fun applyExternalFileEdit(externalFileEdit: PendingExternalFileEdit) {
        val success = modifyExternalFile(externalFileEdit.filePath, externalFileEdit.modifiedContent)
        if (success && externalFileEdit.virtualFile != null) {
            openFileInEditor(externalFileEdit.virtualFile)
        }
        pendingExternalFileEdit = null
    }
    
    /**
     * 외부 파일 수정을 거절합니다.
     */
    private fun rejectExternalFileEdit() {
        pendingExternalFileEdit = null
    }

    /**
     * DB에 연결하여 스키마 정보를 수집하고 systemMessage에 추가합니다.
     * 성능 최적화: 인덱스 정보, Primary Key, Foreign Key 정보를 포함하여 수집합니다.
     * 
     * @param dbType DB 종류 (Tibero 등)
     * @param host 호스트
     * @param port 포트
     * @param dbName 데이터베이스 이름
     * @param user 사용자
     * @param password 비밀번호
     * @param targetTables 대상 테이블 목록 (콤마 구분, 비우면 전체)
     */
    fun connectToDB(dbType: String, host: String, port: String, dbName: String, user: String, password: String, targetTables: String = "") {
        sendMessage("🕒 DB 스키마 학습 중... 잠시만 기다려주세요.", isUser = false)

        CoroutineScope(Dispatchers.IO).launch {
            val url = when (dbType) {
                "Tibero" -> "jdbc:tibero:thin:@$host:$port:$dbName"
                else -> {
                    ApplicationManager.getApplication().invokeLater {
                        sendMessage("지원되지 않는 DB 종류: $dbType", isUser = false)
                    }
                    return@launch
                }
            }

            println("Debug: Attempting DB connection with URL: $url")

            try {
                println("Debug: Loading Tibero driver")
                Class.forName("com.tmax.tibero.jdbc.TbDriver")
                println("Debug: Driver loaded successfully")

                println("Debug: Connecting to database")
                DriverManager.getConnection(url, user, password).use { conn ->
                    println("Debug: Connected successfully")

                    val meta = conn.metaData
                    val schemaPattern = "SEMAS24"
                    val tableNames = mutableListOf<String>()

                    // 테이블 목록 수집 (최적화: 배치 처리)
                    if (targetTables.isBlank()) {
                        println("Debug: Fetching all tables")
                        meta.getTables(null, schemaPattern, "TB_%", arrayOf("TABLE")).use { tablesRs ->
                            while (tablesRs.next()) {
                                tableNames.add(tablesRs.getString("TABLE_NAME"))
                            }
                        }
                        println("Debug: Found ${tableNames.size} tables")
                    } else {
                        tableNames.addAll(targetTables.split(",").map { it.trim().uppercase() })
                        println("Debug: Using specified tables: $tableNames")
                    }

                    // 테이블 수 제한으로 성능 보장 (최대 50개)
                    val limitedTableNames = tableNames.take(50)
                    if (tableNames.size > 50) {
                        println("Debug: Limiting to 50 tables for performance")
                    }

                    // 병렬 처리로 스키마 정보 수집 (컬럼, 인덱스, PK, FK)
                    val schemaJobs = limitedTableNames.map { tableName ->
                        async {
                            try {
                                collectTableSchemaInfo(meta, schemaPattern, tableName)
                            } catch (e: Exception) {
                                println("Debug: Error collecting schema for $tableName: ${e.message}")
                                "Table: $tableName\n  [Error: ${e.message}]\n"
                            }
                        }
                    }

                    val schemaResults = schemaJobs.awaitAll()
                    val schema = StringBuilder()
                    schemaResults.forEach { schema.append(it) }

                    dbSchema = schema.toString()
                    systemMessage += "\n\nDB Schema:\n$dbSchema"
                    
                    // 사용량 측정: DB 연결 기록
                    userService.recordDbConnection()

                    ApplicationManager.getApplication().invokeLater {
                        println("Debug: Sending success message")
                        sendMessage("✅ DB 연결 성공. 스키마 정보(${limitedTableNames.size}개 테이블)가 학습되었습니다.", isUser = false)
                    }
                }
            } catch (e: Exception) {
                println("Debug: Error in DB connection: ${e.message}")
                e.printStackTrace()
                ApplicationManager.getApplication().invokeLater {
                    sendMessage("❌ DB 연결 실패: ${e.message}", isUser = false)
                }
            }
        }
    }

    /**
     * 특정 테이블의 스키마 정보를 수집합니다.
     * 성능 최적화: 컬럼, 인덱스, Primary Key, Foreign Key 정보를 효율적으로 수집합니다.
     * 
     * @param meta DatabaseMetaData 객체
     * @param schemaPattern 스키마 패턴
     * @param tableName 테이블 이름
     * @return 포맷된 스키마 정보 문자열
     */
    private fun collectTableSchemaInfo(meta: java.sql.DatabaseMetaData, schemaPattern: String, tableName: String): String {
        val tableSchema = StringBuilder("Table: $tableName\n")
        
        // 1. 컬럼 정보 수집 (최적화: 최대 20개 컬럼)
        val columns = mutableListOf<Pair<String, String>>()
        meta.getColumns(null, schemaPattern, tableName, "%").use { columnsRs ->
            var columnCount = 0
            while (columnsRs.next() && columnCount < 20) {
                val colName = columnsRs.getString("COLUMN_NAME")
                val colType = columnsRs.getString("TYPE_NAME")
                val colSize = columnsRs.getString("COLUMN_SIZE")
                val nullable = columnsRs.getString("IS_NULLABLE")
                columns.add(Pair(colName, "$colType($colSize)${if (nullable == "NO") " NOT NULL" else ""}"))
                columnCount++
            }
        }
        
        // 컬럼 정보 출력
        columns.forEach { (colName, colInfo) ->
            tableSchema.append("  - $colName: $colInfo\n")
        }
        
        // 2. Primary Key 정보 수집 (성능 최적화: 인덱스 활용)
        val primaryKeys = mutableListOf<String>()
        meta.getPrimaryKeys(null, schemaPattern, tableName).use { pkRs ->
            while (pkRs.next()) {
                primaryKeys.add(pkRs.getString("COLUMN_NAME"))
            }
        }
        if (primaryKeys.isNotEmpty()) {
            tableSchema.append("  Primary Key: ${primaryKeys.joinToString(", ")}\n")
        }
        
        // 3. 인덱스 정보 수집 (성능 최적화 핵심)
        val indexes = mutableMapOf<String, MutableList<String>>()
        meta.getIndexInfo(null, schemaPattern, tableName, false, false).use { indexRs ->
            while (indexRs.next()) {
                val indexName = indexRs.getString("INDEX_NAME") ?: continue
                val columnName = indexRs.getString("COLUMN_NAME") ?: continue
                val nonUnique = indexRs.getBoolean("NON_UNIQUE")
                val indexType = indexRs.getShort("TYPE")
                
                // 인덱스 타입별 분류
                val indexTypeStr = when (indexType) {
                    java.sql.DatabaseMetaData.tableIndexStatistic -> "STATISTIC"
                    java.sql.DatabaseMetaData.tableIndexClustered -> "CLUSTERED"
                    java.sql.DatabaseMetaData.tableIndexHashed -> "HASHED"
                    java.sql.DatabaseMetaData.tableIndexOther -> "OTHER"
                    else -> "UNKNOWN"
                }
                
                if (!indexes.containsKey(indexName)) {
                    indexes[indexName] = mutableListOf()
                }
                
                val indexInfo = if (nonUnique) {
                    "$columnName (NON-UNIQUE, $indexTypeStr)"
                } else {
                    "$columnName (UNIQUE, $indexTypeStr)"
                }
                indexes[indexName]?.add(indexInfo)
            }
        }
        
        // 인덱스 정보 출력 (성능 분석에 중요)
        if (indexes.isNotEmpty()) {
            tableSchema.append("  Indexes:\n")
            indexes.forEach { (indexName, columns) ->
                tableSchema.append("    - $indexName: ${columns.joinToString(", ")}\n")
            }
        }
        
        // 4. Foreign Key 정보 수집 (관계 파악)
        val foreignKeys = mutableListOf<String>()
        meta.getImportedKeys(null, schemaPattern, tableName).use { fkRs ->
            while (fkRs.next()) {
                val fkColumnName = fkRs.getString("FKCOLUMN_NAME")
                val pkTableName = fkRs.getString("PKTABLE_NAME")
                val pkColumnName = fkRs.getString("PKCOLUMN_NAME")
                foreignKeys.add("$fkColumnName -> $pkTableName.$pkColumnName")
            }
        }
        if (foreignKeys.isNotEmpty()) {
            tableSchema.append("  Foreign Keys:\n")
            foreignKeys.forEach { fk ->
                tableSchema.append("    - $fk\n")
            }
        }
        
        tableSchema.append("\n")
        return tableSchema.toString()
    }
    
    // ==================== 작업 모드 관련 메서드 ====================
    
    /**
     * 작업 모드 요청인지 감지합니다.
     * 
     * @param userInput 사용자 입력
     * @return 작업 모드 요청이면 true
     */
    private fun isTaskModeRequest(userInput: String): Boolean {
        val taskKeywords = listOf(
            "작업 목록", "작업목록", "할 일", "todo", "task list",
            "단계별", "순서대로", "단계로", "단계별로"
        )
        
        val input = userInput.lowercase()
        return taskKeywords.any { input.contains(it) } ||
               (input.contains("구현") && input.length > 50) || // 긴 구현 요청
               (input.contains("만들") && input.contains("기능"))
    }
    
    /**
     * 작업 모드로 진입합니다.
     * 작업목록을 생성하고 사용자에게 표시합니다.
     * 
     * @param requirement 사용자 요구사항
     */
    private fun enterTaskMode(requirement: String) {
        sendMessage(requirement, isUser = true)
        sendMessage("📋 작업 목록을 생성하는 중입니다...", isUser = false)
        
        // 작업 이력 관리자 초기화
        if (taskHistoryManager == null) {
            taskHistoryManager = org.dev.semaschatbot.task.TaskHistoryManager(project)
        }
        
        // 비동기로 작업목록 생성
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // 선택된 모델 확인 (Gemini 모델인 경우에만 사용)
                val selectedModelId = getSelectedModel()
                val geminiModelId = if (isGeminiModel(selectedModelId)) {
                    selectedModelId.removePrefix("💎 ").trim()
                } else {
                    "gemini-1.5-flash" // 기본값 사용
                }
                
                // 현재 로그인한 사용자 ID 가져오기
                val currentUserId = try {
                    userService.getCurrentUser()?.id
                } catch (e: Exception) {
                    null
                }
                
                val taskListGenerator = org.dev.semaschatbot.task.TaskListGenerator(geminiClient)
                val tasks = taskListGenerator.generateTaskList(requirement, geminiModelId, currentUserId)
                
                val session = org.dev.semaschatbot.task.TaskSession(
                    id = java.util.UUID.randomUUID().toString(),
                    requirement = requirement,
                    tasks = tasks.toMutableList()
                )
                
                // 파일 저장
                val savedFile = taskHistoryManager!!.saveTaskSession(session)
                
                // UI 업데이트 (EDT 스레드에서)
                ApplicationManager.getApplication().invokeLater {
                    displayTaskList(session, savedFile)
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    sendMessage("❌ 작업 목록 생성 중 오류가 발생했습니다: ${e.message}", isUser = false)
                    e.printStackTrace()
                }
            }
        }
    }
    
    /**
     * 작업목록을 채팅창에 표시합니다.
     * 
     * @param session 작업 세션
     * @param savedFile 저장된 파일
     */
    private fun displayTaskList(session: org.dev.semaschatbot.task.TaskSession, savedFile: java.io.File) {
        currentTaskSession = session
        taskHistoryFile = savedFile
        
        // 작업목록 패널 생성
        val taskListPanel = org.dev.semaschatbot.ui.TaskListPanel(
            session = session,
            savedFile = savedFile,
            onApprove = {
                approveTaskSession()
            },
            onCancel = {
                cancelTaskSession()
            }
        )
        
        // 채팅창에 추가
        chatPanel?.let { panel ->
            if (panel.componentCount > 0) {
                panel.add(Box.createVerticalStrut(8))
            }
            panel.add(taskListPanel)
            panel.revalidate()
            panel.repaint()
            scrollToBottom()
        }
    }
    
    /**
     * 작업 세션을 승인하고 실행을 시작합니다.
     */
    private fun approveTaskSession() {
        val session = currentTaskSession ?: return
        
        session.status = org.dev.semaschatbot.task.SessionStatus.APPROVED
        taskStateMachine = org.dev.semaschatbot.task.TaskExecutionStateMachine(session)
        
        sendMessage("✅ 작업을 시작합니다. 총 ${session.getTotalCount()}개의 작업이 있습니다.", isUser = false)
        
        // 첫 번째 작업 시작
        executeNextTask()
    }
    
    /**
     * 다음 작업을 실행합니다.
     */
    private fun executeNextTask() {
        val stateMachine = taskStateMachine ?: return
        val session = currentTaskSession ?: return
        
        val nextTask = stateMachine.moveToNextTask()
        if (nextTask == null) {
            sendMessage("✅ 모든 작업이 완료되었습니다!", isUser = false)
            session.status = org.dev.semaschatbot.task.SessionStatus.COMPLETED
            updateTaskHistoryFile()
            return
        }
        
        sendMessage("🔄 작업 진행 중: ${nextTask.title} (${stateMachine.getCompletedTasks().size + 1}/${session.getTotalCount()})", isUser = false)
        
        // 작업별 프롬프트 생성
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // 선택된 모델 확인 (Gemini 모델인 경우에만 사용)
                val selectedModelId = getSelectedModel()
                val geminiModelId = if (isGeminiModel(selectedModelId)) {
                    selectedModelId.removePrefix("💎 ").trim()
                } else {
                    "gemini-1.5-flash" // 기본값 사용
                }
                
                // 현재 로그인한 사용자 ID 가져오기
                val currentUserId = try {
                    userService.getCurrentUser()?.id
                } catch (e: Exception) {
                    null
                }
                
                val promptGenerator = org.dev.semaschatbot.task.TaskPromptGenerator(geminiClient)
                val completedTasks = stateMachine.getCompletedTasks()
                val prompt = promptGenerator.generatePromptForTask(nextTask, session.requirement, completedTasks, geminiModelId, currentUserId)
                
                if (prompt != null) {
                    nextTask.prompt = prompt
                    
                    // 프롬프트 승인 UI 표시
                    ApplicationManager.getApplication().invokeLater {
                        displayPromptApproval(nextTask, prompt)
                    }
                } else {
                    ApplicationManager.getApplication().invokeLater {
                        stateMachine.failCurrentTask("프롬프트 생성 실패")
                        sendMessage("❌ 프롬프트 생성에 실패했습니다.", isUser = false)
                        executeNextTask() // 다음 작업으로 진행
                    }
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    stateMachine.failCurrentTask(e.message ?: "알 수 없는 오류")
                    sendMessage("❌ 프롬프트 생성 중 오류 발생: ${e.message}", isUser = false)
                    executeNextTask() // 다음 작업으로 진행
                }
            }
        }
    }
    
    /**
     * 프롬프트 승인 UI를 표시합니다.
     * 
     * @param task 현재 작업
     * @param prompt 생성된 프롬프트
     */
    private fun displayPromptApproval(task: org.dev.semaschatbot.task.Task, prompt: String) {
        val promptPanel = org.dev.semaschatbot.ui.PromptApprovalPanel(
            task = task,
            prompt = prompt,
            onApprove = {
                // '진행' 버튼 클릭 시 작업 실행
                executeTaskWithSelectedModel(task, prompt)
            },
            onCancel = {
                // '취소' 버튼 클릭 시 전체 작업 취소
                cancelTaskSession()
            }
        )
        
        // 채팅창에 추가
        chatPanel?.let { panel ->
            if (panel.componentCount > 0) {
                panel.add(Box.createVerticalStrut(8))
            }
            panel.add(promptPanel)
            panel.revalidate()
            panel.repaint()
            scrollToBottom()
        }
    }
    
    /**
     * 선택된 모델에 따라 작업을 실행합니다.
     * 
     * @param task 실행할 작업
     * @param prompt 실행할 프롬프트
     */
    private fun executeTaskWithSelectedModel(task: org.dev.semaschatbot.task.Task, prompt: String) {
        val modelId = getSelectedModel()
        val systemMessage = "" // 필요시 시스템 메시지 추가
        
        // 현재 로그인한 사용자 ID 가져오기
        val currentUserId = try {
            userService.getCurrentUser()?.id
        } catch (e: Exception) {
            null
        }
        
        sendMessage("⚙️ 작업 실행 중... (모델: $modelId)", isUser = false)
        
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val result = if (isGeminiModel(modelId)) {
                    // Gemini API 호출
                    val actualModelId = modelId.removePrefix("💎 ").trim()
                    geminiClient.sendChatRequest(
                        userMessage = prompt,
                        systemMessage = systemMessage,
                        modelId = actualModelId,
                        userId = currentUserId
                    ) ?: "오류: Gemini API 응답이 null입니다."
                } else {
                    // LM Studio API 호출
                    apiClient.sendChatRequest(
                        userMessage = prompt,
                        systemMessage = systemMessage,
                        modelId = modelId
                    ) ?: "오류: LM Studio API 응답이 null입니다."
                }
                
                ApplicationManager.getApplication().invokeLater {
                    task.result = result
                    displayTaskResult(task, result)
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    task.status = org.dev.semaschatbot.task.TaskStatus.FAILED
                    task.result = "오류: ${e.message}"
                    sendMessage("❌ 작업 실행 중 오류 발생: ${e.message}", isUser = false)
                    e.printStackTrace()
                }
            }
        }
    }
    
    /**
     * 작업 결과를 표시합니다.
     * 
     * @param task 완료된 작업
     * @param result 작업 실행 결과
     */
    private fun displayTaskResult(task: org.dev.semaschatbot.task.Task, result: String) {
        val resultPanel = org.dev.semaschatbot.ui.TaskResultPanel(
            task = task,
            result = result,
            onComplete = {
                // '완료' 버튼 클릭 시 다음 작업으로 진행
                handleTaskComplete()
            },
            onCancel = {
                // '취소' 버튼 클릭 시 전체 작업 취소
                handleTaskCancelFromResult()
            }
        )
        
        // 채팅창에 추가
        chatPanel?.let { panel ->
            if (panel.componentCount > 0) {
                panel.add(Box.createVerticalStrut(8))
            }
            panel.add(resultPanel)
            panel.revalidate()
            panel.repaint()
            scrollToBottom()
        }
    }
    
    /**
     * 작업 완료 처리를 수행합니다.
     */
    private fun handleTaskComplete() {
        val stateMachine = taskStateMachine ?: return
        val session = currentTaskSession ?: return
        
        // 현재 작업 완료 처리
        val currentTask = stateMachine.getCurrentTask()
        currentTask?.let {
            stateMachine.completeCurrentTask(it.result ?: "")
        }
        
        // .md 파일 업데이트
        updateTaskHistoryFile()
        
        // 다음 작업 확인
        if (stateMachine.isAllCompleted()) {
            sendMessage("✅ 모든 작업이 완료되었습니다!", isUser = false)
            session.status = org.dev.semaschatbot.task.SessionStatus.COMPLETED
            updateTaskHistoryFile()
        } else {
            // 다음 작업으로 진행
            executeNextTask()
        }
    }
    
    /**
     * 작업 취소 처리 (결과 단계에서)
     */
    private fun handleTaskCancelFromResult() {
        cancelTaskSession()
    }
    
    
    /**
     * 작업 세션을 취소합니다.
     */
    private fun cancelTaskSession() {
        val session = currentTaskSession ?: return
        val historyFile = taskHistoryFile
        
        taskStateMachine?.cancelSession()
        sendMessage("❌ 작업 세션이 취소되었습니다.", isUser = false)
        
        // .md 파일 삭제
        if (historyFile != null && taskHistoryManager != null) {
            val deleted = taskHistoryManager!!.deleteTaskSessionFile(historyFile)
            if (deleted) {
                sendMessage("🗑️ 작업 이력 파일이 삭제되었습니다.", isUser = false)
            } else {
                sendMessage("⚠️ 작업 이력 파일 삭제에 실패했습니다.", isUser = false)
            }
        }
        
        currentTaskSession = null
        taskStateMachine = null
        taskHistoryFile = null
    }
    
    /**
     * 특정 작업을 취소합니다.
     * 
     * @param taskId 취소할 작업 ID
     */
    fun cancelTask(taskId: String) {
        val stateMachine = taskStateMachine ?: return
        val task = stateMachine.cancelTask(taskId)
        
        if (task == null) {
            sendMessage("❌ 작업을 찾을 수 없습니다.", isUser = false)
            return
        }
        
        sendMessage("✅ 작업 '${task.title}'이(가) 취소되었습니다.", isUser = false)
        updateTaskHistoryFile()
        
        // 다음 작업으로 진행
        val nextTask = stateMachine.moveToNextTask()
        if (nextTask != null) {
            executeNextTask()
        } else {
            sendMessage("모든 작업이 완료되거나 취소되었습니다.", isUser = false)
        }
    }
    
    /**
     * 작업 이력 파일을 업데이트합니다.
     */
    private fun updateTaskHistoryFile() {
        val session = currentTaskSession ?: return
        val historyManager = taskHistoryManager ?: return
        val historyFile = taskHistoryFile ?: return
        
        try {
            historyManager.updateTaskSession(session, historyFile)
        } catch (e: Exception) {
            println("[ChatService] 작업 이력 파일 업데이트 실패: ${e.message}")
        }
    }
}