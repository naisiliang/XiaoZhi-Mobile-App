package com.lchuang.xiaozhimobile.safety

class CentralSafetyPolicyEngine {
    fun evaluate(invocation: ToolInvocation): ToolPolicyResult {
        return ToolPolicyRegistry.decisionFor(invocation.name)
            ?: ToolPolicyResult(ToolDecision.BLOCK, ToolBlockReason.UNKNOWN_TOOL)
    }
}
