package org.dev.semaschatbot

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.IOException
import com.google.gson.*
import java.util.concurrent.TimeUnit

/**
 * `LmStudioClient`는 LM Studio에서 실행되는 로컬 LLM(Large Language Model) 서버와 통신하는 클라이언트입니다.
 * LM Studio의 Chat Completions API를 사용하여 챗봇 요청을 보내고 응답을 처리합니다.
 */
class LmStudioClient(
    private var baseUrl: String = "http://192.168.18.53:7777/v1" // LM Studio 서버의 기본 URL. 필요에 따라 변경 가능합니다.
) {

    private val client = OkHttpClient.Builder() // HTTP 요청을 보내기 위한 OkHttpClient 인스턴스
        .connectTimeout(120, TimeUnit.SECONDS) // 서버 연결 시도 시 최대 대기 시간 (30초)
        .readTimeout(180, TimeUnit.SECONDS) // 서버로부터 응답 데이터를 읽을 때 최대 대기 시간 (60초)
        .writeTimeout(60, TimeUnit.SECONDS) // 서버로 요청 데이터를 쓸 때 최대 대기 시간 (30초)
        .callTimeout(240, TimeUnit.SECONDS) // 전체 요청-응답 주기에 대한 최대 대기 시간 (120초)
        .build()
    private val gson = Gson() // JSON 직렬화 및 역직렬화를 위한 Gson 인스턴스

    /**
     * LM Studio 서버의 기본 URL을 설정합니다.
     * @param url 새로운 기본 URL
     */
    fun setBaseUrl(url: String) {
        baseUrl = url.removeSuffix("/") // 끝의 슬래시 제거
    }

    /**
     * LM Studio에서 사용 가능한 모델 목록을 반환합니다. (OpenAI 호환 /v1/models)
     */
    fun listModels(): List<String> {
        val request = Request.Builder()
            .url("$baseUrl/models")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return emptyList()
                }
                val body = response.body?.string() ?: return emptyList()
                val json = gson.fromJson(body, JsonObject::class.java)
                val data = json.getAsJsonArray("data") ?: return emptyList()
                data.mapNotNull { el ->
                    try {
                        el.asJsonObject.get("id")?.asString
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 현재 설정된 기본 URL을 반환합니다.
     * @return 현재 기본 URL
     */
    fun getBaseUrl(): String {
        return baseUrl
    }
    
    /**
     * LM Studio API에 채팅 요청을 보내고 응답을 반환합니다.
     * @param userMessage 사용자 입력 메시지
     * @param modelId 사용할 LLM 모델의 ID (기본값: "default-model")
     * @return LLM 응답 문자열 또는 오류 발생 시 null
     */
    fun sendChatRequest(userMessage: String, systemMessage: String, modelId: String = "default-model"): String? {
        val messages = listOf(
            mapOf("role" to "system", "content" to systemMessage),
            mapOf("role" to "user", "content" to userMessage)
        )
        // API 요청 본문을 위한 맵을 생성합니다. 모델 ID, 메시지, temperature 등을 포함합니다.
        val requestBodyMap = mapOf(
            "model" to modelId,
            "messages" to messages,
            "temperature" to 0.7
        )
    // 맵을 JSON 문자열로 변환하여 요청 본문으로 사용합니다.
        val requestBodyJson = gson.toJson(requestBodyMap)

        // HTTP 요청 객체를 생성합니다. POST 메서드와 JSON 본문을 사용합니다.
        val request = Request.Builder()
            .url("$baseUrl/chat/completions") // API 엔드포인트 URL 설정
            .post(RequestBody.create("application/json".toMediaTypeOrNull(), requestBodyJson)) // 요청 본문 설정
            .build()

        try {
            // HTTP 요청을 실행하고 응답을 처리합니다.
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) { // 응답이 성공적이지 않은 경우 예외 발생
                    throw IOException("Unexpected code ${response.code}")
                }
                // 응답 본문을 문자열로 읽어오거나, 없으면 null 반환
                val responseBody = response.body?.string() ?: return null
                // JSON 응답을 파싱하여 JsonObject로 변환
                val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
                // "choices" 배열에서 첫 번째 메시지의 "content"를 추출하여 반환
                val choices = jsonResponse.getAsJsonArray("choices")
                if (choices.size() > 0) {
                    val message = choices.get(0).asJsonObject.getAsJsonObject("message")
                    return message.get("content").asString
                }
                return null // "choices"가 비어있으면 null 반환
            }
        } catch (e: IOException) { // 네트워크 또는 I/O 오류 처리
            println("API 호출 오류: ${e.message}")
            return null
        } catch (e: JsonSyntaxException) { // JSON 파싱 오류 처리
            println("JSON 파싱 오류: ${e.message}")
            return null
        }
    }

    /**
     * 스트리밍 모드 채팅 요청. 델타가 수신될 때마다 onDelta 호출, 완료 시 onComplete, 오류 시 onError 호출.
     * LM Studio(OpenAI 호환) SSE 포맷을 우선 시도하고, 실패 시 전체 본문을 파싱하여 단발 콜백합니다.
     */
    fun sendChatRequestStream(
        userMessage: String,
        systemMessage: String,
        modelId: String = "default-model",
        onDelta: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        val messages = listOf(
            mapOf("role" to "system", "content" to systemMessage),
            mapOf("role" to "user", "content" to userMessage)
        )

        val requestBodyMap = mapOf(
            "model" to modelId,
            "messages" to messages,
            "temperature" to 0.7,
            "stream" to true
        )
        val requestBodyJson = gson.toJson(requestBodyMap)

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .post(RequestBody.create("application/json".toMediaTypeOrNull(), requestBodyJson))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        onError(IOException("Unexpected code ${resp.code}"))
                        return
                    }
                    try {
                        val body = resp.body ?: run {
                            onComplete()
                            return
                        }
                        val source = body.source()
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            val trimmed = line.trim()
                            if (trimmed.isEmpty()) continue
                            if (trimmed.startsWith("data:")) {
                                val payload = trimmed.removePrefix("data:").trim()
                                if (payload == "[DONE]") break
                                val delta = extractContentFromChunk(payload)
                                if (!delta.isNullOrEmpty()) onDelta(delta)
                            } else {
                                val delta = extractContentFromChunk(trimmed)
                                if (!delta.isNullOrEmpty()) onDelta(delta)
                            }
                        }
                        onComplete()
                    } catch (ex: Exception) {
                        try {
                            val fallback = response.body?.string()
                            if (!fallback.isNullOrEmpty()) {
                                val content = extractFullContentOrRaw(fallback)
                                if (!content.isNullOrEmpty()) onDelta(content)
                                onComplete()
                            } else {
                                onError(ex)
                            }
                        } catch (ex2: Exception) {
                            onError(ex2)
                        }
                    }
                }
            }
        })
    }

    private fun extractContentFromChunk(jsonLine: String): String? {
        return try {
            val json = gson.fromJson(jsonLine, JsonObject::class.java)
            val choices = json.getAsJsonArray("choices") ?: return null
            if (choices.size() == 0) return null
            val first = choices[0].asJsonObject
            when {
                first.has("delta") && first.getAsJsonObject("delta").has("content") ->
                    first.getAsJsonObject("delta").get("content").asString
                first.has("message") && first.getAsJsonObject("message").has("content") ->
                    first.getAsJsonObject("message").get("content").asString
                first.has("text") -> first.get("text").asString
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractFullContentOrRaw(jsonText: String): String? {
        return try {
            val json = gson.fromJson(jsonText, JsonObject::class.java)
            val choices = json.getAsJsonArray("choices") ?: return jsonText
            if (choices.size() == 0) return jsonText
            val first = choices[0].asJsonObject
            when {
                first.has("message") && first.getAsJsonObject("message").has("content") ->
                    first.getAsJsonObject("message").get("content").asString
                first.has("text") -> first.get("text").asString
                else -> jsonText
            }
        } catch (e: Exception) {
            jsonText
        }
    }
}