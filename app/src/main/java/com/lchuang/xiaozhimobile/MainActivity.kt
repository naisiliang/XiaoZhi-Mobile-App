package com.lchuang.xiaozhimobile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
    private lateinit var settings: SettingsStore
    private lateinit var repository: ConversationRepository
    private lateinit var sessionManager: ConversationSessionManager
    private lateinit var stateStore: AssistantStateStore
    private lateinit var runtimeStatusStore: WakeRuntimeStatusStore
    private lateinit var conversationAdapter: ConversationAdapter
    private lateinit var composer: EditText
    private lateinit var status: TextView
    private lateinit var assistantTitle: TextView
    private lateinit var chatRoot: View
    private lateinit var chatHeader: View
    private lateinit var composerContainer: View
    private lateinit var conversationList: RecyclerView
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
        settings = SettingsStore(this)
        repository = ConversationSessionStore.repository(this)
        sessionManager = ConversationSessionStore.manager(this)
        stateStore = AssistantStateStoreProvider.instance()
        runtimeStatusStore = WakeRuntimeStatusStoreProvider.instance()
        setContentView(R.layout.activity_main_chat)
        bindChatViews()
        configureQuickActions()
        configureInsets()
        renderAssistantTitle()
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

    override fun onResume() {
        super.onResume()
        if (::assistantTitle.isInitialized) renderAssistantTitle()
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

    private fun bindChatViews() {
        chatRoot = findViewById(R.id.chat_root)
        chatHeader = findViewById(R.id.chat_header)
        composerContainer = findViewById(R.id.composer_container)
        assistantTitle = findViewById(R.id.assistant_title)
        status = findViewById(R.id.assistant_status)
        composer = findViewById(R.id.message_input)
        conversationList = findViewById(R.id.conversation_list)
        conversationAdapter = ConversationAdapter()
        conversationList.layoutManager = LinearLayoutManager(this)
        conversationList.adapter = conversationAdapter
        conversationList.setHasFixedSize(false)
        findViewById<TextView>(R.id.home_navigation).setOnClickListener { showHomeMenu(it) }
        findViewById<TextView>(R.id.home_menu).setOnClickListener { showHomeMenu(it) }
    }

    private fun configureQuickActions() {
        mapOf(
            R.id.quick_open_app to "请认真思考后回答",
            R.id.quick_nearby to "打给小白",
            R.id.quick_music to "帮我写作",
            R.id.quick_volume to "请总结当前内容",
        ).forEach { (viewId, command) ->
            findViewById<TextView>(viewId).setOnClickListener { onTextResult(command) }
        }
        findViewById<TextView>(R.id.send_message).setOnClickListener { submitText() }
    }

    private fun configureInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(chatRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            chatHeader.setPadding(dp(16), bars.top + dp(12), dp(12), dp(8))
            composerContainer.setPadding(
                dp(12),
                dp(6),
                dp(8),
                maxOf(bars.bottom, ime.bottom) + dp(6),
            )
            insets
        }
        ViewCompat.requestApplyInsets(chatRoot)
    }

    private fun renderAssistantTitle() {
        val assistantName = settings.assistantName
        assistantTitle.text = "${assistantName}智能体"
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
