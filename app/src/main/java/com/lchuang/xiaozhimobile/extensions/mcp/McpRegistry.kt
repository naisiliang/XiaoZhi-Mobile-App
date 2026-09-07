package com.lchuang.xiaozhimobile.extensions.mcp

import com.lchuang.xiaozhimobile.extensions.ExtensionPermission

enum class McpRegistryCode {
    INVALID_ID,
    INVALID_NAME,
    INVALID_ENDPOINT,
    INVALID_CREDENTIAL_REF,
    INVALID_PERMISSIONS,
    DUPLICATE_ID,
    SERVER_NOT_FOUND,
    SERVER_DISABLED,
    INVALID_SCHEMA,
    TOOL_NOT_FOUND,
    CLIENT_NOT_ATTACHED,
}

sealed interface McpRegistryResult {
    data class Registered(val config: McpServerConfig) : McpRegistryResult

    data class Enabled(val config: McpServerConfig) : McpRegistryResult

    data class Disabled(val config: McpServerConfig) : McpRegistryResult

    data class SchemaInstalled(
        val bindings: List<McpToolBinding>,
        val rejected: List<McpToolRejection>,
    ) : McpRegistryResult

    data class Rejected(val code: McpRegistryCode) : McpRegistryResult
}

/** Registry for explicitly user-added MCP servers; no server is auto-discovered or auto-enabled. */
class McpRegistry(
    private val inspector: McpToolInspector = McpToolInspector(),
) {
    private val serversById = linkedMapOf<String, McpServerConfig>()
    private val enabledIds = linkedSetOf<String>()
    private val bindingsByServer = linkedMapOf<String, List<McpToolBinding>>()
    private val clientsByServer = linkedMapOf<String, McpClient>()

    fun register(config: McpServerConfig): McpRegistryResult {
        val validationError = config.validationError()
        if (validationError != null) {
            return McpRegistryResult.Rejected(validationError.toRegistryCode())
        }
        if (serversById.containsKey(config.id)) {
            return McpRegistryResult.Rejected(McpRegistryCode.DUPLICATE_ID)
        }
        serversById[config.id] = config
        if (config.enabled) enabledIds += config.id
        return McpRegistryResult.Registered(config)
    }

    fun all(): List<McpServerConfig> = serversById.values.sortedBy { it.id }

    fun find(serverId: String): McpServerConfig? = serversById[serverId]

    fun isEnabled(serverId: String): Boolean = serverId in enabledIds

    fun enable(serverId: String): McpRegistryResult {
        val config = serversById[serverId] ?: return McpRegistryResult.Rejected(McpRegistryCode.SERVER_NOT_FOUND)
        enabledIds += serverId
        val updated = config.copy(enabled = true)
        serversById[serverId] = updated
        return McpRegistryResult.Enabled(updated)
    }

    fun disable(serverId: String): McpRegistryResult {
        val config = serversById[serverId] ?: return McpRegistryResult.Rejected(McpRegistryCode.SERVER_NOT_FOUND)
        enabledIds -= serverId
        val updated = config.copy(enabled = false)
        serversById[serverId] = updated
        return McpRegistryResult.Disabled(updated)
    }

    fun remove(serverId: String): Boolean {
        enabledIds -= serverId
        bindingsByServer.remove(serverId)
        clientsByServer.remove(serverId)
        return serversById.remove(serverId) != null
    }

    fun installToolSchema(serverId: String, schemaJson: String): McpRegistryResult {
        val config = serversById[serverId] ?: return McpRegistryResult.Rejected(McpRegistryCode.SERVER_NOT_FOUND)
        return when (val result = inspector.inspect(config, schemaJson)) {
            is McpInspectionResult.Rejected -> McpRegistryResult.Rejected(McpRegistryCode.INVALID_SCHEMA)
            is McpInspectionResult.Inspected -> {
                bindingsByServer[serverId] = result.bindings
                McpRegistryResult.SchemaInstalled(result.bindings, result.rejected)
            }
        }
    }

    fun tools(serverId: String): List<McpToolBinding> = bindingsByServer[serverId].orEmpty()

    fun attachClient(serverId: String, client: McpClient): Boolean {
        val config = serversById[serverId] ?: return false
        if (!client.matches(config)) return false
        clientsByServer[serverId] = client
        return true
    }

    fun invoke(
        serverId: String,
        remoteToolName: String,
        arguments: Map<String, Any?> = emptyMap(),
        timeoutMs: Long = 10_000L,
    ): McpCallResult {
        if (serverId !in serversById) return McpCallResult.Failed(McpCallCode.UNAVAILABLE)
        if (!isEnabled(serverId)) return McpCallResult.Failed(McpCallCode.UNAVAILABLE)
        val binding = bindingsByServer[serverId].orEmpty().firstOrNull { it.remoteName == remoteToolName }
            ?: return McpCallResult.Failed(McpCallCode.UNAVAILABLE)
        val client = clientsByServer[serverId] ?: return McpCallResult.Failed(McpCallCode.UNAVAILABLE)
        return client.call(binding, arguments, timeoutMs)
    }

    fun diagnostics(): List<McpServerDiagnostic> = serversById.values
        .sortedBy { it.id }
        .map { config ->
            config.copy(enabled = isEnabled(config.id)).redacted()
        }

    private fun McpConfigCode.toRegistryCode(): McpRegistryCode = when (this) {
        McpConfigCode.INVALID_ID -> McpRegistryCode.INVALID_ID
        McpConfigCode.INVALID_NAME -> McpRegistryCode.INVALID_NAME
        McpConfigCode.INVALID_ENDPOINT -> McpRegistryCode.INVALID_ENDPOINT
        McpConfigCode.INVALID_CREDENTIAL_REF -> McpRegistryCode.INVALID_CREDENTIAL_REF
        McpConfigCode.INVALID_PERMISSIONS -> McpRegistryCode.INVALID_PERMISSIONS
    }
}
