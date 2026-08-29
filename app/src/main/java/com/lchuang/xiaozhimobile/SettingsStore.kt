package com.lchuang.xiaozhimobile

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("xiaozhi_settings", Context.MODE_PRIVATE)

    init {
        migrateLegacyApiUrlIfNeeded()
    }

    var assistantName: String
        get() = prefs.getString("assistant_name", "小智") ?: "小智"
        set(value) = prefs.edit().putString("assistant_name", value.trim().ifBlank { "小智" }).apply()

    var wakePhrase: String
        get() = prefs.getString("wake_phrase", "小智小智") ?: "小智小智"
        set(value) = prefs.edit().putString("wake_phrase", value.trim().ifBlank { "小智小智" }).apply()

    var defaultMapApp: MapAppPreference
        get() = enumValueOrDefault(prefs.getString("default_map_app", null), MapAppPreference.AUTO)
        set(value) = prefs.edit().putString("default_map_app", value.name).apply()

    var ttsVoiceName: String
        get() = prefs.getString("tts_voice_name", "") ?: ""
        set(value) = prefs.edit().putString("tts_voice_name", value.trim()).apply()

    var ttsSpeechRate: Float
        get() = prefs.getFloat("tts_speech_rate", 1.0f).coerceIn(0.6f, 1.6f)
        set(value) = prefs.edit().putFloat("tts_speech_rate", value.coerceIn(0.6f, 1.6f)).apply()

    var ttsPitch: Float
        get() = prefs.getFloat("tts_pitch", 1.0f).coerceIn(0.6f, 1.4f)
        set(value) = prefs.edit().putFloat("tts_pitch", value.coerceIn(0.6f, 1.4f)).apply()

    var apiBaseUrl: String
        get() = prefs.getString("api_base_url", "") ?: ""
        set(value) = prefs.edit().putString("api_base_url", value.trim().trimEnd('/')).apply()

    var apiMode: ApiMode
        get() = enumValueOrDefault(prefs.getString("api_mode", null), ApiMode.AUTO)
        set(value) = prefs.edit().putString("api_mode", value.name).apply()

    @Deprecated("Use apiBaseUrl")
    var apiUrl: String
        get() = apiBaseUrl
        set(value) { apiBaseUrl = value }

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) = prefs.edit().putString("api_key", value.trim()).apply()

    var model: String
        get() = prefs.getString("model", "gpt-5.6") ?: "gpt-5.6"
        set(value) = prefs.edit().putString("model", value.trim()).apply()

    var systemPrompt: String
        get() = prefs.getString(
            "system_prompt",
            "你是运行在安卓手机上的小智语音助手。回答简洁、自然、适合语音播报。"
        ) ?: "你是小智语音助手。"
        set(value) = prefs.edit().putString("system_prompt", value).apply()

    var wakeReply: String
        get() = prefs.getString("wake_reply", "我在") ?: "我在"
        set(value) = prefs.edit().putString("wake_reply", value.trim()).apply()

    var timeoutReply: String
        get() = prefs.getString("timeout_reply", "我先退下了，有问题再唤醒我") ?: "我先退下了，有问题再唤醒我"
        set(value) = prefs.edit().putString("timeout_reply", value.trim()).apply()

    var sessionTimeoutSeconds: Int
        get() = prefs.getInt("session_timeout_seconds", 20).coerceIn(5, 300)
        set(value) = prefs.edit().putInt("session_timeout_seconds", value.coerceIn(5, 300)).apply()

    var appAliases: String
        get() = prefs.getString("app_aliases", "") ?: ""
        set(value) = prefs.edit().putString("app_aliases", value.trim()).apply()

    var preferOfflineAsr: Boolean
        get() = prefs.getBoolean("prefer_offline_asr", false)
        set(value) = prefs.edit().putBoolean("prefer_offline_asr", value).apply()

    fun migrateLegacyApiUrlIfNeeded() {
        if (prefs.getBoolean("v060_api_base_migrated", false)) return
        val legacy = prefs.getString("api_url", "")?.trim().orEmpty()
        if (legacy.isNotBlank() && !prefs.contains("api_base_url")) {
            val base = legacy
                .substringBefore('?')
                .substringBefore('#')
                .trimEnd('/')
                .removeSuffix("/v1/chat/completions")
                .removeSuffix("/v1/responses")
                .removeSuffix("/v1/models")
                .removeSuffix("/v1")
                .trimEnd('/')
            prefs.edit().putString("api_base_url", base).apply()
        }
        prefs.edit().putBoolean("v060_api_base_migrated", true).apply()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String?, default: T): T {
        return enumValues<T>().firstOrNull { it.name == raw } ?: default
    }
}
