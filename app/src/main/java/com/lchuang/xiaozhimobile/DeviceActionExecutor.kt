package com.lchuang.xiaozhimobile

class DeviceActionExecutor(
    private val phone: PhoneController,
    private val appExitController: AppExitController
) {
    fun execute(action: DeviceAction, callback: (DeviceExecutionResult) -> Unit) {
        when (action) {
            is DeviceAction.OpenApp -> callback(appResult(action.name, phone.openApp(action.name)))
            is DeviceAction.GoHome -> callback(homeResult(action.sourceApp, appExitController.goHome()))
            is DeviceAction.OpenMap -> callback(mapResult(phone.openMap(action.preference)))
            is DeviceAction.SearchNearby -> phone.searchNearby(action.keyword, action.preference) { result ->
                callback(mapResult(result))
            }
            is DeviceAction.Navigate -> callback(mapResult(phone.navigate(action.destination, action.preference)))
            is DeviceAction.OpenWeb -> {
                val success = phone.openBrowser(action.target)
                callback(if (success) {
                    ok("OPEN_WEB_OK", "浏览器已打开", "打开浏览器")
                } else {
                    failed("OPEN_WEB_FAILED", "没有成功打开浏览器", "打开浏览器失败")
                })
            }
            DeviceAction.MediaPlay -> { phone.mediaPlay(); callback(ok("MEDIA_PLAY", "音乐已开始播放", "播放音乐")) }
            DeviceAction.MediaPause -> { phone.mediaPause(); callback(ok("MEDIA_PAUSE", "已暂停", "暂停播放")) }
            DeviceAction.MediaStop -> { phone.mediaStop(); callback(ok("MEDIA_STOP", "已停止播放", "停止播放")) }
            DeviceAction.MediaNext -> { phone.mediaNext(); callback(ok("MEDIA_NEXT", "已切换到下一首", "下一首")) }
            DeviceAction.MediaPrevious -> { phone.mediaPrevious(); callback(ok("MEDIA_PREVIOUS", "已切换到上一首", "上一首")) }
            is DeviceAction.SetMediaVolume -> callback(volumeResult(phone.setMediaVolumePercent(action.percent), "SET_VOLUME"))
            DeviceAction.MediaVolumeUp -> callback(volumeResult(phone.volumeUpVerified(), "VOLUME_UP"))
            DeviceAction.MediaVolumeDown -> callback(volumeResult(phone.volumeDownVerified(), "VOLUME_DOWN"))
            is DeviceAction.SetFlashlight -> {
                val success = phone.setFlashlight(action.enabled)
                val verb = if (action.enabled) "打开" else "关闭"
                callback(if (success) {
                    ok("FLASHLIGHT_${if (action.enabled) "ON" else "OFF"}", "手电筒已$verb", "${verb}手电筒")
                } else {
                    failed("FLASHLIGHT_FAILED", "没有成功${verb}手电筒", "${verb}手电筒失败")
                })
            }
        }
    }

    private fun homeResult(sourceApp: String?, result: AppExitController.HomeResult): DeviceExecutionResult {
        if (!result.success) return failed(result.code, "没有成功返回桌面", "返回桌面失败")
        return if (sourceApp.isNullOrBlank()) {
            ok(result.code, "已返回桌面", "返回桌面")
        } else {
            ok(result.code, "${sourceApp}已退出", "退出$sourceApp")
        }
    }

    private fun appResult(name: String, result: AppLauncher.AppLaunchResult): DeviceExecutionResult = when (result) {
        is AppLauncher.AppLaunchResult.Success -> ok("OPEN_APP_OK", "已打开${result.label}", "打开${result.label}")
        is AppLauncher.AppLaunchResult.Failure -> when (result.error) {
            AppLauncher.AppLaunchError.PACKAGE_NOT_INSTALLED,
            AppLauncher.AppLaunchError.PACKAGE_NOT_VISIBLE -> DeviceExecutionResult(
                false, "OPEN_APP_NOT_FOUND", "没有找到可启动的“$name”", "未找到$name", CommandFailureKind.APP_NOT_FOUND
            )
            AppLauncher.AppLaunchError.NO_LAUNCH_ACTIVITY,
            AppLauncher.AppLaunchError.START_ACTIVITY_FAILED -> failed(
                "OPEN_APP_FAILED", "找到了“$name”，但没有成功启动", "启动${name}失败"
            )
        }
    }

    private fun mapResult(result: MapController.MapActionResult): DeviceExecutionResult = if (result.success) {
        ok(result.code, result.message, result.message)
    } else {
        failed(result.code, result.message, result.message)
    }

    private fun volumeResult(result: PhoneController.MediaVolumeResult, code: String): DeviceExecutionResult {
        val actual = result.actualPercent.coerceIn(0, 100)
        val text = when (actual) {
            0 -> "媒体音量已经静音"
            100 -> "媒体音量已经调整到最大"
            else -> "媒体音量已经调整到${actual}%"
        }
        return if (result.success) {
            ok(code, text, "媒体音量${actual}%", actual)
        } else {
            DeviceExecutionResult(false, "${code}_PARTIAL", text, "媒体音量${actual}%", CommandFailureKind.EXECUTION_FAILED, actual)
        }
    }

    private fun ok(code: String, spoken: String, summary: String, actualPercent: Int? = null) =
        DeviceExecutionResult(true, code, spoken, summary, actualPercent = actualPercent)

    private fun failed(code: String, spoken: String, summary: String) =
        DeviceExecutionResult(false, code, spoken, summary, CommandFailureKind.EXECUTION_FAILED)
}
