package org.dev.semaschatbot.task

import com.google.gson.*
import com.google.gson.JsonSyntaxException
import org.dev.semaschatbot.GeminiClient
import java.util.UUID

/**
 * Gemini API를 통해 작업목록을 생성하는 클래스
 * 
 * 사용자 요구사항을 분석하여 구체적이고 실행 가능한 작업 목록을 생성합니다.
 * Gemini API를 호출하여 JSON 형식의 작업목록을 받아 Task 객체 리스트로 변환합니다.
 * 
 * @param geminiClient Gemini API 클라이언트 인스턴스
 */
class TaskListGenerator(private val geminiClient: GeminiClient) {
    
    /**
     * 최대 재시도 횟수
     */
    private val MAX_RETRIES = 3
    
    /**
     * 재시도 간 대기 시간 (밀리초)
     */
    private val RETRY_DELAY_MS = 1000L
    
    /**
     * 사용자 요구사항을 기반으로 작업목록을 생성합니다.
     * 실패 시 최대 3회까지 재시도합니다.
     * 
     * @param requirement 사용자 요구사항
     * @param modelId 사용할 Gemini 모델 ID (기본값: "gemini-2.5-flash")
     * @param userId 로그인한 사용자 ID (선택적, 서버에서 사용량 추적 등에 사용)
     * @return 생성된 Task 리스트 (실패 시 기본 작업 1개 반환)
     */
    fun generateTaskList(requirement: String, modelId: String = "gemini-2.5-flash", userId: Int? = null): List<Task> {
        var lastException: Exception? = null
        
        for (attempt in 1..MAX_RETRIES) {
            try {
                println("[TaskListGenerator] 작업목록 생성 시도 $attempt/$MAX_RETRIES")
                
                val prompt = buildTaskListPrompt(requirement)
                val response = geminiClient.sendChatRequest(
                    userMessage = prompt,
                    systemMessage = "",
                    modelId = modelId,
                    userId = userId
                )
                
                if (response == null) {
                    println("[TaskListGenerator] Gemini API 응답이 null입니다. (시도 $attempt/$MAX_RETRIES)")
                    if (attempt < MAX_RETRIES) {
                        Thread.sleep(RETRY_DELAY_MS * attempt) // 지수 백오프
                        continue
                    }
                    return createFallbackTask(requirement)
                }
                
                val tasks = parseTaskListResponse(response, requirement)
                
                // 파싱된 작업이 폴백 작업인 경우 재시도
                if (tasks.size == 1 && tasks[0].id == "task_1" && tasks[0].title == "요구사항 분석 및 구현") {
                    if (attempt < MAX_RETRIES) {
                        println("[TaskListGenerator] 파싱 실패로 인한 폴백 작업 생성. 재시도합니다. (시도 $attempt/$MAX_RETRIES)")
                        Thread.sleep(RETRY_DELAY_MS * attempt)
                        continue
                    }
                }
                
                println("[TaskListGenerator] 작업목록 생성 성공: ${tasks.size}개 작업")
                return tasks
                
            } catch (e: Exception) {
                lastException = e
                println("[TaskListGenerator] 작업목록 생성 중 오류 발생 (시도 $attempt/$MAX_RETRIES): ${e.message}")
                
                if (attempt < MAX_RETRIES) {
                    // 재시도 가능한 오류인지 확인
                    if (isRetryableError(e)) {
                        println("[TaskListGenerator] 재시도 가능한 오류입니다. ${RETRY_DELAY_MS * attempt}ms 후 재시도합니다.")
                        Thread.sleep(RETRY_DELAY_MS * attempt)
                        continue
                    } else {
                        // 재시도 불가능한 오류 (예: 잘못된 요청 형식)
                        println("[TaskListGenerator] 재시도 불가능한 오류입니다. 폴백 작업을 생성합니다.")
                        break
                    }
                }
            }
        }
        
        // 모든 재시도 실패
        println("[TaskListGenerator] 모든 재시도가 실패했습니다. 폴백 작업을 생성합니다.")
        if (lastException != null) {
            lastException.printStackTrace()
        }
        return createFallbackTask(requirement)
    }
    
    /**
     * 오류가 재시도 가능한지 확인합니다.
     * 
     * @param e 발생한 예외
     * @return 재시도 가능하면 true
     */
    private fun isRetryableError(e: Exception): Boolean {
        return when (e) {
            is java.net.SocketTimeoutException -> true
            is java.net.ConnectException -> true
            is java.io.IOException -> true
            is java.util.concurrent.TimeoutException -> true
            else -> false
        }
    }
    
    /**
     * 작업목록 생성용 프롬프트를 작성합니다.
     * 
     * @param requirement 사용자 요구사항
     * @return Gemini API에 전송할 프롬프트
     */
    private fun buildTaskListPrompt(requirement: String): String {
        return """
            사용자의 다음 요구사항을 분석하여 구체적이고 실행 가능한 작업 목록을 생성해주세요.
            
            요구사항: $requirement
            
            출력 형식은 반드시 다음 JSON 형식을 따라야 합니다:
            {
                "tasks": [
                    {
                        "id": "task_1",
                        "title": "작업 제목",
                        "description": "작업에 대한 상세 설명",
                        "order": 1
                    },
                    {
                        "id": "task_2",
                        "title": "다음 작업 제목",
                        "description": "다음 작업에 대한 상세 설명",
                        "order": 2
                    }
                ]
            }
            
            각 작업은:
            - 독립적으로 실행 가능해야 합니다
            - 명확한 목표를 가져야 합니다
            - 순서가 중요하다면 order 필드로 순서를 명시해야 합니다
            - 최대 10개 이하로 제한합니다
            - 각 작업은 구체적이고 측정 가능한 결과를 가져야 합니다
            
            JSON 형식만 출력하고, 다른 설명이나 텍스트는 포함하지 마세요.
        """.trimIndent()
    }
    
    /**
     * Gemini API 응답을 파싱하여 Task 리스트로 변환합니다.
     * 
     * @param response Gemini API 응답 문자열
     * @param requirement 원본 요구사항 (폴백용)
     * @return 파싱된 Task 리스트
     */
    private fun parseTaskListResponse(response: String, requirement: String): List<Task> {
        return try {
            // JSON 응답에서 코드 블록 제거 (마크다운 형식일 수 있음)
            val cleanedResponse = cleanJsonResponse(response)
            
            val gson = Gson()
            val jsonElement = JsonParser.parseString(cleanedResponse)
            
            if (!jsonElement.isJsonObject) {
                println("[TaskListGenerator] 응답이 JSON 객체가 아닙니다.")
                return createFallbackTask(requirement)
            }
            
            val jsonObject = jsonElement.asJsonObject
            val tasksArray = jsonObject.getAsJsonArray("tasks")
            
            if (tasksArray == null || tasksArray.size() == 0) {
                println("[TaskListGenerator] tasks 배열이 없거나 비어있습니다.")
                return createFallbackTask(requirement)
            }
            
            val tasks = mutableListOf<Task>()
            tasksArray.forEachIndexed { index, element ->
                if (element.isJsonObject) {
                    val taskObj = element.asJsonObject
                    val task = Task(
                        id = getJsonString(taskObj, "id") ?: "task_${index + 1}",
                        title = getJsonString(taskObj, "title") ?: "작업 ${index + 1}",
                        description = getJsonString(taskObj, "description") ?: "",
                        status = TaskStatus.PENDING,
                        order = getJsonInt(taskObj, "order") ?: (index + 1)
                    )
                    tasks.add(task)
                }
            }
            
            // order 기준으로 정렬
            tasks.sortBy { it.order }
            
            if (tasks.isEmpty()) {
                println("[TaskListGenerator] 파싱된 작업이 없습니다.")
                return createFallbackTask(requirement)
            }
            
            println("[TaskListGenerator] ${tasks.size}개의 작업을 생성했습니다.")
            tasks
        } catch (e: JsonSyntaxException) {
            println("[TaskListGenerator] JSON 파싱 오류: ${e.message}")
            e.printStackTrace()
            return createFallbackTask(requirement)
        } catch (e: Exception) {
            println("[TaskListGenerator] 파싱 중 예상치 못한 오류: ${e.message}")
            e.printStackTrace()
            return createFallbackTask(requirement)
        }
    }
    
    /**
     * JSON 응답에서 마크다운 코드 블록을 제거하고 순수 JSON만 추출합니다.
     * 
     * @param response 원본 응답
     * @return 정제된 JSON 문자열
     */
    private fun cleanJsonResponse(response: String): String {
        var cleaned = response.trim()
        
        // 마크다운 코드 블록 제거
        if (cleaned.startsWith("```")) {
            val lines = cleaned.lines()
            val jsonStart = lines.indexOfFirst { it.contains("{") }
            val jsonEnd = lines.indexOfLast { it.contains("}") }
            
            if (jsonStart >= 0 && jsonEnd >= jsonStart) {
                cleaned = lines.subList(jsonStart, jsonEnd + 1).joinToString("\n")
            } else {
                // 코드 블록 마커 제거
                cleaned = cleaned.removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()
            }
        }
        
        // JSON 시작 부분 찾기
        val jsonStartIndex = cleaned.indexOfFirst { it == '{' }
        if (jsonStartIndex > 0) {
            cleaned = cleaned.substring(jsonStartIndex)
        }
        
        // JSON 끝 부분 찾기
        val jsonEndIndex = cleaned.indexOfLast { it == '}' }
        if (jsonEndIndex >= 0 && jsonEndIndex < cleaned.length - 1) {
            cleaned = cleaned.substring(0, jsonEndIndex + 1)
        }
        
        return cleaned.trim()
    }
    
    /**
     * JSON 객체에서 문자열 값을 안전하게 추출합니다.
     * 
     * @param jsonObject JSON 객체
     * @param key 키 이름
     * @return 문자열 값 또는 null
     */
    private fun getJsonString(jsonObject: JsonObject, key: String): String? {
        val element = jsonObject.get(key) ?: return null
        return when {
            element.isJsonPrimitive -> element.asString
            else -> null
        }
    }
    
    /**
     * JSON 객체에서 정수 값을 안전하게 추출합니다.
     * 
     * @param jsonObject JSON 객체
     * @param key 키 이름
     * @return 정수 값 또는 null
     */
    private fun getJsonInt(jsonObject: JsonObject, key: String): Int? {
        val element = jsonObject.get(key) ?: return null
        return when {
            element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> element.asInt
            else -> null
        }
    }
    
    /**
     * 파싱 실패 시 기본 작업을 생성합니다.
     * 
     * @param requirement 사용자 요구사항
     * @return 기본 작업 1개를 포함한 리스트
     */
    private fun createFallbackTask(requirement: String): List<Task> {
        println("[TaskListGenerator] 폴백 작업을 생성합니다.")
        return listOf(
            Task(
                id = "task_1",
                title = "요구사항 분석 및 구현",
                description = requirement,
                status = TaskStatus.PENDING,
                order = 1
            )
        )
    }
}

