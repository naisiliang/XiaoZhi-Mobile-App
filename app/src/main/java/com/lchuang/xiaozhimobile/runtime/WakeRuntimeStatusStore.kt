package com.lchuang.xiaozhimobile.runtime

import java.util.concurrent.CopyOnWriteArrayList

data class WakeRuntimeStatusSnapshot(
    val status: WakeRuntimeStatus,
    val detail: String? = null,
)

class WakeRuntimeStatusStore {
    private val observers = CopyOnWriteArrayList<(WakeRuntimeStatus) -> Unit>()

    @Volatile
    private var snapshot = WakeRuntimeStatusSnapshot(WakeRuntimeStatus.STOPPED)

    val current: WakeRuntimeStatus
        get() = snapshot.status

    val detail: String?
        get() = snapshot.detail

    fun publish(status: WakeRuntimeStatus, detail: String? = null) {
        snapshot = WakeRuntimeStatusSnapshot(status, detail)
        observers.forEach { it(status) }
    }

    fun addObserver(observer: (WakeRuntimeStatus) -> Unit) {
        observers += observer
    }

    fun removeObserver(observer: (WakeRuntimeStatus) -> Unit) {
        observers -= observer
    }
}

object WakeRuntimeStatusStoreProvider {
    private val store = WakeRuntimeStatusStore()

    fun instance(): WakeRuntimeStatusStore = store
}
