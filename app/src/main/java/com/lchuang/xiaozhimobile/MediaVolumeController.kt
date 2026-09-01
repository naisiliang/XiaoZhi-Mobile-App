package com.lchuang.xiaozhimobile

import android.media.AudioManager
import android.os.Build
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
        val minStep = readMinStep(maxStep)
        val currentStep = readCurrentStep(maxStep, minStep)
        return MediaVolumeSnapshot(
            requestedPercent = null,
            beforeStep = currentStep,
            targetStep = currentStep,
            afterStep = currentStep,
            maxStep = maxStep,
            actualPercent = toPercent(currentStep, minStep, maxStep),
            isVolumeFixed = audioManager.isVolumeFixed,
            retryCount = 0,
            resultCode = RESULT_SNAPSHOT
        )
    }

    open fun setPercent(percent: Int): MediaVolumeSnapshot {
        val maxStep = readMaxStep()
        val minStep = readMinStep(maxStep)
        val isVolumeFixed = audioManager.isVolumeFixed
        val beforeStep = readCurrentStep(maxStep, minStep)
        val requestedPercent = percent.coerceIn(0, 100)
        val targetStep = (
            minStep + round(requestedPercent * (maxStep - minStep) / 100.0).toInt()
            ).coerceIn(minStep, maxStep)
        if (isVolumeFixed) {
            return MediaVolumeSnapshot(
                requestedPercent = requestedPercent,
                beforeStep = beforeStep,
                targetStep = targetStep,
                afterStep = beforeStep,
                maxStep = maxStep,
                actualPercent = toPercent(beforeStep, minStep, maxStep),
                isVolumeFixed = true,
                retryCount = 0,
                resultCode = RESULT_SET_MISMATCH
            )
        }
        return try {
            var retryCount = 0
            var afterStep = writeAndReadBack(targetStep, minStep, maxStep)
            if (shouldRetrySet(beforeStep, targetStep, afterStep)) {
                retryCount = 1
                val retryStep = fallbackStep(beforeStep, targetStep, afterStep, minStep, maxStep)
                afterStep = writeAndReadBack(retryStep, minStep, maxStep)
            }
            val actualPercent = toPercent(afterStep, minStep, maxStep)
            MediaVolumeSnapshot(
                requestedPercent = requestedPercent,
                beforeStep = beforeStep,
                targetStep = targetStep,
                afterStep = afterStep,
                maxStep = maxStep,
                actualPercent = actualPercent,
                isVolumeFixed = isVolumeFixed,
                retryCount = retryCount,
                resultCode = classifySetResult(targetStep, afterStep)
            )
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            MediaVolumeSnapshot(
                requestedPercent = requestedPercent,
                beforeStep = beforeStep,
                targetStep = targetStep,
                afterStep = beforeStep,
                maxStep = maxStep,
                actualPercent = toPercent(beforeStep, minStep, maxStep),
                isVolumeFixed = isVolumeFixed,
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
                actualPercent = toPercent(beforeStep, minStep, maxStep),
                isVolumeFixed = isVolumeFixed,
                retryCount = 0,
                resultCode = RESULT_SET_ERROR
            )
        }
    }

    open fun adjust(direction: Int): MediaVolumeSnapshot {
        val maxStep = readMaxStep()
        val minStep = readMinStep(maxStep)
        val beforeStep = readCurrentStep(maxStep, minStep)
        val targetStep = when (direction) {
            AudioManager.ADJUST_RAISE -> (beforeStep + 1).coerceAtMost(maxStep)
            AudioManager.ADJUST_LOWER -> (beforeStep - 1).coerceAtLeast(minStep)
            else -> beforeStep
        }
        return try {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                direction,
                AudioManager.FLAG_SHOW_UI
            )
            Thread.sleep(120L)
            val afterStep = readCurrentStep(maxStep)
            MediaVolumeSnapshot(
                requestedPercent = null,
                beforeStep = beforeStep,
                targetStep = targetStep,
                afterStep = afterStep,
                maxStep = maxStep,
                actualPercent = toPercent(afterStep, minStep, maxStep),
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
                actualPercent = toPercent(beforeStep, minStep, maxStep),
                isVolumeFixed = audioManager.isVolumeFixed,
                retryCount = 0,
                resultCode = RESULT_ADJUST_ERROR
            )
        }
    }

    private fun readMaxStep(): Int =
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    private fun readMinStep(maxStep: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC).coerceIn(0, maxStep)
        } else {
            // getStreamMinVolume was added in API 28; API 26-27 media streams use zero as the safe fallback.
            0
        }

    private fun readCurrentStep(maxStep: Int, minStep: Int = 0): Int =
        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(minStep, maxStep)

    private fun writeAndReadBack(targetStep: Int, minStep: Int, maxStep: Int): Int {
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            targetStep,
            AudioManager.FLAG_SHOW_UI
        )
        Thread.sleep(120L)
        return readCurrentStep(maxStep, minStep)
    }

    private fun toPercent(step: Int, minStep: Int, maxStep: Int): Int =
        if (maxStep == minStep) {
            0
        } else {
            round((step - minStep) * 100.0 / (maxStep - minStep)).toInt().coerceIn(0, 100)
        }

    private fun shouldRetrySet(beforeStep: Int, targetStep: Int, afterStep: Int): Boolean =
        !audioManager.isVolumeFixed && afterStep != targetStep

    private fun fallbackStep(
        beforeStep: Int,
        targetStep: Int,
        afterStep: Int,
        minStep: Int,
        maxStep: Int,
    ): Int =
        when {
            afterStep == beforeStep -> targetStep
            targetStep > afterStep -> (afterStep + 1).coerceAtMost(maxStep)
            targetStep < afterStep -> (afterStep - 1).coerceAtLeast(minStep)
            else -> targetStep
        }

    private fun classifySetResult(targetStep: Int, afterStep: Int): String =
        if (afterStep == targetStep) RESULT_SET_OK else RESULT_SET_MISMATCH

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
