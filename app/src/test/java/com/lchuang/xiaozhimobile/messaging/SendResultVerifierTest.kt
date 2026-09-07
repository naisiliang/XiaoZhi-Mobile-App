package com.lchuang.xiaozhimobile.messaging

import com.lchuang.xiaozhimobile.screen.ScreenContext
import com.lchuang.xiaozhimobile.screen.ScreenContextStore
import com.lchuang.xiaozhimobile.screen.ScreenNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendResultVerifierTest {
    @Test
    fun sentRequiresClearedInputAndExactOutgoingBubble() {
        val result = SendResultVerifier().verify(
            expectedBody = "晚上好",
            context = Fixture.chat(inputText = null, outgoing = "晚上好"),
            actionSucceeded = true,
        )

        assertEquals(SendVerificationState.SENT, result.state)
        assertEquals(0, result.retryCount)
    }

    @Test
    fun missingBubbleOrUnclearedInputIsUnverified() {
        val verifier = SendResultVerifier()

        val missingBubble = verifier.verify(
            expectedBody = "晚上好",
            context = Fixture.chat(inputText = null, outgoing = null),
            actionSucceeded = true,
        )
        val unclearedInput = verifier.verify(
            expectedBody = "晚上好",
            context = Fixture.chat(inputText = "晚上好", outgoing = "晚上好"),
            actionSucceeded = true,
        )

        assertEquals(SendVerificationState.SEND_UNVERIFIED, missingBubble.state)
        assertEquals(SendVerificationState.SEND_UNVERIFIED, unclearedInput.state)
        assertEquals(0, missingBubble.retryCount)
        assertEquals(0, unclearedInput.retryCount)
    }

    @Test
    fun failedActionIsDistinctAndIncomingBubbleDoesNotProveSend() {
        val verifier = SendResultVerifier()

        val failed = verifier.verify(
            expectedBody = "晚上好",
            context = Fixture.chat(inputText = null, outgoing = "晚上好"),
            actionSucceeded = false,
        )
        val incomingOnly = verifier.verify(
            expectedBody = "晚上好",
            context = Fixture.incomingOnly("晚上好"),
            actionSucceeded = true,
        )

        assertEquals(SendVerificationState.SEND_FAILED, failed.state)
        assertEquals(SendVerificationState.SEND_UNVERIFIED, incomingOnly.state)
        assertEquals(0, failed.retryCount)
        assertTrue(failed.debugCode.isNotBlank())
    }

    @Test
    fun coordinatorHasTerminalNoRetryStates() {
        val clock = MutableClock(1_000L)
        val store = ScreenContextStore(clockMs = { clock.now })
        val list = store.publish(
            "com.tencent.mm",
            "com.tencent.mm:1",
            Fixture.contactListRoot(),
        )
        val beforeSend = store.publish(
            "com.tencent.mm",
            "com.tencent.mm:2",
            Fixture.chatRoot("张三", inputText = null, outgoing = null),
        )
        val coordinator = MessagingCoordinator(
            adapters = listOf(WeChatMessagingAdapter()),
            clockMs = { clock.now },
            contextStore = store,
        )
        coordinator.start(MessagingRequest("com.tencent.mm", "张三", "晚上好"), list)
        val card = coordinator.onChatOpened(beforeSend).confirmationCard!!
        coordinator.confirm(card.tokenId, beforeSend, "晚上好")

        val sentContext = store.publish(
            "com.tencent.mm",
            "com.tencent.mm:2",
            Fixture.chatRoot("张三", inputText = null, outgoing = "晚上好"),
        )
        val sent = coordinator.verifySendResult(sentContext, actionSucceeded = true)

        assertEquals(MessagingState.SENT, sent.state)
        assertEquals(0, sent.sendResult?.retryCount)
        assertNull(coordinator.verifySendResult(sentContext, actionSucceeded = true).sendRequest)
    }

    private class MutableClock(var now: Long)

    private object Fixture {
        fun contactListRoot() = ScreenNode(
            id = "contacts-root",
            role = "contact_list",
            text = "联系人",
            children = listOf(
                ScreenNode(
                    id = "contact-0",
                    role = "contact",
                    text = "张三",
                    clickable = true,
                ),
            ),
        )

        fun chat(
            inputText: String?,
            outgoing: String?,
        ): ScreenContext {
            val store = ScreenContextStore(clockMs = { 1_000L })
            return store.publish("com.tencent.mm", "com.tencent.mm:2", chatRoot("张三", inputText, outgoing))
        }

        fun incomingOnly(text: String): ScreenContext {
            val store = ScreenContextStore(clockMs = { 1_000L })
            val root = ScreenNode(
                id = "chat-root",
                role = "chat",
                text = "张三",
                children = listOf(
                    ScreenNode(id = "message-input", role = "message_input"),
                    ScreenNode(id = "incoming", role = "incoming_message", text = text),
                ),
            )
            return store.publish("com.tencent.mm", "com.tencent.mm:2", root)
        }

        fun chatRoot(contact: String, inputText: String?, outgoing: String?): ScreenNode =
            ScreenNode(
                id = "chat-root",
                role = "chat",
                text = contact,
                children = buildList {
                    add(ScreenNode(id = "message-input", role = "message_input", text = inputText))
                    add(ScreenNode(id = "send-button", role = "send_button", text = "发送", clickable = true))
                    outgoing?.let { add(ScreenNode(id = "outgoing", role = "outgoing_message", text = it)) }
                },
            )
    }
}
