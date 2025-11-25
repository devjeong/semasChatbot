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
        } catch (e: Exception) {
            Logger.error("MCPStdioClient", "MCP 서버 프로세스 시작 실패: ${e.message}")
            disconnect()
            throw IOException("MCP 서버 프로세스 시작 실패: ${e.message}", e)
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
     * @return JSON 응답 객체
     */
    private fun sendJsonRpcRequest(method: String, params: JsonObject? = null): JsonObject {
        if (!isConnected || process == null || stdinWriter == null) {
            throw IOException("MCP 서버에 연결되어 있지 않습니다.")
        }
        
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
        
        // 응답 대기 Future 생성
        val responseFuture = CompletableFuture<JsonObject>()
        responseFutures[requestId] = responseFuture
        
        try {
            // stdin으로 요청 전송
            stdinWriter!!.write(jsonString)
            stdinWriter!!.newLine()
            stdinWriter!!.flush()
            
            Logger.debug("MCPStdioClient", "요청 전송: $method (id=$requestId)")
            
            // 응답 대기 (최대 30초)
            val response = responseFuture.get(30, TimeUnit.SECONDS)
            return response
        } catch (e: TimeoutException) {
            responseFutures.remove(requestId)
            throw IOException("요청 타임아웃: $method", e)
        } catch (e: ExecutionException) {
            responseFutures.remove(requestId)
            throw IOException("요청 처리 오류: $method", e.cause ?: e)
        } catch (e: Exception) {
            responseFutures.remove(requestId)
            Logger.error("MCPStdioClient", "JSON-RPC 요청 실패: ${e.message}")
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
        
        Logger.info("MCPStdioClient", "MCP 서버 초기화 시작")
        val response = sendJsonRpcRequest("initialize", params)
        Logger.info("MCPStdioClient", "MCP 서버 초기화 완료")
        return response
    }
    
    /**
     * 사용 가능한 도구 목록을 조회합니다.
     * 
     * @return 도구 목록
     */
    fun listTools(): List<JsonObject> {
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
                    Logger.error("MCPStdioClient", "작업 목록 파싱 오류: ${e.message}")
                }
            }
        }
        
        Logger.info("MCPStdioClient", "작업 목록 조회 완료: ${taskList.size}개")
        return taskList
    }
    
    /**
     * 연결을 해제하고 프로세스를 종료합니다.
     */
    fun disconnect() {
        if (!isConnected) {
            return
        }
        
        isConnected = false
        
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
        return isConnected && process?.isAlive == true
    }
}

