# Gemini API 엔드포인트 테스트 가이드

## 📋 개요

이 문서는 `localhost:5000` 포트에서 실행 중인 중간 서버의 `/api/gemini` 엔드포인트를 테스트하는 방법을 설명합니다.

---

## 🧪 테스트 코드 종류

### 1. JUnit 테스트 (`GeminiApiEndpointTest.kt`)

**위치**: `src/test/kotlin/org/dev/semaschatbot/GeminiApiEndpointTest.kt`

**특징**:
- 자동화된 단위 테스트
- CI/CD 파이프라인에 통합 가능
- 여러 테스트 케이스를 한 번에 실행

**실행 방법**:
```bash
# 전체 테스트 실행
./gradlew test --tests GeminiApiEndpointTest

# 특정 테스트만 실행
./gradlew test --tests GeminiApiEndpointTest.testBasicRequest
```

**테스트 케이스**:
1. `testBasicRequest()` - 기본 요청 테스트
2. `testDifferentModels()` - 다양한 모델 테스트
3. `testErrorHandling()` - 에러 처리 테스트
4. `testLongMessage()` - 긴 메시지 테스트
5. `testResponseFormat()` - 응답 형식 검증

### 2. 수동 테스트 스크립트 (`GeminiApiEndpointManualTest.kt`)

**위치**: `src/test/kotlin/org/dev/semaschatbot/GeminiApiEndpointManualTest.kt`

**특징**:
- 상세한 출력 제공
- 디버깅에 유용
- 단계별 실행 가능

**실행 방법**:
```bash
# IntelliJ IDEA에서 main 함수 실행
# 또는
./gradlew run -PmainClass=org.dev.semaschatbot.GeminiApiEndpointManualTest
```

---

## 🔧 설정 방법

### 1. API Key 설정

#### JUnit 테스트의 경우
`GeminiApiEndpointTest.kt` 파일을 열고 다음 부분을 수정:

```kotlin
private val testApiKey = "YOUR_GEMINI_API_KEY_HERE"
```

실제 API Key로 변경:
```kotlin
private val testApiKey = ""
```

#### 수동 테스트 스크립트의 경우
`GeminiApiEndpointManualTest.kt` 파일을 열고 다음 부분을 수정:

```kotlin
private const val TEST_API_KEY = "YOUR_GEMINI_API_KEY_HERE"
```

### 2. 서버 URL 확인

기본값은 `http://localhost:5000`입니다. 다른 포트를 사용하는 경우:

**JUnit 테스트**:
```kotlin
private val testServerUrl = "http://localhost:5000"  // 포트 변경
```

**수동 테스트**:
```kotlin
private const val TEST_SERVER_URL = "http://localhost:5000"  // 포트 변경
```

---

## 🚀 테스트 실행

### 전제 조건

1. ✅ 중간 서버가 `localhost:5000`에서 실행 중이어야 합니다.
2. ✅ 유효한 Gemini API Key가 설정되어 있어야 합니다.
3. ✅ 네트워크 연결이 정상이어야 합니다.

### 실행 단계

#### 방법 1: IntelliJ IDEA에서 실행

1. **JUnit 테스트 실행**:
   - `GeminiApiEndpointTest.kt` 파일 열기
   - 테스트 메서드 옆의 실행 버튼 클릭
   - 또는 전체 클래스 실행

2. **수동 테스트 실행**:
   - `GeminiApiEndpointManualTest.kt` 파일 열기
   - `main` 함수 옆의 실행 버튼 클릭

#### 방법 2: Gradle 명령어로 실행

```bash
# JUnit 테스트 실행
./gradlew test --tests GeminiApiEndpointTest

# 수동 테스트 실행 (main 함수)
./gradlew run -PmainClass=org.dev.semaschatbot.GeminiApiEndpointManualTest
```

#### 방법 3: cURL로 직접 테스트

```bash
curl -X POST http://localhost:5000/api/gemini \
  -H "Content-Type: application/json" \
  -d '{
    "modelId": "gemini-1.5-flash",
    "apiKey": "YOUR_API_KEY",
    "requestBody": {
      "contents": [
        {
          "parts": [
            {
              "text": "안녕하세요"
            }
          ]
        }
      ],
      "generationConfig": {
        "temperature": 0.7,
        "topK": 40,
        "topP": 0.95,
        "maxOutputTokens": 8192
      }
    }
  }'
```

---

## 📊 예상 결과

### 성공적인 응답

```json
{
  "candidates": [
    {
      "content": {
        "parts": [
          {
            "text": "안녕하세요! 무엇을 도와드릴까요?"
          }
        ],
        "role": "model"
      },
      "finishReason": "STOP",
      "index": 0
    }
  ],
  "usageMetadata": {
    "promptTokenCount": 5,
    "candidatesTokenCount": 10,
    "totalTokenCount": 15
  }
}
```

### 에러 응답 예시

#### 잘못된 API Key (401 또는 403)
```json
{
  "error": {
    "code": 401,
    "message": "Invalid API key",
    "status": "UNAUTHENTICATED"
  }
}
```

#### 필수 필드 누락 (400)
```json
{
  "error": {
    "code": 400,
    "message": "Missing required fields: modelId, apiKey, or requestBody",
    "status": "INVALID_ARGUMENT"
  }
}
```

---

## 🔍 테스트 결과 확인

### JUnit 테스트 결과

테스트가 성공하면:
```
✅ 테스트 통과!
```

테스트가 실패하면:
```
❌ 테스트 실패: [에러 메시지]
```

### 수동 테스트 출력 예시

```
╔════════════════════════════════════════════════════════════╗
║   Gemini API 프록시 엔드포인트 테스트                       ║
║   서버: http://localhost:5000/api/gemini                   ║
╚════════════════════════════════════════════════════════════╝

============================================================
테스트: 기본 요청 테스트
============================================================
요청 URL: http://localhost:5000/api/gemini
요청 메서드: POST

응답 정보:
  상태 코드: 200 ✅
  응답 시간: 1234ms
  Content-Type: application/json

응답 본문 (JSON):
{
  "candidates": [
    {
      "content": {
        "parts": [
          {
            "text": "안녕하세요! 무엇을 도와드릴까요?"
          }
        ],
        "role": "model"
      },
      "finishReason": "STOP",
      "index": 0
    }
  ],
  "usageMetadata": {
    "promptTokenCount": 5,
    "candidatesTokenCount": 10,
    "totalTokenCount": 15
  }
}

📝 추출된 응답 텍스트:
   안녕하세요! 무엇을 도와드릴까요?

📊 사용량 정보:
   입력 토큰: 5
   출력 토큰: 10
   총 토큰: 15
```

---

## 🐛 문제 해결

### 문제 1: 연결 실패

**증상**: `java.net.ConnectException: Connection refused`

**원인**: 중간 서버가 실행되지 않았거나 잘못된 포트

**해결**:
1. 중간 서버가 `localhost:5000`에서 실행 중인지 확인
2. 서버 URL이 올바른지 확인
3. 방화벽 설정 확인

### 문제 2: 타임아웃

**증상**: `java.net.SocketTimeoutException`

**원인**: 서버 응답이 너무 느림

**해결**:
1. 타임아웃 시간 증가 (테스트 코드에서 `readTimeout` 수정)
2. 서버 로그 확인
3. 네트워크 상태 확인

### 문제 3: API Key 오류

**증상**: `401 Unauthorized` 또는 `403 Forbidden`

**원인**: 잘못된 API Key 또는 만료된 Key

**해결**:
1. API Key가 올바른지 확인
2. Google AI Studio에서 새 API Key 발급
3. API Key에 필요한 권한이 있는지 확인

### 문제 4: 응답 형식 오류

**증상**: `JsonSyntaxException` 또는 `NullPointerException`

**원인**: 서버가 올바른 형식의 응답을 반환하지 않음

**해결**:
1. 서버 로그 확인
2. Gemini API 응답 형식 확인
3. 중간 서버 코드 검토

---

## 📝 테스트 커스터마이징

### 커스텀 테스트 케이스 추가

`GeminiApiEndpointTest.kt`에 새로운 테스트 메서드 추가:

```kotlin
@Test
fun testCustomCase() {
    val requestBody = createTestRequestBody(
        userMessage = "커스텀 테스트 메시지"
    )
    
    val request = Request.Builder()
        .url("$testServerUrl$endpoint")
        .post(RequestBody.create("application/json".toMediaTypeOrNull(), requestBody))
        .build()
    
    // 테스트 로직 작성
}
```

### 다른 서버 포트 테스트

환경 변수나 설정 파일을 사용하여 포트를 동적으로 변경:

```kotlin
private val testServerUrl = System.getenv("TEST_SERVER_URL") 
    ?: "http://localhost:5000"
```

---

## ✅ 체크리스트

테스트 전 확인 사항:

- [ ] 중간 서버가 실행 중인가?
- [ ] 올바른 포트(5000)를 사용하는가?
- [ ] 유효한 Gemini API Key가 설정되어 있는가?
- [ ] 네트워크 연결이 정상인가?
- [ ] 필요한 의존성이 설치되어 있는가? (OkHttp, Gson)

---

**작성일**: 2024년
**버전**: 1.0

