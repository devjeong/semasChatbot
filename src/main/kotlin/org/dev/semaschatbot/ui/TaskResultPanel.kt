package org.dev.semaschatbot.ui

import org.dev.semaschatbot.task.Task
import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.border.CompoundBorder
import javax.swing.border.LineBorder

/**
 * 작업 결과 표시 UI 컴포넌트
 * 
 * 작업 실행 결과를 표시하고, '완료' 또는 '취소' 버튼을 제공합니다.
 * 
 * @param task 완료된 작업
 * @param result 작업 실행 결과
 * @param onComplete 완료 버튼 클릭 시 호출될 콜백
 * @param onCancel 취소 버튼 클릭 시 호출될 콜백
 */
class TaskResultPanel(
    private val task: Task,
    private val result: String,
    private val onComplete: () -> Unit,
    private val onCancel: () -> Unit
) : JPanel() {
    
    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = Color.WHITE
        border = CompoundBorder(
            LineBorder(Color(46, 204, 113), 2, true),
            EmptyBorder(15, 15, 15, 15)
        )
        
        // 최대 너비 설정
        maximumSize = Dimension(600, Int.MAX_VALUE)
        preferredSize = Dimension(600, 400)
        
        // 작업 정보
        val taskLabel = JLabel("<html><b style='font-size:14px;'>✅ 작업 완료: ${escapeHtml(task.title)}</b></html>")
        taskLabel.border = EmptyBorder(0, 0, 10, 0)
        add(taskLabel)
        
        // 결과 내용 표시
        val resultLabel = JLabel("<html><b>실행 결과:</b></html>")
        resultLabel.border = EmptyBorder(5, 0, 5, 0)
        add(resultLabel)
        
        val resultArea = JTextArea(result)
        resultArea.isEditable = false
        resultArea.lineWrap = true
        resultArea.wrapStyleWord = true
        resultArea.font = Font("Monospaced", Font.PLAIN, 12)
        resultArea.background = Color(248, 249, 250)
        resultArea.foreground = Color(44, 62, 80)
        
        val scrollPane = JScrollPane(resultArea)
        scrollPane.border = CompoundBorder(
            LineBorder(Color(220, 221, 222), 1, true),
            EmptyBorder(5, 5, 5, 5)
        )
        scrollPane.preferredSize = Dimension(570, 300)
        scrollPane.maximumSize = Dimension(570, 300)
        scrollPane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        scrollPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        add(scrollPane)
        
        add(Box.createVerticalStrut(10))
        
        // 버튼 패널
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0))
        buttonPanel.background = Color.WHITE
        
        val completeButton = JButton("✅ 완료")
        completeButton.font = Font("SansSerif", Font.PLAIN, 12)
        completeButton.preferredSize = Dimension(100, 30)
        completeButton.addActionListener { onComplete() }
        
        val cancelButton = JButton("❌ 취소")
        cancelButton.font = Font("SansSerif", Font.PLAIN, 12)
        cancelButton.preferredSize = Dimension(100, 30)
        cancelButton.addActionListener { onCancel() }
        
        buttonPanel.add(completeButton)
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

