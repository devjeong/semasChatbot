package org.dev.semaschatbot

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 사용자 인증 및 사용량 관리를 담당하는 서비스입니다.
 * SQLite 데이터베이스를 사용하여 사용자 정보와 사용량 통계를 저장합니다.
 */
@Service(Service.Level.PROJECT)
class UserService(private val project: Project) {
    
    private val dbPath: String
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val dateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    
    // 현재 로그인한 사용자
    @Volatile
    private var currentUser: User? = null
    
    // AuthApiClient 인스턴스 (서버 API 통신용)
    private val authApiClient = AuthApiClient("http://192.168.18.53:5000")
    
    init {
        // 플러그인 데이터 디렉토리에 DB 파일 생성
        val pluginDataDir = File(project.basePath ?: System.getProperty("user.home"), ".semas-chatbot")
        if (!pluginDataDir.exists()) {
            pluginDataDir.mkdirs()
        }
        dbPath = File(pluginDataDir, "users.db").absolutePath
        initializeDatabase()
        
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
                println("[UserService] 서버 URL 동기화 완료: $serverBaseUrl")
            } else {
                // ChatService가 아직 초기화되지 않은 경우, 기본값 사용
                println("[UserService] ChatService 초기화 대기 중, 기본 서버 URL 사용")
            }
        } catch (e: Exception) {
            // ChatService가 아직 초기화되지 않은 경우 기본값 사용
            println("[UserService] ChatService 초기화 대기 중, 기본 서버 URL 사용: ${e.message}")
        }
    }
    
    /**
     * 서버 URL을 업데이트합니다.
     * ChatService의 서버 URL 변경 시 호출됩니다.
     * @param serverBaseUrl 새로운 서버 기본 URL (포트 제외)
     */
    fun updateServerUrl(serverBaseUrl: String) {
        authApiClient.setServerBaseUrl(serverBaseUrl)
        println("[UserService] 서버 URL 업데이트: $serverBaseUrl")
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
                    println("[UserService] 서버 URL 재동기화: $currentServerUrl")
                }
            }
        } catch (e: Exception) {
            // 동기화 실패 시 무시 (기본값 사용)
        }
    }
    
    /**
     * 데이터베이스를 초기화하고 필요한 테이블을 생성합니다.
     */
    private fun initializeDatabase() {
        getConnection().use { conn ->
            try {
                // Users 테이블 생성
                conn.createStatement().execute("""
                    CREATE TABLE IF NOT EXISTS users (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        username TEXT UNIQUE NOT NULL,
                        password_hash TEXT NOT NULL,
                        name TEXT NOT NULL,
                        role TEXT NOT NULL DEFAULT 'USER',
                        created_at TEXT NOT NULL,
                        last_login TEXT,
                        is_active INTEGER DEFAULT 1
                    )
                """.trimIndent())
                
                // UsageStatistics 테이블 생성
                conn.createStatement().execute("""
                    CREATE TABLE IF NOT EXISTS usage_statistics (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_id INTEGER NOT NULL,
                        date TEXT NOT NULL,
                        total_messages INTEGER DEFAULT 0,
                        daily_messages INTEGER DEFAULT 0,
                        avg_message_length REAL DEFAULT 0,
                        input_tokens INTEGER DEFAULT 0,
                        output_tokens INTEGER DEFAULT 0,
                        total_tokens INTEGER DEFAULT 0,
                        daily_tokens INTEGER DEFAULT 0,
                        session_count INTEGER DEFAULT 0,
                        total_session_time INTEGER DEFAULT 0,
                        last_activity TEXT,
                        api_calls INTEGER DEFAULT 0,
                        successful_calls INTEGER DEFAULT 0,
                        failed_calls INTEGER DEFAULT 0,
                        avg_response_time REAL DEFAULT 0,
                        code_modification_requests INTEGER DEFAULT 0,
                        modified_files INTEGER DEFAULT 0,
                        modified_lines INTEGER DEFAULT 0,
                        indexing_requests INTEGER DEFAULT 0,
                        indexed_files INTEGER DEFAULT 0,
                        indexed_chunks INTEGER DEFAULT 0,
                        indexing_time INTEGER DEFAULT 0,
                        db_connections INTEGER DEFAULT 0,
                        db_queries INTEGER DEFAULT 0,
                        FOREIGN KEY (user_id) REFERENCES users(id),
                        UNIQUE(user_id, date)
                    )
                """.trimIndent())
                
                // 인덱스 생성 (성능 최적화)
                try {
                    conn.createStatement().execute("CREATE INDEX IF NOT EXISTS idx_user_id ON usage_statistics(user_id)")
                    conn.createStatement().execute("CREATE INDEX IF NOT EXISTS idx_date ON usage_statistics(date)")
                    conn.createStatement().execute("CREATE INDEX IF NOT EXISTS idx_user_date ON usage_statistics(user_id, date)")
                } catch (e: Exception) {
                    // 인덱스가 이미 존재하는 경우 무시
                    println("인덱스 생성 중 오류 (무시 가능): ${e.message}")
                }
                
                // 트랜잭션 커밋
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }
    
    /**
     * 데이터베이스 연결을 가져옵니다.
     * SQLite 잠금 오류 방지를 위해 타임아웃 및 WAL 모드 설정을 포함합니다.
     * 
     * 성능 최적화:
     * - WAL(Write-Ahead Logging) 모드 활성화: 동시 읽기 성능 향상
     * - busy_timeout 설정: 잠금 대기 시간 5초로 설정
     * - journal_mode=WAL: 쓰기 성능 향상 및 잠금 경합 감소
     */
    private fun getConnection(): Connection {
        Class.forName("org.sqlite.JDBC")
        // SQLite 연결 URL에 성능 및 안정성 파라미터 추가
        val url = "jdbc:sqlite:$dbPath?journal_mode=WAL&busy_timeout=5000"
        val conn = DriverManager.getConnection(url)
        // 자동 커밋 비활성화로 트랜잭션 제어 개선
        conn.autoCommit = false
        return conn
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
        
        // 서버 전송 성공 시 로컬 DB에도 저장 (서버 ID 사용)
        return try {
            getConnection().use { conn ->
                try {
                    // 서버에서 받은 사용자 ID 사용
                    val serverId = (userInfo?.get("id") as? Int) ?: 0
                    
                    if (serverId <= 0) {
                        // 서버에서 ID를 받지 못한 경우, 로컬 AUTOINCREMENT 사용
                        val passwordHash = User.hashPassword(password)
                        val createdAt = LocalDateTime.now().format(dateTimeFormatter)
                        
                        val stmt = conn.prepareStatement("""
                            INSERT INTO users (username, password_hash, name, role, created_at, is_active)
                            VALUES (?, ?, ?, ?, ?, 1)
                        """.trimIndent())
                        
                        stmt.setString(1, username)
                        stmt.setString(2, passwordHash)
                        stmt.setString(3, name)
                        stmt.setString(4, role.name)
                        stmt.setString(5, createdAt)
                        
                        stmt.executeUpdate()
                        stmt.close()
                        conn.commit()
                        
                        return Pair(true, "$message (서버 ID 미수신, 로컬 ID 사용)")
                    }
                    
                    // 서버 ID를 명시적으로 사용하여 저장
                    val serverName = userInfo?.get("name") as? String ?: name
                    val serverRole = try {
                        UserRole.valueOf(userInfo?.get("role") as? String ?: role.name)
                    } catch (e: Exception) {
                        role
                    }
                    val serverCreatedAt = userInfo?.get("created_at") as? String 
                        ?: LocalDateTime.now().format(dateTimeFormatter)
                    
                    val passwordHash = User.hashPassword(password)
                    
                    val stmt = conn.prepareStatement("""
                        INSERT INTO users (id, username, password_hash, name, role, created_at, is_active)
                        VALUES (?, ?, ?, ?, ?, ?, 1)
                    """.trimIndent())
                    
                    stmt.setInt(1, serverId)
                    stmt.setString(2, username)
                    stmt.setString(3, passwordHash)
                    stmt.setString(4, serverName)
                    stmt.setString(5, serverRole.name)
                    stmt.setString(6, serverCreatedAt)
                    
                    stmt.executeUpdate()
                    stmt.close()
                    conn.commit()
                    
                    Pair(true, message)
                } catch (e: Exception) {
                    conn.rollback()
                    // 서버에는 저장되었지만 로컬 저장 실패
                    // 서버 저장이 우선이므로 성공으로 처리하되 경고 메시지
                    if (e.message?.contains("UNIQUE constraint") == true) {
                        Pair(true, "$message (로컬 저장: 이미 존재하는 아이디)")
                    } else {
                        Pair(true, "$message (로컬 저장 실패: ${e.message})")
                    }
                }
            }
        } catch (e: Exception) {
            // 로컬 DB 오류는 무시하고 서버 저장 성공 메시지 반환
            Pair(true, "$message (로컬 저장 실패: ${e.message})")
        }
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
        
        // 서버 인증 성공 시 로컬 DB에서 사용자 정보 조회 및 동기화
        return try {
            getConnection().use { conn ->
                try {
                    // 로컬 DB에서 사용자 정보 조회
                    val stmt = conn.prepareStatement("""
                        SELECT id, username, password_hash, name, role, created_at, last_login, is_active
                        FROM users
                        WHERE username = ? AND is_active = 1
                    """.trimIndent())
                    
                    stmt.setString(1, username)
                    val rs = stmt.executeQuery()
                    
                    if (rs.next()) {
                        // 로컬에 사용자 정보가 있는 경우
                        // 서버에서 받은 ID와 로컬 ID를 비교하여 동기화
                        val localId = rs.getInt("id")
                        val serverId = (userInfo?.get("id") as? Int) ?: 0
                        
                        // 서버 ID가 있고 로컬 ID와 다르면 서버 ID로 업데이트
                        val finalId = if (serverId > 0 && serverId != localId) {
                            // 서버 ID로 업데이트 (기존 레코드 삭제 후 재생성)
                            val deleteStmt = conn.prepareStatement("DELETE FROM users WHERE id = ?")
                            deleteStmt.setInt(1, localId)
                            deleteStmt.executeUpdate()
                            deleteStmt.close()
                            
                            // 서버 ID를 사용하여 재생성
                            val serverName = userInfo?.get("name") as? String ?: rs.getString("name")
                            val serverRole = try {
                                UserRole.valueOf(userInfo?.get("role") as? String ?: rs.getString("role"))
                            } catch (e: Exception) {
                                UserRole.valueOf(rs.getString("role"))
                            }
                            val serverCreatedAt = userInfo?.get("created_at") as? String ?: rs.getString("created_at")
                            
                            val insertStmt = conn.prepareStatement("""
                                INSERT INTO users (id, username, password_hash, name, role, created_at, last_login, is_active)
                                VALUES (?, ?, ?, ?, ?, ?, ?, 1)
                            """.trimIndent())
                            
                            insertStmt.setInt(1, serverId)
                            insertStmt.setString(2, rs.getString("username"))
                            insertStmt.setString(3, rs.getString("password_hash"))
                            insertStmt.setString(4, serverName)
                            insertStmt.setString(5, serverRole.name)
                            insertStmt.setString(6, serverCreatedAt)
                            insertStmt.setString(7, LocalDateTime.now().format(dateTimeFormatter))
                            insertStmt.executeUpdate()
                            insertStmt.close()
                            
                            serverId
                        } else {
                            localId
                        }
                        
                        // 업데이트된 사용자 정보 조회
                        val updatedStmt = conn.prepareStatement("""
                            SELECT id, username, password_hash, name, role, created_at, last_login, is_active
                            FROM users
                            WHERE id = ?
                        """.trimIndent())
                        updatedStmt.setInt(1, finalId)
                        val updatedRs = updatedStmt.executeQuery()
                        
                        if (updatedRs.next()) {
                            val user = User(
                                id = updatedRs.getInt("id"),
                                username = updatedRs.getString("username"),
                                passwordHash = updatedRs.getString("password_hash"),
                                name = updatedRs.getString("name"),
                                role = UserRole.valueOf(updatedRs.getString("role")),
                                createdAt = updatedRs.getString("created_at"),
                                lastLogin = updatedRs.getString("last_login"),
                                isActive = updatedRs.getInt("is_active") == 1
                            )
                            
                            // 마지막 로그인 시간 업데이트
                            val updateStmt = conn.prepareStatement("""
                                UPDATE users SET last_login = ? WHERE id = ?
                            """.trimIndent())
                            updateStmt.setString(1, LocalDateTime.now().format(dateTimeFormatter))
                            updateStmt.setInt(2, user.id)
                            updateStmt.executeUpdate()
                            updateStmt.close()
                            
                            // 통계 초기화
                            initializeTodayStatistics(user.id, conn)
                            conn.commit()
                            
                            currentUser = user
                            Pair(true, "로그인 성공! 환영합니다, ${user.name}님!")
                        } else {
                            conn.rollback()
                            Pair(false, "사용자 정보 동기화 중 오류가 발생했습니다.")
                        }
                    } else {
                        // 로컬에 사용자 정보가 없는 경우 (서버에는 있지만 로컬 동기화 안 됨)
                        // 서버에서 받은 사용자 정보를 사용하여 로컬 DB에 생성
                        val serverId = (userInfo?.get("id") as? Int) ?: 0
                        
                        if (serverId <= 0) {
                            conn.rollback()
                            return Pair(false, "서버에서 사용자 ID를 받지 못했습니다.")
                        }
                        
                        val serverName = userInfo?.get("name") as? String ?: username
                        val serverRole = try {
                            UserRole.valueOf(userInfo?.get("role") as? String ?: "USER")
                        } catch (e: Exception) {
                            UserRole.USER
                        }
                        val serverCreatedAt = userInfo?.get("created_at") as? String 
                            ?: LocalDateTime.now().format(dateTimeFormatter)
                        
                        val passwordHash = User.hashPassword(password)
                        val createdAt = serverCreatedAt
                        val lastLogin = LocalDateTime.now().format(dateTimeFormatter)
                        
                        // 서버 ID를 명시적으로 사용하여 INSERT
                        val insertStmt = conn.prepareStatement("""
                            INSERT INTO users (id, username, password_hash, name, role, created_at, last_login, is_active)
                            VALUES (?, ?, ?, ?, ?, ?, ?, 1)
                        """.trimIndent())
                        
                        insertStmt.setInt(1, serverId)
                        insertStmt.setString(2, username)
                        insertStmt.setString(3, passwordHash)
                        insertStmt.setString(4, serverName)
                        insertStmt.setString(5, serverRole.name)
                        insertStmt.setString(6, createdAt)
                        insertStmt.setString(7, lastLogin)
                        
                        insertStmt.executeUpdate()
                        insertStmt.close()
                        
                        // 생성된 사용자 정보 조회
                        val newUserStmt = conn.prepareStatement("""
                            SELECT id, username, password_hash, name, role, created_at, last_login, is_active
                            FROM users
                            WHERE id = ?
                        """.trimIndent())
                        newUserStmt.setInt(1, serverId)
                        val newRs = newUserStmt.executeQuery()
                        
                        if (newRs.next()) {
                            val user = User(
                                id = newRs.getInt("id"),
                                username = newRs.getString("username"),
                                passwordHash = newRs.getString("password_hash"),
                                name = newRs.getString("name"),
                                role = UserRole.valueOf(newRs.getString("role")),
                                createdAt = newRs.getString("created_at"),
                                lastLogin = newRs.getString("last_login"),
                                isActive = newRs.getInt("is_active") == 1
                            )
                            
                            initializeTodayStatistics(user.id, conn)
                            conn.commit()
                            
                            currentUser = user
                            Pair(true, "로그인 성공! 환영합니다, ${user.name}님!")
                        } else {
                            conn.rollback()
                            Pair(false, "사용자 정보 동기화 중 오류가 발생했습니다.")
                        }
                    }
                } catch (e: Exception) {
                    conn.rollback()
                    throw e
                }
            }
        } catch (e: Exception) {
            // 로컬 DB 오류 발생 시에도 서버 인증은 성공했으므로
            // 서버에서 받은 ID를 사용하여 임시 사용자 객체 생성
            val serverId = (userInfo?.get("id") as? Int) ?: 0
            val tempUser = User(
                id = serverId,  // 서버 ID 사용
                username = username,
                passwordHash = User.hashPassword(password),
                name = userInfo?.get("name") as? String ?: username,
                role = try {
                    UserRole.valueOf(userInfo?.get("role") as? String ?: "USER")
                } catch (e: Exception) {
                    UserRole.USER
                },
                createdAt = userInfo?.get("created_at") as? String ?: LocalDateTime.now().format(dateTimeFormatter),
                isActive = true
            )
            currentUser = tempUser
            Pair(true, "$message (로컬 동기화 실패: ${e.message})")
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
     * 오늘 날짜의 사용량 통계를 초기화합니다.
     * 
     * @param userId 사용자 ID
     * @param conn 기존 데이터베이스 연결 (선택적, 제공되면 중첩 연결 방지)
     */
    private fun initializeTodayStatistics(userId: Int, conn: Connection? = null) {
        val today = LocalDate.now().format(dateFormatter)
        
        // 기존 연결이 제공된 경우 해당 연결 사용, 없으면 새 연결 생성
        val connectionToUse = conn ?: getConnection()
        val shouldClose = conn == null
        
        try {
            val stmt = connectionToUse.prepareStatement("""
                INSERT OR IGNORE INTO usage_statistics (user_id, date, last_activity)
                VALUES (?, ?, ?)
            """.trimIndent())
            
            stmt.setInt(1, userId)
            stmt.setString(2, today)
            stmt.setString(3, LocalDateTime.now().format(dateTimeFormatter))
            stmt.executeUpdate()
            stmt.close()
            
            // 새 연결을 생성한 경우에만 커밋 및 닫기
            if (shouldClose) {
                connectionToUse.commit()
            }
        } finally {
            if (shouldClose) {
                connectionToUse.close()
            }
        }
    }
    
    /**
     * 메시지 사용량을 기록합니다.
     */
    fun recordMessage(messageLength: Int) {
        val user = currentUser ?: return
        val today = LocalDate.now().format(dateFormatter)
        
        getConnection().use { conn ->
            // 오늘의 통계 업데이트
            val stmt = conn.prepareStatement("""
                UPDATE usage_statistics
                SET daily_messages = daily_messages + 1,
                    total_messages = total_messages + 1,
                    avg_message_length = (avg_message_length * (daily_messages - 1) + ?) / daily_messages,
                    last_activity = ?
                WHERE user_id = ? AND date = ?
            """.trimIndent())
            
            stmt.setInt(1, messageLength)
            stmt.setString(2, LocalDateTime.now().format(dateTimeFormatter))
            stmt.setInt(3, user.id)
            stmt.setString(4, today)
            
            if (stmt.executeUpdate() == 0) {
                // 오늘의 통계가 없으면 생성
                initializeTodayStatistics(user.id)
                recordMessage(messageLength) // 재귀 호출
            }
            stmt.close()
        }
    }
    
    /**
     * 토큰 사용량을 기록합니다.
     */
    fun recordTokens(inputTokens: Int, outputTokens: Int) {
        val user = currentUser ?: return
        val today = LocalDate.now().format(dateFormatter)
        
        getConnection().use { conn ->
            val stmt = conn.prepareStatement("""
                UPDATE usage_statistics
                SET input_tokens = input_tokens + ?,
                    output_tokens = output_tokens + ?,
                    total_tokens = total_tokens + ?,
                    daily_tokens = daily_tokens + ?,
                    last_activity = ?
                WHERE user_id = ? AND date = ?
            """.trimIndent())
            
            val totalTokens = inputTokens + outputTokens
            stmt.setInt(1, inputTokens)
            stmt.setInt(2, outputTokens)
            stmt.setInt(3, totalTokens)
            stmt.setInt(4, totalTokens)
            stmt.setString(5, LocalDateTime.now().format(dateTimeFormatter))
            stmt.setInt(6, user.id)
            stmt.setString(7, today)
            
            if (stmt.executeUpdate() == 0) {
                initializeTodayStatistics(user.id)
                recordTokens(inputTokens, outputTokens)
            }
            stmt.close()
        }
    }
    
    /**
     * API 호출을 기록합니다.
     */
    fun recordApiCall(success: Boolean, responseTime: Long) {
        val user = currentUser ?: return
        val today = LocalDate.now().format(dateFormatter)
        
        getConnection().use { conn ->
            val stmt = conn.prepareStatement("""
                UPDATE usage_statistics
                SET api_calls = api_calls + 1,
                    ${if (success) "successful_calls = successful_calls + 1" else "failed_calls = failed_calls + 1"},
                    avg_response_time = (avg_response_time * (api_calls) + ?) / (api_calls + 1),
                    last_activity = ?
                WHERE user_id = ? AND date = ?
            """.trimIndent())
            
            stmt.setDouble(1, responseTime.toDouble())
            stmt.setString(2, LocalDateTime.now().format(dateTimeFormatter))
            stmt.setInt(3, user.id)
            stmt.setString(4, today)
            
            if (stmt.executeUpdate() == 0) {
                initializeTodayStatistics(user.id)
                recordApiCall(success, responseTime)
            }
            stmt.close()
        }
    }
    
    /**
     * 코드 수정을 기록합니다.
     */
    fun recordCodeModification(filesModified: Int, linesModified: Int) {
        val user = currentUser ?: return
        val today = LocalDate.now().format(dateFormatter)
        
        getConnection().use { conn ->
            val stmt = conn.prepareStatement("""
                UPDATE usage_statistics
                SET code_modification_requests = code_modification_requests + 1,
                    modified_files = modified_files + ?,
                    modified_lines = modified_lines + ?,
                    last_activity = ?
                WHERE user_id = ? AND date = ?
            """.trimIndent())
            
            stmt.setInt(1, filesModified)
            stmt.setInt(2, linesModified)
            stmt.setString(3, LocalDateTime.now().format(dateTimeFormatter))
            stmt.setInt(4, user.id)
            stmt.setString(5, today)
            
            if (stmt.executeUpdate() == 0) {
                initializeTodayStatistics(user.id)
                recordCodeModification(filesModified, linesModified)
            }
            stmt.close()
        }
    }
    
    /**
     * 인덱싱을 기록합니다.
     */
    fun recordIndexing(filesIndexed: Int, chunksIndexed: Int, timeMs: Long) {
        val user = currentUser ?: return
        val today = LocalDate.now().format(dateFormatter)
        
        getConnection().use { conn ->
            val stmt = conn.prepareStatement("""
                UPDATE usage_statistics
                SET indexing_requests = indexing_requests + 1,
                    indexed_files = indexed_files + ?,
                    indexed_chunks = indexed_chunks + ?,
                    indexing_time = indexing_time + ?,
                    last_activity = ?
                WHERE user_id = ? AND date = ?
            """.trimIndent())
            
            stmt.setInt(1, filesIndexed)
            stmt.setInt(2, chunksIndexed)
            stmt.setLong(3, timeMs)
            stmt.setString(4, LocalDateTime.now().format(dateTimeFormatter))
            stmt.setInt(5, user.id)
            stmt.setString(6, today)
            
            if (stmt.executeUpdate() == 0) {
                initializeTodayStatistics(user.id)
                recordIndexing(filesIndexed, chunksIndexed, timeMs)
            }
            stmt.close()
        }
    }
    
    /**
     * DB 연결을 기록합니다.
     */
    fun recordDbConnection() {
        val user = currentUser ?: return
        val today = LocalDate.now().format(dateFormatter)
        
        getConnection().use { conn ->
            val stmt = conn.prepareStatement("""
                UPDATE usage_statistics
                SET db_connections = db_connections + 1,
                    db_queries = db_queries + 1,
                    last_activity = ?
                WHERE user_id = ? AND date = ?
            """.trimIndent())
            
            stmt.setString(1, LocalDateTime.now().format(dateTimeFormatter))
            stmt.setInt(2, user.id)
            stmt.setString(3, today)
            
            if (stmt.executeUpdate() == 0) {
                initializeTodayStatistics(user.id)
                recordDbConnection()
            }
            stmt.close()
        }
    }
    
    /**
     * 오늘의 사용량 통계를 조회합니다.
     */
    fun getTodayStatistics(): UsageStatistics? {
        val user = currentUser ?: return null
        val today = LocalDate.now().format(dateFormatter)
        
        return getConnection().use { conn ->
            val stmt = conn.prepareStatement("""
                SELECT * FROM usage_statistics
                WHERE user_id = ? AND date = ?
            """.trimIndent())
            
            stmt.setInt(1, user.id)
            stmt.setString(2, today)
            
            val rs = stmt.executeQuery()
            if (rs.next()) {
                mapResultSetToUsageStatistics(rs)
            } else {
                null
            }
        }
    }
    
    /**
     * ResultSet을 UsageStatistics 객체로 변환합니다.
     */
    private fun mapResultSetToUsageStatistics(rs: ResultSet): UsageStatistics {
        return UsageStatistics(
            id = rs.getInt("id"),
            userId = rs.getInt("user_id"),
            date = rs.getString("date"),
            totalMessages = rs.getInt("total_messages"),
            dailyMessages = rs.getInt("daily_messages"),
            avgMessageLength = rs.getDouble("avg_message_length"),
            inputTokens = rs.getInt("input_tokens"),
            outputTokens = rs.getInt("output_tokens"),
            totalTokens = rs.getInt("total_tokens"),
            dailyTokens = rs.getInt("daily_tokens"),
            sessionCount = rs.getInt("session_count"),
            totalSessionTime = rs.getInt("total_session_time"),
            lastActivity = rs.getString("last_activity"),
            apiCalls = rs.getInt("api_calls"),
            successfulCalls = rs.getInt("successful_calls"),
            failedCalls = rs.getInt("failed_calls"),
            avgResponseTime = rs.getDouble("avg_response_time"),
            codeModificationRequests = rs.getInt("code_modification_requests"),
            modifiedFiles = rs.getInt("modified_files"),
            modifiedLines = rs.getInt("modified_lines"),
            indexingRequests = rs.getInt("indexing_requests"),
            indexedFiles = rs.getInt("indexed_files"),
            indexedChunks = rs.getInt("indexed_chunks"),
            indexingTime = rs.getInt("indexing_time"),
            dbConnections = rs.getInt("db_connections"),
            dbQueries = rs.getInt("db_queries")
        )
    }
}

