package com.lchuang.xiaozhimobile

sealed class VolumeAction {
    data class SetPercent(val percent: Int) : VolumeAction()
    data object StepUp : VolumeAction()
    data object StepDown : VolumeAction()
    data object Unhandled : VolumeAction()
}

class VolumeCommandParser {
    fun parse(raw: String): VolumeAction {
        val text = raw.trim().lowercase().replace(" ", "")
        if (text.isBlank()) return VolumeAction.Unhandled
        val volumeLike = listOf("音量", "声音", "静音").any(text::contains)
        if (!volumeLike) return VolumeAction.Unhandled

        if (listOf("音量大一点", "声音大一点", "加大音量", "调大音量", "提高音量").any(text::contains)) {
            return VolumeAction.StepUp
        }
        if (listOf("音量小一点", "声音小一点", "降低音量", "调小音量", "减小音量").any(text::contains)) {
            return VolumeAction.StepDown
        }
        if (listOf("最大", "最高", "开满", "调满", "最大声").any(text::contains)) {
            return VolumeAction.SetPercent(100)
        }
        if (text.contains("一半") || text.contains("半音量")) return VolumeAction.SetPercent(50)
        if (text == "静音" || listOf("声音关掉", "音量关掉", "把声音关掉", "把音量关掉").any(text::contains)) {
            return VolumeAction.SetPercent(0)
        }

        Regex("百分之([零一二三四五六七八九十百]+)").find(text)?.groupValues?.getOrNull(1)?.let { cn ->
            parseChinese0To100(cn)?.let { return VolumeAction.SetPercent(it) }
            return VolumeAction.Unhandled
        }

        Regex("(?:音量|声音).*?(\\d{1,3})%?").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { n ->
            return if (n in 0..100) VolumeAction.SetPercent(n) else VolumeAction.Unhandled
        }
        return VolumeAction.Unhandled
    }

    private fun parseChinese0To100(value: String): Int? {
        if (value == "一百") return 100
        if (value == "十") return 10
        val digits = mapOf('零' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        if (value.length == 1) return digits[value[0]]
        if (value.startsWith("十")) {
            val ones = value.drop(1).singleOrNull()?.let(digits::get) ?: return null
            return 10 + ones
        }
        val tenIndex = value.indexOf('十')
        if (tenIndex == 1) {
            val tens = digits[value[0]] ?: return null
            val tail = value.drop(2)
            val ones = when {
                tail.isEmpty() -> 0
                tail.length == 1 -> digits[tail[0]] ?: return null
                else -> return null
            }
            val result = tens * 10 + ones
            return result.takeIf { it in 0..99 }
        }
        return null
    }
}
