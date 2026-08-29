package com.lchuang.xiaozhimobile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private lateinit var settings: SettingsStore
    private lateinit var desktopIconManager: DesktopIconManager

    private lateinit var apiUrl: EditText
    private lateinit var apiKey: EditText
    private lateinit var model: EditText
    private lateinit var wakeReply: EditText
    private lateinit var timeoutReply: EditText
    private lateinit var timeoutSeconds: EditText
    private lateinit var appAliases: EditText
    private lateinit var testInput: EditText
    private lateinit var status: TextView
    private lateinit var appCount: TextView
    private lateinit var overlayPermissionButton: Button
    private lateinit var iconPreview: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)
        desktopIconManager = DesktopIconManager(this)
        setContentView(buildUi())
        loadSettings()
        refreshIconPreview()
        refreshInstalledAppCount()
        requestNeededPermissions()
    }

    override fun onResume() {
        super.onResume()
        if (::overlayPermissionButton.isInitialized) refreshOverlayPermissionButton()
        if (::appCount.isInitialized) refreshInstalledAppCount()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_DESKTOP_ICON || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val result = desktopIconManager.applyCustomIcon(uri)
        result.onSuccess {
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
            text = "离线唤醒 · 连续对话 · 手机控制"
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(4), 0, dp(20))
        })

        status = TextView(this).apply {
            text = "v0.5.0：可配置连续会话 + 任意已安装 App 识别 + 自定义桌面图标"
            textSize = 15f
            setTextColor(Color.rgb(34, 95, 68))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.rgb(229, 245, 236))
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        addHeader(root, "语音助手")
        root.addView(TextView(this).apply {
            text = "唤醒后进入连续会话；无人提出新要求达到设置时间后自动退出。"
            textSize = 13f
            setTextColor(Color.GRAY)
        })
        wakeReply = addEdit(root, "唤醒后第一句，例如：我在")
        timeoutReply = addEdit(root, "超时退出话术")
        timeoutSeconds = addEdit(root, "连续会话超时秒数，默认 20").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        overlayPermissionButton = Button(this).apply {
            setOnClickListener {
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "桌面透明语音悬浮层已授权", Toast.LENGTH_SHORT).show()
                } else {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
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
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                    status.text = "离线助手已启动。说“小智小智”即可唤醒。"
                } else {
                    Toast.makeText(this@MainActivity, "请先允许麦克风权限", Toast.LENGTH_LONG).show()
                }
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        root.addView(Button(this).apply {
            text = "停止离线唤醒"
            setOnClickListener {
                stopService(Intent(this@MainActivity, WakeService::class.java))
                status.text = "离线唤醒已停止"
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(22) })

        addHeader(root, "手机应用识别")
        appCount = TextView(this).apply {
            text = "正在扫描手机已安装应用…"
            textSize = 13f
            setTextColor(Color.rgb(34, 95, 68))
        }
        root.addView(appCount)
        root.addView(TextView(this).apply {
            text = "小智会扫描手机所有可启动 App，并用名称/模糊匹配打开。你还可以设置口语别名，每行一个，例如：\nB站=哔哩哔哩\n小破站=哔哩哔哩\n夸克浏览器=夸克"
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, dp(5), 0, dp(4))
        })
        appAliases = addMultilineEdit(root, "App 别名，每行：别名=真实应用名", 5)

        addHeader(root, "AI 对话接口")
        root.addView(TextView(this).apply {
            text = "可选。手机控制不需要 AI 接口；普通问题在配置后才会交给 AI。"
            textSize = 13f
            setTextColor(Color.GRAY)
        })
        apiUrl = addEdit(root, "完整接口，例如 https://example.com/v1/chat/completions")
        apiKey = addEdit(root, "API Key（仅保存在本机）")
        model = addEdit(root, "模型名，例如 gpt-5.6 / deepseek-chat")
        root.addView(TextView(this).apply {
            text = "语音指令识别：sherpa-onnx Paraformer 本地模型（无需网络、无需系统 SpeechRecognizer）"
            textSize = 13f
            setTextColor(Color.rgb(34, 95, 68))
            setPadding(0, 6, 0, 6)
        })

        root.addView(Button(this).apply {
            text = "保存全部设置"
            setOnClickListener {
                saveSettings()
                refreshInstalledAppCount()
                Toast.makeText(this@MainActivity, "设置已保存", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(22) })

        addHeader(root, "个性化桌面图标")
        root.addView(TextView(this).apply {
            text = "应用默认图标使用蓝粉渐变 Logo。自定义图片会创建/更新一个桌面快捷图标；系统应用抽屉和设置页仍保持默认 Logo。"
            textSize = 13f
            setTextColor(Color.GRAY)
        })
        iconPreview = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(235, 238, 244))
        }
        root.addView(iconPreview, LinearLayout.LayoutParams(dp(96), dp(96)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(12)
            bottomMargin = dp(10)
        })
        root.addView(Button(this).apply {
            text = "从相册选择桌面图标"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                }
                startActivityForResult(intent, REQUEST_PICK_DESKTOP_ICON)
            }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "恢复默认 Logo"
            setOnClickListener {
                desktopIconManager.restoreDefault()
                    .onSuccess {
                        refreshIconPreview()
                        Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show()
                    }
                    .onFailure {
                        Toast.makeText(this@MainActivity, "恢复失败：${it.message}", Toast.LENGTH_LONG).show()
                    }
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(22) })

        addHeader(root, "本地手机控制测试")
        root.addView(TextView(this).apply {
            text = "可输入：播放音乐、停止音乐、打开微信、打开QQ、打开小红书、打开夸克、音量大一点、打开手电筒等。"
            textSize = 13f
            setTextColor(Color.GRAY)
        })
        testInput = addEdit(root, "输入一条手机控制指令")
        root.addView(Button(this).apply {
            text = "直接执行（不调用 AI）"
            setOnClickListener {
                saveSettings()
                val result = CommandRouter(PhoneController(this@MainActivity)).handle(
                    VoiceCommandNormalizer.normalize(testInput.text.toString())
                )
                Toast.makeText(
                    this@MainActivity,
                    if (result.handled && result.success) result.reply else "抱歉，我还不会这个指令，你可以换一个指令继续服务你",
                    Toast.LENGTH_LONG
                ).show()
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(22) })

        addHeader(root, "v0.5.0 说明")
        root.addView(TextView(this).apply {
            text = "• 默认连续会话超时为 20 秒，可自行修改。\n" +
                "• 唤醒第一句话和超时退出话术都可自定义。\n" +
                "• 本地手机指令执行后立即继续监听，不需要重新唤醒。\n" +
                "• 已安装 App 通过本机 PackageManager 扫描并模糊匹配；可设置别名。\n" +
                "• 无法执行的手机指令会给出统一提示并继续当前会话。\n" +
                "• KWS 和中文指令 ASR 仍全部在手机本地运行。"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.START
        })
        return scroll
    }

    private fun refreshOverlayPermissionButton() {
        overlayPermissionButton.text = if (Settings.canDrawOverlays(this)) {
            "桌面透明语音悬浮层：已授权"
        } else {
            "授权桌面透明语音悬浮层"
        }
    }

    private fun refreshInstalledAppCount() {
        Thread {
            val count = try { PhoneController(this).installedAppCount() } catch (_: Throwable) { -1 }
            runOnUiThread {
                appCount.text = if (count >= 0) "已发现可启动应用：$count 个" else "应用扫描失败，请稍后重试"
            }
        }.start()
    }

    private fun refreshIconPreview() {
        try {
            iconPreview.setImageBitmap(desktopIconManager.currentBitmap())
        } catch (_: Throwable) {
            iconPreview.setImageResource(R.mipmap.ic_launcher)
        }
    }

    private fun addHeader(root: LinearLayout, textValue: String) {
        root.addView(TextView(this).apply {
            text = textValue
            textSize = 19f
            setTextColor(Color.rgb(30, 36, 48))
            setPadding(0, 8, 0, 8)
        })
    }

    private fun addEdit(root: LinearLayout, hintValue: String): EditText {
        return EditText(this).also { e ->
            e.hint = hintValue
            e.setSingleLine(true)
            root.addView(e, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun addMultilineEdit(root: LinearLayout, hintValue: String, minLinesValue: Int): EditText {
        return EditText(this).also { e ->
            e.hint = hintValue
            e.minLines = minLinesValue
            e.gravity = Gravity.TOP or Gravity.START
            e.setSingleLine(false)
            root.addView(e, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun loadSettings() {
        apiUrl.setText(settings.apiUrl)
        apiKey.setText(settings.apiKey)
        model.setText(settings.model)
        wakeReply.setText(settings.wakeReply)
        timeoutReply.setText(settings.timeoutReply)
        timeoutSeconds.setText(settings.sessionTimeoutSeconds.toString())
        appAliases.setText(settings.appAliases)
    }

    private fun saveSettings() {
        settings.apiUrl = apiUrl.text.toString()
        settings.apiKey = apiKey.text.toString()
        settings.model = model.text.toString()
        settings.wakeReply = wakeReply.text.toString().ifBlank { "我在" }
        settings.timeoutReply = timeoutReply.text.toString().ifBlank { "我先退下了，有问题再唤醒我" }
        settings.sessionTimeoutSeconds = timeoutSeconds.text.toString().toIntOrNull() ?: 20
        settings.appAliases = appAliases.text.toString()
        timeoutSeconds.setText(settings.sessionTimeoutSeconds.toString())
    }

    private fun requestNeededPermissions() {
        val permissions = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions += Manifest.permission.RECORD_AUDIO
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions += Manifest.permission.CAMERA
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        if (permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(), 100)
    }

    companion object {
        private const val REQUEST_PICK_DESKTOP_ICON = 201
    }
}
