package com.lchuang.xiaozhimobile

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.lchuang.xiaozhimobile.runtime.WakeRuntimeStatus
import com.lchuang.xiaozhimobile.runtime.WakeRuntimeStatusStore
import com.lchuang.xiaozhimobile.runtime.WakeRuntimeStatusStoreProvider
import com.lchuang.xiaozhimobile.runtime.WakeServiceController
import com.lchuang.xiaozhimobile.accessibility.XiaoZhiAccessibilityService
import java.util.Locale
import java.util.concurrent.Executors

class SettingsActivity : Activity() {
    private lateinit var settings: SettingsStore
    private lateinit var runtimeStatusStore: WakeRuntimeStatusStore
    private lateinit var appRegistry: InstalledAppRegistry
    private lateinit var locationProvider: LocationProvider
    private lateinit var aiClient: AiClient

    private lateinit var settingsScroll: ScrollView
    private lateinit var assistantName: EditText
    private lateinit var wakePhrase: EditText
    private lateinit var wakeReply: EditText
    private lateinit var timeoutReply: EditText
    private lateinit var timeoutSeconds: EditText
    private lateinit var preferOfflineAsr: Switch
    private lateinit var backgroundWakeEnabled: Switch
    private lateinit var runtimeStatus: TextView
    private lateinit var ttsVoiceName: EditText
    private lateinit var ttsVoice: Spinner
    private lateinit var ttsVoiceStatus: TextView
    private lateinit var ttsSpeechRate: EditText
    private lateinit var ttsPitch: EditText
    private lateinit var defaultMapApp: Spinner
    private lateinit var locationStatus: TextView
    private lateinit var appAliases: EditText
    private lateinit var appTestName: EditText
    private lateinit var appDiscoveryStatus: TextView
    private lateinit var smartUiEnabled: Switch
    private lateinit var accessibilityStatus: TextView
    private lateinit var visionConsentStatus: TextView
    private lateinit var visionCaptureStatus: TextView
    private lateinit var apiBaseUrl: EditText
    private lateinit var apiKey: EditText
    private lateinit var model: EditText
    private lateinit var apiMode: Spinner
    private lateinit var systemPrompt: EditText
    private lateinit var aiTestStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var diagnosticsOutput: TextView

    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private var ttsEngine: TextToSpeech? = null
    private var ttsVoiceManager: TtsVoiceManager? = null
    private var ttsVoiceOptions: List<TtsVoiceManager.VoiceOption> = emptyList()

    private val runtimeObserver: (WakeRuntimeStatus) -> Unit = { status ->
        runOnUiThread {
            renderRuntimeStatus(status)
            renderDiagnostics()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)
        runtimeStatusStore = WakeRuntimeStatusStoreProvider.instance()
        appRegistry = InstalledAppRegistry(this)
        locationProvider = LocationProvider(this)
        aiClient = AiClient(settings)
        setContentView(R.layout.activity_settings)
        bindViews()
        configureSections()
        configureSpinners()
        configureActions()
        configureInsets()
        loadSettings()
        runtimeStatusStore.addObserver(runtimeObserver)
        renderRuntimeStatus(runtimeStatusStore.current)
        renderOverlayStatus()
        renderScreenIntelligenceStatus()
        refreshLocationStatus()
        renderDiagnostics()
        ttsEngine = TextToSpeech(this) { status -> handleTtsInit(status) }
    }

    override fun onResume() {
        super.onResume()
        if (::overlayStatus.isInitialized) renderOverlayStatus()
        if (::locationStatus.isInitialized) refreshLocationStatus()
        if (::accessibilityStatus.isInitialized) renderScreenIntelligenceStatus()
        if (::diagnosticsOutput.isInitialized) renderDiagnostics()
    }

    override fun onDestroy() {
        if (::runtimeStatusStore.isInitialized) {
            runtimeStatusStore.removeObserver(runtimeObserver)
        }
        backgroundExecutor.shutdownNow()
        ttsEngine?.stop()
        ttsEngine?.shutdown()
        ttsEngine = null
        ttsVoiceManager = null
        super.onDestroy()
    }

    private fun bindViews() {
        settingsScroll = findViewById(R.id.settings_scroll)
        assistantName = findViewById(R.id.assistant_name)
        wakePhrase = findViewById(R.id.wake_phrase)
        wakeReply = findViewById(R.id.wake_reply)
        timeoutReply = findViewById(R.id.timeout_reply)
        timeoutSeconds = findViewById(R.id.timeout_seconds)
        preferOfflineAsr = findViewById(R.id.prefer_offline_asr)
        backgroundWakeEnabled = findViewById(R.id.background_wake_enabled)
        runtimeStatus = findViewById(R.id.runtime_status)
        ttsVoiceName = findViewById(R.id.tts_voice_name)
        ttsVoice = findViewById(R.id.tts_voice)
        ttsVoiceStatus = findViewById(R.id.tts_voice_status)
        ttsSpeechRate = findViewById(R.id.tts_speech_rate)
        ttsPitch = findViewById(R.id.tts_pitch)
        defaultMapApp = findViewById(R.id.default_map_app)
        locationStatus = findViewById(R.id.location_status)
        appAliases = findViewById(R.id.app_aliases)
        appTestName = findViewById(R.id.app_test_name)
        appDiscoveryStatus = findViewById(R.id.app_discovery_status)
        smartUiEnabled = findViewById(R.id.smart_ui_enabled)
        accessibilityStatus = findViewById(R.id.accessibility_status)
        visionConsentStatus = findViewById(R.id.vision_consent_status)
        visionCaptureStatus = findViewById(R.id.vision_capture_status)
        apiBaseUrl = findViewById(R.id.api_base_url)
        apiKey = findViewById(R.id.api_key)
        model = findViewById(R.id.model)
        apiMode = findViewById(R.id.api_mode)
        systemPrompt = findViewById(R.id.system_prompt)
        aiTestStatus = findViewById(R.id.ai_test_status)
        overlayStatus = findViewById(R.id.overlay_status)
        diagnosticsOutput = findViewById(R.id.diagnostics_output)
    }

    private fun configureSections() {
        listOf(
            R.id.section_voice to "语音",
            R.id.section_sound to "声音",
            R.id.section_phone to "手机控制",
            R.id.section_smart_ui to "智能 UI",
            R.id.section_ai to "AI",
            R.id.section_advanced to "高级",
        ).forEach { (sectionId, title) ->
            findViewById<View>(sectionId)
                .findViewById<TextView>(R.id.settings_section_title)
                .text = title
        }
    }

    private fun configureSpinners() {
        defaultMapApp.adapter = spinnerAdapter(listOf("自动选择", "高德地图", "百度地图", "系统默认"))
        apiMode.adapter = spinnerAdapter(listOf("自动检测", "Chat Completions", "Responses"))
        ttsVoice.adapter = spinnerAdapter(listOf("正在加载可用声音…"))
    }

    private fun configureActions() {
        findViewById<Button>(R.id.save_settings).setOnClickListener { saveSettings() }
        findViewById<Button>(R.id.start_wake_service).setOnClickListener { startWakeService() }
        findViewById<Button>(R.id.stop_wake_service).setOnClickListener { stopWakeService() }
        findViewById<Button>(R.id.apply_wake_settings).setOnClickListener { applyWakeSettings() }
        findViewById<Button>(R.id.scan_tts_voices).setOnClickListener { scanTtsVoices() }
        findViewById<Button>(R.id.preview_tts).setOnClickListener { previewTts() }
        findViewById<Button>(R.id.scan_apps).setOnClickListener { scanApps() }
        findViewById<Button>(R.id.test_app).setOnClickListener { testAppMatch() }
        findViewById<Button>(R.id.request_location_permission).setOnClickListener { requestLocationPermission() }
        findViewById<Button>(R.id.open_accessibility_settings).setOnClickListener { openAccessibilitySettings() }
        findViewById<Button>(R.id.refresh_screen_intelligence).setOnClickListener { renderScreenIntelligenceStatus() }
        findViewById<Button>(R.id.request_overlay_permission).setOnClickListener { requestOverlayPermission() }
        findViewById<Button>(R.id.refresh_diagnostics).setOnClickListener {
            renderOverlayStatus()
            refreshLocationStatus()
            renderDiagnostics()
        }
        findViewById<Button>(R.id.test_ai_endpoint).setOnClickListener { testAiEndpoint() }
    }

    private fun configureInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(settingsScroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottom = maxOf(bars.bottom, ime.bottom)
            view.setPadding(dp(20), bars.top + dp(16), dp(20), bottom + dp(24))
            insets
        }
        ViewCompat.requestApplyInsets(settingsScroll)
    }

    private fun loadSettings() {
        assistantName.setText(settings.assistantName)
        wakePhrase.setText(settings.wakePhrase)
        wakeReply.setText(settings.wakeReply)
        timeoutReply.setText(settings.timeoutReply)
        timeoutSeconds.setText(settings.sessionTimeoutSeconds.toString())
        backgroundWakeEnabled.isChecked = WakeServiceController.isBackgroundWakeEnabled(this)
        preferOfflineAsr.isChecked = settings.preferOfflineAsr
        smartUiEnabled.isChecked = settings.smartUiEnabled
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
        renderRuntimeStatus(runtimeStatusStore.current)
    }

    private fun saveSettings() {
        persistSettings()
        applyTtsSettings()
        if (backgroundWakeEnabled.isChecked) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO_PERMISSION)
            } else {
                WakeServiceController.setBackgroundWakeEnabled(this, true)
                if (WakeServiceController.isRunning(this)) {
                    WakeServiceController.applyWakeSettings(this)
                } else {
                    WakeServiceController.start(this)
                }
            }
        } else {
            WakeServiceController.setBackgroundWakeEnabled(this, false)
            WakeServiceController.stop(this)
        }
        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
    }

    private fun persistSettings() {
        settings.assistantName = assistantName.text.toString()
        settings.wakePhrase = wakePhrase.text.toString()
        settings.wakeReply = wakeReply.text.toString()
        settings.timeoutReply = timeoutReply.text.toString()
        settings.sessionTimeoutSeconds = timeoutSeconds.text.toString().toIntOrNull() ?: 20
        settings.preferOfflineAsr = preferOfflineAsr.isChecked
        settings.smartUiEnabled = smartUiEnabled.isChecked
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
        WakeServiceController.setBackgroundWakeEnabled(this, backgroundWakeEnabled.isChecked)
    }

    private fun applyWakeSettings() {
        persistSettings()
        applyTtsSettings()
        applyWakeSettingsIfRunning()
    }

    private fun applyWakeSettingsIfRunning() {
        if (!WakeServiceController.isRunning(this)) {
            runtimeStatus.text = "设置已保存；唤醒服务未运行，启动后生效"
            return
        }
        WakeServiceController.applyWakeSettings(this)
        runtimeStatus.text = "已提交唤醒设置，等待服务返回实际状态…"
    }

    private fun startWakeService() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO_PERMISSION)
            return
        }
        persistSettings()
        backgroundWakeEnabled.isChecked = true
        WakeServiceController.setBackgroundWakeEnabled(this, true)
        WakeServiceController.start(this)
    }

    private fun stopWakeService() {
        backgroundWakeEnabled.isChecked = false
        persistSettings()
        WakeServiceController.setBackgroundWakeEnabled(this, false)
        WakeServiceController.stop(this)
    }

    private fun scanTtsVoices() {
        val manager = ttsVoiceManager
        if (manager == null) {
            ttsVoiceStatus.text = "TTS 引擎尚未准备好"
            return
        }
        ttsVoiceOptions = manager.availableVoices()
        val labels = ttsVoiceOptions.map { it.displayLabel }.ifEmpty { listOf("没有发现可用中文声音") }
        ttsVoice.adapter = spinnerAdapter(labels)
        val selected = ttsVoiceOptions.indexOfFirst { it.name == settings.ttsVoiceName }
        if (selected >= 0) ttsVoice.setSelection(selected)
        ttsVoiceStatus.text = "已发现 ${ttsVoiceOptions.size} 个可用声音；带“网络”的声音需要网络。"
    }

    private fun applyTtsSettings() {
        val manager = ttsVoiceManager ?: return
        val selectedName = ttsVoiceOptions.getOrNull(ttsVoice.selectedItemPosition)?.name
            ?: ttsVoiceName.text.toString().trim()
        val rate = ttsSpeechRate.text.toString().toFloatOrNull() ?: 1.0f
        val pitch = ttsPitch.text.toString().toFloatOrNull() ?: 1.0f
        val result = manager.applyVoice(selectedName, rate, pitch)
        ttsVoiceName.setText(result.appliedVoiceName)
        ttsVoiceStatus.text = result.message
    }

    private fun previewTts() {
        applyTtsSettings()
        val manager = ttsVoiceManager
        if (manager == null) {
            ttsVoiceStatus.text = "TTS 引擎尚未准备好"
            return
        }
        manager.preview("你好，我是${assistantName.text.toString().trim().ifBlank { "小智" }}。") {
            runOnUiThread { ttsVoiceStatus.text = "试听完成" }
        }
        ttsVoiceStatus.text = "正在试听…"
    }

    private fun handleTtsInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            runOnUiThread { ttsVoiceStatus.text = "TTS 引擎初始化失败" }
            return
        }
        val engine = ttsEngine ?: return
        engine.language = Locale.SIMPLIFIED_CHINESE
        ttsVoiceManager = TtsVoiceManager(engine, settings)
        val applied = ttsVoiceManager?.applySavedSettings()
        runOnUiThread {
            ttsVoiceStatus.text = applied?.message ?: "TTS 已准备好"
            scanTtsVoices()
        }
    }

    private fun scanApps() {
        appDiscoveryStatus.text = "正在扫描可启动应用…"
        backgroundExecutor.execute {
            val apps = runCatching { appRegistry.discover(force = true) }.getOrElse { emptyList() }
            runOnUiThread {
                appDiscoveryStatus.text = "已发现 ${apps.size} 个可启动应用。"
            }
        }
    }

    private fun testAppMatch() {
        val requested = appTestName.text.toString().trim()
        if (requested.isBlank()) {
            appDiscoveryStatus.text = "请输入应用名或包名。"
            return
        }
        backgroundExecutor.execute {
            val resolution = runCatching { appRegistry.resolveDetailed(requested, appAliases.text.toString()) }
                .getOrNull()
            runOnUiThread {
                appDiscoveryStatus.text = resolution?.explanation ?: "应用匹配失败"
            }
        }
    }

    private fun requestLocationPermission() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (permissions.any { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            refreshLocationStatus()
        } else {
            requestPermissions(permissions, REQUEST_LOCATION_PERMISSION)
        }
    }

    private fun refreshLocationStatus() {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            locationStatus.text = "位置权限未授权；附近搜索和导航将按安全策略提示。"
            return
        }
        locationStatus.text = "位置权限已授权，正在读取最近位置…"
        locationProvider.getCurrentLocation { result ->
            runOnUiThread {
                locationStatus.text = result.fold(
                    onSuccess = { location -> "位置可用：%.4f, %.4f".format(Locale.US, location.latitude, location.longitude) },
                    onFailure = { error -> "位置暂不可用：${error.message ?: "未知原因"}" },
                )
            }
        }
    }

    private fun renderScreenIntelligenceStatus() {
        if (!::smartUiEnabled.isInitialized) return
        val accessibilityEnabled = isXiaoZhiAccessibilityEnabled()
        accessibilityStatus.text = if (accessibilityEnabled) {
            "Accessibility：已由用户在系统设置中开启；当前页面语义信息可用。"
        } else {
            "Accessibility：未开启；请由用户在系统设置中手动开启，小白不会代替你开启。"
        }
        if (!smartUiEnabled.isChecked) {
            visionConsentStatus.text = "Vision 授权：智能界面操作总开关已关闭。"
            visionCaptureStatus.text = "当前会话屏幕捕获：未捕获。"
        } else {
            visionConsentStatus.text = "Vision 授权：仅当前会话按需请求；当前会话未获得授权。"
            visionCaptureStatus.text = "当前会话屏幕捕获：未捕获；仅在非敏感页面授权后捕获当前帧，不保存原始截图。"
        }
    }

    private fun openAccessibilitySettings() {
        runCatching {
            startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
        }.onFailure {
            Toast.makeText(this, "无法打开无障碍设置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isXiaoZhiAccessibilityEnabled(): Boolean {
        val manager = getSystemService(AccessibilityManager::class.java) ?: return false
        return runCatching {
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .asSequence()
                .mapNotNull { it.resolveInfo?.serviceInfo }
                .any {
                    it.packageName == packageName &&
                        it.name == XiaoZhiAccessibilityService::class.java.name
                }
        }.getOrDefault(false)
    }

    private fun renderOverlayStatus() {
        overlayStatus.text = if (AndroidSettings.canDrawOverlays(this)) {
            "桌面透明语音 HUD：已授权"
        } else {
            "桌面透明语音 HUD：未授权；唤醒后不会显示悬浮面板"
        }
    }

    private fun requestOverlayPermission() {
        if (AndroidSettings.canDrawOverlays(this)) {
            renderOverlayStatus()
            return
        }
        runCatching {
            startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }.onFailure {
            Toast.makeText(this, "无法打开悬浮窗授权页面", Toast.LENGTH_SHORT).show()
        }
    }

    private fun testAiEndpoint() {
        persistSettings()
        aiTestStatus.text = "正在测试 AI 接口…"
        aiClient.testEndpoint { result ->
            runOnUiThread {
                aiTestStatus.text = if (result.success) {
                    "接口正常 · HTTP ${result.httpStatus ?: "-"} · ${result.mode ?: settings.apiMode} · ${result.latencyMs}ms · ${result.reply.take(80)}"
                } else {
                    "接口测试失败 · ${result.error.take(180)}"
                }
            }
        }
    }

    private fun renderRuntimeStatus(status: WakeRuntimeStatus) {
        runtimeStatus.text = when (status) {
            WakeRuntimeStatus.STOPPED -> "唤醒服务已停止"
            WakeRuntimeStatus.STARTING -> "正在启动离线唤醒"
            WakeRuntimeStatus.KWS_LISTENING -> "等待唤醒"
            WakeRuntimeStatus.SESSION_ACTIVE -> "连续会话进行中"
            WakeRuntimeStatus.ERROR -> {
                val detail = runtimeStatusStore.detail?.takeIf { it.isNotBlank() }
                if (detail == null) "唤醒服务异常" else "唤醒服务异常：$detail"
            }
        }
    }

    private fun renderDiagnostics() {
        val runtime = runtimeStatusStore.current
        val detail = runtimeStatusStore.detail?.takeIf { it.isNotBlank() } ?: "无"
        val tts = if (ttsVoiceManager == null) "未准备" else "已准备"
        val overlay = if (AndroidSettings.canDrawOverlays(this)) "已授权" else "未授权"
        val location = if (
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) "已授权" else "未授权"
        diagnosticsOutput.text = "运行诊断：状态=$runtime；详情=$detail；实际唤醒词=${settings.activeWakePhrase}；TTS=$tts；HUD=$overlay；位置=$location"
    }

    private fun spinnerAdapter(values: List<String>): ArrayAdapter<String> =
        ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_AUDIO_PERMISSION -> {
                val audioIndex = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
                val granted = audioIndex in grantResults.indices &&
                    grantResults[audioIndex] == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    backgroundWakeEnabled.isChecked = true
                    persistSettings()
                    WakeServiceController.setBackgroundWakeEnabled(this, true)
                    WakeServiceController.start(this)
                } else {
                    backgroundWakeEnabled.isChecked = false
                    WakeServiceController.setBackgroundWakeEnabled(this, false)
                    Toast.makeText(this, "需要麦克风权限才能启动唤醒服务", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_LOCATION_PERMISSION -> refreshLocationStatus()
        }
    }

    companion object {
        private const val REQUEST_AUDIO_PERMISSION = 101
        private const val REQUEST_LOCATION_PERMISSION = 102
    }
}
