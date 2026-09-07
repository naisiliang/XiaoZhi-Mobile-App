package com.lchuang.xiaozhimobile.media

import com.lchuang.xiaozhimobile.accessibility.UiActionProposal
import com.lchuang.xiaozhimobile.screen.ContextCandidate
import com.lchuang.xiaozhimobile.screen.ScreenContext

/**
 * App-specific music semantics. Implementations may locate a semantic target
 * and return a context-bound UiActionProposal, but they do not execute it.
 */
interface MusicAppAdapter {
    val id: String

    fun canHandle(app: MusicApp): Boolean

    fun propose(
        intent: MusicIntent,
        screen: ScreenContext,
    ): MusicAdapterResolution
}

sealed interface MusicAdapterResolution {
    data class Proposed(
        val intent: MusicIntent,
        val proposal: UiActionProposal,
    ) : MusicAdapterResolution {
        init {
            require(proposal.context.packageName.isNotBlank()) {
                "Music proposal must be bound to a package"
            }
        }
    }

    data class NeedsClarification(
        val candidates: List<ContextCandidate> = emptyList(),
        val reason: MusicClarificationReason,
    ) : MusicAdapterResolution {
        init {
            require(candidates.distinctBy(ContextCandidate::id).size == candidates.size) {
                "Music clarification candidates must be unique"
            }
            require(
                candidates.zipWithNext().all { (first, second) ->
                    first.generationId == second.generationId &&
                        first.packageName == second.packageName &&
                        first.windowFingerprint == second.windowFingerprint
                },
            ) {
                "Music clarification candidates must share one screen context"
            }
            if (reason == MusicClarificationReason.MULTIPLE_RESULTS ||
                reason == MusicClarificationReason.AMBIGUOUS_TARGET
            ) {
                require(candidates.size > 1) {
                    "Ambiguous music target clarification requires multiple candidates"
                }
            }
        }
    }

    data object Unsupported : MusicAdapterResolution
}
