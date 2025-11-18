package org.dev.semaschatbot

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.IOException
import com.google.gson.*
import java.util.concurrent.TimeUnit

/**
 * `GeminiClient`는 중간 서버를 통해 Google Gemini API와 통신하는 클라이언트입니다.
 * 폐쇄망 환경에서 외부 Gemini API에 접근하기 위해 중간 서버(프록시)를 사용합니다.
 * 요청 흐름: 로컬PC → 중간서버 → Gemini API → 중간서버 → 로컬PC
 */
class GeminiClient(
    private var apiKey: String = ""
) {
    
    // 중간 서버 기본 URL (기본값: http://192.168.18.53:5000)
    // Gemini API 프록시는 포트 5000을 사용합니다.
    private var serverBaseUrl: String = "http://192.168.18.53:5000"
    
    // Gemini API 프록시 엔드포인트 (중간 서버의 Gemini 프록시 경로)
    private val geminiProxyEndpoint = "/api/gemini"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    
    /**
     * 중간 서버의 기본 URL을 설정합니다.
     * @param url 서버 기본 URL (예: "http://192.168.18.53")
     */
    fun setServerBaseUrl(url: String) {
        serverBaseUrl = url.trim().removeSuffix("/")
        Logger.info("GeminiClient", "서버 URL 설정: $serverBaseUrl")
    }
    
    /**
     * 현재 설정된 서버 기본 URL을 반환합니다.
     * @return 서버 기본 URL
     */
    fun getServerBaseUrl(): String {
        return serverBaseUrl
    }
    
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
     * @param modelId 사용할 모델 ID (기본값: "gemini-2.5-flash")
     * @param userId 로그인한 사용자 ID (로그인 ID 문자열, 예: "selimjhw", 선택적, 서버에서 사용량 추적 등에 사용)
     * @return LLM 응답 문자열 또는 오류 발생 시 null
     */
    fun sendChatRequest(userMessage: String, systemMessage: String, modelId: String = "gemini-2.5-flash", userId: String? = null): String? {
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
        
        // 중간 서버를 통한 프록시 호출
        // 요청 본문에 모델 ID, 사용자 ID를 포함하여 전송 (API Key는 중앙서버에서 관리)
        val proxyRequestBodyMap = mutableMapOf<String, Any>(
            "modelId" to modelId,
            "requestBody" to requestBodyMap
        )
        
        // 사용자 ID가 제공된 경우 추가
        if (userId != null) {
            proxyRequestBodyMap["userId"] = userId
        }
        
        val proxyRequestBodyJson = gson.toJson(proxyRequestBodyMap)
        
        // 중간 서버의 Gemini 프록시 엔드포인트 URL
        val endpointUrl = "$serverBaseUrl$geminiProxyEndpoint"
        
        Logger.debug("GeminiClient", "프록시 호출: $endpointUrl")
        Logger.debug("GeminiClient", "모델 ID: $modelId")
        if (userId != null) {
            Logger.debug("GeminiClient", "사용자 ID: $userId (타입: ${userId::class.simpleName})")
        }
        Logger.debug("GeminiClient", "요청 본문: $proxyRequestBodyJson")
        
        val request = Request.Builder()
            .url(endpointUrl)
            .post(RequestBody.create("application/json".toMediaTypeOrNull(), proxyRequestBodyJson))
            .build()
        
        try {
            client.newCall(request).execute().use { response ->
                Logger.debug("GeminiClient", "응답 코드: ${response.code}")
                Logger.debug("GeminiClient", "응답 헤더: ${response.headers}")
                
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    Logger.error("GeminiClient", "오류 응답 본문: $errorBody")
                    throw IOException("Unexpected code ${response.code}: $errorBody")
                }
                
                val responseBody = response.body?.string() ?: return null
                Logger.debug("GeminiClient", "성공 응답 본문 (처음 500자): ${responseBody.take(500)}")
                val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
                
                // Gemini API 응답 형식 파싱
                Logger.debug("GeminiClient", "JSON 파싱 시작")
                val candidates = jsonResponse.getAsJsonArray("candidates")
                Logger.debug("GeminiClient", "candidates 배열 크기: ${candidates?.size() ?: 0}")
                
                if (candidates != null && candidates.size() > 0) {
                    val candidate = candidates[0].asJsonObject
                    Logger.debug("GeminiClient", "candidate 객체: $candidate")
                    val content = candidate.getAsJsonObject("content")
                    Logger.debug("GeminiClient", "content 객체: $content")
                    val parts = content.getAsJsonArray("parts")
                    Logger.debug("GeminiClient", "parts 배열 크기: ${parts?.size() ?: 0}")
                    
                    if (parts != null && parts.size() > 0) {
                        val text = parts[0].asJsonObject.get("text")
                        val textValue = text?.asString
                        Logger.debug("GeminiClient", "추출된 텍스트 길이: ${textValue?.length ?: 0}")
                        return textValue
                    } else {
                        Logger.warn("GeminiClient", "경고: parts 배열이 비어있거나 null입니다.")
                    }
                } else {
                    Logger.warn("GeminiClient", "경고: candidates 배열이 비어있거나 null입니다.")
                    Logger.debug("GeminiClient", "전체 응답: $jsonResponse")
                }
                return null
            }
        } catch (e: IOException) {
            Logger.error("GeminiClient", "IOException 발생: ${e.message}")
            e.printStackTrace()
            return null
        } catch (e: JsonSyntaxException) {
            Logger.error("GeminiClient", "JsonSyntaxException 발생: ${e.message}")
            e.printStackTrace()
            return null
        } catch (e: Exception) {
            Logger.error("GeminiClient", "예상치 못한 오류 발생: ${e.javaClass.simpleName} - ${e.message}")
            e.printStackTrace()
            return null
        }
    }
    
    /**
     * 스트리밍 모드 채팅 요청. Gemini API는 스트리밍을 지원하지만 여기서는 비스트리밍으로 구현하고
     * 전체 응답을 델타로 분할하여 전송합니다.
     * @param userMessage 사용자 입력 메시지
     * @param systemMessage 시스템 프롬프트
     * @param modelId 사용할 모델 ID (기본값: "gemini-2.5-flash")
     * @param userId 로그인한 사용자 ID (로그인 ID 문자열, 예: "selimjhw", 선택적, 서버에서 사용량 추적 등에 사용)
     * @param onDelta 델타 콜백 (스트리밍 응답의 각 부분)
     * @param onComplete 완료 콜백
     * @param onError 에러 콜백
     */
    fun sendChatRequestStream(
        userMessage: String,
        systemMessage: String,
        modelId: String = "gemini-2.5-flash",
        userId: String? = null,
        onDelta: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        // 백그라운드 스레드에서 실행 (API Key는 중앙서버에서 관리)
        Thread {
            try {
                val response = sendChatRequest(userMessage, systemMessage, modelId, userId)
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

