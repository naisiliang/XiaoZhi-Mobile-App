from pathlib import Path
import subprocess
import tempfile
import textwrap


ROOT = Path(__file__).resolve().parents[1]
CONTROLLER = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/MediaVolumeController.kt"


def assert_source_contract(tokens: list[str]) -> None:
    content = CONTROLLER.read_text(encoding="utf-8")
    missing = [token for token in tokens if token not in content]
    assert not missing, f"missing source contract tokens: {missing}"


def compile_and_run_harness() -> None:
    with tempfile.TemporaryDirectory() as temp_dir:
        temp = Path(temp_dir)
        audio_manager = temp / "AudioManager.kt"
        harness = temp / "MediaVolumeAlgorithmHarness.kt"
        jar = temp / "media-volume-algorithm.jar"

        audio_manager.write_text(
            textwrap.dedent(
                """
                package android.media

                open class AudioManager(
                    private val maxVolume: Int = 10,
                    initialVolume: Int = 5,
                    val isVolumeFixed: Boolean = false,
                    private val staleReadsAfterSet: Int = 0,
                    private val ignoreSet: Boolean = false,
                    private val maxWritableVolume: Int? = null,
                    private val throwOnSet: Boolean = false,
                ) {
                    companion object {
                        const val STREAM_MUSIC = 3
                        const val FLAG_SHOW_UI = 1
                        const val ADJUST_RAISE = 1
                        const val ADJUST_LOWER = -1
                    }

                    private var currentVolume = initialVolume.coerceIn(0, maxVolume)
                    private var pendingVolume = currentVolume
                    private var staleReadsRemaining = 0
                    private var staleReadBudget = staleReadsAfterSet

                    val setHistory = mutableListOf<Int>()
                    val streamHistory = mutableListOf<Int>()

                    open fun getStreamMaxVolume(streamType: Int): Int {
                        check(streamType == STREAM_MUSIC)
                        return maxVolume
                    }

                    open fun getStreamVolume(streamType: Int): Int {
                        check(streamType == STREAM_MUSIC)
                        if (staleReadsRemaining > 0) {
                            staleReadsRemaining -= 1
                            return currentVolume
                        }
                        currentVolume = pendingVolume.coerceIn(0, maxVolume)
                        return currentVolume
                    }

                    open fun setStreamVolume(streamType: Int, index: Int, flags: Int) {
                        check(streamType == STREAM_MUSIC)
                        check(flags == FLAG_SHOW_UI)
                        streamHistory += streamType
                        setHistory += index
                        if (throwOnSet) {
                            throw IllegalStateException("forced set failure")
                        }
                        if (ignoreSet || isVolumeFixed) {
                            pendingVolume = currentVolume
                            staleReadsRemaining = 0
                            return
                        }
                        val effectiveCap = maxWritableVolume ?: maxVolume
                        pendingVolume = index.coerceIn(0, effectiveCap.coerceIn(0, maxVolume))
                        staleReadsRemaining = staleReadBudget
                        staleReadBudget = 0
                    }

                    open fun adjustStreamVolume(streamType: Int, direction: Int, flags: Int) {
                        check(streamType == STREAM_MUSIC)
                        check(flags == FLAG_SHOW_UI)
                        currentVolume = when (direction) {
                            ADJUST_RAISE -> (currentVolume + 1).coerceAtMost(maxVolume)
                            ADJUST_LOWER -> (currentVolume - 1).coerceAtLeast(0)
                            else -> currentVolume
                        }
                        pendingVolume = currentVolume
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
                    val delayedAudio = AudioManager(
                        maxVolume = 9,
                        initialVolume = 1,
                        staleReadsAfterSet = 1,
                    )
                    val delayedController = MediaVolumeController(delayedAudio)
                    val delayedSnapshot = delayedController.setPercent(55)
                    assertEquals(55, delayedSnapshot.requestedPercent, "delayed requested percent")
                    assertEquals(5, delayedSnapshot.targetStep, "delayed rounded target")
                    assertEquals(5, delayedSnapshot.afterStep, "delayed readback after retry")
                    assertEquals(56, delayedSnapshot.actualPercent, "delayed actual percent")
                    assertEquals(1, delayedSnapshot.retryCount, "delayed retry count")
                    assertEquals(MediaVolumeController.RESULT_SET_OK, delayedSnapshot.resultCode, "delayed success result")
                    assertTrue(delayedAudio.streamHistory.all { it == AudioManager.STREAM_MUSIC }, "must always write STREAM_MUSIC")

                    val clampedAudio = AudioManager(
                        maxVolume = 10,
                        initialVolume = 7,
                    )
                    val clampedController = MediaVolumeController(clampedAudio)
                    val clampedSnapshot = clampedController.setPercent(-5)
                    assertEquals(0, clampedSnapshot.requestedPercent, "clamped requested percent")
                    assertEquals(0, clampedSnapshot.targetStep, "clamped target step")
                    assertEquals(MediaVolumeController.RESULT_SET_OK, clampedSnapshot.resultCode, "clamped success result")

                    val limitedAudio = AudioManager(
                        maxVolume = 10,
                        initialVolume = 4,
                        ignoreSet = true,
                    )
                    val limitedController = MediaVolumeController(limitedAudio)
                    val limitedSnapshot = limitedController.setPercent(80)
                    assertEquals(1, limitedSnapshot.retryCount, "limited retry count")
                    assertEquals(4, limitedSnapshot.afterStep, "limited after step")
                    assertEquals(MediaVolumeController.RESULT_SET_MISMATCH, limitedSnapshot.resultCode, "limited result")

                    val adjacentAudio = AudioManager(
                        maxVolume = 15,
                        initialVolume = 4,
                        maxWritableVolume = 7,
                    )
                    val adjacentController = MediaVolumeController(adjacentAudio)
                    val adjacentSnapshot = adjacentController.setPercent(50)
                    assertEquals(8, adjacentSnapshot.targetStep, "adjacent target step")
                    assertEquals(7, adjacentSnapshot.afterStep, "adjacent after step")
                    assertEquals(47, adjacentSnapshot.actualPercent, "adjacent actual percent")
                    assertEquals(1, adjacentSnapshot.retryCount, "adjacent retry count")
                    assertEquals(MediaVolumeController.RESULT_SET_MISMATCH, adjacentSnapshot.resultCode, "adjacent mismatch result")

                    val fixedAudio = AudioManager(
                        maxVolume = 10,
                        initialVolume = 4,
                        isVolumeFixed = true,
                    )
                    val fixedController = MediaVolumeController(fixedAudio)
                    val fixedSnapshot = fixedController.setPercent(80)
                    assertEquals(0, fixedSnapshot.retryCount, "fixed retry count")
                    assertEquals(4, fixedSnapshot.afterStep, "fixed after step")
                    assertEquals(MediaVolumeController.RESULT_SET_MISMATCH, fixedSnapshot.resultCode, "fixed result")

                    val failedAudio = AudioManager(
                        maxVolume = 10,
                        initialVolume = 4,
                        throwOnSet = true,
                    )
                    val failedController = MediaVolumeController(failedAudio)
                    val failedSnapshot = failedController.setPercent(40)
                    assertEquals(0, failedSnapshot.retryCount, "failed retry count")
                    assertEquals(4, failedSnapshot.afterStep, "failed after step")
                    assertEquals(MediaVolumeController.RESULT_SET_ERROR, failedSnapshot.resultCode, "failed result")

                    println("PASS: FIX04 Task 2 media volume delayed readback behavior")
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


assert_source_contract(
    [
        "coerceIn(0, 100)",
        "round(",
        "AudioManager.STREAM_MUSIC",
        "Thread.sleep(120L)",
        "retryCount",
        "SYSTEM_LIMITED",
        "EXECUTION_FAILED",
    ]
)
compile_and_run_harness()
print("PASS: FIX04 Task 2 media volume algorithm contract")
