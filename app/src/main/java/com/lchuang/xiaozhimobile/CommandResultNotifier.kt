package com.lchuang.xiaozhimobile

class CommandResultNotifier(
    private val publish: (String) -> Unit,
    private val clockMs: () -> Long,
    private val holdMs: Long = 4000L
) {
    private var retainedText: String? = null
    private var retainedUntilMs: Long? = null

    fun running(text: String) {
        publish(text)
    }

    fun success(text: String) {
        publishAndRetain(text)
    }

    fun failure(text: String) {
        publishAndRetain(text)
    }

    fun publishTransient(text: String) {
        publish(retainedText() ?: text)
    }

    fun clearRetention() {
        retainedText = null
        retainedUntilMs = null
    }

    fun retainedText(nowMs: Long = clockMs()): String? {
        val until = retainedUntilMs
        if (retainedText == null || until == null || nowMs >= until) {
            clearRetention()
            return null
        }
        return retainedText
    }

    private fun publishAndRetain(text: String) {
        publish(text)
        retainedText = text
        retainedUntilMs = clockMs() + holdMs
    }
}
