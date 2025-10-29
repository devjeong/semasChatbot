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
        val editor = e.getData(CommonDataKeys.EDITOR) // 현재 활성화된 에디터 인스턴스를 가져옵니다.
        val project = e.project // 현재 프로젝트 인스턴스를 가져옵니다.

        if (editor == null || project == null) { // 에디터나 프로젝트가 없는 경우, 액션을 수행하지 않고 종료합니다.
            return
        }

        val selectedText = editor.selectionModel.selectedText // 에디터에서 현재 선택된 텍스트를 가져옵니다.
        // 현재 파일의 VirtualFile을 이벤트에서 우선 가져오고, 없으면 Document로부터 역추적합니다.
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
            ?: FileDocumentManager.getInstance().getFile(editor.document)

        if (selectedText.isNullOrEmpty()) { // 선택된 텍스트가 없는 경우, 액션을 수행하지 않고 종료합니다.
            return
        }

        val fileName = virtualFile?.name // 파일 이름을 가져옵니다.
        val lineNumber = editor.document.getLineNumber(editor.selectionModel.selectionStart) + 1 // 선택 시작 라인 번호를 가져옵니다 (0-based이므로 +1).
        val fileInfo = fileName?.let { "$it (라인: $lineNumber)" } ?: "(라인: $lineNumber)" // 파일 정보 문자열을 생성합니다.

        val chatService = project.service<ChatService>() // ChatService 인스턴스를 가져옵니다.

        val toolWindowManager = ToolWindowManager.getInstance(project) // ToolWindowManager 인스턴스를 가져옵니다.

        val toolWindow = toolWindowManager.getToolWindow("Protein26")

        // 선택 컨텍스트를 즉시 설정하여 UI 활성화 여부와 무관하게 전달되도록 합니다.
        chatService.setSelectionContext(selectedText, fileInfo)
        // 가능하면 툴윈도를 전면으로 활성화합니다.
        toolWindow?.activate(Runnable { })
    }

    /**
     * 액션의 가시성과 활성화 상태를 업데이트하는 메서드입니다.
     * 에디터가 활성화되어 있고 텍스트가 선택된 경우에만 액션을 활성화합니다.
     * @param e 액션 이벤트 객체
     */
    /*override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) // 현재 에디터 인스턴스를 가져옵니다.
        // 에디터가 존재하고, 선택된 텍스트가 비어있지 않은 경우에만 액션을 활성화하고 표시합니다.
        e.presentation.isEnabledAndVisible = editor != null && !editor.selectionModel.selectedText.isNullOrEmpty()
    }*/
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT // UI 관련 작업이므로 EDT 사용
    }
}
