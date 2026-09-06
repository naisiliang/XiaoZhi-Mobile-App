package com.lchuang.xiaozhimobile.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ScreenContextStoreTest {
    @Test
    fun `screen node defensively copies source children`() {
        val originalChild = ScreenNode(id = "child-a")
        val sourceChildren = mutableListOf(originalChild)

        val node = ScreenNode(id = "root", children = sourceChildren)
        sourceChildren += ScreenNode(id = "child-b")

        assertEquals(listOf(originalChild), node.children)
    }

    @Test
    fun `screen node children reject external mutation`() {
        val node = ScreenNode(
            id = "root",
            children = mutableListOf(ScreenNode(id = "child-a")),
        )

        assertThrows(UnsupportedOperationException::class.java) {
            (node.children as MutableList<ScreenNode>) += ScreenNode(id = "child-b")
        }
        assertEquals(listOf(ScreenNode(id = "child-a")), node.children)
    }

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
