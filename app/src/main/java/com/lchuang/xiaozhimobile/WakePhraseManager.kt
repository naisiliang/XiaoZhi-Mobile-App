package com.lchuang.xiaozhimobile

import android.content.Context
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.OnlineStream

class WakePhraseManager(
    private val context: Context,
    private val modelDir: String,
    private val provider: PronunciationProvider = Pinyin4jProvider()
) {
    data class AppliedWakePhrase(val phrase: String, val warning: String)

    private val compiler = WakePhraseCompiler()
    private val tokenInventory: Set<String> by lazy { loadTokenInventory() }
    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    @Volatile private var appliedPhrase: String = "小智小智"

    fun attachSpotter(value: KeywordSpotter) {
        spotter = value
    }

    @Synchronized
    fun applyPhrase(phrase: String): Result<AppliedWakePhrase> {
        val kws = spotter ?: return Result.failure(IllegalStateException("KWS_NOT_READY"))
        val compiled = compiler.compile(phrase, tokenInventory, provider)
        if (compiled is CompileResult.Failure) return Result.failure(IllegalArgumentException(compiled.reason))
        compiled as CompileResult.Success
        return try {
            val newStream = kws.createStream(compiled.runtimeKeyword)
            val old = stream
            stream = newStream
            appliedPhrase = compiled.phrase
            try { old?.release() } catch (_: Throwable) {}
            Result.success(AppliedWakePhrase(compiled.phrase, compiled.warning))
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    fun currentStream(): OnlineStream? = stream
    fun activePhrase(): String = appliedPhrase

    private fun loadTokenInventory(): Set<String> {
        val tokens = linkedSetOf<String>()
        context.assets.open("$modelDir/tokens.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                val value = line.trim().split(Regex("\\s+"), limit = 2).firstOrNull().orEmpty()
                if (value.isNotBlank()) tokens += value
            }
        }
        return tokens
    }
}
