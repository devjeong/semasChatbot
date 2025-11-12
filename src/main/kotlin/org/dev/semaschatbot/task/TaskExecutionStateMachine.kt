package org.dev.semaschatbot.task

/**
 * 작업 실행 상태 머신
 * 
 * 작업의 상태 전환을 관리하고, 작업 실행 순서를 제어합니다.
 * 
 * @param session 작업 세션
 */
class TaskExecutionStateMachine(private val session: TaskSession) {
    
    /**
     * 다음 작업으로 진행합니다.
     * 대기 중인 작업 중 order가 가장 낮은 작업을 선택하여 진행 상태로 변경합니다.
     * 
     * @return 다음 작업 또는 null (모든 작업이 완료/취소된 경우)
     */
    fun moveToNextTask(): Task? {
        val nextTask = session.tasks
            .filter { it.status == TaskStatus.PENDING }
            .minByOrNull { it.order }
        
        if (nextTask != null) {
            nextTask.status = TaskStatus.IN_PROGRESS
            session.status = SessionStatus.IN_PROGRESS
            println("[TaskExecutionStateMachine] 다음 작업 시작: ${nextTask.title} (ID: ${nextTask.id})")
        }
        
        return nextTask
    }
    
    /**
     * 현재 작업을 완료 처리합니다.
     * 
     * @param result 작업 실행 결과
     */
    fun completeCurrentTask(result: String) {
        val currentTask = getCurrentTask()
        currentTask?.let {
            it.status = TaskStatus.COMPLETED
            it.result = result
            println("[TaskExecutionStateMachine] 작업 완료: ${it.title} (ID: ${it.id})")
            
            // 모든 작업이 완료되었는지 확인
            if (session.isAllCompleted()) {
                session.status = SessionStatus.COMPLETED
                println("[TaskExecutionStateMachine] 모든 작업 완료")
            }
        }
    }
    
    /**
     * 현재 작업을 실패 처리합니다.
     * 
     * @param errorMessage 오류 메시지
     */
    fun failCurrentTask(errorMessage: String) {
        val currentTask = getCurrentTask()
        currentTask?.let {
            it.status = TaskStatus.FAILED
            it.result = "오류: $errorMessage"
            println("[TaskExecutionStateMachine] 작업 실패: ${it.title} (ID: ${it.id}) - $errorMessage")
        }
    }
    
    /**
     * 특정 작업을 취소합니다.
     * 
     * @param taskId 취소할 작업 ID
     * @return 취소된 작업 또는 null
     */
    fun cancelTask(taskId: String): Task? {
        val task = session.findTask(taskId)
        task?.let {
            if (it.status == TaskStatus.PENDING || it.status == TaskStatus.IN_PROGRESS) {
                it.status = TaskStatus.CANCELLED
                println("[TaskExecutionStateMachine] 작업 취소: ${it.title} (ID: ${it.id})")
            }
        }
        return task
    }
    
    /**
     * 전체 세션을 취소합니다.
     * 모든 대기 중이거나 진행 중인 작업을 취소 상태로 변경합니다.
     */
    fun cancelSession() {
        session.status = SessionStatus.CANCELLED
        session.tasks.forEach { task ->
            if (task.status == TaskStatus.PENDING || task.status == TaskStatus.IN_PROGRESS) {
                task.status = TaskStatus.CANCELLED
            }
        }
        println("[TaskExecutionStateMachine] 전체 세션 취소")
    }
    
    /**
     * 현재 작업을 반환합니다.
     * 
     * @return 현재 작업 또는 null
     */
    fun getCurrentTask(): Task? {
        return session.getCurrentTask()
    }
    
    /**
     * 모든 작업이 완료되었는지 확인합니다.
     * 
     * @return 모든 작업이 완료/취소되었으면 true
     */
    fun isAllCompleted(): Boolean {
        return session.isAllCompleted()
    }
    
    /**
     * 완료된 작업 목록을 반환합니다.
     * 
     * @return 완료된 작업 목록
     */
    fun getCompletedTasks(): List<Task> {
        return session.tasks.filter { it.status == TaskStatus.COMPLETED }
    }
    
    /**
     * 대기 중인 작업 목록을 반환합니다.
     * 
     * @return 대기 중인 작업 목록
     */
    fun getPendingTasks(): List<Task> {
        return session.tasks.filter { it.status == TaskStatus.PENDING }
    }
}

