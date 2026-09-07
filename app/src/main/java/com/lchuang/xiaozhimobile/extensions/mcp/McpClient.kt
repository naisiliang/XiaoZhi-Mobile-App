package com.lchuang.xiaozhimobile.extensions.mcp

import com.lchuang.xiaozhimobile.extensions.DeclarativeJsonException
import com.lchuang.xiaozhimobile.extensions.DeclarativeJsonNumber
import com.lchuang.xiaozhimobile.extensions.DeclarativeJsonParser
import com.lchuang.xiaozhimobile.safety.CentralSafetyPolicyEngine
import com.lchuang.xiaozhimobile.safety.ToolDecision
import com.lchuang.xiaozhimobile.safety.ToolInvocation
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

sealed interface McpTransportResult {
    data class Success(val responseJson: String) : McpTransportResult

    data object Timeout : McpTransportResult

    data object Unavailable : McpTransportResult
}

interface McpTransport {
    val serverType: McpServerType

    fun request(payload: String, timeoutMs: Long, credential: String?): McpTransportResult
}

fun interface McpCredentialProvider {
    fun credentialFor(serverId: String): String?
}

sealed interface McpCallResult {
    data class Succeeded(val responseJson: String) : McpCallResult

    data class NeedsConfirmation(val invocation: ToolInvocation) : McpCallResult

    data class Failed(val code: McpCallCode) : McpCallResult
}

enum class McpCallCode {
    INVALID_REQUEST,
    UNAVAILABLE,
    TIMEOUT,
    MALFORMED_RESPONSE,
    REMOTE_ERROR,
    SAFETY_BLOCKED,
    PERMISSION_NOT_DECLARED,
}

/** Minimal JSON-RPC client over an explicitly selected, injected transport. */
class McpClient(
    private val server: McpServerConfig,
    private val transport: McpTransport,
    private val credentialProvider: McpCredentialProvider = McpCredentialProvider { null },
) {
    val serverId: String
        get() = server.id

    val serverType: McpServerType
        get() = server.type

    fun matches(config: McpServerConfig): Boolean =
        server.id == config.id &&
            server.type == config.type &&
            server.endpoint == config.endpoint &&
            server.declaredPermissions == config.declaredPermissions &&
            server.credentialRef == config.credentialRef

    private val safetyPolicy = CentralSafetyPolicyEngine()

    fun call(
        binding: McpToolBinding,
        arguments: Map<String, Any?> = emptyMap(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): McpCallResult {
        if (binding.serverId != server.id || !TOOL_PATTERN.matches(binding.remoteName) ||
            !TOOL_PATTERN.matches(binding.localToolName) || timeoutMs !in 1..MAX_TIMEOUT_MS
        ) {
            return McpCallResult.Failed(McpCallCode.INVALID_REQUEST)
        }
        if (server.validationError() != null || transport.serverType != server.type) {
            return McpCallResult.Failed(McpCallCode.UNAVAILABLE)
        }
        val expectedCapability = McpToolInspector.capabilityFor(binding.localToolName)
        if (expectedCapability == null || expectedCapability != binding.capabilityClass ||
            binding.requiredPermissions != McpToolInspector.requiredPermissionsFor(binding.localToolName)
        ) {
            return McpCallResult.Failed(McpCallCode.INVALID_REQUEST)
        }
        if (!server.declaredPermissions.containsAll(binding.requiredPermissions)) {
            return McpCallResult.Failed(McpCallCode.PERMISSION_NOT_DECLARED)
        }
        if (arguments.size > MAX_ARGUMENTS) {
            return McpCallResult.Failed(McpCallCode.INVALID_REQUEST)
        }

        val invocation = ToolInvocation(binding.localToolName, arguments)
        when (safetyPolicy.evaluate(invocation).decision) {
            ToolDecision.BLOCK -> return McpCallResult.Failed(McpCallCode.SAFETY_BLOCKED)
            ToolDecision.CONFIRM -> return McpCallResult.NeedsConfirmation(invocation)
            ToolDecision.ALLOW -> Unit
        }

        val requestId = nextRequestId.getAndIncrement()
        val request = try {
            encodeCall(requestId, binding.remoteName, arguments)
        } catch (_: IllegalArgumentException) {
            return McpCallResult.Failed(McpCallCode.INVALID_REQUEST)
        }
        val credential = try {
            credentialProvider.credentialFor(server.id)
        } catch (_: Throwable) {
            return McpCallResult.Failed(McpCallCode.UNAVAILABLE)
        }
        if (credential != null && credential.length > MAX_CREDENTIAL_LENGTH) {
            return McpCallResult.Failed(McpCallCode.INVALID_REQUEST)
        }
        val transportResult = try {
            transport.request(request, timeoutMs, credential)
        } catch (_: Throwable) {
            McpTransportResult.Unavailable
        }
        return when (transportResult) {
            is McpTransportResult.Success -> when (validateResponse(transportResult.responseJson, requestId)) {
                null -> McpCallResult.Succeeded(transportResult.responseJson)
                McpCallCode.REMOTE_ERROR -> McpCallResult.Failed(McpCallCode.REMOTE_ERROR)
                else -> McpCallResult.Failed(McpCallCode.MALFORMED_RESPONSE)
            }
            McpTransportResult.Timeout -> McpCallResult.Failed(McpCallCode.TIMEOUT)
            McpTransportResult.Unavailable -> McpCallResult.Failed(McpCallCode.UNAVAILABLE)
        }
    }

    private fun validateResponse(responseJson: String, expectedId: Long): McpCallCode? {
        if (responseJson.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) {
            return McpCallCode.MALFORMED_RESPONSE
        }
        val root = try {
            DeclarativeJsonParser(responseJson).parseObject()
        } catch (_: DeclarativeJsonException) {
            return McpCallCode.MALFORMED_RESPONSE
        }
        val responseId = root["id"] as? DeclarativeJsonNumber
        if (root["jsonrpc"] != "2.0" || responseId?.raw?.toLongOrNull() != expectedId) {
            return McpCallCode.MALFORMED_RESPONSE
        }
        if (root.containsKey("error")) return McpCallCode.REMOTE_ERROR
        if (!root.containsKey("result")) return McpCallCode.MALFORMED_RESPONSE
        return null
    }

    private fun encodeCall(id: Long, remoteName: String, arguments: Map<String, Any?>): String {
        val encodedArguments = encodeValue(arguments, 0)
        val request = "{\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":\"tools/call\",\"params\":{" +
            "\"name\":${encodeValue(remoteName, 1)},\"arguments\":$encodedArguments}}"
        require(request.toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES) { "request is too large" }
        return request
    }

    private fun encodeValue(value: Any?, depth: Int): String {
        require(depth <= MAX_JSON_DEPTH) { "JSON nesting is too deep" }
        return when (value) {
            null -> "null"
            is String -> encodeString(value)
            is Boolean -> value.toString()
            is Byte, is Short, is Int, is Long -> value.toString()
            is Float -> value.takeIf { it.isFinite() }?.toString()
                ?: throw IllegalArgumentException("non-finite number")
            is Double -> value.takeIf { it.isFinite() }?.toString()
                ?: throw IllegalArgumentException("non-finite number")
            is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (key, nested) ->
                require(key is String && key.length <= MAX_KEY_LENGTH) { "object key is invalid" }
                "${encodeString(key)}:${encodeValue(nested, depth + 1)}"
            }
            is List<*> -> value.joinToString(",", "[", "]") { nested -> encodeValue(nested, depth + 1) }
            else -> throw IllegalArgumentException("unsupported JSON value")
        }
    }

    private fun encodeString(value: String): String {
        require(value.length <= MAX_STRING_LENGTH) { "string is too long" }
        val output = StringBuilder(value.length + 2).append('"')
        for (character in value) {
            when (character) {
                '"' -> output.append("\\\"")
                '\\' -> output.append("\\\\")
                '\b' -> output.append("\\b")
                '\u000C' -> output.append("\\f")
                '\n' -> output.append("\\n")
                '\r' -> output.append("\\r")
                '\t' -> output.append("\\t")
                else -> if (character.code < 0x20) {
                    output.append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    output.append(character)
                }
            }
        }
        return output.append('"').toString()
    }

    companion object {
        private val TOOL_PATTERN = Regex("[a-z][a-z0-9_.-]{0,63}")
        private val nextRequestId = AtomicLong(1L)
        private const val DEFAULT_TIMEOUT_MS = 10_000L
        private const val MAX_TIMEOUT_MS = 120_000L
        private const val MAX_ARGUMENTS = 32
        private const val MAX_JSON_DEPTH = 8
        private const val MAX_REQUEST_BYTES = 64 * 1024
        private const val MAX_RESPONSE_BYTES = 64 * 1024
        private const val MAX_STRING_LENGTH = 16 * 1024
        private const val MAX_KEY_LENGTH = 256
        private const val MAX_CREDENTIAL_LENGTH = 8 * 1024
    }
}
