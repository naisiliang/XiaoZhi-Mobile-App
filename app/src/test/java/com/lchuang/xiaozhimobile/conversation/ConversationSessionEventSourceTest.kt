package com.lchuang.xiaozhimobile.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ConversationSessionEventSourceTest {
    @Test
    fun `shared source publishes metadata and every persisted message role`() {
        val repository = RecordingRepository()
        val updates = mutableListOf<ConversationSession>()
        val source = ConversationSessionEventSource(
            repository = repository,
            ids = { "session-1" },
            now = { 1_000L },
            assistantName = "小白",
        )
        source.addObserver { updates += it }

        source.startWakeSession()
        source.appendUser("打开地图")
        source.appendSystemAction("open_map")
        source.appendSystemResult("地图已打开")
        source.appendConfirmation("已确认")
        source.appendAssistant("好的")
        val completed = source.endSession("conversation_exit")

        assertEquals("小白", completed!!.assistantName)
        assertEquals(ConversationSession.Status.COMPLETED, completed.status)
        assertEquals("打开地图", completed.title)
        assertEquals(
            listOf(
                ConversationMessage.Role.USER,
                ConversationMessage.Role.SYSTEM_ACTION,
                ConversationMessage.Role.SYSTEM_RESULT,
                ConversationMessage.Role.CONFIRMATION,
                ConversationMessage.Role.ASSISTANT,
            ),
            completed.messages.map { it.role },
        )
        assertEquals(completed, repository.saved.last())
        assertTrue(updates.last() == completed)
    }

    @Test
    fun `shared source serializes concurrent appends without dropping events`() {
        val source = ConversationSessionEventSource(
            repository = RecordingRepository(),
            ids = { "session-1" },
            now = { 1_000L },
        )
        source.startWakeSession()
        val workers = 4
        val appendsPerWorker = 25
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val done = CountDownLatch(workers)
        repeat(workers) { worker ->
            Thread {
                ready.countDown()
                start.await()
                repeat(appendsPerWorker) { source.appendUser("$worker-$it") }
                done.countDown()
            }.start()
        }
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))

        assertEquals(workers * appendsPerWorker, source.currentSession()!!.messages.size)
    }

    private class RecordingRepository : ConversationSessionRepository {
        val saved = mutableListOf<ConversationSession>()

        override fun save(session: ConversationSession) {
            saved += session
        }
    }
}
