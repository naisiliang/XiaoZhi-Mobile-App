package com.lchuang.xiaozhimobile.screen

import java.util.Locale

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
        if (!contextStore.isCurrent(context)) return low(context)

        val currentCandidates = uniqueCurrentCandidates(context, candidates)
        val historyCandidates = uniqueCurrentCandidates(
            context,
            historyTargets(history),
        )
        val allCandidates = (currentCandidates + historyCandidates).distinctBy(ContextCandidate::id)
        val reference = referenceOf(query)

        return when (reference) {
            Reference.ORDINAL_FIRST -> resolveOrdinal(1, context, currentCandidates)
            Reference.ORDINAL_SECOND -> resolveOrdinal(2, context, currentCandidates)
            Reference.NEXT -> resolveNext(context, currentCandidates, history)
            Reference.THIS -> resolveAnchors(context, historyTargets(history))
            Reference.THAT -> resolveAnchors(context, historyTargets(history))
            Reference.RECENT_THAT -> resolveAnchors(context, listOfNotNull(history.recentOperation))
            Reference.PRONOUN -> resolveAnchors(context, historyTargets(history))
            Reference.CURRENT -> resolveAnchors(
                context,
                listOfNotNull(history.currentTarget, history.currentAppTarget, history.sessionTarget),
            )
            Reference.CURRENT_VIDEO -> resolveUnique(
                context,
                allCandidates.filter { it.kind == ContextTargetKind.VIDEO },
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
        val first = hasOrdinal(normalized, "一", "first")
        val second = hasOrdinal(normalized, "二", "second")
        val ordinalConflict = first && second
        val ordinalNegated = (first || second) && containsNegation(normalized)
        if (normalized.contains("第一次") || normalized.contains("first time") ||
            ordinalConflict || ordinalNegated
        ) {
            return Reference.UNKNOWN
        }
        return when {
            normalized.contains("当前视频") || normalized.contains("current video") ->
                Reference.CURRENT_VIDEO
            first -> Reference.ORDINAL_FIRST
            second -> Reference.ORDINAL_SECOND
            isNextReference(normalized) -> Reference.NEXT
            hasPronoun(normalized) -> Reference.PRONOUN
            normalized.contains("这个") || normalized.contains("此项") || hasEnglishReference(normalized, "this") ->
                Reference.THIS
            normalized.contains("刚才那个") || hasRecentEnglishThat(normalized) -> Reference.RECENT_THAT
            normalized.contains("那个") || hasEnglishReference(normalized, "that") -> Reference.THAT
            normalized.contains("当前目标") || normalized == "当前" || normalized == "current" ||
                normalized.contains("current target") -> Reference.CURRENT
            else -> Reference.UNKNOWN
        }
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
        NEGATION_MARKERS.any(normalized::contains)

    private fun isNextReference(normalized: String): Boolean =
        normalized == "下一个" || normalized == "下一步" || normalized == "next" ||
            Regex("(^|[^a-z])next\\s+(one|item|entry|video|target|step)(?=$|[^a-z])")
                .containsMatchIn(normalized)

    private fun hasEnglishReference(normalized: String, word: String): Boolean =
        normalized == word ||
            Regex("(^|[^a-z])$word(?:\\s+one)?(?=$|[^a-z])").containsMatchIn(normalized)

    private fun hasRecentEnglishThat(normalized: String): Boolean =
        normalized.contains("just now") && hasEnglishReference(normalized, "that")

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
        val NEGATION_MARKERS = listOf("不要", "别", "不是", "不能", "not", "don't", "do not")
    }
}
