package com.lchuang.xiaozhimobile.conversation

import java.util.UUID

class ConversationSessionManager(
    private val repository: ConversationSessionRepository,
    private val ids: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = { System.currentTimeMillis() },
    initialSession: ConversationSession? = null,
    private val onChanged: (ConversationSession) -> Unit = {},
    private val assistantName: String = "小智",
) {
    companion object {
        private const val INVALIDATED_ON_PROCESS_RESTART = "INVALIDATED_ON_PROCESS_RESTART"
    }

    private val lock = Any()
    private var session: ConversationSession? = null

    init {
        val restored = initialSession
        if (restored != null &&
            restored.status == ConversationSession.Status.ACTIVE &&
            restored.endedAtMs == null
        ) {
            repository.save(
                restored.copy(
                    endedAtMs = now(),
                    endReason = INVALIDATED_ON_PROCESS_RESTART,
                    status = ConversationSession.Status.COMPLETED,
                ),
            )
        } else {
            session = restored
        }
    }

    fun startWakeSession(): ConversationSession = synchronized(lock) {
        val existing = session
        if (existing != null && existing.endedAtMs == null) return existing

        val started = ConversationSession(
            id = ids(),
            startedAtMs = now(),
            assistantName = assistantName,
        )
        session = started
        persistAndPublish(started)
        started
    }

    fun currentSession(): ConversationSession? = synchronized(lock) { session }

    fun appendUser(text: String) = append(ConversationMessage.Role.USER, text)

    fun appendAssistant(text: String) = append(ConversationMessage.Role.ASSISTANT, text)

    fun appendSystemAction(text: String) = append(ConversationMessage.Role.SYSTEM_ACTION, text)

    fun appendSystemResult(text: String) = append(ConversationMessage.Role.SYSTEM_RESULT, text)

    fun appendConfirmation(text: String) = append(ConversationMessage.Role.CONFIRMATION, text)

    fun endSession(reason: String? = null): ConversationSession? = synchronized(lock) {
        val current = session ?: return null
        if (current.endedAtMs != null) return current

        val ended = current.copy(
            endedAtMs = now(),
            endReason = reason,
            status = ConversationSession.Status.COMPLETED,
        )
        session = ended
        persistAndPublish(ended)
        ended
    }

    private fun append(role: ConversationMessage.Role, text: String): ConversationSession = synchronized(lock) {
        val current = session ?: error("No active conversation session")
        check(current.endedAtMs == null) { "Conversation session has ended" }
        val nextTitle = if (
            role == ConversationMessage.Role.USER && current.title == "新会话"
        ) {
            text.trim().take(40).ifBlank { current.title }
        } else {
            current.title
        }
        val updated = current.copy(
            messages = current.messages + ConversationMessage(role, text, now()),
            title = nextTitle,
        )
        session = updated
        persistAndPublish(updated)
        updated
    }

    private fun persistAndPublish(updated: ConversationSession) {
        repository.save(updated)
        onChanged(updated)
    }
}
