package org.dev.semaschatbot.ui

import org.dev.semaschatbot.task.Task
import org.dev.semaschatbot.task.TaskStatus
import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.border.CompoundBorder
import javax.swing.border.LineBorder

/**
 * 작업별 상태를 표시하는 UI 컴포넌트
 * 
 * 작업의 현재 상태(진행중/완료/취소)를 시각적으로 표시합니다.
 * 
 * @param task 작업 객체
 * @param onCancel 개별 작업 취소 버튼 클릭 시 호출될 콜백 (null이면 취소 버튼 미표시)
 */
class TaskStatusPanel(
    private val task: Task,
    private val onCancel: ((String) -> Unit)? = null
) : JPanel() {
    
    init {
        layout = BorderLayout()
        background = Color.WHITE
        border = CompoundBorder(
            LineBorder(getStatusColor(task.status), 2, true),
            EmptyBorder(10, 12, 10, 12)
        )
        
        // 왼쪽: 작업 정보
        val infoPanel = JPanel()
        infoPanel.layout = BoxLayout(infoPanel, BoxLayout.Y_AXIS)
        infoPanel.background = Color.WHITE
        
        val titleLabel = JLabel("<html><b>${escapeHtml(task.title)}</b></html>")
        titleLabel.font = Font("SansSerif", Font.BOLD, 13)
        titleLabel.border = EmptyBorder(0, 0, 5, 0)
        infoPanel.add(titleLabel)
        
        val descLabel = JLabel("<html><small style='color:#7f8c8d;'>${escapeHtml(task.description)}</small></html>")
        descLabel.font = Font("SansSerif", Font.PLAIN, 11)
        infoPanel.add(descLabel)
        
        add(infoPanel, BorderLayout.CENTER)
        
        // 오른쪽: 상태 및 취소 버튼
        val rightPanel = JPanel()
        rightPanel.layout = BoxLayout(rightPanel, BoxLayout.Y_AXIS)
        rightPanel.background = Color.WHITE
        
        // 상태 표시
        val statusLabel = createStatusLabel(task.status)
        rightPanel.add(statusLabel)
        
        // 취소 버튼 (대기 중이거나 진행 중인 작업만)
        if (onCancel != null && (task.status == TaskStatus.PENDING || task.status == TaskStatus.IN_PROGRESS)) {
            rightPanel.add(Box.createVerticalStrut(5))
            val cancelButton = JButton("취소")
            cancelButton.font = Font("SansSerif", Font.PLAIN, 11)
            cancelButton.preferredSize = Dimension(60, 25)
            cancelButton.addActionListener { onCancel(task.id) }
            rightPanel.add(cancelButton)
        }
        
        add(rightPanel, BorderLayout.EAST)
    }
    
    /**
     * 상태에 따른 색상을 반환합니다.
     * 
     * @param status 작업 상태
     * @return 상태 색상
     */
    private fun getStatusColor(status: TaskStatus): Color {
        return when (status) {
            TaskStatus.PENDING -> Color(149, 165, 166)      // 회색
            TaskStatus.IN_PROGRESS -> Color(52, 152, 219)    // 파란색
            TaskStatus.COMPLETED -> Color(46, 204, 113)     // 초록색
            TaskStatus.CANCELLED -> Color(231, 76, 60)      // 빨간색
            TaskStatus.FAILED -> Color(230, 126, 34)       // 주황색
        }
    }
    
    /**
     * 상태 라벨을 생성합니다.
     * 
     * @param status 작업 상태
     * @return 상태 라벨
     */
    private fun createStatusLabel(status: TaskStatus): JLabel {
        val (icon, text, color) = when (status) {
            TaskStatus.PENDING -> Triple("⏳", "대기 중", Color(149, 165, 166))
            TaskStatus.IN_PROGRESS -> Triple("🔄", "진행 중", Color(52, 152, 219))
            TaskStatus.COMPLETED -> Triple("✅", "완료", Color(46, 204, 113))
            TaskStatus.CANCELLED -> Triple("❌", "취소됨", Color(231, 76, 60))
            TaskStatus.FAILED -> Triple("⚠️", "실패", Color(230, 126, 34))
        }
        
        val label = JLabel("<html><b style='color:${colorToHex(color)};'>$icon $text</b></html>")
        label.font = Font("SansSerif", Font.BOLD, 11)
        return label
    }
    
    /**
     * Color를 HEX 문자열로 변환합니다.
     * 
     * @param color 색상
     * @return HEX 문자열 (#RRGGBB)
     */
    private fun colorToHex(color: Color): String {
        return String.format("#%02x%02x%02x", color.red, color.green, color.blue)
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

