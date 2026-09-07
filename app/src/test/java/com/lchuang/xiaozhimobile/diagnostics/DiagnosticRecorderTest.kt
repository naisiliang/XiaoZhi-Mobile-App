package com.lchuang.xiaozhimobile.diagnostics

import com.lchuang.xiaozhimobile.MediaVolumeSnapshot
import com.lchuang.xiaozhimobile.providers.ProviderCapability
import com.lchuang.xiaozhimobile.providers.health.CapabilityHealthMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRecorderTest {
    @Test
    fun recorder_keeps_only_privacy_safe_metadata() {
        val recorder = DiagnosticRecorder(clockMs = { 123L })

        recorder.record(
            sessionId = "session-1",
            module = "provider",
            action = "request",
            resultCode = "FAILED",
            safeMetadata = mapOf(
                "endpoint_host" to "provider.example",
                "api_key" to "sk-secret",
                "password" to "hunter2",
                "otp" to "123456",
                "payment" to "4111111111111111",
                "raw_screenshot" to "captured-screen-bytes",
                "details" to "authorization=Bearer secret-token",
            ),
        )

        val event = recorder.snapshot().single()
        assertEquals(mapOf("endpoint_host" to "provider.example"), event.safeMetadata)
        assertFalse(recorder.snapshot().toString().contains("sk-secret"))
        assertFalse(recorder.snapshot().toString().contains("hunter2"))
        assertFalse(recorder.snapshot().toString().contains("secret-token"))
    }

    @Test
    fun media_volume_record_preserves_the_real_before_target_after_and_max_steps() {
        val recorder = DiagnosticRecorder(clockMs = { 456L })
        val snapshot = MediaVolumeSnapshot(
            requestedPercent = 70,
            beforeStep = 3,
            targetStep = 11,
            afterStep = 10,
            maxStep = 15,
            actualPercent = 67,
            isVolumeFixed = false,
            retryCount = 1,
            resultCode = "SYSTEM_LIMITED",
        )

        val event = recorder.recordMediaVolume(
            sessionId = "session-1",
            action = "set_volume",
            snapshot = snapshot,
            durationMs = 120L,
        )

        assertEquals("media", event.module)
        assertEquals("set_volume", event.action)
        assertEquals("SYSTEM_LIMITED", event.resultCode)
        assertEquals(
            mapOf(
                "requestedPercent" to "70",
                "beforeStep" to "3",
                "targetStep" to "11",
                "afterStep" to "10",
                "maxStep" to "15",
                "actualPercent" to "67",
                "isVolumeFixed" to "false",
                "retryCount" to "1",
            ),
            event.safeMetadata,
        )
    }

    @Test
    fun identical_notification_errors_are_recorded_once_until_the_dedup_window_expires() {
        var now = 1_000L
        val recorder = DiagnosticRecorder(
            clockMs = { now },
            notificationDedupWindowMs = 4_000L,
        )

        val first = recorder.recordNotificationError(
            sessionId = "session-1",
            module = "wake",
            action = "listen",
            resultCode = "TIMEOUT",
            safeMetadata = mapOf("phase" to "command"),
        )
        val duplicate = recorder.recordNotificationError(
            sessionId = "session-1",
            module = "wake",
            action = "listen",
            resultCode = "TIMEOUT",
            safeMetadata = mapOf("phase" to "command"),
        )

        assertNotNull(first)
        assertNull(duplicate)
        assertEquals(1, recorder.snapshot().size)

        now += 4_000L
        assertNotNull(
            recorder.recordNotificationError(
                sessionId = "session-1",
                module = "wake",
                action = "listen",
                resultCode = "TIMEOUT",
                safeMetadata = mapOf("phase" to "command"),
            ),
        )
        assertEquals(2, recorder.snapshot().size)
    }

    @Test
    fun health_monitor_can_publish_safe_state_events_without_recording_failure_reason() {
        val recorder = DiagnosticRecorder(clockMs = { 789L })
        val monitor = CapabilityHealthMonitor(diagnosticRecorder = recorder)

        val status = monitor.recordFailure(
            capability = ProviderCapability.TEXT,
            reason = "api_key=sk-do-not-record",
        )

        val event = recorder.snapshot().single()
        assertEquals("provider_health", event.module)
        assertEquals("capability_failure", event.action)
        assertEquals(status.state.name, event.resultCode)
        assertEquals("TEXT", event.safeMetadata["capability"])
        assertEquals("1", event.safeMetadata["consecutiveFailures"])
        assertTrue(event.safeMetadata["reasonPresent"] == "true")
        assertFalse(recorder.snapshot().toString().contains("sk-do-not-record"))
    }
}
