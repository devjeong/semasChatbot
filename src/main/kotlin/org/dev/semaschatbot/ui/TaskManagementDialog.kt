package org.dev.semaschatbot.ui

import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import org.dev.semaschatbot.*
import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.border.LineBorder
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellRenderer

/**
 * 작업 관리 다이얼로그 - GitHub Copilot 스타일 게시판
 * 
 * 로그인한 사용자에게 할당된 작업 목록을 친근한 게시판 형태로 표시합니다.
 * MCP를 통해 작업 목록을 조회하고, 상세 화면에서 작업 정보를 수정할 수 있습니다.
 */
class TaskManagementDialog : DialogWrapper(true) {
    
    private lateinit var taskTable: JTable
    private lateinit var refreshButton: JButton
    private lateinit var statusLabel: JLabel
    private lateinit var statusFilterCombo: JComboBox<String>
    private lateinit var priorityFilterCombo: JComboBox<String>
    private lateinit var taskCountLabel: JLabel
    
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
    
    private var mcpStdioClient: MCPStdioClient? = null
    
    init {
        title = "📋 작업 게시판"
        init()
        
        chatService?.let {
            val serverBaseUrl = it.getServerBaseUrl()
            mcpApiClient.setServerBaseUrl(serverBaseUrl)
        }
        
        // MCP 클라이언트 초기화
        val scriptPath = "C:/dev/workspace/semasChatbotMng/mcp_servers/task_mcp_server.py"
        val envVars = mapOf(
            "DB_FILE" to "C:/dev/workspace/semasChatbotMng/auth.db",
            "MCP_LOG_FILE" to "C:/dev/workspace/semasChatbotMng/logs/task_mcp_server.log"
        )
        mcpStdioClient = MCPStdioClient(scriptPath, environment = envVars)
        
        // 백그라운드에서 연결 시도
        Thread {
            try {
                mcpStdioClient?.connect()
                Logger.info("TaskManagementDialog", "MCP Stdio 클라이언트 연결 성공")
                loadTaskList()
            } catch (e: Exception) {
                Logger.error("TaskManagementDialog", "MCP Stdio 연결 실패: ${e.message}")
                SwingUtilities.invokeLater {
                    updateStatusLabel("⚠️ MCP 서버 연결 실패", Color(231, 76, 60))
                }
            }
        }.start()
    }
    
    override fun dispose() {
        mcpStdioClient?.disconnect()
        super.dispose()
    }
    
    override fun createCenterPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(1200, 750)
        panel.background = Color(30, 30, 30) // #1E1E1E - Copilot 다크 배경
        panel.border = EmptyBorder(15, 15, 15, 15)
        
        // 헤더 패널
        val headerPanel = createHeaderPanel()
        panel.add(headerPanel, BorderLayout.NORTH)
        
        // 중앙 작업 목록 테이블
        val tablePanel = createTablePanel()
        panel.add(tablePanel, BorderLayout.CENTER)
        
        // 하단 상태 패널
        val statusPanel = createStatusPanel()
        panel.add(statusPanel, BorderLayout.SOUTH)
        
        return panel
    }
    
    /**
     * 헤더 패널 생성 - 게시판 스타일
     */
    private fun createHeaderPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.background = Color(45, 45, 45) // #2D2D2D
        panel.border = BorderFactory.createCompoundBorder(
            LineBorder(Color(139, 92, 246), 2, true), // #8B5CF6 - Copilot 보라색 테두리
            EmptyBorder(15, 20, 15, 20)
        )
        
        // 왼쪽: 제목 및 통계
        val leftPanel = JPanel()
        leftPanel.layout = BoxLayout(leftPanel, BoxLayout.Y_AXIS)
        leftPanel.background = Color(45, 45, 45)
        
        val titleLabel = JLabel("📋 나의 작업 게시판")
        titleLabel.font = Font("SansSerif", Font.BOLD, 20)
        titleLabel.foreground = Color(167, 139, 250) // #A78BFA - 밝은 보라색
        leftPanel.add(titleLabel)
        
        leftPanel.add(Box.createVerticalStrut(5))
        
        taskCountLabel = JLabel("전체 0개 작업")
        taskCountLabel.font = Font("SansSerif", Font.PLAIN, 13)
        taskCountLabel.foreground = Color(180, 180, 180)
        leftPanel.add(taskCountLabel)
        
        panel.add(leftPanel, BorderLayout.WEST)
        
        // 오른쪽: 필터 및 버튼
        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0))
        rightPanel.background = Color(45, 45, 45)
        
        // 상태 필터
        val statusLabel = JLabel("상태:")
        statusLabel.font = Font("SansSerif", Font.BOLD, 12)
        statusLabel.foreground = Color(220, 220, 220)
        rightPanel.add(statusLabel)
        
        statusFilterCombo = createStyledComboBox(arrayOf("전체", "대기", "진행중", "검토", "완료", "차단"))
        statusFilterCombo.addActionListener { applyFilters() }
        rightPanel.add(statusFilterCombo)
        
        rightPanel.add(Box.createHorizontalStrut(10))
        
        // 우선순위 필터
        val priorityLabel = JLabel("우선순위:")
        priorityLabel.font = Font("SansSerif", Font.BOLD, 12)
        priorityLabel.foreground = Color(220, 220, 220)
        rightPanel.add(priorityLabel)
        
        priorityFilterCombo = createStyledComboBox(arrayOf("전체", "낮음", "보통", "높음", "긴급"))
        priorityFilterCombo.addActionListener { applyFilters() }
        rightPanel.add(priorityFilterCombo)
        
        rightPanel.add(Box.createHorizontalStrut(15))
        
        // 새로고침 버튼
        refreshButton = createStyledButton("🔄 새로고침", Color(139, 92, 246), Color.WHITE)
        refreshButton.addActionListener { loadTaskList() }
        rightPanel.add(refreshButton)
        
        panel.add(rightPanel, BorderLayout.EAST)
        
        return panel
    }
    
    /**
     * 스타일이 적용된 콤보박스 생성
     */
    private fun createStyledComboBox(items: Array<String>): JComboBox<String> {
        val combo = JComboBox(items)
        combo.font = Font("SansSerif", Font.PLAIN, 12)
        combo.background = Color(60, 60, 60)
        combo.foreground = Color(220, 220, 220)
        combo.border = BorderFactory.createCompoundBorder(
            LineBorder(Color(100, 100, 100), 1, true),
            EmptyBorder(5, 10, 5, 10)
        )
        combo.preferredSize = Dimension(120, 35)
        combo.cursor = Cursor(Cursor.HAND_CURSOR)
        return combo
    }
    
    /**
     * 테이블 패널 생성 - 게시판 스타일
     */
    private fun createTablePanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.background = Color(30, 30, 30)
        panel.border = EmptyBorder(15, 0, 0, 0)
        
        // 테이블 모델 생성
        val columnNames = arrayOf("번호", "제목", "상태", "우선순위", "담당자", "시작일", "마감일", "예상시간")
        val tableModel = object : DefaultTableModel(columnNames, 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }
        
        taskTable = JTable(tableModel)
        taskTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        taskTable.rowHeight = 45 // 더 넓은 행 높이
        taskTable.font = Font("SansSerif", Font.PLAIN, 13)
        taskTable.background = Color(45, 45, 45) // #2D2D2D
        taskTable.foreground = Color(220, 220, 220)
        taskTable.setShowGrid(true)
        taskTable.gridColor = Color(60, 60, 60)
        taskTable.intercellSpacing = Dimension(8, 8)
        taskTable.selectionBackground = Color(139, 92, 246, 50) // 반투명 보라색
        taskTable.selectionForeground = Color.WHITE
        
        // 테이블 헤더 스타일
        val header = taskTable.tableHeader
        header.background = Color(60, 60, 60)
        header.foreground = Color(220, 220, 220)
        header.font = Font("SansSerif", Font.BOLD, 13)
        header.border = LineBorder(Color(100, 100, 100), 1)
        
        // 컬럼 너비 설정
        taskTable.columnModel.getColumn(0).preferredWidth = 60   // 번호
        taskTable.columnModel.getColumn(1).preferredWidth = 350  // 제목 (더 넓게)
        taskTable.columnModel.getColumn(2).preferredWidth = 90   // 상태
        taskTable.columnModel.getColumn(3).preferredWidth = 90   // 우선순위
        taskTable.columnModel.getColumn(4).preferredWidth = 100  // 담당자
        taskTable.columnModel.getColumn(5).preferredWidth = 110  // 시작일
        taskTable.columnModel.getColumn(6).preferredWidth = 110  // 마감일
        taskTable.columnModel.getColumn(7).preferredWidth = 90   // 예상시간
        
        // 커스텀 렌더러 - 게시판 스타일
        taskTable.setDefaultRenderer(Any::class.java, object : TableCellRenderer {
            override fun getTableCellRendererComponent(
                table: JTable?,
                value: Any?,
                isSelected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ): Component {
                val label = JLabel(value?.toString() ?: "")
                label.font = Font("SansSerif", Font.PLAIN, 13)
                label.border = EmptyBorder(5, 10, 5, 10)
                label.isOpaque = true
                
                // 배경색
                label.background = if (isSelected) {
                    Color(139, 92, 246, 80)
                } else if (row % 2 == 0) {
                    Color(45, 45, 45)
                } else {
                    Color(40, 40, 40)
                }
                
                // 텍스트 색상
                label.foreground = if (isSelected) Color.WHITE else Color(220, 220, 220)
                
                // 상태 컬럼 (인덱스 2)
                if (column == 2 && row < filteredTaskList.size) {
                    val task = filteredTaskList[row]
                    label.foreground = task.getStatusColor()
                    label.text = "● " + task.getStatusDisplayName()
                    label.font = Font("SansSerif", Font.BOLD, 13)
                }
                // 우선순위 컬럼 (인덱스 3)
                else if (column == 3 && row < filteredTaskList.size) {
                    val task = filteredTaskList[row]
                    label.foreground = task.getPriorityColor()
                    label.text = "⚡ " + task.getPriorityDisplayName()
                    label.font = Font("SansSerif", Font.BOLD, 13)
                }
                // 제목 컬럼 (인덱스 1) - 볼드체
                else if (column == 1) {
                    label.font = Font("SansSerif", Font.BOLD, 14)
                    label.foreground = if (isSelected) Color.WHITE else Color(167, 139, 250)
                }
                
                return label
            }
        })
        
        // 더블 클릭 시 상세 정보 표시
        taskTable.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) {
                    val selectedRow = taskTable.selectedRow
                    if (selectedRow >= 0 && selectedRow < filteredTaskList.size) {
                        showTaskDetailDialog(filteredTaskList[selectedRow])
                    }
                }
            }
        })
        
        val scrollPane = JBScrollPane(taskTable)
        scrollPane.border = LineBorder(Color(100, 100, 100), 1, true)
        scrollPane.background = Color(45, 45, 45)
        panel.add(scrollPane, BorderLayout.CENTER)
        
        return panel
    }
    
    /**
     * 상태 패널 생성
     */
    private fun createStatusPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.background = Color(30, 30, 30)
        panel.border = EmptyBorder(15, 0, 0, 0)
        
        statusLabel = JLabel("💬 작업 목록을 불러오는 중...")
        statusLabel.font = Font("SansSerif", Font.PLAIN, 12)
        statusLabel.foreground = Color(150, 150, 150)
        panel.add(statusLabel, BorderLayout.WEST)
        
        // 도움말 레이블
        val helpLabel = JLabel("💡 작업을 더블클릭하면 상세 정보를 수정할 수 있습니다")
        helpLabel.font = Font("SansSerif", Font.ITALIC, 11)
        helpLabel.foreground = Color(120, 120, 120)
        panel.add(helpLabel, BorderLayout.EAST)
        
        return panel
    }
    
    /**
     * 작업 목록 로드
     */
    private fun loadTaskList() {
        val username = sessionManager.getCurrentUsername()
        if (username == null || username.isBlank()) {
            updateStatusLabel("⚠️ 로그인이 필요합니다", Color(231, 76, 60))
            JOptionPane.showMessageDialog(
                contentPanel,
                "작업 목록을 조회하려면 먼저 로그인해주세요.",
                "로그인 필요",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }
        
        if (mcpStdioClient == null || !mcpStdioClient!!.isConnected()) {
            updateStatusLabel("⚠️ MCP 서버에 연결되지 않았습니다", Color(231, 76, 60))
            return
        }
        
        Logger.info("TaskManagementDialog", "작업 목록 조회 시작: username=$username")
        
        updateStatusLabel("🔄 작업 목록을 불러오는 중...", Color(139, 92, 246))
        refreshButton.isEnabled = false
        
        Thread {
            try {
                val tasks = mcpStdioClient!!.getAssignedTasks(username)
                
                SwingUtilities.invokeLater {
                    refreshButton.isEnabled = true
                    taskList = tasks
                    applyFilters()
                    updateStatusLabel("✅ 작업 목록 조회 완료", Color(46, 204, 113))
                    taskCountLabel.text = "전체 ${tasks.size}개 작업"
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    refreshButton.isEnabled = true
                    taskList = emptyList()
                    updateTaskTable()
                    updateStatusLabel("❌ 오류 발생: ${e.message}", Color(231, 76, 60))
                    Logger.error("TaskManagementDialog", "작업 목록 로드 오류: ${e.message}")
                    showErrorDialog("작업 목록 조회 중 오류가 발생했습니다.\n${e.message}")
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
        updateStatusLabel("📊 표시: ${filteredTaskList.size}개 / 전체: ${taskList.size}개", Color(150, 150, 150))
    }
    
    /**
     * 테이블 업데이트
     */
    private fun updateTaskTable() {
        val tableModel = taskTable.model as DefaultTableModel
        tableModel.rowCount = 0
        
        filteredTaskList.forEachIndexed { index, task ->
            tableModel.addRow(arrayOf(
                (index + 1).toString(),  // 번호
                task.title,
                task.status,
                task.priority ?: "",
                task.assigneeName ?: "-",
                task.startDate ?: "-",
                task.dueDate ?: "-",
                if (task.estimatedHours != null) "${task.estimatedHours}h" else "-"
            ))
        }
        
        taskTable.repaint()
    }
    
    /**
     * 작업 상세 정보 다이얼로그 - 수정 가능
     */
    private fun showTaskDetailDialog(task: AssignedTask) {
        val dialog = JDialog(peer.owner as? Window, "작업 상세 정보", Dialog.ModalityType.APPLICATION_MODAL)
        dialog.layout = BorderLayout()
        dialog.background = Color(30, 30, 30)
        
        // 메인 패널
        val mainPanel = JPanel()
        mainPanel.layout = BoxLayout(mainPanel, BoxLayout.Y_AXIS)
        mainPanel.background = Color(30, 30, 30)
        mainPanel.border = EmptyBorder(20, 20, 20, 20)
        
        // 제목
        val titleLabel = JLabel("📋 ${task.title}")
        titleLabel.font = Font("SansSerif", Font.BOLD, 18)
        titleLabel.foreground = Color(167, 139, 250)
        titleLabel.alignmentX = Component.LEFT_ALIGNMENT
        mainPanel.add(titleLabel)
        mainPanel.add(Box.createVerticalStrut(15))
        
        // 정보 패널
        val infoPanel = JPanel(GridBagLayout())
        infoPanel.background = Color(45, 45, 45)
        infoPanel.border = BorderFactory.createCompoundBorder(
            LineBorder(Color(100, 100, 100), 1, true),
            EmptyBorder(15, 15, 15, 15)
        )
        infoPanel.alignmentX = Component.LEFT_ALIGNMENT
        
        val gbc = GridBagConstraints()
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = Insets(5, 5, 5, 5)
        
        var row = 0
        
        // ID (읽기 전용)
        addInfoRow(infoPanel, gbc, row++, "ID:", task.id.toString())
        
        // 상태 (수정 가능)
        val statusCombo = JComboBox(arrayOf("PENDING", "IN_PROGRESS", "REVIEW", "COMPLETED", "BLOCKED"))
        statusCombo.selectedItem = task.status
        styleComboBox(statusCombo)
        addInfoRow(infoPanel, gbc, row++, "상태:", statusCombo)
        
        // 우선순위 (읽기 전용)
        if (task.priority != null) {
            addInfoRow(infoPanel, gbc, row++, "우선순위:", task.getPriorityDisplayName())
        }
        
        // 담당자 (읽기 전용)
        if (task.assigneeName != null) {
            addInfoRow(infoPanel, gbc, row++, "담당자:", task.assigneeName)
        }
        
        // 시작일 (수정 가능)
        val startDateField = JTextField(task.startDate ?: "")
        styleTextField(startDateField)
        startDateField.toolTipText = "YYYY-MM-DD 형식"
        addInfoRow(infoPanel, gbc, row++, "시작일:", startDateField)
        
        // 마감일 (수정 가능)
        val dueDateField = JTextField(task.dueDate ?: "")
        styleTextField(dueDateField)
        dueDateField.toolTipText = "YYYY-MM-DD 형식"
        addInfoRow(infoPanel, gbc, row++, "마감일:", dueDateField)
        
        // 예상 시간 (읽기 전용)
        if (task.estimatedHours != null) {
            addInfoRow(infoPanel, gbc, row++, "예상 시간:", "${task.estimatedHours}시간")
        }
        
        // 실제 시간 (수정 가능)
        val actualHoursField = JTextField(task.actualHours?.toString() ?: "")
        styleTextField(actualHoursField)
        actualHoursField.toolTipText = "숫자만 입력"
        addInfoRow(infoPanel, gbc, row++, "실제 시간:", actualHoursField)
        
        mainPanel.add(infoPanel)
        
        // 설명
        if (task.description != null && task.description.isNotBlank()) {
            mainPanel.add(Box.createVerticalStrut(15))
            
            val descPanel = JPanel(BorderLayout())
            descPanel.background = Color(45, 45, 45)
            descPanel.border = BorderFactory.createCompoundBorder(
                LineBorder(Color(100, 100, 100), 1, true),
                EmptyBorder(15, 15, 15, 15)
            )
            descPanel.alignmentX = Component.LEFT_ALIGNMENT
            
            val descLabel = JLabel("📝 설명")
            descLabel.font = Font("SansSerif", Font.BOLD, 14)
            descLabel.foreground = Color(167, 139, 250)
            descPanel.add(descLabel, BorderLayout.NORTH)
            
            val descText = JTextArea(task.description)
            descText.font = Font("SansSerif", Font.PLAIN, 13)
            descText.foreground = Color(220, 220, 220)
            descText.background = Color(30, 30, 30)
            descText.isEditable = false
            descText.lineWrap = true
            descText.wrapStyleWord = true
            descText.border = EmptyBorder(10, 0, 0, 0)
            
            val scrollPane = JScrollPane(descText)
            scrollPane.preferredSize = Dimension(500, 100)
            scrollPane.border = null
            scrollPane.background = Color(30, 30, 30)
            descPanel.add(scrollPane, BorderLayout.CENTER)
            
            mainPanel.add(descPanel)
        }
        
        mainPanel.add(Box.createVerticalStrut(20))
        
        // 버튼 패널
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0))
        buttonPanel.background = Color(30, 30, 30)
        buttonPanel.alignmentX = Component.LEFT_ALIGNMENT
        
        val saveButton = createStyledButton("💾 저장", Color(139, 92, 246), Color.WHITE)
        val cancelButton = createStyledButton("✖ 취소", Color(100, 100, 100), Color.WHITE)
        
        saveButton.addActionListener {
            // 변경사항 저장 로직
            val newStatus = statusCombo.selectedItem as String
            val newStartDate = startDateField.text.trim().takeIf { it.isNotEmpty() }
            val newDueDate = dueDateField.text.trim().takeIf { it.isNotEmpty() }
            val newActualHours = actualHoursField.text.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
            
            // 변경사항 확인
            val hasChanges = newStatus != task.status ||
                            newStartDate != task.startDate ||
                            newDueDate != task.dueDate ||
                            newActualHours != task.actualHours
            
            if (!hasChanges) {
                JOptionPane.showMessageDialog(dialog, "변경된 내용이 없습니다.", "알림", JOptionPane.INFORMATION_MESSAGE)
                return@addActionListener
            }
            
            // 날짜 형식 검증
            val datePattern = Regex("^\\d{4}-\\d{2}-\\d{2}$")
            if (newStartDate != null && !newStartDate.matches(datePattern)) {
                JOptionPane.showMessageDialog(dialog, "시작일 형식이 올바르지 않습니다. (YYYY-MM-DD)", "오류", JOptionPane.ERROR_MESSAGE)
                return@addActionListener
            }
            if (newDueDate != null && !newDueDate.matches(datePattern)) {
                JOptionPane.showMessageDialog(dialog, "마감일 형식이 올바르지 않습니다. (YYYY-MM-DD)", "오류", JOptionPane.ERROR_MESSAGE)
                return@addActionListener
            }
            
            // MCP를 통해 서버로 전송
            saveButton.isEnabled = false
            saveButton.text = "⏳ 저장 중..."
            
            Thread {
                try {
                    val success = mcpStdioClient?.updateTask(
                        taskId = task.id,
                        status = if (newStatus != task.status) newStatus else null,
                        startDate = if (newStartDate != task.startDate) newStartDate else null,
                        dueDate = if (newDueDate != task.dueDate) newDueDate else null,
                        actualHours = if (newActualHours != task.actualHours) newActualHours else null
                    ) ?: false
                    
                    SwingUtilities.invokeLater {
                        saveButton.isEnabled = true
                        saveButton.text = "💾 저장"
                        
                        if (success) {
                            JOptionPane.showMessageDialog(dialog, "✅ 작업 정보가 성공적으로 업데이트되었습니다.", "성공", JOptionPane.INFORMATION_MESSAGE)
                            dialog.dispose()
                            loadTaskList()
                        } else {
                            JOptionPane.showMessageDialog(dialog, "❌ 작업 정보 업데이트에 실패했습니다.", "오류", JOptionPane.ERROR_MESSAGE)
                        }
                    }
                } catch (e: Exception) {
                    SwingUtilities.invokeLater {
                        saveButton.isEnabled = true
                        saveButton.text = "💾 저장"
                        JOptionPane.showMessageDialog(dialog, "❌ 오류 발생: ${e.message}", "오류", JOptionPane.ERROR_MESSAGE)
                    }
                }
            }.start()
        }
        
        cancelButton.addActionListener { dialog.dispose() }
        
        buttonPanel.add(saveButton)
        buttonPanel.add(cancelButton)
        mainPanel.add(buttonPanel)
        
        val scrollPane = JScrollPane(mainPanel)
        scrollPane.border = null
        dialog.add(scrollPane, BorderLayout.CENTER)
        dialog.setSize(600, 650)
        dialog.setLocationRelativeTo(peer.owner)
        dialog.isVisible = true
    }
    
    private fun addInfoRow(panel: JPanel, gbc: GridBagConstraints, row: Int, label: String, value: String) {
        gbc.gridx = 0
        gbc.gridy = row
        gbc.weightx = 0.3
        
        val labelComp = JLabel(label)
        labelComp.font = Font("SansSerif", Font.BOLD, 13)
        labelComp.foreground = Color(167, 139, 250)
        panel.add(labelComp, gbc)
        
        gbc.gridx = 1
        gbc.weightx = 0.7
        
        val valueLabel = JLabel(value)
        valueLabel.font = Font("SansSerif", Font.PLAIN, 13)
        valueLabel.foreground = Color(220, 220, 220)
        panel.add(valueLabel, gbc)
    }
    
    private fun addInfoRow(panel: JPanel, gbc: GridBagConstraints, row: Int, label: String, component: JComponent) {
        gbc.gridx = 0
        gbc.gridy = row
        gbc.weightx = 0.3
        
        val labelComp = JLabel(label)
        labelComp.font = Font("SansSerif", Font.BOLD, 13)
        labelComp.foreground = Color(167, 139, 250)
        panel.add(labelComp, gbc)
        
        gbc.gridx = 1
        gbc.weightx = 0.7
        panel.add(component, gbc)
    }
    
    private fun styleTextField(field: JTextField) {
        field.font = Font("SansSerif", Font.PLAIN, 13)
        field.foreground = Color(220, 220, 220)
        field.background = Color(60, 60, 60)
        field.caretColor = Color(167, 139, 250)
        field.border = BorderFactory.createCompoundBorder(
            LineBorder(Color(100, 100, 100), 1, true),
            EmptyBorder(5, 10, 5, 10)
        )
    }
    
    private fun styleComboBox(combo: JComboBox<String>) {
        combo.font = Font("SansSerif", Font.PLAIN, 13)
        combo.foreground = Color(220, 220, 220)
        combo.background = Color(60, 60, 60)
        combo.border = BorderFactory.createCompoundBorder(
            LineBorder(Color(100, 100, 100), 1, true),
            EmptyBorder(5, 10, 5, 10)
        )
    }
    
    private fun updateStatusLabel(text: String, color: Color) {
        statusLabel.text = text
        statusLabel.foreground = color
    }
    
    private fun showErrorDialog(message: String) {
        JOptionPane.showMessageDialog(contentPanel, message, "오류", JOptionPane.ERROR_MESSAGE)
    }
    
    private fun createStyledButton(text: String, bgColor: Color, fgColor: Color): JButton {
        val button = JButton(text)
        button.background = bgColor
        button.foreground = fgColor
        button.font = Font("SansSerif", Font.BOLD, 13)
        button.border = BorderFactory.createCompoundBorder(
            LineBorder(bgColor.darker(), 1, true),
            EmptyBorder(8, 20, 8, 20)
        )
        button.isOpaque = true
        button.isFocusPainted = false
        button.cursor = Cursor(Cursor.HAND_CURSOR)
        
        button.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseEntered(e: java.awt.event.MouseEvent) {
                button.background = Color(167, 139, 250)
            }
            override fun mouseExited(e: java.awt.event.MouseEvent) {
                button.background = bgColor
            }
        })
        
        return button
    }
}
