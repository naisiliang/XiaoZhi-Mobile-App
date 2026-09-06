package com.lchuang.xiaozhimobile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lchuang.xiaozhimobile.conversation.AssistantState
import com.lchuang.xiaozhimobile.conversation.AssistantStateStore
import com.lchuang.xiaozhimobile.conversation.AssistantStateStoreProvider
import com.lchuang.xiaozhimobile.conversation.ConversationAdapter
import com.lchuang.xiaozhimobile.conversation.ConversationHistoryActivity
import com.lchuang.xiaozhimobile.conversation.ConversationMessage
import com.lchuang.xiaozhimobile.conversation.ConversationRepository
import com.lchuang.xiaozhimobile.conversation.ConversationResultBridge
import com.lchuang.xiaozhimobile.conversation.ConversationResultKind
import com.lchuang.xiaozhimobile.conversation.ConversationSession
import com.lchuang.xiaozhimobile.conversation.ConversationSessionManager
import com.lchuang.xiaozhimobile.conversation.ConversationSessionStore
import com.lchuang.xiaozhimobile.runtime.WakeRuntimeStatus
import com.lchuang.xiaozhimobile.runtime.WakeRuntimeStatusStore
import com.lchuang.xiaozhimobile.runtime.WakeRuntimeStatusStoreProvider
import com.lchuang.xiaozhimobile.runtime.WakeServiceController

class MainActivity : Activity() {
    private lateinit var repository: ConversationRepository
    private lateinit var sessionManager: ConversationSessionManager
    private lateinit var stateStore: AssistantStateStore
    private lateinit var runtimeStatusStore: WakeRuntimeStatusStore
    private lateinit var conversationAdapter: ConversationAdapter
    private lateinit var composer: EditText
    private lateinit var status: TextView
    private var currentSession: ConversationSession? = null
    private var assistantState = AssistantState.WAITING_WAKE
    private var runtimeStatus = WakeRuntimeStatus.STOPPED
    private var removeSessionObserver: (() -> Unit)? = null
    private var removeStateObserver: (() -> Unit)? = null
    private var removeRuntimeObserver: (() -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateObserver: (AssistantState) -> Unit = { state ->
        mainHandler.post {
            assistantState = state
            renderStatus()
        }
    }
    private val runtimeObserver: (WakeRuntimeStatus) -> Unit = { state ->
        mainHandler.post {
            runtimeStatus = state
            renderStatus()
        }
    }
    private val sessionObserver: (ConversationSession) -> Unit = { session ->
        mainHandler.post {
            if (!::conversationAdapter.isInitialized) return@post
            currentSession = session.takeIf { it.status == ConversationSession.Status.ACTIVE }
            conversationAdapter.submitSession(currentSession)
        }
    }
    private val resultSink = ConversationResultBridge.Sink { result ->
        mainHandler.post {
            when (result.kind) {
                ConversationResultKind.TEXT,
                ConversationResultKind.VOICE,
                -> Unit
                ConversationResultKind.OPERATION -> appendToCurrentSession(ConversationMessage.Role.ASSISTANT, result.text)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = ConversationSessionStore.repository(this)
        sessionManager = ConversationSessionStore.manager(this)
        stateStore = AssistantStateStoreProvider.instance()
        runtimeStatusStore = WakeRuntimeStatusStoreProvider.instance()
        setContentView(buildChatHome())
        stateStore.addObserver(stateObserver)
        removeStateObserver = { stateStore.removeObserver(stateObserver) }
        runtimeStatusStore.addObserver(runtimeObserver)
        removeRuntimeObserver = { runtimeStatusStore.removeObserver(runtimeObserver) }
        assistantState = stateStore.current
        runtimeStatus = runtimeStatusStore.current
        renderStatus()
        currentSession = sessionManager.currentSession() ?: repository.loadCurrent()
        conversationAdapter.submitSession(currentSession)
        ConversationSessionStore.observe(this, sessionObserver)
        removeSessionObserver = { ConversationSessionStore.removeObserver(this, sessionObserver) }
        ConversationResultBridge.registerSink(resultSink)
        requestNeededPermissions()
    }

    override fun onDestroy() {
        ConversationResultBridge.unregisterSink(resultSink)
        removeSessionObserver?.invoke()
        removeSessionObserver = null
        removeStateObserver?.invoke()
        removeStateObserver = null
        removeRuntimeObserver?.invoke()
        removeRuntimeObserver = null
        super.onDestroy()
    }

    private fun buildChatHome(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(16))
            setBackgroundColor(Color.rgb(247, 248, 250))
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "小智对话"
            textSize = 26f
            setTextColor(Color.rgb(20, 24, 33))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = "⋮"
            textSize = 30f
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { showHomeMenu(it) }
        })
        root.addView(header)

        status = TextView(this).apply {
            text = "v0.6.5：会话状态机 + 悬浮层手动退出 + 智能退出 + 自然语言媒体音量"
            textSize = 14f
            setTextColor(Color.rgb(34, 95, 68))
            setPadding(0, dp(6), 0, dp(10))
        }
        root.addView(status)

        conversationAdapter = ConversationAdapter()
        root.addView(RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = conversationAdapter
            setHasFixedSize(false)
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        val composerRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        composer = EditText(this).apply {
            hint = "输入消息"
            setSingleLine(false)
            maxLines = 3
        }
        composerRow.addView(composer, LinearLayout.LayoutParams(0, -2, 1f))
        composerRow.addView(TextView(this).apply {
            text = "发送"
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { submitText() }
        })
        root.addView(composerRow)
        return root
    }

    private fun showHomeMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("新会话").setOnMenuItemClickListener {
                startNewSession()
                true
            }
            menu.add("历史会话").setOnMenuItemClickListener {
                startActivity(Intent(this@MainActivity, ConversationHistoryActivity::class.java))
                true
            }
            menu.add("插件与技能").setOnMenuItemClickListener {
                showUnavailable("插件与技能")
                true
            }
            menu.add("Agents").setOnMenuItemClickListener {
                showUnavailable("Agents")
                true
            }
            menu.add("设置").setOnMenuItemClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                true
            }
            show()
        }
    }

    private fun startNewSession() {
        sessionManager.endSession("new-session")
        currentSession = null
        conversationAdapter.submitSession(null)
    }

    private fun showUnavailable(name: String) {
        Toast.makeText(this, "${name}将在后续版本接入", Toast.LENGTH_SHORT).show()
    }

    private fun submitText() {
        val text = composer.text.toString().trim()
        if (text.isBlank()) return
        onTextResult(text)
        composer.text.clear()
    }

    fun onTextResult(text: String) {
        WakeServiceController.submitText(this, text)
    }

    fun onVoiceResult(text: String) {
        ConversationResultBridge.submitVoice(text)
    }

    fun onOperationResult(text: String) {
        ConversationResultBridge.submitOperation(text)
    }

    private fun appendToCurrentSession(role: ConversationMessage.Role, text: String) {
        val managerSession = sessionManager.currentSession()
        if (managerSession == null || managerSession.status != ConversationSession.Status.ACTIVE) {
            sessionManager.startWakeSession()
        }
        val updated = when (role) {
            ConversationMessage.Role.USER -> sessionManager.appendUser(text)
            ConversationMessage.Role.ASSISTANT -> sessionManager.appendAssistant(text)
            ConversationMessage.Role.SYSTEM_ACTION -> sessionManager.appendSystemAction(text)
            ConversationMessage.Role.SYSTEM_RESULT -> sessionManager.appendSystemResult(text)
            ConversationMessage.Role.CONFIRMATION -> sessionManager.appendConfirmation(text)
        }
        currentSession = updated
        conversationAdapter.submitSession(updated)
    }

    private fun stateLabel(state: AssistantState): String = when (state) {
        AssistantState.WAITING_WAKE -> "等待唤醒"
        AssistantState.LISTENING -> "正在聆听"
        AssistantState.RECOGNIZING -> "正在识别"
        AssistantState.EXECUTING -> "正在执行"
        AssistantState.SPEAKING -> "正在回答"
        AssistantState.WAITING_CONFIRMATION -> "等待确认"
    }

    private fun renderStatus() {
        if (!::status.isInitialized) return
        status.text = when (runtimeStatus) {
            WakeRuntimeStatus.KWS_LISTENING -> stateLabel(assistantState)
            WakeRuntimeStatus.SESSION_ACTIVE -> {
                if (assistantState == AssistantState.WAITING_WAKE) {
                    "连续会话进行中"
                } else {
                    stateLabel(assistantState)
                }
            }
            WakeRuntimeStatus.STARTING -> "正在启动离线唤醒"
            WakeRuntimeStatus.STOPPED -> "唤醒服务已停止"
            WakeRuntimeStatus.ERROR -> {
                val detail = runtimeStatusStore.detail?.takeIf { it.isNotBlank() }
                if (detail == null) "唤醒服务异常" else "唤醒服务异常：$detail"
            }
        }
    }

    private fun requestNeededPermissions() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (WakeServiceController.isBackgroundWakeEnabled(this)) {
                WakeServiceController.start(this)
            }
        }
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
        if (permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        val audioPermissionIndex = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
        if (audioPermissionIndex < 0 || audioPermissionIndex >= grantResults.size) return
        if (
            permissions[audioPermissionIndex] == Manifest.permission.RECORD_AUDIO &&
            grantResults[audioPermissionIndex] == PackageManager.PERMISSION_GRANTED &&
            WakeServiceController.isBackgroundWakeEnabled(this)
        ) {
            WakeServiceController.start(this)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_PERMISSIONS = 100
    }
}
