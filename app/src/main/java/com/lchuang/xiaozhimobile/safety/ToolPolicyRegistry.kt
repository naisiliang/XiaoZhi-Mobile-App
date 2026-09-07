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
        "ui_click" to ToolPolicyResult(ToolDecision.ALLOW),
        "ui_select" to ToolPolicyResult(ToolDecision.ALLOW),
        "ui_back" to ToolPolicyResult(ToolDecision.ALLOW),
        "ui_next" to ToolPolicyResult(ToolDecision.ALLOW),
        "send_text_message" to ToolPolicyResult(ToolDecision.CONFIRM),

        // Declarative artifact/image/research tools used by bounded Agents.
        "ppt_create" to ToolPolicyResult(ToolDecision.ALLOW),
        "ppt_add_asset" to ToolPolicyResult(ToolDecision.ALLOW),
        "ppt_validate" to ToolPolicyResult(ToolDecision.ALLOW),
        "ppt_save" to ToolPolicyResult(ToolDecision.CONFIRM),
        "image_generate" to ToolPolicyResult(ToolDecision.ALLOW),
        "image_edit" to ToolPolicyResult(ToolDecision.ALLOW),
        "image_regenerate" to ToolPolicyResult(ToolDecision.ALLOW),
        "image_save" to ToolPolicyResult(ToolDecision.CONFIRM),
        "image_share" to ToolPolicyResult(ToolDecision.CONFIRM),
        "file_create_text" to ToolPolicyResult(ToolDecision.ALLOW),
        "file_create_markdown" to ToolPolicyResult(ToolDecision.ALLOW),
        "file_create_csv" to ToolPolicyResult(ToolDecision.ALLOW),
        "file_create_zip" to ToolPolicyResult(ToolDecision.ALLOW),
        "file_create_docx" to ToolPolicyResult(ToolDecision.ALLOW),
        "file_create_xlsx" to ToolPolicyResult(ToolDecision.ALLOW),
        "file_create_pdf" to ToolPolicyResult(ToolDecision.ALLOW),
        "file_open" to ToolPolicyResult(ToolDecision.CONFIRM),
        "file_edit" to ToolPolicyResult(ToolDecision.ALLOW),
        "file_restore" to ToolPolicyResult(ToolDecision.ALLOW),
        "file_save" to ToolPolicyResult(ToolDecision.CONFIRM),
        "file_share" to ToolPolicyResult(ToolDecision.CONFIRM),
        "artifact_open" to ToolPolicyResult(ToolDecision.CONFIRM),
        "artifact_edit" to ToolPolicyResult(ToolDecision.ALLOW),
        "artifact_restore" to ToolPolicyResult(ToolDecision.ALLOW),
        "artifact_save" to ToolPolicyResult(ToolDecision.CONFIRM),
        "artifact_share" to ToolPolicyResult(ToolDecision.CONFIRM),
        "research_search" to ToolPolicyResult(ToolDecision.ALLOW),
        "research_read" to ToolPolicyResult(ToolDecision.ALLOW),
        "research_summarize" to ToolPolicyResult(ToolDecision.ALLOW)
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
