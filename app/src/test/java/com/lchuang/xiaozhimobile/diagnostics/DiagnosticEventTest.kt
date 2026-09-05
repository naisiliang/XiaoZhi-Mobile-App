package com.lchuang.xiaozhimobile.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticEventTest {
    @Test
    fun `event exposes the required fields and keeps ordinary safe metadata`() {
        val event = DiagnosticEvent(
            timestamp = 1_700_000_000_000L,
            sessionId = "session-1",
            module = "media",
            action = "set_volume",
            resultCode = "SUCCESS",
            durationMs = 42L,
            safeMetadata = mapOf("volume_before" to "3", "volume_after" to "5"),
        )

        assertEquals(1_700_000_000_000L, event.timestamp)
        assertEquals("session-1", event.sessionId)
        assertEquals("media", event.module)
        assertEquals("set_volume", event.action)
        assertEquals("SUCCESS", event.resultCode)
        assertEquals(42L, event.durationMs)
        assertEquals(mapOf("volume_before" to "3", "volume_after" to "5"), event.safeMetadata)
    }

    @Test
    fun `construction removes secrets payment data and raw screenshots from metadata`() {
        val event = DiagnosticEvent(
            timestamp = 1L,
            sessionId = "session-1",
            module = "diagnostics",
            action = "record",
            resultCode = "FAILED",
            durationMs = 0L,
            safeMetadata = mapOf(
                "volume_before" to "3",
                "volume_after" to "5",
                "api_key" to "sk-test-secret",
                "password" to "correct horse battery staple",
                "verification_code" to "123456",
                "card_number" to "4111111111111111",
                "screenshot" to "data:image/png;base64,raw-image-bytes",
                "details" to "authorization=Bearer secret-token",
            ),
        )

        assertEquals("3", event.safeMetadata["volume_before"])
        assertEquals("5", event.safeMetadata["volume_after"])
        listOf(
            "api_key",
            "password",
            "verification_code",
            "card_number",
            "screenshot",
            "details",
        ).forEach { forbiddenKey ->
            assertFalse("forbidden key retained: $forbiddenKey", event.safeMetadata.containsKey(forbiddenKey))
        }
        assertTrue(event.safeMetadata.values.none { value ->
            value.contains("sk-test-secret") ||
                value.contains("correct horse") ||
                value.contains("123456") ||
                value.contains("4111111111111111") ||
                value.contains("raw-image-bytes") ||
                value.contains("secret-token")
        })
    }

    @Test
    fun `generic keys remove labeled sensitive assignments but preserve safe metadata`() {
        val event = DiagnosticEvent(
            timestamp = 1L,
            sessionId = "session-1",
            module = "diagnostics",
            action = "capture",
            resultCode = "SUCCESS",
            durationMs = 12L,
            safeMetadata = mapOf(
                "details" to "password=hunter2",
                "notes" to "api_key=my-dev-key",
                "otp_hint" to "otp=654321",
                "capture_info" to "screenshot=data:image/png;base64,raw-image-data",
                "payment_note" to "card_number=4111111111111111",
                "token_count" to "42",
                "cover_image_url" to "https://example.test/cover-image.png",
                "frame_count" to "123456",
                "capture_timestamp" to "1700000000000",
                "duration_label" to "13 digit diagnostic id 1234567890123",
                "secret_assignment" to "secret=public-feature-name",
                "authorization_json" to "{\"authorization\":\"user-interface-mode\"}",
            ),
        )

        assertEquals(
            mapOf(
                "token_count" to "42",
                "cover_image_url" to "https://example.test/cover-image.png",
                "frame_count" to "123456",
                "capture_timestamp" to "1700000000000",
                "duration_label" to "13 digit diagnostic id 1234567890123",
                "secret_assignment" to "secret=public-feature-name",
                "authorization_json" to "{\"authorization\":\"user-interface-mode\"}",
            ),
            event.safeMetadata,
        )
    }

    @Test
    fun `generic plain authorization assignment remains safe metadata`() {
        val event = DiagnosticEvent(
            timestamp = 1L,
            sessionId = "session-1",
            module = "diagnostics",
            action = "capture",
            resultCode = "SUCCESS",
            durationMs = 0L,
            safeMetadata = mapOf("details" to "authorization=user-interface-mode"),
        )

        assertEquals("authorization=user-interface-mode", event.safeMetadata["details"])
    }

    @Test
    fun `construction removes direct raw screenshot metadata`() {
        val event = DiagnosticEvent(
            timestamp = 1L,
            sessionId = "session-1",
            module = "diagnostics",
            action = "capture",
            resultCode = "SUCCESS",
            durationMs = 0L,
            safeMetadata = mapOf(
                "raw_screenshot" to "captured-screen-reference",
                "raw_image" to "captured-image-reference",
                "safe_label" to "kept",
            ),
        )

        assertFalse(event.safeMetadata.containsKey("raw_screenshot"))
        assertFalse(event.safeMetadata.containsKey("raw_image"))
        assertEquals("kept", event.safeMetadata["safe_label"])
    }

    @Test
    fun `benign values under formerly broad exact keys remain safe metadata`() {
        val event = DiagnosticEvent(
            timestamp = 1L,
            sessionId = "session-1",
            module = "diagnostics",
            action = "boundary",
            resultCode = "SUCCESS",
            durationMs = 0L,
            safeMetadata = mapOf(
                "secret" to "public-feature-name",
                "token" to "item-count-label",
                "authorization" to "user-interface-mode",
                "bitmap" to "bitmap-format-name",
                "base64" to "encoding-name",
            ),
        )

        assertEquals(
            mapOf(
                "secret" to "public-feature-name",
                "token" to "item-count-label",
                "authorization" to "user-interface-mode",
                "bitmap" to "bitmap-format-name",
                "base64" to "encoding-name",
            ),
            event.safeMetadata,
        )
    }

    @Test
    fun `generic metadata keeps ordinary values and rejects only valid payment card numbers`() {
        val event = DiagnosticEvent(
            timestamp = 1L,
            sessionId = "session-1",
            module = "diagnostics",
            action = "measure",
            resultCode = "SUCCESS",
            durationMs = 0L,
            safeMetadata = mapOf(
                "large_counter" to "1234567890123",
                "valid_card" to "4111 1111 1111 1111",
                "six_digit_counter" to "654321",
            ),
        )

        assertEquals("1234567890123", event.safeMetadata["large_counter"])
        assertEquals("654321", event.safeMetadata["six_digit_counter"])
        assertFalse(event.safeMetadata.containsKey("valid_card"))
    }

    @Test
    fun `generic serialized JSON values remove sensitive labeled assignments`() {
        val event = DiagnosticEvent(
            timestamp = 1L,
            sessionId = "session-1",
            module = "diagnostics",
            action = "serialize",
            resultCode = "SUCCESS",
            durationMs = 0L,
            safeMetadata = mapOf(
                "details" to "{\"password\":\"hunter2\"}",
                "notes" to "{\"api_key\":\"abc\"}",
                "safe" to "{\"token_count\":42}",
            ),
        )

        assertFalse(event.safeMetadata.containsKey("details"))
        assertFalse(event.safeMetadata.containsKey("notes"))
        assertEquals("{\"token_count\":42}", event.safeMetadata["safe"])
    }

    @Test
    fun `generic metadata preserves benign image MIME markers`() {
        val event = DiagnosticEvent(
            timestamp = 1L,
            sessionId = "session-1",
            module = "diagnostics",
            action = "mime",
            resultCode = "SUCCESS",
            durationMs = 0L,
            safeMetadata = mapOf(
                "png_type" to "image/png",
                "jpeg_type" to "image/jpeg",
                "webp_type" to "image/webp",
            ),
        )

        assertEquals("image/png", event.safeMetadata["png_type"])
        assertEquals("image/jpeg", event.safeMetadata["jpeg_type"])
        assertEquals("image/webp", event.safeMetadata["webp_type"])
    }
}
