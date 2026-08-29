package com.lchuang.xiaozhimobile

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType

class Pinyin4jProvider : PronunciationProvider {
    private val format = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITH_TONE_MARK
        vCharType = HanyuPinyinVCharType.WITH_U_UNICODE
    }

    override fun syllables(ch: Char): List<String> {
        return try {
            val values = PinyinHelper.toHanyuPinyinStringArray(ch, format)
            if (values.isNullOrEmpty()) listOf(ch.lowercaseChar().toString())
            else values.toList().distinct()
        } catch (_: Throwable) {
            listOf(ch.lowercaseChar().toString())
        }
    }
}
