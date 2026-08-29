from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt').read_text(encoding='utf-8')

assert 'Settings.ACTION_TTS_SETTINGS' not in source, 'Android Settings has no public ACTION_TTS_SETTINGS constant'
assert 'com.android.settings.TTS_SETTINGS' in source, 'TTS settings should use the widely supported settings action string'
assert 'Settings.ACTION_SETTINGS' in source, 'TTS settings launch must fall back to generic system settings'

print('PASS: v0.6 TTS settings action uses compilable fallback')
