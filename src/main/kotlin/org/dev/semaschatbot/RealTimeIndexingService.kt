package org.dev.semaschatbot

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.editor.EditorFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 실시간 인덱싱을 관리하는 통합 서비스입니다.
 * 파일 변경 감지, 배치 처리, 해시 기반 중복 방지를 모두 통합하여 관리합니다.
 */
@Service(Service.Level.PROJECT)
class RealTimeIndexingService(private val project: Project) {
    
    private val indexingService = CodeIndexingService(project)
    private val hashService = FileHashService(project)
    private val batchProcessor = BatchIndexingProcessor(project, indexingService, hashService)
    private val fileChangeListener = FileChangeListener(project, indexingService, batchProcessor)
    
    private val isActive = AtomicBoolean(false)
    private val startTime = AtomicLong(0)
    private val totalFilesProcessed = AtomicLong(0)
    
    /**
     * 실시간 인덱싱을 시작합니다.
     */
    fun startRealTimeIndexing() {
        if (isActive.get()) {
            println("[RealTimeIndexingService] 실시간 인덱싱이 이미 활성화되어 있습니다.")
            return
        }
        
        try {
            startTime.set(System.currentTimeMillis())
            
            // 초기 인덱싱 수행
            performInitialIndexing()
            
            // 리스너 등록
            registerListeners()
            
            isActive.set(true)
            
            println("[RealTimeIndexingService] 실시간 인덱싱이 시작되었습니다.")
            logServiceStatus()
            
        } catch (e: Exception) {
            println("[RealTimeIndexingService] 실시간 인덱싱 시작 중 오류 발생: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * 실시간 인덱싱을 중지합니다.
     */
    fun stopRealTimeIndexing() {
        if (!isActive.get()) {
            println("[RealTimeIndexingService] 실시간 인덱싱이 이미 비활성화되어 있습니다.")
            return
        }
        
        try {
            // 리스너 해제
            unregisterListeners()
            
            // 배치 처리 완료 대기
            batchProcessor.forceProcessBatch()
            
            // 리소스 정리
            batchProcessor.shutdown()
            
            isActive.set(false)
            
            println("[RealTimeIndexingService] 실시간 인덱싱이 중지되었습니다.")
            logServiceStatus()
            
        } catch (e: Exception) {
            println("[RealTimeIndexingService] 실시간 인덱싱 중지 중 오류 발생: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * 초기 인덱싱을 수행합니다.
     */
    private fun performInitialIndexing() {
        // 백그라운드 스레드에서 인덱싱 수행
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                println("[RealTimeIndexingService] 초기 인덱싱을 시작합니다...")
                
                val startTime = System.currentTimeMillis()
                val chunkCount = indexingService.indexProject()
                val endTime = System.currentTimeMillis()
                
                totalFilesProcessed.set(chunkCount.toLong())
                
                println("[RealTimeIndexingService] 초기 인덱싱 완료: $chunkCount 개 코드 조각 (${endTime - startTime}ms)")
                
                // 해시 캐시 정리
                hashService.cleanupHashCache()
                
            } catch (e: Exception) {
                println("[RealTimeIndexingService] 초기 인덱싱 중 오류 발생: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    
    /**
     * 리스너들을 등록합니다.
     */
    private fun registerListeners() {
        try {
            VirtualFileManager.getInstance().addVirtualFileListener(fileChangeListener)
            println("[RealTimeIndexingService] VirtualFileListener 등록 완료")
        } catch (e: Exception) {
            println("[RealTimeIndexingService] VirtualFileListener 등록 실패: ${e.message}")
        }
    }
    
    /**
     * 리스너들을 해제합니다.
     */
    private fun unregisterListeners() {
        try {
            VirtualFileManager.getInstance().removeVirtualFileListener(fileChangeListener)
            println("[RealTimeIndexingService] VirtualFileListener 해제 완료")
        } catch (e: Exception) {
            println("[RealTimeIndexingService] VirtualFileListener 해제 실패: ${e.message}")
        }
    }
    
    /**
     * 서비스 상태를 로깅합니다.
     */
    private fun logServiceStatus() {
        if (isDebugMode()) {
            val uptime = if (startTime.get() > 0) System.currentTimeMillis() - startTime.get() else 0
            println("[RealTimeIndexingService] 상태: ${if (isActive.get()) "활성" else "비활성"}, 가동시간: ${uptime}ms")
        }
    }
    
    /**
     * 서비스 상태를 반환합니다.
     */
    fun getServiceStatus(): Map<String, Any> {
        val uptime = if (startTime.get() > 0) System.currentTimeMillis() - startTime.get() else 0L
        
        return mapOf(
            "is_active" to isActive.get(),
            "uptime_ms" to uptime,
            "total_files_processed" to totalFilesProcessed.get().toInt(),
            "start_time" to startTime.get().toLong()
        )
    }
    
    /**
     * 전체 통계를 반환합니다.
     */
    fun getAllStats(): Map<String, Any> {
        return mapOf(
            "service_status" to getServiceStatus(),
            "indexing_stats" to indexingService.getIndexingStats(),
            "hash_stats" to hashService.getHashStats(),
            "batch_stats" to batchProcessor.getBatchStatus(),
            "performance_metrics" to batchProcessor.getPerformanceMetrics(),
            "file_change_stats" to fileChangeListener.getChangeStats()
        )
    }
    
    /**
     * 인덱싱을 강제로 재실행합니다.
     */
    fun forceReindexing() {
        if (!isActive.get()) {
            println("[RealTimeIndexingService] 서비스가 비활성화되어 있어 재인덱싱을 수행할 수 없습니다.")
            return
        }
        
        // 백그라운드 스레드에서 강제 재인덱싱 수행
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                println("[RealTimeIndexingService] 강제 재인덱싱을 시작합니다...")
                
                // 해시 캐시 초기화
                hashService.clearHashCache()
                
                // 전체 재인덱싱 (이미 백그라운드에서 실행됨)
                println("[RealTimeIndexingService] 해시 캐시를 초기화했습니다.")
                
                val startTime = System.currentTimeMillis()
                val chunkCount = indexingService.indexProject()
                val endTime = System.currentTimeMillis()
                
                totalFilesProcessed.set(chunkCount.toLong())
                
                println("[RealTimeIndexingService] 강제 재인덱싱 완료: $chunkCount 개 코드 조각 (${endTime - startTime}ms)")
                
                // 해시 캐시 정리
                hashService.cleanupHashCache()
                
            } catch (e: Exception) {
                println("[RealTimeIndexingService] 강제 재인덱싱 중 오류 발생: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    
    /**
     * 특정 파일의 인덱싱을 강제로 재실행합니다.
     */
    fun forceReindexFile(filePath: String) {
        if (!isActive.get()) {
            println("[RealTimeIndexingService] 서비스가 비활성화되어 있어 파일 재인덱싱을 수행할 수 없습니다.")
            return
        }
        
        try {
            val file = project.baseDir?.fileSystem?.findFileByPath(filePath)
            if (file != null) {
                // 해시 무효화
                hashService.invalidateFileHash(filePath)
                
                // 재인덱싱 스케줄링
                batchProcessor.scheduleReindexing(file)
                
                println("[RealTimeIndexingService] 파일 재인덱싱 스케줄링: $filePath")
            } else {
                println("[RealTimeIndexingService] 파일을 찾을 수 없습니다: $filePath")
            }
        } catch (e: Exception) {
            println("[RealTimeIndexingService] 파일 재인덱싱 중 오류 발생: ${e.message}")
        }
    }
    
    /**
     * 해시 캐시를 정리합니다.
     */
    fun cleanupHashCache() {
        try {
            hashService.cleanupHashCache()
            println("[RealTimeIndexingService] 해시 캐시 정리 완료")
        } catch (e: Exception) {
            println("[RealTimeIndexingService] 해시 캐시 정리 중 오류 발생: ${e.message}")
        }
    }
    
    /**
     * 배치 처리를 강제로 실행합니다.
     */
    fun forceBatchProcessing() {
        if (!isActive.get()) {
            println("[RealTimeIndexingService] 서비스가 비활성화되어 있어 배치 처리를 수행할 수 없습니다.")
            return
        }
        
        try {
            batchProcessor.forceProcessBatch()
            println("[RealTimeIndexingService] 배치 처리 강제 실행 완료")
        } catch (e: Exception) {
            println("[RealTimeIndexingService] 배치 처리 강제 실행 중 오류 발생: ${e.message}")
        }
    }
    
    /**
     * 디버그 모드인지 확인합니다.
     */
    private fun isDebugMode(): Boolean {
        return System.getProperty("semas.debug", "false").toBoolean()
    }
    
    /**
     * 서비스가 활성화되어 있는지 확인합니다.
     */
    fun isActive(): Boolean = isActive.get()
    
    /**
     * 인덱싱 서비스 인스턴스를 반환합니다.
     */
    fun getIndexingService(): CodeIndexingService = indexingService
    
    /**
     * 해시 서비스 인스턴스를 반환합니다.
     */
    fun getHashService(): FileHashService = hashService
    
    /**
     * 배치 프로세서 인스턴스를 반환합니다.
     */
    fun getBatchProcessor(): BatchIndexingProcessor = batchProcessor
}

/**
 * 프로젝트가 열릴 때 실시간 인덱싱을 자동으로 시작하는 리스너입니다.
 */
class RealTimeIndexingStartupActivity : StartupActivity {
    override fun runActivity(project: Project) {
        // 백그라운드 스레드에서 실행하여 EDT 블로킹 방지
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val realTimeIndexingService = project.getService(RealTimeIndexingService::class.java)
                realTimeIndexingService.startRealTimeIndexing()
            } catch (e: Exception) {
                println("[RealTimeIndexingStartupActivity] 실시간 인덱싱 자동 시작 중 오류: ${e.message}")
                e.printStackTrace()
            }
        }
    }
}

/**
 * 프로젝트가 닫힐 때 실시간 인덱싱을 자동으로 중지하는 리스너입니다.
 */
class RealTimeIndexingProjectListener : ProjectManagerListener {
    override fun projectClosing(project: Project) {
        try {
            val realTimeIndexingService = project.getService(RealTimeIndexingService::class.java)
            if (realTimeIndexingService.isActive()) {
                realTimeIndexingService.stopRealTimeIndexing()
            }
        } catch (e: Exception) {
            println("[RealTimeIndexingProjectListener] 실시간 인덱싱 자동 중지 중 오류: ${e.message}")
        }
    }
}
