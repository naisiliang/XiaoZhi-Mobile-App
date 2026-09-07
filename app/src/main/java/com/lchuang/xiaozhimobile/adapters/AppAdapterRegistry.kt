package com.lchuang.xiaozhimobile.adapters

import com.lchuang.xiaozhimobile.ToolExecutionResult
import com.lchuang.xiaozhimobile.accessibility.UiActionProposal
import com.lchuang.xiaozhimobile.accessibility.UiActionExecutor
import com.lchuang.xiaozhimobile.screen.ContextCandidate
import com.lchuang.xiaozhimobile.screen.ScreenContext

data class AppAdapterResolution(
    val proposal: UiActionProposal,
    val adapter: AppAdapter?,
    val usedGenericFallback: Boolean,
)

/** Selects app semantics while preserving one generic, centrally guarded executor. */
class AppAdapterRegistry(
    adapters: List<AppAdapter>,
    private val genericExecutor: UiActionExecutor,
) {
    private val adapters: List<AppAdapter> = adapters.toList()

    init {
        require(adapters.all { it.id.isNotBlank() }) { "App adapter id must not be blank" }
        require(adapters.map(AppAdapter::id).distinct().size == adapters.size) {
            "App adapter ids must be unique"
        }
    }

    fun adapterFor(packageName: String): AppAdapter? = adapters.firstOrNull { adapter ->
        runCatching { adapter.canHandle(packageName) }.getOrDefault(false)
    }

    fun classifyPage(context: ScreenContext): AppPageKind =
        adapterFor(context.packageName)
            ?.let { adapter -> runCatching { adapter.classifyPage(context) }.getOrNull() }
            ?: AppPageKind.UNKNOWN

    fun extractSemanticObjects(
        context: ScreenContext,
    ): List<ContextCandidate> =
        adapterFor(context.packageName)
            ?.let { adapter -> runCatching { adapter.extractSemanticObjects(context).toList() }.getOrNull() }
            ?: emptyList()

    fun resolveAction(proposal: UiActionProposal): AppAdapterResolution {
        val adapter = adapterFor(proposal.context.packageName)
        val adapted = adapter?.let { selected ->
            runCatching { selected.resolveAction(proposal) }.getOrNull()
        }
        return if (adapter != null && adapted != null && adapted.context == proposal.context) {
            AppAdapterResolution(
                proposal = adapted,
                adapter = adapter,
                usedGenericFallback = false,
            )
        } else {
            AppAdapterResolution(
                proposal = proposal,
                adapter = null,
                usedGenericFallback = true,
            )
        }
    }

    fun execute(proposal: UiActionProposal): ToolExecutionResult {
        val resolution = resolveAction(proposal)
        val result = genericExecutor.execute(resolution.proposal)
        val adapter = resolution.adapter ?: return result

        // A dedicated adapter can veto an already completed generic action,
        // but it cannot turn a blocked/failed safety result into success.
        if (!result.success) return result
        val verified = runCatching {
            adapter.verifyResult(resolution.proposal, result)
        }.getOrDefault(false)
        return if (verified) {
            result
        } else {
            ToolExecutionResult(false, "专用适配器未验证操作结果", "ADAPTER_RESULT_UNVERIFIED")
        }
    }
}
