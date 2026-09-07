package com.lchuang.xiaozhimobile.messaging

import com.lchuang.xiaozhimobile.screen.GenerationId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageConfirmationTokenTest {
    @Test
    fun tokenExpiresAtExactlySixtySeconds() {
        val pending = pending()
        val token = MessageConfirmationToken.issue(
            pending = pending,
            issuedAtMs = 1_000L,
            tokenId = "token-1",
        )

        assertTrue(token.isValid(pending, nowMs = 60_999L))
        assertFalse(token.isValid(pending, nowMs = 61_000L))
    }

    @Test
    fun tokenBindsEveryMessageFieldExactly() {
        val pending = pending()
        val token = MessageConfirmationToken.issue(pending, 1_000L, "token-2")

        assertTrue(token.isValid(pending, nowMs = 1_001L))
        assertFalse(token.isValid(pending.copy(contactId = "contact-2"), 1_001L))
        assertFalse(token.isValid(pending.copy(contactDisplayName = "李四"), 1_001L))
        assertFalse(token.isValid(pending.copy(conversationTitle = "另一个对话"), 1_001L))
        assertFalse(token.isValid(pending.copy(body = "已修改的消息"), 1_001L))
        assertFalse(token.isValid(pending.copy(packageName = "com.tencent.mobileqq"), 1_001L))
    }

    @Test
    fun pageGenerationOrWindowChangeInvalidatesToken() {
        val pending = pending()
        val token = MessageConfirmationToken.issue(pending, 1_000L, "token-3")

        assertFalse(token.isValid(pending.copy(generationId = GenerationId(8L)), 1_001L))
        assertFalse(token.isValid(pending.copy(windowFingerprint = "com.tencent.mm:window-2"), 1_001L))
    }

    @Test
    fun statesExposeConfirmationAndTerminalUnverifiedOutcome() {
        assertTrue(MessagingState.values().contains(MessagingState.WAITING_CONFIRMATION))
        assertTrue(MessagingState.values().contains(MessagingState.SEND_UNVERIFIED))
    }

    private fun pending() = PendingMessage(
        packageName = "com.tencent.mm",
        contactId = "contact-1",
        contactDisplayName = "张三",
        conversationTitle = "张三",
        body = "明天见",
        generationId = GenerationId(7L),
        windowFingerprint = "com.tencent.mm:window-1",
        createdAtMs = 900L,
    )
}
