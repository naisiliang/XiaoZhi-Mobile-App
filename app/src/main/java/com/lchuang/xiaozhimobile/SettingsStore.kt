package com.lchuang.xiaozhimobile

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("xiaozhi_settings", Context.MODE_PRIVATE)

    var apiUrl: String
        get() = prefs.getString("api_url", "") ?: ""
        set(value) = prefs.edit().putString("api_url", value.trim()).apply()

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
}
