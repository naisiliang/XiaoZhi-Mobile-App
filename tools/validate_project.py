from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
checks = []

def check(name, ok, detail=''):
    checks.append((name, bool(ok), detail))

build = (ROOT/'app/build.gradle.kts').read_text(encoding='utf-8')
wake = (ROOT/'app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt').read_text(encoding='utf-8')
manifest = (ROOT/'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')
workflow = (ROOT/'.github/workflows/build-apk.yml').read_text(encoding='utf-8')
keywords = (ROOT/'app/src/main/assets/keywords.txt').read_text(encoding='utf-8').strip()

check('version 0.2.1', 'versionName = "0.2.1"' in build)
check('arm64 target', 'arm64-v8a' in build)
check('KWS modeling unit cjkchar', 'modelingUnit = "cjkchar"' in wake)
check('microphone foreground service permission', 'FOREGROUND_SERVICE_MICROPHONE' in manifest)
check('microphone service type', 'android:foregroundServiceType="microphone"' in manifest)
check('wake phrase metadata', '@小智小智' in keywords)
check('workflow produces v0.2.1 apk', 'XiaoZhi-Mobile-v0.2.1-debug.apk' in workflow)
check('workflow fetches KWS model', 'fetch-kws-model.sh' in workflow)

failed = [x for x in checks if not x[1]]
for name, ok, detail in checks:
    print(('PASS' if ok else 'FAIL') + ': ' + name + (f' — {detail}' if detail else ''))
if failed:
    sys.exit(1)
