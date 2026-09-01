from pathlib import Path
import re
import subprocess
import tempfile
import textwrap


ROOT = Path(__file__).resolve().parents[1]
CONTROLLER = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/MediaVolumeController.kt"
PHONE = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/PhoneController.kt"


def compile_and_run_harness() -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
        temp = Path(temp_dir)
        audio_manager = temp / "AudioManager.kt"
        build = temp / "Build.kt"
        harness = temp / "MediaVolumeControllerHarness.kt"
        jar = temp / "media-volume-controller.jar"

        build.write_text(
            textwrap.dedent(
                """
                package android.os

                object Build {
                    object VERSION {
                        const val SDK_INT = 28
                    }

                    object VERSION_CODES {
                        const val P = 28
                    }
                }
                """
            ),
            encoding="utf-8",
        )

        audio_manager.write_text(
            textwrap.dedent(
                """
                package android.media

                open class AudioManager(
                    private val maxVolume: Int = 10,
                    initialVolume: Int = 5,
                    private val minVolume: Int = 0,
                    val isVolumeFixed: Boolean = false,
                    private val noOpAdjustDirections: Set<Int> = emptySet(),
                    private val throwOnAdjustDirections: Set<Int> = emptySet(),
                    private val delayedAdjustMs: Long = 0L,
                ) {
                    companion object {
                        const val STREAM_MUSIC = 3
                        const val FLAG_SHOW_UI = 1
                        const val ADJUST_RAISE = 1
                        const val ADJUST_LOWER = -1
                    }

                    private var currentVolume = initialVolume.coerceIn(minVolume, maxVolume)
                    private var pendingAdjustDirection: Int? = null
                    private var adjustRequestedAtMs: Long = 0L
                    var setStreamVolumeCalls: Int = 0
                        private set

                    open fun getStreamMaxVolume(streamType: Int): Int {
                        check(streamType == STREAM_MUSIC)
                        return maxVolume
                    }

                    open fun getStreamVolume(streamType: Int): Int {
                        check(streamType == STREAM_MUSIC)
                        val pendingDirection = pendingAdjustDirection
                        if (pendingDirection != null &&
                            System.currentTimeMillis() - adjustRequestedAtMs >= delayedAdjustMs
                        ) {
                            currentVolume = when (pendingDirection) {
                                ADJUST_RAISE -> (currentVolume + 1).coerceAtMost(maxVolume)
                                ADJUST_LOWER -> (currentVolume - 1).coerceAtLeast(minVolume)
                                else -> currentVolume
                            }
                            pendingAdjustDirection = null
                        }
                        return currentVolume
                    }

                    open fun getStreamMinVolume(streamType: Int): Int {
                        check(streamType == STREAM_MUSIC)
                        return minVolume
                    }

                    open fun setStreamVolume(streamType: Int, index: Int, flags: Int) {
                        check(streamType == STREAM_MUSIC)
                        check(flags == FLAG_SHOW_UI)
                        setStreamVolumeCalls++
                        currentVolume = index.coerceIn(minVolume, maxVolume)
                    }

                    open fun adjustStreamVolume(streamType: Int, direction: Int, flags: Int) {
                        check(streamType == STREAM_MUSIC)
                        check(flags == FLAG_SHOW_UI)
                        if (direction in throwOnAdjustDirections) {
                            throw IllegalStateException("forced adjust failure")
                        }
                        if (direction in noOpAdjustDirections) {
                            return
                        }
                        if (delayedAdjustMs == 0L) {
                            currentVolume = when (direction) {
                                ADJUST_RAISE -> (currentVolume + 1).coerceAtMost(maxVolume)
                                ADJUST_LOWER -> (currentVolume - 1).coerceAtLeast(minVolume)
                                else -> currentVolume
                            }
                        } else {
                            pendingAdjustDirection = direction
                            adjustRequestedAtMs = System.currentTimeMillis()
                        }
                    }
                }
                """
            ),
            encoding="utf-8",
        )

        harness.write_text(
            textwrap.dedent(
                """
                import android.media.AudioManager
                import com.lchuang.xiaozhimobile.MediaVolumeController

                private fun assertEquals(expected: Any?, actual: Any?, label: String) {
                    check(expected == actual) { "$label: expected=$expected actual=$actual" }
                }

                private fun assertTrue(condition: Boolean, label: String) {
                    check(condition) { label }
                }

                fun main() {
                    val baselineController = MediaVolumeController(
                        AudioManager(maxVolume = 10, initialVolume = 4)
                    )
                    val snapshot = baselineController.snapshot()
                    assertEquals(MediaVolumeController.RESULT_SNAPSHOT, snapshot.resultCode, "snapshot result")
                    assertEquals(4, snapshot.beforeStep, "snapshot before")
                    assertEquals(4, snapshot.targetStep, "snapshot target")
                    assertEquals(4, snapshot.afterStep, "snapshot after")
                    assertEquals(40, snapshot.actualPercent, "snapshot percent")

                    val setController = MediaVolumeController(
                        AudioManager(maxVolume = 10, initialVolume = 2)
                    )
                    val setSnapshot = setController.setPercent(63)
                    assertEquals(MediaVolumeController.RESULT_SET_OK, setSnapshot.resultCode, "set result")
                    assertEquals(63, setSnapshot.requestedPercent, "set requested")
                    assertEquals(6, setSnapshot.targetStep, "set target step")
                    assertEquals(6, setSnapshot.afterStep, "set after step")
                    assertTrue(kotlin.math.abs(setSnapshot.actualPercent - 63) <= 10, "set tolerance")

                    val delayedAdjustAudio = AudioManager(
                        maxVolume = 10,
                        initialVolume = 4,
                        delayedAdjustMs = 110L,
                    )
                    val delayedAdjustController = MediaVolumeController(delayedAdjustAudio)
                    val delayedStartedAt = System.currentTimeMillis()
                    val delayedSnapshot = delayedAdjustController.adjust(AudioManager.ADJUST_RAISE)
                    val delayedElapsedMs = System.currentTimeMillis() - delayedStartedAt
                    assertTrue(delayedElapsedMs >= 120L, "adjust must wait for delayed readback")
                    assertEquals(5, delayedSnapshot.afterStep, "delayed adjust after")
                    assertEquals(MediaVolumeController.RESULT_ADJUST_OK, delayedSnapshot.resultCode, "delayed adjust result")

                    val nonZeroMinimumAudio = AudioManager(
                        maxVolume = 10,
                        initialVolume = 3,
                        minVolume = 3,
                    )
                    val nonZeroMinimumController = MediaVolumeController(nonZeroMinimumAudio)
                    val nonZeroMinimumSnapshot = nonZeroMinimumController.setPercent(50)
                    assertEquals(7, nonZeroMinimumSnapshot.targetStep, "non-zero minimum target")
                    assertEquals(7, nonZeroMinimumSnapshot.afterStep, "non-zero minimum after")
                    assertEquals(57, nonZeroMinimumSnapshot.actualPercent, "non-zero minimum actual")

                    val fixedAudio = AudioManager(
                        maxVolume = 10,
                        initialVolume = 6,
                        minVolume = 3,
                        isVolumeFixed = true,
                    )
                    val fixedSnapshot = MediaVolumeController(fixedAudio).setPercent(80)
                    assertEquals(9, fixedSnapshot.targetStep, "fixed target")
                    assertEquals(6, fixedSnapshot.afterStep, "fixed after")
                    assertEquals(43, fixedSnapshot.actualPercent, "fixed actual")
                    assertEquals(0, fixedSnapshot.retryCount, "fixed retry count")
                    assertEquals(MediaVolumeController.RESULT_SET_MISMATCH, fixedSnapshot.resultCode, "fixed result")
                    assertEquals(0, fixedAudio.setStreamVolumeCalls, "fixed volume must not be written")

                    val raiseController = MediaVolumeController(
                        AudioManager(maxVolume = 10, initialVolume = 4)
                    )
                    val raiseSnapshot = raiseController.adjust(AudioManager.ADJUST_RAISE)
                    assertEquals(MediaVolumeController.RESULT_ADJUST_OK, raiseSnapshot.resultCode, "raise result")
                    assertEquals(4, raiseSnapshot.beforeStep, "raise before")
                    assertEquals(5, raiseSnapshot.targetStep, "raise target")
                    assertEquals(5, raiseSnapshot.afterStep, "raise after")

                    val noOpRaiseController = MediaVolumeController(
                        AudioManager(
                            maxVolume = 10,
                            initialVolume = 4,
                            noOpAdjustDirections = setOf(AudioManager.ADJUST_RAISE),
                        )
                    )
                    val noOpRaiseSnapshot = noOpRaiseController.adjust(AudioManager.ADJUST_RAISE)
                    assertEquals(4, noOpRaiseSnapshot.beforeStep, "no-op raise before")
                    assertEquals(5, noOpRaiseSnapshot.targetStep, "no-op raise target")
                    assertEquals(4, noOpRaiseSnapshot.afterStep, "no-op raise after")
                    assertTrue(
                        noOpRaiseSnapshot.resultCode != MediaVolumeController.RESULT_ADJUST_OK,
                        "no-op raise must not report adjust ok"
                    )

                    val noOpLowerController = MediaVolumeController(
                        AudioManager(
                            maxVolume = 10,
                            initialVolume = 4,
                            noOpAdjustDirections = setOf(AudioManager.ADJUST_LOWER),
                        )
                    )
                    val noOpLowerSnapshot = noOpLowerController.adjust(AudioManager.ADJUST_LOWER)
                    assertEquals(4, noOpLowerSnapshot.beforeStep, "no-op lower before")
                    assertEquals(3, noOpLowerSnapshot.targetStep, "no-op lower target")
                    assertEquals(4, noOpLowerSnapshot.afterStep, "no-op lower after")
                    assertTrue(
                        noOpLowerSnapshot.resultCode != MediaVolumeController.RESULT_ADJUST_OK,
                        "no-op lower must not report adjust ok"
                    )

                    val errorController = MediaVolumeController(
                        AudioManager(
                            maxVolume = 10,
                            initialVolume = 4,
                            throwOnAdjustDirections = setOf(AudioManager.ADJUST_RAISE),
                        )
                    )
                    val errorSnapshot = errorController.adjust(AudioManager.ADJUST_RAISE)
                    assertEquals(MediaVolumeController.RESULT_ADJUST_ERROR, errorSnapshot.resultCode, "error result")
                    assertEquals(4, errorSnapshot.afterStep, "error after")

                    println("PASS: FIX04 media volume controller behavior")
                }
                """
            ),
            encoding="utf-8",
        )

        subprocess.run(
            ["kotlinc", str(build), str(audio_manager), str(CONTROLLER), str(harness), "-include-runtime", "-d", str(jar)],
            cwd=ROOT,
            check=True,
        )
        subprocess.run(["java", "-jar", str(jar)], cwd=ROOT, check=True)


def assert_phone_controller_mapping() -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
        temp = Path(temp_dir)
        android_content = temp / "AndroidContent.kt"
        android_camera = temp / "AndroidCamera.kt"
        android_media = temp / "AndroidMedia.kt"
        android_net = temp / "AndroidNet.kt"
        android_view = temp / "AndroidView.kt"
        build = temp / "Build.kt"
        app_stubs = temp / "PhoneControllerDeps.kt"
        harness = temp / "PhoneControllerHarness.kt"
        jar = temp / "phone-controller-harness.jar"

        android_content.write_text(
            textwrap.dedent(
                """
                package android.content

                open class Context {
                    open fun getSystemService(name: String): Any = error("missing service: $name")
                    open fun startActivity(intent: Intent) {}

                    companion object {
                        const val AUDIO_SERVICE = "audio"
                        const val CAMERA_SERVICE = "camera"
                    }
                }

                class Intent(val action: String, val uri: android.net.Uri? = null) {
                    fun addFlags(flags: Int) = this

                    companion object {
                        const val ACTION_VIEW = "android.intent.action.VIEW"
                        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000.toInt()
                    }
                }
                """
            ),
            encoding="utf-8",
        )

        android_camera.write_text(
            textwrap.dedent(
                """
                package android.hardware.camera2

                class CameraCharacteristics {
                    fun <T> get(key: Key<T>): T? = null

                    class Key<T>

                    companion object {
                        val FLASH_INFO_AVAILABLE = Key<Boolean>()
                        val LENS_FACING = Key<Int>()
                        const val LENS_FACING_BACK = 1
                    }
                }

                open class CameraManager {
                    open val cameraIdList: Array<String> = emptyArray()
                    open fun getCameraCharacteristics(id: String): CameraCharacteristics = CameraCharacteristics()
                    open fun setTorchMode(cameraId: String, enabled: Boolean) {}
                }
                """
            ),
            encoding="utf-8",
        )

        build.write_text(
            textwrap.dedent(
                """
                package android.os

                object Build {
                    object VERSION {
                        const val SDK_INT = 28
                    }

                    object VERSION_CODES {
                        const val P = 28
                    }
                }
                """
            ),
            encoding="utf-8",
        )

        android_media.write_text(
            textwrap.dedent(
                """
                package android.media

                import android.view.KeyEvent

                open class AudioManager(
                    private val maxVolume: Int = 10,
                    initialVolume: Int = 5,
                    val isVolumeFixed: Boolean = false,
                ) {
                    companion object {
                        const val STREAM_MUSIC = 3
                        const val FLAG_SHOW_UI = 1
                        const val ADJUST_RAISE = 1
                        const val ADJUST_LOWER = -1
                    }

                    private var currentVolume = initialVolume.coerceIn(0, maxVolume)

                    open fun getStreamMaxVolume(streamType: Int): Int = maxVolume

                    open fun getStreamMinVolume(streamType: Int): Int = 0

                    open fun getStreamVolume(streamType: Int): Int = currentVolume

                    open fun setStreamVolume(streamType: Int, index: Int, flags: Int) {
                        currentVolume = index.coerceIn(0, maxVolume)
                    }

                    open fun adjustStreamVolume(streamType: Int, direction: Int, flags: Int) {
                        currentVolume = when (direction) {
                            ADJUST_RAISE -> (currentVolume + 1).coerceAtMost(maxVolume)
                            ADJUST_LOWER -> (currentVolume - 1).coerceAtLeast(0)
                            else -> currentVolume
                        }
                    }

                    open fun dispatchMediaKeyEvent(event: KeyEvent) {}
                }
                """
            ),
            encoding="utf-8",
        )

        android_net.write_text(
            textwrap.dedent(
                """
                package android.net

                class Uri private constructor(private val value: String) {
                    override fun toString(): String = value

                    companion object {
                        fun parse(value: String) = Uri(value)
                        fun encode(value: String) = value.replace(" ", "%20")
                    }
                }
                """
            ),
            encoding="utf-8",
        )

        android_view.write_text(
            textwrap.dedent(
                """
                package android.view

                class KeyEvent(val action: Int, val code: Int) {
                    companion object {
                        const val ACTION_DOWN = 0
                        const val ACTION_UP = 1
                        const val KEYCODE_MEDIA_PLAY = 126
                        const val KEYCODE_MEDIA_PAUSE = 127
                        const val KEYCODE_MEDIA_NEXT = 87
                        const val KEYCODE_MEDIA_PREVIOUS = 88
                        const val KEYCODE_MEDIA_PLAY_PAUSE = 85
                        const val KEYCODE_MEDIA_STOP = 86
                    }
                }
                """
            ),
            encoding="utf-8",
        )

        app_stubs.write_text(
            textwrap.dedent(
                """
                package com.lchuang.xiaozhimobile

                import android.content.Context

                enum class MapAppPreference {
                    AUTO
                }

                class SettingsStore(context: Context) {
                    val appAliases: Map<String, String> = emptyMap()
                    val defaultMapApp: MapAppPreference = MapAppPreference.AUTO
                }

                class InstalledAppRegistry(context: Context) {
                    data class AppEntry(
                        val label: String,
                        val packageName: String,
                        val normalizedLabel: String,
                        val launchActivities: List<String>,
                        val source: AppDiscoverySource,
                    )

                    enum class AppDiscoverySource {
                        KNOWN_FALLBACK
                    }

                    data class ResolutionDetailed(
                        val entry: AppEntry?,
                        val explanation: String,
                    )

                    fun resolveDetailed(appName: String, aliases: Map<String, String>): ResolutionDetailed =
                        ResolutionDetailed(null, "not needed")

                    fun count(): Int = 0
                }

                class AppLauncher(context: Context) {
                    sealed class AppLaunchResult {
                        data class Failure(val error: AppLaunchError, val reason: String) : AppLaunchResult()
                    }

                    enum class AppLaunchError {
                        PACKAGE_NOT_INSTALLED
                    }

                    fun launch(entry: InstalledAppRegistry.AppEntry): AppLaunchResult =
                        AppLaunchResult.Failure(AppLaunchError.PACKAGE_NOT_INSTALLED, "not needed")
                }

                class MapController(context: Context) {
                    class MapActionResult
                    fun openMap(preference: MapAppPreference): MapActionResult = MapActionResult()
                    fun navigate(destination: String, preference: MapAppPreference): MapActionResult = MapActionResult()
                    fun searchNearby(
                        keyword: String,
                        preference: MapAppPreference,
                        callback: (MapActionResult) -> Unit,
                    ) {
                    }
                }

                object AppNameMatcher {
                    fun extractRequestedAppName(appName: String): String = appName
                    fun normalize(name: String): String = name
                }
                """
            ),
            encoding="utf-8",
        )

        harness.write_text(
            textwrap.dedent(
                """
                package com.lchuang.xiaozhimobile

                import android.content.Context
                import android.media.AudioManager

                private class FakeContext(private val audioManager: AudioManager) : Context() {
                    override fun getSystemService(name: String): Any =
                        when (name) {
                            Context.AUDIO_SERVICE -> audioManager
                            Context.CAMERA_SERVICE -> android.hardware.camera2.CameraManager()
                            else -> error("unsupported service: $name")
                        }
                }

                private class FakeMediaVolumeController(
                    audioManager: AudioManager,
                ) : MediaVolumeController(audioManager) {
                    var snapshotResult = MediaVolumeSnapshot(
                        requestedPercent = null,
                        beforeStep = 4,
                        targetStep = 4,
                        afterStep = 4,
                        maxStep = 10,
                        actualPercent = 40,
                        isVolumeFixed = false,
                        retryCount = 0,
                        resultCode = RESULT_SNAPSHOT,
                    )
                    var setResult = snapshotResult.copy(requestedPercent = 60, targetStep = 6, afterStep = 6, actualPercent = 60, resultCode = RESULT_SET_OK)
                    var adjustRaiseResult = snapshotResult.copy(targetStep = 5, afterStep = 5, actualPercent = 50, resultCode = RESULT_ADJUST_OK)
                    var adjustLowerResult = snapshotResult.copy(targetStep = 3, afterStep = 3, actualPercent = 30, resultCode = RESULT_ADJUST_OK)

                    override fun snapshot(): MediaVolumeSnapshot = snapshotResult
                    override fun setPercent(percent: Int): MediaVolumeSnapshot = setResult
                    override fun adjust(direction: Int): MediaVolumeSnapshot =
                        when (direction) {
                            AudioManager.ADJUST_RAISE -> adjustRaiseResult
                            AudioManager.ADJUST_LOWER -> adjustLowerResult
                            else -> snapshotResult
                        }
                }

                private fun assertEquals(expected: Any?, actual: Any?, label: String) {
                    check(expected == actual) { "$label: expected=$expected actual=$actual" }
                }

                fun main() {
                    val audioManager = AudioManager(maxVolume = 10, initialVolume = 4)
                    val fakeController = FakeMediaVolumeController(audioManager)
                    val phoneController = PhoneController(
                        context = FakeContext(audioManager),
                        mediaVolumeControllerOverride = fakeController,
                    )

                    val setOk = phoneController.setMediaVolumePercent(60)
                    assertEquals(true, setOk.success, "set ok success")
                    assertEquals(60, setOk.actualPercent, "set ok actual")

                    fakeController.setResult = fakeController.setResult.copy(
                        actualPercent = 40,
                        resultCode = MediaVolumeController.RESULT_SET_MISMATCH,
                    )
                    val setMismatch = phoneController.setMediaVolumePercent(60)
                    assertEquals(false, setMismatch.success, "set mismatch success")
                    assertEquals(40, setMismatch.actualPercent, "set mismatch actual")

                    val raiseOk = phoneController.volumeUpVerified()
                    assertEquals(true, raiseOk.success, "raise ok success")
                    assertEquals(50, raiseOk.actualPercent, "raise ok actual")

                    fakeController.adjustRaiseResult = fakeController.adjustRaiseResult.copy(
                        afterStep = 4,
                        actualPercent = 40,
                        resultCode = MediaVolumeController.RESULT_ADJUST_NO_CHANGE,
                    )
                    val raiseNoChange = phoneController.volumeUpVerified()
                    assertEquals(false, raiseNoChange.success, "raise no-change success")
                    assertEquals(40, raiseNoChange.actualPercent, "raise no-change actual")

                    fakeController.adjustLowerResult = fakeController.adjustLowerResult.copy(
                        afterStep = 4,
                        actualPercent = 40,
                        resultCode = MediaVolumeController.RESULT_ADJUST_NO_CHANGE,
                    )
                    val lowerNoChange = phoneController.volumeDownVerified()
                    assertEquals(false, lowerNoChange.success, "lower no-change success")
                    assertEquals(40, lowerNoChange.actualPercent, "lower no-change actual")

                    println("PASS: FIX04 phone controller media-volume mapping")
                }
                """
            ),
            encoding="utf-8",
        )

        subprocess.run(
            [
                "kotlinc",
                str(android_content),
                str(android_camera),
                str(build),
                str(android_media),
                str(android_net),
                str(android_view),
                str(app_stubs),
                str(CONTROLLER),
                str(PHONE),
                str(harness),
                "-include-runtime",
                "-d",
                str(jar),
            ],
            cwd=ROOT,
            check=True,
        )
        subprocess.run(["java", "-jar", str(jar)], cwd=ROOT, check=True)


compile_and_run_harness()
assert_phone_controller_mapping()
print("PASS: FIX04 Task 1 media volume controller regression coverage")
