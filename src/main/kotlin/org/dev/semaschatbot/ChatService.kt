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
     * 사용자 입력 유형을 분류합니다. (질문, 지시, 일반)
     */
    private enum class UserInputType { QUESTION, INSTRUCTION, GENERAL }
    private fun classifyInput(userInput: String): UserInputType {
        val instructionKeywords = listOf("add", "change", "refactor", "implement", "create", "modify", "improve", "fix", "correct", "추가해", "바꿔줘", "수정해", "리팩토링", "개선해", "고쳐줘", "만들어줘","변경해")
        val lowerInput = userInput.trim().lowercase()
        if (instructionKeywords.any { lowerInput.contains(it) }) {
            return UserInputType.INSTRUCTION
        }
        return UserInputType.GENERAL
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
        val prompt = if (inputType == UserInputType.INSTRUCTION && codeContext != null) {
            // INSTRUCTION 유형일 경우, 선택 영역 수정 요청
            """
            You are an expert software developer specializing in Java, Vue.js, and Tibero DB.
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
        } else {
            // 그 외의 경우, 일반적인 프롬프트 사용
            if (codeContext != null) {
                "User selected code from $fileContext: \n```\n$codeContext\n```\n\nUser query: $userInput"
            } else {
                userInput
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
                        if (inputType == UserInputType.INSTRUCTION && editor != null) {
                            // INSTRUCTION 응답 처리
                            handleInstructionResponse(response, editor)
                        } else {
                            // 일반 응답 처리
                            sendMessage(response, isUser = false)
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
}