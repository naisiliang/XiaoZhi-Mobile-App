from pathlib import Path
import os
import subprocess
import sys
import tempfile
import textwrap


ROOT = Path(__file__).resolve().parents[1]
EXECUTOR = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/DeviceActionExecutor.kt"
PHONE = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/PhoneController.kt"
CONTROLLER = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/MediaVolumeController.kt"


def assert_source_contract() -> None:
    phone = PHONE.read_text(encoding="utf-8")
    executor = EXECUTOR.read_text(encoding="utf-8")

    assert "data class MediaVolumeResult" in phone, "PhoneController.MediaVolumeResult missing"
    assert "val resultCode: String = if (success)" in phone, (
        "MediaVolumeResult must expose a backward-compatible resultCode default"
    )
    assert "snapshot.resultCode" in phone, "PhoneController must propagate controller result codes"
    assert "result.resultCode" in executor, "DeviceActionExecutor must branch on explicit resultCode"


def compile_and_run_harness() -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
        temp = Path(temp_dir)
        android_content = temp / "AndroidContent.kt"
        android_camera = temp / "AndroidCamera.kt"
        android_media = temp / "AndroidMedia.kt"
        build = temp / "Build.kt"
        android_net = temp / "AndroidNet.kt"
        android_view = temp / "AndroidView.kt"
        stubs = temp / "Task4VolumeDeps.kt"
        harness = temp / "Task4VolumeFeedbackHarness.kt"
        jar = temp / "task4-volume-feedback.jar"

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

        stubs.write_text(
            textwrap.dedent(
                """
                package com.lchuang.xiaozhimobile

                import android.content.Context

                enum class MapAppPreference {
                    AUTO
                }

                sealed interface DeviceAction {
                    data class OpenApp(val name: String) : DeviceAction
                    data class GoHome(val sourceApp: String?) : DeviceAction
                    data class OpenMap(val preference: MapAppPreference) : DeviceAction
                    data class SearchNearby(val keyword: String, val preference: MapAppPreference) : DeviceAction
                    data class Navigate(val destination: String, val preference: MapAppPreference) : DeviceAction
                    data class OpenWeb(val target: String) : DeviceAction
                    data object MediaPlay : DeviceAction
                    data object MediaPause : DeviceAction
                    data object MediaStop : DeviceAction
                    data object MediaNext : DeviceAction
                    data object MediaPrevious : DeviceAction
                    data class SetMediaVolume(val percent: Int) : DeviceAction
                    data object MediaVolumeUp : DeviceAction
                    data object MediaVolumeDown : DeviceAction
                    data class SetFlashlight(val enabled: Boolean) : DeviceAction
                }

                enum class CommandFailureKind {
                    EXECUTION_FAILED,
                    APP_NOT_FOUND
                }

                data class DeviceExecutionResult(
                    val success: Boolean,
                    val code: String,
                    val spokenResult: String,
                    val notificationSummary: String,
                    val failureKind: CommandFailureKind? = null,
                    val actualPercent: Int? = null
                )

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
                        data class Success(val label: String) : AppLaunchResult()
                        data class Failure(val error: AppLaunchError, val reason: String) : AppLaunchResult()
                    }

                    enum class AppLaunchError {
                        PACKAGE_NOT_INSTALLED,
                        PACKAGE_NOT_VISIBLE,
                        NO_LAUNCH_ACTIVITY,
                        START_ACTIVITY_FAILED
                    }

                    fun launch(entry: InstalledAppRegistry.AppEntry): AppLaunchResult =
                        AppLaunchResult.Failure(AppLaunchError.PACKAGE_NOT_INSTALLED, "not needed")
                }

                class MapController(context: Context) {
                    data class MapActionResult(
                        val success: Boolean,
                        val code: String,
                        val message: String
                    )

                    fun openMap(preference: MapAppPreference): MapActionResult =
                        MapActionResult(true, "OPEN_MAP_OK", "打开地图")

                    fun navigate(destination: String, preference: MapAppPreference): MapActionResult =
                        MapActionResult(true, "NAVIGATE_OK", "开始导航")

                    fun searchNearby(
                        keyword: String,
                        preference: MapAppPreference,
                        callback: (MapActionResult) -> Unit,
                    ) {
                        callback(MapActionResult(true, "SEARCH_NEARBY_OK", "搜索附近"))
                    }
                }

                object AppNameMatcher {
                    fun extractRequestedAppName(appName: String): String = appName
                    fun normalize(name: String): String = name
                }

                class AppExitController {
                    data class HomeResult(val success: Boolean, val code: String)

                    fun goHome(): HomeResult = HomeResult(true, "GO_HOME_OK")
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
                    var nextSet = MediaVolumeSnapshot(
                        requestedPercent = 70,
                        beforeStep = 5,
                        targetStep = 7,
                        afterStep = 7,
                        maxStep = 10,
                        actualPercent = 70,
                        isVolumeFixed = false,
                        retryCount = 0,
                        resultCode = RESULT_SET_OK,
                    )
                    var nextAdjust = MediaVolumeSnapshot(
                        requestedPercent = null,
                        beforeStep = 4,
                        targetStep = 5,
                        afterStep = 5,
                        maxStep = 10,
                        actualPercent = 50,
                        isVolumeFixed = false,
                        retryCount = 0,
                        resultCode = RESULT_ADJUST_OK,
                    )

                    override fun setPercent(percent: Int): MediaVolumeSnapshot = nextSet
                    override fun adjust(direction: Int): MediaVolumeSnapshot = nextAdjust
                }

                private fun assertEquals(expected: Any?, actual: Any?, label: String) {
                    check(expected == actual) { "$label: expected=$expected actual=$actual" }
                }

                private fun assertNull(actual: Any?, label: String) {
                    check(actual == null) { "$label: expected null actual=$actual" }
                }

                private fun executeResult(
                    action: DeviceAction,
                    phone: PhoneController
                ): DeviceExecutionResult {
                    var delivered: DeviceExecutionResult? = null
                    DeviceActionExecutor(phone, AppExitController()).execute(action) { result ->
                        delivered = result
                    }
                    return checkNotNull(delivered) { "executor did not deliver a result for $action" }
                }

                fun main() {
                    val audioManager = AudioManager(maxVolume = 10, initialVolume = 5)
                    val fakeController = FakeMediaVolumeController(audioManager)
                    val phone = PhoneController(
                        context = FakeContext(audioManager),
                        mediaVolumeControllerOverride = fakeController,
                    )

                    run {
                        fakeController.nextSet = fakeController.nextSet.copy(
                            requestedPercent = 63,
                            afterStep = 0,
                            actualPercent = 0,
                            resultCode = MediaVolumeController.RESULT_SET_OK,
                        )
                        val result = executeResult(DeviceAction.SetMediaVolume(63), phone)
                        assertEquals(true, result.success, "mute success")
                        assertEquals("SET_VOLUME", result.code, "mute code")
                        assertEquals("媒体音量已经静音", result.spokenResult, "mute speech")
                        assertEquals("媒体音量0%", result.notificationSummary, "mute summary")
                        assertEquals(0, result.actualPercent, "mute actual")
                        assertNull(result.failureKind, "mute failure kind")
                    }

                    run {
                        fakeController.nextAdjust = fakeController.nextAdjust.copy(
                            beforeStep = 9,
                            targetStep = 10,
                            afterStep = 10,
                            actualPercent = 100,
                            resultCode = MediaVolumeController.RESULT_ADJUST_OK,
                        )
                        val phoneResult = phone.volumeUpVerified()
                        assertEquals("SUCCESS", phoneResult.resultCode, "raise propagated success code")
                        val result = executeResult(DeviceAction.MediaVolumeUp, phone)
                        assertEquals(true, result.success, "max success")
                        assertEquals("VOLUME_UP", result.code, "max code")
                        assertEquals("媒体音量已经调整到最大", result.spokenResult, "max speech")
                        assertEquals("媒体音量100%", result.notificationSummary, "max summary")
                        assertEquals(100, result.actualPercent, "max actual")
                        assertNull(result.failureKind, "max failure kind")
                    }

                    run {
                        fakeController.nextSet = fakeController.nextSet.copy(
                            requestedPercent = 70,
                            targetStep = 7,
                            afterStep = 7,
                            actualPercent = 69,
                            resultCode = MediaVolumeController.RESULT_SET_OK,
                        )
                        val phoneResult = phone.setMediaVolumePercent(70)
                        assertEquals("SUCCESS", phoneResult.resultCode, "set propagated success code")
                        val result = executeResult(DeviceAction.SetMediaVolume(70), phone)
                        assertEquals(true, result.success, "intermediate success")
                        assertEquals("SET_VOLUME", result.code, "intermediate code")
                        assertEquals("媒体音量已经调整到约69%", result.spokenResult, "intermediate speech")
                        assertEquals("媒体音量69%", result.notificationSummary, "intermediate summary")
                        assertEquals(69, result.actualPercent, "intermediate actual")
                        assertNull(result.failureKind, "intermediate failure kind")
                    }

                    run {
                        fakeController.nextAdjust = fakeController.nextAdjust.copy(
                            beforeStep = 4,
                            targetStep = 3,
                            afterStep = 4,
                            actualPercent = 40,
                            resultCode = MediaVolumeController.RESULT_ADJUST_NO_CHANGE,
                        )
                        val phoneResult = phone.volumeDownVerified()
                        assertEquals("SYSTEM_LIMITED", phoneResult.resultCode, "lower propagated limited code")
                        val result = executeResult(DeviceAction.MediaVolumeDown, phone)
                        assertEquals(false, result.success, "limited success")
                        assertEquals("VOLUME_DOWN_PARTIAL", result.code, "limited code")
                        assertEquals("媒体音量现在约40%", result.spokenResult, "limited speech")
                        assertEquals("媒体音量40%", result.notificationSummary, "limited summary")
                        assertEquals(CommandFailureKind.EXECUTION_FAILED, result.failureKind, "limited failure kind")
                        assertEquals(40, result.actualPercent, "limited actual")
                    }

                    run {
                        fakeController.nextSet = fakeController.nextSet.copy(
                            requestedPercent = 25,
                            beforeStep = 0,
                            targetStep = 3,
                            afterStep = 0,
                            actualPercent = -5,
                            resultCode = MediaVolumeController.RESULT_SET_ERROR,
                        )
                        val phoneResult = phone.setMediaVolumePercent(25)
                        assertEquals("EXECUTION_FAILED", phoneResult.resultCode, "set propagated error code")
                        val result = executeResult(DeviceAction.SetMediaVolume(25), phone)
                        assertEquals(false, result.success, "failed success")
                        assertEquals("SET_VOLUME_FAILED", result.code, "failed code")
                        assertEquals("媒体音量现在是静音", result.spokenResult, "failed speech")
                        assertEquals("媒体音量0%", result.notificationSummary, "failed summary")
                        assertEquals(CommandFailureKind.EXECUTION_FAILED, result.failureKind, "failed failure kind")
                        assertEquals(0, result.actualPercent, "failed actual")
                    }

                    println("PASS: FIX04 Task 4 verified media volume result feedback states")
                }
                """
            ),
            encoding="utf-8",
        )

        compiler = os.environ.get("KOTLINC", "kotlinc")
        compiler_command = ["cmd", "/c", compiler] if compiler.lower().endswith((".bat", ".cmd")) else [compiler]
        try:
            subprocess.run(
                [*compiler_command, "-version"],
                cwd=ROOT,
                check=True,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
        except (FileNotFoundError, subprocess.CalledProcessError):
            print(
                f"FAIL: volume-feedback Kotlin harness unavailable or unusable: {compiler}",
                file=sys.stderr,
            )
            raise SystemExit(1)

        subprocess.run(
            [
                *compiler_command,
                str(android_content),
                str(android_camera),
                str(build),
                str(android_media),
                str(android_net),
                str(android_view),
                str(stubs),
                str(CONTROLLER),
                str(PHONE),
                str(EXECUTOR),
                str(harness),
                "-include-runtime",
                "-d",
                str(jar),
            ],
            cwd=ROOT,
            check=True,
        )
        subprocess.run(["java", "-jar", str(jar)], cwd=ROOT, check=True)


assert_source_contract()
compile_and_run_harness()
