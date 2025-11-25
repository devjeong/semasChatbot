package org.dev.semaschatbot

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 세션 데이터 모델
 * 
 * 로그인 정보를 세션으로 관리하기 위한 데이터 클래스입니다.
 * 
 * 성능 최적화:
 * - data class 사용으로 메모리 효율성 향상
 * - 불변성 보장으로 스레드 안전성 확보
 */
data class Session(
    val user: User,
    val loginTime: String = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
    val lastAccessTime: String = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
) {
    /**
     * 세션의 사용자명을 반환합니다.
     */
    fun getUsername(): String = user.username
    
    /**
     * 세션의 사용자 ID를 반환합니다.
     */
    fun getUserId(): Int = user.id
    
    /**
     * 세션의 사용자 이름을 반환합니다.
     */
    fun getUserName(): String = user.name
    
    /**
     * 세션의 사용자 역할을 반환합니다.
     */
    fun getUserRole(): UserRole = user.role
    
    /**
     * 세션을 갱신합니다. (lastAccessTime 업데이트)
     */
    fun refresh(): Session {
        return this.copy(
            lastAccessTime = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        )
    }
}

