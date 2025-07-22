package org.dev.semaschatbot

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.ide.DataManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.application.ApplicationManager

class CodeActionLineMarkerProvider : LineMarkerProvider {
    private val logger = Logger.getInstance(CodeActionLineMarkerProvider::class.java)

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        logger.info("getLineMarkerInfo called for element: ${element.text}, type: ${element.javaClass.simpleName}, file: ${element.containingFile?.name}, thread: ${Thread.currentThread().name}")

        val project = element.project
        val chatService = project.service<ChatService>()
        if (chatService.pendingChanges.isEmpty()) {
            logger.info("No pending changes found")
            return null
        }

        val containingFile = element.containingFile
        if (containingFile == null) {
            logger.warn("No containing file for element: ${element.text}")
            return null
        }

        // Document 가져오기 전에 EDT에서 동기화
        var document: com.intellij.openapi.editor.Document? = null
        ApplicationManager.getApplication().invokeAndWait {
            document?.let { PsiDocumentManager.getInstance(project).commitDocument(it) }
            document = PsiDocumentManager.getInstance(project).getDocument(containingFile)
        }

        if (document == null) {
            logger.warn("Failed to get Document for file: ${containingFile.name}, isPhysical: ${containingFile.virtualFile?.isInLocalFileSystem}")
            return null
        }

        logger.info("Processing document: ${containingFile.name}")

        chatService.pendingChanges.forEach { change ->
            if (change.document == document) {
                val changeStartLine = document!!.getLineNumber(change.startOffset)
                val elementStartLine = document!!.getLineNumber(element.textRange.startOffset)

                logger.info("Checking change at line $changeStartLine, element at line $elementStartLine")

                if (changeStartLine == elementStartLine) {
                    val lineStartOffset = document!!.getLineStartOffset(changeStartLine)
                    val lineEndOffset = document!!.getLineEndOffset(changeStartLine)
                    val lineText = document!!.getText().substring(lineStartOffset, lineEndOffset).trim()

                    if (!lineText.contains(change.originalCode.trim())) {
                        logger.warn("Original code not found in line: $lineText, expected: ${change.originalCode}")
                        return@forEach
                    }

                    val lineStartElement = containingFile.viewProvider.findElementAt(lineStartOffset)?.parent
                    if (element != lineStartElement) {
                        logger.info("Skipping non-line-start element: ${element.text}")
                        return@forEach
                    }

                    logger.info("Adding LineMarker for change at offset ${change.startOffset}")

                    val actionGroup = DefaultActionGroup().apply {
                        add(object : AnAction("✅ 적용", "제안된 코드 변경을 적용합니다.", AllIcons.Actions.Commit) {
                            override fun actionPerformed(e: AnActionEvent) {
                                logger.info("Applying change: ${change.modifiedCode}")
                                chatService.applyChange(change)
                                e.project?.let {
                                    val editor = FileEditorManager.getInstance(it).selectedTextEditor
                                    editor?.markupModel?.removeAllHighlighters()
                                    //com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx.getInstanceEx(it).scheduleUpdate(containingFile)
                                }
                            }
                        })
                        add(object : AnAction("❌ 거절", "제안된 코드 변경을 거절합니다.", AllIcons.Actions.Cancel) {
                            override fun actionPerformed(e: AnActionEvent) {
                                logger.info("Rejecting change: ${change.modifiedCode}")
                                chatService.rejectChange(change)
                                e.project?.let {
                                    val editor = FileEditorManager.getInstance(it).selectedTextEditor
                                    editor?.markupModel?.removeAllHighlighters()
                                    //com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx.getInstanceEx(it).scheduleUpdate(containingFile)
                                }
                            }
                        })
                    }

                    return LineMarkerInfo(
                        element,
                        element.textRange,
                        AllIcons.Actions.Edit,
                        { "AI 코드 수정 제안" },
                        { _, elt ->
                            val editor = FileEditorManager.getInstance(elt.project).selectedTextEditor
                            if (editor != null) {
                                JBPopupFactory.getInstance()
                                    .createActionGroupPopup(
                                        "AI 제안",
                                        actionGroup,
                                        DataManager.getInstance().getDataContext(editor.component),
                                        JBPopupFactory.ActionSelectionAid.MNEMONICS,
                                        false
                                    )
                                    .showInBestPositionFor(editor)
                            }
                        },
                        GutterIconRenderer.Alignment.CENTER,
                        { "AI 코드 수정 제안" }
                    )
                }
            }
        }
        return null
    }
}