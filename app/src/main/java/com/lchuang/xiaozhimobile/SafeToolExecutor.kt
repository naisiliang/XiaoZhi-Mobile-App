package com.lchuang.xiaozhimobile

import android.net.Uri

sealed interface SafeToolPlan {
    data class Allowed(val action: DeviceAction) : SafeToolPlan
    data class Rejected(val result: ToolExecutionResult) : SafeToolPlan
}

class SafeToolExecutor(private val deviceActionExecutor: DeviceActionExecutor) {
    fun execute(call: AiToolCall, callback: (ToolExecutionResult) -> Unit) {
        when (val planned = plan(call)) {
            is SafeToolPlan.Allowed -> when (planned.action) {
                is DeviceAction.GoHome,
                is DeviceAction.OpenMap,
                DeviceAction.MediaStop -> callback(ToolExecutionResult(false, "该操作不在安全工具白名单中", "REJECTED_NOT_ALLOWED"))
                else -> deviceActionExecutor.execute(planned.action) { result ->
                    callback(ToolExecutionResult(result.success, result.spokenResult, result.code))
                }
            }
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
}
