package com.lchuang.xiaozhimobile

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
    fun execute(
        transaction: CommandTransaction,
        continuation: String,
        onFinished: (CommandTransaction) -> Unit
    ) {
        notifier.running(formatter.runningNotification(transaction.action))
        speech.speak(transaction.announcement, onStart = {
            scheduler.postDelayed(actionDelayMs) {
                runner.run(transaction.action) { result ->
                    val copy = formatter.finalCopy(transaction.action, result, continuation)
                    copy.successNotification?.let(notifier::success)
                    copy.failureNotification?.let(notifier::failure)
                    speech.speak(requireNotNull(copy.finalSpoken), onStart = {}, onDone = {
                        onFinished(transaction.copy(result = result))
                    })
                }
            }
        }, onDone = {})
    }
}
