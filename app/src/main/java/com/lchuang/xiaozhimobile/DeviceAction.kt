package com.lchuang.xiaozhimobile

sealed interface DeviceAction {
    data class OpenApp(val name: String) : DeviceAction
    data class GoHome(val sourceApp: String?) : DeviceAction
    data class OpenMap(val preference: MapAppPreference) : DeviceAction
    data class SearchNearby(val keyword: String, val preference: MapAppPreference) : DeviceAction
    data class Navigate(val destination: String, val preference: MapAppPreference) : DeviceAction
    data class OpenWeb(val target: String) : DeviceAction
    data object MediaPlay : DeviceAction
    data object MediaPause : DeviceAction
    data object MediaStop : DeviceAction
    data object MediaNext : DeviceAction
    data object MediaPrevious : DeviceAction
    data class SetMediaVolume(val percent: Int) : DeviceAction
    data object MediaVolumeUp : DeviceAction
    data object MediaVolumeDown : DeviceAction
    data class SetFlashlight(val enabled: Boolean) : DeviceAction
}

sealed interface DeviceCommandPlan {
    data class Planned(val action: DeviceAction, val normalized: String) : DeviceCommandPlan
    data object Unhandled : DeviceCommandPlan
}

enum class CommandFailureKind {
    NO_SPEECH, ASR_EMPTY, UNSUPPORTED_COMMAND, APP_NOT_FOUND,
    EXECUTION_FAILED, AI_UNAVAILABLE, SAFETY_REJECTED
}

const val MAX_COMMAND_RECOGNITION_ATTEMPTS = 2

data class CommandRecoveryDecision(
    val spokenReply: String? = null,
    val continuation: String? = null,
    val resetAttempts: Boolean,
    val terminal: Boolean = false,
    val immediateListen: Boolean = false
)

object CommandRecoveryPolicy {
    fun forFailure(kind: CommandFailureKind, recognitionAttempts: Int): CommandRecoveryDecision = when (kind) {
        CommandFailureKind.NO_SPEECH -> CommandRecoveryDecision(
            resetAttempts = true,
            immediateListen = true
        )
        CommandFailureKind.ASR_EMPTY -> if (recognitionAttempts >= MAX_COMMAND_RECOGNITION_ATTEMPTS) {
            CommandRecoveryDecision(
                spokenReply = "刚才没有听清，我先退下了，有需要再叫我。",
                resetAttempts = true,
                terminal = true
            )
        } else {
            CommandRecoveryDecision(
                spokenReply = "刚才没有听清，请再说一次。",
                resetAttempts = false
            )
        }
        CommandFailureKind.UNSUPPORTED_COMMAND -> CommandRecoveryDecision(
            spokenReply = "这个指令我暂时还不会，你可以换一种说法。",
            resetAttempts = true
        )
        CommandFailureKind.APP_NOT_FOUND -> CommandRecoveryDecision(
            continuation = "请继续说。",
            resetAttempts = true
        )
        CommandFailureKind.EXECUTION_FAILED -> CommandRecoveryDecision(
            continuation = "请再试一次。",
            resetAttempts = true
        )
        CommandFailureKind.AI_UNAVAILABLE -> CommandRecoveryDecision(
            spokenReply = "AI 服务暂时不可用，请稍后再试。",
            resetAttempts = true
        )
        CommandFailureKind.SAFETY_REJECTED -> CommandRecoveryDecision(
            spokenReply = "这个操作不能执行。",
            resetAttempts = true
        )
    }
}

object CommandRecognitionQuality {
    fun failureKind(normalized: String, knownOneCharacterCommands: Set<String>): CommandFailureKind? {
        return if (normalized.isBlank() ||
            (normalized.length <= 1 && normalized !in knownOneCharacterCommands)
        ) {
            CommandFailureKind.ASR_EMPTY
        } else {
            null
        }
    }
}

data class DeviceExecutionResult(
    val success: Boolean,
    val code: String,
    val spokenResult: String,
    val notificationSummary: String,
    val failureKind: CommandFailureKind? = null,
    val actualPercent: Int? = null
)
