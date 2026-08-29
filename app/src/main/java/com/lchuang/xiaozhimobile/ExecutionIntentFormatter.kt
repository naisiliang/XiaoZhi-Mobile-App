package com.lchuang.xiaozhimobile

data class ExecutionCopy(
    val announcement: String,
    val runningNotification: String,
    val successNotification: String?,
    val failureNotification: String?,
    val finalSpoken: String?
)

class ExecutionIntentFormatter {
    fun announcement(action: DeviceAction): String = "${label(action)}正在执行"

    fun runningNotification(action: DeviceAction): String = announcement(action)

    fun finalCopy(action: DeviceAction, result: DeviceExecutionResult, continuation: String): ExecutionCopy {
        val announcement = announcement(action)
        return if (result.success) {
            val summary = if (action is DeviceAction.SetMediaVolume ||
                action == DeviceAction.MediaVolumeUp || action == DeviceAction.MediaVolumeDown) {
                val actual = result.actualPercent
                if (actual != null) "媒体音量${actual}%" else result.notificationSummary
            } else result.notificationSummary
            ExecutionCopy(announcement, announcement, "✅ 已成功执行：$summary", null, result.spokenResult)
        } else {
            ExecutionCopy(announcement, announcement, null, "❌ 执行失败：${label(action)}", "${result.spokenResult}。请再试一次。")
        }
    }

    private fun label(action: DeviceAction): String = when (action) {
        is DeviceAction.OpenApp -> "打开${action.name}"
        is DeviceAction.GoHome -> action.sourceApp?.let { "退出$it" } ?: "返回桌面"
        is DeviceAction.OpenMap -> "打开地图"
        is DeviceAction.SearchNearby -> "搜索附近${action.keyword}"
        is DeviceAction.Navigate -> "导航到${action.destination}"
        is DeviceAction.OpenWeb -> "打开网页${action.target}"
        DeviceAction.MediaPlay -> "播放音乐"
        DeviceAction.MediaPause -> "暂停播放"
        DeviceAction.MediaStop -> "停止播放"
        DeviceAction.MediaNext -> "下一首"
        DeviceAction.MediaPrevious -> "上一首"
        is DeviceAction.SetMediaVolume -> "调整媒体音量到百分之${chineseNumber(action.percent)}"
        DeviceAction.MediaVolumeUp -> "调高媒体音量"
        DeviceAction.MediaVolumeDown -> "调低媒体音量"
        is DeviceAction.SetFlashlight -> if (action.enabled) "打开手电筒" else "关闭手电筒"
    }

    private fun chineseNumber(value: Int): String {
        val number = value.coerceIn(0, 100)
        if (number == 100) return "一百"
        if (number < 10) return "零一二三四五六七八九"[number].toString()
        val tens = number / 10
        val ones = number % 10
        return if (tens == 1) "十${if (ones == 0) "" else "零一二三四五六七八九"[ones]}"
        else "零一二三四五六七八九"[tens].toString() + "十" + if (ones == 0) "" else "零一二三四五六七八九"[ones]
    }
}
