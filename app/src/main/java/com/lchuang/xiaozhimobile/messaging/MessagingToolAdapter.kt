package com.lchuang.xiaozhimobile.messaging

import com.lchuang.xiaozhimobile.AiToolDefinition
import com.lchuang.xiaozhimobile.safety.ToolInvocation
import java.util.Locale

/**
 * Converts the one supported messaging tool into a typed request.
 *
 * This adapter is intentionally a declaration and validation boundary only.
 * It does not own a send executor, UI driver, confirmation token, or Android
 * permission. The coordinator and central dispatcher remain the only paths
 * for later preparation and execution.
 */
class MessagingToolAdapter {
    fun definitions(): List<AiToolDefinition> = listOf(definition())

    fun definition(): AiToolDefinition = AiToolDefinition(
        name = TOOL_NAME,
        description = "准备发送一条微信或QQ普通文字消息，发送前必须二次确认",
        properties = mapOf(
            "packageName" to "string",
            "contactReference" to "string",
            "body" to "string",
        ),
        required = listOf("packageName", "contactReference", "body"),
    )

    fun resolve(invocation: ToolInvocation): MessagingToolResolution {
        if (!invocation.name.trim().equals(TOOL_NAME, ignoreCase = true)) {
            return MessagingToolResolution.Rejected("MESSAGE_TOOL_UNKNOWN")
        }
        val unsupported = invocation.arguments.keys - ARGUMENT_NAMES
        if (unsupported.isNotEmpty()) {
            return MessagingToolResolution.Rejected("MESSAGE_UNSUPPORTED_ARGUMENT")
        }
        if (invocation.arguments.keys != ARGUMENT_NAMES) {
            return MessagingToolResolution.Rejected("MESSAGE_ARGUMENTS_INVALID")
        }

        val packageName = stringArgument(invocation, "packageName")?.lowercase(Locale.ROOT)
        val contactReference = stringArgument(invocation, "contactReference")
        val body = stringArgument(invocation, "body")
        if (packageName == null || contactReference == null || body == null) {
            return MessagingToolResolution.Rejected("MESSAGE_ARGUMENTS_INVALID")
        }
        if (packageName !in SUPPORTED_PACKAGES) {
            return MessagingToolResolution.Rejected("MESSAGE_UNSUPPORTED_PACKAGE")
        }
        return MessagingToolResolution.Accepted(
            MessagingRequest(
                packageName = packageName,
                contactReference = contactReference,
                body = body,
            ),
        )
    }

    private fun stringArgument(invocation: ToolInvocation, name: String): String? =
        (invocation.arguments[name] as? String)?.trim()?.takeIf(String::isNotBlank)

    companion object {
        const val TOOL_NAME = "send_text_message"
        private val ARGUMENT_NAMES = setOf("packageName", "contactReference", "body")
        private val SUPPORTED_PACKAGES = setOf("com.tencent.mm", "com.tencent.mobileqq")
    }
}

sealed interface MessagingToolResolution {
    data class Accepted(val request: MessagingRequest) : MessagingToolResolution

    data class Rejected(val code: String) : MessagingToolResolution {
        init {
            require(code.isNotBlank()) { "Messaging tool rejection code must not be blank" }
        }
    }
}
