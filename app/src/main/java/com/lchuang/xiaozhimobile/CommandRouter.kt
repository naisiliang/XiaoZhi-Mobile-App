package com.lchuang.xiaozhimobile

class CommandRouter(private val phone: PhoneController) {
    private val volumeParser = VolumeCommandParser()
    data class Result(val handled: Boolean, val reply: String = "", val success: Boolean = true)

    fun handle(raw: String): Result {
        return when (val commandPlan = plan(raw)) {
            DeviceCommandPlan.Unhandled -> Result(false)
            is DeviceCommandPlan.Planned -> execute(commandPlan.action)
        }
    }

    fun plan(raw: String): DeviceCommandPlan {
        val text = VoiceCommandNormalizer.normalize(raw)
        if (text.isBlank()) return DeviceCommandPlan.Unhandled

        fun planned(action: DeviceAction) = DeviceCommandPlan.Planned(action, text)

        if (goHomeOnly.matches(text)) return planned(DeviceAction.GoHome(null))

        when (val volume = volumeParser.parse(text)) {
            is VolumeAction.SetPercent -> return planned(DeviceAction.SetMediaVolume(volume.percent))
            VolumeAction.StepUp -> return planned(DeviceAction.MediaVolumeUp)
            VolumeAction.StepDown -> return planned(DeviceAction.MediaVolumeDown)
            VolumeAction.Unhandled -> Unit
        }

        when {
            containsAny(text, "暂停音乐", "暂停播放", "音乐暂停", "暂停歌曲", "停一下音乐", "暂停一下音乐") -> return planned(DeviceAction.MediaPause)
            containsAny(text, "停止音乐", "停止播放", "停止歌曲", "音乐停止", "关闭音乐", "关掉音乐", "把音乐停掉", "把音乐关掉") -> return planned(DeviceAction.MediaStop)
            containsAny(text, "继续播放", "继续音乐", "播放音乐", "开始播放", "放音乐", "播放一下音乐", "放一下音乐", "来首歌", "来一首歌") -> return planned(DeviceAction.MediaPlay)
            containsAny(text, "下一首", "下一曲", "切下一首") -> return planned(DeviceAction.MediaNext)
            containsAny(text, "上一首", "上一曲", "切上一首") -> return planned(DeviceAction.MediaPrevious)
            containsAny(text, "打开手电筒", "开启手电筒", "开手电筒") -> return planned(DeviceAction.SetFlashlight(true))
            containsAny(text, "关闭手电筒", "关掉手电筒", "关手电筒") -> return planned(DeviceAction.SetFlashlight(false))
        }

        val prefixExitMatch = appExitPrefix.matchEntire(text)
        if (prefixExitMatch != null) {
            val target = exitTarget(prefixExitMatch.groupValues[1])
            if (target != null) return planned(DeviceAction.GoHome(target))
        }
        val shortPrefixExitMatch = appExitShortPrefix.matchEntire(text)
        if (shortPrefixExitMatch != null) {
            val target = exitTarget(shortPrefixExitMatch.groupValues[1])
            if (target != null) return planned(DeviceAction.GoHome(target))
        }
        val naturalExitMatch = appExitNatural.matchEntire(text)
        if (naturalExitMatch != null) {
            val target = exitTarget(naturalExitMatch.groupValues[1])
            if (target != null) return planned(DeviceAction.GoHome(target))
        }

        if (containsAny(text, "打开微信", "启动微信", "打开威信", "启动威信", "进入微信", "打开一下微信")) {
            return planned(DeviceAction.OpenApp("微信"))
        }
        if (containsAny(text, "打开qq", "启动qq", "打开q q", "启动q q", "打开扣扣", "启动扣扣", "进入qq", "打开一下qq")) {
            return planned(DeviceAction.OpenApp("QQ"))
        }

        if (containsAny(text, "打开高德导航", "打开高德地图")) return planned(DeviceAction.OpenMap(MapAppPreference.AMAP))
        if (containsAny(text, "打开百度地图")) return planned(DeviceAction.OpenMap(MapAppPreference.BAIDU))

        val nearby = Regex("(?:用(高德|百度)(?:地图)?(?:帮我)?|帮我)?(?:找|搜索)?(?:一下)?附近(?:的)?(.+)|附近(?:帮我)?(?:找|搜索)?(.+)").find(text)
        if (nearby != null) {
            val keyword = (nearby.groupValues.getOrNull(2).orEmpty() + nearby.groupValues.getOrNull(3).orEmpty()).trim()
            if (keyword.isNotBlank()) {
                val preference = when (nearby.groupValues.getOrNull(1).orEmpty()) {
                    "高德" -> MapAppPreference.AMAP
                    "百度" -> MapAppPreference.BAIDU
                    else -> MapAppPreference.AUTO
                }
                return planned(DeviceAction.SearchNearby(keyword, preference))
            }
        }

        val explicitNavigation = Regex("用(高德|百度)(?:地图)?导航(?:到|去)?(.+)").find(text)
        if (explicitNavigation != null) {
            val preference = if (explicitNavigation.groupValues[1] == "高德") MapAppPreference.AMAP else MapAppPreference.BAIDU
            val destination = explicitNavigation.groupValues[2].trim()
            if (destination.isNotBlank()) return planned(DeviceAction.Navigate(destination, preference))
        }

        val navigation = Regex("(?:导航到|导航去|带我去)(.+)").find(text)
        if (navigation != null) {
            val destination = navigation.groupValues[1].trim()
            if (destination.length >= 2) return planned(DeviceAction.Navigate(destination, MapAppPreference.AUTO))
        }

        val browser = Regex("(?:浏览器打开|打开网页|访问)(.+)").find(text)
        if (browser != null) {
            val target = browser.groupValues[1].trim()
            if (target.isNotBlank()) return planned(DeviceAction.OpenWeb(target))
        }

        val appMatch = Regex("(?:打开|启动|进入|运行)(.+?)(?:app|应用|软件)?$").find(text)
        if (appMatch != null) {
            val name = appMatch.groupValues[1].trim().removeSuffix("app").removeSuffix("应用").removeSuffix("软件").trim()
            if (name.isNotBlank()) return planned(DeviceAction.OpenApp(name))
        }

        return DeviceCommandPlan.Unhandled
    }

    private fun execute(action: DeviceAction): Result = when (action) {
        is DeviceAction.OpenApp -> appResult(action.name, phone.openApp(action.name))
        is DeviceAction.OpenMap -> phone.openMap(action.preference).let { Result(true, it.message, it.success) }
        is DeviceAction.SearchNearby -> {
            phone.searchNearby(action.keyword, action.preference) { }
            Result(true, "已打开地图搜索附近的${action.keyword}", true)
        }
        is DeviceAction.Navigate -> phone.navigate(action.destination, action.preference).let { Result(true, it.message, it.success) }
        is DeviceAction.OpenWeb -> {
            val ok = phone.openBrowser(action.target)
            Result(true, if (ok) "浏览器已打开" else "没有成功打开浏览器", ok)
        }
        DeviceAction.MediaPlay -> { phone.mediaPlay(); Result(true, "音乐已开始播放") }
        DeviceAction.MediaPause -> { phone.mediaPause(); Result(true, "已暂停") }
        DeviceAction.MediaStop -> { phone.mediaStop(); Result(true, "已停止播放") }
        DeviceAction.MediaNext -> { phone.mediaNext(); Result(true, "已切换到下一首") }
        DeviceAction.MediaPrevious -> { phone.mediaPrevious(); Result(true, "已切换到上一首") }
        is DeviceAction.SetMediaVolume -> volumeResult(phone.setMediaVolumePercent(action.percent))
        DeviceAction.MediaVolumeUp -> volumeResult(phone.volumeUpVerified(), step = "up")
        DeviceAction.MediaVolumeDown -> volumeResult(phone.volumeDownVerified(), step = "down")
        is DeviceAction.SetFlashlight -> {
            val ok = phone.setFlashlight(action.enabled)
            val changed = if (action.enabled) "打开" else "关闭"
            Result(true, if (ok) "手电筒已${changed}" else "没有成功${changed}手电筒", ok)
        }
        is DeviceAction.GoHome -> Result(false)
    }

    private fun volumeResult(result: PhoneController.MediaVolumeResult, step: String? = null): Result {
        val actual = result.actualPercent.coerceIn(0, 100)
        if (!result.success) {
            return Result(true, "媒体音量当前是${actual}%，没有完全调整到目标值", false)
        }
        val reply = when {
            actual == 0 -> "媒体音量已经静音"
            actual == 100 -> "媒体音量已经调整到最大"
            step == "up" -> "媒体音量已调大，现在是${actual}%"
            step == "down" -> "媒体音量已调小，现在是${actual}%"
            else -> "媒体音量已经调整到${actual}%"
        }
        return Result(true, reply, true)
    }

    private fun appResult(requested: String, result: AppLauncher.AppLaunchResult): Result = when (result) {
        is AppLauncher.AppLaunchResult.Success -> Result(true, "已打开${result.label}", true)
        is AppLauncher.AppLaunchResult.Failure -> when (result.error) {
            AppLauncher.AppLaunchError.PACKAGE_NOT_VISIBLE,
            AppLauncher.AppLaunchError.PACKAGE_NOT_INSTALLED -> Result(true, "没有找到可启动的“$requested”", false)
            AppLauncher.AppLaunchError.NO_LAUNCH_ACTIVITY,
            AppLauncher.AppLaunchError.START_ACTIVITY_FAILED -> Result(true, "找到了“$requested”，但没有成功启动", false)
        }
    }

    fun looksLikeDeviceCommand(raw: String): Boolean {
        val text = VoiceCommandNormalizer.normalize(raw)
        if (text.isBlank()) return false
        val commandWords = listOf(
            "打开", "启动", "进入", "运行", "关闭", "退出",
            "播放", "暂停", "停止", "下一首", "上一首",
            "音量", "声音", "手电筒", "导航", "带我去",
            "浏览器", "访问", "附近", "高德导航", "百度地图", "设置", "调大", "调小", "切换"
        )
        return commandWords.any(text::contains)
    }

    private fun containsAny(text: String, vararg words: String): Boolean = words.any(text::contains)

    private fun exitTarget(rawTarget: String): String? {
        val target = rawTarget.trim().removeSuffix("app").removeSuffix("应用").removeSuffix("软件").trim()
        return target.takeIf { it.isNotBlank() && it !in genericExitTargets }
    }

    private companion object {
        val goHomeOnly = Regex("^(?:返回桌面|回到桌面|回桌面)$")
        val appExitPrefix = Regex("^(?:退出|关闭|离开)(.+?)(?:app|应用|软件)?$")
        val appExitShortPrefix = Regex("^退一下(.+?)(?:app|应用|软件)?$")
        val appExitNatural = Regex("^(?:把)?(.+?)(?:退了|退掉|退一下|先关掉|先关闭)$")
        val genericExitTargets = setOf("登录", "当前账号", "这个页面", "页面")
    }
}
