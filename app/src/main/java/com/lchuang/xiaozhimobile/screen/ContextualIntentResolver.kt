package com.lchuang.xiaozhimobile.screen

import java.util.Locale

class ContextualIntentResolver {
    fun resolve(
        query: String,
        context: ScreenContext?,
        candidates: List<ContextCandidate> = emptyList(),
        history: ContextualHistory = ContextualHistory(),
    ): ContextResolution {
        if (context == null) return low(null)

        val currentCandidates = uniqueCurrentCandidates(context, candidates)
        val reference = referenceOf(query)

        return when (reference) {
            Reference.ORDINAL_FIRST -> resolveOrdinal(1, context, currentCandidates)
            Reference.ORDINAL_SECOND -> resolveOrdinal(2, context, currentCandidates)
            Reference.NEXT -> resolveNext(context, currentCandidates, history)
            Reference.THIS -> resolveAnchors(
                context,
                listOfNotNull(history.currentTarget, history.recentOperation, history.sessionTarget),
            )
            Reference.THAT -> resolveAnchors(
                context,
                listOfNotNull(history.recentOperation, history.sessionTarget, history.currentTarget),
                useOnlyFirstNonNull = true,
            )
            Reference.CURRENT -> resolveAnchors(
                context,
                listOfNotNull(history.currentTarget, history.sessionTarget, history.recentOperation),
            )
            Reference.CURRENT_VIDEO -> resolveUnique(
                context,
                currentCandidates.filter { it.kind == ContextTargetKind.VIDEO },
            )
            Reference.UNKNOWN -> low(context)
        }
    }

    private fun resolveOrdinal(
        ordinal: Int,
        context: ScreenContext,
        candidates: List<ContextCandidate>,
    ): ContextResolution {
        if (candidates.isEmpty()) return low(context)

        val explicitlyPositioned = candidates.filter { it.position != null }
        if (explicitlyPositioned.isNotEmpty()) {
            return resolveUnique(context, explicitlyPositioned.filter { it.position == ordinal })
        }
        return if (ordinal <= candidates.size) {
            high(context, candidates[ordinal - 1])
        } else {
            low(context)
        }
    }

    private fun resolveNext(
        context: ScreenContext,
        candidates: List<ContextCandidate>,
        history: ContextualHistory,
    ): ContextResolution {
        val anchors = uniqueCurrentCandidates(
            context,
            listOfNotNull(history.currentTarget, history.recentOperation, history.sessionTarget),
        )
        if (anchors.isEmpty()) return low(context)

        val next = anchors.flatMap { anchor -> nextCandidates(anchor, candidates) }.distinctBy(ContextCandidate::id)
        if (next.isEmpty()) {
            // A history item that is not in the current candidate set cannot be
            // safely advanced; its generation is valid, but its page position is not.
            return low(context)
        }
        return resolveUnique(context, next)
    }

    private fun nextCandidates(
        anchor: ContextCandidate,
        candidates: List<ContextCandidate>,
    ): List<ContextCandidate> {
        anchor.position?.let { position ->
            return candidates.filter { it.position == position + 1 }
        }
        val matchingIndexes = candidates.mapIndexedNotNull { index, candidate ->
            index.takeIf { candidate.id == anchor.id }
        }
        if (matchingIndexes.size != 1) return emptyList()
        val nextIndex = matchingIndexes.single() + 1
        return candidates.getOrNull(nextIndex)?.let(::listOf) ?: emptyList()
    }

    private fun resolveAnchors(
        context: ScreenContext,
        rawAnchors: List<ContextCandidate>,
        useOnlyFirstNonNull: Boolean = false,
    ): ContextResolution {
        val anchors = uniqueCurrentCandidates(context, rawAnchors)
        if (anchors.isEmpty()) return low(context)
        if (useOnlyFirstNonNull) return high(context, anchors.first())
        return resolveUnique(context, anchors)
    }

    private fun resolveUnique(
        context: ScreenContext,
        candidates: List<ContextCandidate>,
    ): ContextResolution {
        val unique = candidates.distinctBy(ContextCandidate::id)
        return when (unique.size) {
            0 -> low(context)
            1 -> high(context, unique.single())
            else -> medium(context, unique)
        }
    }

    private fun uniqueCurrentCandidates(
        context: ScreenContext,
        candidates: List<ContextCandidate>,
    ): List<ContextCandidate> = candidates
        .filter { it.matches(context) }
        .distinctBy(ContextCandidate::id)

    private fun ContextCandidate.matches(context: ScreenContext): Boolean =
        generationId == context.generationId &&
            packageName == context.packageName &&
            windowFingerprint == context.windowFingerprint

    private fun high(context: ScreenContext, candidate: ContextCandidate) = ContextResolution(
        confidence = ContextResolutionConfidence.HIGH,
        candidate = candidate,
        generationId = context.generationId,
        packageName = context.packageName,
        windowFingerprint = context.windowFingerprint,
    )

    private fun medium(context: ScreenContext, candidates: List<ContextCandidate>) = ContextResolution(
        confidence = ContextResolutionConfidence.MEDIUM,
        candidateOptions = candidates,
        generationId = context.generationId,
        packageName = context.packageName,
        windowFingerprint = context.windowFingerprint,
    )

    private fun low(context: ScreenContext?) = ContextResolution(
        confidence = ContextResolutionConfidence.LOW,
        generationId = context?.generationId,
        packageName = context?.packageName,
        windowFingerprint = context?.windowFingerprint,
    )

    private fun referenceOf(query: String): Reference {
        val normalized = query.lowercase(Locale.ROOT).replace(WHITESPACE, " ").trim()
        if (normalized.isBlank()) return Reference.UNKNOWN
        return when {
            normalized.contains("当前视频") || normalized.contains("current video") ->
                Reference.CURRENT_VIDEO
            normalized.contains("第一") || normalized.contains("first") -> Reference.ORDINAL_FIRST
            normalized.contains("第二") || normalized.contains("second") -> Reference.ORDINAL_SECOND
            normalized.contains("下一个") || normalized.contains("下一步") || normalized.contains("next") ->
                Reference.NEXT
            normalized.contains("这个") || normalized.contains("此项") || normalized == "this" ||
                normalized.contains("this one") -> Reference.THIS
            normalized.contains("那个") || normalized.contains("刚才那个") || normalized == "that" ||
                normalized.contains("that one") -> Reference.THAT
            normalized.contains("当前目标") || normalized == "当前" || normalized == "current" ||
                normalized.contains("current target") -> Reference.CURRENT
            else -> Reference.UNKNOWN
        }
    }

    private enum class Reference {
        ORDINAL_FIRST,
        ORDINAL_SECOND,
        NEXT,
        THIS,
        THAT,
        CURRENT,
        CURRENT_VIDEO,
        UNKNOWN,
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
