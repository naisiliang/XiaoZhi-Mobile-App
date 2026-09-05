package com.lchuang.xiaozhimobile.safety

object ToolPolicyRegistry {
    private val decisions = mapOf(
        "open_app" to ToolPolicyResult(ToolDecision.ALLOW),
        "navigate" to ToolPolicyResult(ToolDecision.ALLOW),
        "search_nearby" to ToolPolicyResult(ToolDecision.ALLOW),
        "open_web" to ToolPolicyResult(ToolDecision.ALLOW),
        "media_play" to ToolPolicyResult(ToolDecision.ALLOW),
        "media_pause" to ToolPolicyResult(ToolDecision.ALLOW),
        "media_next" to ToolPolicyResult(ToolDecision.ALLOW),
        "media_previous" to ToolPolicyResult(ToolDecision.ALLOW),
        "volume_up" to ToolPolicyResult(ToolDecision.ALLOW),
        "volume_down" to ToolPolicyResult(ToolDecision.ALLOW),
        "set_volume" to ToolPolicyResult(ToolDecision.ALLOW),
        "flashlight_on" to ToolPolicyResult(ToolDecision.ALLOW),
        "flashlight_off" to ToolPolicyResult(ToolDecision.ALLOW),
        "send_text_message" to ToolPolicyResult(ToolDecision.CONFIRM)
    )

    private val restrictedTerms = setOf(
        "pay", "payment", "transfer", "password", "passwd", "otp", "one_time_password",
        "delete", "destroy", "destructive", "wipe", "root", "shell", "terminal", "exec", "sudo"
    )

    fun decisionFor(name: String): ToolPolicyResult? {
        val normalized = name.trim().lowercase()
        if (restrictedTerms.any(normalized::contains)) {
            return ToolPolicyResult(ToolDecision.BLOCK, ToolBlockReason.RESTRICTED_TOOL)
        }
        return decisions[normalized]
    }
}
