# MCP STDIO 클라이언트 설정 가이드

## 개요

이 가이드는 STDIO 방식으로 MCP 서버에 연결하는 클라이언트를 구현하는 방법을 설명합니다.

## 문제 상황

클라이언트가 MCP 서버에 연결할 때 다음과 같은 오류가 발생할 수 있습니다:

```
Failed to validate request: Received request before initialization was complete
```

이는 MCP 프로토콜의 초기화 순서를 따르지 않아서 발생하는 문제입니다.

---

## MCP 프로토콜 초기화 순서

MCP 프로토콜은 **반드시** 다음 순서를 따라야 합니다:

### 1. 클라이언트 → 서버: `initialize` 요청

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2024-11-05",
    "capabilities": {},
    "clientInfo": {
      "name": "your-client-name",
      "version": "1.0.0"
    }
  }
}
```

### 2. 서버 → 클라이언트: `initialize` 응답

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "protocolVersion": "2024-11-05",
    "capabilities": {},
    "serverInfo": {
      "name": "task-mcp-server",
      "version": "1.0.0"
    }
  }
}
```

### 3. 클라이언트 → 서버: `notifications/initialized` 알림

```json
{
  "jsonrpc": "2.0",
  "method": "notifications/initialized"
}
```

**중요**: 이 알림은 `id` 필드가 없습니다 (알림이므로).

### 4. 그 후에 `tools/call` 요청 가능

초기화가 완료된 후에만 도구를 호출할 수 있습니다.

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "get_assigned_tasks",
    "arguments": {
      "username": "selimjhw"
    }
  }
}
```

---

## 클라이언트 구현 예제

### Python 예제

```python
import subprocess
import json
import sys

class MCPStdioClient:
    def __init__(self, server_script_path: str, env_vars: dict = None):
        """
        Args:
            server_script_path: MCP 서버 스크립트 경로 (예: "C:/dev/workspace/semasChatbotMng/mcp_servers/task_mcp_server.py")
            env_vars: 환경 변수 딕셔너리
        """
        self.server_script_path = server_script_path
        self.env_vars = env_vars or {}
        self.process = None
        self.request_id = 1
        self.initialized = False
        
    def connect(self):
        """MCP 서버 프로세스 시작 및 초기화"""
        # 환경 변수 설정
        env = os.environ.copy()
        env.update(self.env_vars)
        
        # 프로세스 시작
        self.process = subprocess.Popen(
            [sys.executable, self.server_script_path],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=env,
            text=True,
            encoding='utf-8',
            bufsize=0
        )
        
        # 초기화 수행
        self._initialize()
        
    def _initialize(self):
        """MCP 서버 초기화"""
        # 1. initialize 요청 전송
        init_request = {
            "jsonrpc": "2.0",
            "id": self.request_id,
            "method": "initialize",
            "params": {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {
                    "name": "task-client",
                    "version": "1.0.0"
                }
            }
        }
        
        self.request_id += 1
        self._send_request(init_request)
        
        # 2. initialize 응답 읽기
        init_response = self._read_response()
        
        if "error" in init_response:
            raise Exception(f"초기화 실패: {init_response['error']}")
        
        # 3. initialized 알림 전송
        initialized_notification = {
            "jsonrpc": "2.0",
            "method": "notifications/initialized"
        }
        
        self._send_request(initialized_notification)
        self.initialized = True
        
    def _send_request(self, request: dict):
        """요청 전송"""
        message = json.dumps(request, ensure_ascii=False) + "\n"
        self.process.stdin.write(message)
        self.process.stdin.flush()
        
    def _read_response(self) -> dict:
        """응답 읽기"""
        line = self.process.stdout.readline()
        if not line:
            raise ConnectionError("서버 연결이 끊어졌습니다.")
        return json.loads(line.strip())
        
    def call_tool(self, tool_name: str, arguments: dict) -> dict:
        """도구 호출"""
        if not self.initialized:
            raise Exception("서버가 초기화되지 않았습니다. connect()를 먼저 호출하세요.")
        
        request = {
            "jsonrpc": "2.0",
            "id": self.request_id,
            "method": "tools/call",
            "params": {
                "name": tool_name,
                "arguments": arguments
            }
        }
        
        self.request_id += 1
        self._send_request(request)
        response = self._read_response()
        
        if "error" in response:
            raise Exception(f"도구 호출 실패: {response['error']}")
        
        # TextContent에서 JSON 추출
        content = response.get("result", {}).get("content", [])
        if content and len(content) > 0:
            text_content = content[0].get("text", "{}")
            return json.loads(text_content)
        
        return {"success": False, "error": "응답 데이터가 없습니다."}
        
    def close(self):
        """연결 종료"""
        if self.process:
            self.process.stdin.close()
            self.process.terminate()
            self.process.wait()

# 사용 예제
if __name__ == "__main__":
    client = MCPStdioClient(
        server_script_path="C:/dev/workspace/semasChatbotMng/mcp_servers/task_mcp_server.py",
        env_vars={
            "DB_FILE": "C:/dev/workspace/semasChatbotMng/auth.db",
            "MCP_LOG_FILE": "C:/dev/workspace/semasChatbotMng/logs/task_mcp_server.log"
        }
    )
    
    try:
        client.connect()
        
        # 작업 목록 조회
        result = client.call_tool("get_assigned_tasks", {
            "username": "selimjhw"
        })
        
        if result.get("success"):
            tasks = result.get("data", [])
            print(f"작업 목록 ({len(tasks)}개):")
            for task in tasks:
                print(f"  - [{task['id']}] {task['title']} ({task['status']})")
        else:
            print(f"오류: {result.get('error')}")
            
    finally:
        client.close()
```

### Kotlin 예제

```kotlin
import java.io.*
import java.util.concurrent.*
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*

class MCPStdioClient(
    private val serverScriptPath: String,
    private val envVars: Map<String, String> = emptyMap()
) {
    private var process: Process? = null
    private var requestId = 1
    private var initialized = false
    private val json = Json { ignoreUnknownKeys = true }
    
    fun connect() {
        // 환경 변수 설정
        val env = System.getenv().toMutableMap()
        env.putAll(envVars)
        
        // 프로세스 시작
        val processBuilder = ProcessBuilder("python", serverScriptPath)
        processBuilder.environment().putAll(env)
        process = processBuilder.start()
        
        // 초기화 수행
        initialize()
    }
    
    private fun initialize() {
        // 1. initialize 요청 전송
        val initRequest = mapOf(
            "jsonrpc" to "2.0",
            "id" to requestId++,
            "method" to "initialize",
            "params" to mapOf(
                "protocolVersion" to "2024-11-05",
                "capabilities" to emptyMap<String, Any>(),
                "clientInfo" to mapOf(
                    "name" to "task-client",
                    "version" to "1.0.0"
                )
            )
        )
        
        sendRequest(initRequest)
        
        // 2. initialize 응답 읽기
        val initResponse = readResponse()
        
        if (initResponse.containsKey("error")) {
            throw Exception("초기화 실패: ${initResponse["error"]}")
        }
        
        // 3. initialized 알림 전송
        val initializedNotification = mapOf(
            "jsonrpc" to "2.0",
            "method" to "notifications/initialized"
        )
        
        sendRequest(initializedNotification)
        initialized = true
    }
    
    private fun sendRequest(request: Map<String, Any>) {
        val message = json.encodeToString(
            JsonObject.serializer(),
            JsonObject(request.mapValues { 
                when (val v = it.value) {
                    is String -> JsonPrimitive(v)
                    is Number -> JsonPrimitive(v)
                    is Boolean -> JsonPrimitive(v)
                    is Map<*, *> -> JsonObject(v.mapValues { 
                        when (val v2 = it.value) {
                            is String -> JsonPrimitive(v2)
                            is Number -> JsonPrimitive(v2)
                            is Boolean -> JsonPrimitive(v2)
                            else -> JsonNull
                        }
                    })
                    else -> JsonNull
                }
            })
        ) + "\n"
        
        process?.outputStream?.writer(Charsets.UTF_8)?.use { writer ->
            writer.write(message)
            writer.flush()
        }
    }
    
    private fun readResponse(): Map<String, Any> {
        val line = process?.inputStream?.bufferedReader(Charsets.UTF_8)?.readLine()
            ?: throw IOException("서버 응답을 읽을 수 없습니다.")
        return json.decodeFromString<Map<String, Any>>(line)
    }
    
    fun callTool(toolName: String, arguments: Map<String, Any>): Map<String, Any> {
        if (!initialized) {
            throw IllegalStateException("서버가 초기화되지 않았습니다. connect()를 먼저 호출하세요.")
        }
        
        val request = mapOf(
            "jsonrpc" to "2.0",
            "id" to requestId++,
            "method" to "tools/call",
            "params" to mapOf(
                "name" to toolName,
                "arguments" to arguments
            )
        )
        
        sendRequest(request)
        val response = readResponse()
        
        if (response.containsKey("error")) {
            throw Exception("도구 호출 실패: ${response["error"]}")
        }
        
        // TextContent에서 JSON 추출
        val result = response["result"] as? Map<*, *>
        val content = result?.get("content") as? List<*>
        val textContent = (content?.firstOrNull() as? Map<*, *>)?.get("text") as? String
        
        if (textContent != null) {
            return json.decodeFromString<Map<String, Any>>(textContent)
        }
        
        return mapOf("success" to false, "error" to "응답 데이터가 없습니다.")
    }
    
    fun close() {
        process?.destroy()
    }
}

// 사용 예제
fun main() = runBlocking {
    val client = MCPStdioClient(
        serverScriptPath = "C:/dev/workspace/semasChatbotMng/mcp_servers/task_mcp_server.py",
        envVars = mapOf(
            "DB_FILE" to "C:/dev/workspace/semasChatbotMng/auth.db",
            "MCP_LOG_FILE" to "C:/dev/workspace/semasChatbotMng/logs/task_mcp_server.log"
        )
    )
    
    try {
        client.connect()
        
        // 작업 목록 조회
        val result = client.callTool("get_assigned_tasks", mapOf(
            "username" to "selimjhw"
        ))
        
        if (result["success"] == true) {
            val tasks = result["data"] as? List<*>
            println("작업 목록 (${tasks?.size ?: 0}개):")
            tasks?.forEach { task ->
                val taskMap = task as? Map<*, *>
                println("  - [${taskMap?.get("id")}] ${taskMap?.get("title")} (${taskMap?.get("status")})")
            }
        } else {
            println("오류: ${result["error"]}")
        }
    } finally {
        client.close()
    }
}
```

---

## MCP 서버 스크립트 설정

### 1. 서버 스크립트 경로

MCP 서버 스크립트는 다음 위치에 있습니다:

```
C:/dev/workspace/semasChatbotMng/mcp_servers/task_mcp_server.py
```

또는 상대 경로:

```
mcp_servers/task_mcp_server.py
```

### 2. Python 실행 명령

클라이언트에서 서버를 실행할 때는 다음 중 하나를 사용합니다:

**Windows:**
```kotlin
ProcessBuilder("python", serverScriptPath)
// 또는
ProcessBuilder("C:/Python39/python.exe", serverScriptPath)
```

**Linux/Mac:**
```kotlin
ProcessBuilder("python3", serverScriptPath)
// 또는
ProcessBuilder("/usr/bin/python3", serverScriptPath)
```

### 3. 환경 변수 설정

다음 환경 변수를 설정해야 합니다:

- **DB_FILE** (선택): 데이터베이스 파일 경로
  - 기본값: `auth.db` (프로젝트 루트 기준)
  - 예: `C:/dev/workspace/semasChatbotMng/auth.db`

- **MCP_LOG_FILE** (선택): 로그 파일 경로
  - 기본값: `./logs/task_mcp_server.log`
  - 예: `C:/dev/workspace/semasChatbotMng/logs/task_mcp_server.log`

### 4. 실행 스크립트 사용 (권장)

실행 스크립트를 사용하면 환경 변수와 Python 경로를 자동으로 설정합니다:

**Windows:**
```kotlin
ProcessBuilder("C:/dev/workspace/semasChatbotMng/mcp_servers/run_task_mcp.bat")
```

**Linux/Mac:**
```kotlin
ProcessBuilder("/path/to/semasChatbotMng/mcp_servers/run_task_mcp.sh")
```

---

## 클라이언트 구현 체크리스트

### ✅ 필수 구현 사항

1. **프로세스 시작**
   - Python 스크립트를 subprocess로 실행
   - stdin, stdout, stderr 파이프 설정
   - UTF-8 인코딩 설정

2. **초기화 순서 준수**
   - ✅ `initialize` 요청 전송
   - ✅ `initialize` 응답 수신 및 확인
   - ✅ `notifications/initialized` 알림 전송
   - ✅ 초기화 완료 플래그 설정

3. **도구 호출**
   - 초기화 완료 후에만 `tools/call` 요청
   - 요청 ID 관리 (각 요청마다 고유 ID)
   - 응답 파싱 및 에러 처리

4. **에러 처리**
   - 프로세스 시작 실패 처리
   - 초기화 실패 처리
   - 도구 호출 실패 처리
   - 연결 끊김 처리

### ❌ 자주 하는 실수

1. **초기화 없이 도구 호출**
   ```kotlin
   // ❌ 잘못된 방법
   client.callTool("get_assigned_tasks", arguments)  // 초기화 없이 호출
   
   // ✅ 올바른 방법
   client.connect()  // 먼저 초기화
   client.callTool("get_assigned_tasks", arguments)
   ```

2. **`notifications/initialized` 알림 누락**
   ```kotlin
   // ❌ 잘못된 방법
   initialize()  // initialized 알림 없이 바로 도구 호출
   callTool(...)
   
   // ✅ 올바른 방법
   initialize()  // initialize 요청
   readResponse()  // initialize 응답 읽기
   sendInitializedNotification()  // initialized 알림 전송
   callTool(...)  // 그 후 도구 호출
   ```

3. **요청 ID 관리 실패**
   ```kotlin
   // ❌ 잘못된 방법
   request["id"] = 1  // 항상 같은 ID 사용
   
   // ✅ 올바른 방법
   request["id"] = requestId++  // 각 요청마다 고유 ID
   ```

---

## 디버깅 팁

### 1. 로그 확인

MCP 서버의 로그를 확인하여 문제를 진단할 수 있습니다:

```kotlin
// stderr를 읽어서 로그 확인
val errorReader = BufferedReader(process.errorStream.reader(Charsets.UTF_8))
val errorLine = errorReader.readLine()
println("서버 로그: $errorLine")
```

### 2. 요청/응답 로깅

각 요청과 응답을 로그로 출력하여 디버깅:

```kotlin
private fun sendRequest(request: Map<String, Any>) {
    val message = json.encodeToString(...) + "\n"
    println("전송: $message")  // 디버깅용
    process?.outputStream?.writer()?.write(message)
}

private fun readResponse(): Map<String, Any> {
    val line = process?.inputStream?.bufferedReader()?.readLine()
    println("수신: $line")  // 디버깅용
    return json.decodeFromString(line)
}
```

### 3. 타임아웃 설정

응답이 없을 경우를 대비하여 타임아웃을 설정:

```kotlin
// 예: 30초 타임아웃
val future = CompletableFuture.supplyAsync {
    process?.inputStream?.bufferedReader()?.readLine()
}
val response = future.get(30, TimeUnit.SECONDS)
```

---

## 문제 해결

### 문제 1: "Received request before initialization was complete"

**원인**: 초기화 순서를 따르지 않음

**해결**:
1. `initialize` 요청을 먼저 전송
2. `initialize` 응답을 수신하고 확인
3. `notifications/initialized` 알림을 전송
4. 그 후에 `tools/call` 요청

### 문제 2: "Invalid request parameters"

**원인**: 요청 형식이 잘못됨

**해결**:
1. JSON-RPC 2.0 형식 준수 확인
2. 필수 필드 (`jsonrpc`, `id`, `method`, `params`) 포함 확인
3. 요청 ID가 고유한지 확인

### 문제 3: 프로세스가 시작되지 않음

**원인**: Python 경로 또는 스크립트 경로 오류

**해결**:
1. Python이 설치되어 있는지 확인: `python --version`
2. 스크립트 경로가 올바른지 확인
3. 실행 권한 확인 (Linux/Mac)

### 문제 4: 인코딩 오류

**원인**: UTF-8 인코딩이 설정되지 않음

**해결**:
```kotlin
ProcessBuilder("python", scriptPath).apply {
    environment()["PYTHONIOENCODING"] = "utf-8"
}
```

---

## 요약

1. ✅ **초기화 순서 준수**: `initialize` → `initialized` → `tools/call`
2. ✅ **환경 변수 설정**: `DB_FILE`, `MCP_LOG_FILE`
3. ✅ **요청 ID 관리**: 각 요청마다 고유 ID 사용
4. ✅ **에러 처리**: 모든 단계에서 에러 처리 구현
5. ✅ **로깅**: 디버깅을 위한 요청/응답 로깅

이 가이드를 따라 구현하면 STDIO 방식으로 MCP 서버에 정상적으로 연결할 수 있습니다.

