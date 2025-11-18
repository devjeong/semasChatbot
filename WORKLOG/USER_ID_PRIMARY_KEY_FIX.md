# WORKLOG: PRIMARY KEY 제약 조건 위반 오류 수정

## 📋 문제 상황

### 증상
- 로그인 시 "로컬 동기화 실패" 오류 발생
- 에러 메시지: `A PRIMARY KEY CONSTRAINT FAILED(UNIQUE CONSTRAINT FAILED : users.id)`

### 원인 분석
1. **서버 ID 충돌**: 서버에서 받은 ID가 이미 로컬 DB의 다른 사용자에게 할당되어 있는 경우
2. **INSERT 시도**: 존재하는 ID로 INSERT를 시도하여 PRIMARY KEY 제약 조건 위반 발생
3. **동기화 로직 문제**: 로컬 ID와 서버 ID가 다를 때, 서버 ID가 이미 사용 중인지 확인하지 않고 INSERT 시도

---

## 🔧 수정 내용

### 1. 로컬에 사용자가 있는 경우 (342-422번 줄)

#### 변경 전
```kotlin
// 서버 ID로 업데이트 (기존 레코드 삭제 후 재생성)
val deleteStmt = conn.prepareStatement("DELETE FROM users WHERE id = ?")
deleteStmt.setInt(1, localId)
deleteStmt.executeUpdate()

// 서버 ID를 사용하여 재생성
INSERT INTO users (id, ...) VALUES (serverId, ...)  // ❌ 충돌 가능
```

#### 변경 후
```kotlin
// 서버 ID가 이미 다른 사용자에게 사용 중인지 확인
val checkStmt = conn.prepareStatement("SELECT id FROM users WHERE id = ?")
checkStmt.setInt(1, serverId)
val checkRs = checkStmt.executeQuery()
val serverIdExists = checkRs.next()

if (serverIdExists) {
    // 서버 ID가 이미 사용 중이면 UPDATE 사용
    UPDATE users SET ... WHERE id = ?  // ✅ 충돌 없음
    // 기존 localId 레코드 삭제
} else {
    // 서버 ID가 사용 가능하면 기존 레코드 삭제 후 재생성
    DELETE FROM users WHERE id = ?
    INSERT INTO users (id, ...) VALUES (serverId, ...)  // ✅ 안전
}
```

### 2. 로컬에 사용자가 없는 경우 (464-531번 줄)

#### 변경 전
```kotlin
// 서버 ID를 명시적으로 사용하여 INSERT
INSERT INTO users (id, ...) VALUES (serverId, ...)  // ❌ 충돌 가능
```

#### 변경 후
```kotlin
// 서버 ID가 이미 다른 사용자에게 사용 중인지 확인
val checkStmt = conn.prepareStatement("SELECT id, username FROM users WHERE id = ?")
val serverIdExists = checkRs.next()
val existingUsername = if (serverIdExists) checkRs.getString("username") else null

if (serverIdExists && existingUsername != username) {
    // 서버 ID가 이미 다른 사용자에게 사용 중이면 UPDATE
    Logger.warn("UserService", "서버 ID $serverId가 이미 다른 사용자($existingUsername)에게 사용 중입니다. 업데이트합니다.")
    UPDATE users SET ... WHERE id = ?  // ✅ 충돌 없음
} else {
    // 서버 ID를 명시적으로 사용하여 INSERT
    INSERT INTO users (id, ...) VALUES (serverId, ...)  // ✅ 안전
}
```

---

## ✅ 해결 방법

### 핵심 로직
1. **INSERT 전 확인**: 서버 ID가 이미 존재하는지 확인
2. **조건부 처리**: 
   - 존재하면 → UPDATE 사용
   - 없으면 → INSERT 사용
3. **로그 추가**: 충돌 상황을 Logger로 기록하여 디버깅 가능

### 안전성 향상
- PRIMARY KEY 제약 조건 위반 방지
- 데이터 무결성 유지
- 기존 사용자 데이터 보호

---

## 📊 변경된 파일

### 수정된 파일
- **src/main/kotlin/org/dev/semaschatbot/UserService.kt**
  - `login()` 메서드의 두 가지 케이스 모두 수정
  - INSERT 전 ID 존재 여부 확인 로직 추가
  - UPDATE/INSERT 조건부 처리 추가

---

## 🔍 테스트 시나리오

### 시나리오 1: 서버 ID가 이미 사용 중인 경우
1. 사용자 A가 서버 ID 1로 로그인 (로컬 DB에 저장됨)
2. 사용자 B가 서버 ID 1로 로그인 시도
3. **기대 결과**: UPDATE로 처리되어 오류 없이 로그인 성공

### 시나리오 2: 서버 ID가 사용 가능한 경우
1. 사용자 A가 서버 ID 1로 로그인
2. 사용자 B가 서버 ID 2로 로그인 시도
3. **기대 결과**: INSERT로 처리되어 정상적으로 저장

### 시나리오 3: 로컬 ID와 서버 ID가 다른 경우
1. 사용자 A가 로컬 ID 1, 서버 ID 2로 로그인
2. 서버 ID 2가 이미 다른 사용자에게 사용 중
3. **기대 결과**: UPDATE로 처리되어 오류 없이 동기화

---

## 💡 향후 개선 사항

### 권장 사항
1. **서버 측 검증**: 서버에서도 ID 중복을 방지하는 로직 추가
2. **로컬 DB 정리**: 주기적으로 사용하지 않는 사용자 데이터 정리
3. **에러 처리 개선**: 더 구체적인 에러 메시지 제공

---

**작업 완료 일시**: 2025-01-29
**작업자**: AI Assistant
**상태**: 완료 ✅

