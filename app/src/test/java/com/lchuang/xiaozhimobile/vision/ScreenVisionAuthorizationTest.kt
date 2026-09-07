package com.lchuang.xiaozhimobile.vision

import com.lchuang.xiaozhimobile.screen.ContextTargetKind
import com.lchuang.xiaozhimobile.screen.GenerationId
import com.lchuang.xiaozhimobile.screen.ScreenContext
import com.lchuang.xiaozhimobile.screen.ScreenContextStore
import com.lchuang.xiaozhimobile.screen.ScreenNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenVisionAuthorizationTest {
    @Test
    fun noSessionAuthorizationMeansCaptureIsNeverCalled() {
        val fixture = Fixture()
        val authorization = fixture.authorization()
        val capturer = RecordingCapturer(fixture.context)
        val coordinator = fixture.coordinator(authorization, capturer)

        val result = coordinator.capture(
            sessionId = fixture.sessionId,
            grant = null,
            context = fixture.context,
        )

        assertFalse(result.success)
        assertEquals("VISION_NOT_AUTHORIZED", result.debugCode)
        assertEquals(0, capturer.calls)
    }

    @Test
    fun sensitiveScreenBlocksCaptureAndAnalyzerCalls() {
        val fixture = Fixture(rootText = "确认支付")
        val authorization = fixture.authorization().also {
            it.beginSession(fixture.sessionId)
        }
        val grant = authorization.grantUserConsent(
            fixture.sessionId,
            UserMediaProjectionConsent("user-granted"),
        )
        assertNotNull(grant)
        val capturer = RecordingCapturer(fixture.context)
        val coordinator = fixture.coordinator(authorization, capturer)

        val capture = coordinator.capture(fixture.sessionId, grant, fixture.context)
        assertFalse(capture.success)
        assertEquals("VISION_SENSITIVE_BLOCKED", capture.debugCode)
        assertEquals(0, capturer.calls)

        val model = RecordingVisionModel()
        val analyzer = ScreenVisionAnalyzer(fixture.store, model)
        val frame = fixture.frame()
        val analysis = analyzer.analyze(frame)

        assertFalse(analysis.success)
        assertEquals("VISION_SENSITIVE_BLOCKED", analysis.debugCode)
        assertEquals(0, model.calls)
    }

    @Test
    fun endingSessionInvalidatesTheGrantImmediately() {
        val fixture = Fixture()
        val authorization = fixture.authorizedSession()
        val grant = authorization.grantUserConsent(
            fixture.sessionId,
            UserMediaProjectionConsent("user-granted"),
        )
        assertNotNull(grant)
        assertTrue(authorization.isAuthorized(fixture.sessionId, grant))

        authorization.endSession(fixture.sessionId)

        assertFalse(authorization.isAuthorized(fixture.sessionId, grant))
    }

    @Test
    fun unverifiedConsentCannotCreateAGrant() {
        val fixture = Fixture()
        val authorization = VisionSessionAuthorization(
            processInstanceId = "process-one",
            consentVerifier = { false },
            clockMs = { 1_000L },
        )
        authorization.beginSession(fixture.sessionId)

        val grant = authorization.grantUserConsent(
            fixture.sessionId,
            UserMediaProjectionConsent("unverified"),
        )

        assertNull(grant)
    }

    @Test
    fun aNewProcessCannotReuseAnOldInMemoryGrant() {
        val fixture = Fixture()
        val oldProcess = fixture.authorizedSession(processInstanceId = "process-one")
        val grant = oldProcess.grantUserConsent(
            fixture.sessionId,
            UserMediaProjectionConsent("user-granted"),
        )
        val restartedProcess = fixture.authorization(processInstanceId = "process-two")

        assertNotNull(grant)
        assertFalse(restartedProcess.isAuthorized(fixture.sessionId, grant))
    }

    @Test
    fun staleContextNeverReachesCapture() {
        val fixture = Fixture()
        val authorization = fixture.authorizedSession()
        val grant = authorization.grantUserConsent(
            fixture.sessionId,
            UserMediaProjectionConsent("user-granted"),
        )
        fixture.store.publish(
            packageName = fixture.context.packageName,
            windowFingerprint = fixture.context.windowFingerprint,
            root = ScreenNode(id = "changed", text = "普通列表"),
        )
        val capturer = RecordingCapturer(fixture.context)

        val result = fixture.coordinator(authorization, capturer)
            .capture(fixture.sessionId, grant, fixture.context)

        assertFalse(result.success)
        assertEquals("SCREEN_CONTEXT_STALE", result.debugCode)
        assertEquals(0, capturer.calls)
    }

    @Test
    fun analyzerReturnsCandidatesBoundToTheCapturedGeneration() {
        val fixture = Fixture()
        val model = RecordingVisionModel(
            listOf(
                VisionSemanticCandidate(
                    id = "place-1",
                    label = "咖啡店",
                    kind = ContextTargetKind.GENERIC,
                    position = 1,
                ),
            ),
        )
        val analyzer = ScreenVisionAnalyzer(fixture.store, model)

        val result = analyzer.analyze(fixture.frame())

        assertTrue(result.success)
        assertEquals("VISION_ANALYSIS_OK", result.debugCode)
        assertEquals(1, model.calls)
        assertEquals(fixture.context.generationId, result.candidates.single().generationId)
        assertEquals(fixture.context.packageName, result.candidates.single().packageName)
        assertEquals(fixture.context.windowFingerprint, result.candidates.single().windowFingerprint)
    }

    @Test
    fun contextChangeAfterCaptureDropsTheFrameBeforeAnalysis() {
        val fixture = Fixture()
        val model = RecordingVisionModel()
        val analyzer = ScreenVisionAnalyzer(fixture.store, model)
        val frame = fixture.frame()
        fixture.store.publish(
            packageName = fixture.context.packageName,
            windowFingerprint = fixture.context.windowFingerprint,
            root = ScreenNode(id = "changed", text = "普通列表"),
        )

        val result = analyzer.analyze(frame)

        assertFalse(result.success)
        assertEquals("SCREEN_CONTEXT_STALE", result.debugCode)
        assertEquals(0, model.calls)
    }

    @Test
    fun duplicateVisionIdsFailClosedInsteadOfGuessing() {
        val fixture = Fixture()
        val duplicate = VisionSemanticCandidate("place-1", "咖啡店")
        val analyzer = ScreenVisionAnalyzer(
            fixture.store,
            RecordingVisionModel(listOf(duplicate, duplicate)),
        )

        val result = analyzer.analyze(fixture.frame())

        assertFalse(result.success)
        assertEquals("VISION_AMBIGUOUS", result.debugCode)
        assertTrue(result.candidates.isEmpty())
    }

    private class Fixture(
        rootText: String = "普通列表",
    ) {
        val sessionId = "session-1"
        val store = ScreenContextStore(ttlMs = 5_000L, clockMs = { 1_000L })
        val context = store.publish(
            packageName = "com.example.maps",
            windowFingerprint = "com.example.maps:1",
            root = ScreenNode(id = "root", text = rootText),
        )

        fun authorization(processInstanceId: String = "process-one") =
            VisionSessionAuthorization(
                processInstanceId = processInstanceId,
                consentVerifier = { consent -> consent.opaqueHandle == "user-granted" },
                clockMs = { 1_000L },
            )

        fun authorizedSession(processInstanceId: String = "process-one") =
            authorization(processInstanceId).also { it.beginSession(sessionId) }

        fun capturerFrame() = VisionFrame(
            generationId = context.generationId,
            packageName = context.packageName,
            windowFingerprint = context.windowFingerprint,
            capturedAtMs = 1_000L,
            payload = byteArrayOf(1, 2, 3),
        )

        fun frame() = capturerFrame()

        fun coordinator(
            authorization: VisionSessionAuthorization,
            capturer: RecordingCapturer,
        ) = ScreenVisionCaptureCoordinator(
            authorization = authorization,
            contextStore = store,
            frameCapturer = capturer,
        )
    }

    private class RecordingCapturer(
        private val context: ScreenContext,
    ) : VisionFrameCapturer {
        var calls = 0

        override fun captureCurrentFrame(context: ScreenContext): VisionFrame {
            calls += 1
            return VisionFrame(
                generationId = context.generationId,
                packageName = context.packageName,
                windowFingerprint = context.windowFingerprint,
                capturedAtMs = 1_000L,
                payload = byteArrayOf(1, 2, 3),
            )
        }
    }

    private class RecordingVisionModel(
        private val candidates: List<VisionSemanticCandidate> = emptyList(),
    ) : VisionModelClient {
        var calls = 0

        override fun analyze(frame: VisionFrame): List<VisionSemanticCandidate> {
            calls += 1
            return candidates
        }
    }
}
