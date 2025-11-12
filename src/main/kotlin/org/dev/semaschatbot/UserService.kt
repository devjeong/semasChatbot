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
    
    init {
        // 플러그인 데이터 디렉토리에 DB 파일 생성
        val pluginDataDir = File(project.basePath ?: System.getProperty("user.home"), ".semas-chatbot")
        if (!pluginDataDir.exists()) {
            pluginDataDir.mkdirs()
        }
        dbPath = File(pluginDataDir, "users.db").absolutePath
        initializeDatabase()
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
     * 회원가입을 처리합니다.
     * @param username 사용자 아이디
     * @param password 비밀번호
     * @param name 사용자 이름
     * @param role 사용자 권한 (기본값: USER)
     * @return 회원가입 성공 여부 및 메시지
     */
    fun registerUser(username: String, password: String, name: String, role: UserRole = UserRole.USER): Pair<Boolean, String> {
        if (username.isBlank() || password.isBlank() || name.isBlank()) {
            return Pair(false, "모든 필드를 입력해주세요.")
        }
        
        if (username.length < 3) {
            return Pair(false, "아이디는 최소 3자 이상이어야 합니다.")
        }
        
        if (password.length < 4) {
            return Pair(false, "비밀번호는 최소 4자 이상이어야 합니다.")
        }
        
        return try {
            getConnection().use { conn ->
                try {
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
                    
                    // 트랜잭션 커밋
                    conn.commit()
                    
                    Pair(true, "회원가입이 완료되었습니다!")
                } catch (e: Exception) {
                    conn.rollback()
                    throw e
                }
            }
        } catch (e: Exception) {
            if (e.message?.contains("UNIQUE constraint") == true) {
                Pair(false, "이미 존재하는 아이디입니다.")
            } else {
                Pair(false, "회원가입 중 오류가 발생했습니다: ${e.message}")
            }
        }
    }
    
    /**
     * 로그인을 처리합니다.
     * @param username 사용자 아이디
     * @param password 비밀번호
     * @return 로그인 성공 여부 및 메시지
     */
    fun login(username: String, password: String): Pair<Boolean, String> {
        if (username.isBlank() || password.isBlank()) {
            return Pair(false, "아이디와 비밀번호를 입력해주세요.")
        }
        
        return try {
            getConnection().use { conn ->
                try {
                    val passwordHash = User.hashPassword(password)
                    val stmt = conn.prepareStatement("""
                        SELECT id, username, password_hash, name, role, created_at, last_login, is_active
                        FROM users
                        WHERE username = ? AND is_active = 1
                    """.trimIndent())
                    
                    stmt.setString(1, username)
                    val rs = stmt.executeQuery()
                    
                    if (rs.next()) {
                        val storedHash = rs.getString("password_hash")
                        if (storedHash == passwordHash) {
                            // 로그인 성공
                            val user = User(
                                id = rs.getInt("id"),
                                username = rs.getString("username"),
                                passwordHash = storedHash,
                                name = rs.getString("name"),
                                role = UserRole.valueOf(rs.getString("role")),
                                createdAt = rs.getString("created_at"),
                                lastLogin = rs.getString("last_login"),
                                isActive = rs.getInt("is_active") == 1
                            )
                            
                            // 마지막 로그인 시간 업데이트
                            val updateStmt = conn.prepareStatement("""
                                UPDATE users SET last_login = ? WHERE id = ?
                            """.trimIndent())
                            updateStmt.setString(1, LocalDateTime.now().format(dateTimeFormatter))
                            updateStmt.setInt(2, user.id)
                            updateStmt.executeUpdate()
                            updateStmt.close()
                            
                            // 같은 연결에서 통계 초기화 (중첩 연결 방지)
                            initializeTodayStatistics(user.id, conn)
                            
                            // 트랜잭션 커밋
                            conn.commit()
                            
                            currentUser = user
                            
                            Pair(true, "로그인 성공! 환영합니다, ${user.name}님!")
                        } else {
                            conn.rollback()
                            Pair(false, "비밀번호가 일치하지 않습니다.")
                        }
                    } else {
                        conn.rollback()
                        Pair(false, "존재하지 않는 아이디이거나 비활성화된 계정입니다.")
                    }
                } catch (e: Exception) {
                    conn.rollback()
                    throw e
                }
            }
        } catch (e: Exception) {
            Pair(false, "로그인 중 오류가 발생했습니다: ${e.message}")
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

