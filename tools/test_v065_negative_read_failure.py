from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import textwrap


ROOT = Path(__file__).resolve().parents[1]
WAKE = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt"
WATCHDOG = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/CommandAudioReadWatchdog.kt"
FAILURE = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/CommandAudioCaptureFailure.kt"
wake = WAKE.read_text(encoding="utf-8")


def function_body(name: str) -> str:
    start = wake.index(f"private fun {name}")
    opening = wake.index("{", start)
    depth = 0
    for index in range(opening, len(wake)):
        if wake[index] == "{":
            depth += 1
        elif wake[index] == "}":
            depth -= 1
            if depth == 0:
                return wake[opening + 1 : index]
    raise AssertionError(f"unbalanced body: {name}")


assert WATCHDOG.exists(), "production read watchdog seam is missing"
watchdog = WATCHDOG.read_text(encoding="utf-8")
capture = function_body("captureCommandAudio")
assert "CommandAudioReadWatchdog(" in capture, "capture must construct the production read watchdog"
assert "readWatchdog.reset(SystemClock.elapsedRealtime())" in capture
assert "readWatchdog.onRead(n, nowMs)" in capture, "capture must execute the production read decision"
assert "negativeReadCount" not in capture, "capture must not duplicate watchdog state"
assert "firstNegativeReadAtMs" not in capture, "capture must not duplicate watchdog state"
assert "lastReadProgressAtMs" not in capture, "capture must not duplicate watchdog state"
assert "CommandAudioReadDecision.AUDIO_START_FAILURE" in watchdog
assert "CommandAudioReadDecision.STOP" in watchdog
assert "CommandAudioReadDecision.PROCESS" in watchdog
assert "negativeReadCount = 0" in watchdog, "positive reads must reset negative-read state"
assert "enhancement.close()" in capture, "capture must retain enhancement cleanup"
assert "throw CommandAudioCaptureException(CommandAudioCaptureFailureKind.AUDIO_START)" in capture


def compiler_command() -> list[str]:
    compiler = os.environ.get("KOTLINC", "kotlinc")
    if shutil.which(compiler) or compiler.lower().endswith((".bat", ".cmd")):
        return ["cmd", "/c", compiler] if compiler.lower().endswith((".bat", ".cmd")) else [compiler]
    embedded = next(
        Path.home().glob(".gradle/wrapper/dists/**/lib/kotlin-compiler-embeddable-*.jar"),
        None,
    )
    if embedded is None:
        raise RuntimeError(f"Kotlin harness unavailable: {compiler}")
    return [
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


with tempfile.TemporaryDirectory() as temp_dir:
    temp = Path(temp_dir)
    harness = temp / "NegativeReadFailureHarness.kt"
    harness.write_text(
        textwrap.dedent(
            """
            import com.lchuang.xiaozhimobile.CommandAudioCaptureException
            import com.lchuang.xiaozhimobile.CommandAudioCaptureFailureKind
            import com.lchuang.xiaozhimobile.CommandAudioReadDecision
            import com.lchuang.xiaozhimobile.CommandAudioReadWatchdog

            private class PersistentNegativeAudioRecord {
                var readCalls = 0

                fun read(): Int {
                    readCalls += 1
                    return -3
                }
            }

            private class PersistentZeroAudioRecord {
                var readCalls = 0

                fun read(): Int {
                    readCalls += 1
                    return 0
                }
            }

            private fun audioStartFailure(decision: CommandAudioReadDecision): Nothing {
                check(decision == CommandAudioReadDecision.AUDIO_START_FAILURE)
                throw CommandAudioCaptureException(CommandAudioCaptureFailureKind.AUDIO_START)
            }

            private fun assertPersistentNegativeReadsBecomeTypedFailure() {
                val record = PersistentNegativeAudioRecord()
                val watchdog = CommandAudioReadWatchdog(
                    maxNegativeReads = 3,
                    maxNegativeDurationMs = 500L,
                    maxZeroReadDurationMs = 200L,
                )
                watchdog.reset(0L)
                var typedFailure: CommandAudioCaptureException? = null
                var nowMs = 0L
                while (typedFailure == null) {
                    nowMs += 100L
                    try {
                        if (watchdog.onRead(record.read(), nowMs) == CommandAudioReadDecision.AUDIO_START_FAILURE) {
                            audioStartFailure(CommandAudioReadDecision.AUDIO_START_FAILURE)
                        }
                    } catch (e: CommandAudioCaptureException) {
                        typedFailure = e
                    }
                }
                check(record.readCalls == 3) { "persistent negative reads were not count-bounded: ${record.readCalls}" }
                check(typedFailure?.kind == CommandAudioCaptureFailureKind.AUDIO_START) {
                    "persistent negative reads did not become typed AUDIO_START: $typedFailure"
                }
            }

            private fun assertPersistentZeroReadsStopAtDeadline() {
                val record = PersistentZeroAudioRecord()
                val watchdog = CommandAudioReadWatchdog(
                    maxNegativeReads = 3,
                    maxNegativeDurationMs = 500L,
                    maxZeroReadDurationMs = 200L,
                )
                watchdog.reset(0L)
                check(watchdog.onRead(record.read(), 100L) == CommandAudioReadDecision.CONTINUE)
                check(watchdog.onRead(record.read(), 200L) == CommandAudioReadDecision.STOP)
                check(record.readCalls == 2) { "zero reads were not bounded: ${record.readCalls}" }
            }

            private fun assertPositiveReadResetsNegativeState() {
                val watchdog = CommandAudioReadWatchdog(
                    maxNegativeReads = 3,
                    maxNegativeDurationMs = 500L,
                    maxZeroReadDurationMs = 200L,
                )
                watchdog.reset(0L)
                check(watchdog.onRead(-3, 100L) == CommandAudioReadDecision.CONTINUE)
                check(watchdog.onRead(160, 150L) == CommandAudioReadDecision.PROCESS)
                check(watchdog.onRead(-3, 200L) == CommandAudioReadDecision.CONTINUE)
                check(watchdog.onRead(-3, 250L) == CommandAudioReadDecision.CONTINUE) {
                    "positive read did not reset the negative-read count"
                }
                check(watchdog.onRead(-3, 300L) == CommandAudioReadDecision.AUDIO_START_FAILURE)
            }

            fun main() {
                assertPersistentNegativeReadsBecomeTypedFailure()
                assertPersistentZeroReadsStopAtDeadline()
                assertPositiveReadResetsNegativeState()
                println("PASS: production AudioRecord read watchdog bounds negative/zero reads and resets on progress")
            }
            """
        ),
        encoding="utf-8",
    )
    jar = temp / "negative-read-failure.jar"
    command = compiler_command()
    embedded_stdlib = next(
        (Path(item) for item in command if item.endswith("kotlin-stdlib-1.7.10.jar")),
        None,
    )
    subprocess.run(
        [
            *command,
            str(WATCHDOG),
            str(FAILURE),
            str(harness),
            *([] if embedded_stdlib else ["-include-runtime"]),
            "-d",
            str(jar),
        ],
        cwd=ROOT,
        check=True,
    )
    if embedded_stdlib:
        subprocess.run(
            [
                shutil.which("java") or "java",
                "-cp",
                os.pathsep.join([str(jar), str(embedded_stdlib)]),
                "NegativeReadFailureHarnessKt",
            ],
            cwd=ROOT,
            check=True,
        )
    else:
        subprocess.run(["java", "-jar", str(jar)], cwd=ROOT, check=True)

print("PASS: production AudioRecord read watchdog regression is bounded, typed, and executable")
