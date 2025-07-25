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
import javax.swing.JPasswordField
import javax.swing.border.EmptyBorder
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import com.intellij.ide.BrowserUtil
import java.awt.Font
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagLayout
import java.awt.GridBagConstraints
import java.awt.Insets
import javax.swing.BoxLayout
import javax.swing.Box
import javax.swing.border.LineBorder
import javax.swing.border.CompoundBorder
import java.awt.RenderingHints
import java.awt.Graphics
import java.awt.Graphics2D

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
        panel.background = Color(245, 245, 245) // 패널의 배경색을 연한 회색으로 설정합니다.

        // 메신저 스타일의 채팅 패널을 생성합니다.
        val chatPanel = JPanel()
        chatPanel.layout = BoxLayout(chatPanel, BoxLayout.Y_AXIS)
        chatPanel.background = Color.WHITE
        chatPanel.border = EmptyBorder(5, 8, 5, 8)
        
        val scrollPane = JBScrollPane(chatPanel)
        scrollPane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        scrollPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        scrollPane.background = Color.WHITE
        scrollPane.border = LineBorder(Color(220, 220, 220), 1)
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
        
        val inputField = JBTextArea() // 사용자 메시지를 입력할 텍스트 필드입니다.
        inputField.rows = 3
        inputField.lineWrap = true
        inputField.wrapStyleWord = true
        inputField.background = Color.WHITE
        inputField.foreground = Color.BLACK
        inputField.font = Font("SansSerif", Font.PLAIN, 14)
        
        val inputScrollPane = JBScrollPane(inputField)
        inputScrollPane.border = CompoundBorder(
            LineBorder(Color(200, 200, 200), 1, true),
            EmptyBorder(8, 12, 8, 12)
        )
        // 모던한 스타일의 버튼들을 생성합니다.
        val sendButton = createStyledButton("📤 전송", Color(52, 152, 219), Color.WHITE)
        val resetButton = createStyledButton("🔄 초기화", Color(231, 76, 60), Color.WHITE)
        val promptButton = createStyledButton("⚙️ 프롬프트", Color(155, 89, 182), Color.WHITE)
        val urlButton = createStyledButton("🌐 URL", Color(241, 196, 15), Color.WHITE)
        val authButton = createStyledButton("🔐 인증", Color(52, 73, 94), Color.WHITE)
        val analyzeFileButton = createStyledButton("📄 전체 분석", Color(46, 204, 113), Color.WHITE)
        val guideButton = createStyledButton("📖 가이드", Color(230, 126, 34), Color.WHITE)
        
        // 커스텀 헤더 패널 생성
        val headerPanel = createHeaderPanel()
        
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
        leftButtonPanel.add(authButton)
        leftButtonPanel.add(analyzeFileButton)
        buttonContainerPanel.add(leftButtonPanel, BorderLayout.WEST)
        
        val rightButtonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0))
        rightButtonPanel.background = Color(245, 245, 245)
        rightButtonPanel.add(guideButton)
        buttonContainerPanel.add(rightButtonPanel, BorderLayout.EAST)
        
        topPanel.add(buttonContainerPanel, BorderLayout.CENTER)

        inputPanel.add(inputScrollPane, BorderLayout.CENTER) // 입력 패널의 중앙에 입력 필드를 추가합니다.
        
        // 버튼들을 입력창 아래쪽에 배치하는 패널
        val bottomButtonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 5))
        bottomButtonPanel.background = Color(245, 245, 245)
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
            val currentUrl = chatService.getLmStudioUrl()

            // URL 입력을 위한 JTextField 생성
            val urlField = JTextField(50) // 50자 크기의 JTextField
            urlField.text = currentUrl
            urlField.font = Font("Monospaced", Font.PLAIN, 12)

            // 설명 레이블 생성
            val descriptionLabel = JLabel("LmStudio 서버 URL을 설정하세요:")
            descriptionLabel.font = Font("SansSerif", Font.PLAIN, 12)

            // 예시 레이블 생성
            val exampleLabel = JLabel("예시: http://192.168.18.52:1234/v1")
            exampleLabel.font = Font("SansSerif", Font.ITALIC, 11)
            exampleLabel.foreground = Color.GRAY

            // 패널 구성
            val urlPanel = JPanel()
            urlPanel.layout = BoxLayout(urlPanel, BoxLayout.Y_AXIS)
            urlPanel.add(descriptionLabel)
            urlPanel.add(Box.createVerticalStrut(5))
            urlPanel.add(urlField)
            urlPanel.add(Box.createVerticalStrut(5))
            urlPanel.add(exampleLabel)

            // JOptionPane을 사용하여 다이얼로그 표시
            val result = JOptionPane.showConfirmDialog(
                panel,
                urlPanel,
                "LmStudio URL 설정",
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
                        chatService.setLmStudioUrl(newUrl)
                        chatService.sendMessage("LmStudio URL이 변경되었습니다: $newUrl", isUser = false)
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

        // 'Auth' 버튼 클릭 시 동작을 정의합니다.
        authButton.addActionListener {
            if (chatService.isUserAuthenticated()) {
                // 이미 인증된 경우, 재인증 여부 확인
                val result = JOptionPane.showConfirmDialog(
                    panel,
                    "이미 인증되어 있습니다.\n다시 인증하시겠습니까?",
                    "인증 상태",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE
                )
                
                if (result == JOptionPane.YES_OPTION) {
                    chatService.resetAuthentication()
                    showAuthenticationDialog(chatService, panel)
                }
            } else {
                // 인증되지 않은 경우, 인증 다이얼로그 표시
                showAuthenticationDialog(chatService, panel)
            }
        }

        // '전체 파일 분석' 버튼 클릭 시 동작을 정의합니다.
        analyzeFileButton.addActionListener {
            chatService.setFullFileContext()
        }

        // '가이드' 버튼 클릭 시 동작을 정의합니다.
        guideButton.addActionListener {
            try {
                // 플러그인 리소스에서 USER_GUIDE.md 파일을 읽기
                val classLoader = this::class.java.classLoader
                val resourceStream = classLoader.getResourceAsStream("USER_GUIDE.md")
                
                if (resourceStream != null) {
                    // 리소스에서 문자열로 읽기
                    val markdownContent = resourceStream.bufferedReader().use { it.readText() }
                    val htmlContent = createMarkdownViewerHtml(markdownContent)
                    
                    // 임시 HTML 파일 생성
                    val tempDir = Files.createTempDirectory("semas-guide")
                    val tempHtmlFile = tempDir.resolve("user_guide.html")
                    Files.write(tempHtmlFile, htmlContent.toByteArray())
                    
                    // 웹 브라우저에서 열기
                    BrowserUtil.browse(tempHtmlFile.toUri())
                    
                    chatService.sendMessage("사용자 가이드를 웹 브라우저에서 열었습니다.", isUser = false)
                } else {
                    chatService.sendMessage("가이드 파일(USER_GUIDE.md)을 플러그인 리소스에서 찾을 수 없습니다.", isUser = false)
                }
            } catch (e: Exception) {
                chatService.sendMessage("가이드 파일을 여는 중 오류가 발생했습니다: ${e.message}", isUser = false)
            }
        }

        // 'Reset' 버튼 클릭 시 동작을 정의합니다.
        resetButton.addActionListener {
            chatPanel.removeAll() // 모든 메시지 패널을 제거합니다.
            chatPanel.revalidate()
            chatPanel.repaint()
            
            // 인증 상태도 초기화
            chatService.resetAuthentication()
            
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
                chatService.sendMessage("안녕하세요! 소진공 AI 챗봇입니다. 무엇을 도와드릴까요?", isUser = false)
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
     * 모던한 스타일의 헤더 패널을 생성하는 함수입니다.
     * @return 스타일이 적용된 헤더 JPanel
     */
    private fun createHeaderPanel(): JPanel {
        val headerPanel = JPanel(BorderLayout())
        headerPanel.background = Color(173, 216, 230)
        headerPanel.border = EmptyBorder(12, 15, 12, 15)
        
        // 아이콘과 제목을 포함하는 왼쪽 패널
        val titlePanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        titlePanel.background = Color(173, 216, 230)
        
        // 아이콘 레이블
        val iconLabel = JLabel("🤖")
        iconLabel.font = Font("SansSerif", Font.PLAIN, 20)
        titlePanel.add(iconLabel)
        
        // 제목 레이블
        val titleLabel = JLabel("소진공 AI 챗봇")
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
        
        // 상태 표시 (우측)
        val statusPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
        statusPanel.background = Color(173, 216, 230)
        
        val statusLabel = JLabel("● 온라인")
        statusLabel.foreground = Color(46, 204, 113)
        statusLabel.font = Font("SansSerif", Font.PLAIN, 12)
        statusPanel.add(statusLabel)
        
        headerPanel.add(statusPanel, BorderLayout.EAST)
        
        return headerPanel
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
     * 인증 다이얼로그를 표시하고 사용자 인증을 처리합니다.
     * @param chatService 챗 서비스 인스턴스
     * @param parentComponent 부모 컴포넌트 (다이얼로그의 위치 기준)
     */
    private fun showAuthenticationDialog(chatService: ChatService, parentComponent: JPanel) {
        var authAttempts = 0
        val maxAttempts = 3

        fun attemptAuthentication() {
            authAttempts++
            
            // 인증키 입력을 위한 JPasswordField 생성
            val passwordField = JPasswordField(20)
            passwordField.font = Font("Monospaced", Font.PLAIN, 12)

            // 설명 레이블 생성
            val descriptionLabel = JLabel("소진공 AI 챗봇을 사용하려면 인증키를 입력하세요:")
            descriptionLabel.font = Font("SansSerif", Font.PLAIN, 12)

            // 시도 횟수 표시 레이블
            val attemptsLabel = JLabel("시도 횟수: $authAttempts / $maxAttempts")
            attemptsLabel.font = Font("SansSerif", Font.ITALIC, 11)
            attemptsLabel.foreground = if (authAttempts >= 2) Color.RED else Color.GRAY

            // 보안 아이콘 레이블
            val securityLabel = JLabel("🔐")
            securityLabel.font = Font("SansSerif", Font.PLAIN, 20)

            // 패널 구성
            val authPanel = JPanel()
            authPanel.layout = BoxLayout(authPanel, BoxLayout.Y_AXIS)
            authPanel.add(Box.createVerticalStrut(5))
            
            val iconPanel = JPanel(FlowLayout(FlowLayout.CENTER))
            iconPanel.add(securityLabel)
            authPanel.add(iconPanel)
            
            authPanel.add(Box.createVerticalStrut(10))
            authPanel.add(descriptionLabel)
            authPanel.add(Box.createVerticalStrut(10))
            authPanel.add(passwordField)
            authPanel.add(Box.createVerticalStrut(5))
            authPanel.add(attemptsLabel)

            // 포커스를 패스워드 필드로 설정
            passwordField.requestFocusInWindow()

            // JOptionPane을 사용하여 다이얼로그 표시
            val result = JOptionPane.showConfirmDialog(
                parentComponent,
                authPanel,
                "SEMAS 챗봇 인증",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
            )

            // 사용자가 OK를 눌렀을 경우
            if (result == JOptionPane.OK_OPTION) {
                val inputKey = String(passwordField.password)
                
                if (chatService.authenticateUser(inputKey)) {
                    // 인증 성공
                    chatService.sendMessage("✅ 인증이 완료되었습니다. 환영합니다!", isUser = false)
                    chatService.sendMessage("안녕하세요! 소진공 AI 챗봇입니다. 무엇을 도와드릴까요?", isUser = false)
                } else {
                    // 인증 실패
                    if (authAttempts >= maxAttempts) {
                        // 최대 시도 횟수 초과
                        chatService.sendMessage("❌ 인증에 실패했습니다. 최대 시도 횟수를 초과했습니다.", isUser = false)
                        chatService.sendMessage("관리자에게 문의하시거나 나중에 다시 시도해주세요.", isUser = false)
                        JOptionPane.showMessageDialog(
                            parentComponent,
                            "인증에 실패했습니다.\n최대 시도 횟수($maxAttempts)를 초과했습니다.\n챗봇을 초기화하거나 다시 시작해주세요.",
                            "인증 실패",
                            JOptionPane.ERROR_MESSAGE
                        )
                    } else {
                        // 재시도 가능
                        chatService.sendMessage("❌ 잘못된 인증키입니다. 다시 시도해주세요. (${maxAttempts - authAttempts}회 남음)", isUser = false)
                        JOptionPane.showMessageDialog(
                            parentComponent,
                            "잘못된 인증키입니다.\n다시 시도해주세요. (${maxAttempts - authAttempts}회 남음)",
                            "인증 실패",
                            JOptionPane.WARNING_MESSAGE
                        )
                        // 재귀적으로 다시 인증 다이얼로그 표시
                        ApplicationManager.getApplication().invokeLater {
                            attemptAuthentication()
                        }
                    }
                }
                
                // 입력된 패스워드 클리어 (보안)
                passwordField.text = ""
            } else {
                // 사용자가 취소를 누른 경우
                chatService.sendMessage("❌ 인증이 취소되었습니다. 챗봇을 사용하려면 인증이 필요합니다.", isUser = false)
            }
        }

        // 인증 시도 시작
        attemptAuthentication()
    }
}