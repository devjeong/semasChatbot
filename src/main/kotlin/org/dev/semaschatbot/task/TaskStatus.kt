package org.dev.semaschatbot.task

/**
 * 작업 상태를 나타내는 열거형
 * 
 * 작업의 생명주기를 추적하기 위한 상태 값들입니다.
 */
enum class TaskStatus {
    /** 대기 중 - 작업목록에 생성되었지만 아직 실행되지 않음 */
    PENDING,
    
    /** 진행 중 - 현재 실행 중인 작업 */
    IN_PROGRESS,
    
    /** 완료 - 작업이 성공적으로 완료됨 */
    COMPLETED,
    
    /** 취소됨 - 사용자에 의해 취소된 작업 */
    CANCELLED,
    
    /** 실패 - 작업 실행 중 오류가 발생하여 실패함 */
    FAILED
}

