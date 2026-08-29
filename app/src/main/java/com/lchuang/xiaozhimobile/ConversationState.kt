package com.lchuang.xiaozhimobile

enum class ConversationState {
    IDLE_WAKE,
    LISTENING,
    RECOGNIZING,
    EXECUTING,
    SPEAKING,
    READY_TO_LISTEN,
    EXITING
}

fun ConversationState.statusText(): String = when (this) {
    ConversationState.IDLE_WAKE -> "等待唤醒…"
    ConversationState.LISTENING -> "正在听你说…"
    ConversationState.RECOGNIZING -> "正在识别…"
    ConversationState.EXECUTING -> "正在执行…"
    ConversationState.SPEAKING -> "正在回复…"
    ConversationState.READY_TO_LISTEN -> "准备继续监听…"
    ConversationState.EXITING -> "正在退出…"
}
