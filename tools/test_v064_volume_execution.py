from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
phone = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/PhoneController.kt').read_text(encoding='utf-8')
assert 'data class MediaVolumeResult' in phone
assert 'fun currentMediaVolumePercent()' in phone
assert 'fun setMediaVolumePercent(percent: Int)' in phone
assert 'fun volumeUpVerified()' in phone
assert 'fun volumeDownVerified()' in phone
assert 'AudioManager.FLAG_SHOW_UI' in phone
assert 'getStreamMaxVolume(AudioManager.STREAM_MUSIC)' in phone
assert 'getStreamVolume(AudioManager.STREAM_MUSIC)' in phone

m = re.search(r'fun setMediaVolumePercent\(percent: Int\): MediaVolumeResult \{(.*?)\n    \}', phone, re.S)
assert m, 'setMediaVolumePercent block missing'
block = m.group(1)
assert 'setStreamVolume(' in block
assert block.rfind('getStreamVolume(AudioManager.STREAM_MUSIC)') > block.find('setStreamVolume('), 'must re-read after set'
for forbidden in ('STREAM_RING', 'STREAM_NOTIFICATION', 'STREAM_ALARM'):
    assert forbidden not in block, forbidden

up = re.search(r'fun volumeUpVerified\(\): MediaVolumeResult \{(.*?)\n    \}', phone, re.S)
down = re.search(r'fun volumeDownVerified\(\): MediaVolumeResult \{(.*?)\n    \}', phone, re.S)
assert up and down
assert 'adjustStreamVolume' in up.group(1) and 'FLAG_SHOW_UI' in up.group(1)
assert 'adjustStreamVolume' in down.group(1) and 'FLAG_SHOW_UI' in down.group(1)
print('PASS: verified media volume execution source contract')
router = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/CommandRouter.kt').read_text(encoding='utf-8')
safe = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/SafeToolExecutor.kt').read_text(encoding='utf-8')
device_executor = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/DeviceActionExecutor.kt').read_text(encoding='utf-8')
for token in ('VolumeCommandParser', 'VolumeAction.SetPercent', 'VolumeAction.StepUp', 'VolumeAction.StepDown', 'actualPercent'):
    assert token in router, f'router missing {token}'
for token in ('setMediaVolumePercent', 'volumeUpVerified', 'volumeDownVerified', 'actualPercent'):
    assert token in device_executor, f'device executor missing {token}'
assert 'DeviceActionExecutor' in safe and 'deviceActionExecutor.execute' in safe
print('PASS: router and safe tools use the unified verified media volume executor')
