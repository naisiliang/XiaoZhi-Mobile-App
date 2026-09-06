package com.lchuang.xiaozhimobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingTextRequestQueueTest {
    @Test
    fun `accepts non blank text in FIFO order`() {
        val queue = PendingTextRequestQueue(capacity = 8)

        assertTrue(queue.offer("  first  "))
        assertTrue(queue.offer("second"))
        assertTrue(queue.offer("third"))

        assertEquals(listOf("first", "second", "third"), queue.drain())
        assertEquals(emptyList<String>(), queue.drain())
    }

    @Test
    fun `rejects blank input`() {
        val queue = PendingTextRequestQueue()

        assertFalse(queue.offer("   "))
        assertFalse(queue.offer("\n\t"))
        assertEquals(emptyList<String>(), queue.drain())
    }

    @Test
    fun `rejects overflow without dropping accepted entries`() {
        val queue = PendingTextRequestQueue(capacity = 8)

        repeat(8) { index -> assertTrue(queue.offer("item-$index")) }
        assertFalse(queue.offer("overflow"))

        assertEquals((0 until 8).map { "item-$it" }, queue.drain())
        assertEquals(emptyList<String>(), queue.drain())
    }
}
