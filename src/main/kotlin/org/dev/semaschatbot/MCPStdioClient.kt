package org.dev.semaschatbot

import com.google.gson.*
import java.io.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP stdio 기반 프로토콜 클라이언트
 * 
 * stdio를 통해 MCP 서버와 통신합니다.
 * ProcessBuilder를 사용하여 Python 스크립트를 실행하고 stdin/stdout으로 JSON-RPC 2.0 메시지를 주고받습니다.
 * 
 * 성능 최적화:
 * - 프로세스 재사용을 통한 연결 관리
 * - 타임아웃 설정으로 무한 대기 방지
 * - JSON 직렬화 최적화
 */
class MCPStdioClient(
    private val serverScriptPath: String,
    private val workingDirectory: String? = null,
    private val environment: Map<String, String> = emptyMap()
) {
    private val gson = Gson()
    
    // JSON-RPC 요청 ID 카운터 (스레드 안전)
    private val requestIdCounter = AtomicInteger(1)
    
    // 프로세스 및 입출력 스트림
    private var process: Process? = null
    private var stdinWriter: BufferedWriter? = null
    private var stdoutReader: BufferedReader? = null
    private var stderrReader: BufferedReader? = null
    
    // 응답 대기 큐 (요청 ID -> CompletableFuture)
    private val responseFutures = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
    
    // stdout 읽기 스레드
    private var stdoutReaderThread: Thread? = null
    
    // 연결 상태
    @Volatile
    private var isConnected = false
    
    @Volatile
    private var isInitialized = false
    
    /**
     * MCP 서버 프로세스를 시작하고 연결합니다.
     */
    fun connect() {
        if (isConnected) {
            Logger.warn("MCPStdioClient", "이미 연결되어 있습니다.")
            return
        }
        
        try {
            Logger.info("MCPStdioClient", "MCP 서버 프로세스 시작: $serverScriptPath")
            
            // Python 명령어 결정 (Windows: python, Linux/Mac: python3)
            val pythonCommand = if (System.getProperty("os.name").lowercase().contains("windows")) {
                "python"
            } else {
                "python3"
            }
            
            // 프로세스 빌더 생성
            val processBuilder = ProcessBuilder(pythonCommand, serverScriptPath)
            
            // 작업 디렉토리 설정
            workingDirectory?.let {
                processBuilder.directory(File(it))
            }
            
            // 환경 변수 설정
            val env = processBuilder.environment()
            // UTF-8 인코딩 강제 설정
            env["PYTHONIOENCODING"] = "utf-8"
            environment.forEach { (key, value) ->
                env[key] = value
            }
            
            // 프로세스 시작
            process = processBuilder.start()
            
            // 입출력 스트림 설정
            stdinWriter = BufferedWriter(
                OutputStreamWriter(process!!.outputStream, Charsets.UTF_8)
            )
            stdoutReader = BufferedReader(
                InputStreamReader(process!!.inputStream, Charsets.UTF_8)
            )
            stderrReader = BufferedReader(
                InputStreamReader(process!!.errorStream, Charsets.UTF_8)
            )
            
            // stdout 읽기 스레드 시작
            startStdoutReader()
            
            // stderr 읽기 스레드 시작 (로깅용)
            startStderrReader()
            
            isConnected = true
            Logger.info("MCPStdioClient", "MCP 서버 프로세스 시작 완료")
            
            // MCP 프로토콜 초기화 수행
            initialize()
            
        } catch (e: Exception) {
            Logger.error("MCPStdioClient", "MCP 서버 연결 실패: ${e.message}")
            disconnect()
            throw IOException("MCP 서버 연결 실패: ${e.message}", e)
        }
    }
    
    /**
     * stdout 읽기 스레드 시작
     */
    private fun startStdoutReader() {
        stdoutReaderThread = Thread({
            try {
                var line: String?
                while (stdoutReader != null && isConnected) {
                    line = stdoutReader?.readLine()
                    if (line == null) break
                    
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    
                    // Logger.debug("MCPStdioClient", "수신: $trimmed")
                    
                    try {
                        val responseJson = gson.fromJson(trimmed, JsonObject::class.java)
                        val requestId = responseJson.get("id")?.asInt
                        
                        if (requestId != null) {
                            val future = responseFutures.remove(requestId)
                            if (future != null) {
                                // 에러 응답 확인
                                if (responseJson.has("error")) {
                                    val error = responseJson.getAsJsonObject("error")
                                    val errorCode = error.get("code")?.asInt ?: -1
                                    val errorMessage = error.get("message")?.asString ?: "Unknown error"
                                    future.completeExceptionally(
                                        IOException("JSON-RPC Error ($errorCode): $errorMessage")
                                    )
                                } else {
                                    future.complete(responseJson)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Logger.error("MCPStdioClient", "응답 파싱 오류: ${e.message}, 라인: $trimmed")
                    }
                }
            } catch (e: Exception) {
                if (isConnected) {
                    Logger.error("MCPStdioClient", "stdout 읽기 오류: ${e.message}")
                }
            }
        }, "MCP-Stdout-Reader")
        stdoutReaderThread?.isDaemon = true
        stdoutReaderThread?.start()
    }
    
    /**
     * stderr 읽기 스레드 시작 (로깅용)
     */
    private fun startStderrReader() {
        Thread({
            try {
                var line: String?
                while (stderrReader != null && isConnected) {
                    line = stderrReader?.readLine()
                    if (line == null) break
                    
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        Logger.debug("MCPStdioClient", "stderr: $trimmed")
                    }
                }
            } catch (e: Exception) {
                if (isConnected) {
                    Logger.debug("MCPStdioClient", "stderr 읽기 종료: ${e.message}")
                }
            }
        }, "MCP-Stderr-Reader").apply {
            isDaemon = true
            start()
        }
    }
    
    /**
     * JSON-RPC 2.0 요청을 전송하고 응답을 받습니다.
     * 
     * @param method JSON-RPC 메서드명
     * @param params 메서드 파라미터
     * @param isNotification 알림 여부 (true이면 응답을 기다리지 않음)
     * @return JSON 응답 객체 (알림인 경우 빈 객체 반환)
     */
    private fun sendJsonRpcRequest(method: String, params: JsonObject? = null, isNotification: Boolean = false): JsonObject {
        if (!isConnected || process == null || stdinWriter == null) {
            throw IOException("MCP 서버에 연결되어 있지 않습니다.")
        }
        
        val requestId = if (isNotification) null else requestIdCounter.getAndIncrement()
        
        // JSON-RPC 2.0 요청 생성
        val requestJson = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            if (!isNotification) {
                addProperty("id", requestId)
            }
            addProperty("method", method)
            if (params != null) {
                add("params", params)
            }
        }
        
        val jsonString = gson.toJson(requestJson)
        
        // 응답 대기 Future 생성 (알림이 아닌 경우에만)
        val responseFuture = if (!isNotification) CompletableFuture<JsonObject>() else null
        
        if (requestId != null && responseFuture != null) {
            responseFutures[requestId] = responseFuture
        }
        
        try {
            // stdin으로 요청 전송
            stdinWriter!!.write(jsonString)
            stdinWriter!!.newLine()
            stdinWriter!!.flush()
            
            Logger.debug("MCPStdioClient", "전송: $jsonString")
            
            if (isNotification) {
                return JsonObject()
            }
            
            // 응답 대기 (최대 30초)
            val response = responseFuture!!.get(30, TimeUnit.SECONDS)
            return response
        } catch (e: TimeoutException) {
            if (requestId != null) responseFutures.remove(requestId)
            throw IOException("요청 타임아웃: $method", e)
        } catch (e: ExecutionException) {
            if (requestId != null) responseFutures.remove(requestId)
            throw IOException("요청 처리 오류: $method", e.cause ?: e)
        } catch (e: Exception) {
            if (requestId != null) responseFutures.remove(requestId)
            Logger.error("MCPStdioClient", "JSON-RPC 요청 실패: ${e.message}")
            throw IOException("요청 처리 중 오류: ${e.message}", e)
        }
    }
    
    /**
     * MCP 서버를 초기화합니다.
     */
    private fun initialize() {
        Logger.info("MCPStdioClient", "MCP 서버 초기화 시작")
        
        // 1. initialize 요청 전송
        val params = JsonObject().apply {
            addProperty("protocolVersion", "2024-11-05")
            add("capabilities", JsonObject())
            add("clientInfo", JsonObject().apply {
                addProperty("name", "semasChatbot")
                addProperty("version", "1.0.0")
            })
        }
        
        val response = sendJsonRpcRequest("initialize", params)
        
        // 2. 응답 확인 (에러 체크는 sendJsonRpcRequest에서 처리됨)
        Logger.info("MCPStdioClient", "initialize 응답 수신 완료")
        
        // 3. initialized 알림 전송
        sendJsonRpcRequest("notifications/initialized", null, true)
        Logger.info("MCPStdioClient", "initialized 알림 전송 완료")
        
        isInitialized = true
        Logger.info("MCPStdioClient", "MCP 서버 초기화 완료")
    }
    
    /**
     * 사용 가능한 도구 목록을 조회합니다.
     * 
     * @return 도구 목록
     */
    fun listTools(): List<JsonObject> {
        if (!isInitialized) {
            throw IllegalStateException("서버가 초기화되지 않았습니다.")
        }
        
        Logger.info("MCPStdioClient", "도구 목록 조회 시작")
        val response = sendJsonRpcRequest("tools/list")
        
        val result = response.getAsJsonObject("result")
        val tools = result.getAsJsonArray("tools")
        
        val toolList = mutableListOf<JsonObject>()
        tools.forEach { tool ->
            if (tool.isJsonObject) {
                toolList.add(tool.asJsonObject)
            }
        }
        
        Logger.info("MCPStdioClient", "도구 목록 조회 완료: ${toolList.size}개")
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
        if (!isInitialized) {
            throw IllegalStateException("서버가 초기화되지 않았습니다.")
        }
        
        Logger.info("MCPStdioClient", "도구 호출: $toolName")
        
        val params = JsonObject().apply {
            addProperty("name", toolName)
            add("arguments", arguments)
        }
        
        val response = sendJsonRpcRequest("tools/call", params)
        val result = response.getAsJsonObject("result")
        
        Logger.info("MCPStdioClient", "도구 호출 완료: $toolName")
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
        Logger.debug("MCPStdioClient", "도구 실행 결과: $result")
        
        // 결과 파싱
        val taskList = mutableListOf<AssignedTask>()
        
        try {
            // content 필드 확인
            if (!result.has("content") || result.get("content").isJsonNull) {
                Logger.warn("MCPStdioClient", "응답에 content 필드가 없거나 null입니다.")
                return emptyList()
            }
            
            val content = result.getAsJsonArray("content")
            
            content.forEach { item ->
                if (item.isJsonObject) {
                    val itemObj = item.asJsonObject
                    
                    // text 필드 확인
                    if (itemObj.has("text") && !itemObj.get("text").isJsonNull) {
                        val textElement = itemObj.get("text")
                        val text = if (textElement.isJsonPrimitive && textElement.asJsonPrimitive.isString) {
                            textElement.asString
                        } else {
                            textElement.toString()
                        }
                        
                        Logger.debug("MCPStdioClient", "파싱할 텍스트: $text")
                        
                        // JSON 문자열 파싱
                        try {
                            val taskJson = gson.fromJson(text, JsonObject::class.java)
                            
                            // data 필드 확인
                            if (taskJson.has("data") && !taskJson.get("data").isJsonNull) {
                                val data = taskJson.getAsJsonArray("data")
                                
                                data.forEach { taskElement ->
                                    if (taskElement.isJsonObject) {
                                        val taskObj = taskElement.asJsonObject
                                        val task = AssignedTask(
                                            id = taskObj.get("id")?.asInt ?: 0,
                                            requirementId = if (taskObj.has("requirement_id") && !taskObj.get("requirement_id").isJsonNull) taskObj.get("requirement_id").asInt else null,
                                            requirementTitle = if (taskObj.has("requirement_title") && !taskObj.get("requirement_title").isJsonNull) taskObj.get("requirement_title").asString else null,
                                            taskNumber = if (taskObj.has("task_number") && !taskObj.get("task_number").isJsonNull) taskObj.get("task_number").asInt else null,
                                            title = if (taskObj.has("title") && !taskObj.get("title").isJsonNull) taskObj.get("title").asString else "",
                                            description = if (taskObj.has("description") && !taskObj.get("description").isJsonNull) taskObj.get("description").asString else null,
                                            status = if (taskObj.has("status") && !taskObj.get("status").isJsonNull) taskObj.get("status").asString else "PENDING",
                                            priority = if (taskObj.has("priority") && !taskObj.get("priority").isJsonNull) taskObj.get("priority").asString else null,
                                            estimatedHours = if (taskObj.has("estimated_hours") && !taskObj.get("estimated_hours").isJsonNull) taskObj.get("estimated_hours").asDouble else null,
                                            actualHours = if (taskObj.has("actual_hours") && !taskObj.get("actual_hours").isJsonNull) taskObj.get("actual_hours").asDouble else null,
                                            assigneeName = if (taskObj.has("assignee_name") && !taskObj.get("assignee_name").isJsonNull) taskObj.get("assignee_name").asString else null,
                                            startDate = if (taskObj.has("start_date") && !taskObj.get("start_date").isJsonNull) taskObj.get("start_date").asString else null,
                                            dueDate = if (taskObj.has("due_date") && !taskObj.get("due_date").isJsonNull) taskObj.get("due_date").asString else null,
                                            completedDate = if (taskObj.has("completed_date") && !taskObj.get("completed_date").isJsonNull) taskObj.get("completed_date").asString else null,
                                            createdAt = if (taskObj.has("created_at") && !taskObj.get("created_at").isJsonNull) taskObj.get("created_at").asString else null,
                                            updatedAt = if (taskObj.has("updated_at") && !taskObj.get("updated_at").isJsonNull) taskObj.get("updated_at").asString else null
                                        )
                                        taskList.add(task)
                                    }
                                }
                            } else {
                                Logger.warn("MCPStdioClient", "응답 JSON에 data 필드가 없습니다: $text")
                            }
                        } catch (e: Exception) {
                            Logger.error("MCPStdioClient", "JSON 텍스트 파싱 오류: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logger.error("MCPStdioClient", "작업 목록 전체 파싱 오류: ${e.message}")
        }
        
        Logger.info("MCPStdioClient", "작업 목록 조회 완료: ${taskList.size}개")
        return taskList
    }
    
    /**
     * 작업 정보를 업데이트합니다.
     * 
     * @param taskId 작업 ID
     * @param status 상태 (선택)
     * @param startDate 시작일 (선택, YYYY-MM-DD 형식)
     * @param dueDate 마감일 (선택, YYYY-MM-DD 형식)
     * @param actualHours 실제 소요 시간 (선택)
     * @return 업데이트 성공 여부
     */
    fun updateTask(
        taskId: Int,
        status: String? = null,
        startDate: String? = null,
        dueDate: String? = null,
        actualHours: Double? = null
    ): Boolean {
        val arguments = JsonObject().apply {
            addProperty("task_id", taskId)
            status?.let { addProperty("status", it) }
            startDate?.let { addProperty("start_date", it) }
            dueDate?.let { addProperty("due_date", it) }
            actualHours?.let { addProperty("actual_hours", it) }
        }
        
        Logger.info("MCPStdioClient", "작업 업데이트 시작: taskId=$taskId")
        
        try {
            val result = callTool("update_task", arguments)
            Logger.debug("MCPStdioClient", "작업 업데이트 결과: $result")
            
            // 결과 파싱
            if (result.has("content") && !result.get("content").isJsonNull) {
                val content = result.getAsJsonArray("content")
                if (content.size() > 0) {
                    val firstItem = content.get(0).asJsonObject
                    if (firstItem.has("text")) {
                        val text = firstItem.get("text").asString
                        val responseJson = gson.fromJson(text, JsonObject::class.java)
                        
                        if (responseJson.has("success")) {
                            val success = responseJson.get("success").asBoolean
                            Logger.info("MCPStdioClient", "작업 업데이트 완료: success=$success")
                            return success
                        }
                    }
                }
            }
            
            Logger.warn("MCPStdioClient", "작업 업데이트 응답 형식이 올바르지 않습니다")
            return false
        } catch (e: Exception) {
            Logger.error("MCPStdioClient", "작업 업데이트 오류: ${e.message}")
            throw IOException("작업 업데이트 실패: ${e.message}", e)
        }
    }
    
    /**
     * 연결을 해제하고 프로세스를 종료합니다.
     */
    fun disconnect() {
        if (!isConnected) {
            return
        }
        
        isConnected = false
        isInitialized = false
        
        try {
            // 스트림 닫기
            stdinWriter?.close()
            stdoutReader?.close()
            stderrReader?.close()
            
            // 프로세스 종료
            process?.destroyForcibly()
            process?.waitFor(5, TimeUnit.SECONDS)
            
            Logger.info("MCPStdioClient", "MCP 서버 프로세스 종료 완료")
        } catch (e: Exception) {
            Logger.error("MCPStdioClient", "프로세스 종료 오류: ${e.message}")
        } finally {
            process = null
            stdinWriter = null
            stdoutReader = null
            stderrReader = null
            responseFutures.clear()
        }
    }
    
    /**
     * 연결 상태를 확인합니다.
     */
    fun isConnected(): Boolean {
        return isConnected && isInitialized && process?.isAlive == true
    }
}

