package com.lchuang.xiaozhimobile.adapters

import com.lchuang.xiaozhimobile.ToolExecutionResult
import com.lchuang.xiaozhimobile.accessibility.UiActionProposal
import com.lchuang.xiaozhimobile.screen.ContextCandidate
import com.lchuang.xiaozhimobile.screen.ScreenContext

enum class AppPageKind {
    UNKNOWN,
    MAP_HOME,
    MAP_SEARCH,
    MAP_NAVIGATION,
    LIST,
    OTHER,
}

/**
 * App-specific semantics only. An adapter has no Android action entry point;
 * the registry always sends its proposal to the generic safety-bound executor.
 */
interface AppAdapter {
    val id: String

    fun canHandle(packageName: String): Boolean

    fun classifyPage(context: ScreenContext): AppPageKind = AppPageKind.UNKNOWN

    fun extractSemanticObjects(context: ScreenContext): List<ContextCandidate> = emptyList()

    /** Return null when this adapter cannot refine the generic proposal. */
    fun resolveAction(proposal: UiActionProposal): UiActionProposal? = proposal

    /** Verify only the result already produced by the central executor. */
    fun verifyResult(proposal: UiActionProposal, result: ToolExecutionResult): Boolean = result.success
}
