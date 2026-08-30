package com.lchuang.xiaozhimobile

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class TtsProgressRegistry(
    private val dispatch: (() -> Unit) -> Unit,
    private val dispatchDelayed: (Long, () -> Unit) -> Unit,
    private val errorFallbackMs: Long = 150L
) {
    private class PendingUtterance(
        val id: String,
        val onStart: () -> Unit,
        val onDone: () -> Unit
    ) {
        val started = AtomicBoolean(false)
        val completionScheduled = AtomicBoolean(false)
        val doneDelivered = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
    }

    private val pending = ConcurrentHashMap<String, PendingUtterance>()

    fun register(
        utteranceId: String,
        onStart: () -> Unit,
        onDone: () -> Unit,
        flushPending: Boolean = true
    ) {
        if (flushPending) cancelPending(deliverCallbacks = false)
        val state = PendingUtterance(utteranceId, onStart, onDone)
        check(pending.putIfAbsent(utteranceId, state) == null) {
            "Duplicate TTS utterance ID: $utteranceId"
        }
    }

    fun onStart(utteranceId: String) {
        pending[utteranceId]?.let(::dispatchStart)
    }

    fun onDone(utteranceId: String) {
        val state = pending[utteranceId] ?: return
        if (state.completionScheduled.compareAndSet(false, true)) {
            dispatchDone(state, deliverCallbacks = true)
        }
    }

    fun onError(utteranceId: String) {
        val state = pending[utteranceId] ?: return
        dispatchStart(state)
        if (state.completionScheduled.compareAndSet(false, true)) {
            dispatchDelayed(errorFallbackMs) { dispatchDone(state, deliverCallbacks = true) }
        }
    }

    fun cancelPending(deliverCallbacks: Boolean = false) {
        pending.values.toList().forEach { dispatchDone(it, deliverCallbacks) }
    }

    private fun dispatchStart(state: PendingUtterance) {
        if (state.cancelled.get()) return
        if (state.started.compareAndSet(false, true)) {
            dispatch {
                if (!state.cancelled.get()) state.onStart()
            }
        }
    }

    private fun dispatchDone(state: PendingUtterance, deliverCallbacks: Boolean) {
        if (!deliverCallbacks) state.cancelled.set(true)
        if (!state.doneDelivered.compareAndSet(false, true)) return
        pending.remove(state.id, state)
        if (deliverCallbacks) dispatch(state.onDone)
    }
}
