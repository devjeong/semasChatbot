package org.dev.semaschatbot

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
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
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import groovy.util.logging.Slf4j
import java.awt.Color
import java.util.regex.Pattern
import javax.swing.JLabel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingWorker
import javax.swing.JPanel
import javax.swing.BoxLayout
import javax.swing.Box
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

/**
 * 사용자 입력 타입을 나타내는 열거형입니다.
 */
enum class UserInputType {
    RAG_QUESTION,           // 코드베이스 기반 질문
    INSTRUCTION,            // 코드 수정/개선 지시
    CURSOR_CODE_GENERATION, // 커서 위치 코드 생성
    FILE_CREATION,          // 새 파일 생성 요청
    EXTERNAL_FILE_EDIT,     // 외부 파일 수정 요청
    GENERAL_QUESTION        // 일반적인 질문
}

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

@Slf4j
@Service(Service.Level.PROJECT)
class ChatService(private val project: Project) {

    private val apiClient = LmStudioClient()
    // 실시간 인덱싱 서비스의 CodeIndexingService 인스턴스 사용
    private val realTimeIndexingService = project.getService(RealTimeIndexingService::class.java)
    private val codeIndexingService: CodeIndexingService
        get() = realTimeIndexingService.getIndexingService()
    var systemMessage: String = """
        당신은 Java 전문 개발 어시스턴트입니다. IntelliJ IDEA 환경에서 작업하는 개발자를 지원합니다.

        ## 역할
        - Java/Kotlin 코드 작성, 수정, 리팩토링
        - Spring, JPA 등 Java 생태계 지원
        - 프로젝트 구조 기반 파일 생성 및 배치
        - 코드베이스 분석 및 질문 답변

        ## 응답 원칙
        1. 항상 한국어로 응답
        2. 실행 가능한 정확한 코드 제공
        3. 간결하고 명확한 설명
        4. 프로젝트 구조와 일관된 코딩 스타일 유지
        5. 보안과 성능을 고려한 모범 사례 적용

        Java 개발자에게 실용적이고 전문적인 도움을 제공하세요.
        """.trimIndent()

    var chatPanel: JPanel? = null
    var scrollPane: JScrollPane? = null
    var loadingIndicator: JLabel? = null
    var fileInfoLabel: JLabel? = null

    private var selectedCode: String? = null
    private var selectedFileInfo: String? = null

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

    // 인증 관련 변수들
    private var isAuthenticated: Boolean = false
    private var configProperties: Properties? = null

    /**
     * LmStudio 서버의 URL을 설정합니다.
     * @param url 새로운 서버 URL
     */
    fun setLmStudioUrl(url: String) {
        apiClient.setBaseUrl(url)
    }

    /**
     * 현재 설정된 LmStudio 서버 URL을 반환합니다.
     * @return 현재 서버 URL
     */
    fun getLmStudioUrl(): String {
        return apiClient.getBaseUrl()
    }

    /**
     * 설정 파일을 로드합니다.
     */
    private fun loadConfigProperties(): Properties? {
        return try {
            val properties = Properties()
            val inputStream: InputStream? = this::class.java.classLoader.getResourceAsStream("config.properties")
            inputStream?.use {
                properties.load(it)
            }
            properties
        } catch (e: Exception) {
            println("설정 파일을 로드하는 중 오류 발생: ${e.message}")
            null
        }
    }

    

    /**
     * 인증키를 검증합니다.
     * @param inputKey 사용자가 입력한 인증키
     * @return 인증 성공 여부
     */
    fun authenticateUser(inputKey: String): Boolean {
        if (configProperties == null) {
            configProperties = loadConfigProperties()
        }
        
        val correctKey = configProperties?.getProperty("auth.key")
        val isValid = correctKey != null && inputKey.trim() == correctKey
        
        if (isValid) {
            isAuthenticated = true
            
            // 인증 성공 시 자동으로 프로젝트 인덱싱 시작
            sendMessage("✅ 인증이 완료되었습니다! 자동으로 프로젝트 인덱싱을 시작합니다.", isUser = false)
            startAutoIndexing()
        }
        
        return isValid
    }

    /**
     * 현재 인증 상태를 반환합니다.
     * @return 인증 여부
     */
    fun isUserAuthenticated(): Boolean {
        return isAuthenticated
    }

    /**
     * 인증 상태를 초기화합니다.
     */
    fun resetAuthentication() {
        isAuthenticated = false
        sendMessage("인증이 초기화되었습니다. 다시 인증해주세요.", isUser = false)
    }

    /**
     * 인증이 필요한지 확인합니다.
     * @return 인증이 필요한 경우 true
     */
    fun requiresAuthentication(): Boolean {
        return !isAuthenticated
    }

    /**
     * 사용자가 에디터에서 선택한 코드와 파일 정보를 컨텍스트로 설정합니다.
     * @param code 선택된 코드
     * @param fileInfo 파일 정보
     */
    fun setSelectionContext(code: String, fileInfo: String) {
        selectedCode = code
        selectedFileInfo = fileInfo
        ApplicationManager.getApplication().invokeLater {
            fileInfoLabel?.text = "선택된 파일: $fileInfo"
            fileInfoLabel?.isVisible = true
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
        ApplicationManager.getApplication().invokeLater {
            fileInfoLabel?.isVisible = false
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
        
        object : SwingWorker<Int, Void>() {
            override fun doInBackground(): Int {
                return codeIndexingService.indexProject()
            }
            
            override fun done() {
                try {
                    val chunkCount = get()
                    val stats = codeIndexingService.getIndexingStats()
                    
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
     * 인증 성공 시 자동으로 실행되는 프로젝트 인덱싱입니다.
     * 실시간 인덱싱 서비스를 시작하고 진행 상황을 상세히 보고합니다.
     */
    private fun startAutoIndexing() {
        object : SwingWorker<Boolean, String>() {
            override fun doInBackground(): Boolean {
                publish("🔍 프로젝트 파일을 스캔하고 있습니다...")
                Thread.sleep(500) // UI 업데이트를 위한 짧은 지연
                
                publish("📂 지원되는 파일 확장자: java, kt, js, ts, vue, sql, xml, yml, yaml, json")
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
        
        // 텍스트 내용에 따른 동적 크기 계산
        val textMetrics = messageText.getFontMetrics(messageText.font)
        val maxWidth = 450  // 최대 너비 확대
        val minWidth = 100
        val maxHeight = 400  // 메시지 패널 최대 높이 제한
        
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
        val totalHeight = totalLines * lineHeight + 20
        val actualWidth = (maxLineWidth + 30).coerceIn(minWidth, maxWidth)
        val actualHeight = totalHeight.coerceAtMost(maxHeight)  // 최대 높이 제한
        
        // 긴 메시지의 경우 스크롤 가능하도록 JScrollPane 사용
        if (totalHeight > maxHeight) {
            val scrollPane = JBScrollPane(messageText)
            scrollPane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            scrollPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            scrollPane.border = null
            scrollPane.isOpaque = false
            scrollPane.viewport.isOpaque = false
            
            messagePanel.removeAll()
            messagePanel.add(scrollPane, BorderLayout.CENTER)
            
            // 스크롤 패널 크기 설정
            scrollPane.preferredSize = Dimension(actualWidth - 20, maxHeight - 20)
            scrollPane.maximumSize = Dimension(actualWidth - 20, maxHeight - 20)
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
     * 사용자 입력 유형을 분류합니다. (질문, 부분수정, 전체수정, 커서위치생성, RAG질문, 일반)
     */
    private enum class UserInputType { QUESTION, INSTRUCTION, FULL_FILE_INSTRUCTION, CURSOR_CODE_GENERATION, RAG_QUESTION, GENERAL, EXTERNAL_FILE_EDIT, FILE_CREATION }
    /*private fun classifyInput(userInput: String): UserInputType {
        val instructionKeywords = listOf("add", "change", "refactor", "implement", "create", "modify", "improve", "fix", "correct", "추가해", "바꿔줘", "수정해", "리팩토링", "개선해", "고쳐줘", "만들어줘","변경해", "작성해")
        val fullFileKeywords = listOf("전체", "파일", "모든", "전부", "완전히", "처음부터", "새로", "전면", "전체적으로", "whole", "entire", "complete", "full", "all")
        val questionKeywords = listOf("어떻게", "무엇", "언제", "어디서", "왜", "어떤", "설명", "알려줘", "찾아줘", "검색", "how", "what", "when", "where", "why", "which", "explain", "tell", "find", "search")
        val lowerInput = userInput.trim().lowercase()
        
        // 커서 컨텍스트가 설정되어 있는 경우 커서 위치 코드 생성으로 분류
        if (cursorLine != null) {
            return UserInputType.CURSOR_CODE_GENERATION
        }
        
        // 인덱싱된 코드가 있고 질문 키워드가 포함된 경우 RAG 질문으로 분류
        if (codeIndexingService.getAllCodeChunks().isNotEmpty() && 
            questionKeywords.any { lowerInput.contains(it) } &&
            selectedCode == null) {  // 선택된 코드가 없는 경우만
            return UserInputType.RAG_QUESTION
        }
        
        if (instructionKeywords.any { lowerInput.contains(it) }) {
            // 전체 파일 수정 키워드가 포함되어 있고, 선택된 코드가 전체 파일인 경우
            if (fullFileKeywords.any { lowerInput.contains(it) } || isFullFileSelected()) {
                return UserInputType.FULL_FILE_INSTRUCTION
            }
            return UserInputType.INSTRUCTION
        }
        return UserInputType.GENERAL
    }*/
    
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
        // 인증 체크
        if (!isUserAuthenticated()) {
            sendMessage("❌ 인증이 필요합니다. 인증키를 입력해주세요.", isUser = false)
            return
        }
        val codeContext = selectedCode  // 선택된 영역만 사용
        val fileContext = selectedFileInfo
        val editor = FileEditorManager.getInstance(project).selectedTextEditor

        sendMessage(userInput, isUser = true)

        val inputType = classifyInput(userInput)
        val prompt = when {
            inputType == UserInputType.RAG_QUESTION -> {
                // RAG 기반 질문 처리
                val relevantChunks = searchRelevantCode(userInput, 5)
                val contextCode = if (relevantChunks.isNotEmpty()) {
                    buildString {
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
                    }
                } else {
                    "관련 코드를 찾을 수 없습니다."
                }
                
                """
                You are an expert software developer and code analyst specializing in Java, Kotlin, Vue.js, and Tibero DB.
                Your task is to answer the user's question based on the provided project code context.
                
                $contextCode
                
                User question: $userInput
                
                Please provide a detailed answer based on the code context above. 
                Include specific references to the code when relevant, and explain how the code works.
                
                You MUST start your response with "[RAG_QUESTION] " followed by your answer.
                Always respond in Korean.
                """.trimIndent()
            }
            inputType == UserInputType.CURSOR_CODE_GENERATION -> {
                // 커서 위치 기반 새로운 코드 생성
                val lines = fullFileContent?.lines() ?: listOf()
                val numberedContent = lines.mapIndexed { index, line -> 
                    "${index + 1}: $line" 
                }.joinToString("\n")
                
                """
                You are an expert software developer specializing in Java, Kotlin, Vue.js, and Tibero DB.
                Your task is to generate NEW code that should be inserted at the current cursor position.
                This is NOT about modifying existing code, but creating NEW functionality.

                You MUST respond ONLY with the new code in this exact format:

                [NewCode]
                (The new code to be inserted goes here)

                Current file context with line numbers:
                ```
                $numberedContent
                ```

                Current cursor position: Line ${cursorLine}
                Current line content: "${currentLineText}"
                File: ${cursorFileInfo}

                User request: $userInput

                Important guidelines:
                1. Generate NEW code that fits naturally at the cursor position
                2. Maintain proper code structure and formatting
                3. Consider the surrounding code context for proper integration
                4. Follow best practices and coding conventions for ${cursorFileName}
                5. Ensure the new code is syntactically correct and follows the project's style
                6. If imports are needed, include them as part of the generated code
                """.trimIndent()
            }
            inputType == UserInputType.FULL_FILE_INSTRUCTION && codeContext != null -> {
                // 전체 파일 수정 요청 (차분만 받기)
                val lines = codeContext.lines()
                val numberedContent = lines.mapIndexed { index, line -> 
                    "${index + 1}: $line" 
                }.joinToString("\n")
                
                """
                You are an expert software developer specializing in Java, Kotlin, Vue.js, and Tibero DB.
                Your task is to analyze the source file and provide ONLY the changes needed, not the entire file.
                This will help reduce token usage significantly.

                You MUST respond with only the specific changes in this exact format:

                [FileChanges]
                OPERATION:LINE_NUMBER:ORIGINAL_LINE:NEW_LINE
                OPERATION:LINE_NUMBER:ORIGINAL_LINE:NEW_LINE
                ...

                Where OPERATION can be:
                - REPLACE: Replace existing line
                - INSERT: Insert new line after the specified line number
                - DELETE: Delete the specified line

                Current file content with line numbers:
                ```
                $numberedContent
                ```

                File: $fileContext
                User request: $userInput

                Example response format:
                [FileChanges]
                REPLACE:15:    public void oldMethod() {:    public void newMethod() {
                INSERT:20::        // This is a new comment
                DELETE:25:    // Old comment:

                Important guidelines:
                1. Provide ONLY the lines that need to be changed, inserted, or deleted
                2. Be precise with line numbers (1-based indexing)
                3. Maintain proper code structure and formatting
                4. Keep existing functionality intact unless specifically requested to change
                5. Add necessary imports if new features are added (use INSERT operations)
                6. Follow best practices and coding conventions
                """.trimIndent()
            }
            inputType == UserInputType.FILE_CREATION -> {
                // 새 파일 생성 요청 처리
                val projectStructureInfo = buildProjectStructureInfo()
                """
                You are an expert software developer specializing in Java, Kotlin, Vue.js, and Tibero DB.
                Your task is to create a new file based on the user's request and the current project structure.
                
                $projectStructureInfo
                
                You MUST respond with the following format:

                [FileCreation]
                FILE_PATH: (relative path where the file should be created based on project structure above)
                FILE_NAME: (name of the file including extension)
                TEMPLATE_TYPE: (JAVA_CLASS, JAVA_INTERFACE, JAVA_ENUM, KOTLIN_CLASS, VUE_COMPONENT, XML_CONFIG, JSON_CONFIG, or CUSTOM)
                CLASS_NAME: (if applicable, the main class/component name)
                PACKAGE_NAME: (if applicable, the package name based on existing patterns)
                CONTENT:
                (The complete file content goes here)

                User request: $userInput

                Important guidelines:
                1. Use the project structure information above to determine the most appropriate file location
                2. Follow existing package naming conventions from the project structure
                3. Choose the correct template type based on file extension and content
                4. Create meaningful class/component names that fit the project's naming patterns
                5. Follow language-specific best practices and conventions seen in existing code
                6. Include necessary imports and dependencies consistent with project structure
                7. Add proper documentation and comments
                8. Ensure the new file integrates well with the existing codebase structure
                """.trimIndent()
            }
            inputType == UserInputType.EXTERNAL_FILE_EDIT -> {
                // 외부 파일 수정 요청 처리
                """
                You are an expert software developer specializing in Java, Kotlin, Vue.js, and Tibero DB.
                Your task is to modify an external file based on the user's request.

                You MUST respond with the following format:

                [ExternalFileEdit]
                FILE_PATH: (path to the file to be modified)
                OPERATION: (MODIFY_EXISTING or CREATE_NEW)
                CONTENT:
                (The complete modified file content goes here)

                User request: $userInput

                Important guidelines:
                1. Extract file path from the user's request
                2. If the file doesn't exist, set OPERATION to CREATE_NEW
                3. If the file exists, set OPERATION to MODIFY_EXISTING
                4. Provide complete file content with modifications
                5. Maintain existing code structure and formatting
                6. Follow language-specific best practices
                7. Add proper error handling if applicable
                """.trimIndent()
            }
            inputType == UserInputType.INSTRUCTION && codeContext != null -> {
                // INSTRUCTION 유형일 경우, 선택 영역 수정 요청
                """
                You are an expert software developer specializing in Java, Kotlin, Vue.js, and Tibero DB.
                Your task is to modify the selected source code snippet based on the user's request.
                You MUST respond ONLY with the modified source code, following this exact format:

                [Modified]
                (The new, modified code snippet goes here)

                Original selected code:
                ```
                $codeContext
                ```

                User request: $userInput
                """.trimIndent()
            }
            else -> {
                // 그 외의 경우, 일반적인 프롬프트 사용
                val basePrompt = if (codeContext != null) {
                    "User selected code from $fileContext: \n```\n$codeContext\n```\n\nUser query: $userInput"
                } else {
                    userInput
                }
                
                """
                You are an expert software developer and code analyst specializing in Java, Kotlin, Vue.js, and Tibero DB.
                
                $basePrompt
                
                You MUST start your response with "[${inputType.name}] " followed by your answer.
                Always respond in Korean.
                """.trimIndent()
            }
        }

        ApplicationManager.getApplication().invokeLater { loadingIndicator?.isVisible = true }

        // 스트리밍 모드: 첫 델타가 도착하면 패널을 생성하고, 이후 델타는 누적 업데이트합니다.
        val initialPanelRef = arrayOfNulls<JPanel>(1)
        val initialTextAreaRef = arrayOfNulls<JTextArea>(1)

        apiClient.sendChatRequestStream(
            userMessage = prompt,
            systemMessage = systemMessage,
            onDelta = { delta ->
                ApplicationManager.getApplication().invokeLater {
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
                        }
                    } else {
                        // 이후 델타는 누적하고, 전체 텍스트 기준으로 버블을 재생성하여 크기를 정확히 맞춤
                        val newText = existingText.text + delta
                        rebuildMessagePanel(existingPanel, newText) { newPanel ->
                            initialPanelRef[0] = newPanel
                            initialTextAreaRef[0] = findTextArea(newPanel)
                        }
                        scrollToBottom()
                    }
                }
            },
            onComplete = {
                ApplicationManager.getApplication().invokeLater {
                    loadingIndicator?.isVisible = false
                    clearSelectionContext()
                    clearCursorContext()
                }
            },
            onError = { e ->
                ApplicationManager.getApplication().invokeLater {
                    loadingIndicator?.isVisible = false
                    sendMessage("오류가 발생했습니다: ${e.message}", isUser = false)
                    clearSelectionContext()
                    clearCursorContext()
                }
            }
        )
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
            scrollBar.value = scrollBar.maximum
            javax.swing.SwingUtilities.invokeLater { scrollBar.value = scrollBar.maximum }
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
            
            val originalCode = selectedCode ?: return  // 로컬 선택 영역 사용

            val fileText = document.text
            val startOffset = fileText.indexOf(originalCode)
            if (startOffset != -1) {
                val endOffset = startOffset + originalCode.length

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
                sendMessage("코드 수정 제안을 받았습니다. diff 창에서 확인 후 '적용' 또는 '거절'을 선택해주세요.", isUser = false)
            } else {
                sendMessage("원본 코드를 현재 파일에서 찾을 수 없습니다. LLM이 코드를 일부 변경하여 응답했을 수 있습니다.", isUser = false)
                sendMessage("LLM 응답:\n$modifiedCode", isUser = false)
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
            ".*/.*", // Unix 스타일 경로
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
        return UserInputType.GENERAL
    }
    
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
                val content = String(virtualFile.contentsToByteArray(), virtualFile.charset)
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
}