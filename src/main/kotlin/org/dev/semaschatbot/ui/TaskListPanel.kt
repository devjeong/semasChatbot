package org.dev.semaschatbot.ui

import org.dev.semaschatbot.task.Task
import org.dev.semaschatbot.task.TaskSession
import org.dev.semaschatbot.task.TaskStatus
import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.border.CompoundBorder
import javax.swing.border.LineBorder

/**
 * 작업목록을 채팅창에 표시하는 UI 컴포넌트
 * 
 * 생성된 작업목록을 요약 형태로 표시하고, 사용자가 진행 여부를 선택할 수 있도록 합니다.
 * 
 * @param session 작업 세션
 * @param savedFile 저장된 파일 경로 (표시용)
 * @param onApprove 진행하기 버튼 클릭 시 호출될 콜백
 * @param onCancel 취소 버튼 클릭 시 호출될 콜백
 */
class TaskListPanel(
    private val session: TaskSession,
    private val savedFile: java.io.File,
    private val onApprove: () -> Unit,
    private val onCancel: () -> Unit
) : JPanel() {
    
    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = Color.WHITE
        border = CompoundBorder(
            LineBorder(Color(189, 195, 199), 1, true),
            EmptyBorder(15, 15, 15, 15)
        )
        
        // 최대 너비 설정
        maximumSize = Dimension(600, Int.MAX_VALUE)
        preferredSize = Dimension(600, 300)
        
        // 헤더
        val headerLabel = JLabel("<html><b style='font-size:14px;'>📋 작업 목록이 생성되었습니다</b></html>")
        headerLabel.border = EmptyBorder(0, 0, 10, 0)
        add(headerLabel)
        
        // 요구사항 표시
        val requirementPanel = JPanel(BorderLayout())
        requirementPanel.background = Color.WHITE
        requirementPanel.border = EmptyBorder(5, 0, 10, 0)
        
        val requirementLabel = JLabel("<html><b>요구사항:</b> ${escapeHtml(session.requirement)}</html>")
        requirementLabel.font = Font("SansSerif", Font.PLAIN, 12)
        requirementPanel.add(requirementLabel, BorderLayout.WEST)
        add(requirementPanel)
        
        // 파일 저장 정보
        val fileInfoLabel = JLabel("<html><small style='color:gray;'>💾 저장 위치: ${savedFile.name}</small></html>")
        fileInfoLabel.border = EmptyBorder(0, 0, 10, 0)
        add(fileInfoLabel)
        
        // 작업 목록 표시 (스크롤 가능)
        val taskListPanel = JPanel()
        taskListPanel.layout = BoxLayout(taskListPanel, BoxLayout.Y_AXIS)
        taskListPanel.background = Color.WHITE
        
        session.tasks.forEachIndexed { index, task ->
            val taskItem = createTaskItem(index + 1, task)
            taskListPanel.add(taskItem)
            if (index < session.tasks.size - 1) {
                taskListPanel.add(Box.createVerticalStrut(8))
            }
        }
        
        val scrollPane = JScrollPane(taskListPanel)
        scrollPane.border = EmptyBorder(0, 0, 0, 0)
        scrollPane.preferredSize = Dimension(570, Math.min(200, session.tasks.size * 60))
        scrollPane.maximumSize = Dimension(570, 200)
        scrollPane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        scrollPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        scrollPane.background = Color.WHITE
        add(scrollPane)
        
        add(Box.createVerticalStrut(10))
        
        // 진행 여부 버튼
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0))
        buttonPanel.background = Color.WHITE
        
        val approveButton = JButton("✅ 진행하기")
        approveButton.font = Font("SansSerif", Font.PLAIN, 12)
        approveButton.preferredSize = Dimension(100, 30)
        approveButton.addActionListener { onApprove() }
        
        val cancelButton = JButton("❌ 취소")
        cancelButton.font = Font("SansSerif", Font.PLAIN, 12)
        cancelButton.preferredSize = Dimension(80, 30)
        cancelButton.addActionListener { onCancel() }
        
        buttonPanel.add(approveButton)
        buttonPanel.add(cancelButton)
        add(buttonPanel)
    }
    
    /**
     * 개별 작업 항목을 생성합니다.
     * 
     * @param number 작업 번호
     * @param task 작업 객체
     * @return 작업 항목 패널
     */
    private fun createTaskItem(number: Int, task: Task): JPanel {
        val panel = JPanel(BorderLayout())
        panel.background = Color(248, 249, 250)
        panel.border = CompoundBorder(
            LineBorder(Color(220, 221, 222), 1, true),
            EmptyBorder(8, 12, 8, 12)
        )
        
        val textPanel = JPanel()
        textPanel.layout = BoxLayout(textPanel, BoxLayout.Y_AXIS)
        textPanel.background = Color(248, 249, 250)
        
        // 작업 제목
        val titleLabel = JLabel("<html><b>$number. ${escapeHtml(task.title)}</b></html>")
        titleLabel.font = Font("SansSerif", Font.BOLD, 12)
        titleLabel.foreground = Color(44, 62, 80)
        titleLabel.border = EmptyBorder(0, 0, 4, 0)
        textPanel.add(titleLabel)
        
        // 작업 설명 (100자 제한)
        val description = if (task.description.length > 100) {
            task.description.take(100) + "..."
        } else {
            task.description
        }
        val descLabel = JLabel("<html><small style='color:#7f8c8d;'>${escapeHtml(description)}</small></html>")
        descLabel.font = Font("SansSerif", Font.PLAIN, 11)
        textPanel.add(descLabel)
        
        panel.add(textPanel, BorderLayout.CENTER)
        
        return panel
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

