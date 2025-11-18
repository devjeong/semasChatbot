package org.dev.semaschatbot.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import org.dev.semaschatbot.Logger
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.border.LineBorder

/**
 * 로그 조회 다이얼로그
 * 
 * 디버깅 로그를 조회하고 필터링할 수 있는 UI를 제공합니다.
 */
class LogViewerDialog : DialogWrapper(true) {
    
    private lateinit var logTextArea: JTextArea
    private lateinit var tagFilterCombo: JComboBox<String>
    private lateinit var levelFilterCombo: JComboBox<Logger.LogLevel>
    private lateinit var refreshButton: JButton
    private lateinit var clearButton: JButton
    
    init {
        title = "로그 조회"
        init()
    }
    
    override fun createCenterPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(900, 600)
        panel.border = EmptyBorder(10, 10, 10, 10)
        
        // 상단 필터 패널
        val filterPanel = createFilterPanel()
        panel.add(filterPanel, BorderLayout.NORTH)
        
        // 중앙 로그 표시 영역
        logTextArea = JTextArea()
        logTextArea.isEditable = false
        logTextArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        logTextArea.background = Color(30, 30, 30)
        logTextArea.foreground = Color(200, 200, 200)
        logTextArea.border = LineBorder(Color(60, 60, 60), 1)
        
        val scrollPane = JBScrollPane(logTextArea)
        scrollPane.border = EmptyBorder(5, 0, 0, 0)
        panel.add(scrollPane, BorderLayout.CENTER)
        
        // 하단 버튼 패널
        val buttonPanel = createButtonPanel()
        panel.add(buttonPanel, BorderLayout.SOUTH)
        
        // 초기 로그 로드
        refreshLogs()
        
        return panel
    }
    
    /**
     * 필터 패널 생성
     */
    private fun createFilterPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = EmptyBorder(0, 0, 10, 0)
        val gbc = GridBagConstraints()
        gbc.insets = java.awt.Insets(5, 5, 5, 5)
        gbc.anchor = GridBagConstraints.WEST
        
        // 태그 필터
        val tagLabel = JLabel("태그:")
        tagLabel.font = Font("SansSerif", Font.BOLD, 11)
        gbc.gridx = 0
        gbc.gridy = 0
        panel.add(tagLabel, gbc)
        
        val tags = mutableListOf("전체")
        tags.addAll(listOf("GeminiClient", "ChatService", "LmStudioClient", "UserService", "TaskListGenerator", "TaskPromptGenerator", "LmStudioStatsApiClient"))
        tagFilterCombo = JComboBox(tags.toTypedArray())
        tagFilterCombo.preferredSize = Dimension(150, 25)
        gbc.gridx = 1
        panel.add(tagFilterCombo, gbc)
        
        // 레벨 필터
        val levelLabel = JLabel("레벨:")
        levelLabel.font = Font("SansSerif", Font.BOLD, 11)
        gbc.gridx = 2
        panel.add(levelLabel, gbc)
        
        levelFilterCombo = JComboBox(Logger.LogLevel.values())
        levelFilterCombo.selectedItem = Logger.LogLevel.DEBUG
        levelFilterCombo.preferredSize = Dimension(100, 25)
        gbc.gridx = 3
        panel.add(levelFilterCombo, gbc)
        
        // 새로고침 버튼
        refreshButton = JButton("🔄 새로고침")
        refreshButton.font = Font("SansSerif", Font.PLAIN, 11)
        refreshButton.preferredSize = Dimension(100, 25)
        refreshButton.addActionListener { refreshLogs() }
        gbc.gridx = 4
        panel.add(refreshButton, gbc)
        
        // 필터 변경 시 자동 새로고침
        tagFilterCombo.addActionListener { refreshLogs() }
        levelFilterCombo.addActionListener { refreshLogs() }
        
        return panel
    }
    
    /**
     * 버튼 패널 생성
     */
    private fun createButtonPanel(): JPanel {
        val panel = JPanel()
        panel.border = EmptyBorder(10, 0, 0, 0)
        
        clearButton = JButton("🗑️ 로그 초기화")
        clearButton.font = Font("SansSerif", Font.PLAIN, 11)
        clearButton.preferredSize = Dimension(120, 30)
        clearButton.addActionListener {
            val result = JOptionPane.showConfirmDialog(
                panel,
                "모든 로그를 삭제하시겠습니까?",
                "로그 초기화 확인",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            )
            if (result == JOptionPane.YES_OPTION) {
                Logger.clear()
                refreshLogs()
            }
        }
        panel.add(clearButton)
        
        return panel
    }
    
    /**
     * 로그를 새로고침합니다.
     */
    private fun refreshLogs() {
        val selectedTag = tagFilterCombo.selectedItem as? String ?: "전체"
        val selectedLevel = levelFilterCombo.selectedItem as? Logger.LogLevel ?: Logger.LogLevel.DEBUG
        
        val filteredLogs = when {
            selectedTag == "전체" -> Logger.getLogsByLevel(selectedLevel)
            else -> Logger.getLogsByTag(selectedTag).filter { 
                val levelOrder = listOf(Logger.LogLevel.DEBUG, Logger.LogLevel.INFO, Logger.LogLevel.WARN, Logger.LogLevel.ERROR)
                levelOrder.indexOf(it.level) >= levelOrder.indexOf(selectedLevel)
            }
        }
        
        val logText = buildString {
            appendLine("=== 로그 조회 (총 ${Logger.getLogCount()}개 중 ${filteredLogs.size}개 표시) ===")
            appendLine()
            
            if (filteredLogs.isEmpty()) {
                appendLine("표시할 로그가 없습니다.")
            } else {
                filteredLogs.forEach { entry ->
                    // 레벨에 따른 색상 구분 (텍스트로 표현)
                    val levelPrefix = when (entry.level) {
                        Logger.LogLevel.DEBUG -> "[DEBUG]"
                        Logger.LogLevel.INFO -> "[INFO ]"
                        Logger.LogLevel.WARN -> "[WARN ]"
                        Logger.LogLevel.ERROR -> "[ERROR]"
                    }
                    appendLine("$levelPrefix [${entry.timestamp}] [${entry.tag}] ${entry.message}")
                }
            }
        }
        
        logTextArea.text = logText
        logTextArea.caretPosition = logTextArea.document.length // 스크롤을 맨 아래로
    }
}

