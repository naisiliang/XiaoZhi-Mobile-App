package com.lchuang.xiaozhimobile.conversation

import java.util.concurrent.CopyOnWriteArrayList

class AssistantStateStore(
    private val onStateChanged: (AssistantState) -> Unit = {},
) {
    private val observers = CopyOnWriteArrayList<(AssistantState) -> Unit>()
    private val stateLock = Any()

    @Volatile
    var current: AssistantState = AssistantState.WAITING_WAKE
        private set

    fun onWakeDetected() = transitionTo(AssistantState.WAITING_WAKE)

    fun onAudioCaptureStarted() = transitionTo(AssistantState.LISTENING)

    fun onAudioCaptureStopped() = transitionTo(AssistantState.RECOGNIZING)

    fun onExecutionStarted() = transitionTo(AssistantState.EXECUTING)

    fun onTtsStarted() = transitionTo(AssistantState.SPEAKING)

    fun onConfirmationRequired() = transitionTo(AssistantState.WAITING_CONFIRMATION)

    fun onConversationEnded() {
        if (current == AssistantState.WAITING_WAKE) {
            publish(current)
        } else {
            transitionTo(AssistantState.WAITING_WAKE)
        }
    }

    fun addObserver(observer: (AssistantState) -> Unit) {
        observers += observer
    }

    fun removeObserver(observer: (AssistantState) -> Unit) {
        observers -= observer
    }

    private fun transitionTo(next: AssistantState) {
        val changed = synchronized(stateLock) {
            if (current == next) false else {
                current = next
                true
            }
        }
        if (changed) publish(next)
    }

    private fun publish(state: AssistantState) {
        onStateChanged(state)
        observers.forEach { it(state) }
    }
}
