from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
checks = []
def check(name, ok): checks.append((name, bool(ok)))

read=lambda p:(ROOT/p).read_text('utf-8')
build=read('app/build.gradle.kts')
wake=read('app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt')
manifest=read('app/src/main/AndroidManifest.xml')
workflow=read('.github/workflows/build-apk.yml')
fetch=read('scripts/fetch-kws-model.sh')
keywords=read('app/src/main/assets/keywords.txt').strip()
router=read('app/src/main/java/com/lchuang/xiaozhimobile/CommandRouter.kt')
main=read('app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt')
phone=read('app/src/main/java/com/lchuang/xiaozhimobile/PhoneController.kt')
settings=read('app/src/main/java/com/lchuang/xiaozhimobile/SettingsStore.kt')

paths={
 'normalizer':'app/src/main/java/com/lchuang/xiaozhimobile/VoiceCommandNormalizer.kt',
 'overlay_controller':'app/src/main/java/com/lchuang/xiaozhimobile/AssistantOverlayController.kt',
 'overlay_view':'app/src/main/java/com/lchuang/xiaozhimobile/AssistantOverlayView.kt',
 'session':'app/src/main/java/com/lchuang/xiaozhimobile/SessionController.kt',
 'matcher':'app/src/main/java/com/lchuang/xiaozhimobile/AppNameMatcher.kt',
 'registry':'app/src/main/java/com/lchuang/xiaozhimobile/InstalledAppRegistry.kt',
 'icon_manager':'app/src/main/java/com/lchuang/xiaozhimobile/DesktopIconManager.kt',
}
texts={k:read(v) if (ROOT/v).exists() else '' for k,v in paths.items()}

check('version 0.5.0', 'versionCode = 6' in build and 'versionName = "0.5.0"' in build)
check('arm64 target', 'arm64-v8a' in build)
check('KWS modeling unit cjkchar', 'modelingUnit = "cjkchar"' in wake)
check('local paraformer ASR', 'OfflineParaformerModelConfig' in wake and 'ASR_MODEL_DIR' in wake)
check('no Android SpeechRecognizer dependency', 'SpeechRecognizer' not in wake and 'RecognizerIntent' not in wake)
check('local command capture', 'captureCommandAudio' in wake and 'decodeLocalCommand' in wake)
check('microphone foreground service', 'FOREGROUND_SERVICE_MICROPHONE' in manifest and 'android:foregroundServiceType="microphone"' in manifest)
check('wake phrase metadata', '@小智小智' in keywords)
check('workflow produces v0.5.0 apk', 'XiaoZhi-Mobile-v0.5.0-debug.apk' in workflow)
check('workflow fetches KWS and ASR models', 'sherpa-onnx-paraformer-zh-small-2024-03-09' in fetch and 'kws-models' in fetch)

check('voice normalizer', 'fun normalize(raw: String)' in texts['normalizer'] and 'router.handle(normalized)' in wake)
check('overlay subsystem', 'TYPE_APPLICATION_OVERLAY' in texts['overlay_controller'] and 'postInvalidateDelayed' in texts['overlay_view'])
check('overlay permission', 'android.permission.SYSTEM_ALERT_WINDOW' in manifest and 'ACTION_MANAGE_OVERLAY_PERMISSION' in main)
check('session controller', (ROOT/paths['session']).exists() and 'settings.sessionTimeoutSeconds' in wake and 'session.isExpired()' in wake)
check('custom wake reply', 'var wakeReply: String' in settings and 'settings.wakeReply' in wake)
check('custom timeout reply', 'var timeoutReply: String' in settings and 'settings.timeoutReply' in wake)
check('20 second default', 'getInt("session_timeout_seconds", 20)' in settings)
check('immediate relisten', 'continueConversationSession(immediate = true)' in wake)
check('unsupported command fallback', '抱歉，我还不会这个指令，你可以换一个指令继续服务你' in wake)
check('installed app registry', (ROOT/paths['registry']).exists() and 'queryIntentActivities' in texts['registry'] and 'InstalledAppRegistry' in phone)
check('fuzzy app matcher', (ROOT/paths['matcher']).exists() and 'similarity' in texts['matcher'] and 'parseAliases' in texts['matcher'])
check('app alias setting', 'var appAliases: String' in settings and 'appAliases' in main)
check('default launcher icon', 'android:icon="@mipmap/ic_launcher"' in manifest and 'android:roundIcon="@mipmap/ic_launcher_round"' in manifest)
check('desktop custom icon manager', (ROOT/paths['icon_manager']).exists() and 'requestPinShortcut' in texts['icon_manager'] and 'ACTION_OPEN_DOCUMENT' in main)
check('default logo resources', all((ROOT/f'app/src/main/res/{folder}/ic_launcher.png').exists() for folder in ['mipmap-mdpi','mipmap-hdpi','mipmap-xhdpi','mipmap-xxhdpi','mipmap-xxxhdpi']))
check('no accessibility service', 'AccessibilityService' not in manifest)

failed=[name for name,ok in checks if not ok]
for name,ok in checks: print(('PASS' if ok else 'FAIL')+': '+name)
if failed: sys.exit(1)
