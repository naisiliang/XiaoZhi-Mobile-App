package com.lchuang.xiaozhimobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeRuntimeStatusStoreTest {
    @Test
    fun `starts stopped without a detail`() {
        val store = WakeRuntimeStatusStore()

        assertEquals(WakeRuntimeStatus.STOPPED, store.current)
        assertNull(store.detail)
    }

    @Test
    fun `publishes startup states in order`() {
        val store = WakeRuntimeStatusStore()
        val observed = mutableListOf<WakeRuntimeStatus>()
        val observer: (WakeRuntimeStatus) -> Unit = { observed += it }

        store.addObserver(observer)
        try {
            store.publish(WakeRuntimeStatus.STARTING, "loading offline models")
            store.publish(WakeRuntimeStatus.KWS_LISTENING, "listening for wake phrase")

            assertEquals(
                listOf(WakeRuntimeStatus.STARTING, WakeRuntimeStatus.KWS_LISTENING),
                observed,
            )
            assertEquals(WakeRuntimeStatus.KWS_LISTENING, store.current)
            assertEquals("listening for wake phrase", store.detail)
        } finally {
            store.removeObserver(observer)
        }
    }

    @Test
    fun `publishes active session and startup error states with details`() {
        val store = WakeRuntimeStatusStore()
        val observed = mutableListOf<WakeRuntimeStatus>()
        val observer: (WakeRuntimeStatus) -> Unit = { observed += it }

        store.addObserver(observer)
        try {
            store.publish(WakeRuntimeStatus.SESSION_ACTIVE, "wake phrase detected")
            assertEquals(WakeRuntimeStatus.SESSION_ACTIVE, store.current)
            assertEquals("wake phrase detected", store.detail)

            store.publish(WakeRuntimeStatus.ERROR, "AudioRecord unavailable")
            assertEquals(WakeRuntimeStatus.ERROR, store.current)
            assertEquals("AudioRecord unavailable", store.detail)
            assertEquals(
                listOf(WakeRuntimeStatus.SESSION_ACTIVE, WakeRuntimeStatus.ERROR),
                observed,
            )
        } finally {
            store.removeObserver(observer)
        }
    }

    @Test
    fun `removing observer stops later notifications`() {
        val store = WakeRuntimeStatusStore()
        val observed = mutableListOf<WakeRuntimeStatus>()
        val observer: (WakeRuntimeStatus) -> Unit = { observed += it }

        store.addObserver(observer)
        store.publish(WakeRuntimeStatus.STARTING)
        store.removeObserver(observer)
        store.publish(WakeRuntimeStatus.STOPPED, "stopped by user")

        assertEquals(listOf(WakeRuntimeStatus.STARTING), observed)
    }
}
