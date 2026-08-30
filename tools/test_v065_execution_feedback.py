from pathlib import Path
import os
import re
import subprocess
import tempfile
import textwrap


root = Path(__file__).resolve().parents[1]
wake_path = root / "app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt"
wake = wake_path.read_text(encoding="utf-8")


def function_body(name: str) -> str:
    match = re.search(rf"private fun {re.escape(name)}\b", wake)
    assert match, f"WakeService.{name} missing"
    signature_open = wake.find("(", match.end())
    assert signature_open >= 0, name
    signature_depth = 0
    signature_close = -1
    for index in range(signature_open, len(wake)):
        if wake[index] == "(":
            signature_depth += 1
        elif wake[index] == ")":
            signature_depth -= 1
            if signature_depth == 0:
                signature_close = index
                break
    assert signature_close >= 0, f"WakeService.{name} signature is not balanced"
    opening = wake.find("{", signature_close)
    assert opening >= 0, f"WakeService.{name} body missing"
    depth = 0
    for index in range(opening, len(wake)):
        if wake[index] == "{":
            depth += 1
        elif wake[index] == "}":
            depth -= 1
            if depth == 0:
                return wake[opening + 1 : index]
    raise AssertionError(f"WakeService.{name} body is not balanced")


def braced_block(source: str, marker: str) -> str:
    marker_index = source.find(marker)
    assert marker_index >= 0, f"missing block marker: {marker}"
    opening = source.find("{", marker_index)
    assert opening >= 0, f"missing opening brace after: {marker}"
    depth = 0
    for index in range(opening, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[opening + 1 : index]
    raise AssertionError(f"unbalanced block after: {marker}")


# Static Android-service integration contract. The pure transaction machinery is
# compiler-backed below; these assertions pin the Android ownership and routing
# that cannot be executed without the Android runtime.
for field in [
    "private lateinit var appExitController: AppExitController",
    "private lateinit var deviceActionExecutor: DeviceActionExecutor",
    "private lateinit var executionFormatter: ExecutionIntentFormatter",
    "private lateinit var commandResultNotifier: CommandResultNotifier",
    "private lateinit var executionCoordinator: ExecutionFeedbackCoordinator",
    "private val audioEnhancementManager = AudioEnhancementManager()",
]:
    assert field in wake, f"WakeService dependency field missing: {field}"

on_create = wake[wake.index("override fun onCreate()") : wake.index("override fun onStartCommand")]
for token in [
    "appExitController = AppExitController(this)",
    "deviceActionExecutor = DeviceActionExecutor(phone, appExitController)",
    "executionFormatter = ExecutionIntentFormatter()",
    "commandResultNotifier = CommandResultNotifier(",
    "publish = { text -> updateNotificationRaw(text) }",
    "clockMs = { SystemClock.elapsedRealtime() }",
    "holdMs = 4000L",
    "executionCoordinator = ExecutionFeedbackCoordinator(",
    "DelayedScheduler",
    "mainHandler.postDelayed(block, delay)",
    "DeviceActionRunner",
    "deviceActionExecutor.execute(action, callback)",
    "SpeechDriver",
    "speakWithProgress(text, onStart, onDone)",
    "actionDelayMs = 120L",
]:
    assert token in on_create, f"WakeService onCreate wiring missing: {token}"

process = function_body("processNonExitUtterance")
plan_index = process.find("router.plan(normalized)")
ai_index = process.find("aiOrchestrator.respond")
assert 0 <= plan_index < ai_index, "local side-effect-free plan must run before AI"
assert "router.handle(" not in process, "WakeService must not bypass the unified action transaction"
planned = braced_block(process, "is DeviceCommandPlan.Planned ->")
assert "executeDeviceAction(rawText, normalized" in planned
assert "return" in planned, "a planned local action must remain handled even when execution fails"

tool_branch = process[process.index("is AiOutcome.Tool ->") :]
safe_plan = tool_branch.find("safeToolExecutor.plan(outcome.call)")
allowed = tool_branch.find("is SafeToolPlan.Allowed")
same_funnel = tool_branch.find("executeDeviceAction(rawText, normalized", allowed)
rejected = tool_branch.find("is SafeToolPlan.Rejected")
assert 0 <= safe_plan < allowed < same_funnel, "AI tools must plan then use executeDeviceAction"
assert rejected >= 0 and "SAFETY_REJECTED" in tool_branch[rejected:], (
    "rejected AI tools must report SAFETY_REJECTED"
)
assert "safeToolExecutor.execute" not in tool_branch, "AI tools must never bypass SafeToolExecutor.plan"

execute_action = function_body("executeDeviceAction")
capture_guard = execute_action.find("commandListening.get()")
duplicate_guard = execute_action.find("isDuplicateDeviceCommand(normalized)")
coordinator_call = execute_action.find("executionCoordinator.execute")
assert 0 <= capture_guard < coordinator_call, "command capture must guard device execution"
assert 0 <= duplicate_guard < coordinator_call, "duplicate guard must run before every action"
for token in [
    "CommandTransaction(",
    "executionFormatter.announcement(action)",
    "completed.result",
    "memory.addTurn(",
    "continueConversationSession(immediate = true)",
]:
    assert token in execute_action, f"unified execution callback missing: {token}"
result_index = execute_action.find("completed.result")
memory_index = execute_action.find("memory.addTurn(")
assert result_index < memory_index, "AI/local memory may only record the actual callback result"
success_block = braced_block(execute_action, "if (result.success) {")
assert "successfulDeviceActions += 1" in success_block
assert execute_action.count("successfulDeviceActions += 1") == 1, (
    "failed actions must not increment successfulDeviceActions"
)

start_recognition = function_body("startLocalCommandRecognition")
assert "ttsSpeaking.get()" in start_recognition, "command ASR must refuse capture while TTS speaks"
process_post = start_recognition.find("processUtterance(text)")
capture_cleared = start_recognition.rfind("commandListening.set(false)", 0, process_post)
assert 0 <= capture_cleared < process_post, "capture flag must clear before utterance processing"

speak_progress = function_body("speakWithProgress")
for token in [
    "AtomicBoolean(false)",
    "utteranceId != id",
    "onStart",
    "onDone",
    "mainHandler.postDelayed",
    "150L",
    "ttsSpeaking.set(true)",
    "ttsSpeaking.set(false)",
]:
    assert token in speak_progress, f"progress-aware TTS contract missing: {token}"
assert speak_progress.count("override fun onError") == 2
assert "compareAndSet(false, true)" in speak_progress, "TTS callbacks must be exactly-once guarded"
speak_then = function_body("speakThen")
assert "speakWithProgress(text, onDone = done)" in speak_then

update_raw = function_body("updateNotificationRaw")
update_transient = function_body("updateNotification")
assert ".notify(NOTIFY_ID, notification(text))" in update_raw
assert "commandResultNotifier.publishTransient(text)" in update_transient
restart = function_body("restartWakeListening")
clear_retention = restart.find("commandResultNotifier.clearRetention()")
idle_publish = restart.find("updateNotificationRaw(")
assert 0 <= clear_retention < idle_publish, "KWS idle must explicitly replace retained command results"

sources = [
    root / "app/src/main/java/com/lchuang/xiaozhimobile/DeviceAction.kt",
    root / "app/src/main/java/com/lchuang/xiaozhimobile/CommandTransaction.kt",
    root / "app/src/main/java/com/lchuang/xiaozhimobile/ExecutionIntentFormatter.kt",
    root / "app/src/main/java/com/lchuang/xiaozhimobile/CommandResultNotifier.kt",
    root / "app/src/main/java/com/lchuang/xiaozhimobile/ExecutionFeedbackCoordinator.kt",
]

for source in sources:
    assert source.exists(), f"missing {source.name}"
    assert "import android." not in source.read_text(encoding="utf-8"), (
        f"{source.name} must remain Android-independent"
    )

with tempfile.TemporaryDirectory() as td:
    td = Path(td)
    stubs = td / "MapPreferenceStub.kt"
    stubs.write_text(
        """
        package com.lchuang.xiaozhimobile
        enum class MapAppPreference { AUTO, AMAP, BAIDU }
        """,
        encoding="utf-8",
    )
    harness = td / "ExecutionFeedbackHarness.kt"
    harness.write_text(
        textwrap.dedent(
            """
            import com.lchuang.xiaozhimobile.*

            class AndroidHandlerShape {
                var observedDelayMs: Long? = null
                fun postDelayed(block: Runnable, delayMs: Long): Boolean {
                    observedDelayMs = delayMs
                    block.run()
                    return true
                }
            }

            fun assertAndroidSamWiringShape() {
                val handler = AndroidHandlerShape()
                var called = false
                val scheduler = DelayedScheduler { delay, block ->
                    handler.postDelayed(block, delay)
                }
                scheduler.postDelayed(120L) { called = true }
                check(called)
                check(handler.observedDelayMs == 120L)
            }

            fun assertNotifierRetention() {
                class FakeClock(var nowMs: Long = 0L) {
                    fun advance(deltaMs: Long) { nowMs += deltaMs }
                }

                val published = mutableListOf<String>()
                val clock = FakeClock(1_000L)
                val notifier = CommandResultNotifier(
                    publish = { published += it },
                    clockMs = { clock.nowMs },
                    holdMs = 4_000L
                )

                notifier.running("打开微信正在执行")
                check(published == listOf("打开微信正在执行")) {
                    "running should publish immediately without retention: $published"
                }
                check(notifier.retainedText() == null) {
                    "running must not retain transient execution text"
                }

                notifier.success("✅ 已成功执行：打开微信")
                check(published == listOf("打开微信正在执行", "✅ 已成功执行：打开微信")) {
                    "success should publish after running: $published"
                }
                check(notifier.retainedText() == "✅ 已成功执行：打开微信") {
                    "success text should be retained immediately"
                }
                notifier.publishTransient("连续会话中...")
                check(published.last() == "✅ 已成功执行：打开微信") {
                    "active retention must suppress transient overwrite: $published"
                }

                clock.advance(3_999L)
                check(notifier.retainedText() == "✅ 已成功执行：打开微信") {
                    "retained success must survive through t+3999"
                }
                notifier.publishTransient("连续会话中...")
                check(published.last() == "✅ 已成功执行：打开微信") {
                    "t+3999 should still republish retained success: $published"
                }

                clock.advance(1L)
                check(notifier.retainedText() == null) {
                    "retained success must expire exactly at t+4000"
                }
                notifier.publishTransient("连续会话中...")
                check(published.last() == "连续会话中...") {
                    "expired retention must stop suppressing transient text: $published"
                }

                notifier.failure("❌ 执行失败：打开微信")
                check(published.last() == "❌ 执行失败：打开微信") {
                    "failure should publish its exact text"
                }
                check(notifier.retainedText() == "❌ 执行失败：打开微信") {
                    "failure text should also be retained"
                }
                notifier.clearRetention()
                check(notifier.retainedText() == null) {
                    "clearRetention must remove retained text immediately"
                }
                notifier.publishTransient("全离线语音已开启...")
                check(published.last() == "全离线语音已开启...") {
                    "cleared retention must allow wake-idle text to win immediately: $published"
                }
            }

            fun assertFailureFeedback() {
                val events = mutableListOf<String>()
                val notifications = mutableListOf<String>()
                var delayedBlock: (() -> Unit)? = null
                var finalDone: (() -> Unit)? = null
                val scheduler = DelayedScheduler { delayMs, block ->
                    check(events.lastOrNull() == "FAILURE_ANNOUNCEMENT_ON_START")
                    check(delayMs == 120L)
                    events += "FAILURE_SCHEDULE_120"
                    delayedBlock = block
                }
                val runner = DeviceActionRunner { action, callback ->
                    check(action == DeviceAction.OpenApp("微信"))
                    events += "FAILURE_DEVICE_EXECUTE"
                    callback(
                        DeviceExecutionResult(
                            false,
                            "OPEN_APP_FAILED",
                            "没有成功打开微信",
                            "启动微信失败",
                            CommandFailureKind.EXECUTION_FAILED
                        )
                    )
                }
                val speech = SpeechDriver { text, onStart, onDone ->
                    if (events.none { it == "FAILURE_ANNOUNCEMENT_SPEAK" }) {
                        check(text == "打开微信正在执行")
                        events += "FAILURE_ANNOUNCEMENT_SPEAK"
                        events += "FAILURE_ANNOUNCEMENT_ON_START"
                        onStart()
                    } else {
                        check(events.contains("FAILURE_RESULT_NOTIFICATION"))
                        check(text == "没有成功打开微信。请再试一次。")
                        events += "FAILURE_FINAL_SPEAK"
                        finalDone = onDone
                    }
                }
                val notifier = CommandResultNotifier(
                    publish = { text ->
                        notifications += text
                        events += if (text.startsWith("❌")) {
                            "FAILURE_RESULT_NOTIFICATION"
                        } else {
                            "FAILURE_RUNNING_NOTIFICATION"
                        }
                    },
                    clockMs = { 0L }
                )
                val coordinator = ExecutionFeedbackCoordinator(
                    scheduler, runner, speech, ExecutionIntentFormatter(), notifier
                )

                coordinator.execute(
                    CommandTransaction(
                        "打开微信", "打开微信", DeviceAction.OpenApp("微信"), "打开微信正在执行"
                    ),
                    "请继续说。"
                ) {
                    check(events.lastOrNull() == "FAILURE_FINAL_ON_DONE") {
                        "failure finished before final TTS completed: $events"
                    }
                    events += "FAILURE_FINISHED"
                    check(!requireNotNull(it.result).success)
                }

                delayedBlock?.invoke() ?: error("failure announcement did not schedule device action")
                check(events.lastOrNull() == "FAILURE_FINAL_SPEAK")
                events += "FAILURE_FINAL_ON_DONE"
                finalDone?.invoke() ?: error("failure final speech was not requested")
                check(notifications == listOf(
                    "打开微信正在执行",
                    "❌ 执行失败：打开微信"
                )) { "failure notification must use failure copy: $notifications" }
                check(events == listOf(
                    "FAILURE_RUNNING_NOTIFICATION",
                    "FAILURE_ANNOUNCEMENT_SPEAK",
                    "FAILURE_ANNOUNCEMENT_ON_START",
                    "FAILURE_SCHEDULE_120",
                    "FAILURE_DEVICE_EXECUTE",
                    "FAILURE_RESULT_NOTIFICATION",
                    "FAILURE_FINAL_SPEAK",
                    "FAILURE_FINAL_ON_DONE",
                    "FAILURE_FINISHED"
                )) { "unexpected failure feedback order: $events" }
            }

            fun main() {
                assertAndroidSamWiringShape()
                assertNotifierRetention()
                assertFailureFeedback()
                val events = mutableListOf<String>()
                val notifications = mutableListOf<String>()
                var delayedBlock: (() -> Unit)? = null
                var announcementDone: (() -> Unit)? = null
                var finalDone: (() -> Unit)? = null

                val scheduler = DelayedScheduler { delayMs, block ->
                    check(events.lastOrNull() == "ANNOUNCEMENT_ON_START") {
                        "device execution was scheduled before announcement onStart: $events"
                    }
                    check(delayMs == 120L) { "expected 120ms action delay, got $delayMs" }
                    events += "SCHEDULE_120"
                    delayedBlock = block
                }
                val runner = DeviceActionRunner { action, callback ->
                    check(events.lastOrNull() == "SCHEDULE_120") {
                        "device execution must follow scheduling: $events"
                    }
                    check(action == DeviceAction.SetMediaVolume(70))
                    events += "DEVICE_EXECUTE"
                    callback(
                        DeviceExecutionResult(
                            true,
                            "SET_VOLUME",
                            "媒体音量已经调整到69%",
                            "媒体音量70%",
                            actualPercent = 69
                        )
                    )
                }
                val speech = SpeechDriver { text, onStart, onDone ->
                    when (events.count { it == "ANNOUNCEMENT_SPEAK_CALLED" }) {
                        0 -> {
                            check(text == "调整媒体音量到百分之七十正在执行")
                            events += "ANNOUNCEMENT_SPEAK_CALLED"
                            events += "ANNOUNCEMENT_ON_START"
                            onStart()
                            announcementDone = onDone
                        }
                        else -> {
                            check(events.contains("RESULT_NOTIFICATION")) {
                                "final speech started before a result notification: $events"
                            }
                            check(events.contains("ANNOUNCEMENT_ON_START")) {
                                "final speech started before announcement onStart: $events"
                            }
                            check(text == "媒体音量已经调整到69%")
                            events += "FINAL_SPEAK"
                            finalDone = onDone
                        }
                    }
                }
                val notifier = CommandResultNotifier(
                    publish = { text ->
                        notifications += text
                        events += if (text.startsWith("✅") || text.startsWith("❌")) {
                            "RESULT_NOTIFICATION"
                        } else {
                            "RUNNING_NOTIFICATION"
                        }
                    },
                    clockMs = { 0L }
                )
                val coordinator = ExecutionFeedbackCoordinator(
                    scheduler, runner, speech, ExecutionIntentFormatter(), notifier
                )

                coordinator.execute(
                    CommandTransaction(
                        "音量调到70%",
                        "音量调到70%",
                        DeviceAction.SetMediaVolume(70),
                        "调整媒体音量到百分之七十正在执行"
                    ),
                    "你有什么需求请说？"
                ) {
                    check(events.lastOrNull() == "FINAL_ON_DONE") {
                        "finished before final TTS completed: $events"
                    }
                    events += "FINISHED"
                    check(it.result?.actualPercent == 69)
                }

                check(events == listOf(
                    "RUNNING_NOTIFICATION",
                    "ANNOUNCEMENT_SPEAK_CALLED",
                    "ANNOUNCEMENT_ON_START",
                    "SCHEDULE_120"
                )) { "announcement must gate scheduling: $events" }
                delayedBlock?.invoke() ?: error("announcement onStart did not schedule the device action")
                check(announcementDone != null) { "announcement should retain its onDone callback" }
                check(events.lastOrNull() == "FINAL_SPEAK") {
                    "device result must lead to final speech: $events"
                }
                events += "FINAL_ON_DONE"
                finalDone?.invoke() ?: error("result speech was not requested")

                check(events == listOf(
                    "RUNNING_NOTIFICATION",
                    "ANNOUNCEMENT_SPEAK_CALLED",
                    "ANNOUNCEMENT_ON_START",
                    "SCHEDULE_120",
                    "DEVICE_EXECUTE",
                    "RESULT_NOTIFICATION",
                    "FINAL_SPEAK",
                    "FINAL_ON_DONE",
                    "FINISHED"
                )) { "unexpected execution feedback order: $events" }
                check(notifications == listOf(
                    "调整媒体音量到百分之七十正在执行",
                    "✅ 已成功执行：媒体音量69%"
                )) { "result notification must use actual result copy: $notifications" }
                println("PASS: v0.6.5 execution feedback event order")
            }
            """
        ),
        encoding="utf-8",
    )
    jar = td / "execution-feedback.jar"
    compiler = os.environ.get("KOTLINC", "kotlinc")
    compiler_command = ["cmd", "/c", compiler] if compiler.lower().endswith((".bat", ".cmd")) else [compiler]
    subprocess.run(
        [
            *compiler_command,
            *(str(source) for source in sources),
            str(stubs),
            str(harness),
            "-include-runtime",
            "-d",
            str(jar),
        ],
        cwd=root,
        check=True,
    )
    subprocess.run(["java", "-jar", str(jar)], cwd=root, check=True)
