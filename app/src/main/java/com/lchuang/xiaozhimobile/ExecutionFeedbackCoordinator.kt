package com.lchuang.xiaozhimobile

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

fun interface DelayedScheduler {
    fun postDelayed(delayMs: Long, block: () -> Unit)
}

fun interface DeviceActionRunner {
    fun run(action: DeviceAction, callback: (DeviceExecutionResult) -> Unit)
}

fun interface SpeechDriver {
    fun speak(text: String, onStart: () -> Unit, onDone: () -> Unit)
}

class ExecutionFeedbackCoordinator(
    private val scheduler: DelayedScheduler,
    private val runner: DeviceActionRunner,
    private val speech: SpeechDriver,
    private val formatter: ExecutionIntentFormatter,
    private val notifier: CommandResultNotifier,
    private val actionDelayMs: Long = 120L
) {
    private class PendingExecution {
        val cancelled = AtomicBoolean(false)
        val resultDelivered = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
    }

    private val pending = AtomicReference<PendingExecution?>(null)

    fun cancelPending() {
        pending.getAndSet(null)?.cancelled?.set(true)
    }

    fun execute(
        transaction: CommandTransaction,
        continuation: String,
        onFinished: (CommandTransaction) -> Unit
    ) {
        execute(transaction, continuation, isValid = { true }, onFinished = onFinished)
    }

    fun execute(
        transaction: CommandTransaction,
        continuation: String,
        isValid: () -> Boolean,
        onFinished: (CommandTransaction) -> Unit
    ) {
        val execution = PendingExecution()
        pending.getAndSet(execution)?.cancelled?.set(true)

        fun isCurrent(): Boolean =
            pending.get() === execution && !execution.cancelled.get() && isValid()

        notifier.running(formatter.runningNotification(transaction.action))
        speech.speak(transaction.announcement, onStart = {
            if (!isCurrent()) return@speak
            scheduler.postDelayed(actionDelayMs) {
                if (!isCurrent()) return@postDelayed
                fun deliverResult(result: DeviceExecutionResult) {
                    if (!isCurrent()) return
                    if (!execution.resultDelivered.compareAndSet(false, true)) return
                    val copy = formatter.finalCopy(transaction.action, result, continuation)
                    if (!isCurrent()) return
                    copy.successNotification?.let(notifier::success)
                    copy.failureNotification?.let(notifier::failure)
                    if (!isCurrent()) return
                    speech.speak(requireNotNull(copy.finalSpoken), onStart = {}, onDone = {
                        if (!isCurrent()) return@speak
                        if (!execution.finished.compareAndSet(false, true)) return@speak
                        if (!isCurrent()) return@speak
                        pending.compareAndSet(execution, null)
                        if (!isValid()) return@speak
                        onFinished(transaction.copy(result = result))
                    })
                }

                try {
                    runner.run(transaction.action) { result ->
                        deliverResult(result)
                    }
                } catch (_: Throwable) {
                    try {
                        deliverResult(
                            DeviceExecutionResult(
                                false,
                                "EXECUTION_FAILED",
                                "设备操作执行失败",
                                "设备操作执行失败",
                                CommandFailureKind.EXECUTION_FAILED
                            )
                        )
                    } catch (_: Throwable) {
                        // A failing feedback callback must not trigger a second result.
                    }
                }
            }
        }, onDone = {})
    }
}
