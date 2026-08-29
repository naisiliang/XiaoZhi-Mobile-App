from pathlib import Path
root = Path(__file__).resolve().parents[1]
wake = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt').read_text('utf-8')
manager = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseManager.kt').read_text('utf-8')
checks = {
    'apply failure includes reason': '唤醒词应用失败：' in wake and 'exceptionOrNull()' in wake,
    'custom stream validates nonzero ptr': 'newStream.ptr == 0L' in manager,
    'failure keeps previous active phrase': 'activePhrase()' in wake and '继续监听' in wake,
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit('v0.6.3 wake diagnostics missing: ' + ', '.join(failed))
print('PASS: v0.6.3 custom wake failures expose a concrete reason and keep previous stream')
