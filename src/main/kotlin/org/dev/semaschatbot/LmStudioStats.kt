package org.dev.semaschatbot

/**
 * LM Studio 통계 정보를 담는 데이터 클래스
 * 
 * LM Studio 모델 사용 시 수집되는 통계 정보를 표현합니다.
 * 
 * @param userId 로그인한 사용자 ID (로그인하지 않은 경우 null)
 * @param modelId 사용한 LM Studio 모델 ID
 * @param inputTokens 입력 토큰 수
 * @param outputTokens 출력 토큰 수
 * @param totalTokens 총 토큰 수 (inputTokens + outputTokens)
 * @param responseTime 응답 시간 (밀리초)
 */
data class LmStudioStats(
    val userId: Int?,
    val modelId: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    val responseTime: Long  // 밀리초
)

