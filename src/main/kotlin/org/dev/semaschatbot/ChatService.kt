package org.dev.semaschatbot

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import javax.swing.JLabel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingWorker

@Service(Service.Level.PROJECT)
class ChatService(private val project: Project) {

    private val apiClient = LmStudioClient()
    var systemMessage: String = "너는 JAVA 개발자이고, JAVA ,TIBERO DB, VUE.JS 전문가로써 상세하지만 장황하지 않게 답변해주고 한국어로 답변해줘. 그리고 소스 수정 요청 시에는, 실제 명령어 보다는 소스 그 자체로만 답변해줘."

    var chatLog: JTextArea? = null
    var scrollPane: JScrollPane? = null
    var loadingIndicator: JLabel? = null
    var fileInfoLabel: JLabel? = null

    private var selectedCode: String? = null
    private var selectedFileInfo: String? = null

    fun setSelectionContext(code: String, fileInfo: String) {
        selectedCode = code
        selectedFileInfo = fileInfo
        ApplicationManager.getApplication().invokeLater {
            fileInfoLabel?.text = "선택된 파일: $fileInfo"
            fileInfoLabel?.isVisible = true
        }
    }

    private fun clearSelectionContext() {
        selectedCode = null
        selectedFileInfo = null
        ApplicationManager.getApplication().invokeLater {
            fileInfoLabel?.isVisible = false
        }
    }

    fun sendMessage(message: String, isUser: Boolean = true) {
        ApplicationManager.getApplication().invokeLater {
            chatLog?.append(if (isUser) "나: $message\n\n" else "소진공: $message\n\n")
            scrollPane?.verticalScrollBar?.value = scrollPane?.verticalScrollBar?.maximum ?: 0
        }
    }

    fun sendChatRequestToLLM(userInput: String) {
        val codeContext = selectedCode
        if (codeContext != null) {
            sendMessage("선택 코드: \n```\n$codeContext\n```", isUser = true)
        }

        val fullPrompt = if (codeContext != null) {
            "User selected code: \n```\n$codeContext\n```\n\nUser query: $userInput"
        } else {
            userInput
        }

        ApplicationManager.getApplication().invokeLater {
            loadingIndicator?.isVisible = true
        }

        object : SwingWorker<String?, Void>() {
            override fun doInBackground(): String? {
                return apiClient.sendChatRequest(fullPrompt, systemMessage)
            }

            override fun done() {
                ApplicationManager.getApplication().invokeLater {
                    loadingIndicator?.isVisible = false
                }
                try {
                    val response = get()
                    if (response != null) {
                        sendMessage(response, isUser = false)
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
}
