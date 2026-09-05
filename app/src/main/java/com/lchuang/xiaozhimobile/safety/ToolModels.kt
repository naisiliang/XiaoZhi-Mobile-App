package com.lchuang.xiaozhimobile.safety

data class ToolInvocation(
    val name: String,
    val arguments: Map<String, Any?> = emptyMap()
)

enum class ToolDecision {
    ALLOW,
    CONFIRM,
    BLOCK
}

enum class ToolBlockReason {
    UNKNOWN_TOOL,
    RESTRICTED_TOOL
}

data class ToolPolicyResult(
    val decision: ToolDecision,
    val blockReason: ToolBlockReason? = null
)
