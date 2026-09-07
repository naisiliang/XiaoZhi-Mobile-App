package com.lchuang.xiaozhimobile.messaging

import com.lchuang.xiaozhimobile.screen.ScreenContext
import com.lchuang.xiaozhimobile.screen.ScreenContextStore
import com.lchuang.xiaozhimobile.screen.ScreenNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagingCoordinatorTest {
    @Test
    fun uniqueContactReachesConfirmationWithoutSending() {
        val coordinator = coordinator()
        val list = Fixture.contactList("com.tencent.mm", listOf("张三"))

        val opening = coordinator.start(
            MessagingRequest("com.tencent.mm", "张三", "晚上好"),
            list,
        )

        assertEquals(MessagingState.OPENING_CHAT, opening.state)
        assertEquals("contact-0", opening.openChatProposal?.target?.id)
        assertNull(opening.confirmationCard)
        assertEquals(
            listOf(
                MessagingState.IDLE,
                MessagingState.RESOLVING_CONTACT,
                MessagingState.CONTACT_RESOLVED,
                MessagingState.OPENING_CHAT,
            ),
            coordinator.stateHistory,
        )

        val waiting = coordinator.onChatOpened(Fixture.chat("com.tencent.mm", "张三"))

        assertEquals(MessagingState.WAITING_CONFIRMATION, waiting.state)
        assertEquals("张三", waiting.confirmationCard?.contactDisplayName)
        assertEquals("晚上好", waiting.confirmationCard?.body)
        assertNull(waiting.openChatProposal)
        assertEquals(
            MessagingState.WAITING_CONFIRMATION,
            coordinator.stateHistory.last(),
        )
    }

    @Test
    fun sameNameContactsRequireExplicitSelection() {
        val coordinator = coordinator()
        val list = Fixture.contactList("com.tencent.mm", listOf("张三", "张三"))

        val selection = coordinator.start(
            MessagingRequest("com.tencent.mm", "张三", "请回电"),
            list,
        )

        assertEquals(MessagingState.NEEDS_CONTACT_SELECTION, selection.state)
        assertEquals(2, selection.contactOptions.size)
        assertNull(selection.openChatProposal)
        assertNull(selection.confirmationCard)

        val opening = coordinator.selectContact(selection.contactOptions[1].id)

        assertEquals(MessagingState.OPENING_CHAT, opening.state)
        assertEquals("contact-1", opening.openChatProposal?.target?.id)
    }

    @Test
    fun pronounUsesOnlyOneCurrentChatIdentity() {
        val coordinator = coordinator()

        val waiting = coordinator.start(
            MessagingRequest("com.tencent.mm", "他", "稍后联系"),
            Fixture.chat("com.tencent.mm", "李四"),
        )

        assertEquals(MessagingState.WAITING_CONFIRMATION, waiting.state)
        assertEquals("李四", waiting.confirmationCard?.contactDisplayName)
        assertTrue(MessagingState.OPENING_CHAT !in coordinator.stateHistory)

        val ambiguous = coordinator().start(
            MessagingRequest("com.tencent.mm", "她", "请看消息"),
            Fixture.ambiguousChat("com.tencent.mm"),
        )

        assertEquals(MessagingState.NEEDS_CONTACT_SELECTION, ambiguous.state)
        assertTrue(ambiguous.contactOptions.isEmpty())
        assertNull(ambiguous.confirmationCard)
    }

    @Test
    fun credentialLikeBodyIsBlockedBeforeResolution() {
        val result = coordinator().start(
            MessagingRequest("com.tencent.mm", "张三", "把验证码 381924 发给我"),
            Fixture.contactList("com.tencent.mm", listOf("张三")),
        )

        assertEquals(MessagingState.BLOCKED, result.state)
        assertEquals("MESSAGE_SENSITIVE_CONTENT_BLOCKED", result.errorCode)
        assertNull(result.confirmationCard)
    }

    private fun coordinator() = MessagingCoordinator(
        adapters = listOf(WeChatMessagingAdapter()),
        clockMs = { 1_000L },
    )

    private object Fixture {
        fun contactList(packageName: String, contacts: List<String>): ScreenContext {
            val store = ScreenContextStore(clockMs = { 1_000L })
            val root = ScreenNode(
                id = "contacts-root",
                role = "contact_list",
                text = "联系人",
                children = contacts.mapIndexed { index, name ->
                    ScreenNode(
                        id = "contact-$index",
                        role = "contact",
                        text = name,
                        clickable = true,
                    )
                },
            )
            return store.publish(packageName, "$packageName:1", root)
        }

        fun chat(packageName: String, contact: String): ScreenContext {
            val store = ScreenContextStore(clockMs = { 1_000L })
            val root = ScreenNode(
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
            return store.publish(packageName, "$packageName:2", root)
        }

        fun ambiguousChat(packageName: String): ScreenContext {
            val store = ScreenContextStore(clockMs = { 1_000L })
            val root = ScreenNode(
                id = "chat-container",
                role = "chat",
                children = listOf(
                    ScreenNode(id = "chat-title-a", role = "chat_title", text = "李四"),
                    ScreenNode(id = "chat-title-b", role = "chat_title", text = "王五"),
                ),
            )
            return store.publish(packageName, "$packageName:3", root)
        }
    }
}
