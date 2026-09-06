package com.lchuang.xiaozhimobile.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenContextStoreTest {
    @Test
    fun `published generations increase monotonically`() {
        var now = 1_000L
        val store = ScreenContextStore(ttlMs = 5_000L, clockMs = { now })
        val node = ScreenNode(id = "root", role = "window")

        val first = store.publish("com.example.app", "window-a", node)
        val second = store.publish("com.example.app", "window-a", node)

        assertEquals(1L, first.generationId.value)
        assertEquals(2L, second.generationId.value)
        assertEquals(first.packageName, second.packageName)
        assertEquals(first.windowFingerprint, second.windowFingerprint)
        now += 1L
    }

    @Test
    fun `context expires and window changes invalidate the previous context`() {
        var now = 1_000L
        val store = ScreenContextStore(ttlMs = 100L, clockMs = { now })
        val node = ScreenNode(id = "root")

        store.publish("com.example.app", "window-a", node)
        assertEquals(node, store.get("com.example.app", "window-a")?.root)

        now += 101L
        assertNull(store.get("com.example.app", "window-a"))

        val fresh = store.publish("com.example.app", "window-a", node)
        assertEquals(fresh, store.get("com.example.app", "window-a"))
        assertNull(store.get("com.example.app", "window-b"))
        assertNull(store.get("other.app", "window-a"))

        store.publish("com.example.app", "window-b", node)
        assertNull(store.get("com.example.app", "window-a"))
        assertEquals("window-b", store.get("com.example.app", "window-b")?.windowFingerprint)
    }
}
