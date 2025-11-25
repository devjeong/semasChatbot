package org.dev.semaschatbot

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import java.io.File

/**
 * MCP 설정 관리 클래스
 * MCP 기능 활성/비활성 상태 및 연결 상태를 영구 저장하고 로드합니다.
 */
class MCPSettings(private val project: Project) {
    
    private val gson = Gson()
    
    // 설정 파일 경로
    private val configFile: File
        get() {
            val basePath = project.basePath ?: System.getProperty("user.home")
            val configDir = File(basePath, ".semas-chatbot")
            configDir.mkdirs()
            return File(configDir, "mcp-settings.json")
        }
    
    // 현재 설정 (메모리 캐시)
    @Volatile
    private var mcpEnabled: Boolean = false
    
    @Volatile
    private var mcpConnections: MutableMap<String, MCPConnection> = mutableMapOf()
    
    /**
     * MCP 기능 활성화 상태를 반환합니다.
     */
    fun isMCPEnabled(): Boolean {
        return mcpEnabled
    }
    
    /**
     * MCP 기능 활성화 상태를 설정합니다.
     */
    fun setMCPEnabled(enabled: Boolean) {
        val oldValue = mcpEnabled
        mcpEnabled = enabled
        Logger.info("MCPSettings", "MCP 기능 활성화 상태 변경: $oldValue -> $enabled")
        saveSettings()
        Logger.info("MCPSettings", "설정 저장 후 확인: mcpEnabled=$mcpEnabled, 파일 존재=${configFile.exists()}")
    }
    
    /**
     * 특정 MCP의 연결 상태를 반환합니다.
     */
    fun isMCPConnected(mcpId: String): Boolean {
        return mcpConnections[mcpId]?.isConnected ?: false
    }
    
    /**
     * 특정 MCP의 연결 정보를 반환합니다.
     */
    fun getMCPConnection(mcpId: String): MCPConnection? {
        return mcpConnections[mcpId]
    }
    
    /**
     * 모든 MCP 연결 정보를 반환합니다.
     */
    fun getAllMCPConnections(): Map<String, MCPConnection> {
        return mcpConnections.toMap()
    }
    
    /**
     * MCP 연결 정보를 설정합니다.
     */
    fun setMCPConnection(mcpId: String, connection: MCPConnection) {
        mcpConnections[mcpId] = connection
        saveSettings()
    }
    
    /**
     * MCP 연결 정보를 제거합니다.
     */
    fun removeMCPConnection(mcpId: String) {
        mcpConnections.remove(mcpId)
        saveSettings()
    }
    
    /**
     * 모든 MCP 연결을 초기화합니다.
     */
    fun clearAllConnections() {
        mcpConnections.clear()
        saveSettings()
    }
    
    /**
     * 설정을 파일에 저장합니다.
     */
    private fun saveSettings() {
        try {
            val settingsJson = JsonObject()
            settingsJson.addProperty("mcpEnabled", mcpEnabled)
            
            val connectionsArray = com.google.gson.JsonArray()
            mcpConnections.values.forEach { connection ->
                val connectionJson = JsonObject()
                connectionJson.addProperty("mcpId", connection.mcpId)
                connectionJson.addProperty("mcpName", connection.mcpName)
                connectionJson.addProperty("mcpEndpoint", connection.mcpEndpoint)
                connectionJson.addProperty("isConnected", connection.isConnected)
                // null 안전하게 처리
                if (connection.connectedAt != null) {
                    connectionJson.addProperty("connectedAt", connection.connectedAt)
                } else {
                    connectionJson.add("connectedAt", com.google.gson.JsonNull.INSTANCE)
                }
                connectionsArray.add(connectionJson)
            }
            settingsJson.add("mcpConnections", connectionsArray)
            
            // 설정 파일 디렉토리 생성
            configFile.parentFile?.mkdirs()
            
            // 파일 저장
            configFile.writeText(gson.toJson(settingsJson))
            Logger.info("MCPSettings", "MCP 설정 저장 완료: 파일=${configFile.absolutePath}, 활성화=$mcpEnabled, 연결=${mcpConnections.size}개")
        } catch (e: Exception) {
            Logger.error("MCPSettings", "설정 저장 오류: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * 설정을 파일에서 로드합니다.
     * 외부에서도 호출 가능하도록 public으로 유지합니다.
     */
    fun loadSettings() {
        try {
            if (!configFile.exists()) {
                Logger.debug("MCPSettings", "설정 파일이 없습니다. 기본값 사용: ${configFile.absolutePath}")
                // 기본값으로 초기화
                mcpEnabled = false
                mcpConnections.clear()
                return
            }
            
            val fileContent = configFile.readText()
            if (fileContent.isBlank()) {
                Logger.debug("MCPSettings", "설정 파일이 비어있습니다. 기본값 사용")
                mcpEnabled = false
                mcpConnections.clear()
                return
            }
            
            val settingsJson = gson.fromJson(fileContent, JsonObject::class.java)
            
            // MCP 활성화 상태 로드
            val enabledElement = settingsJson.get("mcpEnabled")
            mcpEnabled = when {
                enabledElement == null -> false
                enabledElement.isJsonNull -> false
                else -> enabledElement.asBoolean
            }
            
            // MCP 연결 정보 로드
            mcpConnections.clear()
            val connectionsElement = settingsJson.get("mcpConnections")
            if (connectionsElement != null && connectionsElement.isJsonArray) {
                val connectionsArray = connectionsElement.asJsonArray
                connectionsArray.forEach { element ->
                    if (element.isJsonObject) {
                        val connectionJson = element.asJsonObject
                        val connectedAtElement = connectionJson.get("connectedAt")
                        val connectedAt = when {
                            connectedAtElement == null -> null
                            connectedAtElement.isJsonNull -> null
                            else -> try {
                                connectedAtElement.asLong
                            } catch (e: Exception) {
                                null
                            }
                        }
                        
                        val connection = MCPConnection(
                            mcpId = connectionJson.get("mcpId")?.asString ?: "",
                            mcpName = connectionJson.get("mcpName")?.asString ?: "",
                            mcpEndpoint = connectionJson.get("mcpEndpoint")?.asString ?: "",
                            isConnected = connectionJson.get("isConnected")?.asBoolean ?: false,
                            connectedAt = connectedAt
                        )
                        if (connection.mcpId.isNotEmpty()) {
                            mcpConnections[connection.mcpId] = connection
                        }
                    }
                }
            }
            
            Logger.info("MCPSettings", "MCP 설정 로드 완료: 파일=${configFile.absolutePath}, 활성화=$mcpEnabled, 연결=${mcpConnections.size}개")
        } catch (e: Exception) {
            Logger.error("MCPSettings", "설정 로드 오류: ${e.message}")
            Logger.error("MCPSettings", "설정 파일 경로: ${configFile.absolutePath}")
            e.printStackTrace()
            // 오류 발생 시 기본값으로 초기화
            mcpEnabled = false
            mcpConnections.clear()
        }
    }
    
    init {
        // 초기화 시 설정 로드
        loadSettings()
    }
}

/**
 * MCP 연결 정보 데이터 클래스
 */
data class MCPConnection(
    val mcpId: String,
    val mcpName: String,
    val mcpEndpoint: String,
    val isConnected: Boolean = false,
    val connectedAt: Long? = null
)

