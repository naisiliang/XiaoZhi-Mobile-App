package com.lchuang.xiaozhimobile.diagnostics

import com.lchuang.xiaozhimobile.MediaVolumeSnapshot

/**
 * Bounded, in-memory diagnostic sink for operational metadata.
 *
 * Callers provide only structured metadata; [DiagnosticEvent] applies the
 * privacy boundary before an event is retained. Notification errors have a
 * separate short deduplication window so a retry loop cannot flood the
 * diagnostic stream with the same failure.
 */
class DiagnosticRecorder(
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val maxEvents: Int = DEFAULT_MAX_EVENTS,
    private val notificationDedupWindowMs: Long = DEFAULT_NOTIFICATION_DEDUP_WINDOW_MS,
) {
    init {
        require(maxEvents >= 1) { "maxEvents must be positive" }
        require(notificationDedupWindowMs >= 0L) { "notificationDedupWindowMs must not be negative" }
    }

    private data class NotificationFingerprint(
        val sessionId: String,
        val module: String,
        val action: String,
        val resultCode: String,
        val safeMetadata: Map<String, String>,
    )

    private val events = ArrayDeque<DiagnosticEvent>()
    private val lastNotificationAtMs = linkedMapOf<NotificationFingerprint, Long>()

    @Synchronized
    fun record(
        sessionId: String,
        module: String,
        action: String,
        resultCode: String,
        durationMs: Long = 0L,
        safeMetadata: Map<String, String> = emptyMap(),
    ): DiagnosticEvent {
        val event = DiagnosticEvent(
            timestamp = clockMs(),
            sessionId = sessionId,
            module = module,
            action = action,
            resultCode = resultCode,
            durationMs = durationMs.coerceAtLeast(0L),
            safeMetadata = safeMetadata,
        )
        append(event)
        return event
    }

    /**
     * Records a notification-worthy error once per fingerprint in the
     * configured window. The notification text itself is deliberately not an
     * input, preventing user-facing strings from becoming diagnostic payload.
     */
    @Synchronized
    fun recordNotificationError(
        sessionId: String,
        module: String,
        action: String,
        resultCode: String,
        durationMs: Long = 0L,
        safeMetadata: Map<String, String> = emptyMap(),
    ): DiagnosticEvent? {
        val event = DiagnosticEvent(
            timestamp = clockMs(),
            sessionId = sessionId,
            module = module,
            action = action,
            resultCode = resultCode,
            durationMs = durationMs.coerceAtLeast(0L),
            safeMetadata = safeMetadata,
        )
        val fingerprint = NotificationFingerprint(
            sessionId = event.sessionId,
            module = event.module,
            action = event.action,
            resultCode = event.resultCode,
            safeMetadata = event.safeMetadata,
        )
        val previousAtMs = lastNotificationAtMs[fingerprint]
        val elapsedMs = previousAtMs?.let { event.timestamp - it }
        if (elapsedMs != null && elapsedMs >= 0L && elapsedMs < notificationDedupWindowMs) {
            return null
        }
        rememberNotification(fingerprint, event.timestamp)
        append(event)
        return event
    }

    /** Records the complete system-volume step trace without reducing it to a percentage. */
    @Synchronized
    fun recordMediaVolume(
        sessionId: String,
        action: String,
        snapshot: MediaVolumeSnapshot,
        durationMs: Long = 0L,
    ): DiagnosticEvent = record(
        sessionId = sessionId,
        module = "media",
        action = action,
        resultCode = snapshot.resultCode,
        durationMs = durationMs,
        safeMetadata = buildMap {
            snapshot.requestedPercent?.let { put("requestedPercent", it.toString()) }
            put("beforeStep", snapshot.beforeStep.toString())
            put("targetStep", snapshot.targetStep.toString())
            put("afterStep", snapshot.afterStep.toString())
            put("maxStep", snapshot.maxStep.toString())
            put("actualPercent", snapshot.actualPercent.toString())
            put("isVolumeFixed", snapshot.isVolumeFixed.toString())
            put("retryCount", snapshot.retryCount.toString())
        },
    )

    @Synchronized
    fun snapshot(): List<DiagnosticEvent> = events.toList()

    @Synchronized
    fun clear() {
        events.clear()
        lastNotificationAtMs.clear()
    }

    private fun append(event: DiagnosticEvent) {
        if (events.size >= maxEvents) events.removeFirst()
        events.addLast(event)
    }

    private fun rememberNotification(fingerprint: NotificationFingerprint, timestamp: Long) {
        if (!lastNotificationAtMs.containsKey(fingerprint) && lastNotificationAtMs.size >= maxEvents) {
            lastNotificationAtMs.remove(lastNotificationAtMs.keys.first())
        }
        lastNotificationAtMs[fingerprint] = timestamp
    }

    companion object {
        const val DEFAULT_MAX_EVENTS = 256
        const val DEFAULT_NOTIFICATION_DEDUP_WINDOW_MS = 4_000L
    }
}
