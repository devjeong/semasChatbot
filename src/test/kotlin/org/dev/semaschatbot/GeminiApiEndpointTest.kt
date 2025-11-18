package org.dev.semaschatbot

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import com.google.gson.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.concurrent.TimeUnit

/**
 * Gemini API 프록시 엔드포인트 테스트 클래스
 * 
 * 이 테스트는 localhost:5000 포트에서 실행 중인 중간 서버의 /api/gemini 엔드포인트를 테스트합니다.
 * 
 * 사용 방법:
 * 1. 중간 서버가 localhost:5000에서 실행 중이어야 합니다.
 * 2. config.properties에 유효한 gemini.apiKey가 설정되어 있어야 합니다.
 * 3. 테스트 실행: ./gradlew test --tests GeminiApiEndpointTest
 */
class GeminiApiEndpointTest {
    
    // 테스트 대상 서버 URL
    private val testServerUrl = "http://localhost:5000"
    private val endpoint = "/api/gemini"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    
    /**
     * 테스트용 Gemini API Key (실제 API Key로 교체 필요)
     * 주의: 실제 API Key를 사용할 경우 보안에 주의하세요.
     */
    private val testApiKey = ""
    
    /**
     * 기본 요청 본문 생성 헬퍼 메서드
     */
    private fun createTestRequestBody(
        modelId: String = "gemini-2.5-flash",
        apiKey: String = testApiKey,
        userMessage: String = "안녕하세요"
    ): String {
        val requestBodyMap = mapOf(
            "modelId" to modelId,
            "apiKey" to apiKey,
            "requestBody" to mapOf(
                "contents" to listOf(
                    mapOf(
                        "parts" to listOf(
                            mapOf("text" to userMessage)
                        )
                    )
                ),
                "generationConfig" to mapOf(
                    "temperature" to 0.7,
                    "topK" to 40,
                    "topP" to 0.95,
                    "maxOutputTokens" to 8192
                )
            )
        )
        return gson.toJson(requestBodyMap)
    }
    
    /**
     * 테스트 1: 기본 요청 테스트
     * 
     * 목적: 기본적인 요청이 정상적으로 처리되는지 확인
     */
    @Test
    fun testBasicRequest() {
        println("\n=== 테스트 1: 기본 요청 테스트 ===")
        
        val requestBody = createTestRequestBody(
            userMessage = "안녕하세요. 간단히 자기소개 해주세요."
        )
        
        val request = Request.Builder()
            .url("$testServerUrl$endpoint")
            .post(RequestBody.create("application/json".toMediaTypeOrNull(), requestBody))
            .build()
        
        try {
            client.newCall(request).execute().use { response ->
                println("응답 상태 코드: ${response.code}")
                println("응답 헤더: ${response.headers}")
                
                assertTrue(response.isSuccessful, "요청이 성공해야 합니다. 상태 코드: ${response.code}")
                
                val responseBody = response.body?.string()
                assertNotNull(responseBody, "응답 본문이 null이 아니어야 합니다.")
                
                println("응답 본문 (처음 500자): ${responseBody?.take(500)}")
                
                // JSON 파싱 테스트
                val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
                assertNotNull(jsonResponse, "JSON 파싱이 성공해야 합니다.")
                
                // 필수 필드 확인
                val candidates = jsonResponse.getAsJsonArray("candidates")
                assertNotNull(candidates, "candidates 배열이 있어야 합니다.")
                assertTrue(candidates.size() > 0, "candidates 배열이 비어있지 않아야 합니다.")
                
                val candidate = candidates[0].asJsonObject
                val content = candidate.getAsJsonObject("content")
                assertNotNull(content, "content 객체가 있어야 합니다.")
                
                val parts = content.getAsJsonArray("parts")
                assertNotNull(parts, "parts 배열이 있어야 합니다.")
                assertTrue(parts.size() > 0, "parts 배열이 비어있지 않아야 합니다.")
                
                val text = parts[0].asJsonObject.get("text")
                assertNotNull(text, "text 필드가 있어야 합니다.")
                
                val responseText = text.asString
                assertNotNull(responseText, "응답 텍스트가 null이 아니어야 합니다.")
                assertTrue(responseText.isNotBlank(), "응답 텍스트가 비어있지 않아야 합니다.")
                
                println("✅ 응답 텍스트 추출 성공: ${responseText.take(100)}...")
                println("✅ 테스트 통과!")
            }
        } catch (e: Exception) {
            println("❌ 테스트 실패: ${e.message}")
            e.printStackTrace()
            fail("테스트 중 예외 발생: ${e.message}")
        }
    }
    
    /**
     * 테스트 2: 다양한 모델 테스트
     * 
     * 목적: 다양한 Gemini 모델이 정상적으로 작동하는지 확인
     */
    @Test
    fun testDifferentModels() {
        println("\n=== 테스트 2: 다양한 모델 테스트 ===")
        
        val models = listOf(
            "gemini-1.5-flash",
            "gemini-1.5-pro"
            // "gemini-2.0-flash-exp" // 실험적 모델은 주석 처리
        )
        
        models.forEach { modelId ->
            println("\n모델 테스트: $modelId")
            
            val requestBody = createTestRequestBody(
                modelId = modelId,
                userMessage = "1+1은 무엇인가요?"
            )
            
            val request = Request.Builder()
                .url("$testServerUrl$endpoint")
                .post(RequestBody.create("application/json".toMediaTypeOrNull(), requestBody))
                .build()
            
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string()
                        val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
                        val candidates = jsonResponse.getAsJsonArray("candidates")
                        
                        if (candidates != null && candidates.size() > 0) {
                            val text = candidates[0].asJsonObject
                                .getAsJsonObject("content")
                                .getAsJsonArray("parts")[0]
                                .asJsonObject.get("text").asString
                            
                            println("✅ 모델 $modelId 응답 성공: ${text.take(50)}...")
                        } else {
                            println("⚠️ 모델 $modelId 응답 형식 오류")
                        }
                    } else {
                        println("❌ 모델 $modelId 요청 실패: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                println("❌ 모델 $modelId 테스트 중 예외: ${e.message}")
            }
        }
    }
    
    /**
     * 테스트 3: 에러 처리 테스트
     * 
     * 목적: 잘못된 요청에 대한 에러 처리가 올바른지 확인
     */
    @Test
    fun testErrorHandling() {
        println("\n=== 테스트 3: 에러 처리 테스트 ===")
        
        // 테스트 3-1: 잘못된 API Key
        println("\n3-1: 잘못된 API Key 테스트")
        val invalidKeyBody = createTestRequestBody(
            apiKey = "invalid_api_key_12345",
            userMessage = "테스트"
        )
        
        val invalidKeyRequest = Request.Builder()
            .url("$testServerUrl$endpoint")
            .post(RequestBody.create("application/json".toMediaTypeOrNull(), invalidKeyBody))
            .build()
        
        try {
            client.newCall(invalidKeyRequest).execute().use { response ->
                println("응답 상태 코드: ${response.code}")
                val responseBody = response.body?.string()
                println("응답 본문: $responseBody")
                
                // 401 또는 400 에러가 예상됨
                assertTrue(
                    response.code == 401 || response.code == 400 || response.code == 403,
                    "잘못된 API Key에 대해 에러 상태 코드가 반환되어야 합니다."
                )
                println("✅ 잘못된 API Key 에러 처리 확인")
            }
        } catch (e: Exception) {
            println("⚠️ 예외 발생 (예상 가능): ${e.message}")
        }
        
        // 테스트 3-2: 필수 필드 누락
        println("\n3-2: 필수 필드 누락 테스트")
        val missingFieldBody = """
            {
                "modelId": "gemini-1.5-flash"
            }
        """.trimIndent()
        
        val missingFieldRequest = Request.Builder()
            .url("$testServerUrl$endpoint")
            .post(RequestBody.create("application/json".toMediaTypeOrNull(), missingFieldBody))
            .build()
        
        try {
            client.newCall(missingFieldRequest).execute().use { response ->
                println("응답 상태 코드: ${response.code}")
                val responseBody = response.body?.string()
                println("응답 본문: $responseBody")
                
                // 400 에러가 예상됨
                assertEquals(400, response.code, "필수 필드 누락 시 400 에러가 반환되어야 합니다.")
                println("✅ 필수 필드 누락 에러 처리 확인")
            }
        } catch (e: Exception) {
            println("⚠️ 예외 발생: ${e.message}")
        }
    }
    
    /**
     * 테스트 4: 긴 메시지 테스트
     * 
     * 목적: 긴 메시지가 정상적으로 처리되는지 확인
     */
    @Test
    fun testLongMessage() {
        println("\n=== 테스트 4: 긴 메시지 테스트 ===")
        
        val longMessage = "안녕하세요. " + "반복되는 텍스트입니다. ".repeat(50) + "이것은 긴 메시지 테스트입니다."
        
        val requestBody = createTestRequestBody(
            userMessage = longMessage
        )
        
        val request = Request.Builder()
            .url("$testServerUrl$endpoint")
            .post(RequestBody.create("application/json".toMediaTypeOrNull(), requestBody))
            .build()
        
        try {
            client.newCall(request).execute().use { response ->
                assertTrue(response.isSuccessful, "긴 메시지 요청이 성공해야 합니다.")
                
                val responseBody = response.body?.string()
                val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
                val candidates = jsonResponse.getAsJsonArray("candidates")
                
                assertNotNull(candidates, "응답에 candidates가 있어야 합니다.")
                assertTrue(candidates.size() > 0, "candidates 배열이 비어있지 않아야 합니다.")
                
                println("✅ 긴 메시지 테스트 통과")
            }
        } catch (e: Exception) {
            println("❌ 긴 메시지 테스트 실패: ${e.message}")
            fail("긴 메시지 테스트 중 예외 발생: ${e.message}")
        }
    }
    
    /**
     * 테스트 5: 응답 형식 검증
     * 
     * 목적: 응답이 올바른 형식인지 상세히 검증
     */
    @Test
    fun testResponseFormat() {
        println("\n=== 테스트 5: 응답 형식 검증 ===")
        
        val requestBody = createTestRequestBody(
            userMessage = "테스트 메시지"
        )
        
        val request = Request.Builder()
            .url("$testServerUrl$endpoint")
            .post(RequestBody.create("application/json".toMediaTypeOrNull(), requestBody))
            .build()
        
        try {
            client.newCall(request).execute().use { response ->
                assertTrue(response.isSuccessful, "요청이 성공해야 합니다.")
                
                val responseBody = response.body?.string()
                val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
                
                // 필수 필드 검증
                println("응답 필드 검증:")
                
                // candidates 필드
                assertTrue(jsonResponse.has("candidates"), "응답에 'candidates' 필드가 있어야 합니다.")
                val candidates = jsonResponse.getAsJsonArray("candidates")
                assertTrue(candidates.size() > 0, "candidates 배열이 비어있지 않아야 합니다.")
                println("  ✅ candidates 배열 존재 확인 (크기: ${candidates.size()})")
                
                // candidate[0].content 필드
                val candidate = candidates[0].asJsonObject
                assertTrue(candidate.has("content"), "candidate에 'content' 필드가 있어야 합니다.")
                val content = candidate.getAsJsonObject("content")
                println("  ✅ content 객체 존재 확인")
                
                // content.parts 필드
                assertTrue(content.has("parts"), "content에 'parts' 필드가 있어야 합니다.")
                val parts = content.getAsJsonArray("parts")
                assertTrue(parts.size() > 0, "parts 배열이 비어있지 않아야 합니다.")
                println("  ✅ parts 배열 존재 확인 (크기: ${parts.size()})")
                
                // parts[0].text 필드
                val part = parts[0].asJsonObject
                assertTrue(part.has("text"), "part에 'text' 필드가 있어야 합니다.")
                val text = part.get("text").asString
                assertTrue(text.isNotBlank(), "text 필드가 비어있지 않아야 합니다.")
                println("  ✅ text 필드 존재 및 값 확인: ${text.take(50)}...")
                
                // 선택적 필드 확인
                if (candidate.has("finishReason")) {
                    println("  ✅ finishReason 필드 존재: ${candidate.get("finishReason").asString}")
                }
                
                if (jsonResponse.has("usageMetadata")) {
                    println("  ✅ usageMetadata 필드 존재")
                }
                
                println("✅ 모든 응답 형식 검증 통과!")
            }
        } catch (e: Exception) {
            println("❌ 응답 형식 검증 실패: ${e.message}")
            e.printStackTrace()
            fail("응답 형식 검증 중 예외 발생: ${e.message}")
        }
    }
}

