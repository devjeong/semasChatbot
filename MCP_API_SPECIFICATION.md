# MCP 관리 서버 API 명세서

## 📋 개요

이 문서는 MCP(Model Context Protocol) 관리 기능을 위한 서버 API 명세서입니다.
서버 주소: `http://192.168.18.53:5000`

---

## 1. MCP 목록 조회 API

### 1.1 엔드포인트
```
GET /api/mcp/list
```

### 1.2 요청

#### 요청 헤더
```
Content-Type: application/json
```

#### 요청 파라미터
없음

#### 요청 예시
```http
GET http://192.168.18.53:5000/api/mcp/list HTTP/1.1
Content-Type: application/json
```

### 1.3 응답

#### 성공 응답 (200 OK)

**응답 본문**:
```json
{
  "success": true,
  "data": [
    {
      "id": "mcp-server-1",
      "name": "MCP 서버 1",
      "description": "서버 설명 텍스트",
      "endpoint": "http://example.com:3000",
      "type": "HTTP",
      "status": "available"
    },
    {
      "id": "mcp-server-2",
      "name": "MCP 서버 2",
      "description": "다른 서버 설명",
      "endpoint": "http://example2.com:3000",
      "type": "STDIO",
      "status": "available"
    }
  ]
}
```

**응답 필드 설명**:
- `success` (boolean): 요청 성공 여부
- `data` (array): MCP 서버 목록 배열
  - `id` (string, required): MCP 서버 고유 ID
  - `name` (string, required): MCP 서버 이름
  - `description` (string, optional): MCP 서버 설명
  - `endpoint` (string, required): MCP 서버 엔드포인트 URL
  - `type` (string, required): 연결 타입 (HTTP, STDIO, SSE 등)
  - `status` (string, required): 서버 상태 (available, unavailable, maintenance 등)

#### 실패 응답

**400 Bad Request**:
```json
{
  "success": false,
  "error": "Invalid request"
}
```

**500 Internal Server Error**:
```json
{
  "success": false,
  "error": "Internal server error"
}
```

### 1.4 에러 코드
- `INVALID_REQUEST`: 잘못된 요청
- `SERVER_ERROR`: 서버 내부 오류
- `DATABASE_ERROR`: 데이터베이스 오류

---

## 2. MCP 연결 정보 전송 API

### 2.1 엔드포인트
```
POST /api/mcp/connect
```

### 2.2 요청

#### 요청 헤더
```
Content-Type: application/json
```

#### 요청 본문 (연결 시)

**스키마**:
```json
{
  "userId": "string (required)",
  "username": "string (required)",
  "ipAddress": "string (required)",
  "connectedAt": "string (ISO 8601 format, required)",
  "mcpId": "string (required)",
  "mcpName": "string (required)",
  "mcpEndpoint": "string (required)",
  "action": "string (required, 'connect' or 'disconnect')"
}
```

**요청 예시**:
```json
{
  "userId": "user123",
  "username": "홍길동",
  "ipAddress": "192.168.1.100",
  "connectedAt": "2024-01-01T12:00:00Z",
  "mcpId": "mcp-server-1",
  "mcpName": "MCP 서버 1",
  "mcpEndpoint": "http://example.com:3000",
  "action": "connect"
}
```

#### 요청 본문 (연결 해제 시)

**요청 예시**:
```json
{
  "userId": "user123",
  "username": "홍길동",
  "ipAddress": "192.168.1.100",
  "disconnectedAt": "2024-01-01T12:05:00Z",
  "mcpId": "mcp-server-1",
  "mcpName": "MCP 서버 1",
  "action": "disconnect"
}
```

**참고**: 연결 해제 시 `disconnectedAt` 필드를 사용하며, `connectedAt` 대신 `disconnectedAt`을 전송합니다.

### 2.3 응답

#### 성공 응답 (200 OK)

**응답 본문**:
```json
{
  "success": true,
  "message": "Connection info saved successfully"
}
```

**응답 필드 설명**:
- `success` (boolean): 요청 성공 여부
- `message` (string): 성공 메시지

#### 실패 응답

**400 Bad Request** (필수 필드 누락):
```json
{
  "success": false,
  "error": "Missing required field: userId"
}
```

**400 Bad Request** (잘못된 날짜 형식):
```json
{
  "success": false,
  "error": "Invalid date format. Expected ISO 8601 format"
}
```

**500 Internal Server Error**:
```json
{
  "success": false,
  "error": "Failed to save connection info"
}
```

### 2.4 에러 코드
- `MISSING_FIELD`: 필수 필드 누락
- `INVALID_DATE_FORMAT`: 잘못된 날짜 형식
- `INVALID_ACTION`: 잘못된 action 값 (connect/disconnect 외)
- `DATABASE_ERROR`: 데이터베이스 저장 오류
- `SERVER_ERROR`: 서버 내부 오류

---

## 3. 데이터베이스 스키마 (참고)

### 3.1 MCP 연결 이력 테이블

서버에서 MCP 연결 정보를 저장하기 위한 테이블 스키마 예시:

```sql
CREATE TABLE mcp_connection_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(100) NOT NULL,
    username VARCHAR(100) NOT NULL,
    ip_address VARCHAR(45) NOT NULL,
    mcp_id VARCHAR(100) NOT NULL,
    mcp_name VARCHAR(200) NOT NULL,
    mcp_endpoint VARCHAR(500) NOT NULL,
    action VARCHAR(20) NOT NULL,  -- 'connect' or 'disconnect'
    connected_at DATETIME,
    disconnected_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_mcp_id (mcp_id),
    INDEX idx_created_at (created_at)
);
```

### 3.2 MCP 서버 목록 테이블 (선택사항)

서버에서 MCP 서버 목록을 관리하는 경우:

```sql
CREATE TABLE mcp_servers (
    id VARCHAR(100) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    endpoint VARCHAR(500) NOT NULL,
    type VARCHAR(50) NOT NULL,  -- 'HTTP', 'STDIO', 'SSE'
    status VARCHAR(50) NOT NULL DEFAULT 'available',  -- 'available', 'unavailable', 'maintenance'
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_status (status)
);
```

---

## 4. 구현 가이드

### 4.1 날짜/시간 형식

모든 날짜/시간 필드는 ISO 8601 형식을 사용합니다:
- 형식: `YYYY-MM-DDTHH:mm:ssZ`
- 예시: `2024-01-01T12:00:00Z`
- 타임존: UTC 권장

### 4.2 IP 주소 형식

- IPv4: `192.168.1.100`
- IPv6: `2001:0db8:85a3:0000:0000:8a2e:0370:7334`
- 클라이언트에서 로컬 IP 주소를 조회하여 전송

### 4.3 사용자 인증

현재 API는 사용자 인증을 요구하지 않지만, 향후 확장을 위해 다음을 고려할 수 있습니다:
- JWT 토큰 기반 인증
- API 키 기반 인증
- 세션 기반 인증

### 4.4 에러 처리

모든 API는 일관된 에러 응답 형식을 사용합니다:
```json
{
  "success": false,
  "error": "Error message"
}
```

### 4.5 로깅

서버 측에서 다음 정보를 로깅하는 것을 권장합니다:
- 요청 시간
- 요청 IP
- 요청 URL 및 메서드
- 요청 본문 (민감 정보 제외)
- 응답 상태 코드
- 처리 시간

---

## 5. 테스트 시나리오

### 5.1 MCP 목록 조회 테스트

**정상 케이스**:
1. GET /api/mcp/list 요청
2. 200 OK 응답 확인
3. 응답 본문의 success가 true인지 확인
4. data 배열에 MCP 서버 목록이 포함되어 있는지 확인

**에러 케이스**:
1. 서버 오류 시 500 응답 확인
2. 에러 메시지가 포함되어 있는지 확인

### 5.2 MCP 연결 정보 전송 테스트

**정상 케이스 (연결)**:
1. POST /api/mcp/connect 요청 (action: "connect")
2. 필수 필드 모두 포함
3. 200 OK 응답 확인
4. 데이터베이스에 저장되었는지 확인

**정상 케이스 (연결 해제)**:
1. POST /api/mcp/connect 요청 (action: "disconnect")
2. disconnectedAt 필드 포함
3. 200 OK 응답 확인
4. 데이터베이스에 업데이트되었는지 확인

**에러 케이스**:
1. 필수 필드 누락 시 400 응답 확인
2. 잘못된 날짜 형식 시 400 응답 확인
3. 잘못된 action 값 시 400 응답 확인
4. 서버 오류 시 500 응답 확인

---

## 6. 보안 고려사항

### 6.1 입력 검증
- 모든 입력 필드에 대한 검증 수행
- SQL Injection 방지
- XSS 방지
- 날짜 형식 검증

### 6.2 데이터 보호
- 사용자 정보 암호화 저장 (선택사항)
- IP 주소 마스킹 처리 (선택사항)
- 접근 로그 기록

### 6.3 Rate Limiting
- API 호출 빈도 제한 고려
- DDoS 공격 방지

---

## 7. 향후 확장 계획

### 7.1 추가 API
- MCP 연결 통계 조회 API
- 사용자별 MCP 연결 이력 조회 API
- MCP 서버 상태 모니터링 API

### 7.2 기능 확장
- 실시간 연결 상태 알림 (WebSocket)
- 연결 품질 모니터링
- 자동 재연결 기능

---

## 부록: API 테스트 예시

### cURL 예시

**MCP 목록 조회**:
```bash
curl -X GET http://192.168.18.53:5000/api/mcp/list \
  -H "Content-Type: application/json"
```

**MCP 연결 정보 전송**:
```bash
curl -X POST http://192.168.18.53:5000/api/mcp/connect \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "user123",
    "username": "홍길동",
    "ipAddress": "192.168.1.100",
    "connectedAt": "2024-01-01T12:00:00Z",
    "mcpId": "mcp-server-1",
    "mcpName": "MCP 서버 1",
    "mcpEndpoint": "http://example.com:3000",
    "action": "connect"
  }'
```

### Postman 예시

**Collection 설정**:
- Base URL: `http://192.168.18.53:5000`
- Headers: `Content-Type: application/json`

**요청 예시**:
1. GET `/api/mcp/list`
2. POST `/api/mcp/connect` (Body: JSON)

