package com.lchuang.xiaozhimobile

class SessionController(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    @Volatile private var active = false
    @Volatile private var deadlineMs = 0L

    @Synchronized
    fun start(timeoutSeconds: Int) {
        active = true
        deadlineMs = nowMs() + sanitizeTimeout(timeoutSeconds) * 1000L
    }

    @Synchronized
    fun touch(timeoutSeconds: Int) {
        if (!active) return
        deadlineMs = nowMs() + sanitizeTimeout(timeoutSeconds) * 1000L
    }

    @Synchronized
    fun stop() {
        active = false
        deadlineMs = 0L
    }

    fun isActive(): Boolean = active

    fun remainingMs(): Long {
        if (!active) return 0L
        return (deadlineMs - nowMs()).coerceAtLeast(0L)
    }

    fun isExpired(): Boolean = active && remainingMs() <= 0L

    private fun sanitizeTimeout(value: Int): Int = value.coerceIn(5, 300)
}
