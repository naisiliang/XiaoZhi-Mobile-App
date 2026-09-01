from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
CONTROLLER = (
    ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/MediaVolumeController.kt"
).read_text(encoding="utf-8")
PHONE = (
    ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/PhoneController.kt"
).read_text(encoding="utf-8")
ROUTER = (
    ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/CommandRouter.kt"
).read_text(encoding="utf-8")
SAFE = (
    ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/SafeToolExecutor.kt"
).read_text(encoding="utf-8")
DEVICE_EXECUTOR = (
    ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/DeviceActionExecutor.kt"
).read_text(encoding="utf-8")


assert "data class MediaVolumeSnapshot" in CONTROLLER
assert "open class MediaVolumeController" in CONTROLLER
assert "open fun snapshot()" in CONTROLLER
assert "open fun setPercent(percent: Int)" in CONTROLLER
assert "open fun adjust(direction: Int)" in CONTROLLER
assert "AudioManager.STREAM_MUSIC" in CONTROLLER
assert "AudioManager.FLAG_SHOW_UI" in CONTROLLER
assert "Thread.sleep(120L)" in CONTROLLER
assert "writeAndReadBack" in CONTROLLER
for forbidden in ("STREAM_RING", "STREAM_NOTIFICATION", "STREAM_ALARM"):
    assert forbidden not in CONTROLLER, forbidden

set_percent = re.search(
    r"open fun setPercent\(percent: Int\): MediaVolumeSnapshot \{(.*?)\n    \}",
    CONTROLLER,
    re.S,
)
assert set_percent, "setPercent block missing"
set_block = set_percent.group(1)
assert "writeAndReadBack(targetStep, maxStep)" in set_block
assert "shouldRetrySet(beforeStep, targetStep, afterStep)" in set_block
assert "fallbackStep(beforeStep, targetStep, afterStep, maxStep)" in set_block
assert "classifySetResult(targetStep, afterStep)" in set_block

adjust = re.search(
    r"open fun adjust\(direction: Int\): MediaVolumeSnapshot \{(.*?)\n    \}",
    CONTROLLER,
    re.S,
)
assert adjust, "adjust block missing"
adjust_block = adjust.group(1)
assert "audioManager.adjustStreamVolume(" in adjust_block
assert "AudioManager.ADJUST_RAISE" in adjust_block
assert "AudioManager.ADJUST_LOWER" in adjust_block
assert "classifyAdjustResult(direction, beforeStep, afterStep)" in adjust_block
print("PASS: media volume execution lives in MediaVolumeController")

assert "private val mediaVolumeControllerOverride: MediaVolumeController? = null" in PHONE
assert "private val mediaVolumeController = mediaVolumeControllerOverride ?: MediaVolumeController(audioManager)" in PHONE
assert "fun currentMediaVolumePercent(): Int = mediaVolumeController.snapshot().actualPercent" in PHONE

set_wrapper = re.search(
    r"fun setMediaVolumePercent\(percent: Int\): MediaVolumeResult \{(.*?)\n    \}",
    PHONE,
    re.S,
)
assert set_wrapper, "setMediaVolumePercent wrapper missing"
set_wrapper_block = set_wrapper.group(1)
assert "val snapshot = mediaVolumeController.setPercent(percent)" in set_wrapper_block
assert "snapshot.resultCode == MediaVolumeController.RESULT_SET_OK" in set_wrapper_block
assert "toFeedbackResultCode(snapshot.resultCode)" in set_wrapper_block
assert "setStreamVolume(" not in set_wrapper_block
assert "adjustStreamVolume(" not in set_wrapper_block

up_wrapper = re.search(
    r"fun volumeUpVerified\(\): MediaVolumeResult \{(.*?)\n    \}",
    PHONE,
    re.S,
)
down_wrapper = re.search(
    r"fun volumeDownVerified\(\): MediaVolumeResult \{(.*?)\n    \}",
    PHONE,
    re.S,
)
assert up_wrapper and down_wrapper, "verified volume wrappers missing"
assert "mediaVolumeController.adjust(AudioManager.ADJUST_RAISE)" in up_wrapper.group(1)
assert "mediaVolumeController.adjust(AudioManager.ADJUST_LOWER)" in down_wrapper.group(1)
assert "adjustStreamVolume(" not in up_wrapper.group(1)
assert "adjustStreamVolume(" not in down_wrapper.group(1)
print("PASS: PhoneController delegates verified media volume behavior")

for token in (
    "VolumeCommandParser",
    "VolumeAction.SetPercent",
    "VolumeAction.StepUp",
    "VolumeAction.StepDown",
):
    assert token in ROUTER, f"router missing {token}"
for token in (
    "phone.setMediaVolumePercent(action.percent)",
    "phone.volumeUpVerified()",
    "phone.volumeDownVerified()",
    "actualPercent",
):
    assert token in DEVICE_EXECUTOR, f"device executor missing {token}"
for token in ('"volume_up" -> allowed(DeviceAction.MediaVolumeUp)', '"volume_down" -> allowed(DeviceAction.MediaVolumeDown)', '"set_volume" -> {'):
    assert token in SAFE, f"safe tool executor missing {token}"
assert "DeviceActionExecutor" in SAFE and "deviceActionExecutor.execute" in SAFE
print("PASS: router, safe tools, and device executor use the unified verified media volume contract")
