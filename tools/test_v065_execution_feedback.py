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

            fun main() {
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
