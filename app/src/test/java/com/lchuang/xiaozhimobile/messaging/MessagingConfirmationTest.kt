package com.lchuang.xiaozhimobile.messaging

import com.lchuang.xiaozhimobile.screen.ScreenContext
import com.lchuang.xiaozhimobile.screen.ScreenContextStore
import com.lchuang.xiaozhimobile.screen.ScreenNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagingConfirmationTest {
    @Test
    fun validConfirmationRevalidatesLiveContextAndPreparesOnlyOneSendRequest() {
        val clock = MutableClock(1_000L)
        val fixture = Fixture(clock)
        val coordinator = coordinator(clock, fixture.store)
        val card = prepare(coordinator, fixture).confirmationCard!!

        val result = coordinator.confirm(card.tokenId, fixture.chatContext, "晚上好")

        assertEquals(MessagingState.SENDING, result.state)
        assertEquals("晚上好", result.sendRequest?.pendingMessage?.body)
        assertNotNull(result.sendRequest)
        assertTrue(MessagingState.REVALIDATING in coordinator.stateHistory)

        val second = coordinator.confirm(card.tokenId, fixture.chatContext, "晚上好")
        assertNull(second.sendRequest)
    }

    @Test
    fun editedBodyInvalidatesConfirmationBeforeAnySendRequest() {
        val clock = MutableClock(1_000L)
        val fixture = Fixture(clock)
        val coordinator = coordinator(clock, fixture.store)
        val card = prepare(coordinator, fixture).confirmationCard!!

        val result = coordinator.confirm(card.tokenId, fixture.chatContext, "改过的正文")

        assertEquals(MessagingState.EXPIRED, result.state)
        assertEquals("MESSAGE_BODY_CHANGED", result.errorCode)
        assertNull(result.sendRequest)
        assertNull(result.confirmationCard)
    }

    @Test
    fun changedScreenGenerationInvalidatesConfirmation() {
        val clock = MutableClock(1_000L)
        val fixture = Fixture(clock)
        val coordinator = coordinator(clock, fixture.store)
        val card = prepare(coordinator, fixture).confirmationCard!!
        val refreshedChat = fixture.store.publish(
            "com.tencent.mm",
            "com.tencent.mm:2",
            Fixture.chatRoot("张三"),
        )

        val result = coordinator.confirm(card.tokenId, refreshedChat, "晚上好")

        assertEquals(MessagingState.EXPIRED, result.state)
        assertEquals("SCREEN_CONTEXT_STALE", result.errorCode)
        assertNull(result.sendRequest)
    }

    @Test
    fun changedContactPageInvalidatesConfirmationThroughLiveStore() {
        val clock = MutableClock(1_000L)
        val fixture = Fixture(clock)
        val coordinator = coordinator(clock, fixture.store)
        val card = prepare(coordinator, fixture).confirmationCard!!
        val changedContact = fixture.store.publish(
            "com.tencent.mm",
            "com.tencent.mm:2",
            Fixture.chatRoot("王五"),
        )

        val result = coordinator.confirm(card.tokenId, changedContact, "晚上好")

        assertEquals(MessagingState.EXPIRED, result.state)
        assertEquals("SCREEN_CONTEXT_STALE", result.errorCode)
        assertNull(result.sendRequest)
    }

    @Test
    fun expiredTokenAndCancelBothRemoveConfirmationState() {
        val clock = MutableClock(1_000L)
        val fixture = Fixture(clock)
        val expiredCoordinator = coordinator(clock, fixture.store)
        val expiredCard = prepare(expiredCoordinator, fixture).confirmationCard!!
        clock.now = 61_000L

        val expired = expiredCoordinator.confirm(expiredCard.tokenId, fixture.chatContext, "晚上好")
        assertEquals(MessagingState.EXPIRED, expired.state)
        assertEquals("MESSAGE_CONFIRMATION_EXPIRED", expired.errorCode)
        assertNull(expired.confirmationCard)

        val cancelClock = MutableClock(1_000L)
        val cancelFixture = Fixture(cancelClock)
        val cancelCoordinator = coordinator(cancelClock, cancelFixture.store)
        val cancelCard = prepare(cancelCoordinator, cancelFixture).confirmationCard!!

        val cancelled = cancelCoordinator.cancel(cancelCard.tokenId)
        assertEquals(MessagingState.CANCELLED, cancelled.state)
        assertNull(cancelled.confirmationCard)
        assertNull(cancelled.pendingMessage)
        assertNull(cancelCoordinator.confirm(cancelCard.tokenId, cancelFixture.chatContext, "晚上好").sendRequest)
    }

    @Test
    fun processRestartClearsPendingMessageAndConfirmationWithoutCreatingARequest() {
        val clock = MutableClock(1_000L)
        val fixture = Fixture(clock)
        val coordinator = coordinator(clock, fixture.store)
        val card = prepare(coordinator, fixture).confirmationCard!!

        coordinator.invalidateOnProcessRestart()

        assertEquals(MessagingState.IDLE, coordinator.state)
        val result = coordinator.confirm(card.tokenId, fixture.chatContext, "晚上好")
        assertEquals("MESSAGE_CONFIRMATION_NOT_EXPECTED", result.errorCode)
        assertNull(result.pendingMessage)
        assertNull(result.sendRequest)
    }

    private fun prepare(
        coordinator: MessagingCoordinator,
        fixture: Fixture,
    ): MessagingCoordinatorResult {
        coordinator.start(
            MessagingRequest("com.tencent.mm", "张三", "晚上好"),
            fixture.listContext,
        )
        return coordinator.onChatOpened(fixture.chatContext)
    }

    private fun coordinator(clock: MutableClock, store: ScreenContextStore) = MessagingCoordinator(
        adapters = listOf(WeChatMessagingAdapter()),
        clockMs = { clock.now },
        contextStore = store,
    )

    private class MutableClock(var now: Long)

    private class Fixture(clock: MutableClock) {
        val store = ScreenContextStore(clockMs = { clock.now })
        val listContext: ScreenContext
        val chatContext: ScreenContext

        init {
            listContext = store.publish(
                "com.tencent.mm",
                "com.tencent.mm:1",
                contactListRoot(),
            )
            chatContext = store.publish(
                "com.tencent.mm",
                "com.tencent.mm:2",
                chatRoot("张三"),
            )
        }

        private fun contactListRoot() = ScreenNode(
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

        companion object {
            fun chatRoot(contact: String) = ScreenNode(
                id = "chat-root",
                role = "chat",
                text = contact,
                children = listOf(
                    ScreenNode(
                        id = "message-input",
                        role = "message_input",
                        contentDescription = "输入消息",
                    ),
                    ScreenNode(
                        id = "send-button",
                        role = "send_button",
                        text = "发送",
                        clickable = true,
                    ),
                ),
            )
        }
    }
}
