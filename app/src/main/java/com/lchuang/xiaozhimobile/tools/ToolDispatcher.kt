package com.lchuang.xiaozhimobile.tools

import com.lchuang.xiaozhimobile.ToolExecutionResult
import com.lchuang.xiaozhimobile.safety.CentralSafetyPolicyEngine
import com.lchuang.xiaozhimobile.safety.PermissionBroker
import com.lchuang.xiaozhimobile.safety.ToolBlockReason
import com.lchuang.xiaozhimobile.safety.ToolDecision
import com.lchuang.xiaozhimobile.safety.ToolInvocation
import com.lchuang.xiaozhimobile.safety.ToolPolicyResult

typealias ToolExecutor = (ToolInvocation) -> Unit
typealias ResultToolExecutor = (ToolInvocation, (ToolExecutionResult) -> Unit) -> Unit

data class DispatchResult(
    val code: String,
    val decision: ToolDecision,
    val blockReason: ToolBlockReason? = null
)

class ToolDispatcher(
    private val permissionBroker: PermissionBroker,
    private val policyEvaluator: (ToolInvocation) -> ToolPolicyResult = CentralSafetyPolicyEngine()::evaluate,
    private val executors: Map<String, ToolExecutor> = emptyMap(),
    private val resultExecutors: Map<String, ResultToolExecutor> = emptyMap()
) {
    fun dispatch(invocation: ToolInvocation): DispatchResult = dispatch(invocation) {}

    fun dispatch(
        invocation: ToolInvocation,
        onResult: (ToolExecutionResult) -> Unit
    ): DispatchResult {
        if (!permissionBroker.check(invocation)) {
            onResult(ToolExecutionResult(false, "该操作没有执行权限", "PERMISSION_DENIED"))
            return DispatchResult("PERMISSION_DENIED", ToolDecision.BLOCK)
        }

        val policy = policyEvaluator(invocation)
        if (policy.decision == ToolDecision.BLOCK) {
            onResult(ToolExecutionResult(
                false,
                "该操作不在安全工具白名单中",
                policy.blockReason?.name ?: "BLOCKED"
            ))
            return DispatchResult(
                code = policy.blockReason?.name ?: "BLOCKED",
                decision = ToolDecision.BLOCK,
                blockReason = policy.blockReason
            )
        }
        if (policy.decision == ToolDecision.CONFIRM) {
            onResult(ToolExecutionResult(false, "需要你的确认才能执行", "CONFIRMATION_REQUIRED"))
            return DispatchResult("CONFIRMATION_REQUIRED", ToolDecision.CONFIRM)
        }

        val name = invocation.name.trim().lowercase()
        resultExecutors[name]?.let { executor ->
            executor(invocation, onResult)
            return DispatchResult("EXECUTED", ToolDecision.ALLOW)
        }
        val executor = executors[name]
            ?: run {
                onResult(ToolExecutionResult(false, "该操作暂时不可用", "MISSING_EXECUTOR"))
                return DispatchResult("MISSING_EXECUTOR", ToolDecision.BLOCK)
            }
        executor(invocation)
        onResult(ToolExecutionResult(true, "操作已执行", "EXECUTED"))
        return DispatchResult("EXECUTED", ToolDecision.ALLOW)
    }
}
