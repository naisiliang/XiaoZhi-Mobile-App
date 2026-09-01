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

open class MediaVolumeController(
    private val audioManager: AudioManager
) {
    open fun snapshot(): MediaVolumeSnapshot {
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

    open fun setPercent(percent: Int): MediaVolumeSnapshot {
        val maxStep = readMaxStep()
        val beforeStep = readCurrentStep(maxStep)
        val requestedPercent = percent.coerceIn(0, 100)
        val targetStep = round(requestedPercent * maxStep / 100.0).toInt().coerceIn(0, maxStep)
        return try {
            var retryCount = 0
            var afterStep = writeAndReadBack(targetStep, maxStep)
            if (shouldRetrySet(beforeStep, targetStep, afterStep)) {
                retryCount = 1
                val retryStep = fallbackStep(beforeStep, targetStep, afterStep, maxStep)
                afterStep = writeAndReadBack(retryStep, maxStep)
            }
            val actualPercent = toPercent(afterStep, maxStep)
            MediaVolumeSnapshot(
                requestedPercent = requestedPercent,
                beforeStep = beforeStep,
                targetStep = targetStep,
                afterStep = afterStep,
                maxStep = maxStep,
                actualPercent = actualPercent,
                isVolumeFixed = audioManager.isVolumeFixed,
                retryCount = retryCount,
                resultCode = classifySetResult(requestedPercent, targetStep, afterStep, maxStep)
            )
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
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

    open fun adjust(direction: Int): MediaVolumeSnapshot {
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
                resultCode = classifyAdjustResult(direction, beforeStep, afterStep)
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

    private fun writeAndReadBack(targetStep: Int, maxStep: Int): Int {
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            targetStep,
            AudioManager.FLAG_SHOW_UI
        )
        Thread.sleep(120L)
        return readCurrentStep(maxStep)
    }

    private fun toPercent(step: Int, maxStep: Int): Int =
        round(step * 100.0 / maxStep).toInt().coerceIn(0, 100)

    private fun shouldRetrySet(beforeStep: Int, targetStep: Int, afterStep: Int): Boolean =
        !audioManager.isVolumeFixed && afterStep != targetStep

    private fun fallbackStep(beforeStep: Int, targetStep: Int, afterStep: Int, maxStep: Int): Int =
        when {
            afterStep == beforeStep -> targetStep
            targetStep > afterStep -> (afterStep + 1).coerceAtMost(maxStep)
            targetStep < afterStep -> (afterStep - 1).coerceAtLeast(0)
            else -> targetStep
        }

    private fun classifySetResult(requestedPercent: Int, targetStep: Int, afterStep: Int, maxStep: Int): String {
        if (afterStep == targetStep) {
            return RESULT_SET_OK
        }
        val actualPercent = toPercent(afterStep, maxStep)
        val tolerance = ceil(100.0 / maxStep).toInt().coerceAtLeast(1)
        return if (abs(actualPercent - requestedPercent) <= tolerance) RESULT_SET_OK else RESULT_SET_MISMATCH
    }

    private fun classifyAdjustResult(direction: Int, beforeStep: Int, afterStep: Int): String =
        when (direction) {
            AudioManager.ADJUST_RAISE ->
                if (afterStep > beforeStep) RESULT_ADJUST_OK else RESULT_ADJUST_NO_CHANGE
            AudioManager.ADJUST_LOWER ->
                if (afterStep < beforeStep) RESULT_ADJUST_OK else RESULT_ADJUST_NO_CHANGE
            else -> RESULT_ADJUST_NO_CHANGE
        }

    companion object {
        const val RESULT_SNAPSHOT = "SNAPSHOT"
        const val RESULT_SET_OK = "SUCCESS"
        const val RESULT_SET_MISMATCH = "SYSTEM_LIMITED"
        const val RESULT_SET_ERROR = "EXECUTION_FAILED"
        const val RESULT_ADJUST_OK = "ADJUST_OK"
        const val RESULT_ADJUST_NO_CHANGE = "ADJUST_NO_CHANGE"
        const val RESULT_ADJUST_ERROR = "ADJUST_ERROR"
    }
}
