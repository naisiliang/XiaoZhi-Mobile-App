package com.lchuang.xiaozhimobile

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

class TtsVoiceManager(
    private val engine: TextToSpeech,
    private val settings: SettingsStore
) {
    data class VoiceOption(
        val name: String,
        val localeTag: String,
        val networkRequired: Boolean,
        val displayLabel: String,
        val isFallback: Boolean = false
    )

    data class VoiceApplyResult(
        val success: Boolean,
        val appliedVoiceName: String,
        val message: String
    )

    fun availableVoices(): List<VoiceOption> {
        val voices = engine.voices.orEmpty().toList()
        val chinese = voices.filter { it.locale.language.equals(Locale.CHINESE.language, ignoreCase = true) }
            .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.locale.toLanguageTag() }, { it.name }))
        val chosen = if (chinese.isNotEmpty()) chinese else listOfNotNull(engine.voice)
        return chosen.distinctBy { it.name }.map { voice ->
            VoiceOption(
                name = voice.name,
                localeTag = voice.locale.toLanguageTag(),
                networkRequired = voice.isNetworkConnectionRequired,
                displayLabel = "${voice.locale.displayName} · ${voice.name}${if (voice.isNetworkConnectionRequired) " · 网络" else " · 本地"}",
                isFallback = chinese.isEmpty()
            )
        }
    }

    fun applySavedSettings(): VoiceApplyResult =
        applyVoice(settings.ttsVoiceName, settings.ttsSpeechRate, settings.ttsPitch)

    fun applyVoice(name: String, rate: Float, pitch: Float): VoiceApplyResult {
        val options = availableVoices()
        val voice = engine.voices.orEmpty().firstOrNull { it.name == name }
            ?: options.firstOrNull()?.let { option -> engine.voices.orEmpty().firstOrNull { it.name == option.name } }
            ?: engine.voice
        if (voice != null) {
            val result = engine.setVoice(voice)
            if (result == TextToSpeech.ERROR) return VoiceApplyResult(false, "", "语音引擎拒绝该声音")
            settings.ttsVoiceName = voice.name
        }
        val safeRate = rate.coerceIn(0.6f, 1.6f)
        val safePitch = pitch.coerceIn(0.6f, 1.4f)
        settings.ttsSpeechRate = safeRate
        settings.ttsPitch = safePitch
        val rateOk = engine.setSpeechRate(safeRate) != TextToSpeech.ERROR
        val pitchOk = engine.setPitch(safePitch) != TextToSpeech.ERROR
        return VoiceApplyResult(rateOk && pitchOk, voice?.name.orEmpty(), if (rateOk && pitchOk) "语音设置已应用" else "语速或音调设置失败")
    }

    fun preview(text: String, onDone: () -> Unit = {}) {
        val utteranceId = "preview-${UUID.randomUUID()}"
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { if (id == utteranceId) onDone() }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { if (id == utteranceId) onDone() }
            override fun onError(id: String?, errorCode: Int) { if (id == utteranceId) onDone() }
        })
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }
}
