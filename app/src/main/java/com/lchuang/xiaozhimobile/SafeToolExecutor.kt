package com.lchuang.xiaozhimobile

import android.net.Uri

sealed interface SafeToolPlan {
    data class Allowed(val action: DeviceAction) : SafeToolPlan
    data class Rejected(val result: ToolExecutionResult) : SafeToolPlan
}

class SafeToolExecutor(private val phone: PhoneController) {
    fun execute(call: AiToolCall, callback: (ToolExecutionResult) -> Unit) {
        when (val planned = plan(call)) {
            is SafeToolPlan.Allowed -> execute(planned.action, callback)
            is SafeToolPlan.Rejected -> callback(planned.result)
        }
    }

    fun plan(call: AiToolCall): SafeToolPlan = when (call.tool) {
        "open_app" -> {
            val name = stringArg(call, "name")
            if (name == null || looksLikePackageName(name)) rejected(invalidArgs("open_app"))
            else allowed(DeviceAction.OpenApp(name))
        }
        "navigate" -> {
            val destination = stringArg(call, "destination")
            if (destination == null) rejected(invalidArgs("navigate"))
            else allowed(DeviceAction.Navigate(destination, mapPreferenceArg(call)))
        }
        "search_nearby" -> {
            val keyword = stringArg(call, "keyword")
            if (keyword == null) rejected(invalidArgs("search_nearby"))
            else allowed(DeviceAction.SearchNearby(keyword, mapPreferenceArg(call)))
        }
        "open_web" -> {
            val value = stringArg(call, "query_or_url")
            if (value == null || hasRejectedWebScheme(value)) rejected(if (value == null) invalidArgs("open_web") else rejectedScheme())
            else allowed(DeviceAction.OpenWeb(value))
        }
        "media_play" -> allowed(DeviceAction.MediaPlay)
        "media_pause" -> allowed(DeviceAction.MediaPause)
        "media_next" -> allowed(DeviceAction.MediaNext)
        "media_previous" -> allowed(DeviceAction.MediaPrevious)
        "volume_up" -> allowed(DeviceAction.MediaVolumeUp)
        "volume_down" -> allowed(DeviceAction.MediaVolumeDown)
        "set_volume" -> {
            val percent = intArg(call, "percent")
            if (percent == null || percent !in 0..100) rejected(invalidArgs("set_volume"))
            else allowed(DeviceAction.SetMediaVolume(percent))
        }
        "flashlight_on" -> allowed(DeviceAction.SetFlashlight(true))
        "flashlight_off" -> allowed(DeviceAction.SetFlashlight(false))
        else -> rejected(ToolExecutionResult(false, "该操作不在安全工具白名单中", "REJECTED_NOT_ALLOWED"))
    }

    private fun execute(action: DeviceAction, callback: (ToolExecutionResult) -> Unit) {
        when (action) {
            is DeviceAction.OpenApp -> {
                when (val result = phone.openApp(action.name)) {
                    is AppLauncher.AppLaunchResult.Success -> callback(ToolExecutionResult(true, "已打开${result.label}", "OPEN_APP_OK"))
                    is AppLauncher.AppLaunchResult.Failure -> callback(ToolExecutionResult(false, "没有成功打开${action.name}", "OPEN_APP_${result.error.name}"))
                }
            }
            is DeviceAction.Navigate -> {
                val result = phone.navigate(action.destination, action.preference)
                callback(ToolExecutionResult(result.success, result.message, result.code))
            }
            is DeviceAction.SearchNearby -> phone.searchNearby(action.keyword, action.preference) { result ->
                callback(ToolExecutionResult(result.success, result.message, result.code))
            }
            is DeviceAction.OpenWeb -> {
                val ok = phone.openBrowser(action.target)
                callback(ToolExecutionResult(ok, if (ok) "浏览器已打开" else "没有成功打开", if (ok) "OPEN_WEB_OK" else "OPEN_WEB_FAILED"))
            }
            DeviceAction.MediaPlay -> { phone.mediaPlay(); callback(ok("已播放", "MEDIA_PLAY")) }
            DeviceAction.MediaPause -> { phone.mediaPause(); callback(ok("已暂停", "MEDIA_PAUSE")) }
            DeviceAction.MediaNext -> { phone.mediaNext(); callback(ok("已切换到下一首", "MEDIA_NEXT")) }
            DeviceAction.MediaPrevious -> { phone.mediaPrevious(); callback(ok("已切换到上一首", "MEDIA_PREVIOUS")) }
            DeviceAction.MediaVolumeUp -> callback(volumeToolResult(phone.volumeUpVerified(), "VOLUME_UP"))
            DeviceAction.MediaVolumeDown -> callback(volumeToolResult(phone.volumeDownVerified(), "VOLUME_DOWN"))
            is DeviceAction.SetMediaVolume -> callback(volumeToolResult(phone.setMediaVolumePercent(action.percent), "SET_VOLUME"))
            is DeviceAction.SetFlashlight -> {
                val success = phone.setFlashlight(action.enabled)
                val verb = if (action.enabled) "打开" else "关闭"
                callback(ToolExecutionResult(success, if (success) "手电筒已$verb" else "没有成功${verb}手电筒", if (success) "FLASHLIGHT_${if (action.enabled) "ON" else "OFF"}" else "FLASHLIGHT_FAILED"))
            }
            is DeviceAction.GoHome,
            is DeviceAction.OpenMap,
            DeviceAction.MediaStop -> callback(ToolExecutionResult(false, "该操作不在安全工具白名单中", "REJECTED_NOT_ALLOWED"))
        }
    }

    private fun allowed(action: DeviceAction) = SafeToolPlan.Allowed(action)
    private fun rejected(result: ToolExecutionResult) = SafeToolPlan.Rejected(result)
    private fun hasRejectedWebScheme(value: String): Boolean {
        val lower = value.trim().lowercase()
        val dangerous = listOf("javascript:", "file:", "content:", "intent:")
        if (dangerous.any(lower::startsWith)) return true
        val scheme = runCatching { Uri.parse(value).scheme?.lowercase() }.getOrNull()
        return scheme != null && scheme !in setOf("http", "https")
    }

    private fun rejectedScheme() = ToolExecutionResult(false, "不支持该链接类型", "REJECTED_SCHEME")

    private fun volumeToolResult(result: PhoneController.MediaVolumeResult, code: String): ToolExecutionResult {
        val actual = result.actualPercent.coerceIn(0, 100)
        val text = when (actual) {
            0 -> "媒体音量已经静音"
            100 -> "媒体音量已经调整到最大"
            else -> "媒体音量已经调整到${actual}%"
        }
        return ToolExecutionResult(result.success, text, if (result.success) code else "${code}_PARTIAL")
    }

    private fun stringArg(call: AiToolCall, name: String): String? = (call.args[name] as? String)?.trim()?.takeIf { it.isNotBlank() }

    private fun intArg(call: AiToolCall, name: String): Int? = when (val value = call.args[name]) {
        is Int -> value
        is Long -> value.toInt()
        is Double -> if (value % 1.0 == 0.0) value.toInt() else null
        is Number -> value.toInt()
        else -> null
    }

    private fun mapPreferenceArg(call: AiToolCall): MapAppPreference = when (stringArg(call, "mapApp")?.lowercase()) {
        "高德地图", "高德", "amap" -> MapAppPreference.AMAP
        "百度地图", "百度", "baidu" -> MapAppPreference.BAIDU
        "system", "系统", "系统地图" -> MapAppPreference.SYSTEM
        "auto", "自动", null -> MapAppPreference.AUTO
        else -> MapAppPreference.AUTO
    }

    private fun looksLikePackageName(value: String): Boolean = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+){2,}$").matches(value)
    private fun invalidArgs(tool: String) = ToolExecutionResult(false, "指令参数不完整", "INVALID_ARGS_$tool")
    private fun ok(text: String, code: String) = ToolExecutionResult(true, text, code)
}
