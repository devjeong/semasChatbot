# WORKLOG: API 송신 시 userId를 로그인 ID(문자열)로 변경

## 📋 요구사항 요약

### 문제점
API 송신 시 숫자 ID가 전송되고 있었습니다. 사용자는 로그인 성공한 로그인 ID(예: "selimjhw")를 API 송신 시마다 보내고 싶어했습니다.

### 원인 분석
1. **숫자 ID 사용**: `UserService.getCurrentUser()?.id`를 사용하여 숫자 ID(Int)를 전송
2. **타입 불일치**: API 클라이언트들이 `userId: Int?` 타입을 기대하고 있었음
3. **사용자 요구사항**: 로그인 ID(문자열)를 전송해야 함

### 목표
- 로그인 성공한 사용자의 로그인 ID(문자열, 예: "selimjhw")를 모든 API 송신 시 전송
- 모든 관련 코드에서 일관되게 로그인 ID를 사용하도록 수정

---

## 📝 작업 목록

### 1. GeminiClient 수정 ✅
- `sendChatRequest()` 메서드의 `userId` 파라미터를 `Int?`에서 `String?`로 변경
- `sendChatRequestStream()` 메서드의 `userId` 파라미터를 `Int?`에서 `String?`로 변경
- 주석에 로그인 ID 문자열 예시 추가

### 2. LmStudioStats 데이터 클래스 수정 ✅
- `userId` 필드를 `Int?`에서 `String?`로 변경
- 주석에 로그인 ID 문자열 예시 추가

### 3. ChatService 수정 ✅
- 모든 API 호출 위치에서 `currentUser?.id`를 `currentUser?.username`으로 변경
- 총 5개 위치 수정:
  1. 일반 채팅 메시지 전송 시 (Gemini API)
  2. LM Studio 통계 전송 시
  3. 작업목록 생성 시
  4. 작업 프롬프트 생성 시
  5. 작업 실행 시

### 4. TaskListGenerator 수정 ✅
- `generateTaskList()` 메서드의 `userId` 파라미터를 `Int?`에서 `String?`로 변경
- 주석에 로그인 ID 문자열 예시 추가

### 5. TaskPromptGenerator 수정 ✅
- `generatePromptForTask()` 메서드의 `userId` 파라미터를 `Int?`에서 `String?`로 변경
- 주석에 로그인 ID 문자열 예시 추가

### 6. LmStudioStatsApiClient 확인 ✅
- String 타입 userId를 올바르게 처리하는지 확인
- JSON 직렬화 시 String 타입이 그대로 포함되므로 문제없음 확인

---

## 🔧 수정된 파일 목록

### 수정된 파일
1. **src/main/kotlin/org/dev/semaschatbot/GeminiClient.kt**
   - `sendChatRequest()`: `userId: Int?` → `userId: String?`
   - `sendChatRequestStream()`: `userId: Int?` → `userId: String?`

2. **src/main/kotlin/org/dev/semaschatbot/LmStudioStats.kt**
   - `userId: Int?` → `userId: String?`

3. **src/main/kotlin/org/dev/semaschatbot/ChatService.kt**
   - 5개 위치에서 `currentUser?.id` → `currentUser?.username` 변경

4. **src/main/kotlin/org/dev/semaschatbot/task/TaskListGenerator.kt**
   - `generateTaskList()`: `userId: Int?` → `userId: String?`

5. **src/main/kotlin/org/dev/semaschatbot/task/TaskPromptGenerator.kt**
   - `generatePromptForTask()`: `userId: Int?` → `userId: String?`

---

## ✅ 테스트 결과

### 컴파일 검증
- 린터 오류 없음 확인
- 모든 타입 변경이 일관되게 적용됨

### 동작 확인 사항
1. **로그인 후 API 호출 시 로그인 ID 전송 확인**
   - 로그인 성공 후 `currentUser?.username`이 올바르게 전달되는지 확인 필요
   - 실제 API 호출 시 서버로 전송되는 userId가 로그인 ID 문자열인지 확인 필요

2. **로그인하지 않은 경우**
   - `currentUser`가 null이면 `username`도 null이 되어 API 호출 시 userId가 전송되지 않음 (기존 동작 유지)

---

## 📊 성능 및 안정성

### 성능 영향
- **없음**: 타입 변경만 수행했으며, 로직 변경 없음
- 문자열 전송이 숫자 전송보다 약간 더 큰 데이터 크기이지만 무시 가능한 수준

### 안정성
- **향상**: 로그인 ID(문자열)는 사용자가 직접 입력한 값으로, 숫자 ID보다 더 명확하고 추적 가능
- **호환성**: 서버 API가 String 타입 userId를 받을 수 있다고 가정 (서버 측 확인 필요)

---

## 🔍 주요 변경 사항 상세

### 변경 전
```kotlin
// 숫자 ID 사용
val currentUserId = userService.getCurrentUser()?.id  // Int?
geminiClient.sendChatRequest(..., userId = currentUserId)
```

### 변경 후
```kotlin
// 로그인 ID(문자열) 사용
val currentUserId = userService.getCurrentUser()?.username  // String?
geminiClient.sendChatRequest(..., userId = currentUserId)
```

---

## 📝 향후 작업

### 권장 사항
1. **서버 API 확인**: 서버 측 API가 String 타입 userId를 올바르게 처리하는지 확인 필요
2. **통합 테스트**: 실제 로그인 후 API 호출 시 로그인 ID가 올바르게 전송되는지 확인
3. **문서화**: 서버 API 문서에 userId가 String 타입임을 명시

---

## ✨ 완료 상태

- ✅ 모든 타입 변경 완료
- ✅ 모든 호출 위치 수정 완료
- ✅ 컴파일 오류 없음 확인
- ⏳ 실제 동작 테스트 필요 (서버 연동 필요)

---

**작업 완료 일시**: 2025-01-29
**작업자**: AI Assistant
**관련 이슈**: API 송신 시 로그인 ID 전송 요구사항

