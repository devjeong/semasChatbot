package org.dev.semaschatbot.task

import org.dev.semaschatbot.GeminiClient

/**
 * 작업별 프롬프트를 생성하는 클래스
 * 
 * 각 작업에 대해 가장 적절한 프롬프트를 Gemini API를 통해 생성합니다.
 * 이전 작업의 결과를 참고하여 연속적인 작업 흐름을 유지합니다.
 * 
 * @param geminiClient Gemini API 클라이언트 인스턴스
 */
class TaskPromptGenerator(private val geminiClient: GeminiClient) {
    
    /**
     * 작업에 대한 프롬프트를 생성합니다.
     * 
     * @param task 현재 작업
     * @param requirement 전체 요구사항
     * @param previousTasks 이전에 완료된 작업 목록
     * @param modelId 사용할 Gemini 모델 ID (기본값: "gemini-1.5-flash")
     * @param userId 로그인한 사용자 ID (선택적, 서버에서 사용량 추적 등에 사용)
     * @return 생성된 프롬프트
     */
    fun generatePromptForTask(
        task: Task,
        requirement: String,
        previousTasks: List<Task>,
        modelId: String = "gemini-1.5-flash",
        userId: Int? = null
    ): String? {
        try {
            val prompt = buildPromptGenerationRequest(task, requirement, previousTasks)
            val response = geminiClient.sendChatRequest(
                userMessage = prompt,
                systemMessage = "",
                modelId = modelId,
                userId = userId
            )
            
            if (response == null) {
                println("[TaskPromptGenerator] Gemini API 응답이 null입니다.")
                return createFallbackPrompt(task, requirement)
            }
            
            return extractPrompt(response, task, requirement)
        } catch (e: Exception) {
            println("[TaskPromptGenerator] 프롬프트 생성 중 오류 발생: ${e.message}")
            e.printStackTrace()
            return createFallbackPrompt(task, requirement)
        }
    }
    
    /**
     * 프롬프트 생성 요청을 작성합니다.
     * 
     * @param task 현재 작업
     * @param requirement 전체 요구사항
     * @param previousTasks 이전에 완료된 작업 목록
     * @return Gemini API에 전송할 프롬프트
     */
    private fun buildPromptGenerationRequest(
        task: Task,
        requirement: String,
        previousTasks: List<Task>
    ): String {
        val previousContext = if (previousTasks.isNotEmpty()) {
            buildString {
                appendLine("이전에 완료된 작업들:")
                previousTasks.forEachIndexed { index, prevTask ->
                    appendLine("${index + 1}. ${prevTask.title}")
                    val result = prevTask.result
                    if (result != null && result.isNotBlank()) {
                        val resultPreview = result.take(300)
                        appendLine("   결과: $resultPreview${if (result.length > 300) "..." else ""}")
                    }
                    appendLine()
                }
            }
        } else {
            "이전 작업 없음"
        }
        
        return """
            다음 작업을 수행하기 위한 최적의 프롬프트를 생성해주세요.
            
            전체 요구사항: $requirement
            
            현재 작업:
            - 제목: ${task.title}
            - 설명: ${task.description}
            
            $previousContext
            
            프롬프트 요구사항:
            1. 작업을 정확하게 수행할 수 있어야 합니다
            2. 이전 작업의 결과를 참고할 수 있어야 합니다
            3. 구체적이고 실행 가능해야 합니다
            4. 코드 생성이 필요한 경우 적절한 언어와 프레임워크를 명시해야 합니다
            5. 단계별로 명확하게 설명되어야 합니다
            
            프롬프트만 출력해주세요 (설명이나 주석 없이).
        """.trimIndent()
    }
    
    /**
     * Gemini API 응답에서 프롬프트를 추출합니다.
     * 
     * @param response Gemini API 응답
     * @param task 현재 작업 (폴백용)
     * @param requirement 전체 요구사항 (폴백용)
     * @return 추출된 프롬프트
     */
    private fun extractPrompt(response: String, task: Task, requirement: String): String {
        var cleaned = response.trim()
        
        // 마크다운 코드 블록 제거
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.removePrefix("```")
                .removePrefix("markdown")
                .removePrefix("text")
                .removeSuffix("```")
                .trim()
        }
        
        // 빈 응답 처리
        if (cleaned.isBlank()) {
            return createFallbackPrompt(task, requirement)
        }
        
        return cleaned
    }
    
    /**
     * 폴백 프롬프트를 생성합니다.
     * 
     * @param task 현재 작업
     * @param requirement 전체 요구사항
     * @return 기본 프롬프트
     */
    private fun createFallbackPrompt(task: Task, requirement: String): String {
        return """
            다음 작업을 수행해주세요:
            
            요구사항: $requirement
            
            작업: ${task.title}
            설명: ${task.description}
        """.trimIndent()
    }
}

