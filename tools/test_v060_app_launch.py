from pathlib import Path

root = Path(__file__).resolve().parents[1]
registry = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/InstalledAppRegistry.kt').read_text('utf-8')
launcher_path = root / 'app/src/main/java/com/lchuang/xiaozhimobile/AppLauncher.kt'
phone = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/PhoneController.kt').read_text('utf-8')
router = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/CommandRouter.kt').read_text('utf-8')

checks = {
    'installed package fallback': 'getInstalledApplications' in registry or 'getInstalledPackages' in registry,
    'launcher activities': 'launchActivities' in registry,
    'discovery source': 'AppDiscoverySource' in registry,
    'detailed resolution': 'resolveDetailed' in registry and 'AppResolution' in registry,
    'launcher class': launcher_path.exists(),
    'structured phone launch': 'AppLaunchResult' in phone,
    'known amap alias': '高德导航' in router or '高德导航' in (root / 'app/src/main/java/com/lchuang/xiaozhimobile/AppNameMatcher.kt').read_text('utf-8'),
}
failed = [k for k,v in checks.items() if not v]
if failed:
    raise SystemExit('missing app launch features: ' + ', '.join(failed))
print('PASS: v0.6 app discovery and launch source')
