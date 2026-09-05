package com.lchuang.xiaozhimobile.conversation

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ConversationHistoryActivity : Activity() {
    private lateinit var repository: ConversationRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = ConversationSessionStore.repository(this)

        val title = TextView(this).apply {
            text = "历史会话"
            textSize = 24f
            setPadding(32, 32, 32, 16)
        }
        val sessions = repository.loadHistory()
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ConversationHistoryActivity)
            adapter = ConversationAdapter().also { it.submitHistory(sessions) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        })
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
