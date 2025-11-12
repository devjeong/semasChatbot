package org.dev.semaschatbot.ui

import org.dev.semaschatbot.task.Task
import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.border.CompoundBorder
import javax.swing.border.LineBorder

/**
 * 프롬프트 승인 UI 컴포넌트
 * 
 * 생성된 프롬프트를 사용자에게 제시하고, '진행' 또는 '취소' 버튼을 제공합니다.
 * 
 * @param task 현재 작업
 * @param prompt 생성된 프롬프트
 * @param onApprove 진행 버튼 클릭 시 호출될 콜백
 * @param onCancel 취소 버튼 클릭 시 호출될 콜백
 */
class PromptApprovalPanel(
    private val task: Task,
    private val prompt: String,
    private val onApprove: () -> Unit,
    private val onCancel: () -> Unit
) : JPanel() {
    
    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = Color.WHITE
        border = CompoundBorder(
            LineBorder(Color(52, 152, 219), 2, true),
            EmptyBorder(15, 15, 15, 15)
        )
        
        // 최대 너비 설정
        maximumSize = Dimension(600, Int.MAX_VALUE)
        preferredSize = Dimension(600, 350)
        
        // 작업 정보
        val taskLabel = JLabel("<html><b style='font-size:14px;'>📝 작업: ${escapeHtml(task.title)}</b></html>")
        taskLabel.border = EmptyBorder(0, 0, 10, 0)
        add(taskLabel)
        
        // 작업 설명
        val descLabel = JLabel("<html><small style='color:#7f8c8d;'>${escapeHtml(task.description)}</small></html>")
        descLabel.border = EmptyBorder(0, 0, 10, 0)
        add(descLabel)
        
        // 프롬프트 내용 표시
        val promptLabel = JLabel("<html><b>생성된 프롬프트:</b></html>")
        promptLabel.border = EmptyBorder(5, 0, 5, 0)
        add(promptLabel)
        
        val promptArea = JTextArea(prompt)
        promptArea.isEditable = false
        promptArea.lineWrap = true
        promptArea.wrapStyleWord = true
        promptArea.font = Font("Monospaced", Font.PLAIN, 12)
        promptArea.background = Color(248, 249, 250)
        promptArea.foreground = Color(44, 62, 80)
        
        val scrollPane = JScrollPane(promptArea)
        scrollPane.border = CompoundBorder(
            LineBorder(Color(220, 221, 222), 1, true),
            EmptyBorder(5, 5, 5, 5)
        )
        scrollPane.preferredSize = Dimension(570, 200)
        scrollPane.maximumSize = Dimension(570, 200)
        scrollPane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        scrollPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        add(scrollPane)
        
        add(Box.createVerticalStrut(10))
        
        // 버튼 패널
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0))
        buttonPanel.background = Color.WHITE
        
        val approveButton = JButton("✅ 진행")
        approveButton.font = Font("SansSerif", Font.PLAIN, 12)
        approveButton.preferredSize = Dimension(100, 30)
        approveButton.addActionListener { onApprove() }
        
        val cancelButton = JButton("❌ 취소")
        cancelButton.font = Font("SansSerif", Font.PLAIN, 12)
        cancelButton.preferredSize = Dimension(100, 30)
        cancelButton.addActionListener { onCancel() }
        
        buttonPanel.add(approveButton)
        buttonPanel.add(cancelButton)
        add(buttonPanel)
    }
    
    /**
     * HTML 특수문자를 이스케이프합니다.
     * 
     * @param text 원본 텍스트
     * @return 이스케이프된 텍스트
     */
    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}

