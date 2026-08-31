package com.lchuang.xiaozhimobile

enum class CommandAudioReadDecision {
    CONTINUE,
    PROCESS,
    STOP,
    AUDIO_START_FAILURE
}

class CommandAudioReadWatchdog(
    private val maxNegativeReads: Int,
    private val maxNegativeDurationMs: Long,
    private val maxZeroReadDurationMs: Long
) {
    private var negativeReadCount = 0
    private var firstNegativeReadAtMs = 0L
    private var lastReadProgressAtMs = 0L

    fun reset(nowMs: Long) {
        negativeReadCount = 0
        firstNegativeReadAtMs = 0L
        lastReadProgressAtMs = nowMs
    }

    fun onRead(samplesRead: Int, nowMs: Long): CommandAudioReadDecision {
        if (samplesRead < 0) {
            if (negativeReadCount == 0) firstNegativeReadAtMs = nowMs
            negativeReadCount += 1
            return if (
                negativeReadCount >= maxNegativeReads ||
                nowMs - firstNegativeReadAtMs >= maxNegativeDurationMs
            ) {
                CommandAudioReadDecision.AUDIO_START_FAILURE
            } else {
                CommandAudioReadDecision.CONTINUE
            }
        }

        if (samplesRead == 0) {
            return if (nowMs - lastReadProgressAtMs >= maxZeroReadDurationMs) {
                CommandAudioReadDecision.STOP
            } else {
                CommandAudioReadDecision.CONTINUE
            }
        }

        negativeReadCount = 0
        lastReadProgressAtMs = nowMs
        return CommandAudioReadDecision.PROCESS
    }
}
