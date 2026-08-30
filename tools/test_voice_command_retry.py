from pathlib import Path
import os
import subprocess
import sys
import tempfile
import textwrap


root = Path(__file__).resolve().parents[1]
source = root / "app/src/main/java/com/lchuang/xiaozhimobile/DeviceAction.kt"
wake = (root / "app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt").read_text(
    encoding="utf-8"
)
policy = source.read_text(encoding="utf-8")

# Keep deterministic source assertions before compiler discovery.  A missing
# compiler must still be nonzero, but it must not hide a broken retry contract.
assert "const val MAX_COMMAND_RECOGNITION_ATTEMPTS = 2" in policy
assert "recognitionAttempts >= MAX_COMMAND_RECOGNITION_ATTEMPTS" in policy
assert 'spokenReply = "刚才没有听清，请再说一次。"' in policy
assert "terminal = true" in policy
assert 'private val knownOneCharacterCommands = setOf("停")' in wake

recovery_start = wake.index("private fun recoverRecognitionFailure")
recovery_end = wake.index("private fun processUtterance", recovery_start)
recovery = wake[recovery_start:recovery_end]
assert "requestConversationExit(decision.spokenReply.orEmpty())" in recovery
assert "if (decision.resetAttempts) commandRecognitionAttempts = 0" in recovery
assert recovery.index("if (decision.terminal)") < recovery.index(
    "if (decision.resetAttempts) commandRecognitionAttempts = 0"
), "terminal exhaustion must leave the session before attempts can begin a new cycle"

compiler = os.environ.get("KOTLINC", "kotlinc")
compiler_command = ["cmd", "/c", compiler] if compiler.lower().endswith((".bat", ".cmd")) else [compiler]
try:
    subprocess.run([*compiler_command, "-version"], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
except (FileNotFoundError, subprocess.CalledProcessError):
    print(f"FAIL: retry Kotlin harness unavailable or unusable: {compiler}", file=sys.stderr)
    raise SystemExit(1)

with tempfile.TemporaryDirectory() as temp_dir:
    temp = Path(temp_dir)
    stub = temp / "MapPreferenceStub.kt"
    stub.write_text("package com.lchuang.xiaozhimobile\nenum class MapAppPreference { AUTO }\n", encoding="utf-8")
    harness = temp / "RetryHarness.kt"
    harness.write_text(textwrap.dedent("""
        import com.lchuang.xiaozhimobile.*

        fun main() {
            check(MAX_COMMAND_RECOGNITION_ATTEMPTS == 2)
            val first = CommandRecoveryPolicy.forFailure(CommandFailureKind.ASR_EMPTY, 1)
            check(!first.terminal && !first.resetAttempts)
            val exhausted = CommandRecoveryPolicy.forFailure(CommandFailureKind.ASR_EMPTY, 2)
            check(exhausted.terminal && exhausted.resetAttempts)
            val noSpeech = CommandRecoveryPolicy.forFailure(CommandFailureKind.NO_SPEECH, 2)
            check(!noSpeech.terminal && noSpeech.resetAttempts && noSpeech.spokenReply == null)
            println("PASS: two-attempt retry terminal and silent no-speech policy")
        }
    """), encoding="utf-8")
    jar = temp / "retry.jar"
    subprocess.run([*compiler_command, str(source), str(stub), str(harness), "-include-runtime", "-d", str(jar)], check=True)
    subprocess.run(["java", "-jar", str(jar)], check=True)
