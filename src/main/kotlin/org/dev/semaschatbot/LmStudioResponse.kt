package org.dev.semaschatbot

/**
 * LM Studio API 응답을 담는 데이터 클래스
 * 
 * LM Studio API 호출 결과와 토큰 사용 정보를 포함합니다.
 * 
 * @param content 응답 내용 (텍스트)
 * @param usage 토큰 사용 정보 (없을 수 있음)
 * @param modelId 사용한 모델 ID
 */
data class LmStudioResponse(
    val content: String,
    val usage: LmStudioUsage?,
    val modelId: String
)

/**
 * LM Studio API 응답의 토큰 사용 정보
 * 
 * @param promptTokens 프롬프트 토큰 수
 * @param completionTokens 완료(응답) 토큰 수
 * @param totalTokens 총 토큰 수
 */
data class LmStudioUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)

