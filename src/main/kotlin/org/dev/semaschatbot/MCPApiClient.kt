package org.dev.semaschatbot

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.IOException
import com.google.gson.*
import java.util.concurrent.TimeUnit

/**
 * MCP API 클라이언트
 * 서버(192.168.18.53:5000)와 통신하여 MCP 목록 조회 및 연결 정보를 전송합니다.
 * 
 * 성능 최적화:
 * - 연결 풀링을 통한 재사용 연결 관리
 * - 타임아웃 설정으로 무한 대기 방지
 * - JSON 직렬화 최적화
 */
class MCPApiClient(
    private var serverBaseUrl: String = "http://192.168.18.53:5000"
) {
    // HTTP 클라이언트 (연결 풀링 및 타임아웃 설정)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)      // 연결 타임아웃: 10초
        .readTimeout(10, TimeUnit.SECONDS)          // 읽기 타임아웃: 10초
        .writeTimeout(10, TimeUnit.SECONDS)         // 쓰기 타임아웃: 10초
        .callTimeout(30, TimeUnit.SECONDS)         // 전체 호출 타임아웃: 30초
        .build()
    
    private val gson = Gson()
    
    /**
     * 서버 기본 URL을 설정합니다.
     * 포트 5000이 자동으로 추가되어 MCP API 엔드포인트로 사용됩니다.
     * @param url 서버 기본 URL (예: "http://192.168.18.53")
     */
    fun setServerBaseUrl(url: String) {
        var cleanedUrl = url.trim().removeSuffix("/")
        
        // 포트가 포함된 경우 제거 (호스트만 저장)
        val portPattern = Regex(":\\d+$")
        cleanedUrl = cleanedUrl.replace(portPattern, "")
        
        // MCP API는 포트 5000 사용
        serverBaseUrl = "$cleanedUrl:5000"
        Logger.info("MCPApiClient", "서버 URL 설정: $serverBaseUrl")
    }
    
    /**
     * 현재 설정된 서버 기본 URL을 반환합니다.
     * @return 현재 서버 기본 URL (포트 5000 포함)
     */
    fun getServerBaseUrl(): String {
        return serverBaseUrl
    }
    
    /**
     * MCP 목록을 조회합니다.
     * 
     * @return Pair<성공 여부, MCP 목록 또는 오류 메시지>
     */
    fun getMCPList(): Pair<Boolean, List<MCPListItem>> {
        val endpointUrl = "$serverBaseUrl/api/mcp/list"
        
        val request = Request.Builder()
            .url(endpointUrl)
            .get()
            .addHeader("Content-Type", "application/json")
            .build()
        
        return try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                
                if (!response.isSuccessful) {
                    val errorMessage = try {
                        val errorJson = gson.fromJson(responseBody, JsonObject::class.java)
                        errorJson.get("error")?.asString ?: "MCP 목록 조회에 실패했습니다. (HTTP ${response.code})"
                    } catch (e: Exception) {
                        "MCP 목록 조회에 실패했습니다. (HTTP ${response.code})"
                    }
                    Logger.error("MCPApiClient", errorMessage)
                    return Pair(false, emptyList())
                }
                
                // 성공 응답 파싱
                try {
                    val responseJson = gson.fromJson(responseBody, JsonObject::class.java)
                    
                    if (responseJson.get("success")?.asBoolean == true) {
                        // data 배열 안전하게 가져오기
                        val dataElement = responseJson.get("data")
                        val dataArray = when {
                            dataElement == null -> null
                            dataElement.isJsonNull -> null
                            dataElement.isJsonArray -> dataElement.asJsonArray
                            else -> null
                        }
                        
                        val mcpList = mutableListOf<MCPListItem>()
                        
                        dataArray?.forEach { element ->
                            if (element.isJsonObject) {
                                try {
                                    val itemJson = element.asJsonObject
                                    
                                    // 안전하게 필드 추출
                                    fun JsonObject.getSafeString(key: String): String? {
                                        val elem = get(key) ?: return null
                                        return if (elem.isJsonNull) null else try { elem.asString } catch (e: Exception) { null }
                                    }
                                    
                                    val item = MCPListItem(
                                        id = itemJson.getSafeString("id") ?: "",
                                        name = itemJson.getSafeString("name") ?: "",
                                        description = itemJson.getSafeString("description"),
                                        endpoint = itemJson.getSafeString("endpoint") ?: "",
                                        type = itemJson.getSafeString("type") ?: "HTTP",
                                        status = itemJson.getSafeString("status") ?: "unknown"
                                    )
                                    mcpList.add(item)
                                } catch (e: Exception) {
                                    Logger.warn("MCPApiClient", "MCP 항목 파싱 오류: ${e.message}")
                                }
                            }
                        }
                        
                        Logger.info("MCPApiClient", "MCP 목록 조회 성공: ${mcpList.size}개")
                        return Pair(true, mcpList)
                    } else {
                        val errorMessage = responseJson.get("error")?.asString ?: "MCP 목록 조회에 실패했습니다."
                        Logger.error("MCPApiClient", errorMessage)
                        return Pair(false, emptyList())
                    }
                } catch (e: Exception) {
                    Logger.error("MCPApiClient", "응답 파싱 오류: ${e.message}")
                    return Pair(false, emptyList())
                }
            }
        } catch (e: IOException) {
            val errorMessage = "네트워크 오류: ${e.message}"
            Logger.error("MCPApiClient", errorMessage)
            return Pair(false, emptyList())
        } catch (e: Exception) {
            val errorMessage = "MCP 목록 조회 중 오류가 발생했습니다: ${e.message}"
            Logger.error("MCPApiClient", errorMessage)
            return Pair(false, emptyList())
        }
    }
    
    /**
     * 할당된 작업 목록을 조회합니다.
     * MCP 서버의 get_assigned_tasks 도구를 통해 조회합니다.
     * 
     * @param username 사용자명 (필수)
     * @param status 작업 상태 필터 (선택): PENDING, IN_PROGRESS, REVIEW, COMPLETED, BLOCKED
     * @param priority 우선순위 필터 (선택): LOW, MEDIUM, HIGH, CRITICAL
     * @return Pair<성공 여부, 작업 목록>
     */
    fun getAssignedTasks(
        username: String,
        status: String? = null,
        priority: String? = null
    ): Pair<Boolean, List<AssignedTask>> {
        // 서버 API 엔드포인트 (MCP 서버를 통한 작업 조회)
        // 실제 구현에서는 MCP 서버에 연결하여 get_assigned_tasks 도구를 호출해야 하지만,
        // 현재는 서버 API를 통해 직접 조회하는 방식으로 구현
        val endpointUrl = buildString {
            append("$serverBaseUrl/api/tasks/assigned")
            append("?username=${java.net.URLEncoder.encode(username, "UTF-8")}")
            status?.let { append("&status=${java.net.URLEncoder.encode(it, "UTF-8")}") }
            priority?.let { append("&priority=${java.net.URLEncoder.encode(it, "UTF-8")}") }
        }
        
        val request = Request.Builder()
            .url(endpointUrl)
            .get()
            .addHeader("Content-Type", "application/json")
            .build()
        
        return try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                
                if (!response.isSuccessful) {
                    val errorMessage = try {
                        val errorJson = gson.fromJson(responseBody, JsonObject::class.java)
                        errorJson.get("error")?.asString ?: "작업 목록 조회에 실패했습니다. (HTTP ${response.code})"
                    } catch (e: Exception) {
                        "작업 목록 조회에 실패했습니다. (HTTP ${response.code})"
                    }
                    Logger.error("MCPApiClient", errorMessage)
                    return Pair(false, emptyList())
                }
                
                // 성공 응답 파싱
                try {
                    Logger.debug("MCPApiClient", "응답 본문: $responseBody")
                    val responseJson = gson.fromJson(responseBody, JsonObject::class.java)
                    
                    // success 필드 안전하게 확인
                    val successElement = responseJson.get("success")
                    val isSuccess = when {
                        successElement == null -> {
                            Logger.debug("MCPApiClient", "success 필드가 없습니다.")
                            false
                        }
                        successElement.isJsonNull -> {
                            Logger.debug("MCPApiClient", "success 필드가 JsonNull입니다.")
                            false
                        }
                        else -> {
                            try {
                                successElement.asBoolean
                            } catch (e: Exception) {
                                Logger.warn("MCPApiClient", "success 필드 파싱 오류: ${e.message}")
                                false
                            }
                        }
                    }
                    
                    if (isSuccess) {
                        // data 배열 안전하게 가져오기
                        val dataElement = responseJson.get("data")
                        val dataArray = when {
                            dataElement == null -> null
                            dataElement.isJsonNull -> null
                            dataElement.isJsonArray -> dataElement.asJsonArray
                            else -> null
                        }
                        
                        val taskList = mutableListOf<AssignedTask>()
                        
                        dataArray?.forEach { element ->
                            if (element.isJsonObject) {
                                try {
                                    val taskJson = element.asJsonObject
                                    
                                    // 각 필드를 안전하게 추출 (JsonNull 체크)
                                    fun JsonObject.getSafeInt(key: String): Int? {
                                        val elem = get(key) ?: return null
                                        return if (elem.isJsonNull) null else try { elem.asInt } catch (e: Exception) { null }
                                    }
                                    
                                    fun JsonObject.getSafeString(key: String): String? {
                                        val elem = get(key) ?: return null
                                        return if (elem.isJsonNull) null else try { elem.asString } catch (e: Exception) { null }
                                    }
                                    
                                    fun JsonObject.getSafeDouble(key: String): Double? {
                                        val elem = get(key) ?: return null
                                        return if (elem.isJsonNull) null else try { elem.asDouble } catch (e: Exception) { null }
                                    }
                                    
                                    val task = AssignedTask(
                                        id = taskJson.getSafeInt("id") ?: 0,
                                        requirementId = taskJson.getSafeInt("requirement_id"),
                                        requirementTitle = taskJson.getSafeString("requirement_title"),
                                        taskNumber = taskJson.getSafeInt("task_number"),
                                        title = taskJson.getSafeString("title") ?: "",
                                        description = taskJson.getSafeString("description"),
                                        status = taskJson.getSafeString("status") ?: "PENDING",
                                        priority = taskJson.getSafeString("priority"),
                                        estimatedHours = taskJson.getSafeDouble("estimated_hours"),
                                        actualHours = taskJson.getSafeDouble("actual_hours"),
                                        assigneeName = taskJson.getSafeString("assignee_name"),
                                        startDate = taskJson.getSafeString("start_date"),
                                        dueDate = taskJson.getSafeString("due_date"),
                                        completedDate = taskJson.getSafeString("completed_date"),
                                        createdAt = taskJson.getSafeString("created_at"),
                                        updatedAt = taskJson.getSafeString("updated_at")
                                    )
                                    taskList.add(task)
                                } catch (e: Exception) {
                                    Logger.warn("MCPApiClient", "작업 항목 파싱 오류: ${e.message}")
                                }
                            }
                        }
                        
                        Logger.info("MCPApiClient", "작업 목록 조회 성공: ${taskList.size}개")
                        return Pair(true, taskList)
                    } else {
                        val errorElement = responseJson.get("error")
                        val errorMessage = when {
                            errorElement == null -> "작업 목록 조회에 실패했습니다."
                            errorElement.isJsonNull -> "작업 목록 조회에 실패했습니다."
                            else -> errorElement.asString ?: "작업 목록 조회에 실패했습니다."
                        }
                        Logger.error("MCPApiClient", errorMessage)
                        return Pair(false, emptyList())
                    }
                } catch (e: Exception) {
                    Logger.error("MCPApiClient", "응답 파싱 오류: ${e.message}")
                    Logger.error("MCPApiClient", "오류 타입: ${e.javaClass.simpleName}")
                    Logger.debug("MCPApiClient", "응답 본문: $responseBody")
                    if (e.cause != null) {
                        Logger.debug("MCPApiClient", "원인: ${e.cause?.message}")
                    }
                    e.printStackTrace()
                    return Pair(false, emptyList())
                }
            }
        } catch (e: IOException) {
            val errorMessage = "네트워크 오류: ${e.message}"
            Logger.error("MCPApiClient", errorMessage)
            return Pair(false, emptyList())
        } catch (e: Exception) {
            val errorMessage = "작업 목록 조회 중 오류가 발생했습니다: ${e.message}"
            Logger.error("MCPApiClient", errorMessage)
            return Pair(false, emptyList())
        }
    }
    
    /**
     * MCP 연결 정보를 서버로 전송합니다.
     * 
     * @param connectionInfo 연결 정보
     * @return Pair<성공 여부, 메시지>
     */
    fun sendConnectionInfo(connectionInfo: MCPConnectionInfo): Pair<Boolean, String> {
        val endpointUrl = "$serverBaseUrl/api/mcp/connect"
        
        val requestBodyJson = gson.toJson(connectionInfo)
        
        val request = Request.Builder()
            .url(endpointUrl)
            .post(RequestBody.create("application/json".toMediaTypeOrNull(), requestBodyJson))
            .addHeader("Content-Type", "application/json")
            .build()
        
        return try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                
                if (!response.isSuccessful) {
                    val errorMessage = try {
                        val errorJson = gson.fromJson(responseBody, JsonObject::class.java)
                        errorJson.get("error")?.asString ?: "연결 정보 전송에 실패했습니다. (HTTP ${response.code})"
                    } catch (e: Exception) {
                        "연결 정보 전송에 실패했습니다. (HTTP ${response.code})"
                    }
                    Logger.error("MCPApiClient", errorMessage)
                    return Pair(false, errorMessage)
                }
                
                // 성공 응답 파싱
                try {
                    val responseJson = gson.fromJson(responseBody, JsonObject::class.java)
                    
                    if (responseJson.get("success")?.asBoolean == true) {
                        val message = responseJson.get("message")?.asString ?: "연결 정보가 저장되었습니다."
                        Logger.info("MCPApiClient", "연결 정보 전송 성공: ${connectionInfo.action}")
                        return Pair(true, message)
                    } else {
                        val errorMessage = responseJson.get("error")?.asString ?: "연결 정보 전송에 실패했습니다."
                        Logger.error("MCPApiClient", errorMessage)
                        return Pair(false, errorMessage)
                    }
                } catch (e: Exception) {
                    Logger.error("MCPApiClient", "응답 파싱 오류: ${e.message}")
                    return Pair(false, "응답 파싱 오류: ${e.message}")
                }
            }
        } catch (e: IOException) {
            val errorMessage = "네트워크 오류: ${e.message}"
            Logger.error("MCPApiClient", errorMessage)
            return Pair(false, errorMessage)
        } catch (e: Exception) {
            val errorMessage = "연결 정보 전송 중 오류가 발생했습니다: ${e.message}"
            Logger.error("MCPApiClient", errorMessage)
            return Pair(false, errorMessage)
        }
    }
}

/**
 * MCP 목록 항목 데이터 클래스
 */
data class MCPListItem(
    val id: String,
    val name: String,
    val description: String? = null,
    val endpoint: String,
    val type: String,
    val status: String
)

/**
 * MCP 연결 정보 데이터 클래스
 */
data class MCPConnectionInfo(
    val userId: String,
    val username: String,
    val ipAddress: String,
    val connectedAt: String? = null,
    val disconnectedAt: String? = null,
    val mcpId: String,
    val mcpName: String,
    val mcpEndpoint: String,
    val action: String  // "connect" or "disconnect"
)

