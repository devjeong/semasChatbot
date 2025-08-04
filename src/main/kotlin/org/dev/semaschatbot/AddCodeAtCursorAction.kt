package org.dev.semaschatbot

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.wm.ToolWindowManager

/**
 * `AddCodeAtCursorAction`은 현재 커서 위치에서 새로운 코드를 생성하는 액션 클래스입니다.
 * 사용자가 Ctrl+Shift+N을 눌렀을 때, 현재 커서 위치 정보를 챗봇으로 전송하여
 * 새로운 코드를 생성하고 diff 창을 통해 확인 후 적용할 수 있습니다.
 */
class AddCodeAtCursorAction : AnAction() {
    
    /**
     * 액션이 수행될 때 호출되는 메서드입니다.
     * 현재 커서 위치 정보를 가져와 챗봇 서비스로 전송합니다.
     * @param e 액션 이벤트 객체로, 현재 프로젝트 및 에디터 정보를 포함합니다.
     */
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) // 현재 활성화된 에디터 인스턴스를 가져옵니다.
        val project = e.project // 현재 프로젝트 인스턴스를 가져옵니다.

        if (editor == null || project == null) { // 에디터나 프로젝트가 없는 경우, 액션을 수행하지 않고 종료합니다.
            return
        }

        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) // 현재 파일의 VirtualFile 인스턴스를 가져옵니다.
        val document = editor.document
        val caretModel = editor.caretModel
        val currentOffset = caretModel.offset // 현재 커서 위치의 오프셋
        val currentLine = document.getLineNumber(currentOffset) + 1 // 현재 라인 번호 (1-based)
        val lineStartOffset = document.getLineStartOffset(currentLine - 1)
        val lineEndOffset = document.getLineEndOffset(currentLine - 1)
        val currentLineText = document.getText(com.intellij.openapi.util.TextRange(lineStartOffset, lineEndOffset))

        val fileName = virtualFile?.name // 파일 이름을 가져옵니다.
        val fileInfo = if (fileName != null) "$fileName (라인: $currentLine)" else "라인: $currentLine"

        // 현재 파일의 전체 컨텍스트도 함께 전송 (더 나은 코드 생성을 위해)
        val fullFileContent = document.text
        val totalLines = document.lineCount

        val chatService = project.service<ChatService>() // ChatService 인스턴스를 가져옵니다.

        val toolWindowManager = ToolWindowManager.getInstance(project) // ToolWindowManager 인스턴스를 가져옵니다.
        val toolWindow = toolWindowManager.getToolWindow("소진공개발AI(Beta)")

        toolWindow?.activate(Runnable {
            chatService.setCursorContext(
                cursorLine = currentLine,
                currentLineText = currentLineText,
                fileInfo = fileInfo,
                fullFileContent = fullFileContent,
                totalLines = totalLines,
                fileName = fileName ?: "Unknown"
            )
        })
    }

    /**
     * 액션 업데이트 스레드를 지정합니다.
     * @return EDT 스레드 사용
     */
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT // UI 관련 작업이므로 EDT 사용
    }
} 