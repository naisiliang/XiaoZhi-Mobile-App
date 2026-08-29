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

data class DeviceExecutionResult(
    val success: Boolean,
    val code: String,
    val spokenResult: String,
    val notificationSummary: String,
    val failureKind: CommandFailureKind? = null
)
