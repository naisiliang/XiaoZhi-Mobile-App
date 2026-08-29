from pathlib import Path
root = Path(__file__).resolve().parents[1]
wake = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt').read_text('utf-8')
checks = {
    'explicit wakeDetected flag': 'var wakeDetected = false' in wake,
    'set flag only on keyword match': 'wakeDetected = true' in wake,
    'dispatch only when detected': 'if (running.get() && wakeDetected)' in wake,
}
failed=[k for k,v in checks.items() if not v]
if failed:
    raise SystemExit('manual KWS stop can still cause false wake: ' + ', '.join(failed))
if 'if (running.get() && !kwsListening.get())' in wake:
    raise SystemExit('manual KWS stop still dispatches wake based only on kwsListening=false')
print('PASS: manual KWS stop cannot dispatch a false wake')
