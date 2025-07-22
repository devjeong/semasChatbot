package org.dev.semaschatbot

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.Color
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.JLabel
import javax.swing.JTextArea
import javax.swing.JScrollPane
import javax.swing.JOptionPane
import javax.swing.border.EmptyBorder

/**
 * LLMChatToolWindowFactory는 IntelliJ IDEA의 툴 윈도우를 생성하고 관리하는 팩토리 클래스입니다.
 * ToolWindowFactory 인터페이스를 구현하여 챗봇 툴 윈도우의 UI를 구성하고 초기화합니다.
 */
class LLMChatToolWindowFactory : ToolWindowFactory {

    /**
     * 툴 윈도우의 내용을 생성하고 UI 컴포넌트들을 초기화합니다.
     * 이 메서드는 툴 윈도우가 처음 열릴 때 호출됩니다.
     * @param project 현재 IntelliJ 프로젝트 인스턴스
     * @param toolWindow 생성될 툴 윈도우 인스턴스
     */
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val chatService = project.service<ChatService>() // ChatService 인스턴스를 가져옵니다. 챗봇의 핵심 로직을 담당합니다.

        // 툴 윈도우의 메인 패널을 생성합니다. BorderLayout을 사용하여 컴포넌트들을 배치합니다.
        val panel = JPanel(BorderLayout())
        panel.background = Color.WHITE // 패널의 배경색을 흰색으로 설정합니다.

        // 챗봇의 대화 기록을 표시할 텍스트 영역을 생성합니다.
        val chatLogArea = JBTextArea()
        chatLogArea.isEditable = false // 사용자가 직접 편집할 수 없도록 설정합니다.
        chatLogArea.lineWrap = true // 자동 줄바꿈을 활성화합니다.
        chatLogArea.wrapStyleWord = true // 단어 단위로 줄바꿈되도록 설정합니다.
        val scrollPane = JBScrollPane(chatLogArea) // 텍스트 영역에 스크롤 기능을 추가합니다.
        panel.add(scrollPane, BorderLayout.CENTER) // 메인 패널의 중앙에 스크롤 가능한 텍스트 영역을 추가합니다.

        // 사용자 입력을 위한 패널과 컴포넌트들을 생성합니다.
        val inputPanel = JPanel(BorderLayout()) // 입력 필드와 버튼을 포함할 패널입니다.
        val loadingLabel = JLabel("로딩 중...") // 로딩 인디케이터 레이블 생성
        loadingLabel.isVisible = false // 초기에는 보이지 않도록 설정
        inputPanel.add(loadingLabel, BorderLayout.WEST) // 입력 패널의 왼쪽에 로딩 인디케이터 추가
        val inputField = JBTextArea() // 사용자 메시지를 입력할 텍스트 필드입니다.
        inputField.rows = 3
        inputField.lineWrap = true
        inputField.wrapStyleWord = true
        val inputScrollPane = JBScrollPane(inputField)
        inputField.setBorder(BorderFactory.createCompoundBorder(
            inputField.border,
            EmptyBorder(5, 5, 5, 5) // 입력 필드에 내부 여백을 추가합니다.
        ))
        val sendButton = JButton("Send(Ctrl+Enter)") // 메시지 전송 버튼입니다.
        val resetButton = JButton("Reset") // 대화 초기화 버튼입니다.
        val promptButton = JButton("Prompt") // 프롬프트 수정 버튼입니다.
        val analyzeFileButton = JButton("전체 파일 분석") // 전체 파일 분석 버튼입니다.

        val topPanel = JPanel(BorderLayout())
        val leftButtonPanel = JPanel()
        leftButtonPanel.add(promptButton)
        leftButtonPanel.add(analyzeFileButton)
        topPanel.add(leftButtonPanel, BorderLayout.WEST)

        inputPanel.add(inputScrollPane, BorderLayout.CENTER) // 입력 패널의 중앙에 입력 필드를 추가합니다.
        val buttonPanel = JPanel(BorderLayout()) // 버튼들을 담을 패널을 생성합니다.
        buttonPanel.add(sendButton, BorderLayout.WEST) // 전송 버튼을 버튼 패널의 왼쪽에 추가합니다.
        buttonPanel.add(resetButton, BorderLayout.EAST) // 초기화 버튼을 버튼 패널의 오른쪽에 추가합니다.
        inputPanel.add(buttonPanel, BorderLayout.EAST) // 입력 패널의 오른쪽에 버튼 패널을 추가합니다.

        val fileInfoLabel = JLabel("") // 파일 정보를 표시할 레이블
        fileInfoLabel.border = EmptyBorder(0, 5, 5, 5) // 여백 추가
        fileInfoLabel.isVisible = false // 초기에는 숨김

        val southPanel = JPanel(BorderLayout())
        southPanel.add(fileInfoLabel, BorderLayout.NORTH) // 파일 정보 레이블을 입력 패널 위에 추가
        southPanel.add(inputPanel, BorderLayout.CENTER)

        panel.add(topPanel, BorderLayout.NORTH)
        panel.add(southPanel, BorderLayout.SOUTH) // 메인 패널의 하단에 입력 패널을 추가합니다.

        // 할당할 chatLogArea를 chatService의 chatLog 속성에 저장
        chatService.chatLog = chatLogArea

        // 할당할 scrollPane을 chatService의 scrollPane 속성에 저장
        chatService.scrollPane = scrollPane

        // 할당할 loadingLabel을 chatService의 loadingIndicator 속성에 저장
        chatService.loadingIndicator = loadingLabel

        // 할당할 fileInfoLabel을 chatService의 fileInfoLabel 속성에 저장
        chatService.fileInfoLabel = fileInfoLabel

        // 'Send' 버튼 클릭 시 동작을 정의합니다.
        sendButton.addActionListener {
            val message = inputField.text // 입력 필드의 텍스트를 가져옵니다.
            if (message.isNotBlank()) { // 메시지가 비어있지 않은 경우에만 처리합니다.
                chatService.sendChatRequestToLLM(message) // LLM에 채팅 요청을 보냅니다.
                inputField.text = "" // 입력 필드를 초기화합니다.
            }
        }

        // 'Prompt' 버튼 클릭 시 동작을 정의합니다.
        promptButton.addActionListener {
            val currentSystemMessage = chatService.systemMessage

            // 다이얼로그에 표시할 JTextArea 생성
            val textArea = JTextArea(15, 60) // 15행 60열 크기의 JTextArea
            textArea.text = currentSystemMessage
            textArea.wrapStyleWord = true
            textArea.lineWrap = true

            // JTextArea를 JScrollPane에 추가하여 스크롤 가능하게 만듦
            val scrollPane = JScrollPane(textArea)

            // JOptionPane을 사용하여 다이얼로그 표시
            val result = JOptionPane.showConfirmDialog(
                panel,
                scrollPane,
                "Edit System Prompt",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
            )

            // 사용자가 OK를 눌렀을 경우
            if (result == JOptionPane.OK_OPTION) {
                val newSystemMessage = textArea.text
                if (newSystemMessage.isNotBlank()) {
                    chatService.systemMessage = newSystemMessage
                    chatService.sendMessage("Prompt가 변경되었습니다.", isUser = false)
                }
            }
        }





        // '전체 파일 분석' 버튼 클릭 시 동작을 정의합니다.
        analyzeFileButton.addActionListener {
            chatService.setFullFileContext()
        }

        // 'Reset' 버튼 클릭 시 동작을 정의합니다.
        resetButton.addActionListener {
            chatLogArea.text = "" // 챗봇 대화 기록을 지웁니다.
            ApplicationManager.getApplication().invokeLater {
                chatService.sendMessage("대화가 초기화되었습니다.", isUser = false) // 챗봇에 초기화 메시지를 표시합니다.
            }
        }

        // 입력 필드에서 Enter 키를 눌렀을 때 'Send' 버튼 클릭과 동일하게 동작하도록 설정합니다.
        inputField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.isControlDown && e.keyCode == KeyEvent.VK_ENTER) {
                    sendButton.doClick()
                }
            }
        })

        // ContentFactory를 사용하여 툴 윈도우에 표시될 Content 객체를 생성합니다.
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(panel, "", false) // 생성된 패널을 Content로 래핑합니다.
        toolWindow.contentManager.addContent(content) // 툴 윈도우의 ContentManager에 Content를 추가하여 UI를 표시합니다.

        // 툴 윈도우가 로드된 후 초기 환영 메시지를 비동기적으로 표시합니다.
        ApplicationManager.getApplication().invokeLater {
            chatService.sendMessage("안녕하세요! 소진공 AI 챗봇입니다. 무엇을 도와드릴까요?", isUser = false) // 챗봇의 초기 메시지를 전송합니다.
        }
    }
}