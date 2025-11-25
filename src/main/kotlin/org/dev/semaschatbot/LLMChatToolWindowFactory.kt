package org.dev.semaschatbot

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.net.URI
import java.nio.file.Files
import javax.swing.*
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder
import javax.swing.border.LineBorder
import java.awt.Graphics2D
import javax.swing.JComboBox
import javax.swing.JProgressBar

/**
 * LLMChatToolWindowFactory는 IntelliJ IDEA의 툴 윈도우를 생성하고 관리하는 팩토리 클래스입니다.
 * ToolWindowFactory 인터페이스를 구현하여 챗봇 툴 윈도우의 UI를 구성하고 초기화합니다.
 */
class LLMChatToolWindowFactory : ToolWindowFactory {
    
    // 헤더 패널의 상태 레이블을 저장하여 로그인 후 업데이트 가능하도록 함
    private var statusLabel: JLabel? = null

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
        panel.background = Color(245, 245, 245) // 패널의 배경색을 연한 회색으로 설정합니다.
        panel.preferredSize = Dimension(500, 700)  // 툴 윈도우 기본 크기 설정
        panel.minimumSize = Dimension(400, 500)    // 최소 크기 설정

        // 메신저 스타일의 채팅 패널을 생성합니다.
        val chatPanel = JPanel()
        chatPanel.layout = BoxLayout(chatPanel, BoxLayout.Y_AXIS)
        chatPanel.background = Color.WHITE
        chatPanel.border = EmptyBorder(10, 12, 10, 12)
        
        val scrollPane = JBScrollPane(chatPanel)
        scrollPane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        scrollPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        scrollPane.background = Color.WHITE
        scrollPane.border = LineBorder(Color(220, 220, 220), 1)
        
        // 스크롤 패널 크기 설정 개선
        scrollPane.preferredSize = Dimension(400, 500)  // 기본 크기 설정
        scrollPane.minimumSize = Dimension(300, 200)    // 최소 크기 설정
        
        // 스크롤 속도 개선
        scrollPane.verticalScrollBar.unitIncrement = 16
        scrollPane.verticalScrollBar.blockIncrement = 64
        
        panel.add(scrollPane, BorderLayout.CENTER)

        // 사용자 입력을 위한 패널과 컴포넌트들을 생성합니다.
        val inputPanel = JPanel(BorderLayout()) // 입력 필드와 버튼을 포함할 패널입니다.
        inputPanel.background = Color(245, 245, 245)
        inputPanel.border = EmptyBorder(5, 10, 10, 10)
        
        val loadingLabel = JLabel("⏳ 로딩 중...") // 로딩 인디케이터 레이블 생성
        loadingLabel.isVisible = false // 초기에는 보이지 않도록 설정
        loadingLabel.foreground = Color(52, 152, 219)
        loadingLabel.font = Font("SansSerif", Font.PLAIN, 12)
        inputPanel.add(loadingLabel, BorderLayout.WEST) // 입력 패널의 왼쪽에 로딩 인디케이터 추가
        
        val progressBar = JProgressBar()
        progressBar.isIndeterminate = true
        progressBar.isVisible = false
        inputPanel.add(progressBar, BorderLayout.NORTH)

        val inputField = JBTextArea() // 사용자 메시지를 입력할 텍스트 필드입니다.
        inputField.rows = 4  // 입력 필드 높이 증가
        inputField.lineWrap = true
        inputField.wrapStyleWord = true
        inputField.background = Color.WHITE
        inputField.foreground = Color.BLACK
        inputField.font = Font("SansSerif", Font.PLAIN, 14)
        
        val inputScrollPane = JBScrollPane(inputField)
        inputScrollPane.border = CompoundBorder(
            LineBorder(Color(200, 200, 200), 1, true),
            EmptyBorder(10, 15, 10, 15)  // 입력 필드 패딩 증가
        )
        inputScrollPane.preferredSize = Dimension(350, 120)  // 입력 필드 크기 설정
        inputScrollPane.minimumSize = Dimension(200, 80)     // 최소 크기 설정
        // 모던한 스타일의 버튼들을 생성합니다.
        val sendButton = createStyledButton("📤 전송", Color(52, 152, 219), Color.WHITE)
        val resetButton = createStyledButton("🔄 초기화", Color(231, 76, 60), Color.WHITE)
        val promptButton = createStyledButton("⚙️ 프롬프트", Color(155, 89, 182), Color.WHITE)
        val urlButton = createStyledButton("🌐 URL", Color(241, 196, 15), Color.WHITE)
        val analyzeFileButton = createStyledButton("📄 전체 분석", Color(46, 204, 113), Color.WHITE)
        val logButton = createStyledButton("📋 로그", Color(142, 68, 173), Color.WHITE)
        val mcpButton = createStyledButton("🔌 MCP 관리", Color(52, 152, 219), Color.WHITE)
        val taskButton = createStyledButton("📋 작업관리", Color(46, 204, 113), Color.WHITE)
        
        // 작업관리 버튼은 항상 활성화
        taskButton.isEnabled = true
        taskButton.toolTipText = "작업 목록을 조회합니다."

        // 커스텀 헤더 패널 생성
        val headerPanel = createHeaderPanel(chatService)
        
        val topPanel = JPanel(BorderLayout())
        topPanel.background = Color(245, 245, 245)
        topPanel.border = EmptyBorder(5, 10, 5, 10)
        topPanel.add(headerPanel, BorderLayout.NORTH)
        
        val buttonContainerPanel = JPanel(BorderLayout())
        buttonContainerPanel.background = Color(245, 245, 245)
        
        val leftButtonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0))
        leftButtonPanel.background = Color(245, 245, 245)
        leftButtonPanel.add(promptButton)
        leftButtonPanel.add(urlButton)
        /*leftButtonPanel.add(analyzeFileButton)*/
        buttonContainerPanel.add(leftButtonPanel, BorderLayout.WEST)
        
        val rightButtonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0))
        rightButtonPanel.background = Color(245, 245, 245)
        rightButtonPanel.add(taskButton)
        rightButtonPanel.add(mcpButton)
        rightButtonPanel.add(logButton)
        buttonContainerPanel.add(rightButtonPanel, BorderLayout.EAST)
        
        topPanel.add(buttonContainerPanel, BorderLayout.CENTER)

        inputPanel.add(inputScrollPane, BorderLayout.CENTER) // 입력 패널의 중앙에 입력 필드를 추가합니다.
        
        // 버튼들을 입력창 아래쪽에 배치하는 패널
        val bottomButtonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 5))
        bottomButtonPanel.background = Color(245, 245, 245)
        // 모델 선택 콤보박스 (하단 입력란 아래, 초기화 버튼 옆)
        val modelLabel = JLabel("모델:")
        modelLabel.font = Font("SansSerif", Font.BOLD, 11)
        modelLabel.foreground = Color(80, 80, 80)
        // Gemini 모델과 로컬 모델을 함께 표시
        val initialModels = mutableListOf<String>()
        initialModels.add("default-model") // 기본 로컬 모델
        initialModels.add("💎 gemini-2.5-flash") // Gemini 모델
        val modelCombo = createStyledComboBox(initialModels.toTypedArray())
        modelCombo.toolTipText = "모델 선택 (Gemini 또는 LM Studio)"
        bottomButtonPanel.add(modelLabel)
        bottomButtonPanel.add(modelCombo)
        bottomButtonPanel.add(resetButton) // 초기화 버튼을 먼저 추가
        bottomButtonPanel.add(sendButton) // 전송 버튼을 나중에 추가 (오른쪽에 위치)
        inputPanel.add(bottomButtonPanel, BorderLayout.SOUTH) // 입력 패널의 아래쪽에 버튼 패널을 추가합니다.

        val fileInfoLabel = JLabel("") // 파일 정보를 표시할 레이블
        fileInfoLabel.border = EmptyBorder(5, 15, 5, 15) // 여백 추가
        fileInfoLabel.isVisible = false // 초기에는 숨김
        fileInfoLabel.foreground = Color(100, 100, 100)
        fileInfoLabel.font = Font("SansSerif", Font.ITALIC, 12)
        fileInfoLabel.background = Color(248, 248, 248)
        fileInfoLabel.isOpaque = true

        val southPanel = JPanel(BorderLayout())
        southPanel.background = Color(245, 245, 245)
        southPanel.add(fileInfoLabel, BorderLayout.NORTH) // 파일 정보 레이블을 입력 패널 위에 추가
        southPanel.add(inputPanel, BorderLayout.CENTER)

        panel.add(topPanel, BorderLayout.NORTH)
        panel.add(southPanel, BorderLayout.SOUTH) // 메인 패널의 하단에 입력 패널을 추가합니다.

        // ChatService에 새로운 메신저 스타일 컴포넌트들을 설정
        chatService.chatPanel = chatPanel
        chatService.scrollPane = scrollPane
        chatService.loadingIndicator = loadingLabel
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

        // 'URL' 버튼 클릭 시 동작을 정의합니다.
        urlButton.addActionListener {
            val currentServerUrl = chatService.getServerBaseUrl()
            val currentLmStudioUrl = chatService.getLmStudioUrl()

            // URL 입력을 위한 JTextField 생성
            val urlField = JTextField(50) // 50자 크기의 JTextField
            urlField.text = currentServerUrl
            urlField.font = Font("Monospaced", Font.PLAIN, 12)

            // 설명 레이블 생성
            val descriptionLabel = JLabel("서버 기본 URL을 설정하세요:")
            descriptionLabel.font = Font("SansSerif", Font.PLAIN, 12)

            // 안내 레이블 생성
            val infoLabel = JLabel("<html>이 URL은 LM Studio, Gemini API 프록시, 그리고 인증 API의 기본 주소로 사용됩니다.<br>" +
                    "• LM Studio: {서버URL}:7777/v1<br>" +
                    "• Gemini API: {서버URL}:5000/api/gemini<br>" +
                    "• 인증 API: {서버URL}:5000/api/auth</html>")
            infoLabel.font = Font("SansSerif", Font.PLAIN, 11)
            infoLabel.foreground = Color.GRAY

            // 예시 레이블 생성
            val exampleLabel = JLabel("예시: http://192.168.18.53 (포트는 자동으로 추가됩니다)")
            exampleLabel.font = Font("SansSerif", Font.ITALIC, 11)
            exampleLabel.foreground = Color.GRAY

            // 현재 설정 표시 레이블
            val currentGeminiUrl = chatService.getServerBaseUrl() + ":5000/api/gemini"
            val currentAuthUrl = chatService.getServerBaseUrl() + ":5000/api/auth"
            val currentLabel = JLabel("<html>현재 LM Studio URL: $currentLmStudioUrl<br>" +
                    "현재 Gemini API URL: $currentGeminiUrl<br>" +
                    "현재 인증 API URL: $currentAuthUrl</html>")
            currentLabel.font = Font("SansSerif", Font.PLAIN, 10)
            currentLabel.foreground = Color.DARK_GRAY

            // 패널 구성
            val urlPanel = JPanel()
            urlPanel.layout = BoxLayout(urlPanel, BoxLayout.Y_AXIS)
            urlPanel.add(descriptionLabel)
            urlPanel.add(Box.createVerticalStrut(5))
            urlPanel.add(urlField)
            urlPanel.add(Box.createVerticalStrut(5))
            urlPanel.add(infoLabel)
            urlPanel.add(Box.createVerticalStrut(5))
            urlPanel.add(exampleLabel)
            urlPanel.add(Box.createVerticalStrut(5))
            urlPanel.add(currentLabel)

            // JOptionPane을 사용하여 다이얼로그 표시
            val result = JOptionPane.showConfirmDialog(
                panel,
                urlPanel,
                "서버 URL 설정",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
            )

            // 사용자가 OK를 눌렀을 경우
            if (result == JOptionPane.OK_OPTION) {
                val newUrl = urlField.text.trim()
                if (newUrl.isNotBlank()) {
                    // URL 유효성 검증
                    try {
                        val url = URI(newUrl).toURL()
                        chatService.setServerBaseUrl(newUrl)
                        val updatedLmStudioUrl = chatService.getLmStudioUrl()
                        val updatedGeminiUrl = chatService.getServerBaseUrl() + ":5000/api/gemini"
                        val updatedAuthUrl = chatService.getServerBaseUrl() + ":5000/api/auth"
                        chatService.sendMessage("서버 URL이 변경되었습니다: $newUrl\n" +
                                "LM Studio URL: $updatedLmStudioUrl\n" +
                                "Gemini API URL: $updatedGeminiUrl\n" +
                                "인증 API URL: $updatedAuthUrl", isUser = false)
                    } catch (e: Exception) {
                        chatService.sendMessage("유효하지 않은 URL입니다: ${e.message}", isUser = false)
                        JOptionPane.showMessageDialog(
                            panel,
                            "유효하지 않은 URL입니다. 다시 확인해주세요.",
                            "오류",
                            JOptionPane.ERROR_MESSAGE
                        )
                    }
                }
            }
        }

        // '전체 파일 분석' 버튼 클릭 시 동작을 정의합니다.
        analyzeFileButton.addActionListener {
            chatService.setFullFileContext()
        }

        // '작업관리' 버튼 클릭 시 동작을 정의합니다.
        // MCP 관리 상태와 상관없이 항상 접근 가능
        taskButton.addActionListener {
            try {
                val taskDialog = org.dev.semaschatbot.ui.TaskManagementDialog()
                taskDialog.show()
            } catch (e: Exception) {
                chatService.sendMessage("작업 관리 다이얼로그 열기 중 오류가 발생했습니다: ${e.message}", isUser = false)
                e.printStackTrace()
            }
        }

        // 'MCP 관리' 버튼 클릭 시 동작을 정의합니다.
        mcpButton.addActionListener {
            try {
                val mcpDialog = org.dev.semaschatbot.ui.MCPManagementDialog()
                mcpDialog.show()
            } catch (e: Exception) {
                chatService.sendMessage("MCP 관리 다이얼로그 열기 중 오류가 발생했습니다: ${e.message}", isUser = false)
                e.printStackTrace()
            }
        }

        // '로그' 버튼 클릭 시 동작을 정의합니다.
        logButton.addActionListener {
            try {
                val logDialog = org.dev.semaschatbot.ui.LogViewerDialog()
                logDialog.show()
            } catch (e: Exception) {
                chatService.sendMessage("로그 조회 중 오류가 발생했습니다: ${e.message}", isUser = false)
                e.printStackTrace()
            }
        }


        // 'Reset' 버튼 클릭 시 동작을 정의합니다.
        resetButton.addActionListener {
            chatPanel.removeAll() // 모든 메시지 패널을 제거합니다.
            chatPanel.revalidate()
            chatPanel.repaint()
            
            // 스크롤을 최상단으로 이동
            scrollPane?.let { scroll ->
                scroll.verticalScrollBar.value = 0
            }
            
            // 인증 상태 및 선택 컨텍스트도 초기화
            chatService.resetAuthentication()
            chatService.resetSelectionContext()
            
            ApplicationManager.getApplication().invokeLater {
                chatService.sendMessage("대화가 초기화되었습니다.", isUser = false) // 챗봇에 초기화 메시지를 표시합니다.
                // 다시 인증 요구
                if (chatService.requiresAuthentication()) {
                    showAuthenticationDialog(chatService, panel)
                }
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

        // 툴 윈도우가 로드된 후 초기 인증 및 환영 메시지를 비동기적으로 표시합니다.
        ApplicationManager.getApplication().invokeLater {
            // 초기 인증 체크
            if (chatService.requiresAuthentication()) {
                showAuthenticationDialog(chatService, panel)
            } else {
                chatService.sendMessage("안녕하세요! Protein 26 입니다. 무엇을 도와드릴까요?", isUser = false)
            }
            // LM Studio 모델 목록 로드 (백그라운드) - Gemini 모델은 유지
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val lmModels = chatService.listLmStudioModels()
                    if (lmModels.isNotEmpty()) {
                        javax.swing.SwingUtilities.invokeLater {
                            // 기존 Gemini 모델 목록 유지
                            val geminiModels = listOf(
                                "💎 gemini-2.5-flash"
                            )
                            // Gemini 모델과 LM Studio 모델을 합침
                            val allModels = mutableListOf<String>()
                            allModels.add("default-model")
                            allModels.addAll(geminiModels)
                            allModels.addAll(lmModels)
                            modelCombo.model = DefaultComboBoxModel(allModels.toTypedArray())
                            // 기본 모델 선택
                            chatService.setSelectedModel("default-model")
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // 콤보박스 선택 변경 시 ChatService에 반영 (API Key는 중앙서버에서 관리)
        modelCombo.addActionListener {
            val selectedModel = modelCombo.selectedItem as? String ?: return@addActionListener
            
            // Gemini 모델인지 확인 (💎 이모지로 시작하는 모델)
            if (selectedModel.startsWith("💎")) {
                val geminiModelId = selectedModel.removePrefix("💎 ").trim()
                chatService.setSelectedModel(selectedModel)
                chatService.sendMessage("Gemini 모델 '$geminiModelId'이 선택되었습니다.", isUser = false)
            } else {
                // 로컬 모델 선택 시
                chatService.setSelectedModel(selectedModel)
            }
        }
    }
    

    /**
     * 모던한 스타일의 버튼을 생성하는 함수입니다.
     * @param text 버튼에 표시될 텍스트
     * @param bgColor 버튼의 배경색
     * @param fgColor 버튼의 글자색
     * @return 스타일이 적용된 JButton
     */
    private fun createStyledButton(text: String, bgColor: Color, fgColor: Color): JButton {
        val button = object : JButton(text) {
            override fun paintComponent(g: Graphics) {
                val g2d = g as Graphics2D
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                
                if (model.isPressed) {
                    g2d.color = bgColor.darker()
                } else if (model.isRollover) {
                    g2d.color = bgColor.brighter()
                } else {
                    g2d.color = bgColor
                }
                
                g2d.fillRoundRect(0, 0, width, height, 8, 8)
                
                // 텍스트 그리기
                g2d.color = fgColor
                val fm = g2d.fontMetrics
                val textWidth = fm.stringWidth(text)
                val textHeight = fm.ascent
                val x = (width - textWidth) / 2
                val y = (height + textHeight) / 2 - 2
                g2d.drawString(text, x, y)
            }
        }
        
        button.foreground = fgColor
        button.background = bgColor
        button.font = Font("SansSerif", Font.BOLD, 11)
        button.preferredSize = Dimension(80, 30)
        button.isFocusPainted = false
        button.isBorderPainted = false
        button.isContentAreaFilled = false
        button.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        
        return button
    }

    /**
     * 버튼들과 톤앤매너를 맞춘 콤보박스를 생성합니다.
     */
    private fun createStyledComboBox(items: Array<String>): JComboBox<String> {
        val combo = object : JComboBox<String>(items) {
            override fun updateUI() {
                super.updateUI()
                // 드롭다운 아이템 렌더러 스타일 조정
                renderer = object : DefaultListCellRenderer() {
                    override fun getListCellRendererComponent(
                        list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
                    ): Component {
                        val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                        c.border = EmptyBorder(4, 10, 4, 10)
                        c.font = Font("SansSerif", Font.PLAIN, 12)
                        return c
                    }
                }
            }
        }

        combo.font = Font("SansSerif", Font.PLAIN, 12)
        combo.background = Color(255, 255, 255)
        combo.foreground = Color(33, 37, 41)
        combo.isOpaque = true
        combo.border = CompoundBorder(
            LineBorder(Color(200, 200, 200), 1, true),
            EmptyBorder(2, 8, 2, 8)
        )
        combo.preferredSize = Dimension(220, 30)
        combo.maximumSize = Dimension(Short.MAX_VALUE.toInt(), 30)
        combo.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        combo.isFocusable = false
        return combo
    }

    /**
     * 모던한 스타일의 헤더 패널을 생성하는 함수입니다.
     * @return 스타일이 적용된 헤더 JPanel
     */
    private fun createHeaderPanel(chatService: ChatService): JPanel {
        val headerPanel = JPanel(BorderLayout())
        headerPanel.background = Color(173, 216, 230)
        headerPanel.border = EmptyBorder(12, 15, 12, 15)
        
        // 아이콘과 제목을 포함하는 왼쪽 패널
        val titlePanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        titlePanel.background = Color(173, 216, 230)
        
        // 아이콘 레이블 - IntelliJ IconLoader 사용
        val iconLabel = try {
            // 먼저 protein_Logo_resize.svg를 시도
            val icon = IconLoader.findIcon("/META-INF/protein_Logo_resize.svg", javaClass)
            if (icon != null) {
                JLabel(icon)
            } else {
                // 대체 아이콘으로 pluginIcon.svg 시도
                val fallbackIcon = IconLoader.findIcon("/META-INF/pluginIcon.svg", javaClass)
                if (fallbackIcon != null) {
                    JLabel(fallbackIcon)
                } else {
                    // 모든 아이콘 로드 실패 시 텍스트 사용
                    val textLabel = JLabel("🤖")
                    textLabel.font = Font("SansSerif", Font.PLAIN, 20)
                    textLabel
                }
            }
        } catch (e: Exception) {
            println("아이콘 로드 실패: ${e.message}")
            val textLabel = JLabel("🤖")
            textLabel.font = Font("SansSerif", Font.PLAIN, 20)
            textLabel
        }
        titlePanel.add(iconLabel)
        
        // 제목 레이블
        val titleLabel = JLabel("Protein 26")
        titleLabel.foreground = Color.WHITE
        titleLabel.font = Font("SansSerif", Font.BOLD, 16)
        titlePanel.add(titleLabel)
        
        // 베타 배지
        val betaBadge = JLabel("Beta")
        betaBadge.foreground = Color(52, 152, 219)
        betaBadge.background = Color.WHITE
        betaBadge.font = Font("SansSerif", Font.BOLD, 10)
        betaBadge.border = CompoundBorder(
            LineBorder(Color.WHITE, 1, true),
            EmptyBorder(2, 6, 2, 6)
        )
        betaBadge.isOpaque = true
        titlePanel.add(betaBadge)
        
        headerPanel.add(titlePanel, BorderLayout.WEST)
        
        // 상태 표시 (우측) - 로그인한 사용자 정보 표시
        val statusPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
        statusPanel.background = Color(173, 216, 230)
        
        // 로그인한 사용자 정보 가져오기 (SessionManager 사용)
        val currentUser = try {
            SessionManager.getInstance().getCurrentUser()
        } catch (e: Exception) {
            null
        }
        
        val statusText = if (currentUser != null) {
            "${currentUser.name}(${currentUser.username})"
        } else {
            "● 오프라인"
        }
        
        val statusLabel = JLabel(statusText)
        statusLabel.foreground = if (currentUser != null) Color(46, 204, 113) else Color(149, 165, 166)
        statusLabel.font = Font("SansSerif", Font.PLAIN, 12)
        statusPanel.add(statusLabel)
        
        // 상태 레이블을 저장하여 나중에 업데이트 가능하도록 함
        this.statusLabel = statusLabel
        
        headerPanel.add(statusPanel, BorderLayout.EAST)
        
        return headerPanel
    }
    
    /**
     * 상태 레이블을 업데이트합니다.
     * 로그인/로그아웃 시 호출되어 헤더의 사용자 정보를 갱신합니다.
     */
    private fun updateStatusLabel(user: User?) {
        statusLabel?.let { label ->
            val statusText = if (user != null) {
                "${user.name}(${user.username})"
            } else {
                "● 오프라인"
            }
            label.text = statusText
            label.foreground = if (user != null) Color(46, 204, 113) else Color(149, 165, 166)
        }
    }
    
    /**
     * 작업관리 버튼의 활성화 상태를 업데이트합니다.
     * 세션 기반으로 MCP 기능 활성화 및 작업 관리 MCP 연결 상태를 확인합니다.
     */
    private fun updateTaskButtonState(taskButton: JButton, project: Project) {
        try {
            val sessionManager = SessionManager.getInstance()
            val isAvailable = sessionManager.isTaskManagementAvailable(project)
            
            taskButton.isEnabled = isAvailable
            
            if (!isAvailable) {
                val mcpSettings = MCPSettings(project)
                if (!mcpSettings.isMCPEnabled()) {
                    taskButton.toolTipText = "MCP 기능이 비활성화되어 있습니다. MCP 관리에서 활성화해주세요."
                } else {
                    taskButton.toolTipText = "작업 관리 MCP 서버가 연결되어 있지 않습니다. MCP 관리에서 연결해주세요."
                }
            } else {
                taskButton.toolTipText = "작업 목록을 조회합니다."
            }
        } catch (e: Exception) {
            Logger.error("LLMChatToolWindowFactory", "작업관리 버튼 상태 업데이트 오류: ${e.message}")
            taskButton.isEnabled = false
            taskButton.toolTipText = "작업관리 버튼 상태 확인 중 오류가 발생했습니다."
        }
    }

    /**
     * 웹 라이브러리 파일을 읽어서 문자열로 반환합니다.
     * @param resourcePath 리소스 경로
     * @return 파일 내용 문자열
     */
    private fun readWebLibResource(resourcePath: String): String {
        return try {
            val classLoader = this::class.java.classLoader
            val resourceStream = classLoader.getResourceAsStream(resourcePath)
            resourceStream?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Markdown 콘텐츠를 클라이언트 사이드에서 렌더링하는 HTML 페이지를 생성합니다.
     * 로컬 웹 라이브러리를 인라인으로 포함하여 폐쇄망에서도 동작합니다.
     * @param markdownContent 원본 Markdown 텍스트
     * @return Markdown 뷰어 HTML 문서
     */
    private fun createMarkdownViewerHtml(markdownContent: String): String {
        // Markdown 콘텐츠에서 특수 문자 이스케이프 (JavaScript 문자열 안전성을 위해)
        val escapedMarkdown = markdownContent
            .replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("$", "\\$")
        
        // 웹 라이브러리들을 로컬에서 읽어옵니다
        val markedJs = readWebLibResource("web-libs/js/marked.min.js")
        val githubMarkdownCss = readWebLibResource("web-libs/css/github-markdown.min.css")
        val prismCss = readWebLibResource("web-libs/css/prism.min.css")
        val prismJs = readWebLibResource("web-libs/js/prism.min.js")
        val prismJavaJs = readWebLibResource("web-libs/js/prism-java.min.js")
        val prismKotlinJs = readWebLibResource("web-libs/js/prism-kotlin.min.js")
        val prismJavaScriptJs = readWebLibResource("web-libs/js/prism-javascript.min.js")
        val prismBashJs = readWebLibResource("web-libs/js/prism-bash.min.js")
        
        return """
        <!DOCTYPE html>
        <html lang="ko">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>소진공 AI 챗봇 사용자 가이드</title>
            
            <!-- GitHub Markdown CSS (인라인) -->
            <style>
                $githubMarkdownCss
            </style>
            
            <!-- Prism.js CSS (인라인) -->
            <style>
                $prismCss
            </style>
            
            <!-- marked.js (인라인) -->
            <script>
                $markedJs
            </script>
            
            <!-- Prism.js (인라인) -->
            <script>
                $prismJs
            </script>
            
            <!-- Prism.js 언어 컴포넌트들 (인라인) -->
            <script>
                $prismJavaJs
            </script>
            <script>
                $prismKotlinJs
            </script>
            <script>
                $prismJavaScriptJs
            </script>
            <script>
                $prismBashJs
            </script>
            
            <style>
                body {
                    box-sizing: border-box;
                    min-width: 200px;
                    max-width: 1200px;
                    margin: 0 auto;
                    padding: 45px;
                    background-color: #ffffff;
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Noto Sans KR', Helvetica, Arial, sans-serif;
                }
                
                .markdown-body {
                    box-sizing: border-box;
                    min-width: 200px;
                    max-width: 980px;
                    margin: 0 auto;
                }
                
                /* 한국어 폰트 최적화 */
                .markdown-body h1, .markdown-body h2, .markdown-body h3, 
                .markdown-body h4, .markdown-body h5, .markdown-body h6 {
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Noto Sans KR', Helvetica, Arial, sans-serif;
                }
                
                /* 코드 블록 스타일 개선 */
                .markdown-body pre {
                    background-color: #f6f8fa;
                    border-radius: 6px;
                    overflow: auto;
                    padding: 16px;
                    line-height: 1.45;
                    white-space: pre;
                    word-wrap: normal;
                }
                
                .markdown-body code {
                    background-color: rgba(175, 184, 193, 0.2);
                    padding: 0.2em 0.4em;
                    border-radius: 3px;
                    font-size: 85%;
                    font-family: 'SFMono-Regular', 'Consolas', 'Liberation Mono', 'Menlo', 'Courier', monospace;
                }
                
                .markdown-body pre code {
                    background-color: transparent;
                    padding: 0;
                    white-space: pre;
                    word-break: normal;
                    word-wrap: normal;
                    line-height: inherit;
                    font-size: inherit;
                }
                
                /* 체크박스 스타일 */
                .markdown-body input[type="checkbox"] {
                    margin-right: 0.5em;
                }
                
                /* 테이블 스타일 개선 */
                .markdown-body table {
                    display: block;
                    width: max-content;
                    max-width: 100%;
                    overflow: auto;
                }
                
                /* 로딩 스타일 */
                .loading {
                    text-align: center;
                    padding: 50px;
                    color: #666;
                    font-size: 18px;
                }
                
                /* 이모지 크기 조정 */
                .markdown-body .emoji {
                    font-size: 1.2em;
                }
                
                /* 인용구 스타일 */
                .markdown-body blockquote {
                    border-left: 4px solid #dfe2e5;
                    padding-left: 16px;
                    color: #6a737d;
                }
                
                /* 링크 스타일 */
                .markdown-body a {
                    color: #0366d6;
                    text-decoration: none;
                }
                
                .markdown-body a:hover {
                    text-decoration: underline;
                }
                
                @media (max-width: 767px) {
                    body {
                        padding: 15px;
                    }
                }
            </style>
        </head>
        <body>
            <div class="loading" id="loading">
                📖 사용자 가이드를 불러오는 중...
            </div>
            
            <div class="markdown-body" id="content" style="display: none;">
                <!-- Markdown 콘텐츠가 여기에 렌더링됩니다 -->
            </div>
            
            <script>
                // Markdown 콘텐츠
                const markdownContent = `$escapedMarkdown`;
                
                // marked.js 설정
                marked.setOptions({
                    breaks: true,
                    gfm: true,
                    headerIds: true,
                    mangle: false,
                    pedantic: false,
                    sanitize: false
                });
                
                // 사용자 정의 렌더러
                const renderer = new marked.Renderer();
                
                // 체크박스 지원
                renderer.listitem = function(text) {
                    if (/^\s*\[[x ]\]\s*/.test(text)) {
                        text = text
                            .replace(/^\s*\[ \]\s*/, '<input type="checkbox" disabled> ')
                            .replace(/^\s*\[x\]\s*/, '<input type="checkbox" checked disabled> ');
                        return '<li style="list-style: none;">' + text + '</li>';
                    } else {
                        return '<li>' + text + '</li>';
                    }
                };
                
                // 코드 블록에 Prism.js 클래스 추가
                renderer.code = function(code, language) {
                    const validLang = language && Prism.languages[language] ? language : 'text';
                    // HTML 특수 문자 이스케이프 및 줄바꿈 보존
                    const escapedCode = code
                        .replace(/&/g, '&amp;')
                        .replace(/</g, '&lt;')
                        .replace(/>/g, '&gt;')
                        .replace(/"/g, '&quot;')
                        .replace(/'/g, '&#x27;');
                    return `<pre><code class="language-${'$'}{validLang}">${'$'}{escapedCode}</code></pre>`;
                };
                
                // 렌더링 실행
                function renderMarkdown() {
                    try {
                        const html = marked.parse(markdownContent, { renderer: renderer });
                        document.getElementById('content').innerHTML = html;
                        document.getElementById('loading').style.display = 'none';
                        document.getElementById('content').style.display = 'block';
                        
                        // 코드 블록의 줄바꿈 처리 개선
                        const codeBlocks = document.querySelectorAll('pre code');
                        codeBlocks.forEach(block => {
                            // 줄바꿈이 제대로 표시되도록 CSS 속성 명시적 설정
                            block.style.whiteSpace = 'pre';
                            block.style.wordWrap = 'normal';
                            block.style.overflow = 'auto';
                        });
                        
                        // Prism.js로 코드 하이라이팅 적용
                        if (typeof Prism !== 'undefined') {
                            Prism.highlightAll();
                        }
                        
                        console.log('✅ Markdown 렌더링 완료');
                    } catch (error) {
                        console.error('❌ Markdown 렌더링 오류:', error);
                        document.getElementById('loading').innerHTML = '⚠️ 가이드를 불러오는 중 오류가 발생했습니다.';
                    }
                }
                
                // 페이지 로드 후 렌더링
                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', renderMarkdown);
                } else {
                    renderMarkdown();
                }
            </script>
        </body>
        </html>
        """.trimIndent()
    }

    /**
     * 회원가입/로그인 다이얼로그를 표시하고 사용자 인증을 처리합니다.
     * @param chatService 챗 서비스 인스턴스
     * @param parentComponent 부모 컴포넌트 (다이얼로그의 위치 기준)
     */
    private fun showAuthenticationDialog(chatService: ChatService, parentComponent: JPanel) {
        val userService = chatService.getUserService()
        var authAttempts = 0
        val maxAttempts = 3

        fun showLoginOrRegisterDialog() {
            // 탭 패널 생성 (로그인/회원가입)
            val tabbedPane = JTabbedPane()
            
            // === 로그인 탭 ===
            val loginPanel = JPanel()
            loginPanel.layout = BoxLayout(loginPanel, BoxLayout.Y_AXIS)
            
            // 입력 필드 크기 최적화: 컬럼 수를 20에서 12로 축소하여 더 컴팩트한 UI 제공
            val loginUsernameField = JTextField(12)
            val loginPasswordField = JPasswordField(12)
            
            // 입력 필드 최대 크기 제한으로 레이아웃 일관성 유지
            loginUsernameField.maximumSize = Dimension(200, 30)
            loginPasswordField.maximumSize = Dimension(200, 30)
            
            loginPanel.add(Box.createVerticalStrut(10))
            loginPanel.add(JLabel("아이디:"))
            loginPanel.add(loginUsernameField)
            loginPanel.add(Box.createVerticalStrut(10))
            loginPanel.add(JLabel("비밀번호:"))
            loginPanel.add(loginPasswordField)
            loginPanel.add(Box.createVerticalStrut(10))
            
            tabbedPane.addTab("로그인", loginPanel)
            
            // === 회원가입 탭 ===
            val registerPanel = JPanel()
            registerPanel.layout = BoxLayout(registerPanel, BoxLayout.Y_AXIS)
            
            // 회원가입 입력 필드도 로그인 탭과 동일한 크기로 일관성 유지
            val registerNameField = JTextField(12)
            val registerUsernameField = JTextField(12)
            val registerPasswordField = JPasswordField(12)
            val registerPasswordConfirmField = JPasswordField(12)
            val roleComboBox = JComboBox<UserRole>(UserRole.values())
            
            // 모든 입력 필드에 최대 크기 제한 적용
            registerNameField.maximumSize = Dimension(200, 30)
            registerUsernameField.maximumSize = Dimension(200, 30)
            registerPasswordField.maximumSize = Dimension(200, 30)
            registerPasswordConfirmField.maximumSize = Dimension(200, 30)
            roleComboBox.maximumSize = Dimension(200, 30)
            
            registerPanel.add(Box.createVerticalStrut(10))
            registerPanel.add(JLabel("이름:"))
            registerPanel.add(registerNameField)
            registerPanel.add(Box.createVerticalStrut(10))
            registerPanel.add(JLabel("아이디 (최소 3자):"))
            registerPanel.add(registerUsernameField)
            registerPanel.add(Box.createVerticalStrut(10))
            registerPanel.add(JLabel("비밀번호 (최소 4자):"))
            registerPanel.add(registerPasswordField)
            registerPanel.add(Box.createVerticalStrut(10))
            registerPanel.add(JLabel("비밀번호 확인:"))
            registerPanel.add(registerPasswordConfirmField)
            registerPanel.add(Box.createVerticalStrut(10))
            registerPanel.add(JLabel("권한:"))
            registerPanel.add(roleComboBox)
            registerPanel.add(Box.createVerticalStrut(10))
            
            tabbedPane.addTab("회원가입", registerPanel)
            
            // 다이얼로그 표시
            val result = JOptionPane.showConfirmDialog(
                parentComponent,
                tabbedPane,
                "SEMAS 챗봇 - 로그인/회원가입",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
            )
            
            if (result == JOptionPane.OK_OPTION) {
                val selectedTab = tabbedPane.selectedIndex
                
                if (selectedTab == 0) {
                    // 로그인 탭
                    val username = loginUsernameField.text.trim()
                    val password = String(loginPasswordField.password)
                    
                    if (username.isBlank() || password.isBlank()) {
                        JOptionPane.showMessageDialog(
                            parentComponent,
                            "아이디와 비밀번호를 모두 입력해주세요.",
                            "입력 오류",
                            JOptionPane.WARNING_MESSAGE
                        )
                        showLoginOrRegisterDialog()
                        return
                    }
                    
                    // 로딩 다이얼로그 표시 (비모달로 설정하여 UI 스레드 블로킹 방지)
                    // JPanel의 최상위 Window를 찾아서 JDialog의 부모로 사용
                    val parentWindow = javax.swing.SwingUtilities.getWindowAncestor(parentComponent)
                    // JDialog 생성자는 Frame, Dialog, 또는 Window + ModalityType을 요구
                    // parentWindow를 Frame 또는 Dialog로 캐스팅 시도
                    val loadingDialog = when {
                        parentWindow is Frame -> JDialog(parentWindow, "로그인 중...", false)
                        parentWindow is Dialog -> JDialog(parentWindow, "로그인 중...", false)
                        parentWindow is Window -> JDialog(parentWindow, "로그인 중...", Dialog.ModalityType.MODELESS)
                        else -> JDialog().apply { title = "로그인 중..." }
                    }
                    if (loadingDialog.title.isBlank()) {
                        loadingDialog.title = "로그인 중..."
                    }
                    loadingDialog.setSize(250, 120)
                    loadingDialog.setLocationRelativeTo(parentComponent)
                    val loadingPanel = JPanel(BorderLayout())
                    loadingPanel.border = EmptyBorder(20, 20, 20, 20)
                    loadingPanel.add(JLabel("로그인 처리 중입니다. 잠시만 기다려주세요...", SwingConstants.CENTER), BorderLayout.CENTER)
                    loadingDialog.add(loadingPanel)
                    loadingDialog.isVisible = true
                    
                    // 백그라운드 스레드에서 데이터베이스 작업 실행 (UI 프리즈 방지)
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val (success, message) = userService.login(username, password)
                            
                            // UI 업데이트는 UI 스레드에서 실행
                            ApplicationManager.getApplication().invokeLater {
                                loadingDialog.dispose()
                                
                                if (success) {
                                    val user = SessionManager.getInstance().getCurrentUser()
                                    chatService.sendMessage("✅ $message", isUser = false)
                                    chatService.sendMessage("안녕하세요! 소진공 AI 챗봇입니다. 무엇을 도와드릴까요?", isUser = false)
                                    
                                    // 헤더의 상태 레이블 업데이트
                                    updateStatusLabel(user)
                                    
                                    // 로그인 성공 시 자동 인덱싱 시작
                                    chatService.startAutoIndexing()
                                } else {
                                    authAttempts++
                                    chatService.sendMessage("❌ $message", isUser = false)
                                    
                                    if (authAttempts >= maxAttempts) {
                                        JOptionPane.showMessageDialog(
                                            parentComponent,
                                            "로그인에 실패했습니다.\n최대 시도 횟수($maxAttempts)를 초과했습니다.",
                                            "로그인 실패",
                                            JOptionPane.ERROR_MESSAGE
                                        )
                                    } else {
                                        JOptionPane.showMessageDialog(
                                            parentComponent,
                                            "$message\n다시 시도해주세요. (${maxAttempts - authAttempts}회 남음)",
                                            "로그인 실패",
                                            JOptionPane.WARNING_MESSAGE
                                        )
                                        showLoginOrRegisterDialog()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // 예외 발생 시 UI 스레드에서 처리
                            ApplicationManager.getApplication().invokeLater {
                                loadingDialog.dispose()
                                JOptionPane.showMessageDialog(
                                    parentComponent,
                                    "로그인 중 오류가 발생했습니다: ${e.message}",
                                    "오류",
                                    JOptionPane.ERROR_MESSAGE
                                )
                                showLoginOrRegisterDialog()
                            }
                        }
                    }
                } else {
                    // 회원가입 탭
                    val name = registerNameField.text.trim()
                    val username = registerUsernameField.text.trim()
                    val password = String(registerPasswordField.password)
                    val passwordConfirm = String(registerPasswordConfirmField.password)
                    val role = roleComboBox.selectedItem as UserRole
                    
                    // 유효성 검사
                    if (name.isBlank() || username.isBlank() || password.isBlank() || passwordConfirm.isBlank()) {
                        JOptionPane.showMessageDialog(
                            parentComponent,
                            "모든 필드를 입력해주세요.",
                            "입력 오류",
                            JOptionPane.WARNING_MESSAGE
                        )
                        showLoginOrRegisterDialog()
                        return
                    }
                    
                    if (password != passwordConfirm) {
                        JOptionPane.showMessageDialog(
                            parentComponent,
                            "비밀번호가 일치하지 않습니다.",
                            "입력 오류",
                            JOptionPane.WARNING_MESSAGE
                        )
                        showLoginOrRegisterDialog()
                        return
                    }
                    
                    // 로딩 다이얼로그 표시 (비모달로 설정하여 UI 스레드 블로킹 방지)
                    // JPanel의 최상위 Window를 찾아서 JDialog의 부모로 사용
                    val parentWindow = javax.swing.SwingUtilities.getWindowAncestor(parentComponent)
                    // JDialog 생성자는 Frame, Dialog, 또는 Window + ModalityType을 요구
                    // parentWindow를 Frame 또는 Dialog로 캐스팅 시도
                    val loadingDialog = when {
                        parentWindow is Frame -> JDialog(parentWindow, "회원가입 중...", false)
                        parentWindow is Dialog -> JDialog(parentWindow, "회원가입 중...", false)
                        parentWindow is Window -> JDialog(parentWindow, "회원가입 중...", Dialog.ModalityType.MODELESS)
                        else -> JDialog().apply { title = "회원가입 중..." }
                    }
                    if (loadingDialog.title.isBlank()) {
                        loadingDialog.title = "회원가입 중..."
                    }
                    loadingDialog.setSize(250, 120)
                    loadingDialog.setLocationRelativeTo(parentComponent)
                    val loadingPanel = JPanel(BorderLayout())
                    loadingPanel.border = EmptyBorder(20, 20, 20, 20)
                    loadingPanel.add(JLabel("회원가입 처리 중입니다. 잠시만 기다려주세요...", SwingConstants.CENTER), BorderLayout.CENTER)
                    loadingDialog.add(loadingPanel)
                    loadingDialog.isVisible = true
                    
                    // 백그라운드 스레드에서 데이터베이스 작업 실행 (UI 프리즈 방지)
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val (success, message) = userService.registerUser(username, password, name, role)
                            
                            // UI 업데이트는 UI 스레드에서 실행
                            ApplicationManager.getApplication().invokeLater {
                                loadingDialog.dispose()
                                
                                if (success) {
                                    JOptionPane.showMessageDialog(
                                        parentComponent,
                                        message,
                                        "회원가입 성공",
                                        JOptionPane.INFORMATION_MESSAGE
                                    )
                                    
                                    // 회원가입 성공 시 자동으로 로그인 (백그라운드에서 실행)
                                    CoroutineScope(Dispatchers.IO).launch {
                                        try {
                                            val (loginSuccess, loginMessage) = userService.login(username, password)
                                            
                                            ApplicationManager.getApplication().invokeLater {
                                                if (loginSuccess) {
                                                    chatService.sendMessage("✅ $loginMessage", isUser = false)
                                                    chatService.sendMessage("안녕하세요! 소진공 AI 챗봇입니다. 무엇을 도와드릴까요?", isUser = false)
                                                    chatService.startAutoIndexing()
                                                } else {
                                                    chatService.sendMessage("❌ $loginMessage", isUser = false)
                                                }
                                            }
                                        } catch (e: Exception) {
                                            ApplicationManager.getApplication().invokeLater {
                                                chatService.sendMessage("❌ 자동 로그인 중 오류가 발생했습니다: ${e.message}", isUser = false)
                                            }
                                        }
                                    }
                                } else {
                                    JOptionPane.showMessageDialog(
                                        parentComponent,
                                        message,
                                        "회원가입 실패",
                                        JOptionPane.ERROR_MESSAGE
                                    )
                                    showLoginOrRegisterDialog()
                                }
                            }
                        } catch (e: Exception) {
                            // 예외 발생 시 UI 스레드에서 처리
                            ApplicationManager.getApplication().invokeLater {
                                loadingDialog.dispose()
                                JOptionPane.showMessageDialog(
                                    parentComponent,
                                    "회원가입 중 오류가 발생했습니다: ${e.message}",
                                    "오류",
                                    JOptionPane.ERROR_MESSAGE
                                )
                                showLoginOrRegisterDialog()
                            }
                        }
                    }
                }
            } else {
                // 취소
                chatService.sendMessage("❌ 로그인이 취소되었습니다. 챗봇을 사용하려면 로그인이 필요합니다.", isUser = false)
            }
        }
        
        // 다이얼로그 표시 시작
        showLoginOrRegisterDialog()
    }
}