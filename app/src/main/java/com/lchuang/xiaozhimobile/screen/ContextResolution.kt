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
    val assistantPromptTarget: ContextCandidate? = null,
    val sessionTarget: ContextCandidate? = null,
    val currentAppTarget: ContextCandidate? = null,
)

data class ContextResolution(
    val confidence: ContextResolutionConfidence,
    val candidate: ContextCandidate? = null,
    private val candidateOptions: List<ContextCandidate> = emptyList(),
    val generationId: GenerationId? = null,
    val packageName: String? = null,
    val windowFingerprint: String? = null,
) {
    init {
        val resolvedCandidates = if (candidate != null) {
            listOf(candidate)
        } else {
            candidateOptions
        }
        require(candidate == null || candidateOptions.isEmpty()) {
            "A resolution cannot contain both a selected candidate and alternatives"
        }
        when (confidence) {
            ContextResolutionConfidence.HIGH -> require(candidate != null) {
                "HIGH resolution requires one selected candidate"
            }
            ContextResolutionConfidence.MEDIUM -> require(candidate == null && candidateOptions.size > 1) {
                "MEDIUM resolution requires multiple alternatives"
            }
            ContextResolutionConfidence.LOW -> require(candidate == null && candidateOptions.isEmpty()) {
                "LOW resolution must not select or expose candidates"
            }
        }
        if (resolvedCandidates.isNotEmpty()) {
            require(generationId != null && !packageName.isNullOrBlank() && !windowFingerprint.isNullOrBlank()) {
                "Resolved candidates require complete context identity"
            }
            require(resolvedCandidates.all { resolved ->
                resolved.generationId == generationId &&
                    resolved.packageName == packageName &&
                    resolved.windowFingerprint == windowFingerprint
            }) {
                "Resolved candidates must match the result context identity"
            }
        }
    }

    val candidates: List<ContextCandidate> = Collections.unmodifiableList(candidateOptions.toList())
    val requiresClarification: Boolean = confidence == ContextResolutionConfidence.MEDIUM
}
