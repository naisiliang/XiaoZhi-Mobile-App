package com.lchuang.xiaozhimobile

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {
    private lateinit var settings: SettingsStore

    private lateinit var assistantName: EditText
    private lateinit var wakePhrase: EditText
    private lateinit var wakeReply: EditText
    private lateinit var timeoutReply: EditText
    private lateinit var timeoutSeconds: EditText
    private lateinit var preferOfflineAsr: Switch
    private lateinit var ttsVoiceName: EditText
    private lateinit var apiBaseUrl: EditText
    private lateinit var apiKey: EditText
    private lateinit var model: EditText
    private lateinit var systemPrompt: EditText
    private lateinit var ttsSpeechRate: EditText
    private lateinit var ttsPitch: EditText
    private lateinit var defaultMapApp: Spinner
    private lateinit var apiMode: Spinner
    private lateinit var appAliases: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)
        setContentView(buildUi())
        loadSettings()
    }

    private fun buildUi(): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(40))
            setBackgroundColor(Color.rgb(247, 248, 250))
        }

        root.addView(TextView(this).apply {
            text = "设置"
            textSize = 28f
            setTextColor(Color.rgb(20, 24, 33))
        })
        root.addView(TextView(this).apply {
            text = "配置会保存到本机；唤醒短语会沿用现有唤醒服务应用入口。"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(4), 0, dp(20))
        })

        root.addView(TextView(this).apply {
            text = "唤醒"
            textSize = 19f
            setTextColor(Color.rgb(30, 36, 48))
            setPadding(0, dp(8), 0, dp(8))
        })
        assistantName = addEdit(root, "助手名字，例如：小智 / 小白")
        wakePhrase = addEdit(root, "唤醒短语，例如：小智小智")
        wakeReply = addEdit(root, "唤醒后第一句，例如：我在")
        timeoutReply = addEdit(root, "超时退出话术")
        timeoutSeconds = addEdit(root, "连续会话超时秒数，默认 20").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        preferOfflineAsr = Switch(this).apply {
            text = "优先使用离线语音识别"
        }
        root.addView(preferOfflineAsr, LinearLayout.LayoutParams(-1, -2))

        root.addView(TextView(this).apply {
            text = "声音"
            textSize = 19f
            setTextColor(Color.rgb(30, 36, 48))
            setPadding(0, dp(16), 0, dp(8))
        })
        ttsVoiceName = addEdit(root, "TTS 声音名称（留空使用默认）")
        ttsSpeechRate = addEdit(root, "语速 0.6 - 1.6").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        ttsPitch = addEdit(root, "音调 0.6 - 1.4").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        root.addView(TextView(this).apply {
            text = "手机控制与导航"
            textSize = 19f
            setTextColor(Color.rgb(30, 36, 48))
            setPadding(0, dp(16), 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = "默认地图"
            textSize = 13f
            setTextColor(Color.GRAY)
        })
        defaultMapApp = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("自动选择", "高德地图", "百度地图", "系统默认")
            )
        }
        root.addView(defaultMapApp, LinearLayout.LayoutParams(-1, -2))
        appAliases = addMultilineEdit(root, "App 别名，每行：别名=真实应用名", 4)

        root.addView(TextView(this).apply {
            text = "AI 对话"
            textSize = 19f
            setTextColor(Color.rgb(30, 36, 48))
            setPadding(0, dp(16), 0, dp(8))
        })
        apiBaseUrl = addEdit(root, "Base URL，例如 https://api.example.com")
        apiKey = addEdit(root, "API Key（仅保存在本机）").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        model = addEdit(root, "模型名，例如 gpt-5.6")
        root.addView(TextView(this).apply {
            text = "API 模式"
            textSize = 13f
            setTextColor(Color.GRAY)
        })
        apiMode = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("自动检测", "Chat Completions", "Responses")
            )
        }
        root.addView(apiMode, LinearLayout.LayoutParams(-1, -2))
        systemPrompt = addMultilineEdit(root, "系统提示词", 4)

        root.addView(Button(this).apply {
            text = "保存设置"
            setOnClickListener { saveSettings() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })

        return ScrollView(this).apply { addView(root) }
    }

    private fun loadSettings() {
        assistantName.setText(settings.assistantName)
        wakePhrase.setText(settings.wakePhrase)
        wakeReply.setText(settings.wakeReply)
        timeoutReply.setText(settings.timeoutReply)
        timeoutSeconds.setText(settings.sessionTimeoutSeconds.toString())
        preferOfflineAsr.isChecked = settings.preferOfflineAsr
        ttsVoiceName.setText(settings.ttsVoiceName)
        apiBaseUrl.setText(settings.apiBaseUrl)
        apiKey.setText(settings.apiKey)
        model.setText(settings.model)
        apiMode.setSelection(settings.apiMode.ordinal)
        systemPrompt.setText(settings.systemPrompt)
        ttsSpeechRate.setText(settings.ttsSpeechRate.toString())
        ttsPitch.setText(settings.ttsPitch.toString())
        defaultMapApp.setSelection(settings.defaultMapApp.ordinal)
        appAliases.setText(settings.appAliases)
    }

    private fun saveSettings() {
        settings.assistantName = assistantName.text.toString()
        settings.wakePhrase = wakePhrase.text.toString()
        settings.wakeReply = wakeReply.text.toString()
        settings.timeoutReply = timeoutReply.text.toString()
        settings.sessionTimeoutSeconds = timeoutSeconds.text.toString().toIntOrNull() ?: 20
        settings.preferOfflineAsr = preferOfflineAsr.isChecked
        settings.ttsVoiceName = ttsVoiceName.text.toString()
        settings.apiBaseUrl = apiBaseUrl.text.toString()
        settings.apiKey = apiKey.text.toString()
        settings.model = model.text.toString()
        settings.apiMode = ApiMode.entries.getOrElse(apiMode.selectedItemPosition) { ApiMode.AUTO }
        settings.systemPrompt = systemPrompt.text.toString()
        settings.ttsSpeechRate = ttsSpeechRate.text.toString().toFloatOrNull() ?: 1.0f
        settings.ttsPitch = ttsPitch.text.toString().toFloatOrNull() ?: 1.0f
        settings.defaultMapApp = MapAppPreference.entries.getOrElse(defaultMapApp.selectedItemPosition) { MapAppPreference.AUTO }
        settings.appAliases = appAliases.text.toString()
        applyWakeSettingsIfRunning()
        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
    }

    private fun applyWakeSettingsIfRunning() {
        if (!isWakeServiceRunning()) return
        startService(Intent(this, WakeService::class.java).setAction(WakeService.ACTION_APPLY_WAKE_SETTINGS))
    }

    private fun isWakeServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == WakeService::class.java.name }
    }

    private fun addEdit(root: LinearLayout, hintValue: String): EditText = EditText(this).also { editText ->
        editText.hint = hintValue
        editText.setSingleLine(true)
        root.addView(editText, LinearLayout.LayoutParams(-1, -2))
    }

    private fun addMultilineEdit(root: LinearLayout, hintValue: String, minLinesValue: Int): EditText = EditText(this).also { editText ->
        editText.hint = hintValue
        editText.minLines = minLinesValue
        editText.gravity = Gravity.TOP or Gravity.START
        editText.setSingleLine(false)
        root.addView(editText, LinearLayout.LayoutParams(-1, -2))
    }
}
