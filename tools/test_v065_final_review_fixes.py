from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile
import textwrap


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile"
EXECUTOR = (JAVA / "DeviceActionExecutor.kt").read_text(encoding="utf-8")
COORDINATOR = (JAVA / "ExecutionFeedbackCoordinator.kt").read_text(encoding="utf-8")
WAKE = (JAVA / "WakeService.kt").read_text(encoding="utf-8")


def function_body(source: str, declaration: str) -> str:
    start = source.index(declaration)
    opening = source.index("{", start)
    depth = 0
    for index in range(opening, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[opening + 1 : index]
    raise AssertionError(f"unbalanced body: {declaration}")


def braced_block(source: str, marker: str) -> str:
    start = source.index(marker)
    opening = source.index("{", start)
    depth = 0
    for index in range(opening, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[opening + 1 : index]
    raise AssertionError(f"unbalanced block: {marker}")


assert "AtomicBoolean" in EXECUTOR, "executor needs an exactly-once delivery guard"
assert "catch (_: Throwable)" in EXECUTOR, "executor needs a controller exception boundary"
assert "CommandFailureKind.EXECUTION_FAILED" in EXECUTOR
assert "resultDelivered" in COORDINATOR, "coordinator needs a runner-result exactly-once guard"
assert "catch (_: Throwable)" in COORDINATOR, "coordinator must convert runner throws to a result"

start = function_body(WAKE, "private fun startLocalCommandRecognition()")
capture_failure = braced_block(start, "catch (e: CommandAudioCaptureException)")
assert "recoverCommandAudioCaptureFailure(e.kind)" in capture_failure
assert "recoverRecognitionFailure(CommandFailureKind.ASR_EMPTY)" not in capture_failure
assert "commandRecognitionAttempts = (commandRecognitionAttempts - 1).coerceAtLeast(0)" in capture_failure

capture = function_body(WAKE, "private fun captureCommandAudio")
for kind in ["PERMISSION", "AUDIO_INIT", "AUDIO_START"]:
    assert f"CommandAudioCaptureFailureKind.{kind}" in capture
assert "setConversationState(ConversationState.LISTENING)" in start[start.index("captureCommandAudio") : start.index("if (samples.isEmpty())")]
assert "setConversationState(ConversationState.LISTENING)" not in capture_failure


def compiler_command() -> tuple[list[str], Path | None]:
    compiler = os.environ.get("KOTLINC", "kotlinc")
    embedded_stdlib = None
    if shutil.which(compiler) or compiler.lower().endswith((".bat", ".cmd")):
        command = ["cmd", "/c", compiler] if compiler.lower().endswith((".bat", ".cmd")) else [compiler]
    else:
        embedded = next(
            Path.home().glob(".gradle/wrapper/dists/**/lib/kotlin-compiler-embeddable-*.jar"),
            None,
        )
        if embedded is None:
            print(f"FAIL: Kotlin harness unavailable or unusable: {compiler}", file=sys.stderr)
            raise SystemExit(1)
        command = [
            shutil.which("java") or "java",
            "-Xmx192m",
            "-Xss1m",
            "-XX:+UseSerialGC",
            "-cp",
            f"{embedded.parent}/*",
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-no-stdlib",
            "-no-reflect",
            "-nowarn",
            "-classpath",
            str(embedded.parent / "kotlin-stdlib-1.7.10.jar"),
        ]
        embedded_stdlib = embedded.parent / "kotlin-stdlib-1.7.10.jar"
    try:
        subprocess.run([*command, "-version"], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except (FileNotFoundError, subprocess.CalledProcessError):
        print(f"FAIL: Kotlin harness unavailable or unusable: {compiler}", file=sys.stderr)
        raise SystemExit(1)
    return command, embedded_stdlib


with tempfile.TemporaryDirectory() as temp_dir:
    temp = Path(temp_dir)
    stubs = temp / "ControllerStubs.kt"
    stubs.write_text(
        textwrap.dedent(
            """
            package com.lchuang.xiaozhimobile

            enum class MapAppPreference { AUTO, AMAP, BAIDU, SYSTEM }

            object AppLauncher {
                enum class AppLaunchError {
                    PACKAGE_NOT_VISIBLE, PACKAGE_NOT_INSTALLED, NO_LAUNCH_ACTIVITY, START_ACTIVITY_FAILED
                }

                sealed class AppLaunchResult {
                    data class Success(val packageName: String, val label: String) : AppLaunchResult()
                    data class Failure(val error: AppLaunchError, val detail: String = "") : AppLaunchResult()
                }
            }

            object MapController {
                data class MapActionResult(
                    val success: Boolean,
                    val usedMap: MapAppPreference = MapAppPreference.AUTO,
                    val message: String,
                    val code: String
                )
            }

            open class PhoneController {
                data class MediaVolumeResult(
                    val actualPercent: Int,
                    val success: Boolean,
                    val resultCode: String = if (success) "SUCCESS" else "EXECUTION_FAILED"
                )
                var openAppBehavior: (String) -> AppLauncher.AppLaunchResult = { AppLauncher.AppLaunchResult.Success("pkg", it) }
                var nearbyBehavior: ((String, MapAppPreference, (MapController.MapActionResult) -> Unit) -> Unit) = { _, _, _ -> }

                fun openApp(name: String): AppLauncher.AppLaunchResult = openAppBehavior(name)
                fun openMap(preference: MapAppPreference): MapController.MapActionResult = MapController.MapActionResult(true, preference, "地图已经打开", "MAP_OK")
                fun navigate(destination: String, preference: MapAppPreference): MapController.MapActionResult = MapController.MapActionResult(true, preference, "导航已经打开", "NAV_OK")
                fun searchNearby(keyword: String, preference: MapAppPreference, callback: (MapController.MapActionResult) -> Unit) = nearbyBehavior(keyword, preference, callback)
                fun openBrowser(target: String): Boolean = true
                fun mediaPlay() {}
                fun mediaPause() {}
                fun mediaStop() {}
                fun mediaNext() {}
                fun mediaPrevious() {}
                fun setMediaVolumePercent(percent: Int): MediaVolumeResult = MediaVolumeResult(percent, true)
                fun volumeUpVerified(): MediaVolumeResult = MediaVolumeResult(50, true)
                fun volumeDownVerified(): MediaVolumeResult = MediaVolumeResult(50, true)
                fun setFlashlight(enabled: Boolean): Boolean = true
            }

            class AppExitController {
                data class HomeResult(val success: Boolean, val code: String)
                var behavior: () -> HomeResult = { HomeResult(true, "GO_HOME_OK") }
                fun goHome(): HomeResult = behavior()
            }
            """
        ),
        encoding="utf-8",
    )
    harness = temp / "FinalReviewHarness.kt"
    harness.write_text(
        textwrap.dedent(
            """
            import com.lchuang.xiaozhimobile.*

            private fun checkFailure(result: DeviceExecutionResult) {
                check(!result.success) { "expected failure, got $result" }
                check(result.failureKind == CommandFailureKind.EXECUTION_FAILED) { "wrong failure kind: $result" }
            }

            private fun assertExecutorBoundaries() {
                val phone = PhoneController()
                val executor = DeviceActionExecutor(phone, AppExitController())

                phone.openAppBehavior = { throw IllegalStateException("sync controller") }
                val syncResults = mutableListOf<DeviceExecutionResult>()
                executor.execute(DeviceAction.OpenApp("微信")) { syncResults += it }
                check(syncResults.size == 1) { "sync controller throw must produce exactly one result: $syncResults" }
                checkFailure(syncResults.single())

                val home = AppExitController()
                home.behavior = { throw IllegalStateException("home controller") }
                val homeResults = mutableListOf<DeviceExecutionResult>()
                DeviceActionExecutor(phone, home).execute(DeviceAction.GoHome(null)) { homeResults += it }
                check(homeResults.size == 1) { "home controller throw must produce exactly one result: $homeResults" }
                checkFailure(homeResults.single())

                phone.nearbyBehavior = { _, _, _ -> throw IllegalStateException("async API setup") }
                val asyncThrowResults = mutableListOf<DeviceExecutionResult>()
                executor.execute(DeviceAction.SearchNearby("咖啡", MapAppPreference.AUTO)) { asyncThrowResults += it }
                check(asyncThrowResults.size == 1) { "async API throw must produce exactly one result: $asyncThrowResults" }
                checkFailure(asyncThrowResults.single())

                phone.nearbyBehavior = { _, preference, callback ->
                    callback(MapController.MapActionResult(true, preference, "附近结果", "NEARBY_OK"))
                    throw IllegalStateException("async callback completion")
                }
                val callbackThenThrowResults = mutableListOf<DeviceExecutionResult>()
                executor.execute(DeviceAction.SearchNearby("咖啡", MapAppPreference.AUTO)) { callbackThenThrowResults += it }
                check(callbackThenThrowResults.size == 1) { "callback then throw must not duplicate result: $callbackThenThrowResults" }
                check(callbackThenThrowResults.single().success) { "existing successful async result must be preserved" }

                val callbackFailureResults = mutableListOf<DeviceExecutionResult>()
                try {
                    executor.execute(DeviceAction.OpenApp("微信")) {
                        callbackFailureResults += it
                        throw IllegalStateException("consumer callback")
                    }
                } catch (_: Throwable) {
                }
                check(callbackFailureResults.size == 1) { "callback exception must not cause duplicate result: $callbackFailureResults" }
            }

            private fun assertRunnerBoundaries() {
                fun transaction() = CommandTransaction(
                    "打开微信", "打开微信", DeviceAction.OpenApp("微信"), "打开微信正在执行"
                )
                fun result() = DeviceExecutionResult(true, "OPEN_APP_OK", "微信已打开", "打开微信")

                run {
                    val notifications = mutableListOf<String>()
                    val speechTexts = mutableListOf<String>()
                    var finished = 0
                    val coordinator = ExecutionFeedbackCoordinator(
                        DelayedScheduler { _, block -> block() },
                        DeviceActionRunner { _, _ -> throw IllegalStateException("runner") },
                        SpeechDriver { text, onStart, onDone ->
                            speechTexts += text
                            onStart()
                            onDone()
                        },
                        ExecutionIntentFormatter(),
                        CommandResultNotifier({ notifications += it }, { 0L })
                    )
                    coordinator.execute(transaction(), "请继续说。") { finished += 1 }
                    check(notifications == listOf("⏳ 正在执行：打开微信", "❌ 执行失败：打开微信")) { notifications.toString() }
                    check(speechTexts == listOf("打开微信正在执行", "设备操作执行失败。请再试一次。")) { speechTexts.toString() }
                    check(finished == 1) { "throwing runner must continue normally: $finished" }
                }

                run {
                    val notifications = mutableListOf<String>()
                    val speechDone = mutableListOf<() -> Unit>()
                    var speechCalls = 0
                    var finished = 0
                    val coordinator = ExecutionFeedbackCoordinator(
                        DelayedScheduler { _, block -> block() },
                        DeviceActionRunner { _, callback ->
                            callback(result())
                            throw IllegalStateException("runner after callback")
                        },
                        SpeechDriver { _, onStart, onDone ->
                            speechCalls += 1
                            val announcement = speechCalls == 1
                            onStart()
                            if (announcement) onDone() else speechDone += onDone
                        },
                        ExecutionIntentFormatter(),
                        CommandResultNotifier({ notifications += it }, { 0L })
                    )
                    coordinator.execute(transaction(), "请继续说。") { finished += 1 }
                    check(notifications == listOf("⏳ 正在执行：打开微信", "✅ 已成功执行：打开微信")) { notifications.toString() }
                    check(speechCalls == 2 && finished == 0) { "result callback should await final speech: $speechCalls / $finished" }
                    speechDone.single()()
                    check(finished == 1) { "callback then runner throw must finish once: $finished" }
                    check(notifications.size == 2 && speechCalls == 2) { "runner throw must not duplicate result feedback" }
                }
            }

            private fun assertCaptureFailurePolicy() {
                check(CommandAudioCaptureFailureKind.values().toSet() == setOf(
                    CommandAudioCaptureFailureKind.PERMISSION,
                    CommandAudioCaptureFailureKind.AUDIO_INIT,
                    CommandAudioCaptureFailureKind.AUDIO_START
                ))
                for (kind in CommandAudioCaptureFailureKind.values()) {
                    val message = CommandAudioCaptureRecovery.message(kind)
                    check(message.isNotBlank()) { "capture failure must have truthful copy: $kind" }
                    check("没听清" !in message) { "capture failure must not claim ASR silence: $message" }
                }
            }

            fun main() {
                assertExecutorBoundaries()
                assertRunnerBoundaries()
                assertCaptureFailurePolicy()
                println("PASS: final-review executor, coordinator, and capture-failure boundaries")
            }
            """
        ),
        encoding="utf-8",
    )
    jar = temp / "final-review.jar"
    command, embedded_stdlib = compiler_command()
    action_source = JAVA / "DeviceAction.kt"
    if any("K2JVMCompiler" in item for item in command):
        action_source = temp / "DeviceActionCompat.kt"
        action_source.write_text(
            (JAVA / "DeviceAction.kt").read_text(encoding="utf-8").replace("data object", "object"),
            encoding="utf-8",
        )
    subprocess.run(
        [
            *command,
            str(action_source),
            str(JAVA / "CommandTransaction.kt"),
            str(JAVA / "ExecutionIntentFormatter.kt"),
            str(JAVA / "CommandResultNotifier.kt"),
            str(JAVA / "ExecutionFeedbackCoordinator.kt"),
            str(JAVA / "DeviceActionExecutor.kt"),
            str(JAVA / "CommandAudioCaptureFailure.kt"),
            str(stubs),
            str(harness),
            *([] if embedded_stdlib is not None else ["-include-runtime"]),
            "-d",
            str(jar),
        ],
        cwd=ROOT,
        check=True,
    )
    if embedded_stdlib is None:
        subprocess.run(["java", "-jar", str(jar)], cwd=ROOT, check=True)
    else:
        subprocess.run(
            [
                shutil.which("java") or "java",
                "-cp",
                os.pathsep.join([str(jar), str(embedded_stdlib)]),
                "FinalReviewHarnessKt",
            ],
            cwd=ROOT,
            check=True,
        )

print("PASS: source contracts and deterministic final-review harness")
