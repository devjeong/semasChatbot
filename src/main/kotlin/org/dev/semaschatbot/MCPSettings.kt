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
        mcpEnabled = enabled
        saveSettings()
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
                connectionJson.addProperty("connectedAt", connection.connectedAt)
                connectionsArray.add(connectionJson)
            }
            settingsJson.add("mcpConnections", connectionsArray)
            
            configFile.writeText(gson.toJson(settingsJson))
            Logger.debug("MCPSettings", "MCP 설정 저장 완료")
        } catch (e: Exception) {
            Logger.error("MCPSettings", "설정 저장 오류: ${e.message}")
        }
    }
    
    /**
     * 설정을 파일에서 로드합니다.
     */
    fun loadSettings() {
        try {
            if (!configFile.exists()) {
                Logger.debug("MCPSettings", "설정 파일이 없습니다. 기본값 사용")
                return
            }
            
            val settingsJson = gson.fromJson(configFile.readText(), JsonObject::class.java)
            
            // MCP 활성화 상태 로드
            mcpEnabled = settingsJson.get("mcpEnabled")?.asBoolean ?: false
            
            // MCP 연결 정보 로드
            mcpConnections.clear()
            val connectionsArray = settingsJson.getAsJsonArray("mcpConnections")
            connectionsArray?.forEach { element ->
                val connectionJson = element.asJsonObject
                val connection = MCPConnection(
                    mcpId = connectionJson.get("mcpId")?.asString ?: "",
                    mcpName = connectionJson.get("mcpName")?.asString ?: "",
                    mcpEndpoint = connectionJson.get("mcpEndpoint")?.asString ?: "",
                    isConnected = connectionJson.get("isConnected")?.asBoolean ?: false,
                    connectedAt = connectionJson.get("connectedAt")?.asLong
                )
                if (connection.mcpId.isNotEmpty()) {
                    mcpConnections[connection.mcpId] = connection
                }
            }
            
            Logger.info("MCPSettings", "MCP 설정 로드 완료: 활성화=${mcpEnabled}, 연결=${mcpConnections.size}개")
        } catch (e: Exception) {
            Logger.error("MCPSettings", "설정 로드 오류: ${e.message}")
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

