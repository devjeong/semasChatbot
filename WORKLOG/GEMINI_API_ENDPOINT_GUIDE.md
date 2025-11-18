# Gemini API 프록시 엔드포인트 가이드 문서

## 📋 개요

이 문서는 중간 서버(`192.168.18.53`)에서 구현해야 하는 `/api/gemini` 엔드포인트의 입력/출력 스펙을 정의합니다.

**목적**: 폐쇄망 환경의 로컬PC에서 외부 Gemini API를 사용하기 위한 프록시 역할

**요청 흐름**: 로컬PC → 중간서버(`/api/gemini`) → Gemini API → 중간서버 → 로컬PC

---

## 🔌 엔드포인트 정보

- **URL**: `http://192.168.18.53/api/gemini`
- **Method**: `POST`
- **Content-Type**: `application/json`
- **Accept**: `application/json`

---

## 📥 요청 (Request) 스펙

### 요청 헤더

```
Content-Type: application/json
```

### 요청 본문 (Request Body)

요청 본문은 JSON 형식이며, 다음 구조를 가져야 합니다:

```json
{
  "modelId": "string",
  "apiKey": "string",
  "requestBody": {
    "contents": [
      {
        "parts": [
          {
            "text": "string"
          }
        ]
      }
    ],
    "generationConfig": {
      "temperature": number,
      "topK": number,
      "topP": number,
      "maxOutputTokens": number
    }
  }
}
```

### 필드 설명

#### 최상위 필드

| 필드명 | 타입 | 필수 | 설명 |
|--------|------|------|------|
| `modelId` | string | ✅ | 사용할 Gemini 모델 ID<br>예: `"gemini-1.5-flash"`, `"gemini-1.5-pro"`, `"gemini-2.0-flash-exp"` |
| `apiKey` | string | ✅ | Google Gemini API Key<br>예: `""` |
| `requestBody` | object | ✅ | Gemini API에 전달할 실제 요청 본문 |

#### requestBody.contents 필드

| 필드명 | 타입 | 필수 | 설명 |
|--------|------|------|------|
| `contents` | array | ✅ | 메시지 컨텐츠 배열 (단일 요소) |
| `contents[].parts` | array | ✅ | 텍스트 파트 배열 (단일 요소) |
| `contents[].parts[].text` | string | ✅ | 실제 메시지 텍스트<br>시스템 프롬프트와 사용자 메시지가 합쳐진 전체 텍스트 |

#### requestBody.generationConfig 필드

| 필드명 | 타입 | 필수 | 기본값 | 설명 |
|--------|------|------|--------|------|
| `temperature` | number | ❌ | 0.7 | 응답의 창의성/랜덤성 (0.0 ~ 1.0) |
| `topK` | number | ❌ | 40 | 상위 K개 토큰만 고려 |
| `topP` | number | ❌ | 0.95 | 누적 확률 임계값 (0.0 ~ 1.0) |
| `maxOutputTokens` | number | ❌ | 8192 | 최대 출력 토큰 수 |

### 요청 예시

```json
{
  "modelId": "gemini-1.5-flash",
  "apiKey": "",
  "requestBody": {
    "contents": [
      {
        "parts": [
          {
            "text": "당신은 시니어 개발자입니다.\n\n사용자 질문: Kotlin에서 코루틴을 사용하는 방법을 알려주세요."
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
}
```

---

## 📤 응답 (Response) 스펙

### 성공 응답 (HTTP 200 OK)

중간 서버는 Gemini API의 응답을 **그대로 전달**해야 합니다.

#### 응답 본문 구조

```json
{
  "candidates": [
    {
      "content": {
        "parts": [
          {
            "text": "string"
          }
        ],
        "role": "model"
      },
      "finishReason": "STOP",
      "index": 0,
      "safetyRatings": [
        {
          "category": "HARM_CATEGORY_HARASSMENT",
          "probability": "NEGLIGIBLE"
        }
      ]
    }
  ],
  "usageMetadata": {
    "promptTokenCount": 100,
    "candidatesTokenCount": 200,
    "totalTokenCount": 300
  }
}
```

#### 필수 필드

| 필드명 | 타입 | 설명 |
|--------|------|------|
| `candidates` | array | 응답 후보 배열 (최소 1개 요소 필요) |
| `candidates[].content` | object | 응답 컨텐츠 |
| `candidates[].content.parts` | array | 텍스트 파트 배열 (최소 1개 요소 필요) |
| `candidates[].content.parts[].text` | string | **실제 응답 텍스트** (클라이언트가 파싱하는 필드) |

#### 응답 예시

```json
{
  "candidates": [
    {
      "content": {
        "parts": [
          {
            "text": "Kotlin에서 코루틴을 사용하는 방법은 다음과 같습니다:\n\n1. `launch` 함수로 코루틴 시작\n2. `async` 함수로 비동기 작업 수행\n3. `suspend` 함수로 코루틴 내에서 사용 가능한 함수 정의\n\n예시 코드:\n```kotlin\nimport kotlinx.coroutines.*\n\nfun main() = runBlocking {\n    launch {\n        delay(1000L)\n        println(\"World!\")\n    }\n    println(\"Hello,\")\n}\n```"
          }
        ],
        "role": "model"
      },
      "finishReason": "STOP",
      "index": 0
    }
  ],
  "usageMetadata": {
    "promptTokenCount": 45,
    "candidatesTokenCount": 120,
    "totalTokenCount": 165
  }
}
```

### 에러 응답

#### HTTP 상태 코드

| 상태 코드 | 설명 |
|----------|------|
| `200` | 성공 |
| `400` | 잘못된 요청 (요청 본문 형식 오류, 필수 필드 누락 등) |
| `401` | 인증 실패 (API Key 오류) |
| `403` | 권한 없음 |
| `404` | 모델을 찾을 수 없음 |
| `429` | 요청 한도 초과 |
| `500` | 서버 내부 오류 (Gemini API 오류 포함) |
| `503` | 서비스 사용 불가 |

#### 에러 응답 본문 형식

```json
{
  "error": {
    "code": "ERROR_CODE",
    "message": "에러 메시지",
    "status": "ERROR_STATUS"
  }
}
```

#### 에러 응답 예시

```json
{
  "error": {
    "code": 400,
    "message": "Invalid API key",
    "status": "INVALID_ARGUMENT"
  }
}
```

---

## 🔄 중간 서버 처리 로직

### 1. 요청 수신 및 검증

```python
# 예시: Python Flask
@app.route('/api/gemini', methods=['POST'])
def gemini_proxy():
    try:
        data = request.get_json()
        
        # 필수 필드 검증
        if not data or 'modelId' not in data or 'apiKey' not in data or 'requestBody' not in data:
            return jsonify({
                "error": {
                    "code": 400,
                    "message": "Missing required fields: modelId, apiKey, or requestBody",
                    "status": "INVALID_ARGUMENT"
                }
            }), 400
        
        model_id = data['modelId']
        api_key = data['apiKey']
        request_body = data['requestBody']
        
        # 다음 단계로 진행
        ...
```

### 2. Gemini API 호출

```python
import requests

# Gemini API 엔드포인트 구성
gemini_url = f"https://generativelanguage.googleapis.com/v1beta/models/{model_id}:generateContent?key={api_key}"

# Gemini API에 요청 전송
response = requests.post(
    gemini_url,
    json=request_body,
    headers={
        "Content-Type": "application/json"
    },
    timeout=180  # 타임아웃 설정 (초)
)

# 응답 상태 확인
if response.status_code == 200:
    # 성공: Gemini API 응답을 그대로 반환
    return jsonify(response.json()), 200
else:
    # 에러: Gemini API 에러 응답을 그대로 반환
    return jsonify(response.json()), response.status_code
```

### 3. 응답 전달

중간 서버는 Gemini API의 응답을 **수정 없이 그대로** 클라이언트에 전달해야 합니다.

---

## 📝 구현 체크리스트

### 필수 구현 사항

- [ ] `POST /api/gemini` 엔드포인트 구현
- [ ] 요청 본문 JSON 파싱
- [ ] 필수 필드 검증 (`modelId`, `apiKey`, `requestBody`)
- [ ] Gemini API 호출 (`https://generativelanguage.googleapis.com/v1beta/models/{modelId}:generateContent`)
- [ ] API Key를 쿼리 파라미터로 전달 (`?key={apiKey}`)
- [ ] Gemini API 응답을 그대로 클라이언트에 전달
- [ ] 에러 처리 및 적절한 HTTP 상태 코드 반환
- [ ] 타임아웃 설정 (최소 180초)

### 권장 구현 사항

- [ ] 요청 로깅 (디버깅용)
- [ ] 에러 로깅
- [ ] CORS 헤더 설정 (필요한 경우)
- [ ] 요청 본문 크기 제한
- [ ] Rate Limiting (요청 제한)

---

## 🔍 클라이언트 파싱 로직

클라이언트(`GeminiClient`)는 다음 경로로 응답 텍스트를 추출합니다:

```kotlin
// 응답 파싱 예시 (Kotlin)
val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
val candidates = jsonResponse.getAsJsonArray("candidates")
if (candidates != null && candidates.size() > 0) {
    val candidate = candidates[0].asJsonObject
    val content = candidate.getAsJsonObject("content")
    val parts = content.getAsJsonArray("parts")
    if (parts != null && parts.size() > 0) {
        val text = parts[0].asJsonObject.get("text")
        return text?.asString  // 이 값이 최종 응답 텍스트
    }
}
```

**중요**: 중간 서버는 반드시 `candidates[0].content.parts[0].text` 경로에 응답 텍스트가 포함되도록 Gemini API 응답을 그대로 전달해야 합니다.

---

## 🧪 테스트 예시

### cURL 테스트

```bash
curl -X POST http://192.168.18.53/api/gemini \
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

### 예상 응답

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

---

## ⚠️ 주의사항

1. **API Key 보안**: API Key가 요청 본문에 포함되므로 HTTPS 사용을 권장합니다.
2. **응답 형식 유지**: Gemini API 응답 형식을 변경하지 마세요. 클라이언트가 특정 구조를 기대합니다.
3. **에러 전달**: Gemini API의 에러 응답도 그대로 클라이언트에 전달해야 합니다.
4. **타임아웃**: Gemini API 호출 시 충분한 타임아웃을 설정하세요 (최소 180초).
5. **텍스트 인코딩**: UTF-8 인코딩을 사용하세요.

---

## 📚 참고 자료

- [Google Gemini API 공식 문서](https://ai.google.dev/api)
- [Gemini API generateContent 엔드포인트](https://ai.google.dev/api/gemini-api-rest)

---

**작성일**: 2024년
**버전**: 1.0
**대상**: 중간 서버(`192.168.18.53`) 개발자

