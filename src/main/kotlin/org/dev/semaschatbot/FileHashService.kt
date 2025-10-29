package org.dev.semaschatbot

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 파일 해시 기반 중복 인덱싱 방지 서비스입니다.
 * 파일의 내용이 실제로 변경되었을 때만 재인덱싱을 수행합니다.
 */
@Service(Service.Level.PROJECT)
class FileHashService(private val project: Project) {
    
    // 파일 경로 -> 해시값 매핑
    private val fileHashes = ConcurrentHashMap<String, String>()
    
    // 파일 경로 -> 마지막 수정 시간 매핑
    private val fileModificationTimes = ConcurrentHashMap<String, Long>()
    
    // 해시 계산 통계
    private val hashCalculationCount = AtomicLong(0)
    private val hashMatchCount = AtomicLong(0)
    private val hashMismatchCount = AtomicLong(0)
    
    // 해시 알고리즘 (스레드 세이프하게 사용)
    private val digestThreadLocal: ThreadLocal<MessageDigest> = ThreadLocal.withInitial {
        MessageDigest.getInstance("SHA-256")
    }
    
    /**
     * 파일이 재인덱싱이 필요한지 확인합니다.
     * 파일 내용이 변경되었거나 처음 인덱싱하는 경우 true를 반환합니다.
     */
    fun shouldReindex(file: VirtualFile): Boolean {
        if (!file.exists() || file.isDirectory) return false
        
        val filePath = file.path
        val currentHash = calculateFileHash(file)
        val currentModificationTime = file.timeStamp
        
        hashCalculationCount.incrementAndGet()
        
        // 파일이 처음 인덱싱되는 경우
        if (!fileHashes.containsKey(filePath)) {
            fileHashes[filePath] = currentHash
            fileModificationTimes[filePath] = currentModificationTime
            hashMismatchCount.incrementAndGet()
            
            if (isDebugMode()) {
                println("[FileHashService] 새 파일 인덱싱 필요: ${file.path}")
            }
            return true
        }
        
        val previousHash = fileHashes[filePath]
        val previousModificationTime = fileModificationTimes[filePath] ?: 0
        
        // 수정 시간이 변경되지 않았다면 내용도 변경되지 않았을 가능성이 높음
        if (currentModificationTime == previousModificationTime) {
            hashMatchCount.incrementAndGet()
            return false
        }
        
        // 해시가 다르면 내용이 변경됨
        if (currentHash != previousHash) {
            hashMismatchCount.incrementAndGet()
            
            if (isDebugMode()) {
                println("[FileHashService] 파일 내용 변경 감지: ${file.path}")
                println("  이전 해시: ${previousHash?.take(16)}...")
                println("  현재 해시: ${currentHash.take(16)}...")
            }
            return true
        }
        
        // 해시가 같으면 내용이 동일함
        hashMatchCount.incrementAndGet()
        return false
    }
    
    /**
     * 파일의 해시를 계산합니다.
     */
    private fun calculateFileHash(file: VirtualFile): String {
        return try {
            val md = digestThreadLocal.get()
            md.reset()
            file.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    md.update(buffer, 0, n)
                }
            }
            bytesToHex(md.digest())
        } catch (e: Exception) {
            // 파일 읽기 실패 시 파일 경로와 크기를 기반으로 해시 생성
            val md = digestThreadLocal.get()
            md.reset()
            val fallbackContent = "${file.path}_${file.length}_${file.timeStamp}"
            md.update(fallbackContent.toByteArray())
            bytesToHex(md.digest())
        }
    }
    
    /**
     * 바이트 배열을 16진수 문자열로 변환합니다.
     */
    private fun bytesToHex(bytes: ByteArray): String {
        val result = StringBuilder()
        for (byte in bytes) {
            result.append(String.format("%02x", byte))
        }
        return result.toString()
    }
    
    /**
     * 파일의 해시를 업데이트합니다.
     * 재인덱싱이 완료된 후 호출되어야 합니다.
     */
    fun updateFileHash(file: VirtualFile) {
        if (!file.exists() || file.isDirectory) return
        
        val filePath = file.path
        val currentHash = calculateFileHash(file)
        val currentModificationTime = file.timeStamp
        
        fileHashes[filePath] = currentHash
        fileModificationTimes[filePath] = currentModificationTime
        
        if (isDebugMode()) {
            println("[FileHashService] 파일 해시 업데이트: ${file.path}")
        }
    }
    
    /**
     * 파일의 해시를 제거합니다.
     * 파일이 삭제되었을 때 호출되어야 합니다.
     */
    fun removeFileHash(filePath: String) {
        fileHashes.remove(filePath)
        fileModificationTimes.remove(filePath)
        
        if (isDebugMode()) {
            println("[FileHashService] 파일 해시 제거: $filePath")
        }
    }
    
    /**
     * 특정 파일의 해시 정보를 반환합니다.
     */
    fun getFileHashInfo(filePath: String): Map<String, Any>? {
        val hash = fileHashes[filePath] ?: return null
        val modificationTime = fileModificationTimes[filePath] ?: 0
        
        return mapOf(
            "hash" to hash,
            "modification_time" to modificationTime,
            "hash_short" to hash.take(16)
        )
    }
    
    /**
     * 모든 파일의 해시 정보를 반환합니다.
     */
    fun getAllFileHashes(): Map<String, Map<String, Any>> {
        return fileHashes.mapValues { (filePath, hash) ->
            val modificationTime = fileModificationTimes[filePath] ?: 0
            mapOf(
                "hash" to hash,
                "modification_time" to modificationTime,
                "hash_short" to hash.take(16)
            )
        }
    }
    
    /**
     * 해시 서비스 통계를 반환합니다.
     */
    fun getHashStats(): Map<String, Any> {
        val totalCalculations = hashCalculationCount.get()
        val matches = hashMatchCount.get()
        val mismatches = hashMismatchCount.get()
        
        val matchRate = if (totalCalculations > 0) {
            (matches.toDouble() / totalCalculations * 100)
        } else 0.0
        
        return mapOf(
            "total_calculations" to totalCalculations.toInt(),
            "hash_matches" to matches.toInt(),
            "hash_mismatches" to mismatches.toInt(),
            "match_rate_percent" to matchRate,
            "tracked_files" to fileHashes.size,
            "memory_usage_mb" to estimateMemoryUsage()
        )
    }
    
    /**
     * 메모리 사용량을 추정합니다.
     */
    private fun estimateMemoryUsage(): Double {
        val hashSize = fileHashes.size * 64 // SHA-256 해시는 64자
        val pathSize = fileHashes.keys.sumOf { it.length * 2 } // UTF-16 문자당 2바이트
        val timeSize = fileModificationTimes.size * 8 // Long은 8바이트
        
        val totalBytes = hashSize + pathSize + timeSize
        return totalBytes / (1024.0 * 1024.0) // MB로 변환
    }
    
    /**
     * 해시 캐시를 정리합니다.
     * 존재하지 않는 파일들의 해시를 제거합니다.
     */
    fun cleanupHashCache() {
        val filesToRemove = mutableListOf<String>()
        
        fileHashes.keys.forEach { filePath ->
            try {
                val file = project.baseDir?.fileSystem?.findFileByPath(filePath)
                if (file == null || !file.exists()) {
                    filesToRemove.add(filePath)
                }
            } catch (e: Exception) {
                filesToRemove.add(filePath)
            }
        }
        
        filesToRemove.forEach { filePath ->
            removeFileHash(filePath)
        }
        
        if (isDebugMode() && filesToRemove.isNotEmpty()) {
            println("[FileHashService] 해시 캐시 정리: ${filesToRemove.size}개 파일 제거")
        }
    }
    
    /**
     * 해시 캐시를 완전히 초기화합니다.
     */
    fun clearHashCache() {
        fileHashes.clear()
        fileModificationTimes.clear()
        hashCalculationCount.set(0)
        hashMatchCount.set(0)
        hashMismatchCount.set(0)
        
        if (isDebugMode()) {
            println("[FileHashService] 해시 캐시 초기화 완료")
        }
    }
    
    /**
     * 특정 파일의 해시를 강제로 무효화합니다.
     * 다음 인덱싱 시 재계산되도록 합니다.
     */
    fun invalidateFileHash(filePath: String) {
        fileHashes.remove(filePath)
        fileModificationTimes.remove(filePath)
        
        if (isDebugMode()) {
            println("[FileHashService] 파일 해시 무효화: $filePath")
        }
    }
    
    /**
     * 디버그 모드인지 확인합니다.
     */
    private fun isDebugMode(): Boolean {
        return System.getProperty("semas.debug", "false").toBoolean()
    }
    
    /**
     * 해시 서비스 상태를 반환합니다.
     */
    fun getServiceStatus(): Map<String, Any> {
        return mapOf(
            "is_active" to true,
            "tracked_files" to fileHashes.size,
            "last_cleanup" to System.currentTimeMillis(),
            "hash_algorithm" to "SHA-256",
            "memory_usage_mb" to estimateMemoryUsage()
        )
    }
}
