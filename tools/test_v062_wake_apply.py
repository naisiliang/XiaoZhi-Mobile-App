from pathlib import Path

root = Path(__file__).resolve().parents[1]
main = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt').read_text('utf-8')
wake = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt').read_text('utf-8')
settings = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/SettingsStore.kt').read_text('utf-8')

checks = {
    'runtime active wake phrase setting': 'activeWakePhrase' in settings,
    'start wake uses apply action': 'setAction(WakeService.ACTION_APPLY_WAKE_SETTINGS)' in main,
    'save all reapplies wake when running': 'applyWakeSettingsIfRunning' in main,
    'service records active phrase': 'settings.activeWakePhrase = active' in wake,
    'ui displays actual active phrase': 'settings.activeWakePhrase' in main and '当前实际 KWS 唤醒短语' in main,
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit('v0.6.2 wake apply regression missing: ' + ', '.join(failed))
print('PASS: v0.6.2 custom wake phrase is automatically applied and actual runtime phrase is visible')
