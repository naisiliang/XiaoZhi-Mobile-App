package com.lchuang.xiaozhimobile

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandResultNotifierTest {
    @Test
    fun repeated_identical_failures_are_not_published_until_the_hold_window_expires() {
        var now = 100L
        val published = mutableListOf<String>()
        val notifier = CommandResultNotifier(
            publish = published::add,
            clockMs = { now },
            holdMs = 4_000L,
        )

        notifier.failure("❌ 执行失败：网络不可用")
        notifier.failure("❌ 执行失败：网络不可用")
        assertEquals(listOf("❌ 执行失败：网络不可用"), published)

        now += 4_000L
        notifier.failure("❌ 执行失败：网络不可用")
        assertEquals(
            listOf("❌ 执行失败：网络不可用", "❌ 执行失败：网络不可用"),
            published,
        )
    }
}
