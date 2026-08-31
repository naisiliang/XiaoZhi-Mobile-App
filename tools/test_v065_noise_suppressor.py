from pathlib import Path
import os
import re
import subprocess
import sys
import tempfile
import textwrap


ROOT = Path(__file__).resolve().parents[1]
MANAGER = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/AudioEnhancementManager.kt"
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


assert MANAGER.exists(), f"missing noise suppressor manager: {MANAGER.relative_to(ROOT)}"
manager = MANAGER.read_text(encoding="utf-8")
for token in [
    "class AudioEnhancementManager",
    "fun attach(record: AudioRecord): AutoCloseable",
    "import android.util.Log",
    "NoiseSuppressor.isAvailable()",
    "NoiseSuppressor.create(record.audioSessionId)",
    "setEnabled(true)",
    "AudioEffect.SUCCESS",
    "release()",
    "catch",
    "AtomicBoolean",
    '"unavailable"',
    '"availability check threw"',
    '"create returned null"',
    '"create threw"',
    '"enable threw"',
    '"enable failed"',
    "Log.w",
]:
    assert token in manager, f"noise suppressor manager contract missing: {token}"

wake = WAKE_SERVICE.read_text(encoding="utf-8")
capture = function_body(wake, "captureCommandAudio")
kws_capture = function_body(wake, "startKwsCapture")

attach = capture.find("audioEnhancementManager.attach(record)")
initialized = capture.find("record.state != AudioRecord.STATE_INITIALIZED")
record_start = capture.find("record.startRecording()")
assert min(attach, initialized, record_start) >= 0, "command enhancement lifecycle is incomplete"
assert initialized < attach < record_start, "attach only after command record initialization and before startRecording"
assert "enhancement.close()" in capture, "command enhancement must close with capture lifecycle"
assert re.search(r"finally\s*\{[^{}]*enhancement\.close\(\)", capture, re.S), (
    "command enhancement close must be protected by finally"
)
assert "record.release()" not in capture, "capture must keep AudioRecord release owned by outer safety"
assert "AudioEnhancementManager" not in kws_capture, "KWS capture must not attach command enhancement"

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
        f"FAIL: noise suppressor Kotlin harness unavailable or unusable: {compiler}",
        file=sys.stderr,
    )
    raise SystemExit(1)

with tempfile.TemporaryDirectory() as temp_dir:
    temp = Path(temp_dir)
    media_dir = temp / "android/media"
    effect_dir = temp / "android/media/audiofx"
    media_dir.mkdir(parents=True)
    effect_dir.mkdir(parents=True)
    util_dir = temp / "android/util"
    util_dir.mkdir(parents=True)
    (media_dir / "AudioRecord.kt").write_text(
        """
        package android.media

        class AudioRecord(val audioSessionId: Int)
        """,
        encoding="utf-8",
    )
    (effect_dir / "NoiseSuppressor.kt").write_text(
        """
        package android.media.audiofx

        import android.media.AudioRecord
        import android.media.audiofx.AudioEffect.Companion.ERROR
        import android.media.audiofx.AudioEffect.Companion.SUCCESS

        open class AudioEffect {
            companion object {
                const val SUCCESS: Int = 0
                const val ERROR: Int = -1
            }
        }

        class NoiseSuppressor private constructor() : AudioEffect() {
            var enabled: Boolean = false

            var releaseCalls = 0
                private set

            fun setEnabled(value: Boolean): Int {
                if (throwOnEnable) throw IllegalStateException("enable")
                enabled = value
                return enableStatus
            }

            fun release() {
                releaseCalls += 1
            }

            companion object {
                var available = true
                var throwOnAvailable = false
                var returnNull = false
                var throwOnCreate = false
                var throwOnEnable = false
                var enableStatus = SUCCESS
                var createCalls = 0
                var lastCreated: NoiseSuppressor? = null

                fun reset() {
                    available = true
                    throwOnAvailable = false
                    returnNull = false
                    throwOnCreate = false
                    throwOnEnable = false
                    enableStatus = SUCCESS
                    createCalls = 0
                    lastCreated = null
                }

                fun isAvailable(): Boolean {
                    if (throwOnAvailable) throw IllegalStateException("available")
                    return available
                }

                fun create(recordingSession: Int): NoiseSuppressor? {
                    check(recordingSession > 0)
                    createCalls += 1
                    if (throwOnCreate) throw IllegalStateException("create")
                    if (returnNull) return null
                    return NoiseSuppressor().also { lastCreated = it }
                }
            }
        }
        """,
        encoding="utf-8",
    )
    (util_dir / "Log.kt").write_text(
        """
        package android.util

        object Log {
            var throwOnWarning = false
            val warningMessages = mutableListOf<String>()

            fun reset() {
                throwOnWarning = false
                warningMessages.clear()
            }

            @JvmStatic
            fun w(tag: String, message: String): Int {
                if (throwOnWarning) throw IllegalStateException("log")
                warningMessages += "$tag:$message"
                return 0
            }
        }
        """,
        encoding="utf-8",
    )
    harness = temp / "NoiseSuppressorHarness.kt"
    harness.write_text(
        textwrap.dedent(
            """
            import android.media.AudioRecord
            import android.media.audiofx.NoiseSuppressor
            import android.util.Log
            import com.lchuang.xiaozhimobile.AudioEnhancementManager

            private fun attach(): AutoCloseable =
                AudioEnhancementManager().attach(AudioRecord(7))

            private fun checkFallback(reason: String) {
                check(Log.warningMessages.any { it.contains(reason) }) {
                    "missing fallback diagnostic: $reason; messages=${Log.warningMessages}"
                }
            }

            fun main() {
                Log.reset()
                NoiseSuppressor.reset()
                NoiseSuppressor.available = false
                attach().close()
                check(NoiseSuppressor.createCalls == 0)
                checkFallback("unavailable")

                Log.reset()
                NoiseSuppressor.reset()
                NoiseSuppressor.throwOnAvailable = true
                attach().close()
                check(NoiseSuppressor.createCalls == 0)
                checkFallback("availability check threw")

                Log.reset()
                NoiseSuppressor.reset()
                NoiseSuppressor.returnNull = true
                attach().close()
                check(NoiseSuppressor.createCalls == 1)
                checkFallback("create returned null")

                Log.reset()
                NoiseSuppressor.reset()
                NoiseSuppressor.throwOnCreate = true
                attach().close()
                check(NoiseSuppressor.createCalls == 1)
                checkFallback("create threw")

                Log.reset()
                NoiseSuppressor.reset()
                NoiseSuppressor.throwOnEnable = true
                attach().close()
                check(NoiseSuppressor.lastCreated?.releaseCalls == 1) {
                    "failed enable must release the created effect"
                }
                checkFallback("enable threw")

                Log.reset()
                NoiseSuppressor.reset()
                NoiseSuppressor.enableStatus = android.media.audiofx.AudioEffect.ERROR
                attach().close()
                check(NoiseSuppressor.lastCreated?.releaseCalls == 1) {
                    "non-success enable status must release the created effect"
                }
                checkFallback("enable failed")

                Log.reset()
                NoiseSuppressor.reset()
                val handle = attach()
                val effect = checkNotNull(NoiseSuppressor.lastCreated)
                check(effect.enabled)
                handle.close()
                handle.close()
                check(effect.releaseCalls == 1) { "close must release exactly once" }

                Log.reset()
                NoiseSuppressor.reset()
                Log.throwOnWarning = true
                NoiseSuppressor.available = false
                attach().close()
                println("PASS: noise suppressor fallback and idempotent release")
            }
            """
        ),
        encoding="utf-8",
    )
    jar = temp / "noise-suppressor.jar"
    subprocess.run(
        [
            *compiler_command,
            str(media_dir / "AudioRecord.kt"),
            str(effect_dir / "NoiseSuppressor.kt"),
            str(util_dir / "Log.kt"),
            str(MANAGER),
            str(harness),
            "-include-runtime",
            "-d",
            str(jar),
        ],
        check=True,
    )
    subprocess.run(["java", "-jar", str(jar)], check=True)

print("PASS: command-only NoiseSuppressor lifecycle and safe fallback")
