from pathlib import Path
root = Path(__file__).resolve().parents[1]
wake = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt').read_text('utf-8')
checks = {
    'AI orchestrator field': 'AiOrchestrator' in wake,
    'safe executor field': 'SafeToolExecutor' in wake,
    'conversation memory': 'AiConversationMemory' in wake,
    'memory session start': 'memory.startSession()' in wake,
    'memory clear': 'memory.clear()' in wake,
    'AI tool branch': 'AiOutcome.Tool' in wake,
    'AI reply branch': 'AiOutcome.Reply' in wake,
    'safe tool plan-first execution': 'safeToolExecutor.plan' in wake and 'executeDeviceAction' in wake,
    'local immediate relisten preserved': 'continueConversationSession(immediate = true)' in wake,
    'dynamic wake notification': 'settings.wakePhrase' in wake or 'activePhrase()' in wake,
    'assistant name used': 'settings.assistantName' in wake,
}
failed = [k for k,v in checks.items() if not v]
if failed:
    raise SystemExit('missing v0.6 voice integration: ' + ', '.join(failed))
print('PASS: v0.6 voice integration source')
