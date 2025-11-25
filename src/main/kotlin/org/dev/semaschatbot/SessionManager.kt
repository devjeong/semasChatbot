package org.dev.semaschatbot

/**
 * 세션 관리자
 * 
 * 로그인한 사용자의 세션 정보를 관리합니다.
 * 싱글톤 패턴으로 구현하여 프로젝트 전체에서 공유됩니다.
 * 
 * 성능 최적화:
 * - @Volatile을 통한 스레드 안전성 확보
 * - 싱글톤 패턴으로 메모리 효율성 향상
 * - 빠른 세션 조회를 위한 단순 구조
 */
class SessionManager private constructor() {
    
    // 현재 활성 세션 (스레드 안전성을 위해 @Volatile 사용)
    @Volatile
    private var currentSession: Session? = null
    
    // 작업 관리 MCP 연결 상태 (스레드 안전성을 위해 @Volatile 사용)
    @Volatile
    private var taskMCPConnected: Boolean = false
    
    /**
     * 세션을 생성하고 저장합니다.
     * 로그인 성공 시 호출됩니다.
     * 
     * @param user 로그인한 사용자 정보
     * @return 생성된 세션
     */
    fun createSession(user: User): Session {
        val session = Session(user = user)
        currentSession = session
        Logger.info("SessionManager", "세션 생성: username=${user.username}, id=${user.id}")
        return session
    }
    
    /**
     * 현재 활성 세션을 반환합니다.
     * 
     * @return 현재 세션, 로그인하지 않은 경우 null
     */
    fun getCurrentSession(): Session? {
        return currentSession
    }
    
    /**
     * 현재 로그인한 사용자 정보를 반환합니다.
     * 
     * @return 현재 사용자, 로그인하지 않은 경우 null
     */
    fun getCurrentUser(): User? {
        return currentSession?.user
    }
    
    /**
     * 현재 로그인한 사용자명을 반환합니다.
     * 
     * @return 사용자명, 로그인하지 않은 경우 null
     */
    fun getCurrentUsername(): String? {
        return currentSession?.getUsername()
    }
    
    /**
     * 세션을 삭제합니다.
     * 로그아웃 시 호출됩니다.
     */
    fun clearSession() {
        val username = currentSession?.getUsername()
        currentSession = null
        Logger.info("SessionManager", "세션 삭제: username=$username")
    }
    
    /**
     * 로그인 상태를 확인합니다.
     * 
     * @return 로그인되어 있으면 true, 아니면 false
     */
    fun isLoggedIn(): Boolean {
        return currentSession != null
    }
    
    /**
     * 세션을 갱신합니다.
     * 마지막 접근 시간을 업데이트합니다.
     */
    fun refreshSession() {
        currentSession = currentSession?.refresh()
    }
    
    /**
     * 작업 관리 MCP 연결 상태를 설정합니다.
     * 
     * @param connected 연결 상태
     */
    fun setTaskMCPConnected(connected: Boolean) {
        val oldValue = taskMCPConnected
        taskMCPConnected = connected
        Logger.info("SessionManager", "작업 관리 MCP 연결 상태 변경: $oldValue -> $connected")
        
        // 상태 변경 확인
        if (taskMCPConnected != connected) {
            Logger.error("SessionManager", "경고: 작업 관리 MCP 연결 상태 설정 실패! 예상: $connected, 실제: $taskMCPConnected")
        }
    }
    
    /**
     * 작업 관리 MCP 연결 상태를 확인합니다.
     * 
     * @return 연결되어 있으면 true, 아니면 false
     */
    fun isTaskMCPConnected(): Boolean {
        return taskMCPConnected
    }
    
    /**
     * 작업 관리 기능 사용 가능 여부를 확인합니다.
     * MCP 기능이 활성화되어 있고, 작업 관리 MCP가 연결되어 있어야 합니다.
     * 
     * @param project 프로젝트 인스턴스 (MCPSettings 접근용)
     * @return 사용 가능하면 true, 아니면 false
     */
    fun isTaskManagementAvailable(project: com.intellij.openapi.project.Project): Boolean {
        try {
            val mcpSettings = MCPSettings(project)
            
            // 설정이 로드되지 않았을 수 있으므로 다시 로드 시도
            mcpSettings.loadSettings()
            
            // 1. MCP 기능이 활성화되어 있는지 확인
            val isMCPEnabled = mcpSettings.isMCPEnabled()
            Logger.debug("SessionManager", "MCP 기능 활성화 상태: $isMCPEnabled")
            
            if (!isMCPEnabled) {
                Logger.debug("SessionManager", "작업 관리 사용 불가: MCP 기능 비활성화")
                // 세션 상태도 초기화
                if (taskMCPConnected) {
                    taskMCPConnected = false
                }
                return false
            }
            
            // 2. 실제 MCP 연결 상태 확인 (MCPSettings에서 직접 확인)
            val allConnections = mcpSettings.getAllMCPConnections()
            val actualTaskMCPConnected = allConnections.values.any { connection ->
                connection.isConnected && (
                    connection.mcpName.contains("task", ignoreCase = true) ||
                    connection.mcpName.contains("작업", ignoreCase = true) ||
                    connection.mcpId.contains("task", ignoreCase = true) ||
                    connection.mcpId.contains("작업", ignoreCase = true)
                )
            }
            
            Logger.debug("SessionManager", "실제 MCP 연결 상태: actualTaskMCPConnected=$actualTaskMCPConnected, 세션 상태: taskMCPConnected=$taskMCPConnected")
            
            // 세션 상태와 실제 상태가 다르면 동기화
            if (actualTaskMCPConnected != taskMCPConnected) {
                Logger.info("SessionManager", "세션 상태와 실제 연결 상태 불일치 감지. 세션 상태 동기화: $taskMCPConnected -> $actualTaskMCPConnected")
                taskMCPConnected = actualTaskMCPConnected
            }
            
            return actualTaskMCPConnected
        } catch (e: Exception) {
            Logger.error("SessionManager", "작업 관리 기능 사용 가능 여부 확인 오류: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
    
    companion object {
        @Volatile
        private var instance: SessionManager? = null
        
        /**
         * SessionManager 인스턴스를 가져옵니다.
         * 스레드 안전한 싱글톤 패턴을 사용합니다.
         * 
         * @return SessionManager 인스턴스
         */
        fun getInstance(): SessionManager {
            return instance ?: synchronized(this) {
                instance ?: SessionManager().also { instance = it }
            }
        }
    }
}

