package com.lchuang.xiaozhimobile

import java.util.Locale
import kotlin.math.max

object AppNameMatcher {
    private val punctuation = Regex("[\\s，。！？,.!?、；;：:“”‘’'\\\"（）()【】\\[\\]…·_-]+")
    private val removableSuffixes = listOf("app", "应用", "软件", "客户端", "手机版", "office", "浏览器")
    private val requestPrefixes = listOf("帮我打开", "请打开", "打开一下", "打开", "启动", "进入", "运行", "帮我启动", "请启动")

    fun normalize(value: String): String {
        var text = value.trim().lowercase(Locale.getDefault()).replace(punctuation, "")
        var changed: Boolean
        do {
            changed = false
            for (suffix in removableSuffixes) {
                if (text.endsWith(suffix) && text.length > suffix.length) {
                    text = text.removeSuffix(suffix)
                    changed = true
                    break
                }
            }
        } while (changed)
        return text
    }

    fun extractRequestedAppName(value: String): String {
        var text = value.trim().lowercase(Locale.getDefault())
        for (prefix in requestPrefixes.sortedByDescending { it.length }) {
            if (text.startsWith(prefix)) {
                text = text.removePrefix(prefix)
                break
            }
        }
        return normalize(text)
    }

    fun parseAliases(raw: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) return@forEach
            val separator = when {
                '=' in trimmed -> '='
                '：' in trimmed -> '：'
                ':' in trimmed -> ':'
                else -> null
            } ?: return@forEach
            val parts = trimmed.split(separator, limit = 2)
            if (parts.size != 2) return@forEach
            val alias = normalize(parts[0])
            val target = parts[1].trim()
            if (alias.isNotBlank() && target.isNotBlank()) result[alias] = target
        }
        return result
    }

    fun aliasTarget(request: String, aliases: Map<String, String>): String? {
        val normalizedRequest = extractRequestedAppName(request)
        return aliases[normalizedRequest]
            ?: aliases.entries.firstOrNull { (alias, _) ->
                normalizedRequest == alias || normalizedRequest.contains(alias) || alias.contains(normalizedRequest)
            }?.value
    }

    fun similarity(a: String, b: String): Double {
        val left = normalize(a)
        val right = normalize(b)
        if (left == right) return 1.0
        if (left.isBlank() || right.isBlank()) return 0.0
        val distance = levenshtein(left, right)
        return (1.0 - distance.toDouble() / max(left.length, right.length)).coerceIn(0.0, 1.0)
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + cost
                )
            }
            val tmp = previous
            previous = current
            current = tmp
        }
        return previous[b.length]
    }
}
