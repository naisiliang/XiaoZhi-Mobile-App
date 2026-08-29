package com.lchuang.xiaozhimobile

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var settings: SettingsStore
    private lateinit var desktopIconManager: DesktopIconManager
    private lateinit var phoneController: PhoneController

    private lateinit var assistantName: EditText
    private lateinit var wakePhrase: EditText
    private lateinit var wakeReply: EditText
    private lateinit var timeoutReply: EditText
    private lateinit var timeoutSeconds: EditText
    private lateinit var currentWakePhrase: TextView
    private lateinit var voiceSpinner: Spinner
    private lateinit var speechRate: EditText
    private lateinit var pitch: EditText
    private lateinit var mapSpinner: Spinner
    private lateinit var locationStatus: TextView
    private lateinit var appAliases: EditText
    private lateinit var appCount: TextView
    private lateinit var appDiagnostic: TextView
    private lateinit var apiBaseUrl: EditText
    private lateinit var apiKey: EditText
    private lateinit var model: EditText
    private lateinit var apiModeSpinner: Spinner
    private lateinit var aiTestButton: Button
    private lateinit var testInput: EditText
    private lateinit var status: TextView
    private lateinit var overlayPermissionButton: Button
    private lateinit var iconPreview: ImageView

    private var previewTts: TextToSpeech? = null
    private var ttsVoiceManager: TtsVoiceManager? = null
    private var voiceOptions: List<TtsVoiceManager.VoiceOption> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)
        desktopIconManager = DesktopIconManager(this)
        phoneController = PhoneController(this)
        setContentView(buildUi())
        loadSettings()
        refreshIconPreview()
        refreshInstalledApps(force = false)
        refreshLocationStatus()
        requestNeededPermissions()
        previewTts = TextToSpeech(this, this)
    }

    override fun onResume() {
        super.onResume()
        if (::overlayPermissionButton.isInitialized) refreshOverlayPermissionButton()
        if (::locationStatus.isInitialized) refreshLocationStatus()
        if (::currentWakePhrase.isInitialized) refreshActiveWakePhraseLabel()
    }

    override fun onDestroy() {
        previewTts?.shutdown()
        previewTts = null
        super.onDestroy()
    }

    override fun onInit(statusCode: Int) {
        if (statusCode != TextToSpeech.SUCCESS) return
        val engine = previewTts ?: return
        val manager = TtsVoiceManager(engine, settings)
        ttsVoiceManager = manager
        manager.applySavedSettings()
        refreshVoiceSpinner()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_DESKTOP_ICON || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        desktopIconManager.applyCustomIcon(uri).onSuccess {
            refreshIconPreview()
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(this, "更换桌面图标失败：${it.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildUi(): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(40))
            setBackgroundColor(Color.rgb(247, 248, 250))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "小智手机助手"
            textSize = 28f
            setTextColor(Color.rgb(20, 24, 33))
        })
        root.addView(TextView(this).apply {
            text = "离线唤醒 · 安全 AI 工具 · 手机控制与导航"
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(4), 0, dp(20))
        })
        status = TextView(this).apply {
            text = "v0.6.2：自定义唤醒自动生效 + 指令完成语音确认 + 快速连续监听"
            textSize = 15f
            setTextColor(Color.rgb(34, 95, 68))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.rgb(229, 245, 236))
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        addHeader(root, "语音助手")
        assistantName = addEdit(root, "助手名字，例如：小智 / 小白")
        wakePhrase = addEdit(root, "唤醒短语，例如：小智小智 / 小白小白")
        wakeReply = addEdit(root, "唤醒后第一句，例如：我在")
        timeoutReply = addEdit(root, "超时退出话术")
        timeoutSeconds = addEdit(root, "连续会话超时秒数，默认 20").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        currentWakePhrase = TextView(this).apply {
            text = "当前实际 KWS 唤醒短语：${settings.activeWakePhrase}"
            textSize = 13f
            setTextColor(Color.rgb(34, 95, 68))
            setPadding(0, dp(6), 0, dp(6))
        }
        root.addView(currentWakePhrase)
        root.addView(Button(this).apply {
            text = "保存并应用唤醒词"
            setOnClickListener { saveAndApplyWakeSettings() }
        }, LinearLayout.LayoutParams(-1, -2))

        overlayPermissionButton = Button(this).apply {
            setOnClickListener {
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "桌面透明语音悬浮层已授权", Toast.LENGTH_SHORT).show()
                } else {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
        }
        refreshOverlayPermissionButton()
        root.addView(overlayPermissionButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        root.addView(Button(this).apply {
            text = "开启后台离线唤醒"
            setOnClickListener {
                saveSettings()
                requestNeededPermissions()
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    val i = Intent(this@MainActivity, WakeService::class.java)
                        .setAction(WakeService.ACTION_APPLY_WAKE_SETTINGS)
                    currentWakePhrase.text = "当前实际 KWS 唤醒短语：正在应用“${settings.wakePhrase}”…"
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                    status.text = "离线助手正在启动/应用唤醒词。目标：“${settings.wakePhrase}”"
                    currentWakePhrase.postDelayed({ refreshActiveWakePhraseLabel() }, 700L)
                } else Toast.makeText(this@MainActivity, "请先允许麦克风权限", Toast.LENGTH_LONG).show()
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(Button(this).apply {
            text = "停止离线唤醒"
            setOnClickListener {
                stopService(Intent(this@MainActivity, WakeService::class.java))
                status.text = "离线唤醒已停止"
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(22) })

        addHeader(root, "声音")
        voiceSpinner = Spinner(this)
        root.addView(voiceSpinner, LinearLayout.LayoutParams(-1, -2))
        speechRate = addEdit(root, "语速 0.6 - 1.6")
        speechRate.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        pitch = addEdit(root, "音调 0.6 - 1.4")
        pitch.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        root.addView(Button(this).apply {
            text = "试听声音"
            setOnClickListener {
                saveTtsSettings()
                ttsVoiceManager?.preview("你好，我是${assistantName.text.toString().ifBlank { "小智" }}，这是当前语音效果。")
                    ?: Toast.makeText(this@MainActivity, "语音引擎还没有准备好", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "打开系统语音合成设置"
            setOnClickListener { openSystemTtsSettings() }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(22) })

        addHeader(root, "手机控制与导航")
        mapSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("自动选择", "高德地图", "百度地图", "系统默认"))
        }
        root.addView(TextView(this).apply { text = "默认地图"; textSize = 13f; setTextColor(Color.GRAY) })
        root.addView(mapSpinner, LinearLayout.LayoutParams(-1, -2))
        locationStatus = TextView(this).apply { textSize = 13f; setTextColor(Color.rgb(34, 95, 68)) }
        root.addView(locationStatus)
        root.addView(Button(this).apply {
            text = "授权位置权限（仅附近搜索时使用）"
            setOnClickListener { requestLocationPermission() }
        }, LinearLayout.LayoutParams(-1, -2))
        appCount = TextView(this).apply { text = "正在扫描手机已安装应用…"; textSize = 13f; setTextColor(Color.rgb(34, 95, 68)) }
        root.addView(appCount)
        root.addView(Button(this).apply {
            text = "刷新应用列表"
            setOnClickListener { refreshInstalledApps(force = true) }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "查看已发现应用"
            setOnClickListener { showInstalledApps() }
        }, LinearLayout.LayoutParams(-1, -2))
        appAliases = addMultilineEdit(root, "App 别名，每行：别名=真实应用名", 4)
        appDiagnostic = TextView(this).apply {
            text = "最近一次 App 匹配：尚未测试"
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(5), 0, dp(5))
        }
        root.addView(appDiagnostic)
        testInput = addEdit(root, "输入 App 名称或手机控制指令")
        root.addView(Button(this).apply {
            text = "测试打开应用"
            setOnClickListener { testOpenApp() }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "直接执行手机指令（不调用 AI）"
            setOnClickListener {
                saveSettings()
                val result = CommandRouter(phoneController).handle(VoiceCommandNormalizer.normalize(testInput.text.toString()))
                appDiagnostic.text = "最近一次 App 匹配：${phoneController.appRegistry.lastResolutionExplanation()}"
                Toast.makeText(this@MainActivity, if (result.handled) result.reply else "未匹配本地指令", Toast.LENGTH_LONG).show()
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(22) })

        addHeader(root, "AI 对话")
        root.addView(TextView(this).apply {
            text = "填写 Base URL，例如 https://api.example.com；程序自动检测 Chat Completions / Responses。AI 只允许调用安全工具白名单。"
            textSize = 13f
            setTextColor(Color.GRAY)
        })
        apiBaseUrl = addEdit(root, "Base URL，例如 https://api.example.com")
        apiKey = addEdit(root, "API Key（仅保存在本机）")
        model = addEdit(root, "模型名，例如 gpt-5.6")
        root.addView(TextView(this).apply { text = "API 模式"; textSize = 13f; setTextColor(Color.GRAY) })
        apiModeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("自动检测", "Chat Completions", "Responses"))
        }
        root.addView(apiModeSpinner, LinearLayout.LayoutParams(-1, -2))
        aiTestButton = Button(this).apply {
            text = "测试 AI 接口"
            setOnClickListener { testAiEndpoint() }
        }
        root.addView(aiTestButton, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "保存全部设置"
            setOnClickListener {
                saveSettings()
                applyWakeSettingsIfRunning()
                Toast.makeText(this@MainActivity, "设置已保存${if (isWakeServiceRunning()) "，唤醒词正在同步" else ""}", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(22) })

        addHeader(root, "个性化")
        root.addView(TextView(this).apply {
            text = "默认使用蓝粉渐变 Logo；用户图片只改变桌面快捷图标，系统应用抽屉仍保持默认 Logo。"
            textSize = 13f
            setTextColor(Color.GRAY)
        })
        iconPreview = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(235, 238, 244))
        }
        root.addView(iconPreview, LinearLayout.LayoutParams(dp(96), dp(96)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12); bottomMargin = dp(10)
        })
        root.addView(Button(this).apply {
            text = "从相册选择桌面图标"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }, REQUEST_PICK_DESKTOP_ICON)
            }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "恢复默认 Logo"
            setOnClickListener {
                desktopIconManager.restoreDefault().onSuccess { refreshIconPreview(); Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() }
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(22) })

        addHeader(root, "调试")
        root.addView(TextView(this).apply {
            text = "语音指令识别：sherpa-onnx Paraformer 本地 ASR；持续麦克风音频不会发送到 AI。"
            textSize = 13f
            setTextColor(Color.rgb(34, 95, 68))
        })
        root.addView(TextView(this).apply {
            text = "当前 KWS 唤醒短语会在上方显示；App 列表和最近一次 App 匹配用于定位厂商 ROM 的包可见性/启动问题。"
            textSize = 13f
            setTextColor(Color.DKGRAY)
        })
        return scroll
    }

    private fun saveAndApplyWakeSettings() {
        val oldName = settings.assistantName
        val typedName = assistantName.text.toString().trim().ifBlank { "小智" }
        if (typedName != oldName && wakePhrase.text.toString().trim() == oldName + oldName) {
            wakePhrase.setText(typedName + typedName)
        }
        saveSettings()
        if (isWakeServiceRunning()) {
            applyWakeSettingsIfRunning()
            Toast.makeText(this, "设置已保存，正在运行时应用新的唤醒短语", Toast.LENGTH_LONG).show()
        } else {
            currentWakePhrase.text = "当前实际 KWS 唤醒短语：${settings.activeWakePhrase}（新词将在启动时应用）"
            Toast.makeText(this, "设置已保存，下次开启离线唤醒时生效", Toast.LENGTH_LONG).show()
        }
    }

    private fun applyWakeSettingsIfRunning() {
        if (!isWakeServiceRunning()) {
            refreshActiveWakePhraseLabel()
            return
        }
        currentWakePhrase.text = "当前实际 KWS 唤醒短语：正在应用“${settings.wakePhrase}”…"
        startService(Intent(this, WakeService::class.java).setAction(WakeService.ACTION_APPLY_WAKE_SETTINGS))
        currentWakePhrase.postDelayed({ refreshActiveWakePhraseLabel() }, 700L)
    }

    private fun refreshActiveWakePhraseLabel() {
        currentWakePhrase.text = "当前实际 KWS 唤醒短语：${settings.activeWakePhrase}"
    }

    private fun refreshVoiceSpinner() {
        val manager = ttsVoiceManager ?: return
        voiceOptions = manager.availableVoices()
        val labels = if (voiceOptions.isEmpty()) listOf("当前设备没有可用声音") else voiceOptions.map { it.displayLabel }
        voiceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val index = voiceOptions.indexOfFirst { it.name == settings.ttsVoiceName }.coerceAtLeast(0)
        if (voiceOptions.isNotEmpty()) voiceSpinner.setSelection(index)
    }

    private fun openSystemTtsSettings() {
        val opened = runCatching {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            true
        }.getOrDefault(false)
        if (!opened) {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                .onFailure { Toast.makeText(this, "无法打开系统语音设置", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun saveTtsSettings() {
        val rate = speechRate.text.toString().toFloatOrNull() ?: 1.0f
        val pitchValue = pitch.text.toString().toFloatOrNull() ?: 1.0f
        val selected = voiceOptions.getOrNull(voiceSpinner.selectedItemPosition)?.name.orEmpty()
        settings.ttsVoiceName = selected
        settings.ttsSpeechRate = rate
        settings.ttsPitch = pitchValue
        ttsVoiceManager?.applyVoice(selected, settings.ttsSpeechRate, settings.ttsPitch)
        speechRate.setText(settings.ttsSpeechRate.toString())
        pitch.setText(settings.ttsPitch.toString())
    }

    private fun testAiEndpoint() {
        saveSettings()
        aiTestButton.isEnabled = false
        aiTestButton.text = "正在测试 AI 接口…"
        AiClient(settings).testEndpoint { result ->
            runOnUiThread {
                aiTestButton.isEnabled = true
                aiTestButton.text = "测试 AI 接口"
                val detail = buildString {
                    appendLine("连接状态：${if (result.success) "成功" else "失败"}")
                    appendLine("HTTP：${result.httpStatus ?: "-"}")
                    appendLine("接口类型：${result.mode ?: settings.apiMode}")
                    appendLine("模型：${result.model}")
                    appendLine("耗时：${result.latencyMs} ms")
                    if (result.success) append("回复：${result.reply.take(180)}") else append("错误：${result.error.take(180)}")
                }
                AlertDialog.Builder(this).setTitle("AI 接口测试").setMessage(detail).setPositiveButton("确定", null).show()
            }
        }
    }

    private fun refreshInstalledApps(force: Boolean) {
        Thread {
            val apps = runCatching { phoneController.appRegistry.discover(force) }.getOrDefault(emptyList())
            runOnUiThread { appCount.text = "已发现应用：${apps.size} 个" }
        }.start()
    }

    private fun showInstalledApps() {
        Thread {
            val apps = runCatching { phoneController.appRegistry.discover(force = true) }.getOrDefault(emptyList())
            val lines = apps.take(300).map { "${it.label} — ${it.packageName} — ${it.source}" }.toTypedArray()
            runOnUiThread {
                AlertDialog.Builder(this).setTitle("已发现应用（${apps.size}）").setItems(lines, null).setPositiveButton("关闭", null).show()
            }
        }.start()
    }

    private fun testOpenApp() {
        saveSettings()
        val name = testInput.text.toString().trim()
        if (name.isBlank()) { Toast.makeText(this, "请先输入 App 名称", Toast.LENGTH_SHORT).show(); return }
        val result = phoneController.openApp(name)
        appDiagnostic.text = "最近一次 App 匹配：${phoneController.appRegistry.lastResolutionExplanation()}"
        val message = when (result) {
            is AppLauncher.AppLaunchResult.Success -> "已启动 ${result.label}"
            is AppLauncher.AppLaunchResult.Failure -> "启动失败：${result.error} · ${result.detail}"
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun requestLocationPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "位置权限已授权，只会在附近搜索时获取一次位置", Toast.LENGTH_LONG).show()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION)
    }

    private fun refreshLocationStatus() {
        val allowed = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        locationStatus.text = "位置权限：${if (allowed) "已授权（仅按需读取）" else "未授权"}"
    }

    private fun refreshOverlayPermissionButton() {
        overlayPermissionButton.text = if (Settings.canDrawOverlays(this)) "桌面透明语音悬浮层：已授权" else "授权桌面透明语音悬浮层"
    }

    private fun refreshIconPreview() {
        try { iconPreview.setImageBitmap(desktopIconManager.currentBitmap()) }
        catch (_: Throwable) { iconPreview.setImageResource(R.mipmap.ic_launcher) }
    }

    private fun loadSettings() {
        assistantName.setText(settings.assistantName)
        wakePhrase.setText(settings.wakePhrase)
        wakeReply.setText(settings.wakeReply)
        timeoutReply.setText(settings.timeoutReply)
        timeoutSeconds.setText(settings.sessionTimeoutSeconds.toString())
        speechRate.setText(settings.ttsSpeechRate.toString())
        pitch.setText(settings.ttsPitch.toString())
        mapSpinner.setSelection(settings.defaultMapApp.ordinal)
        appAliases.setText(settings.appAliases)
        apiBaseUrl.setText(settings.apiBaseUrl)
        apiKey.setText(settings.apiKey)
        model.setText(settings.model)
        apiModeSpinner.setSelection(settings.apiMode.ordinal)
        refreshActiveWakePhraseLabel()
    }

    private fun saveSettings() {
        settings.assistantName = assistantName.text.toString()
        settings.wakePhrase = wakePhrase.text.toString()
        settings.wakeReply = wakeReply.text.toString().ifBlank { "我在" }
        settings.timeoutReply = timeoutReply.text.toString().ifBlank { "我先退下了，有问题再唤醒我" }
        settings.sessionTimeoutSeconds = timeoutSeconds.text.toString().toIntOrNull() ?: 20
        settings.defaultMapApp = MapAppPreference.entries.getOrElse(mapSpinner.selectedItemPosition) { MapAppPreference.AUTO }
        settings.appAliases = appAliases.text.toString()
        settings.apiBaseUrl = apiBaseUrl.text.toString()
        settings.apiKey = apiKey.text.toString()
        settings.model = model.text.toString()
        settings.apiMode = ApiMode.entries.getOrElse(apiModeSpinner.selectedItemPosition) { ApiMode.AUTO }
        saveTtsSettings()
        timeoutSeconds.setText(settings.sessionTimeoutSeconds.toString())
    }

    private fun isWakeServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == WakeService::class.java.name }
    }

    private fun requestNeededPermissions() {
        val permissions = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permissions += Manifest.permission.RECORD_AUDIO
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) permissions += Manifest.permission.CAMERA
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissions += Manifest.permission.POST_NOTIFICATIONS
        if (permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(), 100)
    }

    private fun addHeader(root: LinearLayout, textValue: String) {
        root.addView(TextView(this).apply { text = textValue; textSize = 19f; setTextColor(Color.rgb(30, 36, 48)); setPadding(0, 8, 0, 8) })
    }

    private fun addEdit(root: LinearLayout, hintValue: String): EditText = EditText(this).also { e ->
        e.hint = hintValue; e.setSingleLine(true); root.addView(e, LinearLayout.LayoutParams(-1, -2))
    }

    private fun addMultilineEdit(root: LinearLayout, hintValue: String, minLinesValue: Int): EditText = EditText(this).also { e ->
        e.hint = hintValue; e.minLines = minLinesValue; e.gravity = Gravity.TOP or Gravity.START; e.setSingleLine(false); root.addView(e, LinearLayout.LayoutParams(-1, -2))
    }

    companion object {
        private const val REQUEST_PICK_DESKTOP_ICON = 201
        private const val REQUEST_LOCATION = 202
    }
}
