package com.lchuang.xiaozhimobile

class CommandResultNotifier(
    private val publish: (String) -> Unit,
    private val clockMs: () -> Long,
    private val holdMs: Long = 4000L
) {
    private var retainedText: String? = null
    private var retainedUntilMs: Long? = null
    private var lastFailureText: String? = null
    private var lastFailureAtMs: Long? = null

    @Synchronized
    fun running(text: String) {
        publish(text)
    }

    @Synchronized
    fun success(text: String) {
        clearFailureDedup()
        publishAndRetain(text, clockMs())
    }

    @Synchronized
    fun failure(text: String) {
        val nowMs = clockMs()
        val previousAtMs = lastFailureAtMs
        if (lastFailureText == text && previousAtMs != null &&
            nowMs >= previousAtMs && nowMs - previousAtMs < holdMs
        ) {
            return
        }
        lastFailureText = text
        lastFailureAtMs = nowMs
        publishAndRetain(text, nowMs)
    }

    @Synchronized
    fun publishTransient(text: String) {
        publish(retainedText() ?: text)
    }

    @Synchronized
    fun clearRetention() {
        retainedText = null
        retainedUntilMs = null
        clearFailureDedup()
    }

    @Synchronized
    fun retainedText(nowMs: Long = clockMs()): String? {
        val until = retainedUntilMs
        if (retainedText == null || until == null || nowMs >= until) {
            clearRetention()
            return null
        }
        return retainedText
    }

    private fun publishAndRetain(text: String, nowMs: Long) {
        publish(text)
        retainedText = text
        retainedUntilMs = nowMs + holdMs
    }

    private fun clearFailureDedup() {
        lastFailureText = null
        lastFailureAtMs = null
    }
}
