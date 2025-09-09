package org.dev.semaschatbot

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import java.text.DecimalFormat

/**
 * 인덱싱 통계를 표시하는 액션입니다.
 */
class IndexingStatsAction : AnAction(), DumbAware {
    
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        
        try {
            val realTimeIndexingService = project.getService(RealTimeIndexingService::class.java)
            val stats = realTimeIndexingService.getAllStats()
            
            val message = formatStatsMessage(stats)
            
            Messages.showMessageDialog(
                project,
                message,
                "인덱싱 통계",
                Messages.getInformationIcon()
            )
            
        } catch (e: Exception) {
            Messages.showErrorDialog(
                project,
                "통계 조회 중 오류가 발생했습니다: ${e.message}",
                "통계 조회 오류"
            )
        }
    }
    
    private fun formatStatsMessage(stats: Map<String, Any>): String {
        val sb = StringBuilder()
        val df = DecimalFormat("#,###")
        
        // 서비스 상태
        val serviceStatus = stats["service_status"] as? Map<String, Any> ?: emptyMap()
        sb.append("=== 서비스 상태 ===\n")
        sb.append("상태: ${if (serviceStatus["is_active"] == true) "활성" else "비활성"}\n")
        sb.append("가동시간: ${formatUptime(serviceStatus["uptime_ms"] as? Long ?: 0)}\n")
        sb.append("처리된 파일: ${df.format(serviceStatus["total_files_processed"] ?: 0)}개\n\n")
        
        // 인덱싱 통계
        val indexingStats = stats["indexing_stats"] as? Map<String, Int> ?: emptyMap()
        sb.append("=== 인덱싱 통계 ===\n")
        sb.append("총 코드 조각: ${df.format(indexingStats["total_chunks"] ?: 0)}개\n")
        sb.append("파일: ${df.format(indexingStats["file"] ?: 0)}개\n")
        sb.append("클래스: ${df.format(indexingStats["class"] ?: 0)}개\n")
        sb.append("메서드: ${df.format(indexingStats["method"] ?: 0)}개\n")
        sb.append("필드: ${df.format(indexingStats["field"] ?: 0)}개\n\n")
        
        // 해시 통계
        val hashStats = stats["hash_stats"] as? Map<String, Any> ?: emptyMap()
        sb.append("=== 해시 통계 ===\n")
        sb.append("총 계산 횟수: ${df.format(hashStats["total_calculations"] ?: 0)}회\n")
        sb.append("해시 일치: ${df.format(hashStats["hash_matches"] ?: 0)}회\n")
        sb.append("해시 불일치: ${df.format(hashStats["hash_mismatches"] ?: 0)}회\n")
        sb.append("일치율: ${String.format("%.1f", hashStats["match_rate_percent"] ?: 0.0)}%\n")
        sb.append("추적 중인 파일: ${df.format(hashStats["tracked_files"] ?: 0)}개\n")
        sb.append("메모리 사용량: ${String.format("%.2f", hashStats["memory_usage_mb"] ?: 0.0)}MB\n\n")
        
        // 배치 처리 통계
        val batchStats = stats["batch_stats"] as? Map<String, Any> ?: emptyMap()
        sb.append("=== 배치 처리 통계 ===\n")
        sb.append("처리 중: ${if (batchStats["is_processing"] == true) "예" else "아니오"}\n")
        sb.append("대기 중인 재인덱싱: ${df.format(batchStats["pending_reindex"] ?: 0)}개\n")
        sb.append("대기 중인 제거: ${df.format(batchStats["pending_remove"] ?: 0)}개\n")
        sb.append("배치 크기: ${df.format(batchStats["batch_size"] ?: 0)}\n")
        sb.append("활성 작업: ${df.format(batchStats["active_operations"] ?: 0)}개\n\n")
        
        // 성능 메트릭
        val performanceMetrics = stats["performance_metrics"] as? Map<String, Any> ?: emptyMap()
        sb.append("=== 성능 메트릭 ===\n")
        sb.append("총 배치 수: ${df.format(performanceMetrics["total_batches"] ?: 0)}개\n")
        sb.append("총 파일 수: ${df.format(performanceMetrics["total_files"] ?: 0)}개\n")
        sb.append("평균 배치 처리 시간: ${String.format("%.1f", performanceMetrics["avg_batch_processing_time"] ?: 0.0)}ms\n")
        sb.append("평균 파일 인덱싱 시간: ${String.format("%.1f", performanceMetrics["avg_file_indexing_time"] ?: 0.0)}ms\n")
        sb.append("최대 배치 처리 시간: ${df.format(performanceMetrics["max_batch_processing_time"] ?: 0)}ms\n")
        sb.append("최대 파일 인덱싱 시간: ${df.format(performanceMetrics["max_file_indexing_time"] ?: 0)}ms\n\n")
        
        // 파일 변경 통계
        val fileChangeStats = stats["file_change_stats"] as? Map<String, Any> ?: emptyMap()
        sb.append("=== 파일 변경 통계 ===\n")
        sb.append("총 변경 횟수: ${df.format(fileChangeStats["total_changes"] ?: 0)}회\n")
        sb.append("추적 중인 파일: ${df.format(fileChangeStats["tracked_files"] ?: 0)}개\n")
        
        return sb.toString()
    }
    
    private fun formatUptime(uptimeMs: Long): String {
        val seconds = uptimeMs / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        
        return when {
            days > 0 -> "${days}일 ${hours % 24}시간 ${minutes % 60}분"
            hours > 0 -> "${hours}시간 ${minutes % 60}분"
            minutes > 0 -> "${minutes}분 ${seconds % 60}초"
            else -> "${seconds}초"
        }
    }
    
    override fun update(event: AnActionEvent) {
        val project = event.project
        event.presentation.isEnabledAndVisible = project != null
    }
}
