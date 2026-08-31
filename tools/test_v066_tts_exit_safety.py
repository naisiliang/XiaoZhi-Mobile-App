from pathlib import Path
import os
import re
import subprocess
import sys
import tempfile
import textwrap


root = Path(__file__).resolve().parents[1]
registry_path = root / "app/src/main/java/com/lchuang/xiaozhimobile/TtsProgressRegistry.kt"
wake_path = root / "app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt"
detector_path = root / "app/src/main/java/com/lchuang/xiaozhimobile/ConversationExitDetector.kt"
registry = registry_path.read_text(encoding="utf-8")
wake = wake_path.read_text(encoding="utf-8")
detector = detector_path.read_text(encoding="utf-8")


def function_body(source: str, signature: str) -> str:
    start = source.index(signature)
    opening = source.index("{", start)
    depth = 0
    for index in range(opening, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[opening + 1 : index]
    raise AssertionError(f"unbalanced function: {signature}")


# These are behavior contracts: they fail against the reviewed implementation,
# before any production change is made.
assert "onWatchdogTimeout: (String) -> Boolean" in registry, (
    "watchdog must report confirmed engine stop instead of using Unit"
)
assert "synthetic = true" in registry and "synthetic = false" in registry, (
    "real and watchdog completion callbacks must be distinguishable"
)
assert "state.timedOut.set(true)" in registry, "confirmed timeout must invalidate real callbacks"
assert "engine.stop() == TextToSpeech.SUCCESS" in wake, (
    "WakeService must only confirm stop on the Android success result"
)
assert "catch (_: Throwable) {\n                        false" in wake, (
    "WakeService must treat stop exceptions as unconfirmed"
)

process_utterance = function_body(wake, "private fun processUtterance(rawText: String)")
local_plan = process_utterance.index("val localPlan = router.plan(normalized)")
exit_classification = process_utterance.index("exitDetector.classify(normalized)")
assert local_plan < exit_classification, (
    "local app-exit planning must precede broad assistant-session exit classification"
)
assert "localPlan.action is DeviceAction.GoHome" in process_utterance
assert "localPlan.action.sourceApp != null" in process_utterance
assert "executeDeviceAction(rawText, normalized, localPlan.action, heard)" in process_utterance

for noun in ["账户", "账号", "密码", "登录", "页面", "界面"]:
    assert noun in detector, f"generic exit noun missing from detector: {noun}"

print("PASS: v0.6.6 source contracts for TTS timeout and exit ordering")

with tempfile.TemporaryDirectory() as td:
    td = Path(td)
    harness = td / "TtsExitSafetyHarness.kt"
    harness.write_text(
        textwrap.dedent(
            """
            import com.lchuang.xiaozhimobile.ConversationExitDetector
            import com.lchuang.xiaozhimobile.ExitDecision
            import com.lchuang.xiaozhimobile.TtsProgressRegistry

            fun assertQueuedRealCompletionIsStaleAfterConfirmedWatchdog() {
                val queued = mutableListOf<() -> Unit>()
                val delayed = mutableListOf<Pair<Long, () -> Unit>>()
                val events = mutableListOf<String>()
                val registry = TtsProgressRegistry(
                    dispatch = { queued += it },
                    dispatchDelayed = { delay, block -> delayed += delay to block },
                    errorFallbackMs = 150L,
                    watchdogMs = 250L,
                    onWatchdogTimeout = { true }
                )

                registry.register("queued-real", { events += "START" }, { events += "DONE" })
                registry.scheduleWatchdog("queued-real")
                registry.onDone("queued-real")
                check(queued.size == 1) {
                    "watchdog must start with exactly one queued real completion callback: ${queued.size}"
                }
                delayed.removeAt(0).second()
                check(events.isEmpty()) { "watchdog must queue callbacks without running them inline: $events" }
                while (queued.isNotEmpty()) queued.removeAt(0)()
                check(events == listOf("START")) {
                    "queued real completion must be stale after confirmed watchdog: $events"
                }
                check(delayed.single().first == 150L)
                delayed.removeAt(0).second()
                check(events == listOf("START", "DONE")) {
                    "synthetic watchdog completion must be exactly once: $events"
                }
                queued.toList().forEach { it() }
                check(events == listOf("START", "DONE"))
            }

            fun assertFailedStopRetainsGuardUntilRealCompletion() {
                val delayed = mutableListOf<Pair<Long, () -> Unit>>()
                val events = mutableListOf<String>()
                var guardReleased = false
                val registry = TtsProgressRegistry(
                    dispatch = { it() },
                    dispatchDelayed = { delay, block -> delayed += delay to block },
                    errorFallbackMs = 150L,
                    watchdogMs = 250L,
                    onWatchdogTimeout = { false }
                )

                registry.register("stop-failed", { events += "START" }, {
                    guardReleased = true
                    events += "DONE"
                })
                registry.scheduleWatchdog("stop-failed")
                delayed.removeAt(0).second()
                check(!guardReleased && events.isEmpty()) {
                    "failed stop must not synthesize completion or release the guard: $events"
                }
                check(delayed.isEmpty()) { "failed stop must not schedule synthetic completion" }
                registry.onError("stop-failed")
                check(!guardReleased && events == listOf("START")) {
                    "real error must still wait for the registry completion fallback: $events"
                }
                check(delayed.single().first == 150L)
                delayed.removeAt(0).second()
                check(guardReleased && events == listOf("START", "DONE")) {
                    "real error completion must release the retained guard: $events"
                }
            }

            fun assertGenericExitNounsContinue() {
                val detector = ConversationExitDetector()
                listOf(
                    "退出账户", "退出当前账户", "退出账号", "退出密码",
                    "退出登录", "退出页面", "退出界面", "关闭界面"
                ).forEach { text ->
                    check(detector.classify(text) == ExitDecision.CONTINUE) {
                        "generic noun must not end the assistant session: $text"
                    }
                }
                check(detector.classify("退出") == ExitDecision.EXIT)
                check(detector.classify("退出微信") == ExitDecision.CONTINUE)
            }

            fun main() {
                assertQueuedRealCompletionIsStaleAfterConfirmedWatchdog()
                assertFailedStopRetainsGuardUntilRealCompletion()
                assertGenericExitNounsContinue()
                println("PASS: v0.6.6 TTS timeout and conversation-exit safety")
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
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    except (FileNotFoundError, subprocess.CalledProcessError):
        print(
            f"FAIL: v0.6.6 Kotlin harness unavailable or unusable: {compiler}",
            file=sys.stderr,
        )
        raise SystemExit(1)
    else:
        jar = td / "tts-exit-safety.jar"
        subprocess.run(
            [
                *compiler_command,
                str(registry_path),
                str(detector_path),
                str(harness),
                "-include-runtime",
                "-d",
                str(jar),
            ],
            cwd=root,
            check=True,
        )
        subprocess.run(["java", "-jar", str(jar)], cwd=root, check=True)
