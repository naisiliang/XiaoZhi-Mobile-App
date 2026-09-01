package com.lchuang.xiaozhimobile

import android.media.AudioManager
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.round

data class MediaVolumeSnapshot(
    val requestedPercent: Int?,
    val beforeStep: Int,
    val targetStep: Int,
    val afterStep: Int,
    val maxStep: Int,
    val actualPercent: Int,
    val isVolumeFixed: Boolean,
    val retryCount: Int,
    val resultCode: String
)

class MediaVolumeController(
    private val audioManager: AudioManager
) {
    fun snapshot(): MediaVolumeSnapshot {
        val maxStep = readMaxStep()
        val currentStep = readCurrentStep(maxStep)
        return MediaVolumeSnapshot(
            requestedPercent = null,
            beforeStep = currentStep,
            targetStep = currentStep,
            afterStep = currentStep,
            maxStep = maxStep,
            actualPercent = toPercent(currentStep, maxStep),
            isVolumeFixed = audioManager.isVolumeFixed,
            retryCount = 0,
            resultCode = RESULT_SNAPSHOT
        )
    }

    fun setPercent(percent: Int): MediaVolumeSnapshot {
        val maxStep = readMaxStep()
        val beforeStep = readCurrentStep(maxStep)
        val requestedPercent = percent.coerceIn(0, 100)
        val targetStep = round(requestedPercent * maxStep / 100.0).toInt().coerceIn(0, maxStep)
        return try {
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                targetStep,
                AudioManager.FLAG_SHOW_UI
            )
            val afterStep = readCurrentStep(maxStep)
            val actualPercent = toPercent(afterStep, maxStep)
            val tolerance = ceil(100.0 / maxStep).toInt().coerceAtLeast(1)
            MediaVolumeSnapshot(
                requestedPercent = requestedPercent,
                beforeStep = beforeStep,
                targetStep = targetStep,
                afterStep = afterStep,
                maxStep = maxStep,
                actualPercent = actualPercent,
                isVolumeFixed = audioManager.isVolumeFixed,
                retryCount = 0,
                resultCode = if (abs(actualPercent - requestedPercent) <= tolerance) RESULT_SET_OK else RESULT_SET_MISMATCH
            )
        } catch (_: Throwable) {
            MediaVolumeSnapshot(
                requestedPercent = requestedPercent,
                beforeStep = beforeStep,
                targetStep = targetStep,
                afterStep = beforeStep,
                maxStep = maxStep,
                actualPercent = toPercent(beforeStep, maxStep),
                isVolumeFixed = audioManager.isVolumeFixed,
                retryCount = 0,
                resultCode = RESULT_SET_ERROR
            )
        }
    }

    fun adjust(direction: Int): MediaVolumeSnapshot {
        val maxStep = readMaxStep()
        val beforeStep = readCurrentStep(maxStep)
        val targetStep = when (direction) {
            AudioManager.ADJUST_RAISE -> (beforeStep + 1).coerceAtMost(maxStep)
            AudioManager.ADJUST_LOWER -> (beforeStep - 1).coerceAtLeast(0)
            else -> beforeStep
        }
        return try {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                direction,
                AudioManager.FLAG_SHOW_UI
            )
            val afterStep = readCurrentStep(maxStep)
            MediaVolumeSnapshot(
                requestedPercent = null,
                beforeStep = beforeStep,
                targetStep = targetStep,
                afterStep = afterStep,
                maxStep = maxStep,
                actualPercent = toPercent(afterStep, maxStep),
                isVolumeFixed = audioManager.isVolumeFixed,
                retryCount = 0,
                resultCode = RESULT_ADJUST_OK
            )
        } catch (_: Throwable) {
            MediaVolumeSnapshot(
                requestedPercent = null,
                beforeStep = beforeStep,
                targetStep = targetStep,
                afterStep = beforeStep,
                maxStep = maxStep,
                actualPercent = toPercent(beforeStep, maxStep),
                isVolumeFixed = audioManager.isVolumeFixed,
                retryCount = 0,
                resultCode = RESULT_ADJUST_ERROR
            )
        }
    }

    private fun readMaxStep(): Int =
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    private fun readCurrentStep(maxStep: Int): Int =
        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, maxStep)

    private fun toPercent(step: Int, maxStep: Int): Int =
        round(step * 100.0 / maxStep).toInt().coerceIn(0, 100)

    companion object {
        const val RESULT_SNAPSHOT = "SNAPSHOT"
        const val RESULT_SET_OK = "SET_OK"
        const val RESULT_SET_MISMATCH = "SET_MISMATCH"
        const val RESULT_SET_ERROR = "SET_ERROR"
        const val RESULT_ADJUST_OK = "ADJUST_OK"
        const val RESULT_ADJUST_ERROR = "ADJUST_ERROR"
    }
}
