# 클라이언트(로컬PC) 회원가입/로그인 API 연동 가이드

## 📋 요구사항 요약

### 목표
- 회원가입 시 회원정보를 서버(192.168.18.53:5000)로 전송
- 로그인 시 서버의 회원정보와 비교하여 인증 처리

### 서버 정보
- **서버 URL**: `http://192.168.18.53:5000`
- **회원가입 엔드포인트**: `POST /api/auth/register`
- **로그인 엔드포인트**: `POST /api/auth/login`

---

## 🎯 작업 목록

1. **HTTP 클라이언트 유틸리티 클래스 생성**
   - OkHttp3를 활용한 API 호출 래퍼 클래스
   - 에러 처리 및 타임아웃 설정
   - JSON 직렬화/역직렬화 처리

2. **회원가입 API 연동**
   - `UserService.registerUser()` 메서드 수정
   - 서버로 회원정보 전송 로직 추가
   - 응답 처리 및 에러 핸들링

3. **로그인 API 연동**
   - `UserService.login()` 메서드 수정
   - 서버 인증 요청 로직 추가
   - 응답 기반 로그인 처리

4. **테스트 및 검증**
   - 회원가입 API 호출 테스트
   - 로그인 API 호출 테스트
   - 네트워크 오류 처리 테스트

---

## 📝 구현 가이드

### 1. HTTP 클라이언트 유틸리티 클래스 생성

**파일 위치**: `src/main/kotlin/org/dev/semaschatbot/AuthApiClient.kt`

```kotlin
package org.dev.semaschatbot

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.IOException
import com.google.gson.*
import java.util.concurrent.TimeUnit

/**
 * 인증 API 클라이언트
 * 서버(192.168.18.53:5000)와 통신하여 회원가입 및 로그인을 처리합니다.
 * 
 * 성능 최적화:
 * - 연결 풀링을 통한 재사용 연결 관리
 * - 타임아웃 설정으로 무한 대기 방지
 * - JSON 직렬화 최적화
 */
class AuthApiClient(
    private val serverBaseUrl: String = "http://192.168.18.53:5000"
) {
    // HTTP 클라이언트 (연결 풀링 및 타임아웃 설정)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)      // 연결 타임아웃: 10초
        .readTimeout(30, TimeUnit.SECONDS)          // 읽기 타임아웃: 30초
        .writeTimeout(10, TimeUnit.SECONDS)         // 쓰기 타임아웃: 10초
        .callTimeout(60, TimeUnit.SECONDS)         // 전체 호출 타임아웃: 60초
        .build()
    
    private val gson = Gson()
    
    /**
     * 회원가입 요청을 서버로 전송합니다.
     * 
     * @param username 사용자 아이디
     * @param password 비밀번호 (평문, 서버에서 해시 처리)
     * @param name 사용자 이름
     * @param role 사용자 권한 (기본값: USER)
     * @return Pair<성공 여부, 메시지>
     */
    fun registerUser(
        username: String,
        password: String,
        name: String,
        role: UserRole = UserRole.USER
    ): Pair<Boolean, String> {
        // 요청 본문 생성
        val requestBodyMap = mapOf(
            "username" to username,
            "password" to password,  // 평문 전송 (서버에서 해시 처리)
            "name" to name,
            "role" to role.name
        )
        val requestBodyJson = gson.toJson(requestBodyMap)
        
        // API 엔드포인트 URL
        val endpointUrl = "$serverBaseUrl/api/auth/register"
        
        // HTTP 요청 생성
        val request = Request.Builder()
            .url(endpointUrl)
            .post(RequestBody.create("application/json".toMediaTypeOrNull(), requestBodyJson))
            .addHeader("Content-Type", "application/json")
            .build()
        
        return try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                
                if (!response.isSuccessful) {
                    // 에러 응답 파싱 시도
                    val errorMessage = try {
                        val errorJson = gson.fromJson(responseBody, JsonObject::class.java)
                        errorJson.get("message")?.asString ?: "서버 오류가 발생했습니다."
                    } catch (e: Exception) {
                        "서버 오류가 발생했습니다. (HTTP ${response.code})"
                    }
                    return Pair(false, errorMessage)
                }
                
                // 성공 응답 파싱
                val responseJson = gson.fromJson(responseBody, JsonObject::class.java)
                val message = responseJson.get("message")?.asString ?: "회원가입이 완료되었습니다!"
                
                Pair(true, message)
            }
        } catch (e: IOException) {
            // 네트워크 오류 처리
            Pair(false, "네트워크 오류가 발생했습니다: ${e.message}")
        } catch (e: Exception) {
            // 기타 오류 처리
            Pair(false, "회원가입 요청 중 오류가 발생했습니다: ${e.message}")
        }
    }
    
    /**
     * 로그인 요청을 서버로 전송합니다.
     * 
     * @param username 사용자 아이디
     * @param password 비밀번호 (평문)
     * @return Pair<성공 여부, 메시지 또는 사용자 정보>
     */
    fun login(username: String, password: String): Pair<Boolean, String> {
        // 요청 본문 생성
        val requestBodyMap = mapOf(
            "username" to username,
            "password" to password  // 평문 전송 (서버에서 검증)
        )
        val requestBodyJson = gson.toJson(requestBodyMap)
        
        // API 엔드포인트 URL
        val endpointUrl = "$serverBaseUrl/api/auth/login"
        
        // HTTP 요청 생성
        val request = Request.Builder()
            .url(endpointUrl)
            .post(RequestBody.create("application/json".toMediaTypeOrNull(), requestBodyJson))
            .addHeader("Content-Type", "application/json")
            .build()
        
        return try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                
                if (!response.isSuccessful) {
                    // 에러 응답 파싱 시도
                    val errorMessage = try {
                        val errorJson = gson.fromJson(responseBody, JsonObject::class.java)
                        errorJson.get("message")?.asString ?: "로그인에 실패했습니다."
                    } catch (e: Exception) {
                        "로그인에 실패했습니다. (HTTP ${response.code})"
                    }
                    return Pair(false, errorMessage)
                }
                
                // 성공 응답 파싱
                val responseJson = gson.fromJson(responseBody, JsonObject::class.java)
                val success = responseJson.get("success")?.asBoolean ?: false
                val message = responseJson.get("message")?.asString ?: "로그인 성공!"
                
                if (success) {
                    Pair(true, message)
                } else {
                    Pair(false, message)
                }
            }
        } catch (e: IOException) {
            // 네트워크 오류 처리
            Pair(false, "네트워크 오류가 발생했습니다: ${e.message}")
        } catch (e: Exception) {
            // 기타 오류 처리
            Pair(false, "로그인 요청 중 오류가 발생했습니다: ${e.message}")
        }
    }
    
    /**
     * 서버 연결 테스트
     * 
     * @return 서버 연결 가능 여부
     */
    fun testConnection(): Boolean {
        return try {
            val request = Request.Builder()
                .url("$serverBaseUrl/api/health")
                .get()
                .build()
            
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }
}
```

### 2. UserService 수정 - 회원가입 API 연동

**파일 위치**: `src/main/kotlin/org/dev/semaschatbot/UserService.kt`

**수정 사항**:
- `AuthApiClient` 인스턴스 추가
- `registerUser()` 메서드에서 서버 API 호출 추가
- 서버 전송 성공 후 로컬 DB에도 저장 (옵션)

```kotlin
// UserService 클래스 내부에 추가

// AuthApiClient 인스턴스 (서버 API 통신용)
private val authApiClient = AuthApiClient("http://192.168.18.53:5000")

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
    
    // 서버로 회원가입 요청 전송
    val (success, message) = authApiClient.registerUser(username, password, name, role)
    
    if (!success) {
        return Pair(false, message)
    }
    
    // 서버 전송 성공 시 로컬 DB에도 저장 (옵션: 오프라인 지원을 위해)
    // 주의: 서버와 로컬 DB의 비밀번호 해시 방식이 다를 수 있으므로,
    // 로컬 DB 저장 시 서버에서 받은 해시값을 사용하거나 별도 처리 필요
    return try {
        getConnection().use { conn ->
            try {
                // 서버에서 성공했으므로 로컬에도 저장
                val passwordHash = User.hashPassword(password)  // 로컬 해시
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
```

### 3. UserService 수정 - 로그인 API 연동

**파일 위치**: `src/main/kotlin/org/dev/semaschatbot/UserService.kt`

**수정 사항**:
- `login()` 메서드에서 서버 API 호출 추가
- 서버 인증 성공 시 사용자 정보 로컬에 동기화

```kotlin
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
    
    // 서버로 로그인 요청 전송
    val (success, message) = authApiClient.login(username, password)
    
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
                    val user = User(
                        id = rs.getInt("id"),
                        username = rs.getString("username"),
                        passwordHash = rs.getString("password_hash"),
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
                    
                    // 통계 초기화
                    initializeTodayStatistics(user.id, conn)
                    conn.commit()
                    
                    currentUser = user
                    Pair(true, "로그인 성공! 환영합니다, ${user.name}님!")
                } else {
                    // 로컬에 사용자 정보가 없는 경우 (서버에는 있지만 로컬 동기화 안 됨)
                    // 로컬 DB에 사용자 정보 생성 (서버 인증 성공했으므로)
                    val passwordHash = User.hashPassword(password)
                    val createdAt = LocalDateTime.now().format(dateTimeFormatter)
                    
                    val insertStmt = conn.prepareStatement("""
                        INSERT INTO users (username, password_hash, name, role, created_at, last_login, is_active)
                        VALUES (?, ?, ?, 'USER', ?, ?, 1)
                    """.trimIndent())
                    
                    insertStmt.setString(1, username)
                    insertStmt.setString(2, passwordHash)
                    insertStmt.setString(3, username)  // 이름은 서버에서 받아와야 하지만, 일단 username 사용
                    insertStmt.setString(4, createdAt)
                    insertStmt.setString(5, LocalDateTime.now().format(dateTimeFormatter))
                    
                    insertStmt.executeUpdate()
                    insertStmt.close()
                    
                    // 생성된 사용자 정보 조회
                    val newUserStmt = conn.prepareStatement("""
                        SELECT id, username, password_hash, name, role, created_at, last_login, is_active
                        FROM users
                        WHERE username = ?
                    """.trimIndent())
                    newUserStmt.setString(1, username)
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
        // 임시 사용자 객체 생성하여 로그인 처리
        val tempUser = User(
            id = 0,
            username = username,
            passwordHash = User.hashPassword(password),
            name = username,
            role = UserRole.USER,
            createdAt = LocalDateTime.now().format(dateTimeFormatter),
            isActive = true
        )
        currentUser = tempUser
        Pair(true, "$message (로컬 동기화 실패: ${e.message})")
    }
}
```

---

## 🧪 테스트 방법

### 1. 서버 연결 테스트

```kotlin
// 테스트 코드 예시
fun testServerConnection() {
    val authApiClient = AuthApiClient()
    val isConnected = authApiClient.testConnection()
    println("서버 연결 상태: ${if (isConnected) "연결됨" else "연결 실패"}")
}
```

### 2. 회원가입 테스트

```kotlin
fun testRegister() {
    val authApiClient = AuthApiClient()
    val (success, message) = authApiClient.registerUser(
        username = "testuser",
        password = "test1234",
        name = "테스트 사용자",
        role = UserRole.USER
    )
    println("회원가입 결과: $success - $message")
}
```

### 3. 로그인 테스트

```kotlin
fun testLogin() {
    val authApiClient = AuthApiClient()
    val (success, message) = authApiClient.login(
        username = "testuser",
        password = "test1234"
    )
    println("로그인 결과: $success - $message")
}
```

---

## ⚠️ 주의사항

1. **비밀번호 전송**
   - 현재 구현은 평문 비밀번호를 서버로 전송합니다.
   - 프로덕션 환경에서는 HTTPS를 사용하여 전송 중 암호화를 보장해야 합니다.

2. **에러 처리**
   - 네트워크 오류 시 사용자에게 명확한 메시지를 제공합니다.
   - 서버 응답이 없을 경우 타임아웃 처리됩니다.

3. **로컬 DB 동기화**
   - 서버 인증 성공 후 로컬 DB에도 사용자 정보를 저장합니다.
   - 오프라인 지원을 위한 선택적 기능입니다.

4. **성능 최적화**
   - HTTP 클라이언트는 연결 풀링을 사용하여 성능을 최적화합니다.
   - 타임아웃 설정으로 무한 대기를 방지합니다.

---

## 📚 참고 자료

- [OkHttp3 공식 문서](https://square.github.io/okhttp/)
- [Gson 공식 문서](https://github.com/google/gson)
- 서버 API 스펙: `SERVER_AUTH_API_GUIDE.md` 참조

