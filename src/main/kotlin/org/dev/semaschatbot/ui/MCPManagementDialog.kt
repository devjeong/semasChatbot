package org.dev.semaschatbot.ui

import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import org.dev.semaschatbot.*
import java.awt.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.border.LineBorder

/**
 * MCP 관리 다이얼로그
 * 
 * MCP 기능 활성/비활성 및 MCP 서버 연결을 관리하는 UI를 제공합니다.
 */
class MCPManagementDialog : DialogWrapper(true) {
    
    private lateinit var mcpEnabledToggle: JToggleButton
    private lateinit var refreshButton: JButton
    private lateinit var testButton: JButton
    private lateinit var mcpListPanel: JPanel
    private lateinit var statusLabel: JLabel
    
    private val mcpApiClient = MCPApiClient()
    private val project = ProjectManager.getInstance().defaultProject
    private val mcpSettings = MCPSettings(project)
    
    private var mcpList: List<MCPListItem> = emptyList()
    private val mcpItemPanels: MutableMap<String, MCPItemPanel> = mutableMapOf()
    
    init {
        title = "MCP 관리"
        init()
        
        // 서버 URL 동기화
        try {
            val chatService = project.getService(ChatService::class.java)
            if (chatService != null) {
                val serverBaseUrl = chatService.getServerBaseUrl()
                mcpApiClient.setServerBaseUrl(serverBaseUrl)
            }
        } catch (e: Exception) {
            Logger.debug("MCPManagementDialog", "ChatService 초기화 대기 중")
        }
        
        // 초기 상태 설정 (설정 파일에서 로드)
        mcpSettings.loadSettings() // 명시적으로 설정 로드
        val initialMCPEnabled = mcpSettings.isMCPEnabled()
        Logger.info("MCPManagementDialog", "초기 MCP 활성화 상태: $initialMCPEnabled")
        
        mcpEnabledToggle.isSelected = initialMCPEnabled
        updateUIState()
        
        // 초기 작업 관리 MCP 연결 상태 확인 및 세션 동기화
        if (initialMCPEnabled) {
            val allConnections = mcpSettings.getAllMCPConnections()
            Logger.info("MCPManagementDialog", "저장된 MCP 연결 수: ${allConnections.size}")
            allConnections.forEach { (id, connection) ->
                Logger.info("MCPManagementDialog", "저장된 연결: id=$id, name=${connection.mcpName}, connected=${connection.isConnected}")
            }
            
            val taskMCPConnected = allConnections.values.any { connection ->
                connection.isConnected && (
                    connection.mcpName.contains("task", ignoreCase = true) ||
                    connection.mcpName.contains("작업", ignoreCase = true) ||
                    connection.mcpId.contains("task", ignoreCase = true) ||
                    connection.mcpId.contains("작업", ignoreCase = true)
                )
            }
            Logger.info("MCPManagementDialog", "초기 작업 관리 MCP 연결 상태: $taskMCPConnected")
            SessionManager.getInstance().setTaskMCPConnected(taskMCPConnected)
            loadMCPList()
        } else {
            SessionManager.getInstance().setTaskMCPConnected(false)
        }
        
        // 초기화 시 자동으로 연결 테스트 실행 (선택사항)
        // runConnectionTest()
    }
    
    override fun createCenterPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(800, 600)
        panel.border = EmptyBorder(10, 10, 10, 10)
        
        // 상단 제어 패널
        val controlPanel = createControlPanel()
        panel.add(controlPanel, BorderLayout.NORTH)
        
        // 중앙 MCP 목록 영역
        mcpListPanel = JPanel()
        mcpListPanel.layout = BoxLayout(mcpListPanel, BoxLayout.Y_AXIS)
        mcpListPanel.border = LineBorder(Color(200, 200, 200), 1)
        mcpListPanel.background = Color.WHITE
        
        val scrollPane = JBScrollPane(mcpListPanel)
        scrollPane.border = EmptyBorder(5, 0, 0, 0)
        scrollPane.preferredSize = Dimension(780, 500)
        panel.add(scrollPane, BorderLayout.CENTER)
        
        // 하단 상태 패널
        val statusPanel = createStatusPanel()
        panel.add(statusPanel, BorderLayout.SOUTH)
        
        return panel
    }
    
    /**
     * 제어 패널 생성 (MCP 기능 토글 및 새로고침 버튼)
     */
    private fun createControlPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = EmptyBorder(0, 0, 10, 0)
        
        // 왼쪽: MCP 기능 토글 및 테스트 버튼
        val togglePanel = JPanel(FlowLayout(FlowLayout.LEFT))
        togglePanel.background = Color(245, 245, 245)
        
        val toggleLabel = JLabel("MCP 기능:")
        toggleLabel.font = Font("SansSerif", Font.BOLD, 12)
        togglePanel.add(toggleLabel)
        
        mcpEnabledToggle = JToggleButton("OFF")
        mcpEnabledToggle.font = Font("SansSerif", Font.BOLD, 11)
        mcpEnabledToggle.preferredSize = Dimension(60, 25)
        mcpEnabledToggle.addActionListener {
            val enabled = mcpEnabledToggle.isSelected
            mcpSettings.setMCPEnabled(enabled)
            mcpEnabledToggle.text = if (enabled) "ON" else "OFF"
            mcpEnabledToggle.background = if (enabled) Color(52, 152, 219) else Color(200, 200, 200)
            updateUIState()
            
            if (enabled) {
                loadMCPList()
            } else {
                // 비활성화 시 모든 연결 해제
                disconnectAllMCPs()
                // 작업 관리 MCP 세션 상태도 초기화
                SessionManager.getInstance().setTaskMCPConnected(false)
            }
        }
        updateToggleButton()
        togglePanel.add(mcpEnabledToggle)
        
        val statusTextLabel = JLabel(if (mcpSettings.isMCPEnabled()) "활성화됨" else "비활성화됨")
        statusTextLabel.font = Font("SansSerif", Font.PLAIN, 11)
        statusTextLabel.foreground = if (mcpSettings.isMCPEnabled()) Color(46, 204, 113) else Color(150, 150, 150)
        togglePanel.add(statusTextLabel)
        
        panel.add(togglePanel, BorderLayout.WEST)
        
        // 오른쪽: 새로고침 및 테스트 버튼
        val refreshPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        refreshPanel.background = Color(245, 245, 245)
        
        testButton = JButton("🧪 연결 테스트")
        testButton.font = Font("SansSerif", Font.PLAIN, 11)
        testButton.foreground = Color(46, 204, 113)
        testButton.addActionListener {
            runConnectionTest()
        }
        refreshPanel.add(testButton)
        
        refreshButton = JButton("🔄 새로고침")
        refreshButton.font = Font("SansSerif", Font.PLAIN, 11)
        refreshButton.addActionListener {
            loadMCPList()
        }
        refreshPanel.add(refreshButton)
        
        panel.add(refreshPanel, BorderLayout.EAST)
        
        return panel
    }
    
    /**
     * 상태 패널 생성
     */
    private fun createStatusPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = EmptyBorder(10, 0, 0, 0)
        
        statusLabel = JLabel("준비됨")
        statusLabel.font = Font("SansSerif", Font.PLAIN, 11)
        statusLabel.foreground = Color(100, 100, 100)
        panel.add(statusLabel, BorderLayout.WEST)
        
        return panel
    }
    
    /**
     * 토글 버튼 상태 업데이트
     */
    private fun updateToggleButton() {
        val enabled = mcpSettings.isMCPEnabled()
        mcpEnabledToggle.isSelected = enabled
        mcpEnabledToggle.text = if (enabled) "ON" else "OFF"
        mcpEnabledToggle.background = if (enabled) Color(52, 152, 219) else Color(200, 200, 200)
    }
    
    /**
     * UI 상태 업데이트 (MCP 기능 활성/비활성에 따라)
     */
    private fun updateUIState() {
        val enabled = mcpSettings.isMCPEnabled()
        refreshButton.isEnabled = enabled
        mcpListPanel.isEnabled = enabled
        
        // MCP 목록 패널의 모든 항목 활성/비활성화
        mcpItemPanels.values.forEach { itemPanel ->
            itemPanel.setEnabled(enabled)
        }
    }
    
    /**
     * MCP 목록 로드
     */
    private fun loadMCPList() {
        if (!mcpSettings.isMCPEnabled()) {
            updateStatusLabel("MCP 기능이 비활성화되어 있습니다.", Color(150, 150, 150))
            return
        }
        
        updateStatusLabel("MCP 목록을 불러오는 중...", Color(52, 152, 219))
        refreshButton.isEnabled = false
        
        // 백그라운드 스레드에서 API 호출
        Thread {
            try {
                val (success, list) = mcpApiClient.getMCPList()
                
                SwingUtilities.invokeLater {
                    refreshButton.isEnabled = true
                    
                    if (success && list.isNotEmpty()) {
                        mcpList = list
                        updateMCPListDisplay()
                        updateStatusLabel("MCP 목록 조회 완료 (${list.size}개)", Color(46, 204, 113))
                    } else if (success && list.isEmpty()) {
                        mcpList = emptyList()
                        updateMCPListDisplay()
                        updateStatusLabel("사용 가능한 MCP가 없습니다.", Color(241, 196, 15))
                    } else {
                        updateStatusLabel("MCP 목록 조회 실패", Color(231, 76, 60))
                        showErrorDialog("MCP 목록을 불러오는데 실패했습니다.")
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    refreshButton.isEnabled = true
                    updateStatusLabel("오류 발생: ${e.message}", Color(231, 76, 60))
                    Logger.error("MCPManagementDialog", "MCP 목록 로드 오류: ${e.message}")
                }
            }
        }.start()
    }
    
    /**
     * MCP 목록 표시 업데이트
     */
    private fun updateMCPListDisplay() {
        mcpListPanel.removeAll()
        mcpItemPanels.clear()
        
        if (mcpList.isEmpty()) {
            val emptyLabel = JLabel("사용 가능한 MCP가 없습니다.")
            emptyLabel.font = Font("SansSerif", Font.PLAIN, 12)
            emptyLabel.foreground = Color(150, 150, 150)
            emptyLabel.horizontalAlignment = SwingConstants.CENTER
            emptyLabel.border = EmptyBorder(20, 0, 20, 0)
            mcpListPanel.add(emptyLabel)
        } else {
            mcpList.forEach { mcpItem ->
                // MCP 정보 로깅 (디버깅용)
                val savedConnection = mcpSettings.getMCPConnection(mcpItem.id)
                val isConnected = savedConnection?.isConnected ?: false
                
                Logger.info("MCPManagementDialog", "MCP 항목 로드: name='${mcpItem.name}', id='${mcpItem.id}', description='${mcpItem.description}', isConnected=$isConnected")
                
                // 작업 관리 MCP인지 확인
                val nameCheck = mcpItem.name.contains("task", ignoreCase = true) || mcpItem.name.contains("작업", ignoreCase = true)
                val idCheck = mcpItem.id.contains("task", ignoreCase = true) || mcpItem.id.contains("작업", ignoreCase = true)
                val descCheck = mcpItem.description?.contains("task", ignoreCase = true) == true || 
                               mcpItem.description?.contains("작업", ignoreCase = true) == true
                val isTaskMCP = nameCheck || idCheck || descCheck
                
                if (isTaskMCP && isConnected) {
                    Logger.info("MCPManagementDialog", "작업 관리 MCP 연결 상태 발견: ${mcpItem.name} (id: ${mcpItem.id})")
                    // 세션 상태 동기화
                    SessionManager.getInstance().setTaskMCPConnected(true)
                }
                
                val itemPanel = MCPItemPanel(
                    mcpItem = mcpItem,
                    isConnected = isConnected,
                    onToggle = { mcpId, enabled ->
                        if (enabled) {
                            connectMCP(mcpId)
                        } else {
                            disconnectMCP(mcpId)
                        }
                    }
                )
                itemPanel.setEnabled(mcpSettings.isMCPEnabled())
                mcpItemPanels[mcpItem.id] = itemPanel
                mcpListPanel.add(itemPanel)
                mcpListPanel.add(Box.createVerticalStrut(5))
            }
        }
        
        mcpListPanel.revalidate()
        mcpListPanel.repaint()
    }
    
    /**
     * MCP 연결
     */
    private fun connectMCP(mcpId: String) {
        val mcpItem = mcpList.find { it.id == mcpId } ?: return
        val itemPanel = mcpItemPanels[mcpId] ?: return
        
        itemPanel.setConnecting(true)
        updateStatusLabel("연결 중: ${mcpItem.name}", Color(52, 152, 219))
        
        // 백그라운드 스레드에서 연결 처리
        Thread {
            try {
                // 실제 MCP 연결 로직은 여기에 구현 (현재는 시뮬레이션)
                Thread.sleep(500) // 연결 시뮬레이션
                
                val connectedAt = System.currentTimeMillis()
                val connection = MCPConnection(
                    mcpId = mcpItem.id,
                    mcpName = mcpItem.name,
                    mcpEndpoint = mcpItem.endpoint,
                    isConnected = true,
                    connectedAt = connectedAt
                )
                mcpSettings.setMCPConnection(mcpId, connection)
                
                // 작업 관리 MCP인 경우 세션 상태 업데이트
                val nameCheck = mcpItem.name.contains("task", ignoreCase = true) || mcpItem.name.contains("작업", ignoreCase = true)
                val idCheck = mcpItem.id.contains("task", ignoreCase = true) || mcpItem.id.contains("작업", ignoreCase = true)
                val descCheck = mcpItem.description?.contains("task", ignoreCase = true) == true || 
                               mcpItem.description?.contains("작업", ignoreCase = true) == true
                
                val isTaskMCP = nameCheck || idCheck || descCheck
                
                Logger.info("MCPManagementDialog", "MCP 연결 체크: name='${mcpItem.name}', id='${mcpItem.id}', nameCheck=$nameCheck, idCheck=$idCheck, descCheck=$descCheck, isTaskMCP=$isTaskMCP")
                
                if (isTaskMCP) {
                    Logger.info("MCPManagementDialog", "작업 관리 MCP 연결 감지: ${mcpItem.name} (id: ${mcpItem.id})")
                    val sessionManager = SessionManager.getInstance()
                    sessionManager.setTaskMCPConnected(true)
                    val updatedState = sessionManager.isTaskMCPConnected()
                    Logger.info("MCPManagementDialog", "세션 상태 업데이트 완료: $updatedState")
                } else {
                    Logger.debug("MCPManagementDialog", "일반 MCP 연결: ${mcpItem.name} (id: ${mcpItem.id})")
                }
                
                // 서버로 연결 정보 전송
                sendConnectionInfoToServer(mcpItem, "connect", connectedAt)
                
                SwingUtilities.invokeLater {
                    itemPanel.setConnecting(false)
                    itemPanel.setConnected(true)
                    updateStatusLabel("연결됨: ${mcpItem.name}", Color(46, 204, 113))
                    
                    // 작업 관리 MCP 연결 상태 확인 및 로그
                    val sessionManager = SessionManager.getInstance()
                    val isTaskMCPConnected = sessionManager.isTaskMCPConnected()
                    Logger.info("MCPManagementDialog", "연결 완료 후 세션 상태 확인: taskMCPConnected=$isTaskMCPConnected")
                    
                    // 작업 관리 MCP인 경우 추가 확인
                    if (isTaskMCP) {
                        val updatedState = sessionManager.isTaskMCPConnected()
                        Logger.info("MCPManagementDialog", "작업 관리 MCP 연결 후 최종 세션 상태: $updatedState")
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    itemPanel.setConnecting(false)
                    itemPanel.setConnected(false)
                    updateStatusLabel("연결 실패: ${e.message}", Color(231, 76, 60))
                    Logger.error("MCPManagementDialog", "MCP 연결 오류: ${e.message}")
                }
            }
        }.start()
    }
    
    /**
     * MCP 연결 해제
     */
    private fun disconnectMCP(mcpId: String) {
        val mcpItem = mcpList.find { it.id == mcpId } ?: return
        val itemPanel = mcpItemPanels[mcpId] ?: return
        
        itemPanel.setConnecting(true)
        updateStatusLabel("연결 해제 중: ${mcpItem.name}", Color(52, 152, 219))
        
        // 백그라운드 스레드에서 연결 해제 처리
        Thread {
            try {
                // 실제 MCP 연결 해제 로직은 여기에 구현 (현재는 시뮬레이션)
                Thread.sleep(300) // 연결 해제 시뮬레이션
                
                val disconnectedAt = System.currentTimeMillis()
                val connection = MCPConnection(
                    mcpId = mcpItem.id,
                    mcpName = mcpItem.name,
                    mcpEndpoint = mcpItem.endpoint,
                    isConnected = false,
                    connectedAt = null
                )
                mcpSettings.setMCPConnection(mcpId, connection)
                
                // 작업 관리 MCP인 경우 세션 상태 업데이트
                val nameCheck = mcpItem.name.contains("task", ignoreCase = true) || mcpItem.name.contains("작업", ignoreCase = true)
                val idCheck = mcpItem.id.contains("task", ignoreCase = true) || mcpItem.id.contains("작업", ignoreCase = true)
                val descCheck = mcpItem.description?.contains("task", ignoreCase = true) == true || 
                               mcpItem.description?.contains("작업", ignoreCase = true) == true
                
                val isTaskMCP = nameCheck || idCheck || descCheck
                
                Logger.info("MCPManagementDialog", "MCP 연결 해제 체크: name='${mcpItem.name}', id='${mcpItem.id}', nameCheck=$nameCheck, idCheck=$idCheck, descCheck=$descCheck, isTaskMCP=$isTaskMCP")
                
                if (isTaskMCP) {
                    Logger.info("MCPManagementDialog", "작업 관리 MCP 연결 해제 감지: ${mcpItem.name} (id: ${mcpItem.id})")
                    val sessionManager = SessionManager.getInstance()
                    sessionManager.setTaskMCPConnected(false)
                    val updatedState = sessionManager.isTaskMCPConnected()
                    Logger.info("MCPManagementDialog", "세션 상태 업데이트 완료: $updatedState")
                } else {
                    Logger.debug("MCPManagementDialog", "일반 MCP 연결 해제: ${mcpItem.name} (id: ${mcpItem.id})")
                }
                
                // 서버로 연결 해제 정보 전송
                sendConnectionInfoToServer(mcpItem, "disconnect", disconnectedAt)
                
                SwingUtilities.invokeLater {
                    itemPanel.setConnecting(false)
                    itemPanel.setConnected(false)
                    updateStatusLabel("연결 해제됨: ${mcpItem.name}", Color(150, 150, 150))
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    itemPanel.setConnecting(false)
                    updateStatusLabel("연결 해제 실패: ${e.message}", Color(231, 76, 60))
                    Logger.error("MCPManagementDialog", "MCP 연결 해제 오류: ${e.message}")
                }
            }
        }.start()
    }
    
    /**
     * 모든 MCP 연결 해제
     */
    private fun disconnectAllMCPs() {
        mcpItemPanels.values.forEach { itemPanel ->
            if (itemPanel.isConnected()) {
                itemPanel.setConnected(false)
            }
        }
        mcpSettings.clearAllConnections()
    }
    
    /**
     * 연결 정보를 서버로 전송
     */
    private fun sendConnectionInfoToServer(mcpItem: MCPListItem, action: String, timestamp: Long) {
        try {
            val sessionManager = SessionManager.getInstance()
            val currentUser = sessionManager.getCurrentUser()
            val userId = currentUser?.username ?: "unknown"
            val username = currentUser?.name ?: "Unknown User"
            
            val ipAddress = getLocalIpAddress()
            // ISO 8601 형식으로 변환 (UTC 기준, 밀리초 제거)
            val dateTime = Instant.ofEpochMilli(timestamp)
                .atZone(ZoneId.of("UTC"))
                .withNano(0) // 밀리초 제거
                .format(DateTimeFormatter.ISO_INSTANT)
            
            val connectionInfo = if (action == "connect") {
                MCPConnectionInfo(
                    userId = userId,
                    username = username,
                    ipAddress = ipAddress,
                    connectedAt = dateTime,
                    disconnectedAt = null,
                    mcpId = mcpItem.id,
                    mcpName = mcpItem.name,
                    mcpEndpoint = mcpItem.endpoint,
                    action = action
                )
            } else {
                MCPConnectionInfo(
                    userId = userId,
                    username = username,
                    ipAddress = ipAddress,
                    connectedAt = null,
                    disconnectedAt = dateTime,
                    mcpId = mcpItem.id,
                    mcpName = mcpItem.name,
                    mcpEndpoint = mcpItem.endpoint,
                    action = action
                )
            }
            
            // 비동기로 서버에 전송 (UI 블로킹 방지)
            Thread {
                val (success, message) = mcpApiClient.sendConnectionInfo(connectionInfo)
                if (!success) {
                    Logger.warn("MCPManagementDialog", "연결 정보 전송 실패: $message")
                    // 연결은 유지되므로 사용자에게 알리지 않음 (로그만 기록)
                }
            }.start()
        } catch (e: Exception) {
            Logger.error("MCPManagementDialog", "연결 정보 전송 오류: ${e.message}")
        }
    }
    
    /**
     * 로컬 IP 주소 조회
     */
    private fun getLocalIpAddress(): String {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
            "unknown"
        } catch (e: Exception) {
            Logger.error("MCPManagementDialog", "IP 주소 조회 실패: ${e.message}")
            "unknown"
        }
    }
    
    /**
     * 상태 레이블 업데이트
     */
    private fun updateStatusLabel(text: String, color: Color) {
        statusLabel.text = text
        statusLabel.foreground = color
    }
    
    /**
     * MCP 연결 테스트 실행
     */
    private fun runConnectionTest() {
        val username = SessionManager.getInstance().getCurrentUsername() ?: "selimjhw"
        
        updateStatusLabel("연결 테스트 진행 중...", Color(52, 152, 219))
        testButton.isEnabled = false
        
        // 백그라운드 스레드에서 테스트 실행
        Thread {
            try {
                val result = MCPConnectionTest.testConnection(username)
                
                SwingUtilities.invokeLater {
                    testButton.isEnabled = true
                    
                    if (result.success) {
                        updateStatusLabel("연결 테스트 성공", Color(46, 204, 113))
                        showTestResultDialog(result)
                    } else {
                        updateStatusLabel("연결 테스트 실패", Color(231, 76, 60))
                        showTestResultDialog(result)
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    testButton.isEnabled = true
                    updateStatusLabel("연결 테스트 오류: ${e.message}", Color(231, 76, 60))
                    Logger.error("MCPManagementDialog", "연결 테스트 오류: ${e.message}")
                    e.printStackTrace()
                    
                    showErrorDialog("연결 테스트 중 오류가 발생했습니다:\n${e.message}")
                }
            }
        }.start()
    }
    
    /**
     * 테스트 결과 다이얼로그 표시
     */
    private fun showTestResultDialog(result: TestResult) {
        val message = buildString {
            append(result.message)
            if (result.details.isNotEmpty()) {
                append("\n\n")
                append(result.details.joinToString("\n"))
            }
        }
        
        val messageType = if (result.success) {
            JOptionPane.INFORMATION_MESSAGE
        } else {
            JOptionPane.ERROR_MESSAGE
        }
        
        JOptionPane.showMessageDialog(
            contentPanel,
            message,
            if (result.success) "연결 테스트 성공" else "연결 테스트 실패",
            messageType
        )
    }
    
    /**
     * 오류 다이얼로그 표시
     */
    private fun showErrorDialog(message: String) {
        JOptionPane.showMessageDialog(
            contentPanel,
            message,
            "오류",
            JOptionPane.ERROR_MESSAGE
        )
    }
}

/**
 * MCP 항목 패널 (각 MCP 서버를 표시하는 패널)
 */
class MCPItemPanel(
    private val mcpItem: MCPListItem,
    private var isConnected: Boolean,
    private val onToggle: (String, Boolean) -> Unit
) : JPanel() {
    
    private lateinit var toggleButton: JToggleButton
    private lateinit var statusLabel: JLabel
    
    init {
        layout = BorderLayout()
        border = EmptyBorder(10, 10, 10, 10)
        background = Color.WHITE
        preferredSize = Dimension(760, 80)
        
        // 왼쪽: 토글 버튼 및 MCP 정보
        val leftPanel = JPanel(BorderLayout())
        leftPanel.background = Color.WHITE
        
        val infoPanel = JPanel()
        infoPanel.layout = BoxLayout(infoPanel, BoxLayout.Y_AXIS)
        infoPanel.background = Color.WHITE
        
        val nameLabel = JLabel(mcpItem.name)
        nameLabel.font = Font("SansSerif", Font.BOLD, 13)
        infoPanel.add(nameLabel)
        
        if (!mcpItem.description.isNullOrBlank()) {
            val descLabel = JLabel(mcpItem.description)
            descLabel.font = Font("SansSerif", Font.PLAIN, 10)
            descLabel.foreground = Color(100, 100, 100)
            infoPanel.add(descLabel)
        }
        
        val endpointLabel = JLabel("엔드포인트: ${mcpItem.endpoint}")
        endpointLabel.font = Font("SansSerif", Font.PLAIN, 10)
        endpointLabel.foreground = Color(100, 100, 100)
        infoPanel.add(endpointLabel)
        
        leftPanel.add(infoPanel, BorderLayout.WEST)
        
        // 오른쪽: 토글 버튼 및 상태
        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        rightPanel.background = Color.WHITE
        
        toggleButton = JToggleButton(if (isConnected) "ON" else "OFF")
        toggleButton.font = Font("SansSerif", Font.BOLD, 11)
        toggleButton.preferredSize = Dimension(60, 25)
        toggleButton.isSelected = isConnected
        toggleButton.background = if (isConnected) Color(52, 152, 219) else Color(200, 200, 200)
        toggleButton.addActionListener {
            val enabled = toggleButton.isSelected
            onToggle(mcpItem.id, enabled)
        }
        rightPanel.add(toggleButton)
        
        statusLabel = JLabel(if (isConnected) "연결됨" else "연결 안 됨")
        statusLabel.font = Font("SansSerif", Font.PLAIN, 11)
        statusLabel.foreground = if (isConnected) Color(46, 204, 113) else Color(150, 150, 150)
        statusLabel.preferredSize = Dimension(80, 25)
        rightPanel.add(statusLabel)
        
        add(leftPanel, BorderLayout.CENTER)
        add(rightPanel, BorderLayout.EAST)
    }
    
    override fun setEnabled(enabled: Boolean) {
        toggleButton.isEnabled = enabled
        if (!enabled) {
            statusLabel.text = "비활성화됨"
            statusLabel.foreground = Color(200, 200, 200)
        }
    }
    
    fun setConnecting(connecting: Boolean) {
        toggleButton.isEnabled = !connecting
        if (connecting) {
            statusLabel.text = "연결 중..."
            statusLabel.foreground = Color(52, 152, 219)
        }
    }
    
    fun setConnected(connected: Boolean) {
        isConnected = connected
        toggleButton.isSelected = connected
        toggleButton.text = if (connected) "ON" else "OFF"
        toggleButton.background = if (connected) Color(52, 152, 219) else Color(200, 200, 200)
        statusLabel.text = if (connected) "연결됨" else "연결 안 됨"
        statusLabel.foreground = if (connected) Color(46, 204, 113) else Color(150, 150, 150)
    }
    
    fun isConnected(): Boolean {
        return isConnected
    }
}

