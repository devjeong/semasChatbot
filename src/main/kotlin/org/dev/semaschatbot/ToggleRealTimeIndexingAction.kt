package org.dev.semaschatbot

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages

/**
 * 실시간 인덱싱을 토글하는 액션입니다.
 */
class ToggleRealTimeIndexingAction : AnAction(), DumbAware {
    
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        
        try {
            val realTimeIndexingService = project.getService(RealTimeIndexingService::class.java)
            val isActive = realTimeIndexingService.isActive()
            
            if (isActive) {
                // 실시간 인덱싱 중지
                val result = Messages.showYesNoDialog(
                    project,
                    "실시간 인덱싱을 중지하시겠습니까?\n\n중지하면 파일 변경사항이 자동으로 인덱싱되지 않습니다.",
                    "실시간 인덱싱 중지",
                    "중지",
                    "취소",
                    Messages.getQuestionIcon()
                )
                
                if (result == Messages.YES) {
                    realTimeIndexingService.stopRealTimeIndexing()
                    Messages.showInfoMessage(
                        project,
                        "실시간 인덱싱이 중지되었습니다.",
                        "실시간 인덱싱 중지됨"
                    )
                }
            } else {
                // 실시간 인덱싱 시작
                val result = Messages.showYesNoDialog(
                    project,
                    "실시간 인덱싱을 시작하시겠습니까?\n\n시작하면 파일 변경사항이 자동으로 인덱싱됩니다.",
                    "실시간 인덱싱 시작",
                    "시작",
                    "취소",
                    Messages.getQuestionIcon()
                )
                
                if (result == Messages.YES) {
                    realTimeIndexingService.startRealTimeIndexing()
                    Messages.showInfoMessage(
                        project,
                        "실시간 인덱싱이 시작되었습니다.",
                        "실시간 인덱싱 시작됨"
                    )
                }
            }
            
        } catch (e: Exception) {
            Messages.showErrorDialog(
                project,
                "실시간 인덱싱 토글 중 오류가 발생했습니다: ${e.message}",
                "토글 오류"
            )
        }
    }
    
    override fun update(event: AnActionEvent) {
        val project = event.project
        if (project != null) {
            try {
                val realTimeIndexingService = project.getService(RealTimeIndexingService::class.java)
                val isActive = realTimeIndexingService.isActive()
                
                event.presentation.isEnabledAndVisible = true
                event.presentation.text = if (isActive) {
                    "실시간 인덱싱 중지"
                } else {
                    "실시간 인덱싱 시작"
                }
                
            } catch (e: Exception) {
                event.presentation.isEnabledAndVisible = false
            }
        } else {
            event.presentation.isEnabledAndVisible = false
        }
    }
}
