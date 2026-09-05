package com.lchuang.xiaozhimobile.conversation

/**
 * Process-owned assistant state. It contains no Activity or Service reference;
 * owners must remove their observers when their lifecycle ends.
 */
object AssistantStateStoreProvider {
    private val store = AssistantStateStore()

    fun instance(): AssistantStateStore = store
}
