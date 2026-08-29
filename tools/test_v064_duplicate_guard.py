from pathlib import Path
import re
root=Path(__file__).resolve().parents[1]
wake=(root/'app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt').read_text(encoding='utf-8')
assert 'DEVICE_DUPLICATE_WINDOW_MS' in wake
m=re.search(r'DEVICE_DUPLICATE_WINDOW_MS\s*=\s*(\d+)L',wake)
assert m and 1200 <= int(m.group(1)) <= 1800
for token in ['lastDeviceCommand','lastDeviceCommandAtMs','isDuplicateDeviceCommand','SystemClock.elapsedRealtime']:
    assert token in wake, token
assert '已忽略重复指令' in wake
print('PASS: duplicate device command guard source')
