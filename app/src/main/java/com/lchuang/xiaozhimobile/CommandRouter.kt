package com.lchuang.xiaozhimobile

class CommandRouter(private val phone: PhoneController) {
    data class Result(val handled: Boolean, val reply: String = "", val success: Boolean = true)

    fun handle(raw: String): Result {
        val text = raw.trim().lowercase()
        if (text.isBlank()) return Result(false)

        when {
            containsAny(text, "暂停音乐", "暂停播放", "音乐暂停", "暂停歌曲", "停一下音乐", "暂停一下音乐") -> {
                phone.mediaPause(); return Result(true, "已暂停")
            }
            containsAny(text, "停止音乐", "停止播放", "停止歌曲", "音乐停止", "关闭音乐", "关掉音乐", "把音乐停掉", "把音乐关掉") -> {
                phone.mediaStop(); return Result(true, "已停止播放")
            }
            containsAny(text, "继续播放", "继续音乐", "播放音乐", "开始播放", "放音乐", "播放一下音乐", "放一下音乐", "来首歌", "来一首歌") -> {
                phone.mediaPlay(); return Result(true, "好的，继续播放")
            }
            containsAny(text, "下一首", "下一曲", "切下一首") -> {
                phone.mediaNext(); return Result(true, "下一首")
            }
            containsAny(text, "上一首", "上一曲", "切上一首") -> {
                phone.mediaPrevious(); return Result(true, "上一首")
            }
            containsAny(text, "音量大一点", "声音大一点", "加大音量", "调大音量") -> {
                phone.volumeUp(); return Result(true, "已调大")
            }
            containsAny(text, "音量小一点", "声音小一点", "降低音量", "调小音量") -> {
                phone.volumeDown(); return Result(true, "已调小")
            }
            Regex("音量.*?(\\d{1,3})").containsMatchIn(text) -> {
                val p = Regex("音量.*?(\\d{1,3})").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 50
                phone.setMediaVolume(p)
                return Result(true, "音量已设置为${p.coerceIn(0, 100)}%")
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
            val ok = phone.openApp("微信")
            return Result(true, if (ok) "正在打开微信" else "没有找到微信", ok)
        }
        if (containsAny(text, "打开qq", "启动qq", "打开q q", "启动q q", "打开扣扣", "启动扣扣", "进入qq", "打开一下qq")) {
            val ok = phone.openApp("qq")
            return Result(true, if (ok) "正在打开QQ" else "没有找到QQ", ok)
        }

        val navigation = Regex("(?:导航到|导航去|带我去|去)(.+)").find(text)
        if (navigation != null) {
            val dest = navigation.groupValues[1].trim().removeSuffix("。")
            if (dest.length >= 2) {
                val ok = phone.navigate(dest)
                return Result(true, if (ok) "正在打开导航" else "导航没有成功打开", ok)
            }
        }

        val browser = Regex("(?:浏览器打开|打开网页|访问)(.+)").find(text)
        if (browser != null) {
            val target = browser.groupValues[1].trim()
            if (target.isNotBlank()) {
                val ok = phone.openBrowser(target)
                return Result(true, if (ok) "正在打开" else "没有成功打开浏览器", ok)
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
                val ok = phone.openApp(name)
                return Result(true, if (ok) "正在打开$name" else "没有找到$name", ok)
            }
        }

        return Result(false)
    }

    fun looksLikeDeviceCommand(raw: String): Boolean {
        val text = VoiceCommandNormalizer.normalize(raw)
        if (text.isBlank()) return false
        val commandWords = listOf(
            "打开", "启动", "进入", "运行", "关闭", "退出",
            "播放", "暂停", "停止", "下一首", "上一首",
            "音量", "声音", "手电筒", "导航", "带我去",
            "浏览器", "访问", "设置", "调大", "调小", "切换"
        )
        return commandWords.any(text::contains)
    }

    private fun containsAny(text: String, vararg words: String): Boolean = words.any(text::contains)
}
