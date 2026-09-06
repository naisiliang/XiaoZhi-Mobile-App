package com.lchuang.xiaozhimobile.runtime

import java.util.ArrayDeque

class PendingTextRequestQueue(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val lock = Any()
    private val pending = ArrayDeque<String>(capacity.coerceAtLeast(1))

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    fun offer(rawText: String): Boolean {
        val text = rawText.trim()
        if (text.isBlank()) return false
        return synchronized(lock) {
            if (pending.size >= capacity) return@synchronized false
            pending.addLast(text)
            true
        }
    }

    fun poll(): String? = synchronized(lock) {
        if (pending.isEmpty()) null else pending.removeFirst()
    }

    fun drain(): List<String> = synchronized(lock) {
        buildList(pending.size) {
            while (pending.isNotEmpty()) add(pending.removeFirst())
        }
    }

    fun clear() = synchronized(lock) { pending.clear() }

    companion object {
        const val DEFAULT_CAPACITY = 8
    }
}
