package org.dev.semaschatbot.ui

import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import org.dev.semaschatbot.*
import java.awt.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.border.LineBorder
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellRenderer

/**
 * 작업 관리 다이얼로그
 * 
 * 로그인한 사용자에게 할당된 작업 목록을 게시판 형태로 표시합니다.
 * MCP를 통해 작업 목록을 조회하고 표시합니다.
 * 
 * 성능 최적화:
 * - 백그라운드 스레드에서 API 호출로 UI 블로킹 방지
 * - 테이블 가상화를 통한 대량 데이터 효율적 렌더링
 * - 연결 풀링을 통한 네트워크 최적화
 */
class TaskManagementDialog : DialogWrapper(true) {
    
    private lateinit var taskTable: JTable
    private lateinit var refreshButton: JButton
    private lateinit var statusLabel: JLabel
    private lateinit var statusFilterCombo: JComboBox<String>
    private lateinit var priorityFilterCombo: JComboBox<String>
    
    private val mcpApiClient = MCPApiClient()
    private val project = ProjectManager.getInstance().defaultProject
    private val sessionManager = SessionManager.getInstance()
    private val chatService: ChatService? = try {
        project.getService(ChatService::class.java)
    } catch (e: Exception) {
        Logger.debug("TaskManagementDialog", "ChatService 초기화 실패: ${e.message}")
        null
    }
    
    private var taskList: List<AssignedTask> = emptyList()
    private var filteredTaskList: List<AssignedTask> = emptyList()
    
    init {
        title = "작업 관리"
        init()
        
        // 서버 URL 동기화
        chatService?.let {
            val serverBaseUrl = it.getServerBaseUrl()
            mcpApiClient.setServerBaseUrl(serverBaseUrl)
        }
        
        // 초기 작업 목록 로드
        loadTaskList()
    }
    
    override fun createCenterPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(1000, 700)
        panel.border = EmptyBorder(10, 10, 10, 10)
        
        // 상단 필터 및 제어 패널
        val controlPanel = createControlPanel()
        panel.add(controlPanel, BorderLayout.NORTH)
        
        // 중앙 작업 목록 테이블
        val tablePanel = createTablePanel()
        panel.add(tablePanel, BorderLayout.CENTER)
        
        // 하단 상태 패널
        val statusPanel = createStatusPanel()
        panel.add(statusPanel, BorderLayout.SOUTH)
        
        return panel
    }
    
    /**
     * 제어 패널 생성 (필터 및 새로고침 버튼)
     */
    private fun createControlPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = EmptyBorder(0, 0, 10, 0)
        
        // 왼쪽: 필터
        val filterPanel = JPanel(FlowLayout(FlowLayout.LEFT, 10, 5))
        filterPanel.background = Color(245, 245, 245)
        
        val statusLabel = JLabel("상태:")
        statusLabel.font = Font("SansSerif", Font.BOLD, 12)
        filterPanel.add(statusLabel)
        
        statusFilterCombo = JComboBox(arrayOf("전체", "대기", "진행중", "검토", "완료", "차단"))
        statusFilterCombo.selectedIndex = 0
        statusFilterCombo.addActionListener { applyFilters() }
        filterPanel.add(statusFilterCombo)
        
        val priorityLabel = JLabel("우선순위:")
        priorityLabel.font = Font("SansSerif", Font.BOLD, 12)
        filterPanel.add(priorityLabel)
        
        priorityFilterCombo = JComboBox(arrayOf("전체", "낮음", "보통", "높음", "긴급"))
        priorityFilterCombo.selectedIndex = 0
        priorityFilterCombo.addActionListener { applyFilters() }
        filterPanel.add(priorityFilterCombo)
        
        panel.add(filterPanel, BorderLayout.WEST)
        
        // 오른쪽: 새로고침 버튼
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        buttonPanel.background = Color(245, 245, 245)
        
        refreshButton = createStyledButton("🔄 새로고침", Color(52, 152, 219), Color.WHITE)
        refreshButton.addActionListener { loadTaskList() }
        buttonPanel.add(refreshButton)
        
        panel.add(buttonPanel, BorderLayout.EAST)
        
        return panel
    }
    
    /**
     * 테이블 패널 생성
     */
    private fun createTablePanel(): JPanel {
        val panel = JPanel(BorderLayout())
        
        // 테이블 모델 생성
        val columnNames = arrayOf("ID", "제목", "상태", "우선순위", "담당자", "시작일", "마감일", "예상시간")
        val tableModel = object : DefaultTableModel(columnNames, 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }
        
        taskTable = JTable(tableModel)
        taskTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        taskTable.rowHeight = 30
        taskTable.font = Font("SansSerif", Font.PLAIN, 12)
        taskTable.setShowGrid(true)
        taskTable.gridColor = Color(220, 220, 220)
        taskTable.intercellSpacing = Dimension(5, 5)
        
        // 컬럼 너비 설정
        taskTable.columnModel.getColumn(0).preferredWidth = 50   // ID
        taskTable.columnModel.getColumn(1).preferredWidth = 300  // 제목
        taskTable.columnModel.getColumn(2).preferredWidth = 80   // 상태
        taskTable.columnModel.getColumn(3).preferredWidth = 80   // 우선순위
        taskTable.columnModel.getColumn(4).preferredWidth = 100  // 담당자
        taskTable.columnModel.getColumn(5).preferredWidth = 100  // 시작일
        taskTable.columnModel.getColumn(6).preferredWidth = 100  // 마감일
        taskTable.columnModel.getColumn(7).preferredWidth = 80   // 예상시간
        
        // 상태 및 우선순위 컬럼에 색상 적용을 위한 커스텀 렌더러
        taskTable.setDefaultRenderer(Any::class.java, object : TableCellRenderer {
            private val defaultRenderer = taskTable.getDefaultRenderer(Any::class.java)
        
            override fun getTableCellRendererComponent(
                table: JTable?,
                value: Any?,
                isSelected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ): Component {
                val component = defaultRenderer.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column
                ) as JLabel
                
                // 상태 컬럼 (인덱스 2)
                if (column == 2 && row < filteredTaskList.size) {
                    val task = filteredTaskList[row]
                    component.foreground = task.getStatusColor()
                    component.text = task.getStatusDisplayName()
                }
                // 우선순위 컬럼 (인덱스 3)
                else if (column == 3 && row < filteredTaskList.size) {
                    val task = filteredTaskList[row]
                    component.foreground = task.getPriorityColor()
                    component.text = task.getPriorityDisplayName()
                }
                
                return component
            }
        })
        
        // 더블 클릭 시 상세 정보 표시
        taskTable.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) {
                    val selectedRow = taskTable.selectedRow
                    if (selectedRow >= 0 && selectedRow < filteredTaskList.size) {
                        showTaskDetail(filteredTaskList[selectedRow])
                    }
                }
            }
        })
        
        val scrollPane = JBScrollPane(taskTable)
        scrollPane.border = LineBorder(Color(200, 200, 200), 1)
        panel.add(scrollPane, BorderLayout.CENTER)
        
        return panel
    }
    
    /**
     * 상태 패널 생성
     */
    private fun createStatusPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = EmptyBorder(10, 0, 0, 0)
        
        statusLabel = JLabel("작업 목록을 불러오는 중...")
        statusLabel.font = Font("SansSerif", Font.PLAIN, 11)
        statusLabel.foreground = Color(100, 100, 100)
        panel.add(statusLabel, BorderLayout.WEST)
        
        return panel
    }
    
    /**
     * 작업 목록 로드
     */
    private fun loadTaskList() {
        // SessionManager를 통해 현재 로그인한 사용자 정보 가져오기
        val username = sessionManager.getCurrentUsername()
        if (username == null || username.isBlank()) {
            updateStatusLabel("로그인이 필요합니다.", Color(231, 76, 60))
            JOptionPane.showMessageDialog(
                contentPanel,
                "작업 목록을 조회하려면 먼저 로그인해주세요.",
                "로그인 필요",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }
        
        Logger.info("TaskManagementDialog", "작업 목록 조회 시작: username=$username")
        
        updateStatusLabel("작업 목록을 불러오는 중...", Color(52, 152, 219))
        refreshButton.isEnabled = false
        
        // 백그라운드 스레드에서 API 호출
        Thread {
            try {
                val (success, tasks) = mcpApiClient.getAssignedTasks(username)
                
                SwingUtilities.invokeLater {
                    refreshButton.isEnabled = true
                    
                    if (success) {
                        taskList = tasks
                        applyFilters()
                        updateStatusLabel("작업 목록 조회 완료 (${tasks.size}개)", Color(46, 204, 113))
                    } else {
                        taskList = emptyList()
                        updateTaskTable()
                        updateStatusLabel("작업 목록 조회 실패", Color(231, 76, 60))
                        showErrorDialog("작업 목록을 불러오는데 실패했습니다.")
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    refreshButton.isEnabled = true
                    updateStatusLabel("오류 발생: ${e.message}", Color(231, 76, 60))
                    Logger.error("TaskManagementDialog", "작업 목록 로드 오류: ${e.message}")
                }
            }
        }.start()
    }
    
    /**
     * 필터 적용
     */
    private fun applyFilters() {
        val statusFilter = statusFilterCombo.selectedItem as? String ?: "전체"
        val priorityFilter = priorityFilterCombo.selectedItem as? String ?: "전체"
        
        filteredTaskList = taskList.filter { task ->
            val statusMatch = statusFilter == "전체" || task.getStatusDisplayName() == statusFilter
            val priorityMatch = priorityFilter == "전체" || task.getPriorityDisplayName() == priorityFilter
            statusMatch && priorityMatch
        }
        
        updateTaskTable()
        updateStatusLabel("표시: ${filteredTaskList.size}개 / 전체: ${taskList.size}개", Color(100, 100, 100))
    }
    
    /**
     * 테이블 업데이트
     */
    private fun updateTaskTable() {
        val tableModel = taskTable.model as DefaultTableModel
        tableModel.rowCount = 0
        
        filteredTaskList.forEach { task ->
            tableModel.addRow(arrayOf(
                task.id,
                task.title,
                task.status,  // 렌더러에서 한글 표시명으로 변환
                task.priority ?: "",  // 렌더러에서 한글 표시명으로 변환
                task.assigneeName ?: "-",
                task.startDate ?: "-",
                task.dueDate ?: "-",
                if (task.estimatedHours != null) "${task.estimatedHours}시간" else "-"
            ))
        }
        
        taskTable.repaint()
    }
    
    /**
     * 작업 상세 정보 표시
     */
    private fun showTaskDetail(task: AssignedTask) {
        val detailText = buildString {
            append("<html><body style='font-family: SansSerif; font-size: 12px; padding: 10px;'>")
            append("<h3 style='color: #3498db;'>${task.title}</h3>")
            append("<table style='width: 100%; border-collapse: collapse;'>")
            append("<tr><td style='font-weight: bold; padding: 5px;'>ID:</td><td style='padding: 5px;'>${task.id}</td></tr>")
            append("<tr><td style='font-weight: bold; padding: 5px;'>상태:</td><td style='padding: 5px; color: ${colorToHex(task.getStatusColor())};'>${task.getStatusDisplayName()}</td></tr>")
            if (task.priority != null) {
                append("<tr><td style='font-weight: bold; padding: 5px;'>우선순위:</td><td style='padding: 5px; color: ${colorToHex(task.getPriorityColor())};'>${task.getPriorityDisplayName()}</td></tr>")
            }
            if (task.assigneeName != null) {
                append("<tr><td style='font-weight: bold; padding: 5px;'>담당자:</td><td style='padding: 5px;'>${task.assigneeName}</td></tr>")
            }
            if (task.startDate != null) {
                append("<tr><td style='font-weight: bold; padding: 5px;'>시작일:</td><td style='padding: 5px;'>${task.startDate}</td></tr>")
            }
            if (task.dueDate != null) {
                append("<tr><td style='font-weight: bold; padding: 5px;'>마감일:</td><td style='padding: 5px;'>${task.dueDate}</td></tr>")
            }
            if (task.estimatedHours != null) {
                append("<tr><td style='font-weight: bold; padding: 5px;'>예상 시간:</td><td style='padding: 5px;'>${task.estimatedHours}시간</td></tr>")
            }
            if (task.actualHours != null) {
                append("<tr><td style='font-weight: bold; padding: 5px;'>실제 시간:</td><td style='padding: 5px;'>${task.actualHours}시간</td></tr>")
            }
            if (task.description != null && task.description.isNotBlank()) {
                append("<tr><td colspan='2' style='padding: 10px;'><strong>설명:</strong><br/>${task.description.replace("\n", "<br/>")}</td></tr>")
            }
            append("</table>")
            append("</body></html>")
        }
        
        JOptionPane.showMessageDialog(
            contentPanel,
            detailText,
            "작업 상세 정보",
            JOptionPane.INFORMATION_MESSAGE
        )
    }
    
    /**
     * 색상을 HEX 문자열로 변환
     */
    private fun colorToHex(color: Color): String {
        return String.format("#%02x%02x%02x", color.red, color.green, color.blue)
    }
    
    /**
     * 상태 레이블 업데이트
     */
    private fun updateStatusLabel(text: String, color: Color) {
        statusLabel.text = text
        statusLabel.foreground = color
    }
    
    /**
     * 에러 다이얼로그 표시
     */
    private fun showErrorDialog(message: String) {
        JOptionPane.showMessageDialog(
            contentPanel,
            message,
            "오류",
            JOptionPane.ERROR_MESSAGE
        )
    }
    
    /**
     * 스타일이 적용된 버튼 생성
     */
    private fun createStyledButton(text: String, bgColor: Color, fgColor: Color): JButton {
        val button = JButton(text)
        button.background = bgColor
        button.foreground = fgColor
        button.font = Font("SansSerif", Font.BOLD, 12)
        button.border = BorderFactory.createCompoundBorder(
            LineBorder(bgColor.darker(), 1),
            EmptyBorder(5, 15, 5, 15)
        )
        button.isOpaque = true
        button.isFocusPainted = false
        button.cursor = Cursor(Cursor.HAND_CURSOR)
        
        // 호버 효과
        button.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseEntered(e: java.awt.event.MouseEvent) {
                button.background = bgColor.brighter()
            }
            
            override fun mouseExited(e: java.awt.event.MouseEvent) {
                button.background = bgColor
            }
        })
        
        return button
    }
}

