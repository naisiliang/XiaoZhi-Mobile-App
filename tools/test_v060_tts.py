from pathlib import Path
root = Path(__file__).resolve().parents[1]
manager = root / 'app/src/main/java/com/lchuang/xiaozhimobile/TtsVoiceManager.kt'
wake = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt').read_text('utf-8')
if not manager.exists():
    raise SystemExit('TtsVoiceManager.kt missing')
text = manager.read_text('utf-8')
for value in ['availableVoices', 'setVoice', 'setSpeechRate', 'setPitch', 'preview', 'Locale.CHINESE']:
    if value not in text:
        raise SystemExit('TTS feature missing: ' + value)
if 'TtsVoiceManager' not in wake:
    raise SystemExit('WakeService not using TtsVoiceManager')
print('PASS: v0.6 TTS voice source')
