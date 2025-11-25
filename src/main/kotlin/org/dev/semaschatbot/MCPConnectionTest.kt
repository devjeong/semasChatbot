package org.dev.semaschatbot

import com.intellij.openapi.project.ProjectManager
import java.io.File

/**
 * MCP 서버 연결 테스트 유틸리티
 * 
 * 서버 MCP에 연결하고 작업 목록을 조회하는 테스트를 수행합니다.
 */
object MCPConnectionTest {
    
    /**
     * MCP 서버 연결 테스트를 수행합니다.
     * 
     * @param username 테스트할 사용자명
     * @return 테스트 결과
     */
    fun testConnection(username: String = "selimjhw"): TestResult {
        Logger.info("MCPConnectionTest", "MCP 연결 테스트 시작: username=$username")
        
        val project = ProjectManager.getInstance().defaultProject
        val mcpApiClient = MCPApiClient()
        
        // 서버 URL 동기화
        try {
            val chatService = project.getService(ChatService::class.java)
            if (chatService != null) {
                val serverBaseUrl = chatService.getServerBaseUrl()
                mcpApiClient.setServerBaseUrl(serverBaseUrl)
            }
        } catch (e: Exception) {
            Logger.debug("MCPConnectionTest", "ChatService 초기화 대기 중")
        }
        
        return try {
            // 1. MCP 목록 조회
            Logger.info("MCPConnectionTest", "1단계: MCP 목록 조회")
            val (success, mcpList) = mcpApiClient.getMCPList()
            
            if (!success || mcpList.isEmpty()) {
                return TestResult(
                    success = false,
                    message = "MCP 목록 조회 실패 또는 목록이 비어있습니다.",
                    details = emptyList()
                )
            }
            
            Logger.info("MCPConnectionTest", "MCP 목록 조회 성공: ${mcpList.size}개")
            
            // 2. 작업 관리 MCP 서버 찾기
            Logger.info("MCPConnectionTest", "2단계: 작업 관리 MCP 서버 찾기")
            val taskMCP = mcpList.find { 
                it.name.contains("task", ignoreCase = true) || 
                it.name.contains("작업", ignoreCase = true) ||
                it.id.contains("task", ignoreCase = true)
            }
            
            if (taskMCP == null) {
                return TestResult(
                    success = false,
                    message = "작업 관리 MCP 서버를 찾을 수 없습니다.",
                    details = listOf("사용 가능한 MCP 서버: ${mcpList.map { it.name }.joinToString(", ")}")
                )
            }
            
            Logger.info("MCPConnectionTest", "작업 관리 MCP 서버 발견: ${taskMCP.name} (${taskMCP.endpoint})")
            
            // 3. MCP 서버 스크립트 경로 결정
            Logger.info("MCPConnectionTest", "3단계: MCP 서버 스크립트 경로 결정")
            
            // MCP 서버 스크립트 경로 결정
            // endpoint가 HTTP URL인 경우, MCP 서버 스크립트 경로를 추론하거나 설정에서 가져옴
            val serverScriptPath = if (taskMCP.endpoint.startsWith("http://") || taskMCP.endpoint.startsWith("https://")) {
                // HTTP URL인 경우, 서버 주소에서 경로 추론
                // 예: http://192.168.18.53:5000 -> C:\dev\workspace\semasChatbotMng\mcp_servers\task_mcp_server.py
                // 실제로는 설정 파일이나 환경 변수에서 가져와야 함
                val osName = System.getProperty("os.name").lowercase()
                val defaultPath = if (osName.contains("windows")) {
                    "C:\\dev\\workspace\\semasChatbotMng\\mcp_servers\\task_mcp_server.py"
                } else {
                    System.getProperty("user.home") + "/workspace/semasChatbotMng/mcp_servers/task_mcp_server.py"
                }
                
                // 환경 변수에서 경로 확인
                val envPath = System.getenv("MCP_SERVER_SCRIPT_PATH")
                envPath ?: defaultPath
            } else {
                // endpoint가 직접 스크립트 경로인 경우
                taskMCP.endpoint
            }
            
            // 스크립트 파일 존재 확인
            val scriptFile = File(serverScriptPath)
            if (!scriptFile.exists()) {
                return TestResult(
                    success = false,
                    message = "MCP 서버 스크립트 파일을 찾을 수 없습니다.",
                    details = listOf(
                        "스크립트 경로: $serverScriptPath",
                        "파일이 존재하지 않습니다.",
                        "환경 변수 MCP_SERVER_SCRIPT_PATH를 설정하거나 스크립트 경로를 확인해주세요."
                    )
                )
            }
            
            Logger.info("MCPConnectionTest", "MCP 서버 스크립트 경로: $serverScriptPath")
            
            // 4. MCP stdio 클라이언트 생성 및 연결
            Logger.info("MCPConnectionTest", "4단계: MCP stdio 클라이언트 연결")
            val mcpClient = MCPStdioClient(
                serverScriptPath = serverScriptPath,
                workingDirectory = File(serverScriptPath).parent,
                environment = mapOf(
                    "DB_FILE" to (System.getenv("DB_FILE") ?: "auth.db"),
                    "MCP_LOG_FILE" to (System.getenv("MCP_LOG_FILE") ?: "./logs/task_mcp_server.log")
                )
            )
            
            mcpClient.connect()
            
            // 5. 초기화
            Logger.info("MCPConnectionTest", "5단계: MCP 서버 초기화")
            val initResponse = mcpClient.initialize()
            Logger.info("MCPConnectionTest", "초기화 응답: ${initResponse.toString()}")
            
            // 6. 도구 목록 조회
            Logger.info("MCPConnectionTest", "6단계: 도구 목록 조회")
            val tools = mcpClient.listTools()
            Logger.info("MCPConnectionTest", "사용 가능한 도구: ${tools.map { it.get("name")?.asString }.joinToString(", ")}")
            
            // 7. get_assigned_tasks 도구 호출
            Logger.info("MCPConnectionTest", "7단계: get_assigned_tasks 도구 호출 (username=$username)")
            val tasks = mcpClient.getAssignedTasks(username)
            
            // 8. 연결 해제
            mcpClient.disconnect()
            
            Logger.info("MCPConnectionTest", "작업 목록 조회 성공: ${tasks.size}개")
            
            // 9. 결과 반환
            TestResult(
                success = true,
                message = "MCP 연결 테스트 성공",
                details = listOf(
                    "MCP 서버: ${taskMCP.name}",
                    "스크립트 경로: $serverScriptPath",
                    "사용 가능한 도구: ${tools.size}개",
                    "조회된 작업 수: ${tasks.size}개",
                    if (tasks.isNotEmpty()) {
                        "작업 예시: ${tasks.first().title}"
                    } else {
                        "작업이 없습니다."
                    }
                )
            )
        } catch (e: Exception) {
            Logger.error("MCPConnectionTest", "MCP 연결 테스트 실패: ${e.message}")
            e.printStackTrace()
            
            TestResult(
                success = false,
                message = "MCP 연결 테스트 실패: ${e.message}",
                details = listOf(
                    "오류 타입: ${e.javaClass.simpleName}",
                    "오류 메시지: ${e.message}",
                    if (e.cause != null) "원인: ${e.cause?.message}" else ""
                ).filter { it.isNotBlank() }
            )
        }
    }
}

/**
 * 테스트 결과 데이터 클래스
 */
data class TestResult(
    val success: Boolean,
    val message: String,
    val details: List<String>
) {
    override fun toString(): String {
        val status = if (success) "✅ 성공" else "❌ 실패"
        val detailsText = if (details.isNotEmpty()) {
            "\n상세 정보:\n" + details.joinToString("\n") { "  - $it" }
        } else {
            ""
        }
        return "$status: $message$detailsText"
    }
}

