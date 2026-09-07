package com.lchuang.xiaozhimobile.vision

import com.lchuang.xiaozhimobile.screen.ContextCandidate
import com.lchuang.xiaozhimobile.screen.ContextTargetKind
import com.lchuang.xiaozhimobile.screen.ScreenContext
import com.lchuang.xiaozhimobile.screen.ScreenContextStore
import com.lchuang.xiaozhimobile.screen.SensitiveScreenDetector
import java.util.Collections

/** Structured model output; it deliberately has no screen coordinates. */
data class VisionSemanticCandidate(
    val id: String,
    val label: String,
    val kind: ContextTargetKind = ContextTargetKind.GENERIC,
    val position: Int? = null,
) {
    init {
        require(id.isNotBlank()) { "Vision candidate id must not be blank" }
        require(label.isNotBlank()) { "Vision candidate label must not be blank" }
        require(position == null || position > 0) {
            "Vision candidate position must be positive"
        }
    }
}

class VisionAnalysisResult(
    val success: Boolean,
    candidates: List<ContextCandidate> = emptyList(),
    val debugCode: String,
) {
    val candidates: List<ContextCandidate> =
        Collections.unmodifiableList(candidates.toList())

    init {
        require(success == (debugCode == "VISION_ANALYSIS_OK")) {
            "Vision result success and debug code must agree"
        }
        require(!success || this.candidates.isNotEmpty()) {
            "Successful Vision analysis must return structured candidates"
        }
    }
}

class ScreenVisionAnalyzer(
    private val contextStore: ScreenContextStore,
    private val modelClient: VisionModelClient,
    private val sensitiveScreenDetector: SensitiveScreenDetector = SensitiveScreenDetector(),
) {
    fun analyze(frame: VisionFrame): VisionAnalysisResult {
        val activeContext = contextStore.get(frame.packageName, frame.windowFingerprint)
            ?.takeIf { it.generationId == frame.generationId }
            ?.takeIf { frame.capturedAtMs >= it.capturedAtMs }
            ?: return failure("SCREEN_CONTEXT_STALE")

        val sensitivity = sensitiveScreenDetector.detect(
            packageName = activeContext.packageName,
            root = activeContext.root,
        )
        if (sensitivity.isSensitive) {
            return failure("VISION_SENSITIVE_BLOCKED")
        }
        if (contextStore.currentIfMatches(activeContext) == null) {
            return failure("SCREEN_CONTEXT_STALE")
        }

        val semanticCandidates = runCatching {
            modelClient.analyze(frame)
        }.getOrNull() ?: return failure("VISION_ANALYSIS_FAILED")

        if (contextStore.currentIfMatches(activeContext) == null) {
            return failure("SCREEN_CONTEXT_STALE")
        }
        if (semanticCandidates.map(VisionSemanticCandidate::id).toSet().size != semanticCandidates.size) {
            return failure("VISION_AMBIGUOUS")
        }

        val boundCandidates = runCatching {
            semanticCandidates.map { candidate ->
                ContextCandidate(
                    id = candidate.id,
                    label = candidate.label,
                    kind = candidate.kind,
                    position = candidate.position,
                    generationId = activeContext.generationId,
                    packageName = activeContext.packageName,
                    windowFingerprint = activeContext.windowFingerprint,
                )
            }
        }.getOrNull() ?: return failure("VISION_ANALYSIS_FAILED")

        return if (boundCandidates.isEmpty()) {
            failure("VISION_NO_CANDIDATES")
        } else {
            VisionAnalysisResult(true, boundCandidates, "VISION_ANALYSIS_OK")
        }
    }

    private fun failure(code: String) = VisionAnalysisResult(false, emptyList(), code)
}

fun interface VisionModelClient {
    fun analyze(frame: VisionFrame): List<VisionSemanticCandidate>
}
