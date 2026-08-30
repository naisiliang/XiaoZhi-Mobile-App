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
import java.util.concurrent.CountDownLatch
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
        private const val DEVICE_DUPLICATE_WINDOW_MS = 1500L
        private const val COMMAND_FRAME_SAMPLES = 800 // 50 ms @ 16 kHz
        private const val COMMAND_WAIT_SPEECH_MS = 4000
        private const val COMMAND_MAX_AUDIO_MS = 8000
        private const val PRE_ROLL_FRAMES = 8 // 400 ms
        private val knownOneCharacterCommands = setOf("停")
    }

    private val running = AtomicBoolean(false)
    private val kwsListening = AtomicBoolean(false)
    private val commandListening = AtomicBoolean(false)
    private val ttsSpeaking = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ttsProgressRegistry = TtsProgressRegistry(
        dispatch = { block -> mainHandler.post(block) },
        dispatchDelayed = { delayMs, block -> mainHandler.postDelayed(block, delayMs) },
        errorFallbackMs = 150L
    )
    private val session = SessionController()
    private val audioEnhancementManager = AudioEnhancementManager()

    private lateinit var settings: SettingsStore
    private lateinit var installedAppRegistry: InstalledAppRegistry
    private lateinit var appLauncher: AppLauncher
    private lateinit var locationProvider: LocationProvider
    private lateinit var mapController: MapController
    private lateinit var phone: PhoneController
    private lateinit var router: CommandRouter
    private lateinit var appExitController: AppExitController
    private lateinit var deviceActionExecutor: DeviceActionExecutor
    private lateinit var executionFormatter: ExecutionIntentFormatter
    private lateinit var commandResultNotifier: CommandResultNotifier
    private lateinit var executionCoordinator: ExecutionFeedbackCoordinator
    private lateinit var ai: AiClient
    private lateinit var aiOrchestrator: AiOrchestrator
    private lateinit var safeToolExecutor: SafeToolExecutor
    private lateinit var memory: AiConversationMemory
    private lateinit var overlay: AssistantOverlayController
    private lateinit var exitDetector: ConversationExitDetector
    private lateinit var wakePhraseManager: WakePhraseManager

    private var tts: TextToSpeech? = null
    private var ttsVoiceManager: TtsVoiceManager? = null
    @Volatile private var ttsReady = false
    private var ttsProgressListenerInstalled = false
    private var commandRecognitionAttempts = 0
    private var conversationActive = false
    private var conversationTurns = 0
    @Volatile private var conversationState = ConversationState.IDLE_WAKE
    private var sessionGeneration = 0L
    private var pendingListenRunnable: Runnable? = null
    @Volatile private var activeTtsUtteranceId: String? = null
    private var lastDeviceCommand = ""
    private var lastDeviceCommandAtMs = 0L
    private var successfulDeviceActions = 0
    @Volatile private var exitInProgress = false

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
        appExitController = AppExitController(this)
        deviceActionExecutor = DeviceActionExecutor(phone, appExitController)
        executionFormatter = ExecutionIntentFormatter()
        commandResultNotifier = CommandResultNotifier(
            publish = { text -> updateNotificationRaw(text) },
            clockMs = { SystemClock.elapsedRealtime() },
            holdMs = 4000L
        )
        executionCoordinator = ExecutionFeedbackCoordinator(
            scheduler = DelayedScheduler { delay, block -> mainHandler.postDelayed(block, delay) },
            runner = DeviceActionRunner { action, callback -> deviceActionExecutor.execute(action, callback) },
            speech = SpeechDriver { text, onStart, onDone -> speakWithProgress(text, onStart, onDone) },
            formatter = executionFormatter,
            notifier = commandResultNotifier,
            actionDelayMs = 120L
        )
        ai = AiClient(settings)
        aiOrchestrator = AiOrchestrator(settings, ai)
        safeToolExecutor = SafeToolExecutor(phone)
        memory = AiConversationMemory(maxTurns = 8)
        overlay = AssistantOverlayController(this)
        exitDetector = ConversationExitDetector()
        overlay.setOnExitRequested {
            mainHandler.post { requestConversationExit("好的，有需要再叫我") }
        }
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
                settings.activeWakePhrase = active
                val applyMessage = if (applied.isSuccess) {
                    "全离线语音已开启 · 说“$active”"
                } else {
                    val reason = applied.exceptionOrNull()?.message
                        ?: applied.exceptionOrNull()?.javaClass?.simpleName
                        ?: "UNKNOWN"
                    "唤醒词应用失败：$reason · 继续监听“$active”"
                }
                updateNotification(applyMessage)
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
                            val reason = applied.exceptionOrNull()?.message
                                ?: applied.exceptionOrNull()?.javaClass?.simpleName
                                ?: "UNKNOWN"
                            updateNotification("自定义唤醒词应用失败：$reason · 已回退“小智小智”")
                        }
                        stream = wakePhraseManager.currentStream()
                    }

                    settings.activeWakePhrase = wakePhraseManager.activePhrase()
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
        settings.activeWakePhrase = DEFAULT_WAKE_PHRASE
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

    private fun setConversationState(state: ConversationState, heard: String = "") {
        conversationState = state
        overlay.updateState(state)
        if (conversationActive) {
            overlay.update("你好，有什么可以帮你？", state.statusText(), heard)
        }
    }

    private fun handleWakeDetected() {
        if (!running.get()) return
        sessionGeneration += 1
        commandRecognitionAttempts = 0
        conversationActive = true
        conversationTurns = 0
        exitInProgress = false
        successfulDeviceActions = 0
        lastDeviceCommand = ""
        lastDeviceCommandAtMs = 0L
        memory.startSession()
        session.start(settings.sessionTimeoutSeconds)
        updateNotification("已唤醒 · 连续会话已开启")
        overlay.show()
        setConversationState(ConversationState.SPEAKING)
        val wakeReply = settings.wakeReply.ifBlank { "我在" }
        speakThen(wakeReply) {
            continueConversationSession(immediate = true)
        }
    }

    private fun scheduleListeningAfterSpeech(delayMs: Long = IMMEDIATE_LISTEN_DELAY_MS) {
        if (!running.get() || !conversationActive || exitInProgress || conversationState == ConversationState.EXITING) return
        val generation = sessionGeneration
        pendingListenRunnable?.let(mainHandler::removeCallbacks)
        setConversationState(ConversationState.READY_TO_LISTEN)
        updateNotification("连续会话中 · 准备继续监听")
        val runnable = Runnable {
            if (!running.get() || !conversationActive || exitInProgress) return@Runnable
            if (generation != sessionGeneration) return@Runnable
            if (conversationState == ConversationState.EXITING) return@Runnable
            startLocalCommandRecognition()
        }
        pendingListenRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs.coerceIn(120L, 180L))
    }

    private fun startLocalCommandRecognition() {
        if (!running.get() || commandListening.get() || ttsSpeaking.get()) return
        if (conversationState != ConversationState.READY_TO_LISTEN) return
        if (!conversationActive || session.isExpired()) {
            finishSessionForTimeout()
            return
        }
        val generation = sessionGeneration
        commandRecognitionAttempts += 1
        commandListening.set(true)
        commandThread = Thread({
            try {
                val samples = captureCommandAudio {
                    mainHandler.post {
                        if (!isCurrentCommandSession(generation)) return@post
                        updateNotification("本地语音识别 · 正在听你说…")
                        setConversationState(ConversationState.LISTENING)
                        session.touch(settings.sessionTimeoutSeconds)
                    }
                }
                if (samples.isEmpty()) {
                    mainHandler.post {
                        if (!isCurrentCommandSession(generation)) return@post
                        recoverRecognitionFailure(CommandFailureKind.NO_SPEECH)
                    }
                    return@Thread
                }
                val recognizingReady = CountDownLatch(1)
                val recognizingPosted = mainHandler.post {
                    try {
                        if (!isCurrentCommandSession(generation)) return@post
                        setConversationState(ConversationState.RECOGNIZING)
                        updateNotification("本地语音识别 · 正在转文字…")
                    } finally {
                        recognizingReady.countDown()
                    }
                }
                check(recognizingPosted) { "RECOGNIZING_POST" }
                recognizingReady.await()
                val text = decodeLocalCommand(samples)
                commandListening.set(false)
                releaseAudioRecord()
                mainHandler.post {
                    if (!isCurrentCommandSession(generation)) return@post
                    processUtterance(text)
                }
            } catch (e: Throwable) {
                val reason = e.message ?: e.javaClass.simpleName
                mainHandler.post {
                    if (!isCurrentCommandSession(generation)) return@post
                    retryLocalCommandRecognition(reason)
                }
            } finally {
                commandListening.set(false)
                releaseAudioRecord()
            }
        }, "xiaozhi-local-asr")
        commandThread?.start()
    }

    private fun captureCommandAudio(onRecordingStarted: () -> Unit): FloatArray {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException("PERMISSION")
        }
        val record = newAudioRecord()
        audioRecord = record
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("AUDIO_INIT")
        }
        val enhancement = audioEnhancementManager.attach(record)
        try {
            try {
                record.startRecording()
            } catch (e: Throwable) {
                throw IllegalStateException("AUDIO_START", e)
            }
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AUDIO_START" }
            onRecordingStarted()

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
            val vad = AdaptiveVoiceActivityDetector()
            vad.reset()
            var speechStarted = false
            var waitedMs = 0

            while (running.get() && commandListening.get() && outputSize < maxSamples) {
                val n = record.read(frame, 0, frame.size)
                if (n <= 0) continue
                val rms = frameRms(frame, n)
                val vadDecision = vad.accept(rms)
                overlay.updateAudioLevel(normalizeOverlayAudioLevel(rms))
                val frameMs = n * 1000 / SAMPLE_RATE

                if (!speechStarted) {
                    preRoll.addLast(frame.copyOf(n))
                    while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                    waitedMs += frameMs
                    if (vadDecision.speechStarted) {
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
                    if (!speechStarted && waitedMs >= waitSpeechBudgetMs) break
                    continue
                }

                val count = minOf(n, maxSamples - outputSize)
                frame.copyInto(output, outputSize, 0, count)
                outputSize += count

                if (vadDecision.speechEnded && outputSize >= SAMPLE_RATE / 2) break
            }

            try { record.stop() } catch (_: Throwable) {}
            overlay.updateAudioLevel(0.08f)
            if (!speechStarted || outputSize == 0) return FloatArray(0)
            return FloatArray(outputSize) { i -> output[i] / 32768.0f }
        } finally {
            enhancement.close()
        }
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
        scheduleListeningAfterSpeech(IDLE_RELISTEN_DELAY_MS)
    }

    private fun retryLocalCommandRecognition(reason: String) {
        updateNotification("本地语音识别失败：$reason")
        recoverRecognitionFailure(CommandFailureKind.ASR_EMPTY)
    }

    private fun recoverRecognitionFailure(kind: CommandFailureKind) {
        if (!running.get() || !conversationActive || exitInProgress) return
        if (session.isExpired()) {
            finishSessionForTimeout()
            return
        }

        val decision = CommandRecoveryPolicy.forFailure(kind, commandRecognitionAttempts)
        if (decision.terminal) {
            requestConversationExit(decision.spokenReply.orEmpty())
            return
        }
        if (decision.resetAttempts) commandRecognitionAttempts = 0
        val reply = decision.spokenReply ?: decision.continuation
        if (reply == null) {
            updateNotification("连续会话中 · 等待下一条指令")
            scheduleListeningAfterSpeech(if (decision.immediateListen) IMMEDIATE_LISTEN_DELAY_MS else IDLE_RELISTEN_DELAY_MS)
            return
        }
        setConversationState(ConversationState.SPEAKING)
        val generation = sessionGeneration
        speakThen(reply) {
            if (generation == sessionGeneration && !exitInProgress) {
                continueConversationSession(immediate = false)
            }
        }
    }

    private fun processUtterance(rawText: String) {
        val normalized = VoiceCommandNormalizer.normalize(rawText)
        updateNotification("你说：$rawText")
        if (normalized.isBlank() || isLowQualityRecognition(normalized)) {
            recoverRecognitionFailure(CommandFailureKind.ASR_EMPTY)
            return
        }
        val heard = "我听到：$rawText"
        setConversationState(ConversationState.EXECUTING, heard)
        when (exitDetector.classify(normalized)) {
            ExitDecision.EXIT -> {
                requestConversationExit("好的，我先退下了，有需要再叫我")
            }
            ExitDecision.CONTINUE -> processNonExitUtterance(rawText, normalized, heard)
            ExitDecision.AMBIGUOUS -> classifyAmbiguousExitOrContinue(rawText, normalized, heard)
        }
    }

    private fun classifyAmbiguousExitOrContinue(rawText: String, normalized: String, heard: String) {
        val aiConfigured = settings.apiBaseUrl.isNotBlank() && settings.model.isNotBlank()
        if (!aiConfigured) {
            processNonExitUtterance(rawText, normalized, heard)
            return
        }
        updateNotification("正在判断是否结束当前会话…")
        setConversationState(ConversationState.EXECUTING, heard)
        val generation = sessionGeneration
        aiOrchestrator.classifyExitIntent(rawText) { result ->
            mainHandler.post {
                if (!conversationActive || exitInProgress || generation != sessionGeneration) return@post
                val decision = result.getOrNull() ?: ExitDecision.CONTINUE
                if (decision == ExitDecision.EXIT) {
                    requestConversationExit("好的，我先退下了，有需要再叫我")
                } else {
                    processNonExitUtterance(rawText, normalized, heard)
                }
            }
        }
    }

    private fun processNonExitUtterance(rawText: String, normalized: String, heard: String) {
        if (!conversationActive || exitInProgress) return
        val deviceLike = router.looksLikeDeviceCommand(normalized)
        when (val localPlan = router.plan(normalized)) {
            is DeviceCommandPlan.Planned -> {
                executeDeviceAction(rawText, normalized, localPlan.action, heard)
                return
            }
            DeviceCommandPlan.Unhandled -> Unit
        }

        val aiConfigured = settings.apiBaseUrl.isNotBlank() && settings.model.isNotBlank()
        if (!aiConfigured) {
            conversationTurns += 1
            recoverRecognitionFailure(CommandFailureKind.UNSUPPORTED_COMMAND)
            return
        }

        updateNotification("正在询问 AI…")
        setConversationState(ConversationState.EXECUTING, heard)
        overlay.update("你好，有什么可以帮你？", if (deviceLike) "正在理解手机指令…" else "正在思考…", heard)
        val requestGeneration = sessionGeneration
        aiOrchestrator.respond(rawText, memory) { result ->
            mainHandler.post {
                if (!conversationActive || exitInProgress || requestGeneration != sessionGeneration) return@post
                if (result.isFailure) {
                    conversationTurns += 1
                    recoverRecognitionFailure(CommandFailureKind.AI_UNAVAILABLE)
                    return@post
                }
                when (val outcome = result.getOrThrow()) {
                    is AiOutcome.Reply -> {
                        val answer = outcome.text.replace(Regex("[\r\n]+"), " ").take(800)
                        val spoken = buildAiContinuation(answer)
                        conversationTurns += 1
                        commandRecognitionAttempts = 0
                        setConversationState(ConversationState.SPEAKING, heard)
                        val generation = sessionGeneration
                        speakThen(spoken) {
                            if (generation != sessionGeneration || exitInProgress) return@speakThen
                            memory.addTurn(rawText, answer)
                            continueConversationSession(immediate = true)
                        }
                    }
                    is AiOutcome.Tool -> {
                        setConversationState(ConversationState.EXECUTING, heard)
                        overlay.update("你好，有什么可以帮你？", "正在执行安全手机操作…", heard)
                        when (val toolPlan = safeToolExecutor.plan(outcome.call)) {
                            is SafeToolPlan.Allowed -> {
                                executeDeviceAction(rawText, normalized, toolPlan.action, heard)
                            }
                            is SafeToolPlan.Rejected -> {
                                val failureKind = CommandFailureKind.SAFETY_REJECTED
                                conversationTurns += 1
                                memory.addTurn(rawText, "执行失败：${failureKind.name}:${toolPlan.result.debugCode}")
                                updateNotification("❌ 执行失败：${failureKind.name}")
                                recoverRecognitionFailure(failureKind)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun executeDeviceAction(
        rawText: String,
        normalized: String,
        action: DeviceAction,
        heard: String
    ) {
        if (!conversationActive || exitInProgress) return
        if (commandListening.get()) {
            val generation = sessionGeneration
            mainHandler.postDelayed({
                if (generation == sessionGeneration && conversationActive && !exitInProgress) {
                    executeDeviceAction(rawText, normalized, action, heard)
                }
            }, IMMEDIATE_LISTEN_DELAY_MS)
            return
        }
        if (isDuplicateDeviceCommand(normalized)) {
            updateNotification("已忽略重复指令 · $normalized")
            overlay.update("你好，有什么可以帮你？", "已忽略重复指令", heard)
            scheduleListeningAfterSpeech(IMMEDIATE_LISTEN_DELAY_MS)
            return
        }

        commandRecognitionAttempts = 0
        setConversationState(ConversationState.EXECUTING, heard)
        overlay.update("你好，有什么可以帮你？", "正在执行安全手机操作…", heard)
        val generation = sessionGeneration
        val continuation = if (successfulDeviceActions == 0) "你有什么需求请说？" else "请继续说。"
        val transaction = CommandTransaction(
            rawText = rawText,
            normalizedText = normalized,
            action = action,
            announcement = executionFormatter.announcement(action)
        )
        executionCoordinator.execute(
            transaction,
            continuation,
            isValid = {
                running.get() && conversationActive && !exitInProgress && generation == sessionGeneration
            }
        ) { completed ->
            if (!conversationActive || exitInProgress || generation != sessionGeneration) return@execute
            val result = completed.result ?: return@execute
            conversationTurns += 1
            commandRecognitionAttempts = 0
            memory.addTurn(
                rawText,
                if (result.success) "已执行：${result.spokenResult}" else "执行失败：${result.code}:${result.spokenResult}"
            )
            if (result.success) {
                successfulDeviceActions += 1
            }
            overlay.update("你好，有什么可以帮你？", result.spokenResult, heard)
            continueConversationSession(immediate = true)
        }
    }

    private fun isLowQualityRecognition(normalized: String): Boolean =
        CommandRecognitionQuality.failureKind(normalized, knownOneCharacterCommands) != null

    private fun isCurrentCommandSession(generation: Long): Boolean {
        return running.get() &&
            conversationActive &&
            !exitInProgress &&
            generation == sessionGeneration &&
            conversationState != ConversationState.EXITING
    }

    private fun isDuplicateDeviceCommand(normalized: String, nowMs: Long = SystemClock.elapsedRealtime()): Boolean {
        if (normalized != lastDeviceCommand) {
            lastDeviceCommand = normalized
            lastDeviceCommandAtMs = nowMs
            return false
        }
        val delta = nowMs - lastDeviceCommandAtMs
        lastDeviceCommandAtMs = nowMs
        return delta in 0..DEVICE_DUPLICATE_WINDOW_MS
    }

    private fun buildAiContinuation(answer: String): String {
        val clean = answer.trim()
        if (clean.isBlank()) return "你还需要什么？"
        return if (clean.endsWith("？") || clean.endsWith("?")) clean else "$clean。你还需要什么？"
    }

    private fun continueConversationSession(immediate: Boolean = false) {
        if (!running.get() || exitInProgress) return
        if (!conversationActive) {
            restartWakeListening()
            return
        }
        if (session.isExpired()) {
            finishSessionForTimeout()
            return
        }
        updateNotification("连续会话中 · 准备下一轮监听")
        val delay = if (immediate) IMMEDIATE_LISTEN_DELAY_MS else 180L
        scheduleListeningAfterSpeech(delay)
    }

    private fun finishSessionForTimeout() {
        if (!running.get() || !conversationActive || exitInProgress) return
        val timeoutReply = settings.timeoutReply.ifBlank { "我先退下了，有问题再唤醒我" }
        updateNotification("连续会话超时 · 即将返回唤醒待机")
        requestConversationExit(timeoutReply)
    }

    private fun requestConversationExit(spokenText: String) {
        if (!running.get() || !conversationActive || exitInProgress) return
        executionCoordinator.cancelPending()
        exitInProgress = true
        setConversationState(ConversationState.EXITING)
        sessionGeneration += 1
        val generation = sessionGeneration
        pendingListenRunnable?.let(mainHandler::removeCallbacks)
        pendingListenRunnable = null
        commandListening.set(false)
        try { audioRecord?.stop() } catch (_: Throwable) {}
        releaseAudioRecord()
        memory.clear()
        session.stop()
        overlay.update("好的，有需要再叫我", ConversationState.EXITING.statusText())
        updateNotification("正在退出当前会话 · 即将恢复唤醒待机")
        speakThen(spokenText.ifBlank { "好的，有需要再叫我" }) {
            completeConversationExit(generation)
        }
    }

    private fun completeConversationExit(generation: Long) {
        if (generation != sessionGeneration) return
        conversationActive = false
        conversationTurns = 0
        commandRecognitionAttempts = 0
        successfulDeviceActions = 0
        lastDeviceCommand = ""
        lastDeviceCommandAtMs = 0L
        exitInProgress = false
        overlay.hide()
        restartWakeListening()
    }

    private fun endConversationSession() {
        if (conversationActive) requestConversationExit("好的，有需要再叫我")
        else restartWakeListening()
    }

    private fun restartWakeListening() {
        if (!running.get()) return
        pendingListenRunnable?.let(mainHandler::removeCallbacks)
        pendingListenRunnable = null
        conversationActive = false
        session.stop()
        overlay.hide()
        conversationState = ConversationState.IDLE_WAKE
        overlay.updateState(ConversationState.IDLE_WAKE)
        commandResultNotifier.clearRetention()
        updateNotificationRaw("全离线语音已开启 · 说“${wakePhraseManager.activePhrase()}”")
        mainHandler.postDelayed({ startKwsCapture() }, 500)
    }

    private fun speakWithProgress(
        text: String,
        onStart: () -> Unit = {},
        onDone: () -> Unit
    ) {
        val id = UUID.randomUUID().toString()
        activeTtsUtteranceId = id
        ttsSpeaking.set(true)
        ttsProgressRegistry.register(
            utteranceId = id,
            onStart = onStart,
            onDone = {
                if (activeTtsUtteranceId == id) {
                    activeTtsUtteranceId = null
                    ttsSpeaking.set(false)
                }
                onDone()
            },
            flushPending = true
        )

        if (text.isBlank()) {
            ttsProgressRegistry.onStart(id)
            ttsProgressRegistry.onDone(id)
            return
        }
        val engine = tts
        if (!ttsReady || engine == null) {
            ttsProgressRegistry.onError(id)
            return
        }
        val status = try {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        } catch (_: Throwable) {
            ttsProgressRegistry.onError(id)
            return
        }
        if (status == TextToSpeech.ERROR) {
            ttsProgressRegistry.onError(id)
        }
    }

    private fun speakThen(text: String, done: () -> Unit) {
        speakWithProgress(text, onDone = done)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return
            if (!ttsProgressListenerInstalled) {
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        utteranceId?.let(ttsProgressRegistry::onStart)
                    }

                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let(ttsProgressRegistry::onDone)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId != null) ttsProgressRegistry.onError(utteranceId)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId != null) ttsProgressRegistry.onError(utteranceId)
                    }
                })
                ttsProgressListenerInstalled = true
            }
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

    private fun updateNotificationRaw(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, notification(text))
    }

    private fun updateNotification(text: String) {
        commandResultNotifier.publishTransient(text)
    }

    override fun onDestroy() {
        if (::memory.isInitialized) memory.clear()
        if (::executionCoordinator.isInitialized) executionCoordinator.cancelPending()
        ttsProgressRegistry.cancelPending()
        running.set(false)
        kwsListening.set(false)
        commandListening.set(false)
        session.stop()
        pendingListenRunnable?.let(mainHandler::removeCallbacks)
        pendingListenRunnable = null
        exitInProgress = true
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
