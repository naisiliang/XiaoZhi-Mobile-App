package com.lchuang.xiaozhimobile

import java.util.Locale

/**
 * Normalizes short spoken commands before they reach CommandRouter.
 * Keep this deterministic and local: no network/LLM calls are allowed here.
 */
object VoiceCommandNormalizer {
    private val removablePunctuation = Regex("[\\s，。！？,.!?、；;：:“”‘’'\\\"（）()【】\\[\\]…·]+")

    private val politePrefixes = listOf(
        "麻烦帮我",
        "可以帮我",
        "请帮我",
        "麻烦",
        "帮我",
        "请",
        "给我"
    )

    fun normalize(raw: String): String {
        var text = raw
            .trim()
            .lowercase(Locale.getDefault())
            .replace(removablePunctuation, "")

        if (text.isBlank()) return ""

        // Common offline-ASR variants for app names.
        text = text
            .replace("威信", "微信")
            .replace("微星", "微信")
            .replace("扣扣", "qq")

        // Strip conversational prefixes repeatedly so phrases such as
        // “请帮我打开微信” and “麻烦给我播放一下音乐” become commands.
        var changed: Boolean
        do {
            changed = false
            for (prefix in politePrefixes) {
                if (text.startsWith(prefix) && text.length > prefix.length) {
                    text = text.removePrefix(prefix)
                    changed = true
                    break
                }
            }
        } while (changed)

        // App launch aliases.
        when {
            text in setOf("打开一下微信", "进入微信", "启动微信") -> return "打开微信"
            text in setOf("打开一下qq", "进入qq", "启动qq") -> return "打开qq"
        }

        // Media aliases. Put stop/pause before play to avoid accidental overlap.
        if (containsAny(text, "把音乐停掉", "把音乐关掉", "关掉音乐", "关闭音乐", "停止音乐", "停止播放", "停止歌曲", "音乐停止")) {
            return "停止音乐"
        }
        if (containsAny(text, "停一下音乐", "暂停一下音乐", "暂停音乐", "暂停播放", "音乐暂停", "暂停歌曲")) {
            return "暂停音乐"
        }
        if (containsAny(text, "来首歌", "来一首歌", "放音乐", "放一下音乐", "播放一下音乐", "播放音乐", "开始播放", "继续播放")) {
            return "播放音乐"
        }

        return text
    }

    private fun containsAny(text: String, vararg words: String): Boolean = words.any(text::contains)
}
