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

    fun close() = database.close()
}
