package org.dev.semaschatbot

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import com.google.gson.*
import java.util.concurrent.TimeUnit

/**
 * Gemini API 프록시 엔드포인트 수동 테스트 스크립트
 * 
 * 이 스크립트는 localhost:5000 포트에서 실행 중인 중간 서버의 /api/gemini 엔드포인트를 테스트합니다.
 * 
 * 사용 방법:
 * 1. 중간 서버가 localhost:5000에서 실행 중이어야 합니다.
 * 2. main 함수의 testApiKey를 실제 API Key로 변경하세요.
 * 3. IntelliJ IDEA에서 main 함수를 실행하거나, ./gradlew run으로 실행하세요.
 */
object GeminiApiEndpointManualTest {
    
    // 테스트 대상 서버 URL
    private const val TEST_SERVER_URL = "http://localhost:5000"
    private const val ENDPOINT = "/api/gemini"
    
    // 실제 API Key로 변경 필요
    private const val TEST_API_KEY = "YOUR_GEMINI_API_KEY_HERE"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val gson = GsonBuilder().setPrettyPrinting().create()
    
    /**
     * 테스트 요청 본문 생성
     */
    private fun createRequest(
        modelId: String = "gemini-1.5-flash",
        apiKey: String = TEST_API_KEY,
        userMessage: String = "안녕하세요"
    ): Request {
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
        
        val requestBodyJson = gson.toJson(requestBodyMap)
        
        return Request.Builder()
            .url("$TEST_SERVER_URL$ENDPOINT")
            .post(RequestBody.create("application/json".toMediaTypeOrNull(), requestBodyJson))
            .build()
    }
    
    /**
     * 요청 실행 및 결과 출력
     */
    private fun executeRequest(request: Request, description: String) {
        println("\n${"=".repeat(60)}")
        println("테스트: $description")
        println("${"=".repeat(60)}")
        println("요청 URL: ${request.url}")
        println("요청 메서드: ${request.method}")
        
        try {
            val startTime = System.currentTimeMillis()
            client.newCall(request).execute().use { response ->
                val elapsedTime = System.currentTimeMillis() - startTime
                
                println("\n응답 정보:")
                println("  상태 코드: ${response.code} ${if (response.isSuccessful) "✅" else "❌"}")
                println("  응답 시간: ${elapsedTime}ms")
                println("  Content-Type: ${response.header("Content-Type")}")
                
                val responseBody = response.body?.string()
                
                if (response.isSuccessful && responseBody != null) {
                    try {
                        val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
                        
                        // 응답 본문을 예쁘게 출력
                        println("\n응답 본문 (JSON):")
                        println(gson.toJson(jsonResponse))
                        
                        // 응답 텍스트 추출
                        val candidates = jsonResponse.getAsJsonArray("candidates")
                        if (candidates != null && candidates.size() > 0) {
                            val candidate = candidates[0].asJsonObject
                            val content = candidate.getAsJsonObject("content")
                            val parts = content.getAsJsonArray("parts")
                            if (parts != null && parts.size() > 0) {
                                val text = parts[0].asJsonObject.get("text").asString
                                println("\n📝 추출된 응답 텍스트:")
                                println("   $text")
                            }
                        }
                        
                        // 사용량 정보 출력
                        if (jsonResponse.has("usageMetadata")) {
                            val usage = jsonResponse.getAsJsonObject("usageMetadata")
                            println("\n📊 사용량 정보:")
                            if (usage.has("promptTokenCount")) {
                                println("   입력 토큰: ${usage.get("promptTokenCount").asInt}")
                            }
                            if (usage.has("candidatesTokenCount")) {
                                println("   출력 토큰: ${usage.get("candidatesTokenCount").asInt}")
                            }
                            if (usage.has("totalTokenCount")) {
                                println("   총 토큰: ${usage.get("totalTokenCount").asInt}")
                            }
                        }
                        
                    } catch (e: JsonSyntaxException) {
                        println("\n⚠️ JSON 파싱 오류: ${e.message}")
                        println("원본 응답:")
                        println(responseBody.take(1000))
                    }
                } else {
                    println("\n❌ 요청 실패")
                    println("응답 본문:")
                    println(responseBody ?: "(없음)")
                }
            }
        } catch (e: Exception) {
            println("\n❌ 예외 발생: ${e.javaClass.simpleName}")
            println("메시지: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * 메인 함수 - 테스트 실행
     */
    @JvmStatic
    fun main(args: Array<String>) {
        println("""
            ╔════════════════════════════════════════════════════════════╗
            ║   Gemini API 프록시 엔드포인트 테스트                       ║
            ║   서버: $TEST_SERVER_URL$ENDPOINT
            ╚════════════════════════════════════════════════════════════╝
        """.trimIndent())
        
        // API Key 확인
        if (TEST_API_KEY == "YOUR_GEMINI_API_KEY_HERE") {
            println("\n⚠️ 경고: TEST_API_KEY를 실제 API Key로 변경해주세요!")
            println("   현재 파일의 TEST_API_KEY 상수를 수정하세요.\n")
        }
        
        // 테스트 1: 기본 요청
        val basicRequest = createRequest(
            userMessage = "안녕하세요. 간단히 자기소개 해주세요."
        )
        executeRequest(basicRequest, "기본 요청 테스트")
        
        // 테스트 2: 다른 모델 사용
        val proModelRequest = createRequest(
            modelId = "gemini-1.5-pro",
            userMessage = "Kotlin에서 코루틴을 사용하는 방법을 간단히 설명해주세요."
        )
        executeRequest(proModelRequest, "gemini-1.5-pro 모델 테스트")
        
        // 테스트 3: 긴 메시지
        val longMessage = """
            다음 코드를 리뷰해주세요:
            
            ```kotlin
            fun calculateSum(numbers: List<Int>): Int {
                var sum = 0
                for (number in numbers) {
                    sum += number
                }
                return sum
            }
            ```
            
            이 코드를 개선할 수 있는 방법이 있나요?
        """.trimIndent()
        
        val longMessageRequest = createRequest(
            userMessage = longMessage
        )
        executeRequest(longMessageRequest, "긴 메시지 테스트")
        
        // 테스트 4: 에러 케이스 - 잘못된 API Key
        println("\n${"=".repeat(60)}")
        println("에러 케이스 테스트")
        println("${"=".repeat(60)}")
        
        val invalidKeyRequest = createRequest(
            apiKey = "invalid_api_key_12345",
            userMessage = "테스트"
        )
        executeRequest(invalidKeyRequest, "잘못된 API Key 테스트")
        
        println("\n${"=".repeat(60)}")
        println("모든 테스트 완료!")
        println("${"=".repeat(60)}")
    }
}

