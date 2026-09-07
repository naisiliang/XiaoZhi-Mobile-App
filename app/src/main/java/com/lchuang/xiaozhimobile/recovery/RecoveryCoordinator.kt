package com.lchuang.xiaozhimobile.recovery

data class RecoveryContext(
    val automaticRetryCount: Int = 0,
    val sideEffect: ExecutionSideEffect = ExecutionSideEffect.NONE,
) {
    init {
        require(automaticRetryCount >= 0) { "automaticRetryCount must be non-negative" }
    }
}

/**
 * A recovery decision never exits the conversation. A retry is the only decision that authorizes
 * another execution, and it is bounded and side-effect checked by RecoveryCoordinator.
 */
sealed interface RecoveryDecision {
    val level: RecoveryLevel
    val keepSession: Boolean
    val autoRetry: Boolean
    val nextRetryCount: Int

    data class Retry(
        val retryCount: Int,
        val maxRetries: Int,
        val reason: String,
    ) : RecoveryDecision {
        init {
            require(maxRetries >= 1) { "maxRetries must be positive" }
            require(retryCount in 1..maxRetries) { "retryCount must be within the retry budget" }
        }

        override val level: RecoveryLevel = RecoveryLevel.A_SAFE_RETRY
        override val keepSession: Boolean = true
        override val autoRetry: Boolean = true
        override val nextRetryCount: Int = retryCount
    }

    data class Degrade(
        val fallbackHint: String,
        val reason: String,
    ) : RecoveryDecision {
        override val level: RecoveryLevel = RecoveryLevel.B_DEGRADE
        override val keepSession: Boolean = true
        override val autoRetry: Boolean = false
        override val nextRetryCount: Int = 0
    }

    data class AskUser(
        val prompt: String,
        val reason: String,
    ) : RecoveryDecision {
        override val level: RecoveryLevel = RecoveryLevel.C_ASK_USER
        override val keepSession: Boolean = true
        override val autoRetry: Boolean = false
        override val nextRetryCount: Int = 0
    }

    data class StopActionKeepSession(
        val userMessage: String,
        val reason: String,
    ) : RecoveryDecision {
        override val level: RecoveryLevel = RecoveryLevel.D_STOP_ACTION_KEEP_SESSION
        override val keepSession: Boolean = true
        override val autoRetry: Boolean = false
        override val nextRetryCount: Int = 0
    }
}

class RecoveryCoordinator(
    private val maxAutomaticRetries: Int = DEFAULT_MAX_AUTOMATIC_RETRIES,
) {
    init {
        require(maxAutomaticRetries >= 1) { "maxAutomaticRetries must be positive" }
    }

    fun decide(
        error: ExecutionError,
        context: RecoveryContext = RecoveryContext(
            automaticRetryCount = error.retryAttempt,
            sideEffect = error.sideEffect,
        ),
    ): RecoveryDecision {
        if (error.code == ExecutionErrorCode.MESSAGE_SEND_UNVERIFIED) {
            return stop(error, "message send result is unverified; manual verification is required")
        }

        if (hasUnsafeSideEffect(error.sideEffect, context.sideEffect)) {
            return stop(error, "automatic retry is forbidden for an irreversible or unknown side effect")
        }

        return when (error.recoveryLevel) {
            RecoveryLevel.A_SAFE_RETRY -> if (context.automaticRetryCount < maxAutomaticRetries) {
                RecoveryDecision.Retry(
                    retryCount = context.automaticRetryCount + 1,
                    maxRetries = maxAutomaticRetries,
                    reason = "safe retry ${context.automaticRetryCount + 1} of $maxAutomaticRetries",
                )
            } else {
                stop(error, "automatic retry limit reached")
            }

            RecoveryLevel.B_DEGRADE -> RecoveryDecision.Degrade(
                fallbackHint = fallbackHint(error.code),
                reason = "degrade instead of repeating the failed operation",
            )

            RecoveryLevel.C_ASK_USER -> RecoveryDecision.AskUser(
                prompt = error.userMessage,
                reason = "user input is required before the action can continue",
            )

            RecoveryLevel.D_STOP_ACTION_KEEP_SESSION -> stop(
                error,
                "stop the failed action while preserving the conversation session",
            )
        }
    }

    private fun stop(error: ExecutionError, reason: String): RecoveryDecision.StopActionKeepSession =
        RecoveryDecision.StopActionKeepSession(
            userMessage = error.userMessage,
            reason = reason,
        )

    private fun hasUnsafeSideEffect(
        errorSideEffect: ExecutionSideEffect,
        contextSideEffect: ExecutionSideEffect,
    ): Boolean = errorSideEffect == ExecutionSideEffect.IRREVERSIBLE ||
        errorSideEffect == ExecutionSideEffect.UNKNOWN ||
        contextSideEffect == ExecutionSideEffect.IRREVERSIBLE ||
        contextSideEffect == ExecutionSideEffect.UNKNOWN

    private fun fallbackHint(code: ExecutionErrorCode): String = when (code.domain) {
        ExecutionErrorDomain.VOICE -> "重新进入本地监听"
        ExecutionErrorDomain.AI,
        ExecutionErrorDomain.NETWORK,
        ExecutionErrorDomain.MCP,
        -> "保留会话并使用可用的本地能力"
        ExecutionErrorDomain.VISION,
        ExecutionErrorDomain.SCREEN,
        ExecutionErrorDomain.ACCESSIBILITY,
        -> "保留会话并等待用户重新授权或提供上下文"
        ExecutionErrorDomain.PLUGIN -> "保留会话并停用当前扩展"
        ExecutionErrorDomain.APP -> "保留会话并请求用户确认目标应用"
        ExecutionErrorDomain.MESSAGE -> "保留会话并等待用户确认消息状态"
        ExecutionErrorDomain.ARTIFACT,
        ExecutionErrorDomain.IMAGE,
        -> "保留会话并报告生成失败"
        ExecutionErrorDomain.SAFETY,
        ExecutionErrorDomain.CANCEL,
        -> "保留会话，不执行被拒绝或取消的动作"
    }

    companion object {
        const val DEFAULT_MAX_AUTOMATIC_RETRIES = 1
    }
}
