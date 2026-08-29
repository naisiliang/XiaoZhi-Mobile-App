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
                val segmented = toOfficialPpinyinTokens(syllable, tokenInventory)
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

    private fun toOfficialPpinyinTokens(rawSyllable: String, tokens: Set<String>): List<String>? {
        val syllable = normalizePinyinToneMarks(rawSyllable.trim().lowercase())
        if (syllable.isBlank()) return null

        // Match sherpa-onnx text2token(..., tokens_type="ppinyin"):
        // split one pinyin syllable into initial + tone-marked final.
        // Long initials must be checked first.
        val initials = listOf(
            "zh", "ch", "sh",
            "b", "p", "m", "f", "d", "t", "n", "l",
            "g", "k", "h", "j", "q", "x", "r", "z", "c", "s",
            "y", "w"
        )
        val initial = initials.firstOrNull { syllable.startsWith(it) }.orEmpty()
        val final = syllable.substring(initial.length)
        if (final.isBlank()) return null

        val result = if (initial.isBlank()) listOf(final) else listOf(initial, final)
        return result.takeIf { parts -> parts.all(tokens::contains) }
    }

    internal fun normalizePinyinToneMarks(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            append(
                when (ch) {
                    // pinyin4j 2.5.x has an upstream Unicode bug where some
                    // third-tone vowels use BREVE instead of standard CARON.
                    'ă' -> 'ǎ'
                    'ĕ' -> 'ě'
                    'ĭ' -> 'ǐ'
                    'ŏ' -> 'ǒ'
                    'ŭ' -> 'ǔ'
                    'Ă' -> 'Ǎ'
                    'Ĕ' -> 'Ě'
                    'Ĭ' -> 'Ǐ'
                    'Ŏ' -> 'Ǒ'
                    'Ŭ' -> 'Ǔ'
                    else -> ch
                }
            )
        }
    }

}
