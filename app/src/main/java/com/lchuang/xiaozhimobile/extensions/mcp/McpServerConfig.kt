package com.lchuang.xiaozhimobile.extensions.mcp

import com.lchuang.xiaozhimobile.extensions.ExtensionPermission
import java.net.URI
import java.util.Locale

enum class McpServerType(val wireName: String) {
    STDIO("stdio"),
    SSE("sse"),
    STREAMABLE_HTTP("streamable_http");

    companion object {
        fun fromWire(value: String): McpServerType? {
            val normalized = value.trim().lowercase(Locale.ROOT).replace('-', '_')
            return entries.firstOrNull { it.wireName == normalized } ?: when (normalized) {
                "http" -> STREAMABLE_HTTP
                else -> null
            }
        }
    }
}

/** User-provided MCP metadata; credentials are referenced, never stored here. */
data class McpServerConfig(
    val id: String,
    val name: String,
    val type: McpServerType,
    val endpoint: String,
    val declaredPermissions: Set<ExtensionPermission> = emptySet(),
    val credentialRef: String? = null,
    val enabled: Boolean = true,
) {
    fun validationError(): McpConfigCode? {
        if (!ID_PATTERN.matches(id)) return McpConfigCode.INVALID_ID
        if (name.isBlank() || name != name.trim() || name.length > MAX_NAME_LENGTH) {
            return McpConfigCode.INVALID_NAME
        }
        if (declaredPermissions.size > MAX_PERMISSIONS) return McpConfigCode.INVALID_PERMISSIONS
        if (type != McpServerType.STDIO && ExtensionPermission.NETWORK !in declaredPermissions) {
            return McpConfigCode.INVALID_PERMISSIONS
        }
        if (!validEndpoint(type, endpoint)) return McpConfigCode.INVALID_ENDPOINT
        if (credentialRef != null && !CREDENTIAL_PATTERN.matches(credentialRef)) {
            return McpConfigCode.INVALID_CREDENTIAL_REF
        }
        return null
    }

    /** Safe diagnostic representation; the credential reference is intentionally not included. */
    fun redacted(): McpServerDiagnostic = McpServerDiagnostic(
        id = id,
        name = name,
        type = type,
        endpoint = endpoint,
        enabled = enabled,
        credentialRef = if (credentialRef == null) null else REDACTED,
    )

    override fun toString(): String =
        "McpServerConfig(id=$id, name=$name, type=$type, endpoint=$endpoint, " +
            "credentialRef=${if (credentialRef == null) null else REDACTED}, enabled=$enabled)"

    companion object {
        private val ID_PATTERN = Regex("[a-z][a-z0-9._-]{2,63}")
        private val CREDENTIAL_PATTERN = Regex("[A-Za-z0-9._:-]{1,128}")
        private const val MAX_NAME_LENGTH = 128
        private const val MAX_ENDPOINT_LENGTH = 2 * 1024
        private const val MAX_PERMISSIONS = 64
        private const val REDACTED = "REDACTED"

        private fun validEndpoint(type: McpServerType, endpoint: String): Boolean {
            if (endpoint.isBlank() || endpoint != endpoint.trim() || endpoint.length > MAX_ENDPOINT_LENGTH) {
                return false
            }
            return when (type) {
                McpServerType.STDIO -> Regex("[a-z][a-z0-9._-]{2,127}").matches(endpoint)
                McpServerType.SSE, McpServerType.STREAMABLE_HTTP -> {
                    val uri = try {
                        URI(endpoint)
                    } catch (_: IllegalArgumentException) {
                        return false
                    }
                    uri.scheme.equals("https", ignoreCase = true) &&
                        !uri.host.isNullOrBlank() &&
                        uri.userInfo == null &&
                        uri.query == null &&
                        uri.fragment == null
                }
            }
        }
    }
}

enum class McpConfigCode {
    INVALID_ID,
    INVALID_NAME,
    INVALID_ENDPOINT,
    INVALID_CREDENTIAL_REF,
    INVALID_PERMISSIONS,
}

data class McpServerDiagnostic(
    val id: String,
    val name: String,
    val type: McpServerType,
    val endpoint: String,
    val enabled: Boolean,
    val credentialRef: String?,
)
