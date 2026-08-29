from pathlib import Path

root = Path(__file__).resolve().parents[1]
wake = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt').read_text('utf-8')
checks = {
    'session controller field': 'SessionController' in wake,
    'custom wake reply': 'settings.wakeReply' in wake,
    'custom timeout reply': 'settings.timeoutReply' in wake,
    'custom timeout seconds': 'settings.sessionTimeoutSeconds' in wake,
    'deadline expiry path': 'session.isExpired()' in wake,
    'silence keeps listening': 'continueIdleListening' in wake,
    'unknown command fallback exact text': '抱歉，我还不会这个指令，你可以换一个指令继续服务你' in wake,
    'device command classifier': 'router.looksLikeDeviceCommand' in wake,
    'immediate local relisten': 'continueConversationSession(immediate = true)' in wake,
    'local command no tts acknowledgement': 'speakThen(local.reply' not in wake,
    'timeout phrase before end': 'finishSessionForTimeout' in wake,
}
failed=[name for name,ok in checks.items() if not ok]
if failed:
    raise SystemExit('missing v0.5 voice flow: ' + ', '.join(failed))
print('PASS: v0.5.0 voice flow source requirements')
