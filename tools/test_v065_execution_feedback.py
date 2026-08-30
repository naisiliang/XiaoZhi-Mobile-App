from pathlib import Path
import os
import subprocess
import tempfile
import textwrap


root = Path(__file__).resolve().parents[1]
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
                    "ANNOUNCEMENT_ON_START"
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
