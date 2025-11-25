package org.dev.semaschatbot

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.IOException
import com.google.gson.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP 프로토콜 클라이언트
 * 
 * JSON-RPC 2.0 기반 MCP 프로토콜을 통해 MCP 서버와 통신합니다.
 * 
 * 성능 최적화:
 * - 연결 풀링을 통한 재사용 연결 관리
 * - 타임아웃 설정으로 무한 대기 방지
 * - JSON 직렬화 최적화
 */
class MCPProtocolClient(
    private val serverEndpoint: String
) {
    // HTTP 클라이언트 (연결 풀링 및 타임아웃 설정)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    
    // JSON-RPC 요청 ID 카운터 (스레드 안전)
    private val requestIdCounter = AtomicInteger(1)
    
    /**
     * JSON-RPC 2.0 요청을 전송하고 응답을 받습니다.
     * 
     * @param method JSON-RPC 메서드명
     * @param params 메서드 파라미터
     * @return JSON 응답 객체
     */
    private fun sendJsonRpcRequest(method: String, params: JsonObject? = null): JsonObject {
        val requestId = requestIdCounter.getAndIncrement()
        
        // JSON-RPC 2.0 요청 생성
        val requestJson = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", requestId)
            addProperty("method", method)
            if (params != null) {
                add("params", params)
            }
        }
        
        val jsonString = gson.toJson(requestJson)
        val mediaType = "application/json".toMediaTypeOrNull()
        val requestBody = if (mediaType != null) {
            RequestBody.create(mediaType, jsonString)
        } else {
            RequestBody.create(null, jsonString)
        }
        
        val request = Request.Builder()
            .url(serverEndpoint)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .build()
        
        return try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}: $responseBody")
                }
                
                val responseJson = gson.fromJson(responseBody, JsonObject::class.java)
                
                // 에러 응답 확인
                if (responseJson.has("error")) {
                    val error = responseJson.getAsJsonObject("error")
                    val errorCode = error.get("code")?.asInt ?: -1
                    val errorMessage = error.get("message")?.asString ?: "Unknown error"
                    throw IOException("JSON-RPC Error ($errorCode): $errorMessage")
                }
                
                responseJson
            }
        } catch (e: IOException) {
            Logger.error("MCPProtocolClient", "JSON-RPC 요청 실패: ${e.message}")
            throw e
        } catch (e: Exception) {
            Logger.error("MCPProtocolClient", "요청 처리 중 오류: ${e.message}")
            throw IOException("요청 처리 중 오류: ${e.message}", e)
        }
    }
    
    /**
     * MCP 서버를 초기화합니다.
     * 
     * @param clientInfo 클라이언트 정보
     * @return 초기화 응답
     */
    fun initialize(clientInfo: MCPClientInfo = MCPClientInfo()): JsonObject {
        val params = JsonObject().apply {
            addProperty("protocolVersion", "2024-11-05")
            addProperty("capabilities", "{}")
            add("clientInfo", JsonObject().apply {
                addProperty("name", clientInfo.name)
                addProperty("version", clientInfo.version)
            })
        }
        
        Logger.info("MCPProtocolClient", "MCP 서버 초기화 시작: $serverEndpoint")
        val response = sendJsonRpcRequest("initialize", params)
        Logger.info("MCPProtocolClient", "MCP 서버 초기화 완료")
        return response
    }
    
    /**
     * 사용 가능한 도구 목록을 조회합니다.
     * 
     * @return 도구 목록
     */
    fun listTools(): List<JsonObject> {
        Logger.info("MCPProtocolClient", "도구 목록 조회 시작")
        val response = sendJsonRpcRequest("tools/list")
        
        val result = response.getAsJsonObject("result")
        val tools = result.getAsJsonArray("tools")
        
        val toolList = mutableListOf<JsonObject>()
        tools.forEach { tool ->
            if (tool.isJsonObject) {
                toolList.add(tool.asJsonObject)
            }
        }
        
        Logger.info("MCPProtocolClient", "도구 목록 조회 완료: ${toolList.size}개")
        return toolList
    }
    
    /**
     * 도구를 호출합니다.
     * 
     * @param toolName 도구 이름
     * @param arguments 도구 인자
     * @return 도구 실행 결과
     */
    fun callTool(toolName: String, arguments: JsonObject): JsonObject {
        Logger.info("MCPProtocolClient", "도구 호출: $toolName")
        
        val params = JsonObject().apply {
            addProperty("name", toolName)
            add("arguments", arguments)
        }
        
        val response = sendJsonRpcRequest("tools/call", params)
        val result = response.getAsJsonObject("result")
        
        Logger.info("MCPProtocolClient", "도구 호출 완료: $toolName")
        return result
    }
    
    /**
     * 할당된 작업 목록을 조회합니다.
     * 
     * @param username 사용자명
     * @param status 작업 상태 필터 (선택)
     * @param priority 우선순위 필터 (선택)
     * @return 작업 목록
     */
    fun getAssignedTasks(
        username: String,
        status: String? = null,
        priority: String? = null
    ): List<AssignedTask> {
        val arguments = JsonObject().apply {
            addProperty("username", username)
            status?.let { addProperty("status", it) }
            priority?.let { addProperty("priority", it) }
        }
        
        val result = callTool("get_assigned_tasks", arguments)
        
        // 결과 파싱
        val content = result.getAsJsonArray("content")
        val taskList = mutableListOf<AssignedTask>()
        
        content.forEach { item ->
            if (item.isJsonObject) {
                val itemObj = item.asJsonObject
                val text = itemObj.get("text")?.asString ?: ""
                
                // JSON 문자열 파싱
                try {
                    val taskJson = gson.fromJson(text, JsonObject::class.java)
                    val data = taskJson.getAsJsonArray("data")
                    
                    data.forEach { taskElement ->
                        if (taskElement.isJsonObject) {
                            val taskObj = taskElement.asJsonObject
                            val task = AssignedTask(
                                id = taskObj.get("id")?.asInt ?: 0,
                                requirementId = taskObj.get("requirement_id")?.asInt,
                                requirementTitle = taskObj.get("requirement_title")?.asString,
                                taskNumber = taskObj.get("task_number")?.asInt,
                                title = taskObj.get("title")?.asString ?: "",
                                description = taskObj.get("description")?.asString,
                                status = taskObj.get("status")?.asString ?: "PENDING",
                                priority = taskObj.get("priority")?.asString,
                                estimatedHours = taskObj.get("estimated_hours")?.asDouble,
                                actualHours = taskObj.get("actual_hours")?.asDouble,
                                assigneeName = taskObj.get("assignee_name")?.asString,
                                startDate = taskObj.get("start_date")?.asString,
                                dueDate = taskObj.get("due_date")?.asString,
                                completedDate = taskObj.get("completed_date")?.asString,
                                createdAt = taskObj.get("created_at")?.asString,
                                updatedAt = taskObj.get("updated_at")?.asString
                            )
                            taskList.add(task)
                        }
                    }
                } catch (e: Exception) {
                    Logger.error("MCPProtocolClient", "작업 목록 파싱 오류: ${e.message}")
                }
            }
        }
        
        Logger.info("MCPProtocolClient", "작업 목록 조회 완료: ${taskList.size}개")
        return taskList
    }
}

/**
 * MCP 클라이언트 정보
 */
data class MCPClientInfo(
    val name: String = "semasChatbot",
    val version: String = "0.1.2"
)

