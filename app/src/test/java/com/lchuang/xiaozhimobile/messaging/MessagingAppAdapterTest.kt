package com.lchuang.xiaozhimobile.messaging

import com.lchuang.xiaozhimobile.accessibility.UiActionType
import com.lchuang.xiaozhimobile.screen.ScreenContext
import com.lchuang.xiaozhimobile.screen.ScreenContextStore
import com.lchuang.xiaozhimobile.screen.ScreenNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagingAppAdapterTest {
    @Test
    fun wechatOpensOneExactContactWithoutCoordinates() {
        val fixture = Fixture.contactList(
            packageName = "com.tencent.mm",
            contacts = listOf("张三"),
        )
        val adapter = WeChatMessagingAdapter()

        val matches = adapter.resolveContactCandidates(fixture.context, "张三")
        val proposal = adapter.proposeOpenChat(fixture.context, "张三")

        assertEquals(1, matches.size)
        assertNotNull(proposal)
        assertEquals(UiActionType.CLICK, proposal?.action)
        assertEquals(matches.single().id, proposal?.target?.id)
        assertEquals(fixture.context.generationId, proposal?.target?.generationId)
    }

    @Test
    fun sameNameContactsRemainAmbiguousAndAreNotOpened() {
        val fixture = Fixture.contactList(
            packageName = "com.tencent.mm",
            contacts = listOf("张三", "张三"),
        )
        val adapter = WeChatMessagingAdapter()

        assertEquals(2, adapter.resolveContactCandidates(fixture.context, "张三").size)
        assertNull(adapter.proposeOpenChat(fixture.context, "张三"))
    }

    @Test
    fun searchResultsAreResolvedAsSemanticContactRows() {
        val fixture = Fixture.searchResult(packageName = "com.tencent.mm", contact = "张三")
        val adapter = WeChatMessagingAdapter()

        assertEquals(1, adapter.resolveContactCandidates(fixture.context, "张三").size)
    }

    @Test
    fun currentChatAndMessageControlsAreSemanticOnly() {
        val fixture = Fixture.chat(packageName = "com.tencent.mm", contact = "李四")
        val adapter = WeChatMessagingAdapter()

        assertEquals("李四", adapter.currentChatCandidate(fixture.context)?.label)
        assertEquals("message-input", adapter.messageInputCandidate(fixture.context)?.id)
        assertEquals("send-button", adapter.sendButtonCandidate(fixture.context)?.id)
        assertTrue(adapter.extractSemanticObjects(fixture.context).all { it.id.isNotBlank() })
    }

    @Test
    fun multipleCurrentChatIdentitiesAreNotGuessed() {
        val store = ScreenContextStore(clockMs = { 1_000L })
        val root = ScreenNode(
            id = "chat-container",
            role = "chat",
            children = listOf(
                ScreenNode(id = "chat-title-a", role = "chat_title", text = "李四"),
                ScreenNode(id = "chat-title-b", role = "chat_title", text = "王五"),
            ),
        )
        val context = store.publish("com.tencent.mm", "com.tencent.mm:1", root)

        assertNull(WeChatMessagingAdapter().currentChatCandidate(context))
    }

    @Test
    fun qqAdapterIsPackageScoped() {
        val fixture = Fixture.chat(packageName = "com.tencent.mobileqq", contact = "王五")
        val adapter = QqMessagingAdapter()

        assertTrue(adapter.canHandle(fixture.context.packageName))
        assertFalse(adapter.canHandle("com.tencent.mm"))
        assertEquals("王五", adapter.currentChatCandidate(fixture.context)?.label)
    }

    private class Fixture private constructor(
        val context: ScreenContext,
    ) {
        companion object {
            fun contactList(packageName: String, contacts: List<String>): Fixture {
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
                return Fixture(store.publish(packageName, "$packageName:1", root))
            }

            fun chat(packageName: String, contact: String): Fixture {
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
                            className = "android.widget.EditText",
                        ),
                        ScreenNode(
                            id = "send-button",
                            role = "send_button",
                            text = "发送",
                            className = "android.widget.Button",
                            clickable = true,
                        ),
                    ),
                )
                return Fixture(store.publish(packageName, "$packageName:1", root))
            }

            fun searchResult(packageName: String, contact: String): Fixture {
                val store = ScreenContextStore(clockMs = { 1_000L })
                val root = ScreenNode(
                    id = "search-root",
                    role = "search_results",
                    text = "搜索结果",
                    children = listOf(
                        ScreenNode(
                            id = "search-row",
                            role = "row",
                            text = contact,
                            clickable = true,
                        ),
                    ),
                )
                return Fixture(store.publish(packageName, "$packageName:1", root))
            }
        }
    }
}
