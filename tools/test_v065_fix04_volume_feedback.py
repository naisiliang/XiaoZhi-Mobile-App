from pathlib import Path
import os
import subprocess
import sys
import tempfile
import textwrap


ROOT = Path(__file__).resolve().parents[1]
EXECUTOR = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/DeviceActionExecutor.kt"


def assert_source_contract() -> None:
    source = EXECUTOR.read_text(encoding="utf-8")
    assert "private fun volumeResult" in source, "DeviceActionExecutor.volumeResult missing"
    assert "result.actualPercent.coerceIn(0, 100)" in source, "volumeResult must clamp actual percent"
    assert "requestedPercent" not in source, "executor must not narrate requestedPercent"


def compile_and_run_harness() -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
        temp = Path(temp_dir)
        stubs = temp / "VolumeExecutorStubs.kt"
        harness = temp / "VolumeExecutorHarness.kt"
        jar = temp / "volume-feedback.jar"

        stubs.write_text(
            textwrap.dedent(
                """
                package com.lchuang.xiaozhimobile

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

                enum class MapAppPreference {
                    AUTO
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

                class AppExitController {
                    data class HomeResult(val success: Boolean, val code: String)

                    fun goHome(): HomeResult = HomeResult(true, "GO_HOME_OK")
                }

                class AppLauncher {
                    sealed class AppLaunchResult {
                        data class Success(val label: String) : AppLaunchResult()
                        data class Failure(val error: AppLaunchError) : AppLaunchResult()
                    }

                    enum class AppLaunchError {
                        PACKAGE_NOT_INSTALLED,
                        PACKAGE_NOT_VISIBLE,
                        NO_LAUNCH_ACTIVITY,
                        START_ACTIVITY_FAILED
                    }
                }

                class MapController {
                    data class MapActionResult(
                        val success: Boolean,
                        val code: String,
                        val message: String
                    )
                }

                class PhoneController(
                    var setResult: MediaVolumeResult = MediaVolumeResult(null, 50, true),
                    var upResult: MediaVolumeResult = MediaVolumeResult(null, 50, true),
                    var downResult: MediaVolumeResult = MediaVolumeResult(null, 50, true),
                ) {
                    data class MediaVolumeResult(
                        val requestedPercent: Int?,
                        val actualPercent: Int,
                        val success: Boolean
                    )

                    fun openApp(name: String): AppLauncher.AppLaunchResult = AppLauncher.AppLaunchResult.Success(name)
                    fun openMap(preference: MapAppPreference): MapController.MapActionResult =
                        MapController.MapActionResult(true, "OPEN_MAP_OK", "打开地图")
                    fun searchNearby(
                        keyword: String,
                        preference: MapAppPreference,
                        callback: (MapController.MapActionResult) -> Unit
                    ) {
                        callback(MapController.MapActionResult(true, "SEARCH_NEARBY_OK", "搜索附近"))
                    }
                    fun navigate(destination: String, preference: MapAppPreference): MapController.MapActionResult =
                        MapController.MapActionResult(true, "NAVIGATE_OK", "开始导航")
                    fun openBrowser(target: String): Boolean = true
                    fun mediaPlay() {}
                    fun mediaPause() {}
                    fun mediaStop() {}
                    fun mediaNext() {}
                    fun mediaPrevious() {}
                    fun setMediaVolumePercent(percent: Int): MediaVolumeResult = setResult
                    fun volumeUpVerified(): MediaVolumeResult = upResult
                    fun volumeDownVerified(): MediaVolumeResult = downResult
                    fun setFlashlight(enabled: Boolean): Boolean = true
                }
                """
            ),
            encoding="utf-8",
        )

        harness.write_text(
            textwrap.dedent(
                """
                package com.lchuang.xiaozhimobile

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
                    run {
                        val phone = PhoneController(
                            setResult = PhoneController.MediaVolumeResult(63, 0, true)
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
                        val phone = PhoneController(
                            upResult = PhoneController.MediaVolumeResult(null, 100, true)
                        )
                        val result = executeResult(DeviceAction.MediaVolumeUp, phone)
                        assertEquals(true, result.success, "max success")
                        assertEquals("VOLUME_UP", result.code, "max code")
                        assertEquals("媒体音量已经调整到最大", result.spokenResult, "max speech")
                        assertEquals("媒体音量100%", result.notificationSummary, "max summary")
                        assertEquals(100, result.actualPercent, "max actual")
                        assertNull(result.failureKind, "max failure kind")
                    }

                    run {
                        val phone = PhoneController(
                            setResult = PhoneController.MediaVolumeResult(70, 69, true)
                        )
                        val result = executeResult(DeviceAction.SetMediaVolume(70), phone)
                        assertEquals(true, result.success, "intermediate success")
                        assertEquals("SET_VOLUME", result.code, "intermediate code")
                        assertEquals("媒体音量已经调整到约69%", result.spokenResult, "intermediate speech")
                        assertEquals("媒体音量69%", result.notificationSummary, "intermediate summary")
                        assertEquals(69, result.actualPercent, "intermediate actual")
                        assertNull(result.failureKind, "intermediate failure kind")
                    }

                    run {
                        val phone = PhoneController(
                            downResult = PhoneController.MediaVolumeResult(null, 40, false)
                        )
                        val result = executeResult(DeviceAction.MediaVolumeDown, phone)
                        assertEquals(false, result.success, "limited success")
                        assertEquals("VOLUME_DOWN_PARTIAL", result.code, "limited code")
                        assertEquals("媒体音量现在约40%", result.spokenResult, "limited speech")
                        assertEquals("媒体音量40%", result.notificationSummary, "limited summary")
                        assertEquals(CommandFailureKind.EXECUTION_FAILED, result.failureKind, "limited failure kind")
                        assertEquals(40, result.actualPercent, "limited actual")
                    }

                    run {
                        val phone = PhoneController(
                            setResult = PhoneController.MediaVolumeResult(25, -5, false)
                        )
                        val result = executeResult(DeviceAction.SetMediaVolume(25), phone)
                        assertEquals(false, result.success, "failed success")
                        assertEquals("SET_VOLUME_PARTIAL", result.code, "failed code")
                        assertEquals("媒体音量现在是静音", result.spokenResult, "failed speech")
                        assertEquals("媒体音量0%", result.notificationSummary, "failed summary")
                        assertEquals(CommandFailureKind.EXECUTION_FAILED, result.failureKind, "failed failure kind")
                        assertEquals(0, result.actualPercent, "failed actual")
                    }

                    println("PASS: FIX04 Task 4 verified media volume result feedback")
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
                str(stubs),
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
