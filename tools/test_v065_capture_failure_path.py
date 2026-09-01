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
start_recording = capture.index("record.startRecording()")
recording_state = capture.index("record.recordingState", start_recording)
guard_start = capture.rfind("\n", 0, recording_state) + 1
guard_opening = capture.index("{", recording_state)
depth = 0
guard_end = -1
for index in range(guard_opening, len(capture)):
    if capture[index] == "{":
        depth += 1
    elif capture[index] == "}":
        depth -= 1
        if depth == 0:
            guard_end = index + 1
            break
assert guard_end >= 0, "recording-state validation block is unbalanced"
guard = capture[guard_start:guard_end].strip()
assert "record.recordingState" in guard, "regression must execute the real recording-state branch"
guard = guard.replace("AudioRecord.RECORDSTATE_RECORDING", "FakeAudioRecord.RECORDSTATE_RECORDING")


def compiler_command() -> list[str]:
    compiler = os.environ.get("KOTLINC", "kotlinc")
    if shutil.which(compiler) or compiler.lower().endswith((".bat", ".cmd")):
        command = ["cmd", "/c", compiler] if compiler.lower().endswith((".bat", ".cmd")) else [compiler]
    else:
        embedded = next(
            Path.home().glob(".gradle/wrapper/dists/**/lib/kotlin-compiler-embeddable-*.jar"),
            None,
        )
        if embedded is None:
            raise RuntimeError(f"Kotlin harness unavailable: {compiler}")
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
    subprocess.run([*command, "-version"], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return command


with tempfile.TemporaryDirectory() as temp_dir:
    temp = Path(temp_dir)
    harness = temp / "CaptureFailurePathHarness.kt"
    harness.write_text(
        textwrap.dedent(
            f"""
            import com.lchuang.xiaozhimobile.CommandAudioCaptureException
            import com.lchuang.xiaozhimobile.CommandAudioCaptureFailureKind

            private class FakeAudioRecord(var recordingState: Int) {{
                var startCalls = 0

                fun startRecording() {{
                    startCalls += 1
                }}

                companion object {{
                    const val RECORDSTATE_RECORDING = 3
                    const val RECORDSTATE_STOPPED = 1
                }}
            }}

            private data class RecognitionOutcome(val route: String, val attempts: Int)

            private fun captureCommandAudioStart(record: FakeAudioRecord) {{
                record.startRecording()
                {guard}
            }}

            private fun startLocalCommandRecognition(record: FakeAudioRecord): RecognitionOutcome {{
                var attempts = 1
                return try {{
                    captureCommandAudioStart(record)
                    RecognitionOutcome("decoded", attempts)
                }} catch (e: CommandAudioCaptureException) {{
                    attempts -= 1
                    check(e.kind == CommandAudioCaptureFailureKind.AUDIO_START)
                    RecognitionOutcome("recoverCommandAudioCaptureFailure", attempts)
                }} catch (_: Throwable) {{
                    RecognitionOutcome("retryLocalCommandRecognition:ASR_EMPTY", attempts)
                }}
            }}

            fun main() {{
                val record = FakeAudioRecord(FakeAudioRecord.RECORDSTATE_STOPPED)
                val outcome = startLocalCommandRecognition(record)
                check(record.startCalls == 1) {{ "non-recording path did not call startRecording: $record" }}
                check(outcome.route == "recoverCommandAudioCaptureFailure") {{
                    "non-recording AudioRecord routed to ${{outcome.route}}"
                }}
                check(outcome.attempts == 0) {{ "capture failure consumed a retry: ${{outcome.attempts}}" }}
                println("PASS: non-recording AudioRecord reaches typed AUDIO_START capture recovery")
            }}
            """
        ),
        encoding="utf-8",
    )
    jar = temp / "capture-failure-path.jar"
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
            [shutil.which("java") or "java", "-cp", os.pathsep.join([str(jar), str(embedded_stdlib)]), "CaptureFailurePathHarnessKt"],
            cwd=ROOT,
            check=True,
        )
    else:
        subprocess.run(["java", "-jar", str(jar)], cwd=ROOT, check=True)

print("PASS: actual non-recording capture path preserves typed recovery")
