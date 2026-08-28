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
fetch = (ROOT/'scripts/fetch-kws-model.sh').read_text(encoding='utf-8')
keywords = (ROOT/'app/src/main/assets/keywords.txt').read_text(encoding='utf-8').strip()
router = (ROOT/'app/src/main/java/com/lchuang/xiaozhimobile/CommandRouter.kt').read_text(encoding='utf-8')
main = (ROOT/'app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt').read_text(encoding='utf-8')
phone = (ROOT/'app/src/main/java/com/lchuang/xiaozhimobile/PhoneController.kt').read_text(encoding='utf-8')
normalizer_path = ROOT/'app/src/main/java/com/lchuang/xiaozhimobile/VoiceCommandNormalizer.kt'
overlay_controller_path = ROOT/'app/src/main/java/com/lchuang/xiaozhimobile/AssistantOverlayController.kt'
overlay_view_path = ROOT/'app/src/main/java/com/lchuang/xiaozhimobile/AssistantOverlayView.kt'
normalizer = normalizer_path.read_text(encoding='utf-8') if normalizer_path.exists() else ''
overlay_controller = overlay_controller_path.read_text(encoding='utf-8') if overlay_controller_path.exists() else ''
overlay_view = overlay_view_path.read_text(encoding='utf-8') if overlay_view_path.exists() else ''

check('version 0.4.0', 'versionCode = 5' in build and 'versionName = "0.4.0"' in build)
check('arm64 target', 'arm64-v8a' in build)
check('KWS modeling unit cjkchar', 'modelingUnit = "cjkchar"' in wake)
check('local paraformer ASR', 'OfflineParaformerModelConfig' in wake and 'ASR_MODEL_DIR' in wake)
check('no Android SpeechRecognizer dependency', 'SpeechRecognizer' not in wake and 'RecognizerIntent' not in wake)
check('local command capture', 'captureCommandAudio' in wake and 'decodeLocalCommand' in wake)
check('microphone foreground service permission', 'FOREGROUND_SERVICE_MICROPHONE' in manifest)
check('microphone service type', 'android:foregroundServiceType="microphone"' in manifest)
check('wake phrase metadata', '@小智小智' in keywords)
check('workflow produces v0.4.0 apk', 'XiaoZhi-Mobile-v0.4.0-debug.apk' in workflow)
check('workflow fetches KWS and ASR models', 'sherpa-onnx-paraformer-zh-small-2024-03-09' in fetch and 'kws-models' in fetch)

check('voice command normalizer exists', normalizer_path.exists() and 'fun normalize(raw: String)' in normalizer)
check('spoken command is normalized before router', 'VoiceCommandNormalizer.normalize(rawText)' in wake and 'router.handle(normalized)' in wake)
check('stop music synonyms', '停止音乐' in router and '把音乐停掉' in router)
check('explicit WeChat visibility', 'com.tencent.mm' in manifest)
check('explicit QQ visibility', 'com.tencent.mobileqq' in manifest)
check('explicit app fallback', 'setPackage(' in phone)
check('continuous conversation', 'continueConversationSession' in wake)

check('overlay manifest permission', 'android.permission.SYSTEM_ALERT_WINDOW' in manifest)
check('overlay permission UI', 'ACTION_MANAGE_OVERLAY_PERMISSION' in main and 'Settings.canDrawOverlays' in main)
check('overlay controller exists', overlay_controller_path.exists() and 'TYPE_APPLICATION_OVERLAY' in overlay_controller)
check('overlay non-blocking flags', 'FLAG_NOT_FOCUSABLE' in overlay_controller and 'FLAG_NOT_TOUCHABLE' in overlay_controller)
check('overlay custom HUD exists', overlay_view_path.exists() and '你好，有什么可以帮你？' in overlay_view and 'postInvalidateDelayed' in overlay_view)
check('overlay integrated with wake flow', 'overlay.show()' in wake and 'overlay.updateAudioLevel' in wake and 'overlay.release()' in wake)

failed = [x for x in checks if not x[1]]
for name, ok, detail in checks:
    print(('PASS' if ok else 'FAIL') + ': ' + name + (f' — {detail}' if detail else ''))
if failed:
    sys.exit(1)
