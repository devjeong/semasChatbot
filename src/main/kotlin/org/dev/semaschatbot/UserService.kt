package org.dev.semaschatbot

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 사용자 인증 및 사용량 관리를 담당하는 서비스입니다.
 * 서버 API를 통해 인증하며, 사용자 정보는 메모리에만 저장합니다.
 * 사용량 통계는 서버로 직접 전송됩니다.
 */
@Service(Service.Level.PROJECT)
class UserService(private val project: Project) {
    
    // 현재 로그인한 사용자 (메모리에만 저장)
    @Volatile
    private var currentUser: User? = null
    
    // AuthApiClient 인스턴스 (서버 API 통신용)
    private val authApiClient = AuthApiClient("http://192.168.18.53:5000")
    
    init {
        // ChatService의 서버 URL과 동기화 (지연 초기화)
        syncServerUrlFromChatService()
    }
    
    /**
     * ChatService의 서버 URL과 동기화합니다.
     * ChatService가 초기화되지 않은 경우 나중에 다시 시도합니다.
     */
    private fun syncServerUrlFromChatService() {
        try {
            val chatService = project.getService(ChatService::class.java)
            if (chatService != null) {
                val serverBaseUrl = chatService.getServerBaseUrl()
                authApiClient.setServerBaseUrl(serverBaseUrl)
                Logger.info("UserService", "서버 URL 동기화 완료: $serverBaseUrl")
            } else {
                // ChatService가 아직 초기화되지 않은 경우, 기본값 사용
                Logger.debug("UserService", "ChatService 초기화 대기 중, 기본 서버 URL 사용")
            }
        } catch (e: Exception) {
            // ChatService가 아직 초기화되지 않은 경우 기본값 사용
            Logger.debug("UserService", "ChatService 초기화 대기 중, 기본 서버 URL 사용: ${e.message}")
        }
    }
    
    /**
     * 서버 URL을 업데이트합니다.
     * ChatService의 서버 URL 변경 시 호출됩니다.
     * @param serverBaseUrl 새로운 서버 기본 URL (포트 제외)
     */
    fun updateServerUrl(serverBaseUrl: String) {
        authApiClient.setServerBaseUrl(serverBaseUrl)
        Logger.info("UserService", "서버 URL 업데이트: $serverBaseUrl")
    }
    
    /**
     * API 호출 전에 서버 URL을 동기화합니다.
     * ChatService의 URL이 변경되었을 수 있으므로 확인합니다.
     */
    private fun ensureServerUrlSynced() {
        try {
            val chatService = project.getService(ChatService::class.java)
            if (chatService != null) {
                val currentServerUrl = chatService.getServerBaseUrl()
                val authServerUrl = authApiClient.getServerBaseUrl()
                // 포트를 제거하여 비교
                val currentBase = currentServerUrl.replace(Regex(":\\d+$"), "")
                val authBase = authServerUrl.replace(Regex(":\\d+$"), "")
                if (currentBase != authBase) {
                    authApiClient.setServerBaseUrl(currentServerUrl)
                    Logger.info("UserService", "서버 URL 재동기화: $currentServerUrl")
                }
            }
        } catch (e: Exception) {
            // 동기화 실패 시 무시 (기본값 사용)
        }
    }
    
    
    /**
     * 회원가입을 처리합니다. (서버 API 연동)
     * @param username 사용자 아이디
     * @param password 비밀번호
     * @param name 사용자 이름
     * @param role 사용자 권한 (기본값: USER)
     * @return 회원가입 성공 여부 및 메시지
     */
    fun registerUser(username: String, password: String, name: String, role: UserRole = UserRole.USER): Pair<Boolean, String> {
        // 입력 유효성 검사
        if (username.isBlank() || password.isBlank() || name.isBlank()) {
            return Pair(false, "모든 필드를 입력해주세요.")
        }
        
        if (username.length < 3) {
            return Pair(false, "아이디는 최소 3자 이상이어야 합니다.")
        }
        
        if (password.length < 4) {
            return Pair(false, "비밀번호는 최소 4자 이상이어야 합니다.")
        }
        
        // 서버 URL 동기화 확인
        ensureServerUrlSynced()
        
        // 서버로 회원가입 요청 전송
        val (success, message, userInfo) = authApiClient.registerUser(username, password, name, role)
        
        if (!success) {
            return Pair(false, message)
        }
        
        // 서버 저장 성공 시 사용자 정보를 메모리에 저장
        val serverId = (userInfo?.get("id") as? Int) ?: 0
        if (serverId > 0) {
            val serverName = userInfo?.get("name") as? String ?: name
            val serverRole = try {
                UserRole.valueOf(userInfo?.get("role") as? String ?: role.name)
            } catch (e: Exception) {
                role
            }
            val serverCreatedAt = userInfo?.get("created_at") as? String 
                ?: LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            
            val user = User(
                id = serverId,
                username = username,
                passwordHash = User.hashPassword(password),
                name = serverName,
                role = serverRole,
                createdAt = serverCreatedAt,
                isActive = true
            )
            currentUser = user
        }
        
        return Pair(true, message)
    }
    
    /**
     * 로그인을 처리합니다. (서버 API 연동)
     * @param username 사용자 아이디
     * @param password 비밀번호
     * @return 로그인 성공 여부 및 메시지
     */
    fun login(username: String, password: String): Pair<Boolean, String> {
        // 입력 유효성 검사
        if (username.isBlank() || password.isBlank()) {
            return Pair(false, "아이디와 비밀번호를 입력해주세요.")
        }
        
        // 서버 URL 동기화 확인
        ensureServerUrlSynced()
        
        // 서버로 로그인 요청 전송
        val (success, message, userInfo) = authApiClient.login(username, password)
        
        if (!success) {
            return Pair(false, message)
        }
        
        // 서버 인증 성공 시 사용자 정보를 메모리에 저장
        val serverId = (userInfo?.get("id") as? Int) ?: 0
        if (serverId > 0) {
            val serverName = userInfo?.get("name") as? String ?: username
            val serverRole = try {
                UserRole.valueOf(userInfo?.get("role") as? String ?: "USER")
            } catch (e: Exception) {
                UserRole.USER
            }
            val serverCreatedAt = userInfo?.get("created_at") as? String 
                ?: LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            
            val user = User(
                id = serverId,
                username = username,
                passwordHash = User.hashPassword(password),
                name = serverName,
                role = serverRole,
                createdAt = serverCreatedAt,
                lastLogin = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                isActive = true
            )
            currentUser = user
            return Pair(true, "로그인 성공! 환영합니다, ${user.name}님!")
        } else {
            return Pair(false, "서버에서 사용자 ID를 받지 못했습니다.")
        }
    }
    
    /**
     * 로그아웃을 처리합니다.
     */
    fun logout() {
        currentUser = null
    }
    
    /**
     * 현재 로그인한 사용자를 반환합니다.
     */
    fun getCurrentUser(): User? = currentUser
    
    /**
     * 사용자가 로그인되어 있는지 확인합니다.
     */
    fun isLoggedIn(): Boolean = currentUser != null
    
    /**
     * 메시지 사용량을 기록합니다.
     * 사용량 통계는 서버로 직접 전송되므로 로컬 저장은 하지 않습니다.
     */
    fun recordMessage(messageLength: Int) {
        // 로컬 DB 제거로 인해 빈 구현 (서버로 통계 전송은 LmStudioStatsApiClient에서 처리)
    }
    
    /**
     * 토큰 사용량을 기록합니다.
     * 사용량 통계는 서버로 직접 전송되므로 로컬 저장은 하지 않습니다.
     */
    fun recordTokens(inputTokens: Int, outputTokens: Int) {
        // 로컬 DB 제거로 인해 빈 구현 (서버로 통계 전송은 LmStudioStatsApiClient에서 처리)
    }
    
    /**
     * API 호출을 기록합니다.
     * 사용량 통계는 서버로 직접 전송되므로 로컬 저장은 하지 않습니다.
     */
    fun recordApiCall(success: Boolean, responseTime: Long) {
        // 로컬 DB 제거로 인해 빈 구현 (서버로 통계 전송은 LmStudioStatsApiClient에서 처리)
    }
    
    /**
     * 코드 수정을 기록합니다.
     * 사용량 통계는 서버로 직접 전송되므로 로컬 저장은 하지 않습니다.
     */
    fun recordCodeModification(filesModified: Int, linesModified: Int) {
        // 로컬 DB 제거로 인해 빈 구현
    }
    
    /**
     * 인덱싱을 기록합니다.
     * 사용량 통계는 서버로 직접 전송되므로 로컬 저장은 하지 않습니다.
     */
    fun recordIndexing(filesIndexed: Int, chunksIndexed: Int, timeMs: Long) {
        // 로컬 DB 제거로 인해 빈 구현
    }
    
    /**
     * DB 연결을 기록합니다.
     * 사용량 통계는 서버로 직접 전송되므로 로컬 저장은 하지 않습니다.
     */
    fun recordDbConnection() {
        // 로컬 DB 제거로 인해 빈 구현
    }
    
    /**
     * 오늘의 사용량 통계를 조회합니다.
     * 로컬 DB가 제거되어 항상 null을 반환합니다.
     * 통계는 서버에서 조회해야 합니다.
     */
    fun getTodayStatistics(): UsageStatistics? {
        // 로컬 DB 제거로 인해 항상 null 반환
        return null
    }
}

