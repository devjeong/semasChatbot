# MCP 관리 기능 요구사항 명세서

## 📋 요구사항 요약

### 목표
- 로그 버튼 옆에 'MCP 관리' 버튼 추가
- MCP(Model Context Protocol) 기능 활성/비활성 토글 기능 제공
- 서버 API(192.168.18.53:5000)를 통해 사용 가능한 MCP 목록 조회
- MCP 목록 중 원하는 MCP를 ON/OFF로 설정하여 연결/연결 해제
- 연결 시 계정정보, IP, 연결 시간, 연결한 MCP 정보를 서버로 전달

### 핵심 가치
- **최고 성능**: MCP 연결 관리를 효율적으로 처리
- **코드 효율성**: 재사용 가능한 구조로 설계
- **안정성**: 연결 오류 처리 및 검증 로직 포함

---

## 1. 기능 요구사항

### 1.1 MCP 관리 버튼 추가
- **위치**: 로그 버튼 옆 (우측 버튼 패널)
- **스타일**: 기존 버튼과 동일한 스타일 유지
- **아이콘**: 🔌 MCP 관리
- **색상**: Color(52, 152, 219) (기존 전송 버튼과 유사한 파란색)

### 1.2 MCP 기능 활성/비활성 토글
- **전역 MCP 기능 활성화/비활성화 토글**
- MCP 기능이 비활성화된 경우 모든 MCP 연결 비활성화
- 활성화 상태는 영구 저장 (애플리케이션 재시작 시 유지)
- 활성화 상태 표시: 버튼 텍스트 또는 아이콘으로 표시

### 1.3 MCP 목록 API 조회
- **API 엔드포인트**: `GET http://192.168.18.53:5000/api/mcp/list`
- **호출 시점**: 
  - MCP 관리 다이얼로그 열 때 (MCP 기능이 활성화된 경우)
  - 새로고침 버튼 클릭 시
- **응답 형식**: JSON 배열
- **응답 데이터 구조**:
  ```json
  [
    {
      "id": "mcp-server-1",
      "name": "MCP 서버 1",
      "description": "서버 설명",
      "endpoint": "http://example.com:3000",
      "type": "HTTP",
      "status": "available"
    }
  ]
  ```
- **에러 처리**: API 호출 실패 시 오류 메시지 표시 및 재시도 옵션 제공

### 1.4 MCP 연결/연결 해제 (ON/OFF 토글)
- **개별 MCP ON/OFF 토글 기능**
- 각 MCP 항목에 토글 스위치 또는 ON/OFF 버튼 제공
- ON 상태: MCP 서버에 연결
- OFF 상태: MCP 서버 연결 해제
- 연결 상태 실시간 표시
- 연결 실패 시 오류 메시지 표시 및 자동 OFF 처리

### 1.5 MCP 연결 정보 서버 전송 API
- **API 엔드포인트**: `POST http://192.168.18.53:5000/api/mcp/connect`
- **호출 시점**: MCP 연결 시 (ON 상태로 변경 시)
- **전송 데이터**:
  ```json
  {
    "userId": "사용자 계정 ID",
    "username": "사용자 이름",
    "ipAddress": "클라이언트 IP 주소",
    "connectedAt": "2024-01-01T12:00:00Z",
    "mcpId": "mcp-server-1",
    "mcpName": "MCP 서버 1",
    "mcpEndpoint": "http://example.com:3000",
    "action": "connect"
  }
  ```
- **연결 해제 시 전송**:
  ```json
  {
    "userId": "사용자 계정 ID",
    "username": "사용자 이름",
    "ipAddress": "클라이언트 IP 주소",
    "disconnectedAt": "2024-01-01T12:05:00Z",
    "mcpId": "mcp-server-1",
    "mcpName": "MCP 서버 1",
    "action": "disconnect"
  }
  ```
- **에러 처리**: API 전송 실패 시 로그 기록 및 사용자에게 알림 (연결은 유지)

### 1.6 설정 영구 저장
- MCP 기능 활성/비활성 상태 저장
- 각 MCP의 ON/OFF 상태 저장
- 애플리케이션 재시작 시 설정 자동 로드
- 설정 파일 형식: JSON
- 저장 위치: 프로젝트 설정 디렉토리 또는 사용자 홈 디렉토리

---

## 2. 비기능 요구사항

### 2.1 성능 요구사항
- MCP 목록 API 호출 시간: 2초 이하
- MCP 연결/연결 해제 응답 시간: 5초 이하
- 연결 정보 전송 API 호출: 비동기 처리 (UI 블로킹 방지)
- UI 반응성: 버튼 클릭 후 즉시 피드백 (100ms 이내)

### 2.2 안정성 요구사항
- API 호출 실패 시 예외 처리 및 사용자 친화적 오류 메시지
- 네트워크 타임아웃 처리 (기본 10초)
- API 응답 형식 검증 및 오류 처리
- 연결 정보 전송 실패 시 재시도 로직 (선택사항)
- 동시 연결 수 제한 (서버 정책에 따름)

### 2.3 사용성 요구사항
- 직관적인 UI/UX 제공
- 기존 플러그인 UI 스타일과 일관성 유지
- 키보드 단축키 지원 (선택사항)
- 도움말/툴팁 제공

### 2.4 확장성 요구사항
- 향후 MCP 서버 타입 확장 가능한 구조
- 플러그인 형태로 MCP 서버 추가 가능 (선택사항)
- 다양한 연결 프로토콜 지원 (stdio, HTTP, SSE 등)

---

## 3. 화면 명세서

### 3.1 메인 화면 - 버튼 추가

#### 3.1.1 버튼 위치 및 스타일
```
[기존 버튼들...] [📋 로그] [🔌 MCP 관리]
                                    ↑
                              새로 추가될 버튼
```

**구현 위치**: `LLMChatToolWindowFactory.kt`
- 파일: `src/main/kotlin/org/dev/semaschatbot/LLMChatToolWindowFactory.kt`
- 라인: 140번째 줄 근처 (로그 버튼 추가 위치)
- 패널: `rightButtonPanel` (우측 버튼 패널)

**버튼 스타일**:
```kotlin
val mcpButton = createStyledButton("🔌 MCP 관리", Color(52, 152, 219), Color.WHITE)
```

### 3.2 MCP 관리 다이얼로그 화면

#### 3.2.1 전체 레이아웃
```
┌─────────────────────────────────────────────────────────┐
│  MCP 관리                                        [X]   │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  MCP 기능:  [ON/OFF 토글 스위치]  활성화됨              │
│                                                         │
│  [🔄 새로고침]                                         │
│                                                         │
│  ┌───────────────────────────────────────────────────┐ │
│  │ 사용 가능한 MCP 목록                               │ │
│  ├───────────────────────────────────────────────────┤ │
│  │ [ON] MCP 서버 1                    [연결됨]         │ │
│  │      설명: 서버 설명 텍스트                         │ │
│  │      엔드포인트: http://example.com:3000           │ │
│  │                                                    │ │
│  │ [OFF] MCP 서버 2                   [연결 안 됨]     │ │
│  │       설명: 서버 설명 텍스트                        │ │
│  │       엔드포인트: http://example2.com:3000         │ │
│  │                                                    │ │
│  │ [ON] MCP 서버 3                    [연결됨]         │ │
│  │      설명: 서버 설명 텍스트                         │ │
│  │      엔드포인트: http://example3.com:3000          │ │
│  └───────────────────────────────────────────────────┘ │
│                                                         │
│  [닫기]                                                │
└─────────────────────────────────────────────────────────┘
```

#### 3.2.2 화면 구성 요소

**상단 영역**:
- 다이얼로그 제목: "MCP 관리"
- MCP 기능 활성/비활성 토글 스위치
  - ON 상태: "활성화됨" 텍스트 표시
  - OFF 상태: "비활성화됨" 텍스트 표시 (회색)
  - OFF 상태일 때 MCP 목록 비활성화

**중앙 영역 - MCP 목록**:
- 새로고침 버튼: MCP 목록 API 재호출
- 각 MCP 항목 구성:
  1. ON/OFF 토글 스위치 (각 MCP별)
  2. MCP 이름 (볼드체)
  3. 설명 (작은 글씨, 회색)
  4. 엔드포인트 정보
  5. 연결 상태 표시 (연결됨/연결 안 됨)
- MCP 기능이 비활성화된 경우 목록 비활성화 (회색 처리)

**하단 영역 - 액션 버튼**:
- 닫기: 다이얼로그 닫기

### 3.3 로딩 및 오류 상태 화면

#### 3.3.1 MCP 목록 로딩 중
```
┌─────────────────────────────────────────────────────────┐
│  MCP 관리                                        [X]   │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  MCP 기능:  [ON/OFF 토글 스위치]  활성화됨              │
│                                                         │
│  [🔄 새로고침]                                         │
│                                                         │
│  ┌───────────────────────────────────────────────────┐ │
│  │                                                    │ │
│  │         MCP 목록을 불러오는 중...                  │ │
│  │              [로딩 인디케이터]                      │ │
│  │                                                    │ │
│  └───────────────────────────────────────────────────┘ │
│                                                         │
│  [닫기]                                                │
└─────────────────────────────────────────────────────────┘
```

#### 3.3.2 MCP 목록 없음
```
┌─────────────────────────────────────────────────────────┐
│  MCP 관리                                        [X]   │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  MCP 기능:  [ON/OFF 토글 스위치]  활성화됨              │
│                                                         │
│  [🔄 새로고침]                                         │
│                                                         │
│  ┌───────────────────────────────────────────────────┐ │
│  │                                                    │ │
│  │         사용 가능한 MCP가 없습니다.                │ │
│  │                                                    │ │
│  └───────────────────────────────────────────────────┘ │
│                                                         │
│  [닫기]                                                │
└─────────────────────────────────────────────────────────┘
```

#### 3.3.3 API 오류 상태
```
┌─────────────────────────────────────────────────────────┐
│  MCP 관리                                        [X]   │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  MCP 기능:  [ON/OFF 토글 스위치]  활성화됨              │
│                                                         │
│  [🔄 새로고침]                                         │
│                                                         │
│  ┌───────────────────────────────────────────────────┐ │
│  │                                                    │ │
│  │  ❌ MCP 목록을 불러오는데 실패했습니다.            │ │
│  │                                                    │ │
│  │  오류: Connection timeout                          │ │
│  │                                                    │ │
│  │  [재시도]                                          │ │
│  │                                                    │ │
│  └───────────────────────────────────────────────────┘ │
│                                                         │
│  [닫기]                                                │
└─────────────────────────────────────────────────────────┘
```

### 3.4 연결 상태 표시

#### 3.4.1 토글 스위치 상태
- **ON 상태**: 
  - 토글 스위치가 오른쪽으로 이동
  - 배경색: 파란색 또는 초록색
  - 연결 상태 텍스트: "연결됨" (초록색)
- **OFF 상태**:
  - 토글 스위치가 왼쪽으로 이동
  - 배경색: 회색
  - 연결 상태 텍스트: "연결 안 됨" (회색)

#### 3.4.2 연결 중 상태
- 토글 스위치 비활성화
- 연결 상태 텍스트: "연결 중..." (파란색)
- 로딩 인디케이터 표시

#### 3.4.3 연결 오류 상태
- 토글 스위치 자동으로 OFF로 변경
- 연결 상태 텍스트: "연결 실패: [오류 메시지]" (빨간색)
- 오류 메시지 툴팁 표시

### 3.5 오류 메시지 다이얼로그

#### 3.5.1 API 호출 오류
```
┌─────────────────────────────────────┐
│  오류                                │
├─────────────────────────────────────┤
│  MCP 목록을 불러오는데 실패했습니다. │
│                                     │
│  오류: Connection timeout           │
│                                     │
│  [재시도]  [확인]                    │
└─────────────────────────────────────┘
```

#### 3.5.2 MCP 연결 오류
```
┌─────────────────────────────────────┐
│  연결 실패                           │
├─────────────────────────────────────┤
│  MCP 서버에 연결할 수 없습니다.       │
│                                     │
│  MCP: MCP 서버 1                     │
│  오류: Connection refused            │
│                                     │
│              [확인]                  │
└─────────────────────────────────────┘
```

#### 3.5.3 연결 정보 전송 오류 (경고)
```
┌─────────────────────────────────────┐
│  경고                                │
├─────────────────────────────────────┤
│  연결 정보를 서버로 전송하지         │
│  못했습니다. 연결은 정상적으로       │
│  유지됩니다.                         │
│                                     │
│              [확인]                  │
└─────────────────────────────────────┘
```

---

## 4. 데이터 모델

### 4.1 MCP 목록 API 응답 모델
```kotlin
data class MCPListItem(
    val id: String,              // MCP 고유 ID
    val name: String,            // MCP 이름
    val description: String? = null,  // MCP 설명
    val endpoint: String,        // MCP 엔드포인트 URL
    val type: String,            // 연결 타입 (HTTP, STDIO, SSE 등)
    val status: String          // 상태 (available, unavailable 등)
)
```

### 4.2 MCP 연결 정보 클래스
```kotlin
data class MCPConnection(
    val mcpId: String,           // MCP ID
    val mcpName: String,        // MCP 이름
    val mcpEndpoint: String,     // MCP 엔드포인트
    val isConnected: Boolean = false,  // 연결 상태
    val connectedAt: Long? = null      // 연결 시간 (타임스탬프)
)
```

### 4.3 MCP 연결 정보 전송 모델
```kotlin
data class MCPConnectionInfo(
    val userId: String,          // 사용자 계정 ID
    val username: String,        // 사용자 이름
    val ipAddress: String,       // 클라이언트 IP 주소
    val connectedAt: String,     // 연결 시간 (ISO 8601 형식)
    val mcpId: String,          // MCP ID
    val mcpName: String,        // MCP 이름
    val mcpEndpoint: String,    // MCP 엔드포인트
    val action: String          // "connect" 또는 "disconnect"
)

data class MCPDisconnectionInfo(
    val userId: String,
    val username: String,
    val ipAddress: String,
    val disconnectedAt: String,
    val mcpId: String,
    val mcpName: String,
    val action: String = "disconnect"
)
```

### 4.4 설정 저장 형식 (JSON 예시)
```json
{
  "mcpEnabled": true,
  "mcpConnections": [
    {
      "mcpId": "mcp-server-1",
      "mcpName": "MCP 서버 1",
      "mcpEndpoint": "http://example.com:3000",
      "isConnected": true,
      "connectedAt": 1704067200000
    },
    {
      "mcpId": "mcp-server-2",
      "mcpName": "MCP 서버 2",
      "mcpEndpoint": "http://example2.com:3000",
      "isConnected": false,
      "connectedAt": null
    }
  ]
}
```

---

## 5. 구현 우선순위

### Phase 1: 기본 기능 (필수)
1. ✅ MCP 관리 버튼 추가
2. ✅ MCP 기능 활성/비활성 토글 기능
3. ✅ MCP 관리 다이얼로그 기본 UI
4. ✅ MCP 목록 API 클라이언트 구현
5. ✅ MCP 목록 조회 및 표시 기능

### Phase 2: 연결 기능 (필수)
6. ✅ MCP ON/OFF 토글 기능
7. ✅ MCP 연결/연결 해제 기능
8. ✅ 연결 상태 표시
9. ✅ 연결 정보 전송 API 클라이언트 구현
10. ✅ 연결 정보 서버 전송 기능

### Phase 3: 설정 및 최적화 (필수)
11. ✅ 설정 영구 저장/로드 기능
12. ✅ 애플리케이션 재시작 시 설정 복원
13. ✅ 에러 처리 및 사용자 피드백

### Phase 4: 고급 기능 (선택)
14. ⬜ 연결 재시도 로직
15. ⬜ 연결 통계 및 모니터링
16. ⬜ MCP 리소스 조회 기능

---

## 6. 기술 스택 및 의존성

### 6.1 기존 기술 스택 활용
- **UI 프레임워크**: IntelliJ Platform UI (Swing 기반)
- **언어**: Kotlin
- **설정 저장**: JSON (Gson 라이브러리 활용)
- **네트워크 통신**: OkHttp (기존 코드베이스 활용)
- **API 클라이언트 패턴**: AuthApiClient, LmStudioStatsApiClient 참조

### 6.2 API 엔드포인트
- **서버 기본 URL**: `http://192.168.18.53:5000`
- **MCP 목록 조회**: `GET /api/mcp/list`
- **MCP 연결 정보 전송**: `POST /api/mcp/connect`
- **MCP 연결 해제 정보 전송**: `POST /api/mcp/disconnect` (또는 connect 엔드포인트에 action 필드로 구분)

### 6.3 추가 의존성
- JSON 파싱: Gson (이미 사용 중)
- IP 주소 조회: Java 표준 라이브러리 활용

---

## 7. 테스트 시나리오

### 7.1 기능 테스트
1. **MCP 기능 활성/비활성**
   - MCP 기능 활성화 토글 동작 확인
   - 비활성화 시 MCP 목록 비활성화 확인
   - 활성화 상태 영구 저장 확인

2. **MCP 목록 조회**
   - MCP 관리 다이얼로그 열 때 목록 자동 조회
   - 새로고침 버튼으로 목록 재조회
   - API 호출 실패 시 오류 처리 확인

3. **MCP 연결/연결 해제**
   - MCP ON 토글 시 연결 확인
   - MCP OFF 토글 시 연결 해제 확인
   - 연결 실패 시 자동 OFF 처리 확인
   - 연결 상태 실시간 표시 확인

4. **연결 정보 서버 전송**
   - 연결 시 서버로 정보 전송 확인
   - 연결 해제 시 서버로 정보 전송 확인
   - 전송 실패 시 로그 기록 확인

### 7.2 통합 테스트
1. **설정 영구 저장**
   - MCP 활성화 상태 및 연결 상태 저장
   - 애플리케이션 재시작 후 설정 복원 확인
   - 저장된 연결 상태가 정확히 복원되는지 확인

2. **API 연동 테스트**
   - 서버 API 응답 형식 검증
   - 네트워크 오류 처리 확인
   - 타임아웃 처리 확인

3. **사용자 인증 연동**
   - 로그인한 사용자 정보 활용 확인
   - 로그인하지 않은 경우 처리 확인

---

## 8. 성능 최적화 고려사항

### 8.1 비동기 처리
- MCP 목록 API 호출은 백그라운드 스레드에서 수행
- MCP 연결/연결 해제는 백그라운드 스레드에서 수행
- 연결 정보 전송 API는 비동기 처리 (UI 블로킹 방지)
- UI 스레드 블로킹 방지

### 8.2 API 호출 최적화
- MCP 목록 캐싱 (일정 시간 동안 재사용)
- 불필요한 API 호출 최소화
- 연결 정보 전송 실패 시 백그라운드 재시도 (선택사항)

### 8.3 설정 파일 캐싱
- 설정 파일 읽기 최소화
- 메모리 캐시 활용
- 변경 시에만 파일 쓰기

---

## 9. 보안 고려사항

### 9.1 사용자 인증 정보
- 로그인한 사용자 정보만 서버로 전송
- 사용자 ID 및 사용자명은 서버 인증 후에만 사용

### 9.2 IP 주소 수집
- 클라이언트 IP 주소는 Java 표준 라이브러리로 안전하게 조회
- 로컬 IP 주소 조회 (외부 IP는 서버에서 처리 가능)

### 9.3 API 통신 보안
- HTTPS 사용 권장 (서버 설정에 따름)
- API 응답 검증 및 오류 처리

---

## 10. API 스펙 상세

### 10.1 MCP 목록 조회 API
**엔드포인트**: `GET http://192.168.18.53:5000/api/mcp/list`

**요청 헤더**:
```
Content-Type: application/json
```

**응답 형식** (성공 시):
```json
{
  "success": true,
  "data": [
    {
      "id": "mcp-server-1",
      "name": "MCP 서버 1",
      "description": "서버 설명",
      "endpoint": "http://example.com:3000",
      "type": "HTTP",
      "status": "available"
    }
  ]
}
```

**응답 형식** (실패 시):
```json
{
  "success": false,
  "error": "Error message"
}
```

### 10.2 MCP 연결 정보 전송 API
**엔드포인트**: `POST http://192.168.18.53:5000/api/mcp/connect`

**요청 헤더**:
```
Content-Type: application/json
```

**요청 본문** (연결 시):
```json
{
  "userId": "user123",
  "username": "사용자명",
  "ipAddress": "192.168.1.100",
  "connectedAt": "2024-01-01T12:00:00Z",
  "mcpId": "mcp-server-1",
  "mcpName": "MCP 서버 1",
  "mcpEndpoint": "http://example.com:3000",
  "action": "connect"
}
```

**요청 본문** (연결 해제 시):
```json
{
  "userId": "user123",
  "username": "사용자명",
  "ipAddress": "192.168.1.100",
  "disconnectedAt": "2024-01-01T12:05:00Z",
  "mcpId": "mcp-server-1",
  "mcpName": "MCP 서버 1",
  "action": "disconnect"
}
```

**응답 형식** (성공 시):
```json
{
  "success": true,
  "message": "Connection info saved"
}
```

**응답 형식** (실패 시):
```json
{
  "success": false,
  "error": "Error message"
}
```

### 10.3 IP 주소 조회 방법
```kotlin
// 로컬 IP 주소 조회
fun getLocalIpAddress(): String {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (!address.isLoopbackAddress && address is Inet4Address) {
                    return address.hostAddress
                }
            }
        }
    } catch (e: Exception) {
        Logger.error("MCPManager", "IP 주소 조회 실패: ${e.message}")
    }
    return "unknown"
}
```

---

## 11. 향후 확장 계획

### 11.1 추가 기능
- 연결 자동 재시도 기능
- 연결 통계 및 모니터링
- MCP 리소스 조회 기능
- MCP 도구 목록 조회 기능

### 11.2 통합 기능
- 챗봇과 MCP 서버 연동
- MCP 리소스를 챗봇 컨텍스트로 활용
- MCP 도구를 챗봇 기능으로 노출

---

## 부록: 참고 자료

### A. MCP 프로토콜 스펙
- Model Context Protocol 공식 문서 참조
- MCP 서버 구현 가이드 참조

### B. 기존 코드 참조
- `LogViewerDialog.kt`: 다이얼로그 구현 패턴 참조
- `LLMChatToolWindowFactory.kt`: 버튼 추가 패턴 참조
- `ChatService.kt`: 설정 저장/로드 패턴 참조
- `AuthApiClient.kt`: API 클라이언트 구현 패턴 참조
- `LmStudioStatsApiClient.kt`: API 클라이언트 구현 패턴 참조
- `UserService.kt`: 사용자 정보 조회 패턴 참조

