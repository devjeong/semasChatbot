package org.dev.semaschatbot

import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 사용자 데이터 모델
 * @param id 사용자 ID (자동 생성)
 * @param username 사용자 아이디 (고유)
 * @param passwordHash 비밀번호 해시값
 * @param name 사용자 이름
 * @param role 사용자 권한 (USER, ADMIN, PREMIUM)
 * @param createdAt 계정 생성 시간
 * @param lastLogin 마지막 로그인 시간
 * @param isActive 계정 활성화 여부
 */
data class User(
    val id: Int = 0,
    val username: String,
    val passwordHash: String,
    val name: String,
    val role: UserRole = UserRole.USER,
    val createdAt: String = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
    val lastLogin: String? = null,
    val isActive: Boolean = true
) {
    /**
     * 비밀번호를 해시화합니다.
     * @param password 원본 비밀번호
     * @return SHA-256 해시값
     */
    companion object {
        fun hashPassword(password: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(password.toByteArray())
            return hash.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * 사용자 권한 열거형
 */
enum class UserRole {
    USER,      // 일반 사용자
    ADMIN,     // 관리자
    PREMIUM    // 프리미엄 사용자
}

/**
 * 사용량 통계 데이터 모델
 * @param id 통계 ID (자동 생성)
 * @param userId 사용자 ID
 * @param date 통계 날짜 (YYYY-MM-DD)
 * @param totalMessages 총 메시지 수
 * @param dailyMessages 일일 메시지 수
 * @param avgMessageLength 평균 메시지 길이
 * @param inputTokens 입력 토큰 수
 * @param outputTokens 출력 토큰 수
 * @param totalTokens 총 토큰 수
 * @param dailyTokens 일일 토큰 수
 * @param sessionCount 세션 수
 * @param totalSessionTime 총 세션 시간 (초)
 * @param lastActivity 마지막 활동 시간
 * @param apiCalls API 호출 횟수
 * @param successfulCalls 성공한 API 호출 수
 * @param failedCalls 실패한 API 호출 수
 * @param avgResponseTime 평균 응답 시간 (밀리초)
 * @param codeModificationRequests 코드 수정 요청 수
 * @param modifiedFiles 수정된 파일 수
 * @param modifiedLines 수정된 라인 수
 * @param indexingRequests 인덱싱 요청 수
 * @param indexedFiles 인덱싱된 파일 수
 * @param indexedChunks 인덱싱된 코드 청크 수
 * @param indexingTime 인덱싱 소요 시간 (밀리초)
 * @param dbConnections DB 연결 횟수
 * @param dbQueries DB 쿼리 수
 */
data class UsageStatistics(
    val id: Int = 0,
    val userId: Int,
    val date: String,  // YYYY-MM-DD 형식
    
    // 메시지 관련
    val totalMessages: Int = 0,
    val dailyMessages: Int = 0,
    val avgMessageLength: Double = 0.0,
    
    // 토큰 관련
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val totalTokens: Int = 0,
    val dailyTokens: Int = 0,
    
    // 세션 관련
    val sessionCount: Int = 0,
    val totalSessionTime: Int = 0,  // 초 단위
    val lastActivity: String? = null,
    
    // API 호출 관련
    val apiCalls: Int = 0,
    val successfulCalls: Int = 0,
    val failedCalls: Int = 0,
    val avgResponseTime: Double = 0.0,  // 밀리초 단위
    
    // 코드 수정 관련
    val codeModificationRequests: Int = 0,
    val modifiedFiles: Int = 0,
    val modifiedLines: Int = 0,
    
    // 인덱싱 관련
    val indexingRequests: Int = 0,
    val indexedFiles: Int = 0,
    val indexedChunks: Int = 0,
    val indexingTime: Int = 0,  // 밀리초 단위
    
    // DB 관련
    val dbConnections: Int = 0,
    val dbQueries: Int = 0
)

