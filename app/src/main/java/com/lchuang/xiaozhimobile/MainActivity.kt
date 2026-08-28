package com.lchuang.xiaozhimobile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private lateinit var settings: SettingsStore
    private lateinit var apiUrl: EditText
    private lateinit var apiKey: EditText
    private lateinit var model: EditText
    private lateinit var testInput: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)
        setContentView(buildUi())
        loadSettings()
        requestNeededPermissions()
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
            text = "离线唤醒 · AI 对话 · 手机控制"
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(4), 0, dp(20))
        })

        status = TextView(this).apply {
            text = "v0.3.1：全离线语音 + 连续会话\n一次唤醒后可连续说多条指令，无需每句都喊“小智小智”。"
            textSize = 15f
            setTextColor(Color.rgb(34, 95, 68))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.rgb(229, 245, 236))
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        val start = Button(this).apply {
            text = "开启后台离线唤醒"
            setOnClickListener {
                saveSettings()
                requestNeededPermissions()
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    val i = Intent(this@MainActivity, WakeService::class.java)
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                    status.text = "已请求启动。保持通知栏中的“小智手机助手”运行即可息屏监听。"
                } else {
                    Toast.makeText(this@MainActivity, "请先允许麦克风权限", Toast.LENGTH_LONG).show()
                }
            }
        }
        root.addView(start, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val stop = Button(this).apply {
            text = "停止离线唤醒"
            setOnClickListener {
                stopService(Intent(this@MainActivity, WakeService::class.java))
                status.text = "离线唤醒已停止"
            }
        }
        root.addView(stop, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(22) })

        addHeader(root, "AI 对话接口")
        root.addView(TextView(this).apply {
            text = "支持 OpenAI 兼容 /v1/chat/completions 接口。手机控制无需 AI 接口。"
            textSize = 13f
            setTextColor(Color.GRAY)
        })
        apiUrl = addEdit(root, "完整接口，例如 https://example.com/v1/chat/completions")
        apiKey = addEdit(root, "API Key（本机保存）")
        model = addEdit(root, "模型名，例如 gpt-5.6 / deepseek-chat")
        root.addView(TextView(this).apply {
            text = "语音指令识别：sherpa-onnx Paraformer 本地模型（无需网络、无需手机系统 SpeechRecognizer）"
            textSize = 13f
            setTextColor(Color.rgb(34, 95, 68))
            setPadding(0, dp(8), 0, dp(8))
        })
        root.addView(Button(this).apply {
            text = "保存设置"
            setOnClickListener {
                saveSettings()
                Toast.makeText(this@MainActivity, "已保存", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(22) })

        addHeader(root, "本地手机控制测试")
        root.addView(TextView(this).apply {
            text = "可输入：播放音乐、停止音乐、暂停音乐、下一首、打开微信、打开QQ、音量大一点、打开手电筒"
            textSize = 13f
            setTextColor(Color.GRAY)
        })
        testInput = addEdit(root, "输入一条手机控制指令")
        root.addView(Button(this).apply {
            text = "直接执行（不调用 AI）"
            setOnClickListener {
                val result = CommandRouter(PhoneController(this@MainActivity)).handle(testInput.text.toString())
                Toast.makeText(
                    this@MainActivity,
                    if (result.handled) result.reply else "未匹配本地指令",
                    Toast.LENGTH_LONG
                ).show()
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(22) })

        addHeader(root, "第一版说明")
        root.addView(TextView(this).apply {
            text = "• “小智小智”由本机 KWS 模型识别，不上传持续监听音频。\n" +
                "• 唤醒后的整句中文指令由 sherpa-onnx Paraformer 本地 ASR 转文字，不调用 Android SpeechRecognizer。\n" +
                "• 播放/停止/暂停/切歌/音量/手电筒/打开 App/导航优先本地执行。\n• 唤醒一次后进入连续会话；说“再见/退出对话/休息吧”即可结束。\n" +
                "• 只有普通聊天问题才调用你可选配置的 AI 接口，并用手机 TTS 播报。\n" +
                "• Android 14+ 必须从本页面主动开启麦克风前台服务；重启手机后需重新开启。"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.START
        })
        return scroll
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

    private fun loadSettings() {
        apiUrl.setText(settings.apiUrl)
        apiKey.setText(settings.apiKey)
        model.setText(settings.model)
    }

    private fun saveSettings() {
        settings.apiUrl = apiUrl.text.toString()
        settings.apiKey = apiKey.text.toString()
        settings.model = model.text.toString()
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
}
