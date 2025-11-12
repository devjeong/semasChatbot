package org.dev.semaschatbot.task

/**
 * 작업 세션 상태를 나타내는 열거형
 * 
 * 전체 작업 세션의 생명주기를 추적하기 위한 상태 값들입니다.
 */
enum class SessionStatus {
    /** 생성됨 - 작업목록만 생성되고 사용자 승인 대기 중 */
    CREATED,
    
    /** 사용자 승인됨 - 사용자가 작업 진행을 승인함 */
    APPROVED,
    
    /** 진행 중 - 하나 이상의 작업이 실행 중 */
    IN_PROGRESS,
    
    /** 완료 - 모든 작업이 완료됨 */
    COMPLETED,
    
    /** 취소됨 - 사용자에 의해 전체 세션이 취소됨 */
    CANCELLED
}

