package com.lchuang.xiaozhimobile.conversation

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lchuang.xiaozhimobile.R
import java.text.DateFormat
import java.util.Date

class ConversationHistoryActivity : Activity() {
    private lateinit var repository: ConversationRepository
    private lateinit var historyRoot: View
    private lateinit var historyHeader: View
    private lateinit var historyList: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = ConversationSessionStore.repository(this)
        setContentView(R.layout.activity_conversation_history)
        historyRoot = findViewById(R.id.history_root)
        historyHeader = findViewById(R.id.history_header)
        historyList = findViewById(R.id.history_list)
        configureInsets()

        val sessions = repository.loadHistoryPage().sessions
        historyList.layoutManager = LinearLayoutManager(this)
        historyList.adapter = HistoryAdapter(sessions)
    }

    private fun configureInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(historyRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            historyHeader.setPadding(dp(20), bars.top + dp(16), dp(20), dp(12))
            historyList.setPadding(
                dp(12),
                dp(8),
                dp(12),
                maxOf(bars.bottom, ime.bottom) + dp(16),
            )
            insets
        }
        ViewCompat.requestApplyInsets(historyRoot)
    }

    override fun onDestroy() {
        if (::repository.isInitialized) repository.close()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

private class HistoryAdapter(
    private val sessions: List<ConversationSession>,
) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {
    init {
        setHasStableIds(true)
    }

    override fun getItemCount(): Int = sessions.size

    override fun getItemId(position: Int): Long = sessions[position].id.hashCode().toLong()

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): HistoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_conversation_history, parent, false)
        return HistoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(sessions[position])
    }

    class HistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title = itemView.findViewById<TextView>(R.id.history_session_title)
        private val meta = itemView.findViewById<TextView>(R.id.history_session_meta)
        private val status = itemView.findViewById<TextView>(R.id.history_session_status)

        fun bind(session: ConversationSession) {
            title.text = session.title.ifBlank { "新会话" }
            meta.text = "${session.assistantName} · ${formatTimestamp(session.startedAtMs)} · ${session.messages.size} 条消息"
            status.text = when (session.status) {
                ConversationSession.Status.ACTIVE -> "进行中"
                ConversationSession.Status.COMPLETED -> "已完成"
            }
        }

        private fun formatTimestamp(timestampMs: Long): String =
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestampMs))
    }
}
