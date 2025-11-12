# 작업 모드 모델 사용 규칙 기능명세서

## 📋 개요

작업 모드에서 각 단계별로 사용할 LLM 모델을 명확히 구분하여, 작업목록 생성과 프롬프트 생성은 항상 Gemini API를 사용하고, 프롬프트 실행은 사용자가 선택한 모델에 따라 구분하여 호출하는 기능을 명세합니다.

### 목적
- 작업목록 및 프롬프트 생성의 일관성 보장 (항상 Gemini API 사용)
- 프롬프트 실행 단계에서 사용자 선택 모델 지원 (Gemini 또는 LM Studio)
- 명확한 모델 사용 규칙으로 예측 가능한 동작 보장

---

## 🎯 요구사항

### 기능 요구사항

1. **작업목록 생성 단계**
   - 항상 Gemini API를 사용하여 작업목록 생성
   - 사용자가 선택한 모델과 무관하게 Gemini API 호출
   - 기본 모델: `gemini-1.5-flash` (또는 설정 가능한 기본 Gemini 모델)

2. **작업별 프롬프트 생성 단계**
   - 항상 Gemini API를 사용하여 작업별 프롬프트 생성
   - 사용자가 선택한 모델과 무관하게 Gemini API 호출
   - 기본 모델: `gemini-1.5-flash` (또는 설정 가능한 기본 Gemini 모델)

3. **프롬프트 실행 단계**
   - 사용자가 선택한 모델에 따라 API 호출 구분
   - Gemini 모델 선택 시: Gemini API 호출
   - LM Studio 모델 선택 시: LM Studio API 호출
   - 통계 정보 수집 및 전송 (LM Studio 사용 시)

---

## 📐 모델 사용 규칙

### 단계별 모델 사용 매트릭스

| 단계 | 작업 내용 | 사용 모델 | API 클라이언트 | 비고 |
|------|----------|----------|---------------|------|
| 1단계 | 작업목록 생성 | **무조건 Gemini** | `GeminiClient` | 사용자 선택 모델 무시 |
| 2단계 | 작업별 프롬프트 생성 | **무조건 Gemini** | `GeminiClient` | 사용자 선택 모델 무시 |
| 3단계 | 프롬프트 실행 | **선택된 모델** | `GeminiClient` 또는 `LmStudioClient` | 사용자 선택 모델에 따라 구분 |

### 상세 규칙

#### 1단계: 작업목록 생성 (`enterTaskMode()`)

```
사용자 요구사항 입력
    ↓
작업 모드 진입 감지
    ↓
[무조건 Gemini API 호출]
    - 모델: gemini-1.5-flash (또는 기본 Gemini 모델)
    - 클라이언트: GeminiClient
    - 사용자 선택 모델 무시
    ↓
작업목록 생성 및 표시
```

**구현 규칙:**
- `getSelectedModel()` 호출하지 않음
- 항상 기본 Gemini 모델 ID 사용: `"gemini-1.5-flash"`
- `TaskListGenerator.generateTaskList()` 호출 시 고정된 Gemini 모델 ID 전달

#### 2단계: 작업별 프롬프트 생성 (`executeNextTask()`)

```
작업 승인 (진행하기 버튼 클릭)
    ↓
다음 작업 선택
    ↓
[무조건 Gemini API 호출]
    - 모델: gemini-1.5-flash (또는 기본 Gemini 모델)
    - 클라이언트: GeminiClient
    - 사용자 선택 모델 무시
    ↓
작업별 프롬프트 생성 및 표시
```

**구현 규칙:**
- `getSelectedModel()` 호출하지 않음
- 항상 기본 Gemini 모델 ID 사용: `"gemini-1.5-flash"`
- `TaskPromptGenerator.generatePromptForTask()` 호출 시 고정된 Gemini 모델 ID 전달

#### 3단계: 프롬프트 실행 (`executeTaskWithSelectedModel()`)

```
프롬프트 승인 (진행 버튼 클릭)
    ↓
[선택된 모델 확인]
    ↓
┌─────────────────────────┬─────────────────────────┐
│   Gemini 모델 선택 시    │   LM Studio 모델 선택 시  │
├─────────────────────────┼─────────────────────────┤
│ Gemini API 호출         │ LM Studio API 호출      │
│ - 모델: 선택된 Gemini    │ - 모델: 선택된 LM Studio │
│ - 클라이언트: GeminiClient│ - 클라이언트: LmStudioClient│
│                         │ - 통계 정보 수집 및 전송 │
└─────────────────────────┴─────────────────────────┘
    ↓
결과 표시 및 다음 작업 진행
```

**구현 규칙:**
- `getSelectedModel()` 호출하여 현재 선택된 모델 확인
- `isGeminiModel()` 메서드로 모델 타입 구분
- Gemini 모델: `GeminiClient.sendChatRequest()` 호출
- LM Studio 모델: `LmStudioClient.sendChatRequestWithStats()` 호출
- LM Studio 사용 시 통계 정보 수집 및 서버 전송

---

## 🔧 구현 계획

### Phase 1: 작업목록 생성 로직 수정

#### Task 1.1: `enterTaskMode()` 메서드 수정

**현재 문제점:**
- 선택된 모델을 확인하여 Gemini 모델이면 사용, 아니면 기본값 사용
- 사용자가 LM Studio 모델을 선택해도 기본값으로 폴백

**수정 방안:**
```kotlin
// 수정 전
val selectedModelId = getSelectedModel()
val geminiModelId = if (isGeminiModel(selectedModelId)) {
    selectedModelId.removePrefix("💎 ").trim()
} else {
    "gemini-1.5-flash" // 기본값 사용
}

// 수정 후
// 항상 Gemini API 사용 (기본 모델 또는 설정 가능한 모델)
val geminiModelId = "gemini-1.5-flash" // 또는 설정에서 가져온 기본 Gemini 모델
```

**파일 위치**: `src/main/kotlin/org/dev/semaschatbot/ChatService.kt`
**메서드**: `enterTaskMode()` (라인 4567-4617)

---

### Phase 2: 프롬프트 생성 로직 수정

#### Task 2.1: `executeNextTask()` 메서드 수정

**현재 문제점:**
- 선택된 모델을 확인하여 Gemini 모델이면 사용, 아니면 기본값 사용
- 사용자가 LM Studio 모델을 선택해도 기본값으로 폴백

**수정 방안:**
```kotlin
// 수정 전
val selectedModelId = getSelectedModel()
val geminiModelId = if (isGeminiModel(selectedModelId)) {
    selectedModelId.removePrefix("💎 ").trim()
} else {
    "gemini-1.5-flash" // 기본값 사용
}

// 수정 후
// 항상 Gemini API 사용 (기본 모델 또는 설정 가능한 모델)
val geminiModelId = "gemini-1.5-flash" // 또는 설정에서 가져온 기본 Gemini 모델
```

**파일 위치**: `src/main/kotlin/org/dev/semaschatbot/ChatService.kt`
**메서드**: `executeNextTask()` (라인 4671-4729)

---

### Phase 3: 프롬프트 실행 로직 검증

#### Task 3.1: `executeTaskWithSelectedModel()` 메서드 검증

**현재 상태:**
- 이미 선택된 모델에 따라 구분되어 있음
- Gemini 모델: `GeminiClient` 사용
- LM Studio 모델: `LmStudioClient` 사용
- 통계 정보 수집 및 전송 구현됨

**검증 사항:**
- ✅ 모델 선택 로직 정상 동작 확인
- ✅ Gemini/LM Studio 구분 로직 확인
- ✅ 통계 전송 로직 확인

**파일 위치**: `src/main/kotlin/org/dev/semaschatbot/ChatService.kt`
**메서드**: `executeTaskWithSelectedModel()` (라인 4769-4835)

---

## 📊 워크플로우 다이어그램

```
사용자 요구사항 입력
    ↓
[1단계: 작업목록 생성]
    ├─ API: Gemini API (무조건)
    ├─ 모델: gemini-1.5-flash
    └─ 결과: 작업목록 생성 및 표시
    ↓
사용자 승인 (진행하기)
    ↓
[2단계: 작업별 프롬프트 생성]
    ├─ API: Gemini API (무조건)
    ├─ 모델: gemini-1.5-flash
    └─ 결과: 작업별 프롬프트 생성 및 표시
    ↓
사용자 승인 (진행)
    ↓
[3단계: 프롬프트 실행]
    ├─ 모델 확인: getSelectedModel()
    ├─ 분기:
    │   ├─ Gemini 모델 선택 시
    │   │   ├─ API: Gemini API
    │   │   └─ 모델: 선택된 Gemini 모델
    │   └─ LM Studio 모델 선택 시
    │       ├─ API: LM Studio API
    │       ├─ 모델: 선택된 LM Studio 모델
    │       └─ 통계 정보 수집 및 전송
    └─ 결과: 작업 결과 표시
    ↓
다음 작업으로 진행 또는 완료
```

---

## 🔍 상세 구현 사항

### 1. 기본 Gemini 모델 설정

**현재 구현:**
- 하드코딩된 기본값: `"gemini-1.5-flash"`

**개선 방안 (선택적):**
- 설정 파일에서 기본 Gemini 모델 읽기
- 사용자가 기본 Gemini 모델 변경 가능하도록 설정 추가

### 2. 모델 ID 추출 로직

**Gemini 모델 ID 추출:**
```kotlin
// Gemini 모델 ID는 "💎 " 접두사 제거
val actualModelId = modelId.removePrefix("💎 ").trim()
// 예: "💎 gemini-1.5-flash" -> "gemini-1.5-flash"
```

**LM Studio 모델 ID:**
```kotlin
// LM Studio 모델 ID는 그대로 사용
val modelId = getSelectedModel()
// 예: "default-model", "llama-3.1-8b-instruct" 등
```

### 3. 에러 처리

**작업목록 생성 실패 시:**
- 재시도 로직 (최대 3회)
- 폴백 작업 생성 (단일 작업으로 전체 요구사항 처리)

**프롬프트 생성 실패 시:**
- 폴백 프롬프트 생성 (작업 제목과 설명 기반)
- 작업 실패 처리 및 다음 작업으로 진행

**프롬프트 실행 실패 시:**
- 작업 상태를 `FAILED`로 변경
- 에러 메시지 표시
- 사용자에게 다음 작업 진행 여부 선택

---

## 🧪 테스트 시나리오

### 시나리오 1: LM Studio 모델 선택 시

1. **사용자 액션**: LM Studio 모델 선택 (예: "default-model")
2. **작업목록 생성**: Gemini API 호출 확인 (모델: gemini-1.5-flash)
3. **프롬프트 생성**: Gemini API 호출 확인 (모델: gemini-1.5-flash)
4. **프롬프트 실행**: LM Studio API 호출 확인 (모델: default-model)
5. **통계 전송**: LM Studio 통계 정보 서버 전송 확인

### 시나리오 2: Gemini 모델 선택 시

1. **사용자 액션**: Gemini 모델 선택 (예: "💎 gemini-2.5-flash")
2. **작업목록 생성**: Gemini API 호출 확인 (모델: gemini-1.5-flash)
3. **프롬프트 생성**: Gemini API 호출 확인 (모델: gemini-1.5-flash)
4. **프롬프트 실행**: Gemini API 호출 확인 (모델: gemini-2.5-flash)
5. **통계 전송**: Gemini 사용 시 통계 전송 없음 (또는 별도 처리)

### 시나리오 3: 모델 변경 시

1. **초기 상태**: LM Studio 모델 선택
2. **작업목록 생성**: Gemini API 호출 (무조건)
3. **모델 변경**: Gemini 모델로 변경
4. **프롬프트 생성**: Gemini API 호출 (무조건)
5. **프롬프트 실행**: Gemini API 호출 (변경된 모델 사용)

---

## 📝 코드 변경 사항 요약

### 변경 파일

1. **`ChatService.kt`**
   - `enterTaskMode()`: 선택된 모델 확인 로직 제거, 항상 Gemini 모델 사용
   - `executeNextTask()`: 선택된 모델 확인 로직 제거, 항상 Gemini 모델 사용
   - `executeTaskWithSelectedModel()`: 변경 없음 (이미 올바르게 구현됨)

### 변경 전/후 비교

#### 변경 전 (`enterTaskMode()`)
```kotlin
val selectedModelId = getSelectedModel()
val geminiModelId = if (isGeminiModel(selectedModelId)) {
    selectedModelId.removePrefix("💎 ").trim()
} else {
    "gemini-1.5-flash" // 기본값 사용
}
```

#### 변경 후 (`enterTaskMode()`)
```kotlin
// 항상 Gemini API 사용 (작업목록 생성은 무조건 Gemini)
val geminiModelId = "gemini-1.5-flash" // 또는 설정에서 가져온 기본 Gemini 모델
```

#### 변경 전 (`executeNextTask()`)
```kotlin
val selectedModelId = getSelectedModel()
val geminiModelId = if (isGeminiModel(selectedModelId)) {
    selectedModelId.removePrefix("💎 ").trim()
} else {
    "gemini-1.5-flash" // 기본값 사용
}
```

#### 변경 후 (`executeNextTask()`)
```kotlin
// 항상 Gemini API 사용 (프롬프트 생성은 무조건 Gemini)
val geminiModelId = "gemini-1.5-flash" // 또는 설정에서 가져온 기본 Gemini 모델
```

---

## 🔄 기존 코드와의 호환성

### 호환성 유지 사항

1. **데이터 모델**: 변경 없음
   - `Task`, `TaskSession`, `TaskStatus` 등 기존 데이터 모델 유지

2. **UI 컴포넌트**: 변경 없음
   - `TaskListPanel`, `PromptApprovalPanel`, `TaskResultPanel` 유지

3. **API 클라이언트**: 변경 없음
   - `GeminiClient`, `LmStudioClient` 인터페이스 유지

### 변경 영향 범위

- **영향 받는 메서드**: 2개 (`enterTaskMode()`, `executeNextTask()`)
- **영향 받지 않는 메서드**: 나머지 모든 메서드
- **사용자 경험**: 개선됨 (명확한 모델 사용 규칙)

---

## 📅 구현 우선순위

### High Priority
1. **Phase 1**: 작업목록 생성 로직 수정
2. **Phase 2**: 프롬프트 생성 로직 수정

### Medium Priority
3. **Phase 3**: 프롬프트 실행 로직 검증 (이미 구현됨)

---

## 🔍 리스크 및 대응 방안

### 리스크 1: 기본 Gemini 모델이 변경될 경우
- **대응**: 하드코딩된 값 대신 설정 가능한 값 사용 (선택적)
- **검증**: 기본값이 유효한 Gemini 모델인지 확인

### 리스크 2: Gemini API 호출 실패 시
- **대응**: 기존 재시도 로직 및 폴백 메커니즘 유지
- **검증**: 에러 처리 로직 테스트

### 리스크 3: 사용자가 모델 변경을 기대하는 경우
- **대응**: 명확한 문서화 및 UI 메시지로 설명
- **검증**: 사용자 가이드 작성

---

## 📚 참고 자료

- 기존 작업 모드 구현: `WORKLOG/단계별작업기능.md`
- Gemini API 클라이언트: `src/main/kotlin/org/dev/semaschatbot/GeminiClient.kt`
- LM Studio API 클라이언트: `src/main/kotlin/org/dev/semaschatbot/LmStudioClient.kt`
- 작업 관련 클래스: `src/main/kotlin/org/dev/semaschatbot/task/`

---

**작성일**: 2024-01-XX
**작성자**: AI Assistant
**버전**: 1.0

