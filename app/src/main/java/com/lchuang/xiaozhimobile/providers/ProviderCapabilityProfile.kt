package com.lchuang.xiaozhimobile.providers

import com.lchuang.xiaozhimobile.AiEndpointResolver
import com.lchuang.xiaozhimobile.ApiMode
import java.net.URI

enum class ProviderCapability(val displayName: String) {
    TEXT("Text"),
    RESPONSES_API("Responses API"),
    CHAT_COMPLETIONS("Chat Completions"),
    FUNCTION_CALLING("Function Calling"),
    STRUCTURED_OUTPUT("Structured Output"),
    VISION("Vision"),
    IMAGE_GENERATION("Image Generation"),
    FILE_INPUT("File Input"),
    CODE_INTERPRETER("Code Interpreter"),
    MCP("MCP"),
    NATIVE_SKILLS("Native Skills"),
}

enum class CapabilitySupport {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN,
}

enum class ProviderConfigError {
    INVALID_BASE_URL,
    INVALID_MODEL,
    API_KEY_TOO_LONG,
}

/** Provider settings used for probing; the API key is never part of a profile. */
data class ProviderConnectionConfig(
    val baseUrl: String,
    val model: String,
    val apiMode: ApiMode,
    val apiKey: String = "",
) {
    fun validationError(): ProviderConfigError? {
        if (model.isBlank() || model != model.trim() || model.length > MAX_MODEL_LENGTH) {
            return ProviderConfigError.INVALID_MODEL
        }
        if (apiKey.length > MAX_API_KEY_LENGTH) return ProviderConfigError.API_KEY_TOO_LONG
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull() ?: return ProviderConfigError.INVALID_BASE_URL
        if (baseUrl.isBlank() || baseUrl != baseUrl.trim() || baseUrl.length > MAX_BASE_URL_LENGTH) {
            return ProviderConfigError.INVALID_BASE_URL
        }
        if (!uri.scheme.equals("http", ignoreCase = true) && !uri.scheme.equals("https", ignoreCase = true)) {
            return ProviderConfigError.INVALID_BASE_URL
        }
        if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null) {
            return ProviderConfigError.INVALID_BASE_URL
        }
        return runCatching { AiEndpointResolver.normalizeBaseUrl(baseUrl) }
            .fold(onSuccess = { null }, onFailure = { ProviderConfigError.INVALID_BASE_URL })
    }

    fun normalizedBaseUrl(): String {
        require(validationError() == null) { "provider configuration is invalid" }
        return AiEndpointResolver.normalizeBaseUrl(baseUrl)
    }

    override fun toString(): String =
        "ProviderConnectionConfig(baseUrl=$baseUrl, model=$model, apiMode=$apiMode, apiKey=${if (apiKey.isBlank()) "" else REDACTED})"

    companion object {
        private const val MAX_BASE_URL_LENGTH = 2 * 1024
        private const val MAX_MODEL_LENGTH = 256
        private const val MAX_API_KEY_LENGTH = 8 * 1024
        private const val REDACTED = "REDACTED"
    }
}

data class ProviderCapabilityProfile(
    val baseUrl: String,
    val model: String,
    val apiMode: ApiMode,
    val capabilities: Map<ProviderCapability, CapabilitySupport>,
    val nativeSkills: List<String> = emptyList(),
    val localSkills: List<String> = emptyList(),
    val checkedAtMs: Long = 0L,
) {
    fun status(capability: ProviderCapability): CapabilitySupport =
        capabilities[capability] ?: CapabilitySupport.UNKNOWN

    fun supports(capability: ProviderCapability): Boolean =
        status(capability) == CapabilitySupport.SUPPORTED
}
