package com.lchuang.xiaozhimobile.conversation

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lchuang.xiaozhimobile.R
import java.text.DateFormat
import java.util.Date

class ConversationAdapter : RecyclerView.Adapter<ConversationAdapter.MessageViewHolder>() {
    sealed interface Row {
        data class SessionHeader(val session: ConversationSession) : Row
        data class Message(val message: ConversationMessage) : Row
    }

    private val rows = mutableListOf<Row>()

    init {
        setHasStableIds(true)
    }

    fun submitSession(session: ConversationSession?) {
        submitMessages(session?.messages.orEmpty())
    }

    fun submitMessages(nextMessages: List<ConversationMessage>) {
        rows.clear()
        rows.addAll(nextMessages.map(Row::Message))
        notifyDataSetChanged()
    }

    fun submitHistory(sessions: List<ConversationSession>) {
        rows.clear()
        sessions.forEach { session ->
            rows += Row.SessionHeader(session)
            rows += session.messages.map(Row::Message)
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (val row = rows[position]) {
        is Row.SessionHeader -> VIEW_TYPE_SESSION_HEADER
        is Row.Message -> when (row.message.role) {
            ConversationMessage.Role.USER -> VIEW_TYPE_USER
            ConversationMessage.Role.ASSISTANT -> VIEW_TYPE_ASSISTANT
            ConversationMessage.Role.SYSTEM_ACTION,
            ConversationMessage.Role.SYSTEM_RESULT,
            ConversationMessage.Role.CONFIRMATION,
            -> VIEW_TYPE_OPERATION
        }
    }

    override fun getItemId(position: Int): Long = when (val row = rows[position]) {
        is Row.SessionHeader -> row.session.id.hashCode().toLong()
        is Row.Message -> {
            var key = row.message.timestampMs
            key = key * 31 + row.message.role.ordinal
            key = key * 31 + row.message.text.hashCode()
            key
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val layout = when (viewType) {
            VIEW_TYPE_USER -> R.layout.item_message_user
            VIEW_TYPE_ASSISTANT -> R.layout.item_message_assistant
            VIEW_TYPE_OPERATION, VIEW_TYPE_SESSION_HEADER -> R.layout.item_message_operation
            else -> error("Unknown conversation row type: $viewType")
        }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(rows[position])
    }

    override fun getItemCount(): Int = rows.size

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val role = itemView.findViewById<TextView>(R.id.message_role)
        private val content = itemView.findViewById<TextView>(R.id.message_text)

        fun bind(row: Row) {
            when (row) {
                is Row.SessionHeader -> {
                    role.text = "会话 · ${row.session.title}"
                    content.text = "${row.session.assistantName} · ${statusLabel(row.session.status)} · ${formatTimestamp(row.session.startedAtMs)}"
                }
                is Row.Message -> {
                    role.text = when (row.message.role) {
                        ConversationMessage.Role.USER -> "我"
                        ConversationMessage.Role.ASSISTANT -> "小白"
                        ConversationMessage.Role.SYSTEM_ACTION -> "系统操作"
                        ConversationMessage.Role.SYSTEM_RESULT -> "系统结果"
                        ConversationMessage.Role.CONFIRMATION -> "确认"
                    }
                    content.text = row.message.text
                }
            }
        }

        private fun statusLabel(status: ConversationSession.Status): String = when (status) {
            ConversationSession.Status.ACTIVE -> "进行中"
            ConversationSession.Status.COMPLETED -> "已完成"
        }

        private fun formatTimestamp(timestampMs: Long): String =
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestampMs))
    }

    private companion object {
        const val VIEW_TYPE_USER = 1
        const val VIEW_TYPE_ASSISTANT = 2
        const val VIEW_TYPE_OPERATION = 3
        const val VIEW_TYPE_SESSION_HEADER = 4
    }
}

enum class ConversationResultKind {
    TEXT,
    VOICE,
    OPERATION,
}

data class ConversationResult(
    val kind: ConversationResultKind,
    val text: String,
)

object ConversationResultBridge {
    fun interface Sink {
        fun onResult(result: ConversationResult)
    }

    private val lock = Any()
    @Volatile private var sink: Sink? = null

    fun registerSink(nextSink: Sink) {
        synchronized(lock) { sink = nextSink }
    }

    fun unregisterSink(previousSink: Sink) {
        synchronized(lock) {
            if (sink === previousSink) sink = null
        }
    }

    fun submitText(text: String) = submit(ConversationResult(ConversationResultKind.TEXT, text))

    fun submitVoice(text: String) = submit(ConversationResult(ConversationResultKind.VOICE, text))

    fun submitOperation(text: String) = submit(ConversationResult(ConversationResultKind.OPERATION, text))

    private fun submit(result: ConversationResult) {
        val currentSink = synchronized(lock) { sink }
        currentSink?.onResult(result)
    }
}

/**
 * UI-facing facade over the existing persistence implementation. It keeps the
 * write contract from SqliteConversationRepository and adds read operations for
 * the chat home and history screens.
 */
class ConversationRepository(context: Context) : ConversationSessionRepository {
    private val writer = SqliteConversationRepository(context)
    private val database = ConversationDatabase(context.applicationContext)

    override fun save(session: ConversationSession) {
        writer.save(session)
    }

    fun loadCurrent(): ConversationSession? = loadSessions("status = ?", arrayOf("active")).firstOrNull()

    fun loadHistory(): List<ConversationSession> = loadSessions(null, null)

    fun close() {
        writer.close()
        database.close()
    }

    private fun loadSessions(selection: String?, selectionArgs: Array<String>?): List<ConversationSession> {
        val db = database.readableDatabase
        val sessions = mutableListOf<ConversationSession>()
        db.query(
            "conversation_sessions",
            arrayOf("id", "title", "started_at", "ended_at", "status", "assistant_name"),
            selection,
            selectionArgs,
            null,
            null,
            "started_at DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                sessions += ConversationSession(
                    id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                    startedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("started_at")),
                    messages = loadMessages(cursor.getString(cursor.getColumnIndexOrThrow("id"))),
                    endedAtMs = cursor.getLongOrNull("ended_at"),
                    title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                    status = parseStatus(cursor.getString(cursor.getColumnIndexOrThrow("status"))),
                    assistantName = cursor.getString(cursor.getColumnIndexOrThrow("assistant_name")),
                )
            }
        }
        return sessions
    }

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
