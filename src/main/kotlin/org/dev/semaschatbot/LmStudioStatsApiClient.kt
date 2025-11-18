package org.dev.semaschatbot

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import com.google.gson.*
import java.util.concurrent.TimeUnit

/**
 * LM Studio 통계 정보를 서버로 전송하는 API 클라이언트
 * 
 * LM Studio 모델 사용 시 수집된 통계 정보를 서버로 전송합니다.
 * 비동기 처리로 사용자 경험에 영향을 주지 않습니다.
 * 
 * @param serverBaseUrl 서버 기본 URL (기본값: "http://192.168.18.53:5000")
 */
class LmStudioStatsApiClient(
    private var serverBaseUrl: String = "http://192.168.18.53:5000"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    
    /**
     * 서버 기본 URL을 설정합니다.
     * 
     * @param url 서버 기본 URL
     */
    fun setServerBaseUrl(url: String) {
        serverBaseUrl = url.trim().removeSuffix("/")
        Logger.info("LmStudioStatsApiClient", "서버 URL 설정: $serverBaseUrl")
    }
    
    /**
     * 현재 설정된 서버 기본 URL을 반환합니다.
     * 
     * @return 서버 기본 URL
     */
    fun getServerBaseUrl(): String {
        return serverBaseUrl
    }
    
    /**
     * LM Studio 통계 정보를 서버로 전송합니다.
     * 
     * @param stats 통계 정보
     * @return 전송 성공 여부
     */
    fun sendStats(stats: LmStudioStats): Boolean {
        return try {
            // userId가 null이면 전송하지 않거나 서버에서 처리하도록 함
            // 0 대신 null을 전송하여 서버에서 올바르게 처리하도록 함
            val requestBodyMap = if (stats.userId != null) {
                mapOf(
                    "userId" to stats.userId,
                    "modelId" to stats.modelId,
                    "inputTokens" to stats.inputTokens,
                    "outputTokens" to stats.outputTokens,
                    "totalTokens" to stats.totalTokens,
                    "responseTime" to stats.responseTime
                )
            } else {
                // userId가 null인 경우 (로그인하지 않은 사용자)
                mapOf(
                    "modelId" to stats.modelId,
                    "inputTokens" to stats.inputTokens,
                    "outputTokens" to stats.outputTokens,
                    "totalTokens" to stats.totalTokens,
                    "responseTime" to stats.responseTime
                )
            }
            
            val requestBodyJson = gson.toJson(requestBodyMap)
            val request = Request.Builder()
                .url("$serverBaseUrl/api/lm-studio/stats")
                .post(RequestBody.create("application/json".toMediaTypeOrNull(), requestBodyJson))
                .build()
            
            Logger.debug("LmStudioStatsApiClient", "통계 전송 시도: $serverBaseUrl/api/lm-studio/stats")
            Logger.debug("LmStudioStatsApiClient", "통계 정보: userId=${stats.userId}, modelId=${stats.modelId}, tokens=${stats.totalTokens}, time=${stats.responseTime}ms")
            
            client.newCall(request).execute().use { response ->
                val success = response.isSuccessful
                if (success) {
                    Logger.info("LmStudioStatsApiClient", "통계 전송 성공")
                } else {
                    val errorBody = response.body?.string()
                    Logger.error("LmStudioStatsApiClient", "통계 전송 실패: HTTP ${response.code} - $errorBody")
                }
                success
            }
        } catch (e: Exception) {
            Logger.error("LmStudioStatsApiClient", "통계 전송 중 오류 발생: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    /**
     * LM Studio 통계 정보를 서버로 전송합니다. (비동기, 재시도 포함)
     * 
     * 실패 시 최대 3회까지 재시도하며, 지수 백오프를 사용합니다.
     * 사용자 경험에 영향을 주지 않도록 백그라운드 스레드에서 실행됩니다.
     * 
     * @param stats 통계 정보
     * @param maxRetries 최대 재시도 횟수 (기본값: 3)
     */
    fun sendStatsAsync(stats: LmStudioStats, maxRetries: Int = 3) {
        Thread {
            var success = false
            for (attempt in 1..maxRetries) {
                success = sendStats(stats)
                if (success) {
                    Logger.info("LmStudioStatsApiClient", "통계 전송 성공 (시도 $attempt/$maxRetries)")
                    break
                } else {
                    if (attempt < maxRetries) {
                        val delayMs = 1000L * attempt // 지수 백오프
                        Logger.warn("LmStudioStatsApiClient", "통계 전송 실패, ${delayMs}ms 후 재시도 (시도 $attempt/$maxRetries)")
                        Thread.sleep(delayMs)
                    }
                }
            }
            if (!success) {
                Logger.error("LmStudioStatsApiClient", "통계 전송 실패 (모든 재시도 실패, 총 ${maxRetries}회 시도)")
            }
        }.start()
    }
}

