package com.lchuang.xiaozhimobile

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class TtsProgressRegistry(
    private val dispatch: (() -> Unit) -> Unit,
    private val dispatchDelayed: (Long, () -> Unit) -> Unit,
    private val errorFallbackMs: Long = 150L,
    private val watchdogMs: Long = 250L,
    private val onWatchdogTimeout: (String) -> Unit = {}
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
        val watchdogScheduled = AtomicBoolean(false)
        val timedOut = AtomicBoolean(false)
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
        pending[utteranceId]?.let(::scheduleDone)
    }

    fun scheduleWatchdog(utteranceId: String) {
        val state = pending[utteranceId] ?: return
        if (!state.watchdogScheduled.compareAndSet(false, true)) return
        dispatchDelayed(watchdogMs) {
            val timedOut = synchronized(state) {
                if (pending[utteranceId] !== state || state.cancelled.get() || state.doneDelivered.get()) {
                    false
                } else if (state.started.get()) {
                    false
                } else {
                    state.timedOut.compareAndSet(false, true)
                }
            }
            if (!timedOut) return@dispatchDelayed
            try {
                onWatchdogTimeout(utteranceId)
            } catch (_: Throwable) {
                // The fallback must remain live even if stopping the engine fails.
            }
            dispatchStart(state, synthetic = true)
            val shouldScheduleDone = synchronized(state) {
                state.completionScheduled.compareAndSet(false, true)
            }
            if (shouldScheduleDone) dispatchDelayed(errorFallbackMs) { dispatchDone(state) }
        }
    }

    fun onError(utteranceId: String) {
        val state = pending[utteranceId] ?: return
        synchronized(state) {
            if (state.timedOut.get()) return@synchronized
            dispatchStart(state)
            if (state.completionScheduled.compareAndSet(false, true)) {
                dispatchDelayed(errorFallbackMs) { dispatchDone(state) }
            }
        }
    }

    fun cancelPending(deliverCallbacks: Boolean = false) {
        pending.values.toList().forEach { state ->
            if (!deliverCallbacks) {
                state.cancelled.set(true)
            }
            if (state.completionScheduled.compareAndSet(false, true)) {
                if (deliverCallbacks) {
                    dispatch { dispatchDone(state) }
                } else {
                    pending.remove(state.id, state)
                }
            } else if (!deliverCallbacks) {
                pending.remove(state.id, state)
            }
        }
    }

    private fun dispatchStart(state: PendingUtterance) {
        dispatchStart(state, synthetic = false)
    }

    private fun dispatchStart(state: PendingUtterance, synthetic: Boolean) {
        synchronized(state) {
            if (state.cancelled.get() || (!synthetic && state.timedOut.get())) return@synchronized
            if (state.started.compareAndSet(false, true)) {
                dispatch {
                    if (!state.cancelled.get() && (synthetic || !state.timedOut.get())) state.onStart()
                }
            }
        }
    }

    private fun scheduleDone(state: PendingUtterance) {
        synchronized(state) {
            if (state.timedOut.get()) return@synchronized
            if (state.completionScheduled.compareAndSet(false, true)) {
                dispatch { dispatchDone(state) }
            }
        }
    }

    private fun dispatchDone(state: PendingUtterance) {
        if (!state.doneDelivered.compareAndSet(false, true)) return
        try {
            // Cancelled stale continuations are suppressed even if completion was already queued.
            if (!state.cancelled.get()) state.onDone()
        } finally {
            pending.remove(state.id, state)
        }
    }
}
