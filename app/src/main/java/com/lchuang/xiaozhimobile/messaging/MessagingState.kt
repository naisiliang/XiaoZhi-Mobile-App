package com.lchuang.xiaozhimobile.messaging

/** Explicit lifecycle for a guarded ordinary-text message request. */
enum class MessagingState {
    IDLE,
    RESOLVING_CONTACT,
    NEEDS_CONTACT_SELECTION,
    CONTACT_RESOLVED,
    OPENING_CHAT,
    PREPARING_MESSAGE,
    WAITING_CONFIRMATION,
    REVALIDATING,
    SENDING,
    SENT,
    SEND_FAILED,
    SEND_UNVERIFIED,
    CANCELLED,
    EXPIRED,
    BLOCKED,
}
