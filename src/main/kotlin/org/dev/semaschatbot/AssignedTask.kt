package org.dev.semaschatbot

/**
 * 할당된 작업 데이터 모델
 * MCP 서버의 get_assigned_tasks 응답 형식에 맞춰 정의
 * 
 * 성능 최적화:
 * - data class 사용으로 메모리 효율성 향상
 * - nullable 필드로 불필요한 데이터 처리 방지
 */
data class AssignedTask(
    val id: Int,
    val requirementId: Int? = null,
    val requirementTitle: String? = null,
    val taskNumber: Int? = null,
    val title: String,
    val description: String? = null,
    val status: String,  // PENDING, IN_PROGRESS, REVIEW, COMPLETED, BLOCKED
    val priority: String? = null,  // LOW, MEDIUM, HIGH, CRITICAL
    val estimatedHours: Double? = null,
    val actualHours: Double? = null,
    val assigneeName: String? = null,
    val startDate: String? = null,
    val dueDate: String? = null,
    val completedDate: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
) {
    /**
     * 상태에 따른 한글 표시명 반환
     */
    fun getStatusDisplayName(): String {
        return when (status.uppercase()) {
            "PENDING" -> "대기"
            "IN_PROGRESS" -> "진행중"
            "REVIEW" -> "검토"
            "COMPLETED" -> "완료"
            "BLOCKED" -> "차단"
            else -> status
        }
    }
    
    /**
     * 우선순위에 따른 한글 표시명 반환
     */
    fun getPriorityDisplayName(): String {
        return when (priority?.uppercase()) {
            "LOW" -> "낮음"
            "MEDIUM" -> "보통"
            "HIGH" -> "높음"
            "CRITICAL" -> "긴급"
            else -> priority ?: "-"
        }
    }
    
    /**
     * 상태에 따른 색상 반환 (UI 표시용)
     */
    fun getStatusColor(): java.awt.Color {
        return when (status.uppercase()) {
            "PENDING" -> java.awt.Color(150, 150, 150)  // 회색
            "IN_PROGRESS" -> java.awt.Color(52, 152, 219)  // 파란색
            "REVIEW" -> java.awt.Color(241, 196, 15)  // 노란색
            "COMPLETED" -> java.awt.Color(46, 204, 113)  // 초록색
            "BLOCKED" -> java.awt.Color(231, 76, 60)  // 빨간색
            else -> java.awt.Color(100, 100, 100)
        }
    }
    
    /**
     * 우선순위에 따른 색상 반환 (UI 표시용)
     */
    fun getPriorityColor(): java.awt.Color {
        return when (priority?.uppercase()) {
            "LOW" -> java.awt.Color(150, 150, 150)  // 회색
            "MEDIUM" -> java.awt.Color(241, 196, 15)  // 노란색
            "HIGH" -> java.awt.Color(231, 76, 60)  // 빨간색
            "CRITICAL" -> java.awt.Color(142, 68, 173)  // 보라색
            else -> java.awt.Color(100, 100, 100)
        }
    }
}

