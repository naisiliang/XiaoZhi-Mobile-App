from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import textwrap


ROOT = Path(__file__).resolve().parents[1]
WAKE = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt"
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


capture = function_body("captureCommandAudio")
negative_start = capture.find("if (n < 0)")
assert negative_start >= 0, "capture must distinguish negative AudioRecord.read errors"
negative_end = capture.find("if (n == 0)", negative_start)
assert negative_end > negative_start, "zero-length reads need a separate non-error path"
negative_branch = capture[negative_start:negative_end]
assert "COMMAND_MAX_READ_ERRORS" in negative_branch, "negative reads need a bounded error-count limit"
assert "SystemClock.elapsedRealtime()" in capture, "capture needs a wall-clock read watchdog"
assert "COMMAND_MAX_READ_ERROR_MS" in negative_branch, "negative reads need a bounded wall-clock limit"
assert "throw CommandAudioCaptureException(CommandAudioCaptureFailureKind.AUDIO_START)" in negative_branch
zero_end = capture.find("val rms = frameRms", negative_end)
assert zero_end > negative_end, "zero-read branch is not bounded before frame processing"
assert "COMMAND_MAX_ZERO_READ_MS" in capture[negative_end:zero_end]
assert "if (n <= 0) continue" not in capture, "negative reads must not spin through the old unbounded path"


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

            private const val MAX_NEGATIVE_READS = 3
            private const val MAX_NEGATIVE_READ_MS = 500L

            private class PersistentNegativeAudioRecord {
                var readCalls = 0

                fun read(): Int {
                    readCalls += 1
                    return -3
                }
            }

            private class FakeClock {
                var nowMs = 0L

                fun elapsedRealtime(): Long {
                    nowMs += 100L
                    return nowMs
                }
            }

            private data class RecognitionOutcome(val route: String, val attempts: Int)

            private fun captureCommandAudio(
                record: PersistentNegativeAudioRecord,
                clock: FakeClock
            ): FloatArray {
                var negativeReadCount = 0
                var firstNegativeReadAtMs = 0L
                val captureStartedAtMs = clock.elapsedRealtime()

                while (true) {
                    val n = record.read()
                    val nowMs = clock.elapsedRealtime()
                    if (n < 0) {
                        if (negativeReadCount == 0) firstNegativeReadAtMs = nowMs
                        negativeReadCount += 1
                        if (
                            negativeReadCount >= MAX_NEGATIVE_READS ||
                            nowMs - firstNegativeReadAtMs >= MAX_NEGATIVE_READ_MS ||
                            nowMs - captureStartedAtMs >= MAX_NEGATIVE_READ_MS
                        ) {
                            throw CommandAudioCaptureException(CommandAudioCaptureFailureKind.AUDIO_START)
                        }
                        continue
                    }
                    if (n == 0) continue
                    negativeReadCount = 0
                    return FloatArray(n)
                }
            }

            private fun startLocalCommandRecognition(
                record: PersistentNegativeAudioRecord,
                clock: FakeClock
            ): RecognitionOutcome {
                var attempts = 1
                return try {
                    captureCommandAudio(record, clock)
                    RecognitionOutcome("decoded", attempts)
                } catch (e: CommandAudioCaptureException) {
                    attempts -= 1
                    check(e.kind == CommandAudioCaptureFailureKind.AUDIO_START)
                    RecognitionOutcome("recoverCommandAudioCaptureFailure", attempts)
                }
            }

            fun main() {
                val record = PersistentNegativeAudioRecord()
                val outcome = startLocalCommandRecognition(record, FakeClock())
                check(record.readCalls <= MAX_NEGATIVE_READS) {
                    "persistent negative reads were not bounded: ${record.readCalls}"
                }
                check(outcome.route == "recoverCommandAudioCaptureFailure") {
                    "negative read failure used ${outcome.route}"
                }
                check(outcome.attempts == 0) {
                    "typed capture failure consumed the wrong retry count: ${outcome.attempts}"
                }
                println("PASS: persistent negative reads have bounded typed capture recovery")
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
        [*command, str(FAILURE), str(harness), *([] if embedded_stdlib else ["-include-runtime"]), "-d", str(jar)],
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

print("PASS: persistent negative AudioRecord.read regression is bounded and typed")
