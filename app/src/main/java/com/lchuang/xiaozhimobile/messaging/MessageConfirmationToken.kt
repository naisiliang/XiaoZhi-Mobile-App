package com.lchuang.xiaozhimobile.messaging

import com.lchuang.xiaozhimobile.screen.GenerationId
import java.util.UUID

/** A short-lived, exact binding for the message shown to the user. */
class MessageConfirmationToken internal constructor(
    val tokenId: String,
    val packageName: String,
    val contactId: String,
    val contactDisplayName: String,
    val conversationTitle: String,
    val body: String,
    val generationId: GenerationId,
    val windowFingerprint: String,
    val issuedAtMs: Long,
    val expiresAtMs: Long,
) {
    init {
        require(tokenId.isNotBlank()) { "Message confirmation token id must not be blank" }
        require(packageName.isNotBlank()) { "Message token package must not be blank" }
        require(contactId.isNotBlank()) { "Message token contact id must not be blank" }
        require(contactDisplayName.isNotBlank()) { "Message token contact name must not be blank" }
        require(conversationTitle.isNotBlank()) { "Message token conversation title must not be blank" }
        require(body.isNotBlank()) { "Message token body must not be blank" }
        require(generationId.value > 0L) { "Message token generation must be positive" }
        require(windowFingerprint.isNotBlank()) { "Message token window fingerprint must not be blank" }
        require(issuedAtMs >= 0L) { "Message token issue time must not be negative" }
        require(expiresAtMs - issuedAtMs == TTL_MS) {
            "Message confirmation token must expire after exactly 60 seconds"
        }
    }

    fun isValid(pending: PendingMessage, nowMs: Long): Boolean =
        nowMs >= issuedAtMs && nowMs < expiresAtMs && matches(pending)

    fun matches(pending: PendingMessage): Boolean =
        packageName == pending.packageName &&
            contactId == pending.contactId &&
            contactDisplayName == pending.contactDisplayName &&
            conversationTitle == pending.conversationTitle &&
            body == pending.body &&
            generationId == pending.generationId &&
            windowFingerprint == pending.windowFingerprint

    companion object {
        const val TTL_MS = 60_000L

        fun issue(
            pending: PendingMessage,
            issuedAtMs: Long,
            tokenId: String = UUID.randomUUID().toString(),
        ): MessageConfirmationToken {
            require(issuedAtMs >= 0L) { "Message token issue time must not be negative" }
            require(issuedAtMs <= Long.MAX_VALUE - TTL_MS) {
                "Message token issue time is too large"
            }
            return MessageConfirmationToken(
                tokenId = tokenId,
                packageName = pending.packageName,
                contactId = pending.contactId,
                contactDisplayName = pending.contactDisplayName,
                conversationTitle = pending.conversationTitle,
                body = pending.body,
                generationId = pending.generationId,
                windowFingerprint = pending.windowFingerprint,
                issuedAtMs = issuedAtMs,
                expiresAtMs = issuedAtMs + TTL_MS,
            )
        }
    }
}
