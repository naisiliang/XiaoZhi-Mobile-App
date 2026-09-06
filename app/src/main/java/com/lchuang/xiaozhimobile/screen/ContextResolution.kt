package com.lchuang.xiaozhimobile.screen

import java.util.Collections

enum class ContextResolutionConfidence {
    HIGH,
    MEDIUM,
    LOW,
}

enum class ContextTargetKind {
    GENERIC,
    VIDEO,
}

/** A semantic target tied to one exact ScreenContext generation. */
data class ContextCandidate(
    val id: String,
    val label: String,
    val kind: ContextTargetKind = ContextTargetKind.GENERIC,
    val position: Int? = null,
    val generationId: GenerationId,
    val packageName: String,
    val windowFingerprint: String,
) {
    init {
        require(id.isNotBlank()) { "Context candidate id must not be blank" }
        require(packageName.isNotBlank()) { "Context candidate package must not be blank" }
        require(windowFingerprint.isNotBlank()) { "Context candidate window must not be blank" }
        require(position == null || position > 0) { "Context candidate position must be positive" }
    }

    /** Keep semantic labels out of accidental logs while retaining useful identity. */
    override fun toString(): String =
        "ContextCandidate(id=$id, kind=$kind, position=$position, " +
            "generationId=$generationId, packageName=$packageName, windowFingerprint=$windowFingerprint)"
}

data class ContextualHistory(
    val currentTarget: ContextCandidate? = null,
    val recentOperation: ContextCandidate? = null,
    val sessionTarget: ContextCandidate? = null,
)

data class ContextResolution(
    val confidence: ContextResolutionConfidence,
    val candidate: ContextCandidate? = null,
    private val candidateOptions: List<ContextCandidate> = emptyList(),
    val generationId: GenerationId? = null,
    val packageName: String? = null,
    val windowFingerprint: String? = null,
) {
    val candidates: List<ContextCandidate> = Collections.unmodifiableList(candidateOptions.toList())
    val requiresClarification: Boolean = confidence == ContextResolutionConfidence.MEDIUM
}
