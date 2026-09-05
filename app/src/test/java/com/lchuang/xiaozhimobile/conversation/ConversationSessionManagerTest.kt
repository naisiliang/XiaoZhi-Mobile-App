package com.lchuang.xiaozhimobile.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSessionManagerTest {
    @Test
    fun `manager persists and exposes the complete session lifecycle`() {
        val repository = RecordingConversationSessionRepository()
        var nextId = 0
        var nextTime = 1_000L
        val manager = ConversationSessionManager(
            repository = repository,
            ids = { "session-${++nextId}" },
            now = { nextTime.also { nextTime += 10 } },
        )

        val first = manager.startWakeSession()
        val repeated = manager.startWakeSession()
        assertEquals("session-1", first.id)
        assertTrue(first.endedAtMs == null)
        assertSame(first, repeated)
        assertEquals(first, manager.currentSession())
        assertEquals(listOf(first), repository.saved)

        val withUser = manager.appendUser("一")
        assertEquals(ConversationMessage.Role.USER, withUser.messages.single().role)
        assertEquals("一", withUser.messages.single().text)
        assertEquals(withUser, manager.currentSession())
        assertEquals(withUser, repository.saved.last())

        val withAssistant = manager.appendAssistant("你好")
        assertEquals(
            listOf(ConversationMessage.Role.USER, ConversationMessage.Role.ASSISTANT),
            withAssistant.messages.map { it.role },
        )
        assertEquals("你好", withAssistant.messages.last().text)
        assertEquals(withAssistant, repository.saved.last())

        val ended = manager.endSession("wake-timeout")
        assertNotNull(ended)
        assertEquals("wake-timeout", ended!!.endReason)
        assertEquals(1_030L, ended.endedAtMs)
        assertEquals(ended, manager.currentSession())
        assertEquals(ended, repository.saved.last())

        val rejection = runCatching { manager.appendUser("之后") }.exceptionOrNull()
        assertTrue(rejection is IllegalStateException)
        assertEquals(ended, manager.currentSession())

        val second = manager.startWakeSession()
        assertNotEquals(first.id, second.id)
        assertEquals("session-2", second.id)
        assertTrue(second.endedAtMs == null)
        assertEquals(second, manager.currentSession())
        assertEquals(second, repository.saved.last())
    }

    @Test
    fun `new manager invalidates persisted active session before creating a new wake session`() {
        val repository = PersistedConversationSessionRepository()
        val stale = ConversationSession(
            id = "stale-session",
            startedAtMs = 1_000L,
            messages = listOf(
                ConversationMessage(
                    role = ConversationMessage.Role.USER,
                    text = "未完成的请求",
                    timestampMs = 1_010L,
                    status = "pending",
                ),
            ),
        )
        repository.seed(stale)

        var nextId = 0
        var nextTime = 2_000L
        val restartedManager = ConversationSessionManager(
            repository = repository,
            ids = { "new-session-${++nextId}" },
            now = { nextTime.also { nextTime += 10 } },
            initialSession = repository.loadCurrent(),
        )

        assertNull(restartedManager.currentSession())
        val invalidated = repository.session(stale.id)
        assertEquals(ConversationSession.Status.COMPLETED, invalidated.status)
        assertEquals("INVALIDATED_ON_PROCESS_RESTART", invalidated.endReason)
        assertEquals("pending", invalidated.messages.single().status)
        assertEquals("未完成的请求", invalidated.messages.single().text)
        assertNull(repository.loadCurrent())

        val fresh = restartedManager.startWakeSession()
        assertNotEquals(stale.id, fresh.id)
        assertEquals("new-session-1", fresh.id)
        assertTrue(fresh.messages.isEmpty())
        assertEquals(fresh, repository.loadCurrent())
    }

    private class RecordingConversationSessionRepository : ConversationSessionRepository {
        val saved = mutableListOf<ConversationSession>()

        override fun save(session: ConversationSession) {
            saved += session
        }
    }

    private class PersistedConversationSessionRepository : ConversationSessionRepository {
        private val sessions = linkedMapOf<String, ConversationSession>()

        override fun save(session: ConversationSession) {
            sessions[session.id] = session
        }

        fun seed(session: ConversationSession) {
            sessions[session.id] = session
        }

        fun loadCurrent(): ConversationSession? = sessions.values.firstOrNull {
            it.status == ConversationSession.Status.ACTIVE
        }

        fun session(id: String): ConversationSession = checkNotNull(sessions[id])
    }
}
