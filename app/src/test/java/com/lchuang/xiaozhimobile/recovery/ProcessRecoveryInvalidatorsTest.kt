package com.lchuang.xiaozhimobile.recovery

import com.lchuang.xiaozhimobile.messaging.MessagingCoordinator
import com.lchuang.xiaozhimobile.messaging.MessagingRequest
import com.lchuang.xiaozhimobile.messaging.WeChatMessagingAdapter
import com.lchuang.xiaozhimobile.screen.ScreenContext
import com.lchuang.xiaozhimobile.screen.ScreenContextStore
import com.lchuang.xiaozhimobile.screen.ScreenNode
import com.lchuang.xiaozhimobile.vision.UserMediaProjectionConsent
import com.lchuang.xiaozhimobile.vision.VisionSessionAuthorization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessRecoveryInvalidatorsTest {
    @Test
    fun `runtime binding invalidates concrete message screen and vision owners`() {
        val screenStore = ScreenContextStore(clockMs = { 1_000L })
        val listContext = screenStore.publish(
            packageName = "com.tencent.mm",
            windowFingerprint = "com.tencent.mm:1",
            root = contactListRoot(),
        )
        val chatContext = screenStore.publish(
            packageName = "com.tencent.mm",
            windowFingerprint = "com.tencent.mm:2",
            root = chatRoot(),
        )
        val messaging = MessagingCoordinator(
            adapters = listOf(WeChatMessagingAdapter()),
            clockMs = { 1_000L },
            contextStore = screenStore,
        )
        messaging.start(MessagingRequest("com.tencent.mm", "张三", "晚上好"), listContext)
        val confirmation = messaging.onChatOpened(chatContext).confirmationCard
        assertTrue(confirmation != null)

        val vision = VisionSessionAuthorization(
            processInstanceId = "process-1",
            consentVerifier = { it.opaqueHandle == "approved" },
            clockMs = { 1_000L },
        )
        vision.beginSession("vision-1")
        val grant = vision.grantUserConsent("vision-1", UserMediaProjectionConsent("approved"))
        assertTrue(grant != null)

        val result = ProcessRecoveryInvalidators.forRuntime(messaging, screenStore, vision).invalidate()

        assertEquals(ProcessBoundResource.entries.toSet(), result.resources)
        assertTrue(result.failures.isEmpty())
        assertEquals(com.lchuang.xiaozhimobile.messaging.MessagingState.IDLE, messaging.state)
        assertNull(screenStore.currentIfMatches(chatContext))
        assertFalse(vision.isAuthorized("vision-1", grant))
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

    private fun chatRoot() = ScreenNode(
        id = "chat-root",
        role = "chat",
        text = "张三",
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
