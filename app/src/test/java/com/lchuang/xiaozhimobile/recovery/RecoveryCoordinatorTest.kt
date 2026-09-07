package com.lchuang.xiaozhimobile.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryCoordinatorTest {
    @Test
    fun `recovery levels are exactly the four approved levels`() {
        assertEquals(
            setOf(
                RecoveryLevel.A_SAFE_RETRY,
                RecoveryLevel.B_DEGRADE,
                RecoveryLevel.C_ASK_USER,
                RecoveryLevel.D_STOP_ACTION_KEEP_SESSION,
            ),
            RecoveryLevel.entries.toSet(),
        )
    }

    @Test
    fun `catalog covers every approved execution error category`() {
        val required = setOf(
            "VOICE_NO_SPEECH",
            "VOICE_ASR_EMPTY",
            "AI_UNAVAILABLE",
            "AI_TIMEOUT",
            "AI_RATE_LIMITED",
            "AI_UNSUPPORTED_CAPABILITY",
            "NETWORK_OFFLINE",
            "ACCESSIBILITY_DISABLED",
            "ACCESSIBILITY_NODE_MISSING",
            "SCREEN_CONTEXT_STALE",
            "SCREEN_AMBIGUOUS",
            "VISION_PERMISSION_REQUIRED",
            "VISION_MODEL_UNSUPPORTED",
            "PLUGIN_DISABLED",
            "PLUGIN_PERMISSION_DENIED",
            "MCP_UNAVAILABLE",
            "APP_NOT_FOUND",
            "APP_UI_CHANGED",
            "MESSAGE_CONTACT_AMBIGUOUS",
            "MESSAGE_CONFIRMATION_EXPIRED",
            "MESSAGE_SEND_FAILED",
            "MESSAGE_SEND_UNVERIFIED",
            "ARTIFACT_GENERATION_FAILED",
            "ARTIFACT_INVALID",
            "IMAGE_GENERATION_FAILED",
            "SAFETY_BLOCKED",
            "USER_CANCELLED",
        )

        assertTrue(required.all { name -> ExecutionErrorCode.entries.any { it.name == name } })
    }

    @Test
    fun `safe retry is bounded and retains the conversation`() {
        val coordinator = RecoveryCoordinator(maxAutomaticRetries = 2)
        val error = ExecutionError(
            code = ExecutionErrorCode.AI_TIMEOUT,
            userMessage = "AI 请求超时",
            recoveryLevel = RecoveryLevel.A_SAFE_RETRY,
            sideEffect = ExecutionSideEffect.NONE,
        )

        val first = coordinator.decide(error, RecoveryContext(automaticRetryCount = 0))
        val second = coordinator.decide(error, RecoveryContext(automaticRetryCount = 1))
        val exhausted = coordinator.decide(error, RecoveryContext(automaticRetryCount = 2))

        assertTrue(first is RecoveryDecision.Retry)
        assertTrue(second is RecoveryDecision.Retry)
        assertTrue(exhausted is RecoveryDecision.StopActionKeepSession)
        assertTrue(first.keepSession)
        assertTrue(second.keepSession)
        assertTrue(exhausted.keepSession)
        assertFalse(exhausted.autoRetry)
    }

    @Test
    fun `irreversible or unknown side effects can never be automatically retried`() {
        val coordinator = RecoveryCoordinator(maxAutomaticRetries = 3)

        listOf(
            ExecutionSideEffect.IRREVERSIBLE,
            ExecutionSideEffect.UNKNOWN,
        ).forEach { sideEffect ->
            val decision = coordinator.decide(
                ExecutionError(
                    code = ExecutionErrorCode.AI_TIMEOUT,
                    userMessage = "请求失败",
                    recoveryLevel = RecoveryLevel.A_SAFE_RETRY,
                    sideEffect = sideEffect,
                ),
                RecoveryContext(automaticRetryCount = 0, sideEffect = sideEffect),
            )

            assertTrue(decision is RecoveryDecision.StopActionKeepSession)
            assertTrue(decision.keepSession)
            assertFalse(decision.autoRetry)
        }

        val contextOnlyUnsafe = coordinator.decide(
            ExecutionError(
                code = ExecutionErrorCode.AI_TIMEOUT,
                userMessage = "请求失败",
                recoveryLevel = RecoveryLevel.A_SAFE_RETRY,
                sideEffect = ExecutionSideEffect.NONE,
            ),
            RecoveryContext(automaticRetryCount = 0, sideEffect = ExecutionSideEffect.IRREVERSIBLE),
        )
        assertTrue(contextOnlyUnsafe is RecoveryDecision.StopActionKeepSession)
        assertFalse(contextOnlyUnsafe.autoRetry)
    }

    @Test
    fun `unverified message send is always stop action with zero retry`() {
        val decision = RecoveryCoordinator().decide(
            ExecutionError(
                code = ExecutionErrorCode.MESSAGE_SEND_UNVERIFIED,
                userMessage = "消息发送结果无法确认",
                recoveryLevel = RecoveryLevel.A_SAFE_RETRY,
                sideEffect = ExecutionSideEffect.NONE,
            ),
            RecoveryContext(automaticRetryCount = 0),
        )

        assertEquals(RecoveryLevel.D_STOP_ACTION_KEEP_SESSION, decision.level)
        assertTrue(decision is RecoveryDecision.StopActionKeepSession)
        assertEquals(0, decision.nextRetryCount)
        assertFalse(decision.autoRetry)
        assertTrue(decision.keepSession)
    }

    @Test
    fun `degrade ask and stop decisions never terminate the conversation`() {
        val coordinator = RecoveryCoordinator()
        val cases = listOf(
            RecoveryLevel.B_DEGRADE,
            RecoveryLevel.C_ASK_USER,
            RecoveryLevel.D_STOP_ACTION_KEEP_SESSION,
        )

        cases.forEach { level ->
            val decision = coordinator.decide(
                ExecutionError(
                    code = ExecutionErrorCode.AI_UNAVAILABLE,
                    userMessage = "AI 暂不可用",
                    recoveryLevel = level,
                    sideEffect = ExecutionSideEffect.NONE,
                ),
            )

            when (level) {
                RecoveryLevel.B_DEGRADE -> assertTrue(decision is RecoveryDecision.Degrade)
                RecoveryLevel.C_ASK_USER -> assertTrue(decision is RecoveryDecision.AskUser)
                RecoveryLevel.D_STOP_ACTION_KEEP_SESSION -> {
                    assertTrue(decision is RecoveryDecision.StopActionKeepSession)
                }
                RecoveryLevel.A_SAFE_RETRY -> throw AssertionError("unexpected retry level")
            }
            assertEquals(level, decision.level)
            assertTrue(decision.keepSession)
            assertFalse(decision.autoRetry)
        }
    }

    @Test
    fun `coordinator rejects negative retry counts and zero retry budget`() {
        assertIllegalArgument {
            RecoveryCoordinator(maxAutomaticRetries = 0)
        }
        assertIllegalArgument {
            RecoveryContext(automaticRetryCount = -1)
        }
        assertIllegalArgument {
            ExecutionError(
                code = ExecutionErrorCode.AI_TIMEOUT,
                userMessage = "超时",
                retryAttempt = -1,
            )
        }
    }

    private fun assertIllegalArgument(action: () -> Unit) {
        try {
            action()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }
}
