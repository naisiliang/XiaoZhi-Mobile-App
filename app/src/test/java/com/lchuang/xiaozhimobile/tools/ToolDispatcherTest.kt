package com.lchuang.xiaozhimobile.tools

import com.lchuang.xiaozhimobile.safety.CentralSafetyPolicyEngine
import com.lchuang.xiaozhimobile.safety.PermissionBroker
import com.lchuang.xiaozhimobile.safety.ToolDecision
import com.lchuang.xiaozhimobile.safety.ToolInvocation
import com.lchuang.xiaozhimobile.ToolExecutionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolDispatcherTest {
    @Test
    fun productionResultExecutorRunsOnlyAfterPermissionAndPolicyAllow() {
        val events = mutableListOf<String>()
        val results = mutableListOf<ToolExecutionResult>()
        var executionCalls = 0
        val dispatcher = ToolDispatcher(
            permissionBroker = PermissionBroker { events += "permission"; true },
            policyEvaluator = { invocation ->
                events += "policy"
                CentralSafetyPolicyEngine().evaluate(invocation)
            },
            resultExecutors = mapOf("open_app" to { _, callback ->
                executionCalls += 1
                callback(ToolExecutionResult(true, "已打开微信", "OPEN_APP_OK"))
            })
        )

        val dispatch = dispatcher.dispatch(
            ToolInvocation("open_app", mapOf("name" to "微信")),
            results::add
        )

        assertEquals(listOf("permission", "policy"), events)
        assertEquals(ToolDecision.ALLOW, dispatch.decision)
        assertEquals("EXECUTED", dispatch.code)
        assertEquals(1, executionCalls)
        assertEquals(listOf(ToolExecutionResult(true, "已打开微信", "OPEN_APP_OK")), results)
    }

    @Test
    fun productionResultExecutorDoesNotRunForConfirmationOrBlock() {
        var executionCalls = 0
        val results = mutableListOf<ToolExecutionResult>()
        val dispatcher = ToolDispatcher(
            permissionBroker = PermissionBroker { true },
            policyEvaluator = CentralSafetyPolicyEngine()::evaluate,
            resultExecutors = mapOf(
                "send_text_message" to { _, _ -> executionCalls += 1 },
                "delete_file" to { _, _ -> executionCalls += 1 }
            )
        )

        val confirmation = dispatcher.dispatch(ToolInvocation("send_text_message"), results::add)
        val blocked = dispatcher.dispatch(ToolInvocation("delete_file"), results::add)

        assertEquals(ToolDecision.CONFIRM, confirmation.decision)
        assertEquals("CONFIRMATION_REQUIRED", confirmation.code)
        assertEquals(ToolDecision.BLOCK, blocked.decision)
        assertEquals("RESTRICTED_TOOL", blocked.code)
        assertEquals(0, executionCalls)
        assertEquals(
            listOf("CONFIRMATION_REQUIRED", "RESTRICTED_TOOL"),
            results.map { it.debugCode }
        )
    }
    @Test
    fun permissionIsCheckedBeforePolicyAndExecutor() {
        val events = mutableListOf<String>()
        var executorCalls = 0
        val dispatcher = ToolDispatcher(
            permissionBroker = PermissionBroker { events += "permission"; false },
            policyEvaluator = { invocation -> events += "policy"; CentralSafetyPolicyEngine().evaluate(invocation) },
            executors = mapOf("open_app" to { executorCalls++ })
        )

        val result = dispatcher.dispatch(ToolInvocation("open_app"))

        assertEquals("PERMISSION_DENIED", result.code)
        assertEquals(listOf("permission"), events)
        assertEquals(0, executorCalls)
    }

    @Test
    fun unknownToolAndMissingExecutorNeverAllow() {
        val dispatcher = ToolDispatcher(
            permissionBroker = PermissionBroker { true },
            policyEvaluator = CentralSafetyPolicyEngine()::evaluate,
            executors = emptyMap()
        )

        val unknown = dispatcher.dispatch(ToolInvocation("unknown"))
        val missing = dispatcher.dispatch(ToolInvocation("open_app"))

        assertEquals("UNKNOWN_TOOL", unknown.code)
        assertEquals(ToolDecision.BLOCK, unknown.decision)
        assertEquals("MISSING_EXECUTOR", missing.code)
        assertEquals(ToolDecision.BLOCK, missing.decision)
    }

    @Test
    fun confirmationAndDangerousToolsDoNotInvokeExecutors() {
        var executorCalls = 0
        val dispatcher = ToolDispatcher(
            permissionBroker = PermissionBroker { true },
            policyEvaluator = CentralSafetyPolicyEngine()::evaluate,
            executors = mapOf(
                "send_text_message" to { executorCalls++ },
                "delete_file" to { executorCalls++ }
            )
        )

        val confirmation = dispatcher.dispatch(ToolInvocation("send_text_message"))
        val dangerous = dispatcher.dispatch(ToolInvocation("delete_file"))

        assertEquals("CONFIRMATION_REQUIRED", confirmation.code)
        assertEquals(ToolDecision.CONFIRM, confirmation.decision)
        assertEquals("RESTRICTED_TOOL", dangerous.code)
        assertEquals(ToolDecision.BLOCK, dangerous.decision)
        assertEquals(0, executorCalls)
    }

    @Test
    fun allowedToolInvokesRegisteredExecutorOnce() {
        var executorCalls = 0
        val dispatcher = ToolDispatcher(
            permissionBroker = PermissionBroker { true },
            policyEvaluator = CentralSafetyPolicyEngine()::evaluate,
            executors = mapOf("open_app" to { executorCalls++ })
        )

        val result = dispatcher.dispatch(ToolInvocation("open_app"))

        assertEquals("EXECUTED", result.code)
        assertEquals(ToolDecision.ALLOW, result.decision)
        assertEquals(1, executorCalls)
        assertTrue(result.blockReason == null)
    }
}
