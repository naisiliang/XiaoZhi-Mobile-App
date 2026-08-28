from pathlib import Path

p = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt'
s = p.read_text(encoding='utf-8')
checks = {
    'wake-to-command delay': 'COMMAND_LISTEN_DELAY_MS' in s and 'startLocalCommandRecognition()' in s,
    'recognition retry limit': 'MAX_COMMAND_RECOGNITION_ATTEMPTS' in s,
    'retry helper': 'retryLocalCommandRecognition' in s,
    'local audio capture': 'captureCommandAudio' in s,
    'local asr decode': 'decodeLocalCommand' in s,
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    print('FAIL:', ', '.join(failed))
    raise SystemExit(1)
print('PASS: local command recognition retry protections present')
