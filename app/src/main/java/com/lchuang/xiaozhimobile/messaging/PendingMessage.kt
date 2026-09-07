package com.lchuang.xiaozhimobile.messaging

import com.lchuang.xiaozhimobile.screen.GenerationId

/** In-memory request snapshot used while a message waits for confirmation. */
data class PendingMessage(
    val packageName: String,
    val contactId: String,
    val contactDisplayName: String,
    val conversationTitle: String,
    val body: String,
    val generationId: GenerationId,
    val windowFingerprint: String,
    val createdAtMs: Long,
) {
    init {
        require(packageName.isNotBlank()) { "Messaging package must not be blank" }
        require(contactId.isNotBlank()) { "Messaging contact id must not be blank" }
        require(contactDisplayName.isNotBlank()) { "Messaging contact name must not be blank" }
        require(conversationTitle.isNotBlank()) { "Messaging conversation title must not be blank" }
        require(body.isNotBlank()) { "Messaging body must not be blank" }
        require(generationId.value > 0L) { "Messaging generation must be positive" }
        require(windowFingerprint.isNotBlank()) { "Messaging window fingerprint must not be blank" }
        require(createdAtMs >= 0L) { "Messaging creation time must not be negative" }
    }
}
