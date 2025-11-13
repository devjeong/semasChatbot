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
    private var serverBaseUrl: String = "http://192.168.18.53:5000"
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
     * 서버 기본 URL을 설정합니다.
     * 포트 5000이 자동으로 추가되어 인증 API 엔드포인트로 사용됩니다.
     * @param url 서버 기본 URL (예: "http://192.168.18.53")
     */
    fun setServerBaseUrl(url: String) {
        var cleanedUrl = url.trim().removeSuffix("/")
        
        // 포트가 포함된 경우 제거 (호스트만 저장)
        // 예: http://192.168.18.53:5000 -> http://192.168.18.53
        val portPattern = Regex(":\\d+$")
        cleanedUrl = cleanedUrl.replace(portPattern, "")
        
        // 인증 API는 포트 5000 사용
        serverBaseUrl = "$cleanedUrl:5000"
        println("[AuthApiClient] 서버 URL 설정: $serverBaseUrl")
    }
    
    /**
     * 현재 설정된 서버 기본 URL을 반환합니다.
     * @return 현재 서버 기본 URL (포트 5000 포함)
     */
    fun getServerBaseUrl(): String {
        return serverBaseUrl
    }
    
    /**
     * 회원가입 요청을 서버로 전송합니다.
     * 
     * @param username 사용자 아이디
     * @param password 비밀번호 (평문, 서버에서 해시 처리)
     * @param name 사용자 이름
     * @param role 사용자 권한 (기본값: USER)
     * @return Triple<성공 여부, 메시지, 사용자 정보>, 성공 시 사용자 정보도 포함된 Triple 반환
     */
    fun registerUser(
        username: String,
        password: String,
        name: String,
        role: UserRole = UserRole.USER
    ): Triple<Boolean, String, Map<String, Any>?> {
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
                    return Triple(false, errorMessage, null)
                }
                
                // 성공 응답 파싱
                val responseJson = gson.fromJson(responseBody, JsonObject::class.java)
                val message = responseJson.get("message")?.asString ?: "회원가입이 완료되었습니다!"
                
                // 사용자 정보 추출 (있는 경우)
                val userInfo: Map<String, Any>? = try {
                    val userJson = responseJson.getAsJsonObject("user")
                    if (userJson != null) {
                        mapOf(
                            "id" to (userJson.get("id")?.asInt ?: 0),
                            "username" to (userJson.get("username")?.asString ?: username),
                            "name" to (userJson.get("name")?.asString ?: name),
                            "role" to (userJson.get("role")?.asString ?: role.name),
                            "created_at" to (userJson.get("created_at")?.asString ?: "")
                        )
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    null
                }
                
                Triple(true, message, userInfo)
            }
        } catch (e: IOException) {
            // 네트워크 오류 처리
            Triple(false, "네트워크 오류가 발생했습니다: ${e.message}", null)
        } catch (e: Exception) {
            // 기타 오류 처리
            Triple(false, "회원가입 요청 중 오류가 발생했습니다: ${e.message}", null)
        }
    }
    
    /**
     * 로그인 요청을 서버로 전송합니다.
     * 
     * @param username 사용자 아이디
     * @param password 비밀번호 (평문)
     * @return Pair<성공 여부, 메시지>, 성공 시 사용자 정보도 포함된 Triple 반환
     */
    fun login(username: String, password: String): Triple<Boolean, String, Map<String, Any>?> {
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
                    return Triple(false, errorMessage, null)
                }
                
                // 성공 응답 파싱
                val responseJson = gson.fromJson(responseBody, JsonObject::class.java)
                val success = responseJson.get("success")?.asBoolean ?: false
                val message = responseJson.get("message")?.asString ?: "로그인 성공!"
                
                // 사용자 정보 추출 (있는 경우)
                val userInfo: Map<String, Any>? = try {
                    val userJson = responseJson.getAsJsonObject("user")
                    if (userJson != null) {
                        mapOf(
                            "id" to (userJson.get("id")?.asInt ?: 0),
                            "username" to (userJson.get("username")?.asString ?: username),
                            "name" to (userJson.get("name")?.asString ?: username),
                            "role" to (userJson.get("role")?.asString ?: "USER"),
                            "created_at" to (userJson.get("created_at")?.asString ?: ""),
                            "last_login" to (userJson.get("last_login")?.asString ?: "")
                        )
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    null
                }
                
                if (success) {
                    Triple(true, message, userInfo)
                } else {
                    Triple(false, message, null)
                }
            }
        } catch (e: IOException) {
            // 네트워크 오류 처리
            Triple(false, "네트워크 오류가 발생했습니다: ${e.message}", null)
        } catch (e: Exception) {
            // 기타 오류 처리
            Triple(false, "로그인 요청 중 오류가 발생했습니다: ${e.message}", null)
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
                .url("$serverBaseUrl/api/auth/health")
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

