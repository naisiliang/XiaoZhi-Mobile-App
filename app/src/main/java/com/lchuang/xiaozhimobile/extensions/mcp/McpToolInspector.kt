package com.lchuang.xiaozhimobile.extensions.mcp

import com.lchuang.xiaozhimobile.extensions.DeclarativeJsonException
import com.lchuang.xiaozhimobile.extensions.DeclarativeJsonParser
import com.lchuang.xiaozhimobile.extensions.ExtensionPermission
import com.lchuang.xiaozhimobile.safety.CentralSafetyPolicyEngine
import com.lchuang.xiaozhimobile.safety.ToolDecision
import com.lchuang.xiaozhimobile.safety.ToolInvocation
import java.nio.charset.StandardCharsets
import java.util.Locale

enum class McpLocalCapability {
    OPEN_WEB,
    SEARCH_NEARBY,
    OPEN_APP,
    NAVIGATE,
    MEDIA,
    VOLUME,
    FLASHLIGHT,
    MESSAGING,
}

data class McpToolBinding(
    val serverId: String,
    val remoteName: String,
    val localToolName: String,
    val capabilityClass: McpLocalCapability,
    val requiredPermissions: Set<ExtensionPermission> = emptySet(),
) {
    val localCapability: McpLocalCapability
        get() = capabilityClass
}

data class McpToolRejection(
    val remoteName: String,
    val code: McpInspectionCode,
)

sealed interface McpInspectionResult {
    data class Inspected(
        val bindings: List<McpToolBinding>,
        val rejected: List<McpToolRejection>,
    ) : McpInspectionResult

    data class Rejected(val code: McpInspectionCode) : McpInspectionResult
}

enum class McpInspectionCode {
    INVALID_SCHEMA,
    INVALID_TOOL,
    DUPLICATE_TOOL,
    UNKNOWN_LOCAL_CAPABILITY,
    PERMISSION_ESCALATION,
    SAFETY_BLOCKED,
}

/** Converts untrusted remote schemas into a small, app-owned local capability map. */
class McpToolInspector {
    private val safetyPolicy = CentralSafetyPolicyEngine()

    fun inspect(server: McpServerConfig, schemaJson: String): McpInspectionResult {
        if (schemaJson.toByteArray(StandardCharsets.UTF_8).size > MAX_SCHEMA_BYTES) {
            return McpInspectionResult.Rejected(McpInspectionCode.INVALID_SCHEMA)
        }
        val root = try {
            DeclarativeJsonParser(schemaJson).parseObject()
        } catch (_: DeclarativeJsonException) {
            return McpInspectionResult.Rejected(McpInspectionCode.INVALID_SCHEMA)
        }
        val rawTools = root["tools"] ?: (root["result"] as? Map<*, *>)?.get("tools")
        if (rawTools !is List<*> || rawTools.size > MAX_TOOLS) {
            return McpInspectionResult.Rejected(McpInspectionCode.INVALID_SCHEMA)
        }

        val bindings = mutableListOf<McpToolBinding>()
        val rejected = mutableListOf<McpToolRejection>()
        val seenRemoteNames = linkedSetOf<String>()
        for (raw in rawTools) {
            val tool = raw as? Map<*, *> ?: return McpInspectionResult.Rejected(McpInspectionCode.INVALID_SCHEMA)
            val remoteName = tool["name"] as? String
                ?: return McpInspectionResult.Rejected(McpInspectionCode.INVALID_SCHEMA)
            val normalizedName = remoteName.trim().lowercase(Locale.ROOT)
            if (remoteName != normalizedName || !TOOL_PATTERN.matches(normalizedName)) {
                rejected += McpToolRejection(remoteName, McpInspectionCode.INVALID_TOOL)
                continue
            }
            if (!seenRemoteNames.add(normalizedName)) {
                rejected += McpToolRejection(remoteName, McpInspectionCode.DUPLICATE_TOOL)
                continue
            }
            val description = tool["description"]
            if (description != null && (description !is String || description.length > MAX_DESCRIPTION_LENGTH)) {
                rejected += McpToolRejection(remoteName, McpInspectionCode.INVALID_TOOL)
                continue
            }

            val capability = capabilityFor(normalizedName)
            if (capability == null) {
                rejected += McpToolRejection(remoteName, McpInspectionCode.UNKNOWN_LOCAL_CAPABILITY)
                continue
            }
            val requiredPermissions = requiredPermissionsFor(normalizedName)
            val remotePermissions = parsePermissions(
                tool["permissions"] ?: tool["requiredPermissions"] ?: tool["required_permissions"],
            )
            val remoteAndroidPermissions = parseAndroidPermissions(
                tool["androidPermissions"] ?: tool["android_permissions"],
            )
            if (remotePermissions == null || remoteAndroidPermissions == null ||
                remoteAndroidPermissions.isNotEmpty() ||
                !server.declaredPermissions.containsAll(remotePermissions) ||
                !server.declaredPermissions.containsAll(requiredPermissions)
            ) {
                rejected += McpToolRejection(remoteName, McpInspectionCode.PERMISSION_ESCALATION)
                continue
            }
            if (safetyPolicy.evaluate(ToolInvocation(normalizedName)).decision == ToolDecision.BLOCK) {
                rejected += McpToolRejection(remoteName, McpInspectionCode.SAFETY_BLOCKED)
                continue
            }
            bindings += McpToolBinding(
                serverId = server.id,
                remoteName = remoteName,
                localToolName = normalizedName,
                capabilityClass = capability,
                requiredPermissions = requiredPermissions,
            )
        }
        return McpInspectionResult.Inspected(bindings, rejected)
    }

    private fun parsePermissions(value: Any?): Set<ExtensionPermission>? {
        if (value == null) return emptySet()
        if (value !is List<*> || value.size > MAX_PERMISSIONS) return null
        val result = linkedSetOf<ExtensionPermission>()
        for (raw in value) {
            val permission = (raw as? String)?.let(ExtensionPermission::fromWire) ?: return null
            if (!result.add(permission)) return null
        }
        return result
    }

    private fun parseAndroidPermissions(value: Any?): Set<String>? {
        if (value == null) return emptySet()
        if (value !is List<*> || value.size > MAX_PERMISSIONS) return null
        val result = linkedSetOf<String>()
        for (raw in value) {
            if (raw !is String || raw.isBlank() || raw.length > MAX_PERMISSION_LENGTH || !result.add(raw)) {
                return null
            }
        }
        return result
    }

    companion object {
        private val TOOL_PATTERN = Regex("[a-z][a-z0-9_.-]{0,63}")
        private const val MAX_SCHEMA_BYTES = 64 * 1024
        private const val MAX_TOOLS = 64
        private const val MAX_PERMISSIONS = 64
        private const val MAX_DESCRIPTION_LENGTH = 4 * 1024
        private const val MAX_PERMISSION_LENGTH = 256

        private val LOCAL_CAPABILITIES = mapOf(
            "open_web" to McpLocalCapability.OPEN_WEB,
            "search_nearby" to McpLocalCapability.SEARCH_NEARBY,
            "open_app" to McpLocalCapability.OPEN_APP,
            "navigate" to McpLocalCapability.NAVIGATE,
            "media_play" to McpLocalCapability.MEDIA,
            "media_pause" to McpLocalCapability.MEDIA,
            "media_next" to McpLocalCapability.MEDIA,
            "media_previous" to McpLocalCapability.MEDIA,
            "volume_up" to McpLocalCapability.VOLUME,
            "volume_down" to McpLocalCapability.VOLUME,
            "set_volume" to McpLocalCapability.VOLUME,
            "flashlight_on" to McpLocalCapability.FLASHLIGHT,
            "flashlight_off" to McpLocalCapability.FLASHLIGHT,
            "send_text_message" to McpLocalCapability.MESSAGING,
        )

        fun capabilityFor(localToolName: String): McpLocalCapability? =
            LOCAL_CAPABILITIES[localToolName.trim().lowercase(Locale.ROOT)]

        fun requiredPermissionsFor(localToolName: String): Set<ExtensionPermission> = buildSet {
            val normalized = localToolName.trim().lowercase(Locale.ROOT)
            if (normalized.contains("message")) add(ExtensionPermission.MESSAGING)
            if (normalized.startsWith("ui_") || normalized.contains("accessibility")) {
                add(ExtensionPermission.ACCESSIBILITY)
            }
            if (normalized == "read_screen" || normalized.startsWith("screen_")) {
                add(ExtensionPermission.READ_SCREEN)
            }
        }
    }
}
