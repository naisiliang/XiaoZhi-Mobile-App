package com.lchuang.xiaozhimobile.recovery

/** Recovery levels are ordered from the least disruptive response to the most conservative one. */
enum class RecoveryLevel(val wireValue: String) {
    A_SAFE_RETRY("A"),
    B_DEGRADE("B"),
    C_ASK_USER("C"),
    D_STOP_ACTION_KEEP_SESSION("D"),
}

/** Describes whether repeating an operation could repeat an external side effect. */
enum class ExecutionSideEffect {
    NONE,
    REVERSIBLE,
    IRREVERSIBLE,
    UNKNOWN,
}

enum class ExecutionErrorDomain {
    VOICE,
    AI,
    NETWORK,
    ACCESSIBILITY,
    SCREEN,
    VISION,
    PLUGIN,
    MCP,
    APP,
    MESSAGE,
    ARTIFACT,
    IMAGE,
    SAFETY,
    CANCEL,
}

/** Stable error vocabulary shared by voice, tools, agents, plugins, and the conversation session. */
enum class ExecutionErrorCode(
    val domain: ExecutionErrorDomain,
    val defaultRecoveryLevel: RecoveryLevel,
    val defaultSideEffect: ExecutionSideEffect,
) {
    VOICE_NO_SPEECH(ExecutionErrorDomain.VOICE, RecoveryLevel.A_SAFE_RETRY, ExecutionSideEffect.NONE),
    VOICE_ASR_EMPTY(ExecutionErrorDomain.VOICE, RecoveryLevel.A_SAFE_RETRY, ExecutionSideEffect.NONE),

    AI_UNAVAILABLE(ExecutionErrorDomain.AI, RecoveryLevel.B_DEGRADE, ExecutionSideEffect.NONE),
    AI_TIMEOUT(ExecutionErrorDomain.AI, RecoveryLevel.A_SAFE_RETRY, ExecutionSideEffect.NONE),
    AI_RATE_LIMITED(ExecutionErrorDomain.AI, RecoveryLevel.B_DEGRADE, ExecutionSideEffect.NONE),
    AI_UNSUPPORTED_CAPABILITY(ExecutionErrorDomain.AI, RecoveryLevel.B_DEGRADE, ExecutionSideEffect.NONE),
    NETWORK_OFFLINE(ExecutionErrorDomain.NETWORK, RecoveryLevel.B_DEGRADE, ExecutionSideEffect.NONE),

    ACCESSIBILITY_DISABLED(ExecutionErrorDomain.ACCESSIBILITY, RecoveryLevel.C_ASK_USER, ExecutionSideEffect.NONE),
    ACCESSIBILITY_NODE_MISSING(ExecutionErrorDomain.ACCESSIBILITY, RecoveryLevel.C_ASK_USER, ExecutionSideEffect.NONE),
    SCREEN_CONTEXT_STALE(ExecutionErrorDomain.SCREEN, RecoveryLevel.C_ASK_USER, ExecutionSideEffect.NONE),
    SCREEN_AMBIGUOUS(ExecutionErrorDomain.SCREEN, RecoveryLevel.C_ASK_USER, ExecutionSideEffect.NONE),
    VISION_PERMISSION_REQUIRED(ExecutionErrorDomain.VISION, RecoveryLevel.C_ASK_USER, ExecutionSideEffect.NONE),
    VISION_MODEL_UNSUPPORTED(ExecutionErrorDomain.VISION, RecoveryLevel.B_DEGRADE, ExecutionSideEffect.NONE),

    PLUGIN_DISABLED(ExecutionErrorDomain.PLUGIN, RecoveryLevel.C_ASK_USER, ExecutionSideEffect.NONE),
    PLUGIN_PERMISSION_DENIED(ExecutionErrorDomain.PLUGIN, RecoveryLevel.C_ASK_USER, ExecutionSideEffect.NONE),
    MCP_UNAVAILABLE(ExecutionErrorDomain.MCP, RecoveryLevel.B_DEGRADE, ExecutionSideEffect.NONE),
    APP_NOT_FOUND(ExecutionErrorDomain.APP, RecoveryLevel.C_ASK_USER, ExecutionSideEffect.NONE),
    APP_UI_CHANGED(ExecutionErrorDomain.APP, RecoveryLevel.C_ASK_USER, ExecutionSideEffect.NONE),

    MESSAGE_CONTACT_AMBIGUOUS(ExecutionErrorDomain.MESSAGE, RecoveryLevel.C_ASK_USER, ExecutionSideEffect.NONE),
    MESSAGE_CONFIRMATION_EXPIRED(ExecutionErrorDomain.MESSAGE, RecoveryLevel.C_ASK_USER, ExecutionSideEffect.NONE),
    MESSAGE_SEND_FAILED(ExecutionErrorDomain.MESSAGE, RecoveryLevel.D_STOP_ACTION_KEEP_SESSION, ExecutionSideEffect.UNKNOWN),
    MESSAGE_SEND_UNVERIFIED(
        ExecutionErrorDomain.MESSAGE,
        RecoveryLevel.D_STOP_ACTION_KEEP_SESSION,
        ExecutionSideEffect.IRREVERSIBLE,
    ),

    ARTIFACT_GENERATION_FAILED(ExecutionErrorDomain.ARTIFACT, RecoveryLevel.B_DEGRADE, ExecutionSideEffect.NONE),
    ARTIFACT_INVALID(ExecutionErrorDomain.ARTIFACT, RecoveryLevel.B_DEGRADE, ExecutionSideEffect.NONE),
    IMAGE_GENERATION_FAILED(ExecutionErrorDomain.IMAGE, RecoveryLevel.B_DEGRADE, ExecutionSideEffect.NONE),

    SAFETY_BLOCKED(ExecutionErrorDomain.SAFETY, RecoveryLevel.D_STOP_ACTION_KEEP_SESSION, ExecutionSideEffect.NONE),
    USER_CANCELLED(ExecutionErrorDomain.CANCEL, RecoveryLevel.D_STOP_ACTION_KEEP_SESSION, ExecutionSideEffect.NONE),
}

/**
 * An execution failure is deliberately data-only: callers provide a safe user-facing message and
 * a stable code, while the coordinator decides whether any recovery action is permitted.
 */
data class ExecutionError(
    val code: ExecutionErrorCode,
    val userMessage: String,
    val recoveryLevel: RecoveryLevel = code.defaultRecoveryLevel,
    val sideEffect: ExecutionSideEffect = code.defaultSideEffect,
    val retryAttempt: Int = 0,
    val operationId: String? = null,
) {
    val domain: ExecutionErrorDomain
        get() = code.domain

    init {
        require(userMessage.isNotBlank()) { "userMessage must not be blank" }
        require(userMessage.length <= MAX_USER_MESSAGE_LENGTH) { "userMessage is too long" }
        require(retryAttempt >= 0) { "retryAttempt must be non-negative" }
        require(operationId == null || operationId.length <= MAX_OPERATION_ID_LENGTH) {
            "operationId is too long"
        }
    }

    companion object {
        private const val MAX_USER_MESSAGE_LENGTH = 512
        private const val MAX_OPERATION_ID_LENGTH = 128
    }
}
