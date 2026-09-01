from pathlib import Path
import re
import subprocess
import tempfile
import textwrap


ROOT = Path(__file__).resolve().parents[1]
CONTROLLER = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/MediaVolumeController.kt"
PHONE = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/PhoneController.kt"


def assert_source_contract() -> None:
    controller = CONTROLLER.read_text(encoding="utf-8")
    phone = PHONE.read_text(encoding="utf-8")

    for token in ("ADJUST_RAISE", "ADJUST_LOWER", "beforeStep", "afterStep"):
        assert token in controller, f"missing controller token: {token}"

    assert "mediaVolumeController.adjust(AudioManager.ADJUST_RAISE)" in phone
    assert "mediaVolumeController.adjust(AudioManager.ADJUST_LOWER)" in phone
    assert re.search(r"snapshot\.afterStep\s*>\s*snapshot\.beforeStep", phone), (
        "volumeUpVerified must require observed raise evidence"
    )
    assert re.search(r"snapshot\.afterStep\s*<\s*snapshot\.beforeStep", phone), (
        "volumeDownVerified must require observed lower evidence"
    )


def compile_and_run_harness() -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
        temp = Path(temp_dir)
        android_content = temp / "AndroidContent.kt"
        android_camera = temp / "AndroidCamera.kt"
        android_media = temp / "AndroidMedia.kt"
        android_net = temp / "AndroidNet.kt"
        android_view = temp / "AndroidView.kt"
        app_stubs = temp / "PhoneControllerDeps.kt"
        harness = temp / "MediaVolumeStepsHarness.kt"
        jar = temp / "media-volume-steps.jar"

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
                    var lastDirection: Int? = null
                    var raiseResult = MediaVolumeSnapshot(
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
                    var lowerResult = MediaVolumeSnapshot(
                        requestedPercent = null,
                        beforeStep = 4,
                        targetStep = 3,
                        afterStep = 3,
                        maxStep = 10,
                        actualPercent = 30,
                        isVolumeFixed = false,
                        retryCount = 0,
                        resultCode = RESULT_ADJUST_OK,
                    )

                    override fun adjust(direction: Int): MediaVolumeSnapshot {
                        lastDirection = direction
                        return when (direction) {
                            AudioManager.ADJUST_RAISE -> raiseResult
                            AudioManager.ADJUST_LOWER -> lowerResult
                            else -> error("unexpected direction: $direction")
                        }
                    }
                }

                private fun assertEquals(expected: Any?, actual: Any?, label: String) {
                    check(expected == actual) { "$label: expected=$expected actual=$actual" }
                }

                private fun assertFalse(condition: Boolean, label: String) {
                    check(!condition) { label }
                }

                fun main() {
                    val audioManager = AudioManager(maxVolume = 10, initialVolume = 4)
                    val fakeController = FakeMediaVolumeController(audioManager)
                    val phoneController = PhoneController(
                        context = FakeContext(audioManager),
                        mediaVolumeControllerOverride = fakeController,
                    )

                    val raiseOk = phoneController.volumeUpVerified()
                    assertEquals(AudioManager.ADJUST_RAISE, fakeController.lastDirection, "raise direction")
                    assertEquals(true, raiseOk.success, "raise success")
                    assertEquals(50, raiseOk.actualPercent, "raise percent")

                    val lowerOk = phoneController.volumeDownVerified()
                    assertEquals(AudioManager.ADJUST_LOWER, fakeController.lastDirection, "lower direction")
                    assertEquals(true, lowerOk.success, "lower success")
                    assertEquals(30, lowerOk.actualPercent, "lower percent")

                    fakeController.raiseResult = fakeController.raiseResult.copy(
                        afterStep = 4,
                        actualPercent = 40,
                        resultCode = MediaVolumeController.RESULT_ADJUST_OK,
                    )
                    val raiseWithoutObservedStep = phoneController.volumeUpVerified()
                    assertFalse(
                        raiseWithoutObservedStep.success,
                        "raise must not succeed without observed afterStep > beforeStep"
                    )

                    fakeController.lowerResult = fakeController.lowerResult.copy(
                        afterStep = 4,
                        actualPercent = 40,
                        resultCode = MediaVolumeController.RESULT_ADJUST_OK,
                    )
                    val lowerWithoutObservedStep = phoneController.volumeDownVerified()
                    assertFalse(
                        lowerWithoutObservedStep.success,
                        "lower must not succeed without observed afterStep < beforeStep"
                    )

                    println("PASS: FIX04 Task 3 stepped media volume verification")
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


assert_source_contract()
compile_and_run_harness()
print("PASS: FIX04 Task 3 media volume step contract")
