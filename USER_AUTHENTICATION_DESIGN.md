# 회원인증 시스템 및 사용량 측정 설계

## 📋 사용량 측정 아이디어

### 1. 기본 사용량 지표

#### 📊 메시지 관련 지표
- **총 메시지 수**: 사용자가 보낸 전체 메시지 수
- **일일 메시지 수**: 일별 메시지 수 통계
- **평균 메시지 길이**: 메시지당 평균 문자 수
- **최대 메시지 길이**: 가장 긴 메시지의 길이

#### 🎯 토큰 사용량 지표
- **입력 토큰 수**: 사용자 입력에 사용된 토큰 수
- **출력 토큰 수**: LLM 응답에 사용된 토큰 수
- **총 토큰 수**: 입력 + 출력 토큰 합계
- **일일 토큰 사용량**: 일별 토큰 사용량 통계
- **평균 토큰 사용량**: 메시지당 평균 토큰 수

#### ⏱️ 세션 관련 지표
- **로그인 시간**: 마지막 로그인 시간
- **마지막 활동 시간**: 마지막 활동 시간
- **총 세션 시간**: 누적 세션 시간
- **평균 세션 시간**: 세션당 평균 시간
- **세션 수**: 총 로그인 횟수

#### 🔌 API 호출 지표
- **LLM API 호출 횟수**: LLM API 호출 총 횟수
- **성공한 API 호출**: 성공한 호출 수
- **실패한 API 호출**: 실패한 호출 수
- **평균 응답 시간**: API 응답 평균 시간

#### 💻 코드 수정 관련 지표
- **코드 수정 요청 수**: 코드 변경 요청 횟수
- **수정된 파일 수**: 수정된 파일 개수
- **수정된 라인 수**: 수정된 코드 라인 수
- **수정 성공률**: 수정 성공 비율

#### 🔍 인덱싱 관련 지표
- **인덱싱 요청 횟수**: 재인덱싱 요청 횟수
- **인덱싱된 파일 수**: 인덱싱된 파일 개수
- **인덱싱된 코드 청크 수**: 인덱싱된 코드 조각 수
- **인덱싱 소요 시간**: 인덱싱에 걸린 시간

#### 🗄️ 데이터베이스 관련 지표
- **DB 연결 횟수**: 데이터베이스 연결 횟수
- **DB 쿼리 수**: 실행된 쿼리 수
- **DB 연결 시간**: DB 연결에 걸린 시간

### 2. 고급 분석 지표

#### 📈 사용 패턴 분석
- **시간대별 사용량**: 시간대별 사용 패턴
- **요일별 사용량**: 요일별 사용 패턴
- **피크 사용 시간**: 가장 많이 사용한 시간대
- **사용 빈도**: 일일/주간/월간 사용 빈도

#### 🎨 기능별 사용량
- **질문 유형별 사용량**: RAG_QUESTION, GENERAL_QUESTION 등
- **코드 수정 유형별 사용량**: 부분 수정, 전체 수정 등
- **가장 많이 사용한 기능**: 사용 빈도가 높은 기능

#### 💰 비용 관련 지표 (향후 확장)
- **예상 비용**: 토큰 사용량 기반 예상 비용
- **비용 효율성**: 기능별 비용 대비 효과

### 3. 데이터 저장 구조

#### User 테이블
```sql
CREATE TABLE users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    name TEXT NOT NULL,
    role TEXT NOT NULL DEFAULT 'USER',  -- USER, ADMIN, PREMIUM
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login TIMESTAMP,
    is_active INTEGER DEFAULT 1
);
```

#### UsageStatistics 테이블
```sql
CREATE TABLE usage_statistics (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    date DATE NOT NULL,
    
    -- 메시지 관련
    total_messages INTEGER DEFAULT 0,
    daily_messages INTEGER DEFAULT 0,
    avg_message_length REAL DEFAULT 0,
    
    -- 토큰 관련
    input_tokens INTEGER DEFAULT 0,
    output_tokens INTEGER DEFAULT 0,
    total_tokens INTEGER DEFAULT 0,
    daily_tokens INTEGER DEFAULT 0,
    
    -- 세션 관련
    session_count INTEGER DEFAULT 0,
    total_session_time INTEGER DEFAULT 0,  -- 초 단위
    last_activity TIMESTAMP,
    
    -- API 호출 관련
    api_calls INTEGER DEFAULT 0,
    successful_calls INTEGER DEFAULT 0,
    failed_calls INTEGER DEFAULT 0,
    avg_response_time REAL DEFAULT 0,  -- 밀리초 단위
    
    -- 코드 수정 관련
    code_modification_requests INTEGER DEFAULT 0,
    modified_files INTEGER DEFAULT 0,
    modified_lines INTEGER DEFAULT 0,
    
    -- 인덱싱 관련
    indexing_requests INTEGER DEFAULT 0,
    indexed_files INTEGER DEFAULT 0,
    indexed_chunks INTEGER DEFAULT 0,
    indexing_time INTEGER DEFAULT 0,  -- 밀리초 단위
    
    -- DB 관련
    db_connections INTEGER DEFAULT 0,
    db_queries INTEGER DEFAULT 0,
    
    FOREIGN KEY (user_id) REFERENCES users(id),
    UNIQUE(user_id, date)
);
```

#### SessionLog 테이블 (선택사항)
```sql
CREATE TABLE session_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    login_time TIMESTAMP NOT NULL,
    logout_time TIMESTAMP,
    session_duration INTEGER,  -- 초 단위
    FOREIGN KEY (user_id) REFERENCES users(id)
);
```

### 4. 구현 방식

#### 데이터 저장 옵션
1. **SQLite (권장)**
   - 로컬 파일 기반 DB
   - 별도 서버 불필요
   - 간단한 쿼리로 통계 조회 가능
   - 파일 위치: `{plugin_data_dir}/users.db`

2. **JSON 파일 (간단한 구현)**
   - 파일 기반 저장
   - 별도 라이브러리 불필요
   - 대용량 데이터에는 비효율적
   - 파일 위치: `{plugin_data_dir}/users.json`, `{plugin_data_dir}/usage.json`

#### 권장 구현: SQLite
- 성능: 빠른 조회 및 집계
- 확장성: 향후 기능 추가 용이
- 안정성: 트랜잭션 지원
- 표준: SQL 쿼리로 통계 분석 가능

### 5. 사용량 측정 구현 전략

#### 실시간 측정
- 각 액션 발생 시 즉시 DB 업데이트
- 예: 메시지 전송 시 `daily_messages++`, `total_messages++`

#### 배치 측정
- 일정 시간마다 집계하여 업데이트
- 예: 1시간마다 통계 집계

#### 하이브리드 방식 (권장)
- 실시간: 중요한 지표 (메시지 수, 토큰 수)
- 배치: 집계가 필요한 지표 (평균값, 시간대별 통계)

### 6. 사용량 조회 기능

#### 사용자 대시보드
- 오늘의 사용량
- 이번 주 사용량
- 이번 달 사용량
- 전체 사용량

#### 관리자 대시보드 (ADMIN 권한)
- 전체 사용자 통계
- 사용자별 상세 통계
- 기능별 사용량 분석
- 시간대별 사용 패턴

### 7. 권한별 제한사항

#### USER (일반 사용자)
- 기본 기능 사용 가능
- 자신의 사용량만 조회 가능
- 일일 메시지 제한: 100개
- 일일 토큰 제한: 10,000개

#### PREMIUM (프리미엄 사용자)
- 모든 기능 사용 가능
- 자신의 사용량만 조회 가능
- 일일 메시지 제한: 500개
- 일일 토큰 제한: 50,000개

#### ADMIN (관리자)
- 모든 기능 사용 가능
- 전체 사용자 통계 조회 가능
- 제한 없음

### 8. 성능 최적화

#### 인덱싱
- `user_id`, `date`에 인덱스 생성
- 빠른 조회를 위한 복합 인덱스

#### 캐싱
- 자주 조회하는 통계는 메모리 캐싱
- 일정 시간마다 캐시 갱신

#### 데이터 아카이빙
- 오래된 데이터는 별도 테이블로 이동
- 최근 1년 데이터만 메인 테이블 유지

## 📝 구현 우선순위

### Phase 1: 기본 기능
1. ✅ User 데이터 모델 생성
2. ✅ UsageStatistics 데이터 모델 생성
3. ✅ UserService 클래스 생성 (회원가입, 로그인)
4. ✅ 기본 사용량 측정 (메시지 수, 토큰 수)

### Phase 2: 고급 기능
5. 세션 관리
6. 상세 통계 조회
7. 사용량 대시보드 UI

### Phase 3: 분석 기능
8. 사용 패턴 분석
9. 관리자 대시보드
10. 리포트 생성

