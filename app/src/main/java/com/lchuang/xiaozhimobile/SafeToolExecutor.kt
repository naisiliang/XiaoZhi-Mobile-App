package com.lchuang.xiaozhimobile

import android.net.Uri

class SafeToolExecutor(private val phone: PhoneController) {
    fun execute(call: AiToolCall, callback: (ToolExecutionResult) -> Unit) {
        when (call.tool) {
            "open_app" -> {
                val name = stringArg(call, "name")
                if (name == null || looksLikePackageName(name)) return callback(invalidArgs("open_app"))
                when (val result = phone.openApp(name)) {
                    is AppLauncher.AppLaunchResult.Success -> callback(ToolExecutionResult(true, "正在打开${result.label}", "OPEN_APP_OK"))
                    is AppLauncher.AppLaunchResult.Failure -> callback(ToolExecutionResult(false, "没有成功打开$name", "OPEN_APP_${result.error.name}"))
                }
            }
            "navigate" -> {
                val destination = stringArg(call, "destination") ?: return callback(invalidArgs("navigate"))
                val result = phone.navigate(destination, mapPreferenceArg(call))
                callback(ToolExecutionResult(result.success, result.message, result.code))
            }
            "search_nearby" -> {
                val keyword = stringArg(call, "keyword") ?: return callback(invalidArgs("search_nearby"))
                phone.searchNearby(keyword, mapPreferenceArg(call)) { result ->
                    callback(ToolExecutionResult(result.success, result.message, result.code))
                }
            }
            "open_web" -> {
                val value = stringArg(call, "query_or_url") ?: return callback(invalidArgs("open_web"))
                val lower = value.trim().lowercase()
                val dangerous = listOf("javascript:", "file:", "content:", "intent:")
                if (dangerous.any(lower::startsWith)) return callback(ToolExecutionResult(false, "不支持该链接类型", "REJECTED_SCHEME"))
                val scheme = runCatching { Uri.parse(value).scheme?.lowercase() }.getOrNull()
                if (scheme != null && scheme !in setOf("http", "https")) {
                    return callback(ToolExecutionResult(false, "不支持该链接类型", "REJECTED_SCHEME"))
                }
                val ok = phone.openBrowser(value)
                callback(ToolExecutionResult(ok, if (ok) "正在打开" else "没有成功打开", if (ok) "OPEN_WEB_OK" else "OPEN_WEB_FAILED"))
            }
            "media_play" -> { phone.mediaPlay(); callback(ok("已播放", "MEDIA_PLAY")) }
            "media_pause" -> { phone.mediaPause(); callback(ok("已暂停", "MEDIA_PAUSE")) }
            "media_next" -> { phone.mediaNext(); callback(ok("下一首", "MEDIA_NEXT")) }
            "media_previous" -> { phone.mediaPrevious(); callback(ok("上一首", "MEDIA_PREVIOUS")) }
            "volume_up" -> { phone.volumeUp(); callback(ok("已调大", "VOLUME_UP")) }
            "volume_down" -> { phone.volumeDown(); callback(ok("已调小", "VOLUME_DOWN")) }
            "set_volume" -> {
                val percent = intArg(call, "percent")
                if (percent == null || percent !in 0..100) return callback(invalidArgs("set_volume"))
                phone.setMediaVolume(percent)
                callback(ok("音量已设置为$percent%", "SET_VOLUME"))
            }
            "flashlight_on" -> {
                val success = phone.setFlashlight(true)
                callback(ToolExecutionResult(success, if (success) "手电筒已打开" else "没有成功打开手电筒", if (success) "FLASHLIGHT_ON" else "FLASHLIGHT_FAILED"))
            }
            "flashlight_off" -> {
                val success = phone.setFlashlight(false)
                callback(ToolExecutionResult(success, if (success) "手电筒已关闭" else "没有成功关闭手电筒", if (success) "FLASHLIGHT_OFF" else "FLASHLIGHT_FAILED"))
            }
            else -> callback(ToolExecutionResult(false, "该操作不在安全工具白名单中", "REJECTED_NOT_ALLOWED"))
        }
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
