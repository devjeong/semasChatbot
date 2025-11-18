package org.dev.semaschatbot

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 디버깅 로그를 저장하고 조회할 수 있는 로거 클래스
 * 
 * 메모리 기반으로 최근 로그를 저장하며, 로그 조회 UI에서 사용됩니다.
 */
object Logger {
    
    /**
     * 로그 엔트리 데이터 클래스
     * @param timestamp 로그 발생 시간
     * @param level 로그 레벨 (DEBUG, INFO, WARN, ERROR)
     * @param tag 로그 태그 (예: "GeminiClient", "ChatService")
     * @param message 로그 메시지
     */
    data class LogEntry(
        val timestamp: String,
        val level: LogLevel,
        val tag: String,
        val message: String
    ) {
        override fun toString(): String {
            return "[$timestamp] [$level] [$tag] $message"
        }
    }
    
    /**
     * 로그 레벨 열거형
     */
    enum class LogLevel {
        DEBUG, INFO, WARN, ERROR
    }
    
    // 로그 저장소 (최대 1000개까지 저장)
    private val logs = CopyOnWriteArrayList<LogEntry>()
    private val maxLogs = 1000
    
    // 타임스탬프 포맷터
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    
    /**
     * 로그를 추가합니다.
     * @param level 로그 레벨
     * @param tag 로그 태그
     * @param message 로그 메시지
     */
    fun log(level: LogLevel, tag: String, message: String) {
        val timestamp = LocalDateTime.now().format(timeFormatter)
        val entry = LogEntry(timestamp, level, tag, message)
        
        synchronized(logs) {
            logs.add(entry)
            
            // 최대 개수 초과 시 오래된 로그 제거
            if (logs.size > maxLogs) {
                logs.removeAt(0)
            }
        }
        
        // 콘솔에도 출력 (기존 동작 유지)
        println(entry.toString())
    }
    
    /**
     * DEBUG 레벨 로그를 추가합니다.
     */
    fun debug(tag: String, message: String) {
        log(LogLevel.DEBUG, tag, message)
    }
    
    /**
     * INFO 레벨 로그를 추가합니다.
     */
    fun info(tag: String, message: String) {
        log(LogLevel.INFO, tag, message)
    }
    
    /**
     * WARN 레벨 로그를 추가합니다.
     */
    fun warn(tag: String, message: String) {
        log(LogLevel.WARN, tag, message)
    }
    
    /**
     * ERROR 레벨 로그를 추가합니다.
     */
    fun error(tag: String, message: String) {
        log(LogLevel.ERROR, tag, message)
    }
    
    /**
     * 모든 로그를 조회합니다.
     * @return 로그 엔트리 리스트
     */
    fun getAllLogs(): List<LogEntry> {
        return synchronized(logs) {
            logs.toList()
        }
    }
    
    /**
     * 특정 태그의 로그만 조회합니다.
     * @param tag 로그 태그
     * @return 해당 태그의 로그 엔트리 리스트
     */
    fun getLogsByTag(tag: String): List<LogEntry> {
        return synchronized(logs) {
            logs.filter { it.tag == tag }
        }
    }
    
    /**
     * 특정 레벨 이상의 로그만 조회합니다.
     * @param minLevel 최소 로그 레벨
     * @return 해당 레벨 이상의 로그 엔트리 리스트
     */
    fun getLogsByLevel(minLevel: LogLevel): List<LogEntry> {
        val levelOrder = listOf(LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR)
        val minIndex = levelOrder.indexOf(minLevel)
        
        return synchronized(logs) {
            logs.filter { levelOrder.indexOf(it.level) >= minIndex }
        }
    }
    
    /**
     * 로그를 초기화합니다.
     */
    fun clear() {
        synchronized(logs) {
            logs.clear()
        }
    }
    
    /**
     * 현재 저장된 로그 개수를 반환합니다.
     */
    fun getLogCount(): Int {
        return synchronized(logs) {
            logs.size
        }
    }
}

