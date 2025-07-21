package org.dev.semaschatbot

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import javax.swing.JTextArea
import javax.swing.JScrollPane
import javax.swing.JLabel
import javax.swing.SwingWorker
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages

/**
 * `ChatService`는 챗봇의 핵심 로직을 담당하는 프로젝트 레벨 서비스입니다.
 * LLM(Large Language Model)과의 통신, 챗봇 UI 업데이트, 코드 분석 및 수정 요청 처리 등
 * 챗봇의 주요 기능을 수행합니다.
 * IntelliJ IDEA의 서비스 메커니즘을 통해 프로젝트 전반에서 접근하고 사용될 수 있습니다.
 */
@Service(Service.Level.PROJECT)
class ChatService(private val project: Project) {

    private val apiClient = LmStudioClient() // LM Studio API와 통신하기 위한 클라이언트 인스턴스

    var systemMessage: String = "너는 JAVA 개발자이고, JAVA ,TIBERO DB, VUE.JS 전문가로써 상세하지만 장황하지 않게 답변해주고 한국어로 답변해줘. 그리고 소스 수정 요청 시에는, 실제 명령어 보다는 소스 그 자체로만 답변해줘."

    /**
     * 챗봇 대화 내용을 표시하는 JTextArea 컴포넌트에 대한 참조입니다.
     * LLMChatToolWindowFactory에서 UI 생성 시 설정됩니다.
     */
    var chatLog: JTextArea? = null
    /**
     * 챗봇 대화 영역의 스크롤 패인 컴포넌트에 대한 참조입니다.
     * 새 메시지 추가 시 자동으로 스크롤을 최하단으로 이동시키는 데 사용됩니다.
     */
    var scrollPane: JScrollPane? = null
    /**
     * LLM 요청 시 로딩 상태를 표시하는 JLabel 컴포넌트에 대한 참조입니다.
     * LLMChatToolWindowFactory에서 UI 생성 시 설정됩니다.
     */
    var loadingIndicator: JLabel? = null

    /**
     * 챗봇 UI에 메시지를 추가하고 스크롤을 최신 메시지로 이동시킵니다.
     * 이 함수는 UI 스레드에서 실행되어야 합니다.
     * @param message 표시할 메시지 내용
     * @param isUser 메시지를 보낸 주체가 사용자인지(true) 챗봇인지(false) 나타냅니다.
     */
    fun sendMessage(message: String, isUser: Boolean = true) {
        ApplicationManager.getApplication().invokeLater {
            chatLog?.append(if (isUser) "나 : $message\n\n" else "소진공 : $message\n\n")
            scrollPane?.verticalScrollBar?.value = scrollPane?.verticalScrollBar?.maximum ?: 0
        }
    }

    /**
     * 선택된 코드와 사용자 질의를 LLM에 전송하고 응답을 받아 챗봇 UI에 표시합니다.
     * 이 함수는 백그라운드 스레드에서 LLM 요청을 처리하여 UI가 블록되지 않도록 합니다.
     * @param selectedText 사용자가 에디터에서 선택한 코드 스니펫
     * @param userQuery 코드에 대한 사용자 질의 (선택 사항)
     */
    fun sendCodeToLLM(selectedText: String, userQuery: String = "") {
        val fullPrompt = if (userQuery.isNotEmpty()) {
            "User selected code: \n```\n$selectedText\n```\n\nUser query: $userQuery"
        } else {
            "Analyze the following code: \n```\n$selectedText\n```"
        }

        sendMessage("선택 코드: \n```\n$selectedText\n```", isUser = true)
        if (userQuery.isNotEmpty()) {
            sendMessage("질문: $userQuery", isUser = true)
        }

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("소진공 챗봇(개발자전용)")
        toolWindow?.activate(null)

        // 백그라운드 스레드에서 LLM API 호출을 수행하기 위한 SwingWorker를 생성합니다.
        ApplicationManager.getApplication().invokeLater { // UI 업데이트는 EDT에서 수행
            loadingIndicator?.isVisible = true // 로딩 인디케이터 표시
        }
        object : SwingWorker<String?, Void>() {
            override fun doInBackground(): String? { // 백그라운드 작업: LLM에 채팅 요청을 보냅니다.
                return apiClient.sendChatRequest(fullPrompt, systemMessage)
            }

            override fun done() { // 백그라운드 작업 완료 후 UI 스레드에서 실행될 작업입니다.
                ApplicationManager.getApplication().invokeLater { // UI 업데이트는 EDT에서 수행
                    loadingIndicator?.isVisible = false // 로딩 인디케이터 숨김
                }
                val response = get() // LLM으로부터 받은 응답을 가져옵니다.
                if (response != null) { // 응답이 성공적으로 도착한 경우
                    sendMessage(response, isUser = false) // 챗봇 응답을 UI에 표시합니다.
                } else { // 응답이 null이거나 오류가 발생한 경우
                    sendMessage("API 호출 실패. 서버를 확인하세요.", isUser = false) // 오류 메시지를 UI에 표시합니다.
                }
            }
        }.execute() // SwingWorker를 실행하여 백그라운드 작업을 시작합니다.
    }

    /**
     * 사용자 입력의 유형을 정의하는 enum 클래스입니다.
     * - QUESTION: 질문 형태의 입력
     * - INSTRUCTION: 특정 작업을 지시하는 형태의 입력 (예: 코드 수정 요청)
     * - GENERAL: 일반적인 대화 형태의 입력
     */
    private enum class UserInputType { QUESTION, INSTRUCTION, GENERAL }

    /**
     * 사용자 입력 문자열을 분석하여 그 유형(질문, 지시, 일반)을 분류합니다.
     * @param userInput 사용자가 입력한 문자열
     * @return 분류된 UserInputType (QUESTION, INSTRUCTION, GENERAL)
     */
    private fun classifyInput(userInput: String): UserInputType {
        val questionKeywords = listOf("what", "how", "why", "explain", "is", "can", "do", "does", "뭐야", "어떻게", "왜", "설명해", "알려줘")
        val instructionKeywords = listOf("add", "change", "refactor", "implement", "create", "modify", "improve", "fix", "correct", "추가해", "바꿔줘", "수정해", "리팩토링", "개선해", "고쳐줘", "만들어줘", "바꿔줘")
        val lowerInput = userInput.trim().lowercase()

        if (lowerInput.endsWith("?")) return UserInputType.QUESTION
        if (questionKeywords.any { lowerInput.startsWith(it) }) {
            return UserInputType.QUESTION
        }
        if (instructionKeywords.any { lowerInput.contains(it) }) {
            return UserInputType.INSTRUCTION
        }
        return UserInputType.GENERAL
    }

    /**
     * 사용자 입력에 따라 LLM에 채팅 요청을 보냅니다.
     * 입력 유형에 따라 다른 프롬프트 전략을 사용합니다.
     * @param userInput 사용자가 챗봇에 입력한 문자열
     */
    fun sendChatRequestToLLM(userInput: String) {
        val userInputType = classifyInput(userInput) // 사용자 입력 유형을 분류합니다.
        val typePrefix = "[${userInputType.name}]" // 분류된 유형에 따라 접두사를 생성합니다.

        // 현재 활성화된 에디터와 문서, 선택된 텍스트 정보를 가져옵니다.
        val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor
        val document = editor?.document
        val selectedText = editor?.selectionModel?.selectedText

        // 에디터에서 텍스트가 선택되었는지 여부에 따라 소스 텍스트를 결정합니다.
        // 선택된 텍스트가 있으면 해당 텍스트를 사용하고, 없으면 문서 전체 텍스트를 사용합니다.
        val (sourceText, isSelection) = if (!selectedText.isNullOrEmpty()) {
            selectedText to true
        } else {
            document?.text to false
        }

        // 소스 텍스트가 없는데 사용자 입력 유형이 GENERAL이 아닌 경우 (즉, 코드 분석이나 수정이 필요한 경우)
        // 사용자에게 분석할 코드가 없음을 알리고 함수를 종료합니다.
        if (sourceText.isNullOrEmpty() && userInputType != UserInputType.GENERAL) {
            sendMessage("분석할 코드가 없습니다. 파일을 열거나 코드를 선택해주세요.", isUser = false)
            return
        }

        val fullSourceCode = document?.text // 문서 전체의 소스 코드를 가져옵니다.
        // 사용자 입력 유형에 따라 LLM에 보낼 프롬프트를 구성합니다.
        val fullPrompt = when (userInputType) {
            UserInputType.QUESTION -> // 질문 유형일 경우
                "Answer the following question based on the provided source code.\n\n                " +
                        "Question: \"$userInput\"\n\n                " +
                        "Source Code:\n                ```\n                " +
                        "$sourceText\n                ```\n                "
            UserInputType.INSTRUCTION -> // 지시 유형일 경우
                if (isSelection) { // 특정 코드가 선택된 경우
                    "Modify or improve the following selected code and return the modified code in " +
                            "``` block format: \n```$sourceText``` \n" +
                            "User query: $userInput"
                } else { // 선택된 코드 없이 전체 문서에 대한 지시일 경우
                    "\n                    Based on the user\'s request, identify all the necessary code modifications in the full source code provided below.\n\n                    User Request: \"$userInput\"\n\n                    Please respond by providing a series of change sets. Each set must contain:\n                    1. A header comment in the format: // CHANGE N/M (where N is the current change number and M is the total number of changes).\n                    2. A markdown block with the EXACT original code snippet you are replacing. Use ```original as the language identifier.\n                    3. A markdown block with the new, modified code snippet. Use ```modified as the language identifier.\n\n                    It is crucial that you provide all changes in this structured format.\n\n                    Full Source Code: \"$fullSourceCode\"\n                    "
                }
            UserInputType.GENERAL -> // 일반 대화 유형일 경우
                "Provide a general response to the following user input, more friendly.\n\n                User Input:  $userInput "
        }

        // 백그라운드 스레드에서 LLM API 호출을 수행하기 위한 SwingWorker를 생성합니다.
        ApplicationManager.getApplication().invokeLater { // UI 업데이트는 EDT에서 수행
            loadingIndicator?.isVisible = true // 로딩 인디케이터 표시
        }
        object : SwingWorker<String?, Void>() {
            override fun doInBackground(): String? { // 백그라운드 작업: LLM에 채팅 요청을 보냅니다.
                return apiClient.sendChatRequest(fullPrompt, systemMessage)
            }

            override fun done() { // 백그라운드 작업 완료 후 UI 스레드에서 실행될 작업입니다.
                ApplicationManager.getApplication().invokeLater { // UI 업데이트는 EDT에서 수행
                    loadingIndicator?.isVisible = false // 로딩 인디케이터 숨김
                }
                val response = get() // LLM으로부터 받은 응답을 가져옵니다.
                if (response != null) { // 응답이 성공적으로 도착한 경우
                    sendMessage(response, isUser = false) // 챗봇 응답을 UI에 표시합니다.
                } else { // 응답이 null이거나 오류가 발생한 경우
                    sendMessage("API 호출 실패. 서버를 확인하세요.", isUser = false) // 오류 메시지를 UI에 표시합니다。
                }
            }
        }.execute() // SwingWorker를 실행하여 백그라운드 작업을 시작합니다.
    }
}
