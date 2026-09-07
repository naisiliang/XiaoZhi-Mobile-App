package com.lchuang.xiaozhimobile.screen

import java.util.Locale

/**
 * Resolves a semantic reference into a context-bound target.
 *
 * The returned resolution is a snapshot. An executor must revalidate its
 * generation against the store immediately before performing any UI action.
 */
class ContextualIntentResolver(
    private val contextStore: ScreenContextStore,
) {
    fun resolve(
        query: String,
        context: ScreenContext?,
        candidates: List<ContextCandidate> = emptyList(),
        history: ContextualHistory = ContextualHistory(),
    ): ContextResolution {
        if (context == null) return low(null)
        val activeContext = contextStore.currentIfMatches(context) ?: return low(context)

        val historyReferences = historyTargets(history)
        if (hasConflictingCurrentCandidates(activeContext, candidates + historyReferences)) return low(activeContext)

        val currentCandidates = uniqueCurrentCandidates(activeContext, candidates)
        val historyCandidates = uniqueCurrentCandidates(
            activeContext,
            historyReferences,
        )
        val reference = referenceOf(query)

        return when (reference) {
            Reference.ORDINAL_FIRST -> resolveOrdinal(1, activeContext, currentCandidates)
            Reference.ORDINAL_SECOND -> resolveOrdinal(2, activeContext, currentCandidates)
            Reference.NEXT -> resolveNext(activeContext, currentCandidates, history)
            Reference.THIS -> resolveUnique(
                activeContext,
                (currentCandidates + historyCandidates).distinctBy(ContextCandidate::id),
            )
            Reference.THAT -> resolveAnchors(activeContext, historyReferences)
            Reference.RECENT_THAT -> resolveAnchors(activeContext, listOfNotNull(history.recentOperation))
            Reference.PRONOUN -> resolveAnchors(activeContext, historyReferences)
            Reference.CURRENT -> resolveAnchors(
                activeContext,
                currentCandidates + listOfNotNull(
                    history.currentTarget,
                    history.currentAppTarget,
                    history.sessionTarget,
                ),
            )
            Reference.CURRENT_VIDEO -> resolveUnique(
                activeContext,
                currentVideoCandidates(activeContext, currentCandidates, history),
            )
            Reference.UNKNOWN -> low(activeContext)
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
            historyTargets(history),
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
        val matchingAnchors = candidates.filter { it.id == anchor.id }
        if (matchingAnchors.size != 1) return emptyList()
        val currentAnchor = matchingAnchors.single()
        currentAnchor.position?.let { position ->
            return candidates.filter { it.position == position + 1 }
        }
        val nextIndex = candidates.indexOfFirst { it.id == currentAnchor.id } + 1
        return candidates.getOrNull(nextIndex)?.let(::listOf) ?: emptyList()
    }

    private fun resolveAnchors(
        context: ScreenContext,
        rawAnchors: List<ContextCandidate>,
    ): ContextResolution {
        val anchors = uniqueCurrentCandidates(context, rawAnchors)
        if (anchors.isEmpty()) return low(context)
        return resolveUnique(context, anchors)
    }

    private fun historyTargets(history: ContextualHistory): List<ContextCandidate> = listOfNotNull(
        history.currentTarget,
        history.currentAppTarget,
        history.recentOperation,
        history.assistantPromptTarget,
        history.sessionTarget,
    )

    private fun currentVideoCandidates(
        context: ScreenContext,
        currentCandidates: List<ContextCandidate>,
        history: ContextualHistory,
    ): List<ContextCandidate> = (currentCandidates + uniqueCurrentCandidates(
        context,
        listOfNotNull(history.currentTarget, history.sessionTarget),
    )).distinctBy(ContextCandidate::id).filter { it.kind == ContextTargetKind.VIDEO }

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

    private fun hasConflictingCurrentCandidates(
        context: ScreenContext,
        candidates: List<ContextCandidate>,
    ): Boolean = candidates
        .filter { it.matches(context) }
        .groupBy(ContextCandidate::id)
        .values
        .any { sameIdCandidates -> sameIdCandidates.distinct().size > 1 }

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
        if (containsNegation(normalized)) return Reference.UNKNOWN

        val first = hasOrdinal(normalized, "一", "first")
        val second = hasOrdinal(normalized, "二", "second")
        val ordinalConflict = first && second
        if (normalized.contains("第一次") || normalized.contains("第一时间") ||
            normalized.contains("first time") ||
            ordinalConflict
        ) {
            return Reference.UNKNOWN
        }

        val recentThat = normalized.contains("刚才那个") || hasRecentEnglishThat(normalized)
        val signals = listOfNotNull(
            Reference.CURRENT_VIDEO.takeIf {
                normalized.contains("当前视频") || normalized.contains("current video")
            },
            Reference.ORDINAL_FIRST.takeIf { first },
            Reference.ORDINAL_SECOND.takeIf { second },
            Reference.NEXT.takeIf { isNextReference(normalized) },
            Reference.PRONOUN.takeIf { hasPronoun(normalized) },
            Reference.THIS.takeIf {
                normalized.contains("这个") || normalized.contains("此项") ||
                    hasEnglishReference(normalized, "this")
            },
            Reference.RECENT_THAT.takeIf { recentThat },
            Reference.THAT.takeIf {
                !recentThat && (normalized.contains("那个") || hasEnglishReference(normalized, "that"))
            },
            Reference.CURRENT.takeIf { hasCurrentReference(normalized) },
        )
        return signals.singleOrNull() ?: Reference.UNKNOWN
    }

    private fun hasOrdinal(normalized: String, chineseNumber: String, englishWord: String): Boolean {
        val chinese = ORDINAL_SUFFIXES.any { suffix -> normalized.contains("第$chineseNumber$suffix") } ||
            ORDINAL_CONNECTORS.any { connector -> normalized.contains("第$chineseNumber$connector") }
        val english = normalized == englishWord ||
            Regex("(^|[^a-z])$englishWord\\s+(one|item|entry|place|video|target|step)(?=$|[^a-z])")
                .containsMatchIn(normalized)
        return chinese || english
    }

    private fun containsNegation(normalized: String): Boolean =
        CHINESE_NEGATION_MARKERS.any(normalized::contains) || ENGLISH_NEGATION_MARKERS.any { marker ->
            Regex("(^|[^a-z])${Regex.escape(marker)}(?=$|[^a-z])").containsMatchIn(normalized)
        }

    private fun isNextReference(normalized: String): Boolean =
        normalized.contains("下一个") || normalized.contains("下一步") || normalized == "next" ||
            Regex("(^|[^a-z])next\\s+(one|item|entry|video|target|step)(?=$|[^a-z])")
                .containsMatchIn(normalized)

    private fun hasEnglishReference(normalized: String, word: String): Boolean =
        normalized == word ||
            Regex("(^|[^a-z])$word(?:\\s+one)?(?=$|[^a-z])").containsMatchIn(normalized)

    private fun hasRecentEnglishThat(normalized: String): Boolean =
        normalized.contains("just now") && hasEnglishReference(normalized, "that")

    private fun hasCurrentReference(normalized: String): Boolean =
        normalized.contains("当前目标") || normalized == "当前" || normalized.endsWith("当前") ||
            normalized == "current" || normalized.contains("current target")

    private fun hasPronoun(normalized: String): Boolean =
        Regex("(?:^|[^a-z])(he|she|him|her)(?=$|[^a-z])").containsMatchIn(normalized) ||
            Regex("[他她](?=$|[，。！？、,.!?])").containsMatchIn(normalized)

    private enum class Reference {
        ORDINAL_FIRST,
        ORDINAL_SECOND,
        NEXT,
        THIS,
        THAT,
        RECENT_THAT,
        PRONOUN,
        CURRENT,
        CURRENT_VIDEO,
        UNKNOWN,
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val ORDINAL_SUFFIXES = listOf("个", "项", "家", "位", "条", "张", "人", "步", "段", "页")
        val ORDINAL_CONNECTORS = listOf("和", "与", "、", ",", "，")
        val CHINESE_NEGATION_MARKERS = listOf("不要", "别", "不是", "不能", "不打开", "不播放", "不选择", "不点")
        val ENGLISH_NEGATION_MARKERS = listOf("not", "don't", "do not", "never", "cannot", "can't")
    }
}
