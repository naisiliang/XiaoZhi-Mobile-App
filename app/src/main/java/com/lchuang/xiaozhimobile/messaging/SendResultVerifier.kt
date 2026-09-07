package com.lchuang.xiaozhimobile.messaging

import com.lchuang.xiaozhimobile.screen.ScreenContext
import com.lchuang.xiaozhimobile.screen.ScreenNode
import java.util.Locale

enum class SendVerificationState {
    SENT,
    SEND_FAILED,
    SEND_UNVERIFIED,
}

/** Result of inspecting one post-action semantic snapshot. */
data class SendVerificationResult(
    val state: SendVerificationState,
    val retryCount: Int = 0,
    val debugCode: String,
) {
    init {
        require(retryCount == 0) { "Message sending must never retry automatically" }
        require(debugCode.isNotBlank()) { "Send verification debug code must not be blank" }
    }

    val verified: Boolean
        get() = state == SendVerificationState.SENT

    override fun toString(): String =
        "SendVerificationResult(state=$state, retryCount=$retryCount, debugCode=$debugCode)"
}

/**
 * Proves a send from semantic evidence only. A positive action callback is
 * not enough: the input must be empty and an exact outgoing bubble must be
 * visible in the current snapshot.
 */
class SendResultVerifier {
    fun verify(
        expectedBody: String,
        context: ScreenContext?,
        actionSucceeded: Boolean,
    ): SendVerificationResult {
        if (!actionSucceeded) {
            return SendVerificationResult(
                state = SendVerificationState.SEND_FAILED,
                debugCode = "MESSAGE_ACTION_FAILED",
            )
        }
        if (expectedBody.isBlank()) {
            return unverified("MESSAGE_EXPECTED_BODY_EMPTY")
        }
        val currentContext = context ?: return unverified("MESSAGE_SCREEN_UNAVAILABLE")
        val nodes = flatten(currentContext.root)
        val inputNodes = nodes.filter(::isMessageInput)
        if (inputNodes.size != 1) {
            return unverified("MESSAGE_INPUT_UNAVAILABLE")
        }
        if (!inputNodes.single().text.orEmpty().isBlank()) {
            return unverified("MESSAGE_INPUT_NOT_CLEARED")
        }
        val outgoingBubblePresent = nodes.any { node ->
            isOutgoingMessage(node) && node.text == expectedBody
        }
        if (!outgoingBubblePresent) {
            return unverified("MESSAGE_OUTGOING_BUBBLE_MISSING")
        }
        return SendVerificationResult(
            state = SendVerificationState.SENT,
            debugCode = "MESSAGE_SEND_VERIFIED",
        )
    }

    private fun unverified(debugCode: String) = SendVerificationResult(
        state = SendVerificationState.SEND_UNVERIFIED,
        debugCode = debugCode,
    )

    private fun flatten(root: ScreenNode): List<ScreenNode> = buildList {
        fun visit(node: ScreenNode) {
            add(node)
            node.children.forEach(::visit)
        }
        visit(root)
    }

    private fun isMessageInput(node: ScreenNode): Boolean {
        val role = normalize(node.role.orEmpty())
        val className = normalize(node.className.orEmpty())
        val label = normalize(listOfNotNull(node.text, node.contentDescription).firstOrNull().orEmpty())
        return role.contains("message_input") || role.contains("input") ||
            role.contains("editor") || className.contains("edittext") ||
            label.contains("输入消息") || label == "输入" || label == "message input"
    }

    private fun isOutgoingMessage(node: ScreenNode): Boolean {
        val role = normalize(node.role.orEmpty())
        return role.contains("outgoing") || role.contains("sent_message") ||
            role.contains("message_bubble_outgoing")
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).replace(WHITESPACE, " ").trim()

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
