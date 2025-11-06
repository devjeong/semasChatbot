package org.dev.semaschatbot

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.fileEditor.FileDocumentManager

/**
 * `SendSelectionToChatAction`은 IntelliJ IDEA의 액션(Action) 클래스입니다.
 * 사용자가 에디터에서 텍스트를 선택한 후 특정 메뉴 항목을 클릭했을 때,
 * 선택된 텍스트를 챗봇으로 전송하는 기능을 수행합니다.
 * `AnAction`을 상속받아 IntelliJ 플랫폼에 통합됩니다.
 */
class SendSelectionToChatAction : AnAction() {
    /**
     * 액션이 수행될 때 호출되는 메서드입니다.
     * 에디터에서 선택된 텍스트를 가져와 챗봇 서비스로 전송합니다.
     * @param e 액션 이벤트 객체로, 현재 프로젝트 및 에디터 정보를 포함합니다.
     */
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val project = e.project

        if (editor == null || project == null) {
            println("[SendSelectionToChat] 에디터 또는 프로젝트가 없습니다.")
            return
        }

        val selectionModel = editor.selectionModel
        
        if (!selectionModel.hasSelection()) {
            println("[SendSelectionToChat] 선택된 텍스트가 없습니다.")
            return
        }

        val selectedText = selectionModel.selectedText
        
        if (selectedText.isNullOrBlank()) {
            println("[SendSelectionToChat] 선택된 텍스트가 비어있습니다.")
            return
        }

        // 현재 파일의 VirtualFile을 이벤트에서 우선 가져오고, 없으면 Document로부터 역추적합니다.
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
            ?: FileDocumentManager.getInstance().getFile(editor.document)

        val fileName = virtualFile?.name ?: "Unknown"
        val startLine = editor.document.getLineNumber(selectionModel.selectionStart) + 1 // 1-based
        val endLine = editor.document.getLineNumber(selectionModel.selectionEnd) + 1 // 1-based
        
        val fileInfo = if (startLine == endLine) {
            "$fileName (라인: $startLine)"
        } else {
            "$fileName (라인: $startLine-$endLine)"
        }

        println("[SendSelectionToChat] 선택된 텍스트: ${selectedText.take(50)}... (${selectedText.length}자)")
        println("[SendSelectionToChat] 파일 정보: $fileInfo")

        try {
            val chatService = project.service<ChatService>()
            
            // 선택 컨텍스트 설정
            chatService.setSelectionContext(selectedText, fileInfo)
            
            // 채팅창에 선택된 코드 미리보기 표시
            val previewMessage = buildString {
                appendLine("📋 선택된 코드가 컨텍스트로 설정되었습니다.")
                appendLine("📄 파일: $fileInfo")
                appendLine("📝 선택된 코드 (${selectedText.length}자):")
                appendLine()
                appendLine("```")
                // 선택된 코드의 처음 500자만 미리보기로 표시
                val preview = if (selectedText.length > 500) {
                    selectedText.take(500) + "\n... (${selectedText.length - 500}자 더 있음)"
                } else {
                    selectedText
                }
                appendLine(preview)
                appendLine("```")
                appendLine()
                appendLine("💡 이제 이 코드에 대해 질문하거나 수정 요청을 할 수 있습니다!")
            }
            
            chatService.sendMessage(previewMessage, isUser = false)
            
            // 툴윈도우 활성화
            val toolWindowManager = ToolWindowManager.getInstance(project)
            val toolWindow = toolWindowManager.getToolWindow("Protein26")
            toolWindow?.activate(null)
            
            println("[SendSelectionToChat] 선택 컨텍스트 설정 완료 및 툴윈도우 활성화")
            
        } catch (e: Exception) {
            println("[SendSelectionToChat] 오류 발생: ${e.message}")
            e.printStackTrace()
            
            // 사용자에게 오류 알림
            com.intellij.openapi.ui.Messages.showErrorDialog(
                project,
                "선택된 코드를 챗봇으로 전송하는 중 오류가 발생했습니다:\n${e.message}",
                "Send Selection to Chat 오류"
            )
        }
    }

    /**
     * 액션의 가시성과 활성화 상태를 업데이트하는 메서드입니다.
     * 에디터가 활성화되어 있고 텍스트가 선택된 경우에만 액션을 활성화합니다.
     * @param e 액션 이벤트 객체
     */
    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val project = e.project
        
        // 에디터가 존재하고, 선택된 텍스트가 비어있지 않은 경우에만 액션을 활성화하고 표시합니다.
        val hasSelection = editor != null && 
                          editor.selectionModel.hasSelection() && 
                          !editor.selectionModel.selectedText.isNullOrBlank()
        
        e.presentation.isEnabledAndVisible = project != null && hasSelection
        
        // 툴팁 업데이트
        if (e.presentation.isEnabledAndVisible) {
            val selectedText = editor?.selectionModel?.selectedText
            val preview = selectedText?.take(50)?.replace("\n", " ")?.let { 
                if (selectedText.length > 50) "$it..." else it 
            } ?: ""
            e.presentation.text = "Send Selection to Chat${if (preview.isNotEmpty()) ": $preview" else ""}"
        }
    }
    
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT // UI 관련 작업이므로 EDT 사용
    }
}
