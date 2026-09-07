package com.lchuang.xiaozhimobile.accessibility

import com.lchuang.xiaozhimobile.ToolExecutionResult
import com.lchuang.xiaozhimobile.safety.CentralSafetyPolicyEngine
import com.lchuang.xiaozhimobile.safety.PermissionBroker
import com.lchuang.xiaozhimobile.safety.ToolInvocation
import com.lchuang.xiaozhimobile.safety.ToolPolicyResult
import com.lchuang.xiaozhimobile.screen.ContextCandidate
import com.lchuang.xiaozhimobile.screen.ScreenContext
import com.lchuang.xiaozhimobile.screen.ScreenContextStore
import com.lchuang.xiaozhimobile.screen.SensitiveScreenDetector
import com.lchuang.xiaozhimobile.tools.ToolDispatcher

/**
 * Executes low-risk generic accessibility actions only against a live node.
 *
 * Permission and central policy checks happen before any node lookup. The
 * context is then checked, the target is semantically re-found, and the
 * context is checked once more immediately before the driver is called. A
 * missing, duplicated, changed, expired, or sensitive target fails closed.
 */
class GenericAccessibilityExecutor(
    private val contextStore: ScreenContextStore,
    private val nodeFinder: AccessibilityNodeFinder,
    private val actionDriver: AccessibilityActionDriver,
    private val permissionBroker: PermissionBroker,
    private val policyEvaluator: (ToolInvocation) -> ToolPolicyResult =
        CentralSafetyPolicyEngine()::evaluate,
    private val sensitiveScreenDetector: SensitiveScreenDetector = SensitiveScreenDetector(),
) : UiActionExecutor {
    override fun execute(proposal: UiActionProposal): ToolExecutionResult {
        var result: ToolExecutionResult? = null
        val invocation = ToolInvocation(
            name = toolName(proposal.action),
            arguments = invocationArguments(proposal),
        )
        val dispatcher = ToolDispatcher(
            permissionBroker = permissionBroker,
            policyEvaluator = policyEvaluator,
            resultExecutors = mapOf(invocation.name to { _, callback ->
                callback(revalidateAndExecute(proposal))
            }),
        )

        return runCatching {
            dispatcher.dispatch(invocation) { dispatched -> result = dispatched }
            result ?: failure("UI_ACTION_FAILED", "该操作未返回执行结果")
        }.getOrElse {
            // Do not expose bridge exceptions or accidentally turn an
            // accessibility failure into an apparent successful action.
            failure("UI_ACTION_FAILED", "界面操作未执行")
        }
    }

    private fun revalidateAndExecute(proposal: UiActionProposal): ToolExecutionResult {
        val activeContext = contextStore.currentIfMatches(proposal.context)
            ?: return failure("SCREEN_CONTEXT_STALE", "当前界面已变化，请重新尝试")

        val sensitivity = sensitiveScreenDetector.detect(
            packageName = activeContext.packageName,
            root = activeContext.root,
        )
        if (sensitivity.isSensitive) {
            return failure("SAFETY_BLOCKED", "当前界面涉及敏感操作，已停止执行")
        }

        val currentNode = if (proposal.action == UiActionType.BACK && proposal.target == null) {
            null
        } else {
            val target = proposal.target
                ?: return failure("ACCESSIBILITY_NODE_MISSING", "未找到可执行的界面目标")
            when (val lookup = findCurrentNode(activeContext, target)) {
                is NodeLookup.Found -> lookup.node
                NodeLookup.Missing -> {
                    return failure("ACCESSIBILITY_NODE_MISSING", "未找到当前界面的语义目标")
                }
                NodeLookup.Ambiguous -> {
                    return failure("SCREEN_AMBIGUOUS", "当前界面存在多个相同目标，已停止执行")
                }
            }
        }

        // Re-check after the semantic lookup. Never use a node from a prior
        // generation, even if its id or old screen coordinates still exist.
        if (contextStore.currentIfMatches(activeContext) == null) {
            return failure("SCREEN_CONTEXT_STALE", "当前界面已变化，请重新尝试")
        }

        val performed = actionDriver.perform(proposal.action, currentNode)
        return if (performed) {
            ToolExecutionResult(true, "操作已执行", "UI_ACTION_EXECUTED")
        } else {
            failure("UI_ACTION_FAILED", "界面操作未执行")
        }
    }

    private fun findCurrentNode(
        context: ScreenContext,
        target: ContextCandidate,
    ): NodeLookup {
        val nodes = runCatching { nodeFinder.findCurrentNodes(context, target) }.getOrNull()
            ?: return NodeLookup.Missing
        val matching = nodes.filter { node ->
            node.id == target.id &&
                node.semanticLabel == target.label &&
                node.generationId == context.generationId &&
                node.packageName == context.packageName &&
                node.windowFingerprint == context.windowFingerprint
        }
        return when (matching.size) {
            0 -> NodeLookup.Missing
            1 -> NodeLookup.Found(matching.single())
            else -> NodeLookup.Ambiguous
        }
    }

    private sealed interface NodeLookup {
        data class Found(val node: CurrentAccessibilityNode) : NodeLookup
        data object Missing : NodeLookup
        data object Ambiguous : NodeLookup
    }

    private fun invocationArguments(proposal: UiActionProposal): Map<String, Any?> = buildMap {
        put("action", proposal.action.name)
        put("generationId", proposal.context.generationId.value)
        put("packageName", proposal.context.packageName)
        put("windowFingerprint", proposal.context.windowFingerprint)
        proposal.target?.let { target ->
            put("targetId", target.id)
        }
    }

    private fun toolName(action: UiActionType): String = when (action) {
        UiActionType.CLICK -> "ui_click"
        UiActionType.SELECT -> "ui_select"
        UiActionType.BACK -> "ui_back"
        UiActionType.NEXT -> "ui_next"
    }

    private fun failure(code: String, message: String): ToolExecutionResult =
        ToolExecutionResult(false, message, code)
}
