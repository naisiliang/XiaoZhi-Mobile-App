package com.lchuang.xiaozhimobile.messaging

import com.lchuang.xiaozhimobile.screen.ContextCandidate
import com.lchuang.xiaozhimobile.screen.GenerationId

/** A contact identity enriched with the exact semantic node that resolved it. */
data class ContactCandidate(
    val target: ContextCandidate,
    val displayName: String = target.label,
    val conversationTitle: String = displayName,
) {
    val id: String
        get() = target.id

    val generationId: GenerationId
        get() = target.generationId

    val packageName: String
        get() = target.packageName

    val windowFingerprint: String
        get() = target.windowFingerprint

    init {
        require(displayName.isNotBlank()) { "Messaging contact name must not be blank" }
        require(conversationTitle.isNotBlank()) {
            "Messaging conversation title must not be blank"
        }
    }

    override fun toString(): String =
        "ContactCandidate(id=$id, generationId=$generationId, " +
            "packageName=$packageName, windowFingerprint=$windowFingerprint)"
}
