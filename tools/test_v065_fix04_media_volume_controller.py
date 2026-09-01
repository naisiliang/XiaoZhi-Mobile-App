from pathlib import Path
import re
import subprocess
import tempfile
import textwrap


ROOT = Path(__file__).resolve().parents[1]
CONTROLLER = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/MediaVolumeController.kt"
PHONE = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/PhoneController.kt"


def compile_and_run_harness() -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
        temp = Path(temp_dir)
        audio_manager = temp / "AudioManager.kt"
        harness = temp / "MediaVolumeControllerHarness.kt"
        jar = temp / "media-volume-controller.jar"

        audio_manager.write_text(
            textwrap.dedent(
                """
                package android.media

                open class AudioManager(
                    private val maxVolume: Int = 10,
                    initialVolume: Int = 5,
                    val isVolumeFixed: Boolean = false,
                    private val noOpAdjustDirections: Set<Int> = emptySet(),
                    private val throwOnAdjustDirections: Set<Int> = emptySet(),
                ) {
                    companion object {
                        const val STREAM_MUSIC = 3
                        const val FLAG_SHOW_UI = 1
                        const val ADJUST_RAISE = 1
                        const val ADJUST_LOWER = -1
                    }

                    private var currentVolume = initialVolume.coerceIn(0, maxVolume)

                    open fun getStreamMaxVolume(streamType: Int): Int {
                        check(streamType == STREAM_MUSIC)
                        return maxVolume
                    }

                    open fun getStreamVolume(streamType: Int): Int {
                        check(streamType == STREAM_MUSIC)
                        return currentVolume
                    }

                    open fun setStreamVolume(streamType: Int, index: Int, flags: Int) {
                        check(streamType == STREAM_MUSIC)
                        check(flags == FLAG_SHOW_UI)
                        currentVolume = index.coerceIn(0, maxVolume)
                    }

                    open fun adjustStreamVolume(streamType: Int, direction: Int, flags: Int) {
                        check(streamType == STREAM_MUSIC)
                        check(flags == FLAG_SHOW_UI)
                        if (direction in throwOnAdjustDirections) {
                            throw IllegalStateException("forced adjust failure")
                        }
                        if (direction in noOpAdjustDirections) {
                            return
                        }
                        currentVolume = when (direction) {
                            ADJUST_RAISE -> (currentVolume + 1).coerceAtMost(maxVolume)
                            ADJUST_LOWER -> (currentVolume - 1).coerceAtLeast(0)
                            else -> currentVolume
                        }
                    }
                }
                """
            ),
            encoding="utf-8",
        )

        harness.write_text(
            textwrap.dedent(
                """
                import android.media.AudioManager
                import com.lchuang.xiaozhimobile.MediaVolumeController

                private fun assertEquals(expected: Any?, actual: Any?, label: String) {
                    check(expected == actual) { "$label: expected=$expected actual=$actual" }
                }

                private fun assertTrue(condition: Boolean, label: String) {
                    check(condition) { label }
                }

                fun main() {
                    val baselineController = MediaVolumeController(
                        AudioManager(maxVolume = 10, initialVolume = 4)
                    )
                    val snapshot = baselineController.snapshot()
                    assertEquals(MediaVolumeController.RESULT_SNAPSHOT, snapshot.resultCode, "snapshot result")
                    assertEquals(4, snapshot.beforeStep, "snapshot before")
                    assertEquals(4, snapshot.targetStep, "snapshot target")
                    assertEquals(4, snapshot.afterStep, "snapshot after")
                    assertEquals(40, snapshot.actualPercent, "snapshot percent")

                    val setController = MediaVolumeController(
                        AudioManager(maxVolume = 10, initialVolume = 2)
                    )
                    val setSnapshot = setController.setPercent(63)
                    assertEquals(MediaVolumeController.RESULT_SET_OK, setSnapshot.resultCode, "set result")
                    assertEquals(63, setSnapshot.requestedPercent, "set requested")
                    assertEquals(6, setSnapshot.targetStep, "set target step")
                    assertEquals(6, setSnapshot.afterStep, "set after step")
                    assertTrue(kotlin.math.abs(setSnapshot.actualPercent - 63) <= 10, "set tolerance")

                    val raiseController = MediaVolumeController(
                        AudioManager(maxVolume = 10, initialVolume = 4)
                    )
                    val raiseSnapshot = raiseController.adjust(AudioManager.ADJUST_RAISE)
                    assertEquals(MediaVolumeController.RESULT_ADJUST_OK, raiseSnapshot.resultCode, "raise result")
                    assertEquals(4, raiseSnapshot.beforeStep, "raise before")
                    assertEquals(5, raiseSnapshot.targetStep, "raise target")
                    assertEquals(5, raiseSnapshot.afterStep, "raise after")

                    val noOpRaiseController = MediaVolumeController(
                        AudioManager(
                            maxVolume = 10,
                            initialVolume = 4,
                            noOpAdjustDirections = setOf(AudioManager.ADJUST_RAISE),
                        )
                    )
                    val noOpRaiseSnapshot = noOpRaiseController.adjust(AudioManager.ADJUST_RAISE)
                    assertEquals(4, noOpRaiseSnapshot.beforeStep, "no-op raise before")
                    assertEquals(5, noOpRaiseSnapshot.targetStep, "no-op raise target")
                    assertEquals(4, noOpRaiseSnapshot.afterStep, "no-op raise after")
                    assertTrue(
                        noOpRaiseSnapshot.resultCode != MediaVolumeController.RESULT_ADJUST_OK,
                        "no-op raise must not report adjust ok"
                    )

                    val noOpLowerController = MediaVolumeController(
                        AudioManager(
                            maxVolume = 10,
                            initialVolume = 4,
                            noOpAdjustDirections = setOf(AudioManager.ADJUST_LOWER),
                        )
                    )
                    val noOpLowerSnapshot = noOpLowerController.adjust(AudioManager.ADJUST_LOWER)
                    assertEquals(4, noOpLowerSnapshot.beforeStep, "no-op lower before")
                    assertEquals(3, noOpLowerSnapshot.targetStep, "no-op lower target")
                    assertEquals(4, noOpLowerSnapshot.afterStep, "no-op lower after")
                    assertTrue(
                        noOpLowerSnapshot.resultCode != MediaVolumeController.RESULT_ADJUST_OK,
                        "no-op lower must not report adjust ok"
                    )

                    val errorController = MediaVolumeController(
                        AudioManager(
                            maxVolume = 10,
                            initialVolume = 4,
                            throwOnAdjustDirections = setOf(AudioManager.ADJUST_RAISE),
                        )
                    )
                    val errorSnapshot = errorController.adjust(AudioManager.ADJUST_RAISE)
                    assertEquals(MediaVolumeController.RESULT_ADJUST_ERROR, errorSnapshot.resultCode, "error result")
                    assertEquals(4, errorSnapshot.afterStep, "error after")

                    println("PASS: FIX04 media volume controller behavior")
                }
                """
            ),
            encoding="utf-8",
        )

        subprocess.run(
            ["kotlinc", str(audio_manager), str(CONTROLLER), str(harness), "-include-runtime", "-d", str(jar)],
            cwd=ROOT,
            check=True,
        )
        subprocess.run(["java", "-jar", str(jar)], cwd=ROOT, check=True)


def assert_phone_controller_mapping() -> None:
    phone_source = PHONE.read_text(encoding="utf-8")
    up = re.search(r"fun volumeUpVerified\(\): MediaVolumeResult \{(.*?)\n    \}", phone_source, re.S)
    down = re.search(r"fun volumeDownVerified\(\): MediaVolumeResult \{(.*?)\n    \}", phone_source, re.S)
    set_percent = re.search(r"fun setMediaVolumePercent\(percent: Int\): MediaVolumeResult \{(.*?)\n    \}", phone_source, re.S)
    assert up and down and set_percent, "PhoneController media volume methods missing"
    assert "snapshot.resultCode == MediaVolumeController.RESULT_SET_OK" in set_percent.group(1)
    assert "snapshot.resultCode == MediaVolumeController.RESULT_ADJUST_OK" in up.group(1)
    assert "snapshot.resultCode == MediaVolumeController.RESULT_ADJUST_OK" in down.group(1)
    assert "MediaVolumeResult(null, snapshot.actualPercent, true)" not in up.group(1)
    assert "MediaVolumeResult(null, snapshot.actualPercent, true)" not in down.group(1)


compile_and_run_harness()
assert_phone_controller_mapping()
print("PASS: FIX04 Task 1 media volume controller regression coverage")
