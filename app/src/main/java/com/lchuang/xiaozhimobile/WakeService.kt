package com.lchuang.xiaozhimobile

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class WakeService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val CHANNEL_ID = "xiaozhi_wake"
        const val NOTIFY_ID = 1001
        const val ACTION_STOP = "com.lchuang.xiaozhimobile.STOP"
        private const val SAMPLE_RATE = 16000
        private const val MODEL_DIR = "sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20"
    }

    private val running = AtomicBoolean(false)
    private val kwsListening = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var settings: SettingsStore
    private lateinit var phone: PhoneController
    private lateinit var router: CommandRouter
    private lateinit var ai: AiClient

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private var speechRecognizer: SpeechRecognizer? = null

    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var audioRecord: AudioRecord? = null
    private var kwsThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        phone = PhoneController(this)
        router = CommandRouter(phone)
        ai = AiClient(settings)
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

        startForeground(NOTIFY_ID, notification("正在加载离线唤醒模型…"))
        if (running.compareAndSet(false, true)) {
            if (wakeLock?.isHeld != true) wakeLock?.acquire()
            Thread {
                try {
                    initKeywordSpotter()
                    updateNotification("离线唤醒已开启 · 说“小智小智”")
                    startKwsCapture()
                } catch (e: Throwable) {
                    updateNotification("离线唤醒启动失败：${e.message ?: e.javaClass.simpleName}")
                }
            }.start()
        }
        return START_STICKY
    }

    private fun initKeywordSpotter() {
        if (spotter != null) return
        val model = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = "$MODEL_DIR/encoder-epoch-13-avg-2-chunk-16-left-64.onnx",
                decoder = "$MODEL_DIR/decoder-epoch-13-avg-2-chunk-16-left-64.onnx",
                joiner = "$MODEL_DIR/joiner-epoch-13-avg-2-chunk-16-left-64.onnx"
            ),
            tokens = "$MODEL_DIR/tokens.txt",
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
        stream = spotter!!.createStream()
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
        if (!running.get() || kwsListening.get()) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateNotification("缺少麦克风权限，请打开 App 授权")
            return
        }
        kwsListening.set(true)
        kwsThread = Thread({
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
                            kwsListening.set(false)
                            break
                        }
                    }
                }
            } catch (e: Throwable) {
                if (running.get()) updateNotification("监听异常：${e.message ?: e.javaClass.simpleName}")
            } finally {
                releaseAudioRecord()
                if (running.get() && !kwsListening.get()) {
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
        updateNotification("已唤醒 · 正在听你说话")
        speakThen("我在") { startSpeechRecognition() }
    }

    private fun startSpeechRecognition() {
        if (!running.get()) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speakThen("这台手机没有可用的语音识别服务") { restartWakeListening() }
            return
        }
        speechRecognizer?.destroy()
        speechRecognizer = if (
            Build.VERSION.SDK_INT >= 31 &&
            settings.preferOfflineAsr &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
        speechRecognizer = speechRecognizer?.also { recognizer ->
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { updateNotification("请说指令或问题…") }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { updateNotification("正在处理…") }
                override fun onError(error: Int) {
                    speechRecognizer?.destroy(); speechRecognizer = null
                    speakThen("没有听清，请再叫我一次") { restartWakeListening() }
                }
                override fun onResults(results: Bundle?) {
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = list?.firstOrNull().orEmpty().trim()
                    speechRecognizer?.destroy(); speechRecognizer = null
                    if (text.isBlank()) {
                        speakThen("没有听清") { restartWakeListening() }
                    } else {
                        processUtterance(text)
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, settings.preferOfflineAsr)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun processUtterance(text: String) {
        updateNotification("你说：$text")
        val local = router.handle(text)
        if (local.handled) {
            speakThen(local.reply.ifBlank { "好的" }) { restartWakeListening() }
            return
        }

        if (settings.apiUrl.isBlank()) {
            speakThen("我听到你说$text。手机控制已经可以使用，聊天功能还需要在 App 里配置 AI 接口。") {
                restartWakeListening()
            }
            return
        }

        updateNotification("正在询问 AI…")
        ai.ask(text) { result ->
            mainHandler.post {
                val answer = result.getOrElse { "AI 请求失败：${it.message ?: "未知错误"}" }
                    .replace(Regex("[\\r\\n]+"), " ")
                    .take(800)
                speakThen(answer) { restartWakeListening() }
            }
        }
    }

    private fun restartWakeListening() {
        if (!running.get()) return
        updateNotification("离线唤醒已开启 · 说“小智小智”")
        mainHandler.postDelayed({ startKwsCapture() }, 500)
    }

    private fun speakThen(text: String, done: () -> Unit) {
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
            tts?.language = Locale.SIMPLIFIED_CHINESE
            tts?.setSpeechRate(1.05f)
            ttsReady = true
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "小智离线唤醒",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "小智手机助手在本机持续检测唤醒词"
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
            .setContentTitle("小智手机助手")
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
        running.set(false)
        stopKwsCapture()
        try { kwsThread?.join(500) } catch (_: Throwable) {}
        speechRecognizer?.destroy(); speechRecognizer = null
        tts?.stop(); tts?.shutdown(); tts = null
        try { stream?.release() } catch (_: Throwable) {}
        try { spotter?.release() } catch (_: Throwable) {}
        stream = null; spotter = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
