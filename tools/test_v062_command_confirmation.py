from pathlib import Path

root = Path(__file__).resolve().parents[1]
wake = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt').read_text('utf-8')
router = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/CommandRouter.kt').read_text('utf-8')
safe = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/SafeToolExecutor.kt').read_text('utf-8')
device = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/DeviceActionExecutor.kt').read_text('utf-8')
coordinator = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/ExecutionFeedbackCoordinator.kt').read_text('utf-8')
safe_execute = safe[safe.index('fun execute(call: AiToolCall'):safe.index('fun plan(call: AiToolCall')]

checks = {
    'shared command confirmation coordinator': 'ExecutionFeedbackCoordinator' in wake and 'executionCoordinator.execute' in wake,
    'local command uses shared execution funnel': 'router.plan(normalized)' in wake and 'executeDeviceAction(rawText, normalized, localPlan.action, heard)' in wake,
    'AI tool uses shared execution funnel': 'safeToolExecutor.plan(outcome.call)' in wake and 'executeDeviceAction(rawText, normalized, toolPlan.action, heard)' in wake,
    'real result is spoken before transaction finishes': 'speech.speak(requireNotNull(copy.finalSpoken)' in coordinator and 'onFinished(transaction.copy(result = result))' in coordinator,
    'immediate listen remains 120ms': 'IMMEDIATE_LISTEN_DELAY_MS = 120L' in wake,
    'app reply is completed tense': '已打开${result.label}' in router,
    'unified executor owns AI open app result contract': 'is AppLauncher.AppLaunchResult.Success -> ok("OPEN_APP_OK", "已打开${result.label}", "打开${result.label}")' in device,
    'SafeToolExecutor maps unified result contract': 'deviceActionExecutor.execute(planned.action) { result ->' in safe_execute and 'callback(ToolExecutionResult(result.success, result.spokenResult, result.code))' in safe_execute,
    'SafeToolExecutor does not duplicate app result speech': '已打开${result.label}' not in safe_execute,
    'SafeToolExecutor preserves restricted action boundary': 'is DeviceAction.GoHome,\n                is DeviceAction.OpenMap,\n                DeviceAction.MediaStop -> callback(ToolExecutionResult(false, "该操作不在安全工具白名单中", "REJECTED_NOT_ALLOWED"))' in safe_execute,
    'next-track trigger preserved': 'containsAny(text, "下一首", "下一曲", "切下一首")' in router,
    'previous-track trigger preserved': 'containsAny(text, "上一首", "上一曲", "切上一首")' in router,
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit('v0.6.2 command confirmation regression missing: ' + ', '.join(failed))
print('PASS: v0.6.2 successful commands are spoken then immediately return to listening')
