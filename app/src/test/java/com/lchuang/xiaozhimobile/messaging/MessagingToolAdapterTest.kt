package com.lchuang.xiaozhimobile.messaging

import com.lchuang.xiaozhimobile.safety.CentralSafetyPolicyEngine
import com.lchuang.xiaozhimobile.safety.PermissionBroker
import com.lchuang.xiaozhimobile.safety.ToolDecision
import com.lchuang.xiaozhimobile.safety.ToolInvocation
import com.lchuang.xiaozhimobile.tools.ToolDispatcher
import com.lchuang.xiaozhimobile.tools.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagingToolAdapterTest {
    @Test
    fun registryExposesOnlyOrdinaryTextMessagingDeclaration() {
        val adapter = MessagingToolAdapter()
        val definitions = adapter.definitions()

        assertEquals(listOf(MessagingToolAdapter.TOOL_NAME), definitions.map { it.name })
        assertEquals(
            setOf("packageName", "contactReference", "body"),
            definitions.single().properties.keys,
        )
        assertEquals(
            listOf("packageName", "contactReference", "body"),
            definitions.single().required,
        )
        assertEquals(
            definitions.single(),
            ToolRegistry.definitionFor(MessagingToolAdapter.TOOL_NAME),
        )
        assertTrue(ToolRegistry.definitions().any { it.name == MessagingToolAdapter.TOOL_NAME })
        assertTrue(ToolRegistry.definitions().none { it.name in setOf("send_image", "send_file", "send_voice", "manage_group") })
    }

    @Test
    fun adapterAcceptsOnlyStrictWeChatOrQqTextInvocation() {
        val adapter = MessagingToolAdapter()
        val accepted = adapter.resolve(
            ToolInvocation(
                MessagingToolAdapter.TOOL_NAME,
                mapOf(
                    "packageName" to "com.tencent.mm",
                    "contactReference" to "张三",
                    "body" to "晚上好",
                ),
            ),
        )

        assertEquals(
            MessagingToolResolution.Accepted(
                MessagingRequest("com.tencent.mm", "张三", "晚上好"),
            ),
            accepted,
        )
        assertEquals(
            MessagingToolResolution.Rejected("MESSAGE_UNSUPPORTED_PACKAGE"),
            adapter.resolve(
                ToolInvocation(
                    MessagingToolAdapter.TOOL_NAME,
                    mapOf(
                        "packageName" to "com.example.chat",
                        "contactReference" to "张三",
                        "body" to "晚上好",
                    ),
                ),
            ),
        )
        assertEquals(
            MessagingToolResolution.Rejected("MESSAGE_UNSUPPORTED_ARGUMENT"),
            adapter.resolve(
                ToolInvocation(
                    MessagingToolAdapter.TOOL_NAME,
                    mapOf(
                        "packageName" to "com.tencent.mm",
                        "contactReference" to "张三",
                        "body" to "晚上好",
                        "attachment" to "photo.jpg",
                    ),
                ),
            ),
        )
        assertEquals(
            MessagingToolResolution.Rejected("MESSAGE_ARGUMENTS_INVALID"),
            adapter.resolve(ToolInvocation(MessagingToolAdapter.TOOL_NAME)),
        )
    }

    @Test
    fun centralPolicyRequiresConfirmationBeforeAnyRegisteredExecutor() {
        var executorCalls = 0
        val dispatcher = ToolDispatcher(
            permissionBroker = PermissionBroker { true },
            policyEvaluator = CentralSafetyPolicyEngine()::evaluate,
            resultExecutors = mapOf(
                MessagingToolAdapter.TOOL_NAME to { _, _ -> executorCalls += 1 },
            ),
        )

        val result = dispatcher.dispatch(
            ToolInvocation(
                MessagingToolAdapter.TOOL_NAME,
                mapOf(
                    "packageName" to "com.tencent.mm",
                    "contactReference" to "张三",
                    "body" to "晚上好",
                ),
            ),
        )

        assertEquals(ToolDecision.CONFIRM, result.decision)
        assertEquals("CONFIRMATION_REQUIRED", result.code)
        assertEquals(0, executorCalls)
    }
}
