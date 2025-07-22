package org.dev.semaschatbot

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.util.base.DiffViewerBase

import com.intellij.openapi.actionSystem.*
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
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.JBColor
import com.intellij.icons.AllIcons
import groovy.util.logging.Slf4j
import java.awt.Color
import java.util.regex.Pattern
import javax.swing.JLabel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingWorker
import javax.swing.SwingUtilities

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
    var systemMessage: String = "You are a helpful assistant. Please respond in Korean."

    var chatLog: JTextArea? = null
    var scrollPane: JScrollPane? = null
    var loadingIndicator: JLabel? = null
    var fileInfoLabel: JLabel? = null

    private var selectedCode: String? = null
    private var selectedFileInfo: String? = null

    // 여러 개의 동시 변경 제안을 관리하기 위한 리스트
    val pendingChanges = mutableListOf<PendingChange>()
    
    // 전체 파일 변경 제안을 관리하기 위한 변수
    private var pendingFileChange: PendingFileChange? = null

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
     * 챗봇 UI에 메시지를 추가합니다.
     * @param message 표시할 메시지
     * @param isUser 사용자가 보낸 메시지인지 여부
     */
    fun sendMessage(message: String, isUser: Boolean = true) {
        ApplicationManager.getApplication().invokeLater {
            chatLog?.append(if (isUser) "나: $message\n\n" else "소진공: $message\n\n")
            scrollPane?.verticalScrollBar?.value = scrollPane?.verticalScrollBar?.maximum ?: 0
        }
    }

    /**
     * 사용자 입력 유형을 분류합니다. (질문, 부분수정, 전체수정, 일반)
     */
    private enum class UserInputType { QUESTION, INSTRUCTION, FULL_FILE_INSTRUCTION, GENERAL }
    private fun classifyInput(userInput: String): UserInputType {
        val instructionKeywords = listOf("add", "change", "refactor", "implement", "create", "modify", "improve", "fix", "correct", "추가해", "바꿔줘", "수정해", "리팩토링", "개선해", "고쳐줘", "만들어줘","변경해")
        val fullFileKeywords = listOf("전체", "파일", "모든", "전부", "완전히", "처음부터", "새로", "전면", "전체적으로", "whole", "entire", "complete", "full", "all")
        val lowerInput = userInput.trim().lowercase()
        
        if (instructionKeywords.any { lowerInput.contains(it) }) {
            // 전체 파일 수정 키워드가 포함되어 있고, 선택된 코드가 전체 파일인 경우
            if (fullFileKeywords.any { lowerInput.contains(it) } || isFullFileSelected()) {
                return UserInputType.FULL_FILE_INSTRUCTION
            }
            return UserInputType.INSTRUCTION
        }
        return UserInputType.GENERAL
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
        val codeContext = selectedCode  // 선택된 영역만 사용
        val fileContext = selectedFileInfo
        val editor = FileEditorManager.getInstance(project).selectedTextEditor

        sendMessage(userInput, isUser = true)

        val inputType = classifyInput(userInput)
        val prompt = when {
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
                if (codeContext != null) {
                    "User selected code from $fileContext: \n```\n$codeContext\n```\n\nUser query: $userInput"
                } else {
                    userInput
                }
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
}