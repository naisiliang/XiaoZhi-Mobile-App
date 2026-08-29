package com.lchuang.xiaozhimobile

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class WakeService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val CHANNEL_ID = "xiaozhi_wake"
        const val NOTIFY_ID = 1001
        const val ACTION_STOP = "com.lchuang.xiaozhimobile.STOP"
        const val ACTION_APPLY_WAKE_SETTINGS = "com.lchuang.xiaozhimobile.APPLY_WAKE_SETTINGS"
        private const val SAMPLE_RATE = 16000
        private const val KWS_MODEL_DIR = "sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20"
        private const val ASR_MODEL_DIR = "sherpa-onnx-paraformer-zh-small-2024-03-09"
        private const val DEFAULT_WAKE_PHRASE = "小智小智"
        private const val COMMAND_LISTEN_DELAY_MS = 700L
        private const val COMMAND_RETRY_DELAY_MS = 500L
        private const val CONTINUOUS_LISTEN_DELAY_MS = 550L
        private const val IMMEDIATE_LISTEN_DELAY_MS = 120L
        private const val IDLE_RELISTEN_DELAY_MS = 120L
        private const val UNKNOWN_COMMAND_REPLY = "抱歉，我还不会这个指令，你可以换一个指令继续服务你"
        private const val MAX_COMMAND_RECOGNITION_ATTEMPTS = 2
        private const val COMMAND_FRAME_SAMPLES = 800 // 50 ms @ 16 kHz
        private const val COMMAND_WAIT_SPEECH_MS = 4000
        private const val COMMAND_MAX_AUDIO_MS = 8000
        private const val COMMAND_END_SILENCE_MS = 900
        private const val PRE_ROLL_FRAMES = 6 // 300 ms
        private const val SPEECH_RMS_THRESHOLD = 0.0105f
        private const val SILENCE_RMS_THRESHOLD = 0.0075f
    }

    private val running = AtomicBoolean(false)
    private val kwsListening = AtomicBoolean(false)
    private val commandListening = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val session = SessionController()

    private lateinit var settings: SettingsStore
    private lateinit var installedAppRegistry: InstalledAppRegistry
    private lateinit var appLauncher: AppLauncher
    private lateinit var locationProvider: LocationProvider
    private lateinit var mapController: MapController
    private lateinit var phone: PhoneController
    private lateinit var router: CommandRouter
    private lateinit var ai: AiClient
    private lateinit var aiOrchestrator: AiOrchestrator
    private lateinit var safeToolExecutor: SafeToolExecutor
    private lateinit var memory: AiConversationMemory
    private lateinit var overlay: AssistantOverlayController
    private lateinit var wakePhraseManager: WakePhraseManager

    private var tts: TextToSpeech? = null
    private var ttsVoiceManager: TtsVoiceManager? = null
    @Volatile private var ttsReady = false
    private var commandRecognitionAttempts = 0
    private var conversationActive = false
    private var conversationTurns = 0

    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var offlineRecognizer: OfflineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var kwsThread: Thread? = null
    private var commandThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        installedAppRegistry = InstalledAppRegistry(this)
        appLauncher = AppLauncher(this)
        locationProvider = LocationProvider(this)
        mapController = MapController(this, locationProvider)
        phone = PhoneController(this, installedAppRegistry, appLauncher, mapController)
        router = CommandRouter(phone)
        ai = AiClient(settings)
        aiOrchestrator = AiOrchestrator(settings, ai)
        safeToolExecutor = SafeToolExecutor(phone)
        memory = AiConversationMemory(maxTurns = 8)
        overlay = AssistantOverlayController(this)
        wakePhraseManager = WakePhraseManager(this, KWS_MODEL_DIR)
        tts = TextToSpeech(this, this)
        createNotificationChannel()

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "XiaoZhiMobile:WakeLock")
        wakeLock?.setReferenceCounted(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_APPLY_WAKE_SETTINGS && running.get()) {
            Thread {
                val requested = settings.wakePhrase.trim().ifBlank { DEFAULT_WAKE_PHRASE }
                stopKwsCapture()
                val applied = if (requested == DEFAULT_WAKE_PHRASE) {
                    wakePhraseManager.applyBundledPhrase(DEFAULT_WAKE_PHRASE)
                } else {
                    updateNotification("正在应用自定义唤醒词（3/3）· “$requested”")
                    wakePhraseManager.applyPhrase(requested)
                }
                stream = wakePhraseManager.currentStream()
                val active = wakePhraseManager.activePhrase()
                updateNotification(if (applied.isSuccess) "全离线语音已开启 · 说“$active”" else "唤醒词应用失败，继续监听“$active”")
                mainHandler.postDelayed({ startKwsCapture() }, 250L)
            }.start()
            return START_STICKY
        }

        startForeground(NOTIFY_ID, notification("准备启动本地语音…"))
        if (running.compareAndSet(false, true)) {
            if (wakeLock?.isHeld != true) wakeLock?.acquire()
            Thread {
                try {
                    updateNotification("正在加载离线唤醒模型（1/3）")
                    initKeywordSpotter()
                    updateNotification("正在加载离线语音识别模型（2/3）")
                    initOfflineAsr()

                    val requested = settings.wakePhrase.trim().ifBlank { DEFAULT_WAKE_PHRASE }
                    if (settings.wakePhrase != DEFAULT_WAKE_PHRASE && requested != DEFAULT_WAKE_PHRASE) {
                        updateNotification("正在应用自定义唤醒词（3/3）· “$requested”")
                        val applied = wakePhraseManager.applyPhrase(requested)
                        if (applied.isFailure) {
                            updateNotification("自定义唤醒词应用失败，已回退“小智小智”")
                        }
                        stream = wakePhraseManager.currentStream()
                    }

                    updateNotification("全离线语音已开启 · 说“${wakePhraseManager.activePhrase()}”")
                    startKwsCapture()
                } catch (e: Throwable) {
                    updateNotification("本地语音启动失败：${e.message ?: e.javaClass.simpleName}")
                }
            }.start()
        }
        return START_STICKY
    }

    private fun initKeywordSpotter() {
        if (spotter != null) return
        val model = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = "$KWS_MODEL_DIR/encoder-epoch-13-avg-2-chunk-16-left-64.onnx",
                decoder = "$KWS_MODEL_DIR/decoder-epoch-13-avg-2-chunk-16-left-64.onnx",
                joiner = "$KWS_MODEL_DIR/joiner-epoch-13-avg-2-chunk-16-left-64.onnx"
            ),
            tokens = "$KWS_MODEL_DIR/tokens.txt",
            numThreads = 1,
            debug = false,
            provider = "cpu",
            modelType = "",
            modelingUnit = "cjkchar"
        )
        val config = KeywordSpotterConfig(
            modelConfig = model,
            maxActivePaths = 4,
            keywordsFile = "keywords.txt",
            keywordsScore = 1.5f,
            keywordsThreshold = 0.20f,
            numTrailingBlanks = 1
        )
        spotter = KeywordSpotter(assets, config)
        wakePhraseManager.attachSpotter(spotter!!)
        val bundledStream = spotter!!.createStream()
        wakePhraseManager.adoptBundledStream(DEFAULT_WAKE_PHRASE, bundledStream)
        stream = bundledStream
    }

    private fun initOfflineAsr() {
        if (offlineRecognizer != null) return
        val model = OfflineModelConfig(
            paraformer = OfflineParaformerModelConfig(
                model = "$ASR_MODEL_DIR/model.int8.onnx"
            ),
            tokens = "$ASR_MODEL_DIR/tokens.txt",
            numThreads = 2,
            debug = false,
            provider = "cpu",
            modelType = "paraformer"
        )
        val config = OfflineRecognizerConfig(
            modelConfig = model,
            decodingMethod = "greedy_search"
        )
        offlineRecognizer = OfflineRecognizer(assets, config)
    }

    private fun newAudioRecord(): AudioRecord {
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val size = maxOf(min, SAMPLE_RATE / 5 * 2)
        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            size
        )
    }

    private fun startKwsCapture() {
        if (!running.get() || kwsListening.get() || commandListening.get()) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateNotification("缺少麦克风权限，请打开 App 授权")
            return
        }
        kwsListening.set(true)
        kwsThread = Thread({
            var wakeDetected = false
            try {
                val record = newAudioRecord()
                audioRecord = record
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioRecord 初始化失败")
                }
                record.startRecording()
                val shorts = ShortArray(1600) // 100 ms
                while (running.get() && kwsListening.get()) {
                    val n = record.read(shorts, 0, shorts.size)
                    if (n <= 0) continue
                    val samples = FloatArray(n)
                    for (i in 0 until n) samples[i] = shorts[i] / 32768.0f
                    val s = stream ?: break
                    s.acceptWaveform(samples, SAMPLE_RATE)
                    val k = spotter ?: break
                    while (k.isReady(s)) {
                        k.decode(s)
                        val result = k.getResult(s)
                        if (result.keyword.isNotBlank()) {
                            k.reset(s)
                            if (result.keyword == wakePhraseManager.activePhrase()) {
                                wakeDetected = true
                                kwsListening.set(false)
                                break
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                if (running.get()) updateNotification("唤醒监听异常：${e.message ?: e.javaClass.simpleName}")
            } finally {
                releaseAudioRecord()
                if (running.get() && wakeDetected) {
                    mainHandler.post { handleWakeDetected() }
                }
            }
        }, "xiaozhi-kws")
        kwsThread?.start()
    }

    private fun stopKwsCapture() {
        kwsListening.set(false)
        try { audioRecord?.stop() } catch (_: Throwable) {}
        releaseAudioRecord()
    }

    private fun releaseAudioRecord() {
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
    }

    private fun handleWakeDetected() {
        if (!running.get()) return
        commandRecognitionAttempts = 0
        conversationActive = true
        conversationTurns = 0
        memory.startSession()
        session.start(settings.sessionTimeoutSeconds)
        updateNotification("已唤醒 · 连续会话已开启")
        overlay.show()
        overlay.update("你好，有什么可以帮你？", "我在听…")
        val wakeReply = settings.wakeReply.ifBlank { "我在" }
        speakThen(wakeReply) {
            session.touch(settings.sessionTimeoutSeconds)
            mainHandler.postDelayed({ startLocalCommandRecognition() }, COMMAND_LISTEN_DELAY_MS)
        }
    }

    private fun startLocalCommandRecognition() {
        if (!running.get() || commandListening.get()) return
        if (!conversationActive || session.isExpired()) {
            finishSessionForTimeout()
            return
        }
        commandRecognitionAttempts += 1
        commandListening.set(true)
        updateNotification("本地语音识别 · 请说指令或问题…")
        overlay.update("你好，有什么可以帮你？", "我在听…")
        commandThread = Thread({
            try {
                val samples = captureCommandAudio()
                if (samples.isEmpty()) {
                    mainHandler.post { continueIdleListening() }
                    return@Thread
                }
                mainHandler.post {
                    updateNotification("本地语音识别 · 正在转文字…")
                    overlay.update("你好，有什么可以帮你？", "正在识别…")
                }
                val text = decodeLocalCommand(samples)
                mainHandler.post {
                    if (text.isBlank()) retryLocalCommandRecognition("NO_MATCH")
                    else processUtterance(text)
                }
            } catch (e: Throwable) {
                val reason = e.message ?: e.javaClass.simpleName
                mainHandler.post {
                    updateNotification("本地语音识别失败：$reason")
                    retryLocalCommandRecognition("LOCAL_ASR_ERROR")
                }
            } finally {
                commandListening.set(false)
                releaseAudioRecord()
            }
        }, "xiaozhi-local-asr")
        commandThread?.start()
    }

    private fun captureCommandAudio(): FloatArray {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException("PERMISSION")
        }
        val record = newAudioRecord()
        audioRecord = record
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("AUDIO_INIT")
        }
        record.startRecording()

        val remainingAtStart = session.remainingMs()
        if (remainingAtStart <= 0L) {
            try { record.stop() } catch (_: Throwable) {}
            return FloatArray(0)
        }
        val waitSpeechBudgetMs = minOf(COMMAND_WAIT_SPEECH_MS.toLong(), remainingAtStart).toInt()
        val maxSamples = SAMPLE_RATE * COMMAND_MAX_AUDIO_MS / 1000
        val output = ShortArray(maxSamples)
        var outputSize = 0
        val frame = ShortArray(COMMAND_FRAME_SAMPLES)
        val preRoll = ArrayDeque<ShortArray>()
        var speechStarted = false
        var speechFrames = 0
        var silenceMs = 0
        var waitedMs = 0

        while (running.get() && commandListening.get() && outputSize < maxSamples) {
            val n = record.read(frame, 0, frame.size)
            if (n <= 0) continue
            val rms = frameRms(frame, n)
            overlay.updateAudioLevel(normalizeOverlayAudioLevel(rms))
            val frameMs = n * 1000 / SAMPLE_RATE

            if (!speechStarted) {
                preRoll.addLast(frame.copyOf(n))
                while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                waitedMs += frameMs
                if (rms >= SPEECH_RMS_THRESHOLD) {
                    speechFrames += 1
                    if (speechFrames >= 2) {
                        speechStarted = true
                        session.touch(settings.sessionTimeoutSeconds)
                        for (chunk in preRoll) {
                            val count = minOf(chunk.size, maxSamples - outputSize)
                            chunk.copyInto(output, outputSize, 0, count)
                            outputSize += count
                            if (outputSize >= maxSamples) break
                        }
                        preRoll.clear()
                    }
                } else {
                    speechFrames = 0
                }
                if (!speechStarted && waitedMs >= waitSpeechBudgetMs) break
                continue
            }

            val count = minOf(n, maxSamples - outputSize)
            frame.copyInto(output, outputSize, 0, count)
            outputSize += count

            if (rms < SILENCE_RMS_THRESHOLD) silenceMs += frameMs else silenceMs = 0
            if (silenceMs >= COMMAND_END_SILENCE_MS && outputSize >= SAMPLE_RATE / 2) break
        }

        try { record.stop() } catch (_: Throwable) {}
        overlay.updateAudioLevel(0.08f)
        if (!speechStarted || outputSize == 0) return FloatArray(0)
        return FloatArray(outputSize) { i -> output[i] / 32768.0f }
    }

    private fun normalizeOverlayAudioLevel(rms: Float): Float {
        return ((rms - 0.0035f) / 0.045f).coerceIn(0f, 1f)
    }

    private fun frameRms(samples: ShortArray, n: Int): Float {
        if (n <= 0) return 0f
        var sum = 0.0
        for (i in 0 until n) {
            val v = samples[i] / 32768.0
            sum += v * v
        }
        return sqrt(sum / n).toFloat()
    }

    private fun decodeLocalCommand(samples: FloatArray): String {
        val recognizer = offlineRecognizer ?: throw IllegalStateException("ASR_NOT_READY")
        val localStream = recognizer.createStream()
        return try {
            localStream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(localStream)
            recognizer.getResult(localStream).text.trim()
        } finally {
            localStream.release()
        }
    }

    private fun continueIdleListening() {
        if (!running.get() || !conversationActive) return
        commandRecognitionAttempts = 0
        if (session.isExpired()) {
            finishSessionForTimeout()
            return
        }
        updateNotification("连续会话中 · 等待下一条指令")
        overlay.update("你好，有什么可以帮你？", "我在听…", "等待下一条指令")
        mainHandler.postDelayed({ startLocalCommandRecognition() }, IDLE_RELISTEN_DELAY_MS)
    }

    private fun retryLocalCommandRecognition(reason: String) {
        if (!running.get()) return
        if (session.isExpired()) {
            finishSessionForTimeout()
            return
        }

        if (commandRecognitionAttempts < MAX_COMMAND_RECOGNITION_ATTEMPTS) {
            updateNotification("没有听清($reason) · 将再次本地听取指令")
            overlay.update("你好，有什么可以帮你？", "没有听清，请再说一次…")
            speakThen("没有听清，请直接说指令") {
                session.touch(settings.sessionTimeoutSeconds)
                mainHandler.postDelayed({ startLocalCommandRecognition() }, COMMAND_RETRY_DELAY_MS)
            }
        } else {
            commandRecognitionAttempts = 0
            speakThen(UNKNOWN_COMMAND_REPLY) {
                session.touch(settings.sessionTimeoutSeconds)
                continueConversationSession()
            }
        }
    }

    private fun processUtterance(rawText: String) {
        val normalized = VoiceCommandNormalizer.normalize(rawText)
        updateNotification("你说：$rawText")
        session.touch(settings.sessionTimeoutSeconds)

        if (normalized.isBlank()) {
            retryLocalCommandRecognition("NO_MATCH")
            return
        }

        val heard = "我听到：$rawText"
        val normalizedHint = if (normalized != rawText.trim().lowercase()) "标准化：$normalized" else "已识别"
        overlay.update("你好，有什么可以帮你？", normalizedHint, heard)

        if (containsConversationExit(normalized)) {
            overlay.update("好的，有需要再叫我", "会话结束", heard)
            speakThen("好的，有需要再叫我") { endConversationSession() }
            return
        }

        overlay.update("你好，有什么可以帮你？", "正在执行：$normalized", heard)
        val deviceLike = router.looksLikeDeviceCommand(normalized)
        val local = router.handle(normalized)
        if (local.handled && local.success) {
            conversationTurns += 1
            commandRecognitionAttempts = 0
            overlay.update("你好，有什么可以帮你？", local.reply.ifBlank { "已执行" }, heard)
            session.touch(settings.sessionTimeoutSeconds)
            continueConversationSession(immediate = true)
            return
        }

        val aiConfigured = settings.apiBaseUrl.isNotBlank() && settings.model.isNotBlank()
        if (!aiConfigured) {
            conversationTurns += 1
            commandRecognitionAttempts = 0
            overlay.update("你好，有什么可以帮你？", UNKNOWN_COMMAND_REPLY, heard)
            speakThen(UNKNOWN_COMMAND_REPLY) {
                session.touch(settings.sessionTimeoutSeconds)
                continueConversationSession()
            }
            return
        }

        updateNotification("正在询问 AI…")
        overlay.update("你好，有什么可以帮你？", if (deviceLike) "正在理解手机指令…" else "正在思考…", heard)
        aiOrchestrator.respond(rawText, memory) { result ->
            mainHandler.post {
                if (result.isFailure) {
                    conversationTurns += 1
                    commandRecognitionAttempts = 0
                    val message = "AI 服务暂时不可用，请稍后再试"
                    overlay.update("你好，有什么可以帮你？", message, heard)
                    speakThen(message) {
                        session.touch(settings.sessionTimeoutSeconds)
                        continueConversationSession()
                    }
                    return@post
                }
                when (val outcome = result.getOrThrow()) {
                    is AiOutcome.Reply -> {
                        val answer = outcome.text.replace(Regex("[\r\n]+"), " ").take(800)
                        conversationTurns += 1
                        commandRecognitionAttempts = 0
                        overlay.update("你好，有什么可以帮你？", "正在回答…", heard)
                        speakThen(answer) {
                            memory.addTurn(rawText, answer)
                            session.touch(settings.sessionTimeoutSeconds)
                            continueConversationSession()
                        }
                    }
                    is AiOutcome.Tool -> {
                        overlay.update("你好，有什么可以帮你？", "正在执行安全手机操作…", heard)
                        safeToolExecutor.execute(outcome.call) { executed ->
                            mainHandler.post {
                                conversationTurns += 1
                                commandRecognitionAttempts = 0
                                memory.addTurn(rawText, if (executed.success) "已执行：${executed.spokenText}" else "执行失败：${executed.debugCode}")
                                if (executed.success) {
                                    overlay.update("你好，有什么可以帮你？", executed.spokenText, heard)
                                    session.touch(settings.sessionTimeoutSeconds)
                                    continueConversationSession(immediate = true)
                                } else {
                                    overlay.update("你好，有什么可以帮你？", UNKNOWN_COMMAND_REPLY, heard)
                                    speakThen(UNKNOWN_COMMAND_REPLY) {
                                        session.touch(settings.sessionTimeoutSeconds)
                                        continueConversationSession()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun containsConversationExit(text: String): Boolean {
        val normalized = text.trim().lowercase()
        return listOf("退出对话", "结束对话", "休息吧", "你休息吧", "再见", "拜拜", "不用了").any(normalized::contains)
    }

    private fun continueConversationSession(immediate: Boolean = false) {
        if (!running.get()) return
        if (!conversationActive) {
            restartWakeListening()
            return
        }
        if (session.isExpired()) {
            finishSessionForTimeout()
            return
        }
        updateNotification("连续会话中 · 请继续说，或说“再见”结束")
        overlay.update("你好，有什么可以帮你？", "我在听…", "可以继续说，或说“再见”结束")
        val delay = if (immediate) IMMEDIATE_LISTEN_DELAY_MS else CONTINUOUS_LISTEN_DELAY_MS
        mainHandler.postDelayed({ startLocalCommandRecognition() }, delay)
    }

    private fun finishSessionForTimeout() {
        if (!running.get() || !conversationActive) return
        conversationActive = false
        val timeoutReply = settings.timeoutReply.ifBlank { "我先退下了，有问题再唤醒我" }
        updateNotification("连续会话超时 · 即将返回唤醒待机")
        overlay.update(timeoutReply, "会话超时")
        speakThen(timeoutReply) { endConversationSession() }
    }

    private fun endConversationSession() {
        memory.clear()
        conversationActive = false
        conversationTurns = 0
        commandRecognitionAttempts = 0
        session.stop()
        overlay.hide()
        restartWakeListening()
    }

    private fun restartWakeListening() {
        if (!running.get()) return
        if (conversationActive) memory.clear()
        conversationActive = false
        session.stop()
        overlay.hide()
        updateNotification("全离线语音已开启 · 说“${wakePhraseManager.activePhrase()}”")
        mainHandler.postDelayed({ startKwsCapture() }, 500)
    }

    private fun speakThen(text: String, done: () -> Unit) {
        if (text.isBlank()) {
            mainHandler.post(done)
            return
        }
        val engine = tts
        if (!ttsReady || engine == null) {
            mainHandler.postDelayed(done, 150)
            return
        }
        val id = UUID.randomUUID().toString()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == id) mainHandler.post(done)
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == id) mainHandler.post(done)
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == id) mainHandler.post(done)
            }
        })
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return
            engine.language = Locale.SIMPLIFIED_CHINESE
            val manager = TtsVoiceManager(engine, settings)
            ttsVoiceManager = manager
            val applied = manager.applySavedSettings()
            ttsReady = applied.success || engine.voice != null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "${settings.assistantName}全离线语音",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "本地唤醒与本地语音指令识别"
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, WakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("${settings.assistantName}手机助手")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "停止唤醒", stop).build())
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, notification(text))
    }

    override fun onDestroy() {
        if (::memory.isInitialized) memory.clear()
        running.set(false)
        kwsListening.set(false)
        commandListening.set(false)
        session.stop()
        stopKwsCapture()
        try { kwsThread?.join(500) } catch (_: Throwable) {}
        try { commandThread?.join(500) } catch (_: Throwable) {}
        overlay.release()
        tts?.stop(); tts?.shutdown(); tts = null
        try { stream?.release() } catch (_: Throwable) {}
        try { spotter?.release() } catch (_: Throwable) {}
        try { offlineRecognizer?.release() } catch (_: Throwable) {}
        stream = null; spotter = null; offlineRecognizer = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
