package com.lchuang.xiaozhimobile

class CommandRouter(private val phone: PhoneController) {
    data class Result(val handled: Boolean, val reply: String = "")

    fun handle(raw: String): Result {
        val text = raw.trim().lowercase()
        if (text.isBlank()) return Result(false)

        when {
            containsAny(text, "暂停音乐", "暂停播放", "音乐暂停") -> {
                phone.mediaPause(); return Result(true, "已暂停")
            }
            containsAny(text, "继续播放", "继续音乐", "播放音乐", "开始播放") -> {
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
                return Result(true, if (ok) "手电筒已打开" else "没有成功打开手电筒")
            }
            containsAny(text, "关闭手电筒", "关掉手电筒", "关手电筒") -> {
                val ok = phone.setFlashlight(false)
                return Result(true, if (ok) "手电筒已关闭" else "没有成功关闭手电筒")
            }
        }

        val navigation = Regex("(?:导航到|导航去|带我去|去)(.+)").find(text)
        if (navigation != null) {
            val dest = navigation.groupValues[1].trim().removeSuffix("。")
            if (dest.length >= 2 && phone.navigate(dest)) return Result(true, "正在打开导航")
        }

        val browser = Regex("(?:浏览器打开|打开网页|访问)(.+)").find(text)
        if (browser != null) {
            val target = browser.groupValues[1].trim()
            if (target.isNotBlank()) {
                phone.openBrowser(target)
                return Result(true, "正在打开")
            }
        }

        val appMatch = Regex("(?:打开|启动)(.+?)(?:app|应用|软件)?$").find(text)
        if (appMatch != null) {
            val name = appMatch.groupValues[1].trim()
                .removeSuffix("app")
                .removeSuffix("应用")
                .removeSuffix("软件")
                .trim()
            if (name.isNotBlank()) {
                val ok = phone.openApp(name)
                return Result(true, if (ok) "正在打开$name" else "没有找到$name")
            }
        }

        return Result(false)
    }

    private fun containsAny(text: String, vararg words: String): Boolean = words.any(text::contains)
}
