package org.dev.semaschatbot

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages

/**
 * 강제 재인덱싱을 수행하는 액션입니다.
 */
class ForceReindexingAction : AnAction(), DumbAware {
    
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        
        try {
            val realTimeIndexingService = project.getService(RealTimeIndexingService::class.java)
            
            if (!realTimeIndexingService.isActive()) {
                Messages.showWarningDialog(
                    project,
                    "실시간 인덱싱 서비스가 비활성화되어 있습니다.",
                    "재인덱싱 실패"
                )
                return
            }
            
            // 사용자 확인
            val result = Messages.showYesNoDialog(
                project,
                "전체 프로젝트를 재인덱싱하시겠습니까?\n\n이 작업은 시간이 걸릴 수 있습니다.",
                "강제 재인덱싱",
                "재인덱싱 시작",
                "취소",
                Messages.getQuestionIcon()
            )
            
            if (result == Messages.YES) {
                // 백그라운드에서 재인덱싱 수행
                project.getService(RealTimeIndexingService::class.java).forceReindexing()
                
                Messages.showInfoMessage(
                    project,
                    "재인덱싱이 시작되었습니다.\n완료되면 알림을 받게 됩니다.",
                    "재인덱싱 시작됨"
                )
            }
            
        } catch (e: Exception) {
            Messages.showErrorDialog(
                project,
                "재인덱싱 중 오류가 발생했습니다: ${e.message}",
                "재인덱싱 오류"
            )
        }
    }
    
    override fun update(event: AnActionEvent) {
        val project = event.project
        event.presentation.isEnabledAndVisible = project != null
    }
}
