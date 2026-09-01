from pathlib import Path
import hashlib
import os
import re
import subprocess
import sys
import tempfile
import textwrap


ROOT = Path(__file__).resolve().parents[1]
DETECTOR = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/AdaptiveVoiceActivityDetector.kt"
WAKE_SERVICE = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt"


def function_body(source: str, name: str) -> str:
    match = re.search(rf"private fun {re.escape(name)}\b[^{{]*\{{", source)
    assert match, f"{name} missing"
    opening = match.end() - 1
    depth = 0
    for index in range(opening, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[opening + 1 : index]
    raise AssertionError(f"{name} body is not balanced")


assert DETECTOR.exists(), f"missing adaptive VAD source: {DETECTOR.relative_to(ROOT)}"
detector = DETECTOR.read_text(encoding="utf-8")
assert "package com.lchuang.xiaozhimobile" in detector
assert "import android." not in detector, "adaptive VAD must remain Android-independent"
for token in [
    "data class VadFrameDecision(",
    "val speechStarted: Boolean",
    "val speechEnded: Boolean",
    "val noiseFloor: Float",
    "val startThreshold: Float",
    "val endThreshold: Float",
    "class AdaptiveVoiceActivityDetector(",
    "private val frameMs: Int = 50",
    "private val stableSpeechFrames: Int = 2",
    "private val endSilenceMs: Int = 650",
    "initialNoiseFloor: Float = 0.0045f",
    "fun reset()",
    "fun accept(rms: Float): VadFrameDecision",
]:
    assert token in detector, f"adaptive VAD interface missing: {token}"

wake = WAKE_SERVICE.read_text(encoding="utf-8")
capture = function_body(wake, "captureCommandAudio")
assert "private const val COMMAND_FRAME_SAMPLES = 800" in wake
assert "private const val PRE_ROLL_FRAMES = 8" in wake
assert "SPEECH_RMS_THRESHOLD" not in capture
assert "SILENCE_RMS_THRESHOLD" not in capture
assert "AdaptiveVoiceActivityDetector()" in capture
assert capture.find("vad.reset()") < capture.find("while (running.get()"), (
    "VAD must reset before the command capture loop"
)
assert "vad.accept(rms)" in capture
assert ".speechStarted" in capture and ".speechEnded" in capture
assert "outputSize >= SAMPLE_RATE / 2" in capture

expected_hashes = {
    "app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseCompiler.kt": "6376a9ade23c87856aad3bdfc869f05936faa4ddd3aaae4612101cccebe895cc",
    "app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseManager.kt": "ced9c7276cd98e72d488b4f228d8bf4cfe77a08c184f06ef112425f701a5a608",
    "app/src/main/java/com/lchuang/xiaozhimobile/Pinyin4jProvider.kt": "1fead428ba6b77be1ccbbd0882e9694fb9fe1aee8ac53e2707cb3872edb57f6f",
}
for relative_path, expected_hash in expected_hashes.items():
    actual_hash = hashlib.sha256((ROOT / relative_path).read_bytes()).hexdigest()
    assert actual_hash == expected_hash, f"frozen wake file changed: {relative_path}"

kws_block = re.search(
    r"private fun initKeywordSpotter\(\)\s*\{.*?\n    \}\n\n    private fun initOfflineAsr",
    wake,
    flags=re.S,
)
assert kws_block, "initKeywordSpotter block not found"
assert hashlib.sha256(kws_block.group(0).encode("utf-8")).hexdigest() == (
    "77071fcc4a9d9c9627e8a30ddb45d0ad831ece80483152d8b71ce8b4c128abcd"
), "WakeService KWS initialization changed"

compiler = os.environ.get("KOTLINC") or "kotlinc"
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
        f"FAIL: adaptive VAD Kotlin harness unavailable or unusable: {compiler}",
        file=sys.stderr,
    )
    raise SystemExit(1)

with tempfile.TemporaryDirectory() as temp_dir:
    temp = Path(temp_dir)
    harness = temp / "AdaptiveVadHarness.kt"
    harness.write_text(
        textwrap.dedent(
            """
            import com.lchuang.xiaozhimobile.AdaptiveVoiceActivityDetector

            private fun close(actual: Float, expected: Float, tolerance: Float = 0.0003f) {
                check(kotlin.math.abs(actual - expected) <= tolerance) {
                    "expected $expected, got $actual"
                }
            }

            fun main() {
                val quiet = AdaptiveVoiceActivityDetector()
                repeat(40) {
                    val decision = quiet.accept(if (it % 2 == 0) 0.0038f else 0.0042f)
                    check(!decision.speechStarted)
                    check(!decision.speechEnded)
                }
                close(quiet.accept(0.004f).noiseFloor, 0.004f)

                val spike = AdaptiveVoiceActivityDetector()
                check(!spike.accept(0.02f).speechStarted)
                repeat(3) { check(!spike.accept(0.004f).speechStarted) }

                val speech = AdaptiveVoiceActivityDetector()
                check(!speech.accept(0.02f).speechStarted)
                check(speech.accept(0.02f).speechStarted)
                repeat(8) { speech.accept(0.03f) }
                check(speech.accept(0.03f).noiseFloor < 0.01f) {
                    "speech frames must not poison the noise floor"
                }

                val ending = AdaptiveVoiceActivityDetector()
                ending.accept(0.02f)
                check(ending.accept(0.02f).speechStarted)
                repeat(12) { check(!ending.accept(0.004f).speechEnded) }
                check(ending.accept(0.004f).speechEnded)

                val noisy = AdaptiveVoiceActivityDetector()
                var noisyDecision = noisy.accept(0.008f)
                repeat(39) { noisyDecision = noisy.accept(0.008f) }
                check(!noisyDecision.speechStarted)
                check(noisyDecision.noiseFloor > 0.007f)
                check(noisyDecision.startThreshold > 0.015f)
                check(noisyDecision.startThreshold <= 0.03f)
                check(noisyDecision.endThreshold >= 0.006f)
                check(noisyDecision.endThreshold <= noisyDecision.startThreshold * 0.82f)

                val safe = AdaptiveVoiceActivityDetector()
                safe.accept(Float.NaN)
                val safeDecision = safe.accept(-1f)
                check(safeDecision.noiseFloor.isFinite() && safeDecision.noiseFloor >= 0f)
                println("PASS: adaptive VAD deterministic behavior")
            }
            """
        ),
        encoding="utf-8",
    )
    jar = temp / "adaptive-vad.jar"
    subprocess.run(
        [*compiler_command, str(DETECTOR), str(harness), "-include-runtime", "-d", str(jar)],
        check=True,
    )
    subprocess.run(["java", "-jar", str(jar)], check=True)

print("PASS: adaptive VAD source and command-capture integration")
