package org.dev.semaschatbot

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.IOException
import com.google.gson.*
import java.util.concurrent.TimeUnit

/**
 * `GeminiClient`는 Google Gemini API와 통신하는 클라이언트입니다.
 * Gemini API의 generateContent 엔드포인트를 사용하여 챗봇 요청을 보내고 응답을 처리합니다.
 */
class GeminiClient(
    private var apiKey: String = ""
) {
    
    // Gemini API 기본 URL (모델명은 동적으로 설정)
    private val baseUrl = "https://generativelanguage.googleapis.com/v1beta/models"
    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    
    /**
     * Gemini API 키를 설정합니다.
     * @param key API 키
     */
    fun setApiKey(key: String) {
        apiKey = key.trim()
    }
    
    /**
     * 현재 설정된 API 키를 반환합니다.
     * @return API 키
     */
    fun getApiKey(): String {
        return apiKey
    }
    
    /**
     * Gemini API에 채팅 요청을 보내고 응답을 반환합니다.
     * @param userMessage 사용자 입력 메시지
     * @param systemMessage 시스템 프롬프트 (Gemini는 system role을 지원하지 않으므로 user message에 포함)
     * @param modelId 사용할 모델 ID (기본값: "gemini-1.5-flash" - 최신 안정 모델)
     * @return LLM 응답 문자열 또는 오류 발생 시 null
     */
    fun sendChatRequest(userMessage: String, systemMessage: String, modelId: String = "gemini-1.5-flash"): String? {
        if (apiKey.isBlank()) {
            println("Gemini API 키가 설정되지 않았습니다.")
            return null
        }
        
        // Gemini API는 system role을 지원하지 않으므로 system message를 user message 앞에 추가
        val fullMessage = if (systemMessage.isNotBlank()) {
            "$systemMessage\n\n$userMessage"
        } else {
            userMessage
        }
        
        // Gemini API 요청 형식
        val requestBodyMap = mapOf(
            "contents" to listOf(
                mapOf(
                    "parts" to listOf(
                        mapOf("text" to fullMessage)
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
        
        val requestBodyJson = gson.toJson(requestBodyMap)
        
        // 올바른 엔드포인트 URL 형식: {baseUrl}/{modelId}:generateContent?key={apiKey}
        val endpointUrl = "$baseUrl/$modelId:generateContent?key=$apiKey"
        
        val request = Request.Builder()
            .url(endpointUrl)
            .post(RequestBody.create("application/json".toMediaTypeOrNull(), requestBodyJson))
            .build()
        
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    println("Gemini API 오류: ${response.code} - $errorBody")
                    throw IOException("Unexpected code ${response.code}: $errorBody")
                }
                
                val responseBody = response.body?.string() ?: return null
                val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
                
                // Gemini API 응답 형식 파싱
                val candidates = jsonResponse.getAsJsonArray("candidates")
                if (candidates != null && candidates.size() > 0) {
                    val candidate = candidates[0].asJsonObject
                    val content = candidate.getAsJsonObject("content")
                    val parts = content.getAsJsonArray("parts")
                    if (parts != null && parts.size() > 0) {
                        val text = parts[0].asJsonObject.get("text")
                        return text?.asString
                    }
                }
                return null
            }
        } catch (e: IOException) {
            println("Gemini API 호출 오류: ${e.message}")
            return null
        } catch (e: JsonSyntaxException) {
            println("Gemini JSON 파싱 오류: ${e.message}")
            return null
        }
    }
    
    /**
     * 스트리밍 모드 채팅 요청. Gemini API는 스트리밍을 지원하지만 여기서는 비스트리밍으로 구현하고
     * 전체 응답을 델타로 분할하여 전송합니다.
     * @param modelId 사용할 모델 ID (기본값: "gemini-1.5-flash" - 최신 안정 모델)
     *                 지원되는 모델: gemini-1.5-flash, gemini-1.5-pro, gemini-2.0-flash-exp 등
     */
    fun sendChatRequestStream(
        userMessage: String,
        systemMessage: String,
        modelId: String = "gemini-1.5-flash",
        onDelta: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        if (apiKey.isBlank()) {
            onError(IllegalStateException("Gemini API 키가 설정되지 않았습니다."))
            return
        }
        
        // 백그라운드 스레드에서 실행
        Thread {
            try {
                val response = sendChatRequest(userMessage, systemMessage, modelId)
                if (response != null) {
                    // 응답을 문자 단위로 분할하여 스트리밍처럼 전송
                    response.forEach { char ->
                        onDelta(char.toString())
                        Thread.sleep(10) // 스트리밍 효과를 위한 짧은 지연
                    }
                    onComplete()
                } else {
                    onError(IOException("Gemini API 응답이 null입니다."))
                }
            } catch (e: Exception) {
                onError(e)
            }
        }.start()
    }
}

