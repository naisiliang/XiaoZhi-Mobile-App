from pathlib import Path

root = Path(__file__).resolve().parents[1]
wake = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt').read_text('utf-8')
router = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/CommandRouter.kt').read_text('utf-8')
safe = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/SafeToolExecutor.kt').read_text('utf-8')

checks = {
    'shared command confirmation helper': 'speakCommandConfirmation' in wake,
    'local command success speaks confirmation': 'speakCommandConfirmation(local.reply' in wake,
    'AI tool success speaks confirmation': 'speakCommandConfirmation(executed.spokenText' in wake,
    'immediate listen remains 120ms': 'IMMEDIATE_LISTEN_DELAY_MS = 120L' in wake,
    'app reply is completed tense': '已打开${result.label}' in router,
    'AI open app reply is completed tense': '已打开${result.label}' in safe,
    'next-track trigger preserved': 'containsAny(text, "下一首", "下一曲", "切下一首")' in router,
    'previous-track trigger preserved': 'containsAny(text, "上一首", "上一曲", "切上一首")' in router,
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit('v0.6.2 command confirmation regression missing: ' + ', '.join(failed))
print('PASS: v0.6.2 successful commands are spoken then immediately return to listening')
