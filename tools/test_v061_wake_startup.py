from pathlib import Path

root = Path(__file__).resolve().parents[1]
wake = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt').read_text('utf-8')
manager = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseManager.kt').read_text('utf-8')

checks = {
    'default wake phrase constant': 'DEFAULT_WAKE_PHRASE = "小智小智"' in wake,
    'bundled stream direct startup': 'spotter!!.createStream()' in wake,
    'manager can adopt bundled stream': 'adoptBundledStream' in manager,
    'default stream registered with manager': 'adoptBundledStream(DEFAULT_WAKE_PHRASE' in wake,
    'KWS stage notification': '正在加载离线唤醒模型（1/3）' in wake,
    'ASR stage notification': '正在加载离线语音识别模型（2/3）' in wake,
    'custom wake stage notification': '正在应用自定义唤醒词（3/3）' in wake,
    'default phrase skips runtime apply': 'settings.wakePhrase != DEFAULT_WAKE_PHRASE' in wake,
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit('v0.6.1 startup regression missing: ' + ', '.join(failed))

# The startup initializer must not unconditionally replace the known-good bundled stream.
init_start = wake.index('private fun initKeywordSpotter()')
init_end = wake.index('private fun initOfflineAsr()', init_start)
init_block = wake[init_start:init_end]
if 'applyPhrase(settings.wakePhrase)' in init_block:
    raise SystemExit('v0.6.1 startup regression: initKeywordSpotter still unconditionally applies runtime wake phrase')

print('PASS: v0.6.1 default wake startup uses bundled stream and staged diagnostics')
