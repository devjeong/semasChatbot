# 작업 관리 MCP 서버 연결 가이드

## 개요

작업 관리 MCP 서버는 클라이언트에게 할당된 작업 목록과 상세 정보를 제공하는 MCP(Model Context Protocol) 서버입니다. 이 서버를 통해 사용자는 자신에게 할당된 작업을 조회하고, 작업의 상세 정보를 확인할 수 있습니다.

## 기능

### 제공 도구 (Tools)

1. **get_assigned_tasks**: 할당된 작업 목록 조회
2. **get_task_detail**: 특정 작업의 상세 정보 조회

## 사전 요구사항

### 1. Python 환경

- Python 3.8 이상
- MCP SDK 설치 필요

```bash
pip install mcp
```

### 2. 데이터베이스

- SQLite 데이터베이스 파일 (`auth.db`)
- 데이터베이스 파일 경로는 환경 변수 `DB_FILE`로 지정 가능 (기본값: `auth.db`)

### 3. 프로젝트 구조

```
semasChatbotMng/
├── mcp_servers/
│   ├── task_mcp_server.py      # MCP 서버 메인 파일
│   ├── run_task_mcp.sh          # Linux/Mac 실행 스크립트
│   └── run_task_mcp.bat         # Windows 실행 스크립트
├── database.py                   # 데이터베이스 모듈
└── auth.db                      # SQLite 데이터베이스 파일
```

## 설치 및 설정

### 1. MCP 서버 파일 확인

MCP 서버 파일이 다음 위치에 있는지 확인합니다:

- `mcp_servers/task_mcp_server.py`
- `mcp_servers/run_task_mcp.sh` (Linux/Mac)
- `mcp_servers/run_task_mcp.bat` (Windows)

### 2. 환경 변수 설정 (선택사항)

```bash
# 데이터베이스 파일 경로 (기본값: auth.db)
export DB_FILE=auth.db

# 로그 파일 경로 (선택사항)
export MCP_LOG_FILE=./logs/task_mcp_server.log
```

### 3. 실행 권한 부여 (Linux/Mac)

```bash
chmod +x mcp_servers/run_task_mcp.sh
```

## MCP 클라이언트 연결 방법

### 1. Claude Desktop 설정

Claude Desktop에서 MCP 서버를 연결하려면 설정 파일을 수정합니다.

#### Windows 설정 파일 위치

```
%APPDATA%\Claude\claude_desktop_config.json
```

#### Mac 설정 파일 위치

```
~/Library/Application Support/Claude/claude_desktop_config.json
```

#### 설정 파일 예시

```json
{
  "mcpServers": {
    "task-management": {
      "command": "python",
      "args": [
        "C:/dev/workspace/semasChatbotMng/mcp_servers/task_mcp_server.py"
      ],
      "env": {
        "DB_FILE": "C:/dev/workspace/semasChatbotMng/auth.db",
        "MCP_LOG_FILE": "C:/dev/workspace/semasChatbotMng/logs/task_mcp_server.log"
      }
    }
  }
}
```

**Mac/Linux 예시:**

```json
{
  "mcpServers": {
    "task-management": {
      "command": "/path/to/semasChatbotMng/mcp_servers/run_task_mcp.sh",
      "env": {
        "DB_FILE": "/path/to/semasChatbotMng/auth.db",
        "MCP_LOG_FILE": "/path/to/semasChatbotMng/logs/task_mcp_server.log"
      }
    }
  }
}
```

### 2. 실행 스크립트 사용 (권장)

#### Linux/Mac

```bash
/path/to/semasChatbotMng/mcp_servers/run_task_mcp.sh
```

#### Windows

```cmd
C:\dev\workspace\semasChatbotMng\mcp_servers\run_task_mcp.bat
```

### 3. Python 직접 실행

```bash
cd /path/to/semasChatbotMng
python mcp_servers/task_mcp_server.py
```

## 도구 사용 방법

### 1. get_assigned_tasks - 작업 목록 조회

#### 설명

사용자에게 할당된 작업 목록을 조회합니다. 사용자 ID 또는 사용자명으로 자신에게 할당된 작업 목록을 가져올 수 있습니다.

#### 파라미터

| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| `user_id` | integer | 선택* | 사용자 ID (숫자). `user_id` 또는 `username` 중 하나는 필수입니다. |
| `username` | string | 선택* | 사용자명 (문자열). `user_id` 또는 `username` 중 하나는 필수입니다. |
| `status` | string | 선택 | 작업 상태 필터. `PENDING`, `IN_PROGRESS`, `REVIEW`, `COMPLETED`, `BLOCKED` 중 하나 |
| `priority` | string | 선택 | 우선순위 필터. `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` 중 하나 |

*`user_id` 또는 `username` 중 하나는 반드시 제공해야 합니다.

#### 사용 예시

**사용자명으로 조회:**

```json
{
  "name": "get_assigned_tasks",
  "arguments": {
    "username": "sellimjhw"
  }
}
```

**사용자 ID로 조회:**

```json
{
  "name": "get_assigned_tasks",
  "arguments": {
    "user_id": 1
  }
}
```

**상태 필터 적용:**

```json
{
  "name": "get_assigned_tasks",
  "arguments": {
    "username": "sellimjhw",
    "status": "IN_PROGRESS"
  }
}
```

**우선순위 필터 적용:**

```json
{
  "name": "get_assigned_tasks",
  "arguments": {
    "username": "sellimjhw",
    "priority": "HIGH"
  }
}
```

**복합 필터:**

```json
{
  "name": "get_assigned_tasks",
  "arguments": {
    "username": "sellimjhw",
    "status": "IN_PROGRESS",
    "priority": "HIGH"
  }
}
```

#### 응답 형식

**성공 응답:**

```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "requirement_id": 3,
      "requirement_title": "사용자 인증 시스템 구현",
      "task_number": 1,
      "title": "사용자 로그인 기능 구현",
      "description": "사용자 인증 시스템을 구현합니다...",
      "status": "IN_PROGRESS",
      "priority": "HIGH",
      "estimated_hours": 16.0,
      "actual_hours": null,
      "assignee_name": "홍길동",
      "start_date": "2024-01-01",
      "due_date": "2024-01-15",
      "completed_date": null,
      "created_at": "2024-01-01T10:00:00",
      "updated_at": "2024-01-05T14:30:00"
    }
  ],
  "count": 1
}
```

**실패 응답:**

```json
{
  "success": false,
  "error": "user_id 또는 username 중 하나는 필수입니다."
}
```

### 2. get_task_detail - 작업 상세 조회

#### 설명

특정 작업의 상세 정보를 조회합니다. 작업 ID를 사용하여 작업의 모든 상세 정보를 가져올 수 있습니다.

#### 파라미터

| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| `task_id` | integer | 필수 | 작업 ID |
| `user_id` | integer | 선택 | 사용자 ID (권한 확인용). `user_id` 또는 `username` 중 하나를 제공하면 해당 사용자가 담당자인지 확인합니다. |
| `username` | string | 선택 | 사용자명 (권한 확인용). `user_id` 또는 `username` 중 하나를 제공하면 해당 사용자가 담당자인지 확인합니다. |

#### 사용 예시

**기본 조회 (권한 확인 없음):**

```json
{
  "name": "get_task_detail",
  "arguments": {
    "task_id": 1
  }
}
```

**권한 확인 포함:**

```json
{
  "name": "get_task_detail",
  "arguments": {
    "task_id": 1,
    "username": "sellimjhw"
  }
}
```

#### 응답 형식

**성공 응답:**

```json
{
  "success": true,
  "data": {
    "id": 1,
    "requirement_id": 3,
    "requirement_title": "사용자 인증 시스템 구현",
    "task_number": 1,
    "title": "사용자 로그인 기능 구현",
    "description": "사용자 인증 시스템을 구현합니다.\n\n비기능적 요구사항: 로그인 응답 시간 2초 이내, 세션 타임아웃 30분, 비밀번호 암호화 필수\n기술적 제약사항: JWT 토큰 사용, HTTPS 통신 필수, SQL Injection 방지\n비즈니스 제약사항: 기존 사용자 데이터베이스와 호환 필요\n성공기준: 로그인 성공률 99% 이상, 보안 테스트 통과",
    "status": "IN_PROGRESS",
    "priority": "HIGH",
    "estimated_hours": 16.0,
    "actual_hours": null,
    "assignee_id": 1,
    "assignee_name": "홍길동",
    "start_date": "2024-01-01",
    "due_date": "2024-01-15",
    "completed_date": null,
    "created_at": "2024-01-01T10:00:00",
    "updated_at": "2024-01-05T14:30:00"
  }
}
```

**실패 응답 (작업 없음):**

```json
{
  "success": false,
  "error": "작업을 찾을 수 없습니다: task_id=999"
}
```

**실패 응답 (권한 없음):**

```json
{
  "success": false,
  "error": "이 작업에 대한 접근 권한이 없습니다. 담당자만 조회할 수 있습니다."
}
```

## Claude Desktop에서 사용하기

Claude Desktop에 연결한 후, 다음과 같이 사용할 수 있습니다:

### 예시 대화

**사용자:**
```
내게 할당된 작업 목록을 보여줘. 사용자명은 "sellimjhw"야.
```

**Claude:**
```
get_assigned_tasks 도구를 사용하여 할당된 작업 목록을 조회하겠습니다.
```

**결과:**
- 작업 목록이 표시됩니다.

**사용자:**
```
작업 ID 1번의 상세 정보를 알려줘.
```

**Claude:**
```
get_task_detail 도구를 사용하여 작업 상세 정보를 조회하겠습니다.
```

## 문제 해결

### 1. MCP 서버 연결 실패

**증상:** Claude Desktop에서 MCP 서버를 연결할 수 없음

**해결 방법:**
1. Python 경로 확인: `python --version` 또는 `python3 --version`
2. MCP SDK 설치 확인: `pip list | grep mcp`
3. 데이터베이스 파일 경로 확인: `DB_FILE` 환경 변수 설정
4. 로그 파일 확인: `MCP_LOG_FILE` 환경 변수로 지정한 경로 확인

### 2. 데이터베이스 연결 오류

**증상:** "database 모듈을 찾을 수 없습니다" 오류

**해결 방법:**
1. 프로젝트 루트 디렉토리에서 실행하는지 확인
2. `PYTHONPATH` 환경 변수에 프로젝트 루트 경로 추가
3. 실행 스크립트 사용 (스크립트가 자동으로 경로 설정)

### 3. 사용자 조회 실패

**증상:** "존재하지 않는 사용자명" 오류

**해결 방법:**
1. 사용자명이 정확한지 확인
2. 사용자가 활성화(`is_active=1`)되어 있는지 확인
3. 데이터베이스에서 직접 확인:
   ```sql
   SELECT id, username, name, is_active FROM users WHERE username = 'sellimjhw';
   ```

### 4. 작업 조회 실패

**증상:** 작업 목록이 비어있음

**해결 방법:**
1. 사용자에게 작업이 할당되어 있는지 확인
2. 데이터베이스에서 직접 확인:
   ```sql
   SELECT * FROM tasks WHERE assignee_id = (SELECT id FROM users WHERE username = 'sellimjhw');
   ```

## 로그 확인

로그 파일은 기본적으로 다음 위치에 저장됩니다:

- Linux/Mac: `./logs/task_mcp_server.log`
- Windows: `.\logs\task_mcp_server.log`

환경 변수 `MCP_LOG_FILE`로 경로를 변경할 수 있습니다.

로그 레벨은 기본적으로 `INFO`입니다. 더 자세한 로그가 필요하면 코드에서 `logging.basicConfig(level=logging.DEBUG)`로 변경할 수 있습니다.

## 보안 고려사항

1. **권한 확인**: `get_task_detail` 도구는 담당자만 조회할 수 있도록 권한 확인을 수행합니다.
2. **SQL Injection 방지**: 모든 데이터베이스 쿼리는 파라미터 바인딩을 사용합니다.
3. **사용자 검증**: 사용자명으로 조회할 때 존재하고 활성화된 사용자인지 확인합니다.

## API 참조

### 작업 상태 (status)

- `PENDING`: 대기
- `IN_PROGRESS`: 진행 중
- `REVIEW`: 검토
- `COMPLETED`: 완료
- `BLOCKED`: 차단

### 우선순위 (priority)

- `LOW`: 낮음
- `MEDIUM`: 보통
- `HIGH`: 높음
- `CRITICAL`: 긴급

## 추가 정보

- 프로젝트 루트: `semasChatbotMng`
- MCP 서버 파일: `mcp_servers/task_mcp_server.py`
- 데이터베이스 파일: `auth.db` (기본값)
- 로그 파일: `logs/task_mcp_server.log` (기본값)

## 지원

문제가 발생하거나 질문이 있으시면 로그 파일을 확인하거나 프로젝트 관리자에게 문의하세요.

