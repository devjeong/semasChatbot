package org.dev.semaschatbot.task

import com.intellij.openapi.project.Project
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 작업 이력 파일 관리 클래스
 * 
 * 작업 세션을 마크다운 파일로 저장하고 관리합니다.
 * 파일은 날짜별 디렉토리에 저장되며, 파일명은 타임스탬프와 요구사항명을 포함합니다.
 * 
 * 디렉토리 구조:
 * {workspace}/.semasChatbot/task_history/YYYY-MM-DD/YYYY-MM-DD_HHmmss_요구사항명.md
 * 
 * @param project IntelliJ 프로젝트 인스턴스
 */
class TaskHistoryManager(private val project: Project) {
    
    /**
     * 작업 이력 저장 기본 디렉토리를 반환합니다.
     * 디렉토리가 없으면 생성합니다.
     * 
     * @return 기본 디렉토리 File 객체
     */
    private val baseDir: File
        get() {
            val workspaceRoot = project.basePath
                ?: return File(System.getProperty("user.home"), ".semasChatbot")
            
            val semasDir = File(workspaceRoot, ".semasChatbot")
            val historyDir = File(semasDir, "task_history")
            val dateDir = File(historyDir, getCurrentDateString())
            
            // 디렉토리 생성 (부모 디렉토리 포함)
            if (!dateDir.exists()) {
                dateDir.mkdirs()
                println("[TaskHistoryManager] 디렉토리 생성: ${dateDir.absolutePath}")
            }
            
            return dateDir
        }
    
    /**
     * 작업 세션을 마크다운 파일로 저장합니다.
     * 
     * @param session 저장할 작업 세션
     * @return 저장된 파일 객체
     */
    fun saveTaskSession(session: TaskSession): File {
        val fileName = generateFileName(session.requirement)
        val file = File(baseDir, fileName)
        
        // 파일명 중복 처리
        val finalFile = handleFileNameConflict(file)
        
        val content = buildMarkdownContent(session)
        finalFile.writeText(content, Charsets.UTF_8)
        
        println("[TaskHistoryManager] 작업 세션 저장 완료: ${finalFile.absolutePath}")
        return finalFile
    }
    
    /**
     * 작업 세션 파일을 업데이트합니다.
     * 기존 파일이 있으면 덮어쓰고, 없으면 새로 생성합니다.
     * 
     * @param session 업데이트할 작업 세션
     * @param originalFile 원본 파일 (없으면 null)
     * @return 업데이트된 파일 객체
     */
    fun updateTaskSession(session: TaskSession, originalFile: File?): File {
        return if (originalFile != null && originalFile.exists()) {
            // 기존 파일 업데이트
            val content = buildMarkdownContent(session)
            originalFile.writeText(content, Charsets.UTF_8)
            println("[TaskHistoryManager] 작업 세션 업데이트 완료: ${originalFile.absolutePath}")
            originalFile
        } else {
            // 새 파일 생성
            saveTaskSession(session)
        }
    }
    
    /**
     * 작업 이력 파일을 삭제합니다.
     * 
     * @param file 삭제할 파일
     * @return 삭제 성공 여부
     */
    fun deleteTaskSessionFile(file: File): Boolean {
        return try {
            if (file.exists() && file.isFile) {
                val deleted = file.delete()
                if (deleted) {
                    println("[TaskHistoryManager] 파일 삭제 완료: ${file.absolutePath}")
                } else {
                    println("[TaskHistoryManager] 파일 삭제 실패: ${file.absolutePath}")
                }
                deleted
            } else {
                println("[TaskHistoryManager] 파일이 존재하지 않습니다: ${file.absolutePath}")
                false
            }
        } catch (e: Exception) {
            println("[TaskHistoryManager] 파일 삭제 중 오류 발생: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 파일명을 생성합니다.
     * 형식: YYYY-MM-DD_HHmmss_요구사항명.md
     * 
     * @param requirement 사용자 요구사항
     * @return 생성된 파일명
     */
    private fun generateFileName(requirement: String): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault())
            .format(Date())
        
        // 요구사항명 정제 (특수문자 제거, 길이 제한)
        val sanitizedRequirement = sanitizeFileName(requirement)
        
        return "${timestamp}_${sanitizedRequirement}.md"
    }
    
    /**
     * 파일명에 사용할 수 있도록 요구사항 문자열을 정제합니다.
     * 
     * @param requirement 원본 요구사항
     * @return 정제된 파일명 부분
     */
    private fun sanitizeFileName(requirement: String): String {
        // 특수문자 제거 (한글, 영문, 숫자, 공백만 허용)
        var sanitized = requirement
            .replace(Regex("[^가-힣a-zA-Z0-9\\s]"), "")
            .trim()
        
        // 연속된 공백을 하나로 변환
        sanitized = sanitized.replace(Regex("\\s+"), " ")
        
        // 공백을 언더스코어로 변환
        sanitized = sanitized.replace(" ", "_")
        
        // 길이 제한 (50자)
        sanitized = sanitized.take(50)
        
        // 빈 문자열이면 기본값 사용
        if (sanitized.isEmpty()) {
            sanitized = "task"
        }
        
        return sanitized
    }
    
    /**
     * 파일명 중복을 처리합니다.
     * 중복된 파일명이 있으면 번호를 추가합니다.
     * 
     * @param file 원본 파일
     * @return 중복 처리된 파일 객체
     */
    private fun handleFileNameConflict(file: File): File {
        if (!file.exists()) {
            return file
        }
        
        // 파일명에 번호 추가
        val baseName = file.nameWithoutExtension
        val extension = file.extension
        var counter = 1
        var newFile: File
        
        do {
            val newFileName = "${baseName}_${counter}.${extension}"
            newFile = File(file.parent, newFileName)
            counter++
        } while (newFile.exists() && counter < 1000) // 최대 1000개까지 시도
        
        if (counter >= 1000) {
            // 1000개 이상 중복 시 타임스탬프 추가
            val timestamp = System.currentTimeMillis()
            val newFileName = "${baseName}_${timestamp}.${extension}"
            newFile = File(file.parent, newFileName)
        }
        
        return newFile
    }
    
    /**
     * 현재 날짜 문자열을 반환합니다.
     * 형식: YYYY-MM-DD
     * 
     * @return 날짜 문자열
     */
    private fun getCurrentDateString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Date())
    }
    
    /**
     * 타임스탬프를 읽기 쉬운 날짜/시간 형식으로 변환합니다.
     * 
     * @param timestamp 밀리초 타임스탬프
     * @return 포맷된 날짜/시간 문자열
     */
    private fun formatDateTime(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(timestamp))
    }
    
    /**
     * 작업 세션을 마크다운 형식의 문자열로 변환합니다.
     * 
     * @param session 작업 세션
     * @return 마크다운 콘텐츠
     */
    private fun buildMarkdownContent(session: TaskSession): String {
        return buildString {
            // 헤더
            appendLine("# 작업 세션: ${session.requirement}")
            appendLine()
            
            // 메타 정보
            appendLine("**세션 ID**: `${session.id}`")
            appendLine("**생성 시간**: ${formatDateTime(session.createdAt)}")
            appendLine("**상태**: ${session.status}")
            appendLine("**진행률**: ${(session.getProgress() * 100).toInt()}% (${session.getCompletedCount()}/${session.getTotalCount()})")
            appendLine()
            
            // 요구사항
            appendLine("## 요구사항")
            appendLine()
            appendLine(session.requirement)
            appendLine()
            
            // 작업 목록
            appendLine("## 작업 목록")
            appendLine()
            
            session.tasks.forEachIndexed { index, task ->
                appendLine("### ${index + 1}. ${task.title}")
                appendLine()
                
                // 작업 메타 정보
                appendLine("- **ID**: `${task.id}`")
                appendLine("- **상태**: `${task.status}`")
                appendLine("- **순서**: ${task.order}")
                appendLine("- **설명**: ${task.description}")
                appendLine()
                
                // 프롬프트 (있는 경우)
                val prompt = task.prompt
                if (prompt != null && prompt.isNotBlank()) {
                    appendLine("**프롬프트**:")
                    appendLine("```")
                    appendLine(prompt)
                    appendLine("```")
                    appendLine()
                }
                
                // 결과 (있는 경우)
                val result = task.result
                if (result != null && result.isNotBlank()) {
                    appendLine("**결과**:")
                    appendLine("```")
                    appendLine(result)
                    appendLine("```")
                    appendLine()
                }
                
                // 구분선
                if (index < session.tasks.size - 1) {
                    appendLine("---")
                    appendLine()
                }
            }
            
            // 요약
            appendLine("## 요약")
            appendLine()
            appendLine("- 총 작업 수: ${session.getTotalCount()}")
            appendLine("- 완료된 작업: ${session.getCompletedCount()}")
            appendLine("- 취소된 작업: ${session.tasks.count { it.status == TaskStatus.CANCELLED }}")
            appendLine("- 실패한 작업: ${session.tasks.count { it.status == TaskStatus.FAILED }}")
            appendLine("- 진행률: ${(session.getProgress() * 100).toInt()}%")
        }
    }
}

