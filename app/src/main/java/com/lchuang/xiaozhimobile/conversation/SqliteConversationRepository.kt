package com.lchuang.xiaozhimobile.conversation

import android.content.ContentValues
import android.content.Context

class SqliteConversationRepository(
    context: Context,
    private val assistantName: String = "小智",
) : ConversationSessionRepository {
    private val database = ConversationDatabase(context.applicationContext)

    override fun save(session: ConversationSession) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val sessionValues = ContentValues().apply {
                put("id", session.id)
                put("title", session.title)
                put("started_at", session.startedAtMs)
                if (session.endedAtMs == null) putNull("ended_at") else put("ended_at", session.endedAtMs)
                put("status", session.status.name.lowercase())
                put("assistant_name", session.assistantName.ifBlank { assistantName })
            }
            db.insertWithOnConflict(
                "conversation_sessions",
                null,
                sessionValues,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
            )

            db.delete("conversation_messages", "session_id = ?", arrayOf(session.id))
            session.messages.forEachIndexed { index, message ->
                val messageValues = ContentValues().apply {
                    put("id", "${session.id}:$index")
                    put("session_id", session.id)
                    put("timestamp", message.timestampMs)
                    put("role", message.role.name.lowercase())
                    put("content", message.text)
                    put("status", message.status)
                }
                db.insertOrThrow("conversation_messages", null, messageValues)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun loadAll(): List<ConversationSession> {
        return loadSessions()
    }

    /** Loads a bounded session page for history UIs without changing the recovery loadAll contract. */
    fun loadHistoryPage(
        limit: Int = ConversationHistoryPagination.DEFAULT_PAGE_SIZE,
        offset: Int = 0,
    ): ConversationHistoryPage {
        ConversationHistoryPagination.validate(offset, limit)
        return ConversationHistoryPagination.fromQueryRows(
            rows = loadSessions(queryLimit = limit + 1, queryOffset = offset),
            offset = offset,
            limit = limit,
        )
    }

    private fun loadSessions(queryLimit: Int? = null, queryOffset: Int = 0): List<ConversationSession> {
        if (queryLimit != null) {
            require(queryLimit >= 1) { "query limit must be positive" }
            require(queryOffset >= 0) { "query offset must not be negative" }
        }
        val sessions = mutableListOf<ConversationSession>()
        database.readableDatabase.query(
            "conversation_sessions",
            arrayOf("id", "title", "started_at", "ended_at", "status", "assistant_name"),
            null,
            null,
            null,
            null,
            "started_at DESC, id ASC",
            queryLimit?.let { "$it OFFSET $queryOffset" },
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(cursor.getColumnIndexOrThrow("id"))
                sessions += ConversationSession(
                    id = id,
                    startedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("started_at")),
                    messages = loadMessages(id),
                    endedAtMs = cursor.getLongOrNull("ended_at"),
                    title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                    status = parseStatus(cursor.getString(cursor.getColumnIndexOrThrow("status"))),
                    assistantName = cursor.getString(cursor.getColumnIndexOrThrow("assistant_name")),
                )
            }
        }
        return sessions
    }

    fun close() = database.close()

    private fun loadMessages(sessionId: String): List<ConversationMessage> {
        val messages = mutableListOf<ConversationMessage>()
        database.readableDatabase.query(
            "conversation_messages",
            arrayOf("timestamp", "role", "content", "status"),
            "session_id = ?",
            arrayOf(sessionId),
            null,
            null,
            "timestamp ASC, id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                messages += ConversationMessage(
                    role = ConversationMessage.Role.valueOf(
                        cursor.getString(cursor.getColumnIndexOrThrow("role")).uppercase(),
                    ),
                    text = cursor.getString(cursor.getColumnIndexOrThrow("content")),
                    timestampMs = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
                    status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
                )
            }
        }
        return messages
    }

    private fun parseStatus(raw: String): ConversationSession.Status = when (raw.lowercase()) {
        "active" -> ConversationSession.Status.ACTIVE
        else -> ConversationSession.Status.COMPLETED
    }

    private fun android.database.Cursor.getLongOrNull(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }
}
