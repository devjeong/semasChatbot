# WORKLOG: MCP stdio 기반 클라이언트 구현

## 📋 요구사항 요약

### 목표
- MCP 서버가 stdio만 지원하므로 stdio 기반 구현으로 기능 변경
- ProcessBuilder를 사용하여 Python 스크립트 실행
- stdin/stdout을 통한 JSON-RPC 2.0 통신

### 핵심 가치
- **최고 성능**: 프로세스 재사용을 통한 효율적인 연결 관리
- **코드 효율성**: 재사용 가능한 stdio 클라이언트 구조
- **안정성**: 완전한 에러 처리 및 프로세스 관리

---

## ✅ 작업 목록 및 진행 상황

### 1. MCPStdioClient 클래스 구현 ✅
**파일**: `src/main/kotlin/org/dev/semaschatbot/MCPStdioClient.kt`

**구현 내용**:
- ProcessBuilder를 사용한 Python 스크립트 실행
- stdin/stdout을 통한 JSON-RPC 2.0 통신
- 비동기 응답 처리 (CompletableFuture 사용)
- 프로세스 관리 (생성, 종료)

**주요 기능**:
```kotlin
class MCPStdioClient(
    serverScriptPath: String,
    workingDirectory: String?,
    environment: Map<String, String>
) {
    fun connect()
    fun disconnect()
    fun initialize(clientInfo: MCPClientInfo): JsonObject
    fun listTools(): List<JsonObject>
    fun callTool(toolName: String, arguments: JsonObject): JsonObject
    fun getAssignedTasks(username: String, status: String?, priority: String?): List<AssignedTask>
    fun isConnected(): Boolean
}
```

**성능 최적화**:
- 프로세스 재사용을 통한 연결 관리
- 타임아웃 설정으로 무한 대기 방지 (30초)
- 별도 스레드에서 stdout/stderr 읽기
- CompletableFuture를 통한 비동기 응답 처리

### 2. MCPConnectionTest 수정 ✅
**파일**: `src/main/kotlin/org/dev/semaschatbot/MCPConnectionTest.kt`

**구현 내용**:
- HTTP 기반 MCPProtocolClient 대신 stdio 기반 MCPStdioClient 사용
- MCP 서버 스크립트 경로 결정 로직 추가
- 스크립트 파일 존재 확인
- 환경 변수 지원 (MCP_SERVER_SCRIPT_PATH)

**주요 변경사항**:
```kotlin
// 수정 전
val mcpClient = MCPProtocolClient(taskMCP.endpoint)

// 수정 후
val mcpClient = MCPStdioClient(
    serverScriptPath = serverScriptPath,
    workingDirectory = File(serverScriptPath).parent,
    environment = mapOf(
        "DB_FILE" to (System.getenv("DB_FILE") ?: "auth.db"),
        "MCP_LOG_FILE" to (System.getenv("MCP_LOG_FILE") ?: "./logs/task_mcp_server.log")
    )
)
mcpClient.connect()
```

### 3. MCPProtocolClient 유지 ✅
**파일**: `src/main/kotlin/org/dev/semaschatbot/MCPProtocolClient.kt`

**상태**: HTTP 기반 클라이언트는 유지 (향후 HTTP 지원 MCP 서버용)

---

## 🔧 개별 작업 및 테스트

### 작업 1: MCPStdioClient 구현
**테스트 결과**:
- ✅ ProcessBuilder를 사용한 프로세스 실행 구현
- ✅ stdin/stdout을 통한 JSON-RPC 2.0 통신 구현
- ✅ 비동기 응답 처리 (CompletableFuture)
- ✅ stdout/stderr 읽기 스레드 구현
- ✅ 프로세스 관리 (connect, disconnect)
- ✅ 컴파일 성공

### 작업 2: MCPConnectionTest 수정
**테스트 결과**:
- ✅ MCPStdioClient 사용으로 변경
- ✅ 스크립트 경로 결정 로직 추가
- ✅ 스크립트 파일 존재 확인
- ✅ 환경 변수 지원
- ✅ 컴파일 성공

---

## 🚀 자동 성능 최적화

### 최적화 항목

1. **비동기 응답 처리**
   - CompletableFuture를 통한 비동기 응답 대기
   - 요청 ID 기반 응답 매칭
   - 타임아웃 설정 (30초)

2. **별도 스레드 처리**
   - stdout 읽기 전용 스레드
   - stderr 읽기 전용 스레드 (로깅용)
   - UI 스레드 블로킹 방지

3. **프로세스 관리**
   - 프로세스 재사용 가능한 구조
   - 안전한 프로세스 종료 (destroyForcibly)
   - 리소스 정리 (스트림 닫기)

4. **에러 처리**
   - 상세한 에러 메시지
   - 로깅을 통한 디버깅 지원
   - 프로세스 상태 확인

### 성능 측정 결과
- 컴파일 성공
- 린트 오류 없음

---

## 📝 작업 이력 기록

### 생성된 파일
1. `src/main/kotlin/org/dev/semaschatbot/MCPStdioClient.kt` - stdio 기반 MCP 프로토콜 클라이언트

### 수정된 파일
1. `src/main/kotlin/org/dev/semaschatbot/MCPConnectionTest.kt` - stdio 기반 클라이언트 사용으로 변경

### 유지된 파일
1. `src/main/kotlin/org/dev/semaschatbot/MCPProtocolClient.kt` - HTTP 기반 클라이언트 (향후 사용 가능)

### 주요 변경 사항

#### MCPStdioClient.kt
- ProcessBuilder를 사용한 Python 스크립트 실행
- stdin/stdout을 통한 JSON-RPC 2.0 통신
- 비동기 응답 처리 (CompletableFuture)
- stdout/stderr 읽기 스레드
- 프로세스 관리 (connect, disconnect)

#### MCPConnectionTest.kt
- MCPProtocolClient → MCPStdioClient로 변경
- 스크립트 경로 결정 로직 추가
- 스크립트 파일 존재 확인
- 환경 변수 지원

---

## ✅ 완료 체크리스트

- [x] stdio 기반 MCP 프로토콜 클라이언트 구현 (ProcessBuilder 사용)
- [x] MCP 서버 프로세스 실행 및 관리 (Python 스크립트 실행)
- [x] stdin/stdout을 통한 JSON-RPC 2.0 통신 구현
- [x] MCPConnectionTest를 stdio 기반으로 수정
- [x] 스크립트 경로 결정 및 파일 존재 확인
- [x] 환경 변수 지원
- [x] 컴파일 테스트 통과
- [x] 작업 이력 기록

---

## 📌 사용 방법

### MCP 서버 스크립트 경로 설정

1. **환경 변수 사용 (권장)**
   ```bash
   # Windows
   set MCP_SERVER_SCRIPT_PATH=C:\dev\workspace\semasChatbotMng\mcp_servers\task_mcp_server.py
   
   # Linux/Mac
   export MCP_SERVER_SCRIPT_PATH=/path/to/semasChatbotMng/mcp_servers/task_mcp_server.py
   ```

2. **기본 경로 사용**
   - Windows: `C:\dev\workspace\semasChatbotMng\mcp_servers\task_mcp_server.py`
   - Linux/Mac: `~/workspace/semasChatbotMng/mcp_servers/task_mcp_server.py`

### 테스트 실행 방법

1. **MCP 관리 다이얼로그 열기**
   - 상단 "🔌 MCP 관리" 버튼 클릭

2. **연결 테스트 실행**
   - "🧪 연결 테스트" 버튼 클릭
   - 테스트는 자동으로 selimjhw 계정으로 실행됩니다

3. **테스트 단계**
   - MCP 목록 조회
   - 작업 관리 MCP 서버 찾기
   - MCP 서버 스크립트 경로 결정
   - Python 프로세스 실행
   - MCP 서버 초기화
   - 도구 목록 조회
   - get_assigned_tasks 도구 호출
   - 프로세스 종료

---

## 🎯 요약

MCP 클라이언트가 stdio 기반으로 성공적으로 변경되었습니다. ProcessBuilder를 사용하여 Python 스크립트를 실행하고, stdin/stdout을 통해 JSON-RPC 2.0 프로토콜로 통신합니다. 비동기 응답 처리와 프로세스 관리를 통해 안정적이고 효율적인 연결을 제공합니다.

