package com.lchuang.xiaozhimobile.conversation

data class ConversationMessage(
    val role: Role,
    val text: String,
    val timestampMs: Long,
    val status: String = "complete",
) {
    enum class Role { USER, ASSISTANT, SYSTEM_ACTION, SYSTEM_RESULT, CONFIRMATION }
}

data class ConversationSession(
    val id: String,
    val startedAtMs: Long,
    val messages: List<ConversationMessage> = emptyList(),
    val endedAtMs: Long? = null,
    val endReason: String? = null,
    val title: String = "新会话",
    val status: Status = if (endedAtMs == null) Status.ACTIVE else Status.COMPLETED,
    val assistantName: String = "小智",
) {
    enum class Status { ACTIVE, COMPLETED }
}

interface ConversationSessionRepository {
    fun save(session: ConversationSession)
}
