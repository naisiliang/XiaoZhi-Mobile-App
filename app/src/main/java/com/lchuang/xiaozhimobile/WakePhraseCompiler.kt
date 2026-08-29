package com.lchuang.xiaozhimobile

interface PronunciationProvider {
    fun syllables(ch: Char): List<String>
}

sealed class CompileResult {
    data class Success(
        val phrase: String,
        val runtimeKeyword: String,
        val warning: String = ""
    ) : CompileResult()

    data class Failure(val reason: String) : CompileResult()
}

class WakePhraseCompiler {
    fun compile(
        phrase: String,
        tokenInventory: Set<String>,
        provider: PronunciationProvider
    ): CompileResult {
        val clean = phrase.trim()
        if (clean.isBlank()) return CompileResult.Failure("唤醒短语不能为空")
        if (clean.length < 2) return CompileResult.Failure("唤醒短语至少 2 个字符")
        if (clean.length > 12) return CompileResult.Failure("唤醒短语最多 12 个字符")

        val output = mutableListOf<String>()
        for (ch in clean) {
            val candidates = provider.syllables(ch)
            var chosen: List<String>? = null
            for (syllable in candidates) {
                val segmented = segment(syllable.trim().lowercase(), tokenInventory)
                if (segmented != null) {
                    chosen = segmented
                    break
                }
            }
            if (chosen == null) return CompileResult.Failure("字符“$ch”无法由当前离线唤醒模型表示")
            output += chosen
        }
        val warning = if (clean.length > 6) "唤醒短语较长，建议使用 2–6 个汉字以获得更快响应" else ""
        return CompileResult.Success(clean, output.joinToString(" ") + " @" + clean, warning)
    }

    private fun segment(syllable: String, tokens: Set<String>): List<String>? {
        if (syllable.isBlank()) return null
        val candidates = tokens.filter { it.isNotBlank() && !it.startsWith("<") }
            .sortedWith(compareByDescending<String> { it.length }.thenBy { it })
        val memo = HashMap<Int, List<String>?>()
        fun solve(index: Int): List<String>? {
            if (index == syllable.length) return emptyList()
            if (memo.containsKey(index)) return memo[index]
            var best: List<String>? = null
            for (token in candidates) {
                if (!syllable.startsWith(token, index)) continue
                val tail = solve(index + token.length) ?: continue
                val option = listOf(token) + tail
                val previous = best
                if (previous == null || option.size < previous.size ||
                    (option.size == previous.size && option.first().length > previous.first().length)) {
                    best = option
                }
            }
            memo[index] = best
            return best
        }
        return solve(0)
    }
}
