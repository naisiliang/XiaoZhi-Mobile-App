package com.lchuang.xiaozhimobile

data class VadFrameDecision(
    val speechStarted: Boolean,
    val speechEnded: Boolean,
    val noiseFloor: Float,
    val startThreshold: Float,
    val endThreshold: Float
)

class AdaptiveVoiceActivityDetector(
    private val frameMs: Int = 50,
    private val stableSpeechFrames: Int = 2,
    private val endSilenceMs: Int = 650,
    initialNoiseFloor: Float = 0.0045f
) {
    private val initialFloor = sanitize(initialNoiseFloor)
    private var noiseFloor = initialFloor
    private var consecutiveSpeechFrames = 0
    private var silenceMs = 0
    private var speechActive = false
    private var speechEnded = false

    fun reset() {
        noiseFloor = initialFloor
        consecutiveSpeechFrames = 0
        silenceMs = 0
        speechActive = false
        speechEnded = false
    }

    fun accept(rms: Float): VadFrameDecision {
        val cleanRms = sanitize(rms)
        val startThreshold = startThreshold(noiseFloor)
        val endThreshold = endThreshold(noiseFloor, startThreshold)
        var startedThisFrame = false
        var endedThisFrame = false

        if (!speechActive) {
            if (cleanRms >= startThreshold) {
                consecutiveSpeechFrames += 1
                if (consecutiveSpeechFrames >= stableSpeechFrames) {
                    speechActive = true
                    silenceMs = 0
                    startedThisFrame = true
                }
            } else {
                consecutiveSpeechFrames = 0
                noiseFloor += (cleanRms - noiseFloor) * QUIET_EMA_ALPHA
            }
        } else if (!speechEnded) {
            if (cleanRms < endThreshold) {
                silenceMs += frameMs
                if (silenceMs >= endSilenceMs) {
                    speechEnded = true
                    endedThisFrame = true
                }
            } else {
                silenceMs = 0
            }
        }

        val currentStartThreshold = startThreshold(noiseFloor)
        return VadFrameDecision(
            speechStarted = startedThisFrame,
            speechEnded = endedThisFrame,
            noiseFloor = noiseFloor,
            startThreshold = currentStartThreshold,
            endThreshold = endThreshold(noiseFloor, currentStartThreshold)
        )
    }

    private fun startThreshold(floor: Float): Float =
        maxOf(0.0085f, floor * 2.2f).coerceIn(0.0085f, 0.0300f)

    private fun endThreshold(floor: Float, startThreshold: Float): Float =
        maxOf(0.0060f, floor * 1.5f).coerceIn(0.0060f, startThreshold * 0.82f)

    private fun sanitize(value: Float): Float =
        if (value.isFinite() && value >= 0f) value else 0f

    private companion object {
        const val QUIET_EMA_ALPHA = 0.08f
    }
}
