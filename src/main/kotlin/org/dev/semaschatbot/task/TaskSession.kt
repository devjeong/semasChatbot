package org.dev.semaschatbot.task

/**
 * 작업 세션을 관리하는 클래스
 * 
 * 사용자 요구사항에 대한 전체 작업 세트를 관리합니다.
 * 여러 작업을 그룹으로 묶어 순차적으로 실행할 수 있도록 합니다.
 * 
 * @param id 세션 고유 식별자 (UUID)
 * @param requirement 사용자 요구사항 (원본 요청 내용)
 * @param tasks 작업 목록 (가변 리스트)
 * @param createdAt 세션 생성 시간 (밀리초 타임스탬프)
 * @param status 세션 상태 (CREATED, APPROVED, IN_PROGRESS, COMPLETED, CANCELLED)
 */
class TaskSession(
    val id: String,
    val requirement: String,
    val tasks: MutableList<Task>,
    val createdAt: Long = System.currentTimeMillis(),
    var status: SessionStatus = SessionStatus.CREATED
) {
    /**
     * 현재 진행 중인 작업을 반환합니다.
     * 진행 중인 작업이 없으면 다음 대기 중인 작업을 반환합니다.
     * 
     * @return 현재 작업 또는 null (모든 작업이 완료/취소된 경우)
     */
    fun getCurrentTask(): Task? {
        return tasks.firstOrNull { it.status == TaskStatus.IN_PROGRESS }
            ?: tasks.firstOrNull { it.status == TaskStatus.PENDING }
    }
    
    /**
     * 모든 작업이 완료되었는지 확인합니다.
     * 완료 또는 취소 상태의 작업만 있는 경우 true를 반환합니다.
     * 
     * @return 모든 작업이 완료/취소되었으면 true
     */
    fun isAllCompleted(): Boolean {
        return tasks.all { 
            it.status == TaskStatus.COMPLETED || 
            it.status == TaskStatus.CANCELLED 
        }
    }
    
    /**
     * 작업 진행률을 계산합니다.
     * 완료된 작업 수를 전체 작업 수로 나눈 비율을 반환합니다.
     * 
     * @return 진행률 (0.0 ~ 1.0)
     */
    fun getProgress(): Double {
        val completed = tasks.count { it.status == TaskStatus.COMPLETED }
        return if (tasks.isEmpty()) 0.0 else completed.toDouble() / tasks.size
    }
    
    /**
     * 완료된 작업 수를 반환합니다.
     * 
     * @return 완료된 작업 수
     */
    fun getCompletedCount(): Int {
        return tasks.count { it.status == TaskStatus.COMPLETED }
    }
    
    /**
     * 전체 작업 수를 반환합니다.
     * 
     * @return 전체 작업 수
     */
    fun getTotalCount(): Int {
        return tasks.size
    }
    
    /**
     * 특정 ID를 가진 작업을 찾습니다.
     * 
     * @param taskId 작업 ID
     * @return 찾은 작업 또는 null
     */
    fun findTask(taskId: String): Task? {
        return tasks.find { it.id == taskId }
    }
    
    /**
     * 작업 목록을 순서대로 정렬합니다.
     * order 필드를 기준으로 오름차순 정렬합니다.
     */
    fun sortTasksByOrder() {
        tasks.sortBy { it.order }
    }
}

