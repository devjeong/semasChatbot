package org.dev.semaschatbot

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 배치 처리를 통한 성능 최적화된 인덱싱 프로세서입니다.
 * 여러 파일 변경을 모아서 일괄 처리하여 성능을 향상시킵니다.
 */
class BatchIndexingProcessor(
    private val project: Project,
    private val indexingService: CodeIndexingService,
    private val hashService: FileHashService
) {
    
    private val pendingReindexFiles = ConcurrentHashMap<String, VirtualFile>()
    private val pendingRemoveFiles = ConcurrentHashMap<String, VirtualFile>()
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "BatchIndexingProcessor").apply { isDaemon = true }
    }
    private val fileIndexingPool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "BatchIndexingWorker").apply { isDaemon = true }
    }
    
    private val isProcessing = AtomicBoolean(false)
    private val batchSize = AtomicInteger(0)
    private val maxBatchSize = 100
    private val batchDelayMs = 400L // 0.4초
    private val maxConcurrentOperations = 2
    
    private val activeOperations = AtomicInteger(0)
    private val performanceMetrics = PerformanceMetrics()
    
    init {
        // 정기적인 배치 처리 스케줄링
        executor.scheduleWithFixedDelay(
            { processBatchIfNeeded() },
            batchDelayMs,
            batchDelayMs,
            TimeUnit.MILLISECONDS
        )
    }
    
    /**
     * 파일 재인덱싱을 스케줄링합니다.
     */
    fun scheduleReindexing(file: VirtualFile) {
        if (shouldSkipFile(file)) return
        
        pendingReindexFiles[file.path] = file
        val newBatchSize = batchSize.incrementAndGet()
        
        if (isDebugMode()) {
            println("[BatchProcessor] 파일 스케줄링: ${file.path} (배치 크기: $newBatchSize)")
        }
        
        // 배치 크기가 임계값에 도달하면 즉시 처리
        if (newBatchSize >= maxBatchSize) {
            if (isDebugMode()) {
                println("[BatchProcessor] 배치 크기 임계값 도달 ($newBatchSize >= $maxBatchSize) - 즉시 처리")
            }
            processBatchIfNeeded()
        }
    }
    
    /**
     * 파일 제거를 스케줄링합니다.
     */
    fun scheduleFileRemoval(file: VirtualFile) {
        pendingRemoveFiles[file.path] = file
        batchSize.incrementAndGet()
        
        // 파일 제거는 즉시 처리
        processBatchIfNeeded()
    }
    
    /**
     * 배치 처리가 필요한지 확인하고 실행합니다.
     */
    private fun processBatchIfNeeded() {
        if (isProcessing.get() || batchSize.get() == 0) return
        
        if (activeOperations.get() >= maxConcurrentOperations) {
            // 너무 많은 동시 작업이 있으면 대기
            return
        }
        
        isProcessing.set(true)
        activeOperations.incrementAndGet()
        
        try {
            val startTime = System.currentTimeMillis()
            
            // 백그라운드에서 배치 처리 실행
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    processBatch()
                } finally {
                    isProcessing.set(false)
                    activeOperations.decrementAndGet()
                    performanceMetrics.recordBatchProcessingTime(System.currentTimeMillis() - startTime)
                }
            }
        } catch (e: Exception) {
            isProcessing.set(false)
            activeOperations.decrementAndGet()
            println("배치 처리 중 오류 발생: ${e.message}")
        }
    }
    
    /**
     * 실제 배치 처리를 수행합니다.
     */
    private fun processBatch() {
        val reindexFiles = pendingReindexFiles.values.toList()
        val removeFiles = pendingRemoveFiles.values.toList()
        
        if (reindexFiles.isEmpty() && removeFiles.isEmpty()) return
        
        // 처리할 파일 수만큼 배치 크기에서 차감 (음수 방지)
        val totalFilesToProcess = reindexFiles.size + removeFiles.size
        val currentBatchSize = batchSize.get()
        val newBatchSize = maxOf(0, currentBatchSize - totalFilesToProcess)
        batchSize.set(newBatchSize)
        
        if (isDebugMode()) {
            println("[BatchProcessor] 배치 크기 변화: $currentBatchSize → $newBatchSize (처리: $totalFilesToProcess)")
        }
        
        // 대기 중인 파일 목록 클리어
        pendingReindexFiles.clear()
        pendingRemoveFiles.clear()
        
        logBatchProcessing(reindexFiles.size, removeFiles.size)
        
        // 파일 제거 처리
        removeFiles.forEach { file ->
            try {
                removeFileFromIndex(file)
            } catch (e: Exception) {
                println("파일 제거 중 오류: ${file.path} - ${e.message}")
            }
        }
        
        // 파일 재인덱싱 처리 (소규모 병렬화)
        val futures = reindexFiles.map { file ->
            fileIndexingPool.submit<Void> {
                try {
                    reindexFile(file)
                } catch (e: Exception) {
                    println("파일 재인덱싱 중 오류: ${file.path} - ${e.message}")
                }
                null
            }
        }
        futures.forEach { f ->
            try { f.get() } catch (_: Exception) { }
        }
    }
    
    /**
     * 파일을 재인덱싱합니다.
     */
    private fun reindexFile(file: VirtualFile) {
        val startTime = System.currentTimeMillis()
        
        try {
            // 해시 기반 중복 인덱싱 방지
            if (!hashService.shouldReindex(file)) {
                if (isDebugMode()) {
                    println("[BatchProcessor] 파일 해시가 동일하여 인덱싱 건너뜀: ${file.path}")
                }
                return
            }
            
            // 기존 인덱스에서 해당 파일 제거
            removeFileFromIndex(file)
            
            // 새로 인덱싱
            val chunks = indexingService.indexFilePublic(file)
            chunks.forEach { chunk ->
                indexingService.addCodeChunk(chunk)
            }
            
            // 해시 업데이트
            hashService.updateFileHash(file)
            
            val processingTime = System.currentTimeMillis() - startTime
            performanceMetrics.recordFileIndexingTime(processingTime)
            
            if (isDebugMode()) {
                println("[BatchProcessor] 파일 재인덱싱 완료: ${file.path} (${chunks.size} chunks, ${processingTime}ms)")
            }
            
        } catch (e: Exception) {
            println("파일 재인덱싱 실패: ${file.path} - ${e.message}")
        }
    }
    
    /**
     * 인덱스에서 파일을 제거합니다.
     */
    private fun removeFileFromIndex(file: VirtualFile) {
        try {
            indexingService.removeFileFromIndex(file.path)
            hashService.removeFileHash(file.path)
            
            if (isDebugMode()) {
                println("[BatchProcessor] 파일 인덱스에서 제거: ${file.path}")
            }
        } catch (e: Exception) {
            println("파일 인덱스 제거 실패: ${file.path} - ${e.message}")
        }
    }
    
    /**
     * 파일을 건너뛰어야 하는지 확인합니다.
     */
    private fun shouldSkipFile(file: VirtualFile): Boolean {
        if (file.isDirectory) return true
        if (!file.exists()) return true
        if (file.length == 0L) return true
        
        // 너무 큰 파일은 건너뛰기 (10MB 이상)
        if (file.length > 10 * 1024 * 1024) {
            if (isDebugMode()) {
                println("[BatchProcessor] 파일이 너무 커서 건너뜀: ${file.path} (${file.length} bytes)")
            }
            return true
        }
        
        return false
    }
    
    /**
     * 배치 처리 로깅
     */
    private fun logBatchProcessing(reindexCount: Int, removeCount: Int) {
        if (isDebugMode()) {
            println("[BatchProcessor] 배치 처리 시작: 재인덱싱 $reindexCount 개, 제거 $removeCount 개")
        }
    }
    
    /**
     * 디버그 모드인지 확인합니다.
     */
    private fun isDebugMode(): Boolean {
        return System.getProperty("semas.debug", "true").toBoolean()
    }
    
    /**
     * 성능 메트릭을 반환합니다.
     */
    fun getPerformanceMetrics(): Map<String, Any> {
        return performanceMetrics.getMetrics()
    }
    
    /**
     * 배치 처리 상태를 반환합니다.
     */
    fun getBatchStatus(): Map<String, Any> {
        return mapOf(
            "is_processing" to isProcessing.get(),
            "pending_reindex" to pendingReindexFiles.size,
            "pending_remove" to pendingRemoveFiles.size,
            "batch_size" to batchSize.get().toInt(),
            "active_operations" to activeOperations.get().toInt(),
            "max_concurrent_operations" to maxConcurrentOperations.toInt()
        )
    }
    
    /**
     * 배치 처리를 강제로 실행합니다.
     */
    fun forceProcessBatch() {
        if (!isProcessing.get()) {
            processBatchIfNeeded()
        }
    }
    
    /**
     * 리소스를 정리합니다.
     */
    fun shutdown() {
        try {
            // 남은 배치 처리 완료
            processBatch()
            
            // 스케줄러 종료
            executor.shutdown()
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
            // 워커 풀 종료
            fileIndexingPool.shutdown()
            if (!fileIndexingPool.awaitTermination(5, TimeUnit.SECONDS)) {
                fileIndexingPool.shutdownNow()
            }
        } catch (e: Exception) {
            println("BatchIndexingProcessor 종료 중 오류: ${e.message}")
        }
    }
}

/**
 * 성능 메트릭을 수집하는 클래스입니다.
 */
class PerformanceMetrics {
    private val batchProcessingTimes = ArrayDeque<Long>()
    private val fileIndexingTimes = ArrayDeque<Long>()
    private val totalBatches = AtomicInteger(0)
    private val totalFiles = AtomicInteger(0)
    private var emaBatchMs = 0.0
    private var emaFileMs = 0.0
    private val alpha = 0.2 // EMA smoothing factor
    
    fun recordBatchProcessingTime(time: Long) {
        batchProcessingTimes.addLast(time)
        while (batchProcessingTimes.size > 100) batchProcessingTimes.removeFirst()
        totalBatches.incrementAndGet()
        emaBatchMs = if (emaBatchMs == 0.0) time.toDouble() else (alpha * time + (1 - alpha) * emaBatchMs)
    }
    
    fun recordFileIndexingTime(time: Long) {
        fileIndexingTimes.addLast(time)
        while (fileIndexingTimes.size > 1000) fileIndexingTimes.removeFirst()
        totalFiles.incrementAndGet()
        emaFileMs = if (emaFileMs == 0.0) time.toDouble() else (alpha * time + (1 - alpha) * emaFileMs)
    }
    
    fun getMetrics(): Map<String, Any> {
        return mapOf(
            "total_batches" to totalBatches.get().toInt(),
            "total_files" to totalFiles.get().toInt(),
            "avg_batch_processing_time" to emaBatchMs,
            "avg_file_indexing_time" to emaFileMs,
            "max_batch_processing_time" to (batchProcessingTimes.maxOrNull() ?: 0L),
            "max_file_indexing_time" to (fileIndexingTimes.maxOrNull() ?: 0L)
        )
    }
}
