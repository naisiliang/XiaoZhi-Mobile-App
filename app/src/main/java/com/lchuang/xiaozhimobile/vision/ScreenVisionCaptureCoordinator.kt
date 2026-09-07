package com.lchuang.xiaozhimobile.vision

import com.lchuang.xiaozhimobile.screen.GenerationId
import com.lchuang.xiaozhimobile.screen.ScreenContext
import com.lchuang.xiaozhimobile.screen.ScreenContextStore
import com.lchuang.xiaozhimobile.screen.SensitiveScreenDetector

/** One current in-memory frame; never a persisted screenshot or a coordinate map. */
class VisionFrame internal constructor(
    val generationId: GenerationId,
    val packageName: String,
    val windowFingerprint: String,
    val capturedAtMs: Long,
    payload: ByteArray,
) {
    internal val payload: ByteArray = payload.copyOf()

    init {
        require(packageName.isNotBlank()) { "Vision frame package must not be blank" }
        require(windowFingerprint.isNotBlank()) {
            "Vision frame window fingerprint must not be blank"
        }
        require(payload.isNotEmpty()) { "Vision frame payload must not be empty" }
    }

    override fun toString(): String =
        "VisionFrame(generationId=$generationId, packageName=$packageName, " +
            "windowFingerprint=$windowFingerprint, capturedAtMs=$capturedAtMs)"
}

class VisionCaptureResult(
    val success: Boolean,
    val frame: VisionFrame? = null,
    val debugCode: String,
) {
    init {
        require(success == (frame != null)) {
            "Successful Vision capture must contain exactly one transient frame"
        }
    }
}

class ScreenVisionCaptureCoordinator(
    private val authorization: VisionSessionAuthorization,
    private val contextStore: ScreenContextStore,
    private val frameCapturer: VisionFrameCapturer,
    private val sensitiveScreenDetector: SensitiveScreenDetector = SensitiveScreenDetector(),
) {
    fun capture(
        sessionId: String,
        grant: VisionAuthorizationGrant?,
        context: ScreenContext?,
    ): VisionCaptureResult {
        if (!authorization.isAuthorized(sessionId, grant)) {
            return failure("VISION_NOT_AUTHORIZED")
        }
        val requestedContext = context ?: return failure("SCREEN_CONTEXT_STALE")
        val activeContext = contextStore.currentIfMatches(requestedContext)
            ?: return failure("SCREEN_CONTEXT_STALE")

        val sensitivity = sensitiveScreenDetector.detect(
            packageName = activeContext.packageName,
            root = activeContext.root,
        )
        if (sensitivity.isSensitive) {
            return failure("VISION_SENSITIVE_BLOCKED")
        }

        // Re-check user consent immediately before opening the capture bridge.
        if (!authorization.isAuthorized(sessionId, grant)) {
            return failure("VISION_NOT_AUTHORIZED")
        }
        val frame = runCatching {
            frameCapturer.captureCurrentFrame(activeContext)
        }.getOrNull() ?: return failure("VISION_CAPTURE_FAILED")

        if (!frameMatches(frame, activeContext)) {
            return failure("SCREEN_CONTEXT_STALE")
        }
        if (!authorization.isAuthorized(sessionId, grant)) {
            return failure("VISION_NOT_AUTHORIZED")
        }
        if (contextStore.currentIfMatches(activeContext) == null) {
            return failure("SCREEN_CONTEXT_STALE")
        }
        return VisionCaptureResult(true, frame, "VISION_CAPTURE_OK")
    }

    private fun frameMatches(frame: VisionFrame, context: ScreenContext): Boolean =
        frame.generationId == context.generationId &&
            frame.packageName == context.packageName &&
            frame.windowFingerprint == context.windowFingerprint &&
            frame.capturedAtMs >= context.capturedAtMs

    private fun failure(code: String) = VisionCaptureResult(false, null, code)
}

fun interface VisionFrameCapturer {
    fun captureCurrentFrame(context: ScreenContext): VisionFrame?
}
