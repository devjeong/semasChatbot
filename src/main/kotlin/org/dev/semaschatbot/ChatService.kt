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
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.JBColor
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

/**
 * 사용자 입력 타입을 나타내는 열거형입니다.
 */
enum class UserInputType {
    RAG_QUESTION,           // 코드베이스 기반 질문
    INSTRUCTION,            // 코드 수정/개선 지시
    CURSOR_CODE_GENERATION, // 커서 위치 코드 생성
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

@Slf4j
@Service(Service.Level.PROJECT)
class ChatService(private val project: Project) {

    private val apiClient = LmStudioClient()
    private val codeIndexingService = CodeIndexingService(project)
    var systemMessage: String = "You are a helpful assistant. Please respond in Korean."

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
     * 진행 상황을 상세히 보고합니다.
     */
    private fun startAutoIndexing() {
        object : SwingWorker<Int, String>() {
            override fun doInBackground(): Int {
                publish("🔍 프로젝트 파일을 스캔하고 있습니다...")
                Thread.sleep(500) // UI 업데이트를 위한 짧은 지연
                
                publish("📂 지원되는 파일 확장자: java, kt, js, ts, vue, sql, xml, yml, yaml, json")
                Thread.sleep(500)
                
                publish("⚙️ PSI 트리를 분석하여 코드 구조를 파악합니다...")
                Thread.sleep(500)
                
                val chunkCount = codeIndexingService.indexProject()
                
                publish("🔧 인덱싱 통계를 생성하고 있습니다...")
                Thread.sleep(300)
                
                return chunkCount
            }
            
            override fun process(chunks: List<String>) {
                // 진행 상황 메시지들을 실시간으로 전송
                chunks.forEach { message ->
                    sendMessage(message, isUser = false)
                }
            }
            
            override fun done() {
                try {
                    val chunkCount = get()
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
                // messagePanel 간 간격 완전 제거
                panel.add(messagePanel)
                panel.revalidate()
                panel.repaint()
                
                // 스크롤을 맨 아래로 이동
                scrollPane?.let { scroll ->
                    scroll.verticalScrollBar.value = scroll.verticalScrollBar.maximum
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
        val maxWidth = 350
        val minWidth = 80
        
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
        val totalHeight = totalLines * lineHeight
        val actualWidth = (maxLineWidth + 30).coerceIn(minWidth, maxWidth)
        val actualHeight = totalHeight + 20
        
        // 패널 크기 조정 - 내용에 맞게 동적으로 설정
        messagePanel.preferredSize = Dimension(actualWidth, actualHeight)
        messagePanel.maximumSize = Dimension(maxWidth, actualHeight)
        messagePanel.minimumSize = Dimension(minWidth, actualHeight)
        
        // 컨테이너 패널도 동일한 높이로 설정
        containerPanel.preferredSize = Dimension(Int.MAX_VALUE, actualHeight)
        containerPanel.maximumSize = Dimension(Int.MAX_VALUE, actualHeight)
        
        messageWrapper.add(messagePanel)
        containerPanel.add(messageWrapper, BorderLayout.CENTER)
        
        return containerPanel
    }

    /**
     * 사용자 입력 유형을 분류합니다. (질문, 부분수정, 전체수정, 커서위치생성, RAG질문, 일반)
     */
    private enum class UserInputType { QUESTION, INSTRUCTION, FULL_FILE_INSTRUCTION, CURSOR_CODE_GENERATION, RAG_QUESTION, GENERAL }
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

        object : SwingWorker<String?, Void>() {
            override fun doInBackground(): String? = apiClient.sendChatRequest(prompt, systemMessage)

            override fun done() {
                ApplicationManager.getApplication().invokeLater { loadingIndicator?.isVisible = false }
                try {
                    val response = get()
                    if (response != null) {
                        when (inputType) {
                            UserInputType.RAG_QUESTION -> {
                                // RAG 기반 답변 처리 (일반 텍스트 응답)
                                sendMessage(response, isUser = false)
                            }
                            UserInputType.CURSOR_CODE_GENERATION -> {
                                if (editor != null) {
                                    handleCursorCodeGenerationResponse(response, editor)
                                } else {
                                    sendMessage("에디터가 활성화되지 않았습니다.", isUser = false)
                                }
                            }
                            UserInputType.FULL_FILE_INSTRUCTION -> {
                                if (editor != null) {
                                    handleFullFileInstructionResponse(response, editor)
                                } else {
                                    sendMessage("에디터가 활성화되지 않았습니다.", isUser = false)
                                }
                            }
                            UserInputType.INSTRUCTION -> {
                                if (editor != null) {
                                    handleInstructionResponse(response, editor)
                                } else {
                                    sendMessage("에디터가 활성화되지 않았습니다.", isUser = false)
                                }
                            }
                            else -> {
                                // 일반 응답 처리
                                sendMessage(response, isUser = false)
                            }
                        }
                    } else {
                        sendMessage("API 호출 실패. 서버를 확인하세요.", isUser = false)
                    }
                } catch (e: Exception) {
                    sendMessage("오류가 발생했습니다: ${e.message}", isUser = false)
                } finally {
                    clearSelectionContext()
                    clearCursorContext()
                }
            }
        }.execute()
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
}