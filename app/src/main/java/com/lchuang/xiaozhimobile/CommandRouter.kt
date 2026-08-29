package com.lchuang.xiaozhimobile

class CommandRouter(private val phone: PhoneController) {
    private val volumeParser = VolumeCommandParser()
    data class Result(val handled: Boolean, val reply: String = "", val success: Boolean = true)

    fun handle(raw: String): Result {
        val text = raw.trim().lowercase()
        if (text.isBlank()) return Result(false)

        when (val volume = volumeParser.parse(text)) {
            is VolumeAction.SetPercent -> return volumeResult(phone.setMediaVolumePercent(volume.percent))
            VolumeAction.StepUp -> return volumeResult(phone.volumeUpVerified(), step = "up")
            VolumeAction.StepDown -> return volumeResult(phone.volumeDownVerified(), step = "down")
            VolumeAction.Unhandled -> Unit
        }

        when {
            containsAny(text, "暂停音乐", "暂停播放", "音乐暂停", "暂停歌曲", "停一下音乐", "暂停一下音乐") -> {
                phone.mediaPause(); return Result(true, "已暂停")
            }
            containsAny(text, "停止音乐", "停止播放", "停止歌曲", "音乐停止", "关闭音乐", "关掉音乐", "把音乐停掉", "把音乐关掉") -> {
                phone.mediaStop(); return Result(true, "已停止播放")
            }
            containsAny(text, "继续播放", "继续音乐", "播放音乐", "开始播放", "放音乐", "播放一下音乐", "放一下音乐", "来首歌", "来一首歌") -> {
                phone.mediaPlay(); return Result(true, "音乐已开始播放")
            }
            containsAny(text, "下一首", "下一曲", "切下一首") -> {
                phone.mediaNext(); return Result(true, "已切换到下一首")
            }
            containsAny(text, "上一首", "上一曲", "切上一首") -> {
                phone.mediaPrevious(); return Result(true, "已切换到上一首")
            }
            containsAny(text, "打开手电筒", "开启手电筒", "开手电筒") -> {
                val ok = phone.setFlashlight(true)
                return Result(true, if (ok) "手电筒已打开" else "没有成功打开手电筒", ok)
            }
            containsAny(text, "关闭手电筒", "关掉手电筒", "关手电筒") -> {
                val ok = phone.setFlashlight(false)
                return Result(true, if (ok) "手电筒已关闭" else "没有成功关闭手电筒", ok)
            }
        }

        if (containsAny(text, "打开微信", "启动微信", "打开威信", "启动威信", "进入微信", "打开一下微信")) {
            return appResult("微信", phone.openApp("微信"))
        }
        if (containsAny(text, "打开qq", "启动qq", "打开q q", "启动q q", "打开扣扣", "启动扣扣", "进入qq", "打开一下qq")) {
            return appResult("QQ", phone.openApp("qq"))
        }

        if (containsAny(text, "打开高德导航", "打开高德地图")) {
            val result = phone.openMap(MapAppPreference.AMAP)
            return Result(true, result.message, result.success)
        }
        if (containsAny(text, "打开百度地图")) {
            val result = phone.openMap(MapAppPreference.BAIDU)
            return Result(true, result.message, result.success)
        }

        val nearby = Regex("(?:用(高德|百度)(?:地图)?(?:帮我)?|帮我)?(?:找|搜索)?(?:一下)?附近(?:的)?(.+)|附近(?:帮我)?(?:找|搜索)?(.+)").find(text)
        if (nearby != null) {
            val keyword = (nearby.groupValues.getOrNull(2).orEmpty() + nearby.groupValues.getOrNull(3).orEmpty())
                .trim().removeSuffix("。")
            if (keyword.isNotBlank()) {
                val pref = when (nearby.groupValues.getOrNull(1).orEmpty()) {
                    "高德" -> MapAppPreference.AMAP
                    "百度" -> MapAppPreference.BAIDU
                    else -> MapAppPreference.AUTO
                }
                phone.searchNearby(keyword, pref) { }
                return Result(true, "已打开地图搜索附近的$keyword", true)
            }
        }

        val explicitNavigation = Regex("用(高德|百度)(?:地图)?导航(?:到|去)?(.+)").find(text)
        if (explicitNavigation != null) {
            val pref = if (explicitNavigation.groupValues[1] == "高德") MapAppPreference.AMAP else MapAppPreference.BAIDU
            val dest = explicitNavigation.groupValues[2].trim().removeSuffix("。")
            val result = phone.navigate(dest, pref)
            return Result(true, result.message, result.success)
        }

        val navigation = Regex("(?:导航到|导航去|带我去)(.+)").find(text)
        if (navigation != null) {
            val dest = navigation.groupValues[1].trim().removeSuffix("。")
            if (dest.length >= 2) {
                val result = phone.navigate(dest, MapAppPreference.AUTO)
                return Result(true, result.message, result.success)
            }
        }

        val browser = Regex("(?:浏览器打开|打开网页|访问)(.+)").find(text)
        if (browser != null) {
            val target = browser.groupValues[1].trim()
            if (target.isNotBlank()) {
                val ok = phone.openBrowser(target)
                return Result(true, if (ok) "浏览器已打开" else "没有成功打开浏览器", ok)
            }
        }

        val appMatch = Regex("(?:打开|启动|进入|运行)(.+?)(?:app|应用|软件)?$").find(text)
        if (appMatch != null) {
            val name = appMatch.groupValues[1].trim()
                .removeSuffix("app")
                .removeSuffix("应用")
                .removeSuffix("软件")
                .trim()
            if (name.isNotBlank()) {
                return appResult(name, phone.openApp(name))
            }
        }

        return Result(false)
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
}
