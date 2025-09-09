package org.dev.semaschatbot

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 파일 변경을 감지하고 인덱싱을 트리거하는 리스너입니다.
 * VirtualFileListener를 구현하여 파일 생성, 수정, 삭제를 감지합니다.
 */
class FileChangeListener(
    private val project: Project,
    private val indexingService: CodeIndexingService,
    private val batchProcessor: BatchIndexingProcessor
) : VirtualFileListener {
    
    private val changeCount = AtomicLong(0)
    private val lastChangeTime = ConcurrentHashMap<String, Long>()
    
    /**
     * 파일 내용이 변경되었을 때 호출됩니다.
     */
    override fun contentsChanged(event: VirtualFileEvent) {
        val file = event.file
        if (shouldIndexFile(file)) {
            val currentTime = System.currentTimeMillis()
            val filePath = file.path
            val lastTime = lastChangeTime[filePath] ?: 0
            
            // 너무 빈번한 변경을 방지 (최소 500ms 간격)
            if (currentTime - lastTime > 500) {
                lastChangeTime[filePath] = currentTime
                logFileChange(file, "CONTENTS_CHANGED")
                batchProcessor.scheduleReindexing(file)
                changeCount.incrementAndGet()
            }
        }
    }
    
    /**
     * 새 파일이 생성되었을 때 호출됩니다.
     */
    override fun fileCreated(event: VirtualFileEvent) {
        val file = event.file
        if (shouldIndexFile(file)) {
            logFileChange(file, "FILE_CREATED")
            batchProcessor.scheduleReindexing(file)
            changeCount.incrementAndGet()
        }
    }
    
    /**
     * 파일이 삭제되었을 때 호출됩니다.
     */
    override fun fileDeleted(event: VirtualFileEvent) {
        val file = event.file
        if (shouldIndexFile(file)) {
            logFileChange(file, "FILE_DELETED")
            batchProcessor.scheduleFileRemoval(file)
            changeCount.incrementAndGet()
        }
    }
    
    /**
     * 파일이 이동되었을 때 호출됩니다.
     */
    override fun fileMoved(event: VirtualFileMoveEvent) {
        val file = event.file
        if (shouldIndexFile(file)) {
            logFileChange(file, "FILE_MOVED")
            batchProcessor.scheduleReindexing(file)
            changeCount.incrementAndGet()
        }
    }
    
    /**
     * 파일이 복사되었을 때 호출됩니다.
     */
    override fun fileCopied(event: VirtualFileCopyEvent) {
        val file = event.file
        if (shouldIndexFile(file)) {
            logFileChange(file, "FILE_COPIED")
            batchProcessor.scheduleReindexing(file)
            changeCount.incrementAndGet()
        }
    }
    
    /**
     * 파일이 인덱싱 대상인지 확인합니다.
     */
    private fun shouldIndexFile(file: VirtualFile): Boolean {
        if (file.isDirectory) return false
        
        val extension = file.extension?.lowercase() ?: return false
        val supportedExtensions = setOf("java", "kt", "js", "ts", "vue", "sql", "xml", "yml", "yaml", "json")
        if (!supportedExtensions.contains(extension)) return false
        
        // 제외할 디렉토리/파일 패턴
        val path = file.path.lowercase()
        val excludePatterns = listOf(
            "/build/", "/target/", "/out/", "/.gradle/", "/.idea/",
            "/node_modules/", "/dist/", "/.git/", "/bin/", "/.vscode/"
        )
        
        return excludePatterns.none { path.contains(it) }
    }
    
    /**
     * 파일 변경을 로깅합니다.
     */
    private fun logFileChange(file: VirtualFile, action: String) {
        if (isDebugMode()) {
            println("[FileChangeListener] $action: ${file.path}")
        }
    }
    
    /**
     * 디버그 모드인지 확인합니다.
     */
    private fun isDebugMode(): Boolean {
        return System.getProperty("semas.debug", "false").toBoolean()
    }
    
    /**
     * 변경 통계를 반환합니다.
     */
    fun getChangeStats(): Map<String, Any> {
        return mapOf(
            "total_changes" to changeCount.get().toInt(),
            "tracked_files" to lastChangeTime.size,
            "last_change_times" to lastChangeTime.toMap()
        )
    }
    
    /**
     * 통계를 초기화합니다.
     */
    fun resetStats() {
        changeCount.set(0)
        lastChangeTime.clear()
    }
}
