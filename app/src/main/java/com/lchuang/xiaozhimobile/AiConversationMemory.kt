package com.lchuang.xiaozhimobile

import java.util.ArrayDeque

class AiConversationMemory(private val maxTurns: Int = 8) {
    private data class Turn(val user: String, val assistant: String)
    private val turns = ArrayDeque<Turn>()
    @Volatile private var active = false

    @Synchronized
    fun startSession() {
        turns.clear()
        active = true
    }

    @Synchronized
    fun addTurn(userText: String, assistantText: String) {
        if (!active) return
        val user = userText.trim()
        val assistant = assistantText.trim().take(1200)
        if (user.isBlank() || assistant.isBlank()) return
        turns.addLast(Turn(user, assistant))
        while (turns.size > maxTurns.coerceAtLeast(1)) turns.removeFirst()
    }

    @Synchronized
    fun messages(): List<ConversationMessage> = turns.flatMap { turn ->
        listOf(
            ConversationMessage("user", turn.user),
            ConversationMessage("assistant", turn.assistant)
        )
    }

    @Synchronized
    fun clear() {
        turns.clear()
        active = false
    }
}
