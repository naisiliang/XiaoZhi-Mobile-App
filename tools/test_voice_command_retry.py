from pathlib import Path

p = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt'
s = p.read_text(encoding='utf-8')

checks = {
    'wake-to-command delay': 'COMMAND_LISTEN_DELAY_MS' in s and 'postDelayed({ startSpeechRecognition() }, COMMAND_LISTEN_DELAY_MS)' in s,
    'recognition retry limit': 'MAX_COMMAND_RECOGNITION_ATTEMPTS' in s,
    'retry helper': 'retryCommandRecognition' in s,
    'error code diagnostics': 'speechErrorName(error)' in s,
    'speech timeout tuning': 'EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS' in s,
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    print('FAIL:', ', '.join(failed))
    raise SystemExit(1)
print('PASS: wake-to-command retry protections present')
