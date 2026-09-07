package com.lchuang.xiaozhimobile.accessibility

import com.lchuang.xiaozhimobile.screen.ContextCandidate
import com.lchuang.xiaozhimobile.screen.ScreenContext

enum class UiActionType {
    CLICK,
    SELECT,
    BACK,
    NEXT,
}

/**
 * A semantic action bound to one exact screen generation.
 *
 * Coordinates are deliberately not part of this object. An executor must
 * locate the live node again from the semantic identity immediately before
 * performing the action.
 */
data class UiActionProposal(
    val action: UiActionType,
    val context: ScreenContext,
    val target: ContextCandidate? = null,
) {
    init {
        require(action == UiActionType.BACK || target != null) {
            "A target is required for $action"
        }
        target?.let { candidate ->
            require(candidate.generationId == context.generationId) {
                "Target generation must match the action context"
            }
            require(candidate.packageName == context.packageName) {
                "Target package must match the action context"
            }
            require(candidate.windowFingerprint == context.windowFingerprint) {
                "Target window must match the action context"
            }
        }
    }
}

/**
 * A live, semantic accessibility node returned by the current UI bridge.
 *
 * This deliberately contains no saved coordinates or bounds. The generation
 * and window identity make the result usable only for the snapshot that was
 * just queried.
 */
data class CurrentAccessibilityNode(
    val id: String,
    val semanticLabel: String,
    val generationId: com.lchuang.xiaozhimobile.screen.GenerationId,
    val packageName: String,
    val windowFingerprint: String,
    val role: String? = null,
    val clickable: Boolean = false,
    val selectable: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "Accessibility node id must not be blank" }
        require(packageName.isNotBlank()) { "Accessibility node package must not be blank" }
        require(windowFingerprint.isNotBlank()) {
            "Accessibility node window fingerprint must not be blank"
        }
    }
}

fun interface AccessibilityNodeFinder {
    fun findCurrentNodes(
        context: ScreenContext,
        target: ContextCandidate,
    ): List<CurrentAccessibilityNode>
}

fun interface AccessibilityActionDriver {
    fun perform(action: UiActionType, node: CurrentAccessibilityNode?): Boolean
}
