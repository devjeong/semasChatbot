package org.dev.semaschatbot.task

/**
 * 작업 단위를 나타내는 데이터 클래스
 * 
 * 사용자 요구사항을 세분화한 개별 작업을 표현합니다.
 * 각 작업은 독립적으로 실행 가능하며, 순서가 지정될 수 있습니다.
 * 
 * @param id 작업 고유 식별자 (예: "task_1", "task_2")
 * @param title 작업 제목 (간단한 설명)
 * @param description 작업 상세 설명 (구체적인 수행 내용)
 * @param status 작업 상태 (PENDING, IN_PROGRESS, COMPLETED, CANCELLED, FAILED)
 * @param prompt 작업 실행 시 사용할 프롬프트 (생성 전에는 null)
 * @param result 작업 실행 결과 (완료 후 저장)
 * @param order 작업 실행 순서 (낮은 숫자가 먼저 실행됨)
 */
data class Task(
    val id: String,
    val title: String,
    val description: String,
    var status: TaskStatus,
    var prompt: String? = null,
    var result: String? = null,
    val order: Int
) {
    /**
     * 작업이 완료되었는지 확인
     * @return 완료 또는 취소 상태이면 true
     */
    fun isCompleted(): Boolean {
        return status == TaskStatus.COMPLETED || status == TaskStatus.CANCELLED
    }
    
    /**
     * 작업이 실행 가능한 상태인지 확인
     * @return 대기 중이면 true
     */
    fun isExecutable(): Boolean {
        return status == TaskStatus.PENDING
    }
    
    /**
     * 작업이 진행 중인지 확인
     * @return 진행 중이면 true
     */
    fun isInProgress(): Boolean {
        return status == TaskStatus.IN_PROGRESS
    }
}

