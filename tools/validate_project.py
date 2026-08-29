from pathlib import Path
import re, sys

ROOT = Path(__file__).resolve().parents[1]
checks = []
def check(name, ok): checks.append((name, bool(ok)))
def read(p): return (ROOT / p).read_text('utf-8')

def exists(p): return (ROOT / p).exists()

build = read('app/build.gradle.kts')
wake = read('app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt')
manifest = read('app/src/main/AndroidManifest.xml')
workflow = read('.github/workflows/build-apk.yml')
fetch = read('scripts/fetch-kws-model.sh')
keywords = read('app/src/main/assets/keywords.txt').strip()
router = read('app/src/main/java/com/lchuang/xiaozhimobile/CommandRouter.kt')
main = read('app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt')
phone = read('app/src/main/java/com/lchuang/xiaozhimobile/PhoneController.kt')
settings = read('app/src/main/java/com/lchuang/xiaozhimobile/SettingsStore.kt')

def text(path): return read(path) if exists(path) else ''

normalizer = text('app/src/main/java/com/lchuang/xiaozhimobile/VoiceCommandNormalizer.kt')
overlay_controller = text('app/src/main/java/com/lchuang/xiaozhimobile/AssistantOverlayController.kt')
overlay_view = text('app/src/main/java/com/lchuang/xiaozhimobile/AssistantOverlayView.kt')
session = text('app/src/main/java/com/lchuang/xiaozhimobile/SessionController.kt')
matcher = text('app/src/main/java/com/lchuang/xiaozhimobile/AppNameMatcher.kt')
registry = text('app/src/main/java/com/lchuang/xiaozhimobile/InstalledAppRegistry.kt')
launcher = text('app/src/main/java/com/lchuang/xiaozhimobile/AppLauncher.kt')
map_controller = text('app/src/main/java/com/lchuang/xiaozhimobile/MapController.kt')
location = text('app/src/main/java/com/lchuang/xiaozhimobile/LocationProvider.kt')
wake_compiler = text('app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseCompiler.kt')
wake_manager = text('app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseManager.kt')
tts = text('app/src/main/java/com/lchuang/xiaozhimobile/TtsVoiceManager.kt')
ai_endpoint = text('app/src/main/java/com/lchuang/xiaozhimobile/AiEndpointResolver.kt')
ai_memory = text('app/src/main/java/com/lchuang/xiaozhimobile/AiConversationMemory.kt')
ai_orchestrator = text('app/src/main/java/com/lchuang/xiaozhimobile/AiOrchestrator.kt')
safe_tools = text('app/src/main/java/com/lchuang/xiaozhimobile/SafeToolExecutor.kt')
icon_manager = text('app/src/main/java/com/lchuang/xiaozhimobile/DesktopIconManager.kt')

conversation_state = text('app/src/main/java/com/lchuang/xiaozhimobile/ConversationState.kt')
exit_detector = text('app/src/main/java/com/lchuang/xiaozhimobile/ConversationExitDetector.kt')
volume_parser = text('app/src/main/java/com/lchuang/xiaozhimobile/VolumeCommandParser.kt')

check('version 0.6.4', 'versionCode = 11' in build and 'versionName = "0.6.4"' in build)
check('arm64 target', 'arm64-v8a' in build)
check('compile target 35', 'compileSdk = 35' in build and 'targetSdk = 35' in build)
check('KWS modeling unit cjkchar', 'modelingUnit = "cjkchar"' in wake)
check('local paraformer ASR', 'OfflineParaformerModelConfig' in wake and 'ASR_MODEL_DIR' in wake)
check('no Android SpeechRecognizer dependency', 'SpeechRecognizer' not in wake and 'RecognizerIntent' not in wake)
check('local command capture', 'captureCommandAudio' in wake and 'decodeLocalCommand' in wake)
check('microphone foreground service', 'FOREGROUND_SERVICE_MICROPHONE' in manifest and 'android:foregroundServiceType="microphone"' in manifest)
check('bundled wake phrase metadata', '@小智小智' in keywords)
check('workflow fetches KWS and ASR models', 'sherpa-onnx-paraformer-zh-small-2024-03-09' in fetch and 'kws-models' in fetch)
check('workflow produces v0.6.4 apk', 'XiaoZhi-Mobile-v0.6.4-debug.apk' in workflow)

check('voice normalizer', 'fun normalize(raw: String)' in normalizer and 'router.handle(normalized)' in wake)
check('overlay subsystem', 'TYPE_APPLICATION_OVERLAY' in overlay_controller and 'postInvalidateDelayed' in overlay_view)
check('overlay permission', 'android.permission.SYSTEM_ALERT_WINDOW' in manifest and 'ACTION_MANAGE_OVERLAY_PERMISSION' in main)
check('session controller', bool(session) and 'settings.sessionTimeoutSeconds' in wake and 'session.isExpired()' in wake)
check('custom wake reply', 'var wakeReply: String' in settings and 'settings.wakeReply' in wake)
check('custom timeout reply', 'var timeoutReply: String' in settings and 'settings.timeoutReply' in wake)
check('20 second default', 'getInt("session_timeout_seconds", 20)' in settings)
check('immediate relisten', 'continueConversationSession(immediate = true)' in wake)
check('unsupported command fallback', '抱歉，我还不会这个指令，你可以换一个指令继续服务你' in wake)
check('default logo resources', all(exists(f'app/src/main/res/{folder}/ic_launcher.png') for folder in ['mipmap-mdpi','mipmap-hdpi','mipmap-xhdpi','mipmap-xxhdpi','mipmap-xxxhdpi']))
check('desktop custom icon manager', 'requestPinShortcut' in icon_manager and 'ACTION_OPEN_DOCUMENT' in main)

check('QUERY_ALL_PACKAGES present', 'android.permission.QUERY_ALL_PACKAGES' in manifest)
check('foreground location permissions', 'ACCESS_FINE_LOCATION' in manifest and 'ACCESS_COARSE_LOCATION' in manifest and 'ACCESS_BACKGROUND_LOCATION' not in manifest)
check('structured installed app registry', 'getInstalledApplications' in registry and 'resolveDetailed' in registry and 'AppResolution' in registry)
check('structured app launcher', 'AppLaunchResult' in launcher and 'ComponentName' in launcher and 'AppLaunchResult' in phone)
check('map controller', 'androidamap://keywordNavi' in map_controller and 'baidumap://map/navi' in map_controller and 'geo:0,0?q=' in map_controller)
check('one shot location provider', 'getCurrentLocation' in location and 'ACCESS_BACKGROUND_LOCATION' not in location)
check('runtime wake phrase compiler', 'WakePhraseCompiler' in wake_compiler and 'createStream(' in wake_manager and 'activePhrase' in wake_manager)
check('pinyin4j dependency', 'com.belerweb:pinyin4j:2.5.1' in build)
check('TTS voice manager', 'availableVoices' in tts and 'setVoice' in tts and 'setPitch' in tts)
check('api base URL setting', 'var apiBaseUrl: String' in settings and 'var apiMode: ApiMode' in settings)
check('AI endpoint resolver', 'normalizeBaseUrl' in ai_endpoint and '/v1/chat/completions' in ai_endpoint and '/v1/responses' in ai_endpoint)
check('AI memory 8 turns', 'AiConversationMemory(maxTurns = 8)' in wake and 'maxTurns' in ai_memory)
check('AI orchestrator', 'AiOrchestrator' in ai_orchestrator and 'tool_calls' in ai_orchestrator and 'AiOutcome.Tool' in wake)
check('safe tool executor', 'SafeToolExecutor' in safe_tools and all(f'"{x}"' in safe_tools for x in ['open_app','navigate','search_nearby','open_web','set_volume','flashlight_on','flashlight_off']))
check('manual proxy forced behavior retained', exists('tools/test_manual_proxy_forced.py') and 'XIAOZHI_GIT_PROXY' in read('PUSH_TO_GITHUB.ps1'))
check('no accessibility service', 'AccessibilityService' not in manifest and 'BIND_ACCESSIBILITY_SERVICE' not in manifest)

check('v0.6.4 conversation state model', 'enum class ConversationState' in conversation_state and 'READY_TO_LISTEN' in conversation_state and 'EXITING' in conversation_state)
check('v0.6.4 intelligent exit detector', 'class ConversationExitDetector' in exit_detector and 'AMBIGUOUS' in exit_detector)
check('v0.6.4 natural media volume parser', 'class VolumeCommandParser' in volume_parser and 'SetPercent' in volume_parser)
check('v0.6.4 panel manual exit', 'setOnExitRequested' in overlay_controller and 'GestureDetector' in overlay_view and 'onDoubleTap' in overlay_view)
check('v0.6.4 verified media volume', 'setMediaVolumePercent' in phone and 'FLAG_SHOW_UI' in phone and 'currentMediaVolumePercent' in phone)
check('v0.6.4 state driven relisten', 'scheduleListeningAfterSpeech' in wake and 'ConversationState.READY_TO_LISTEN' in wake)
check('v0.6.4 duplicate guard', 'DEVICE_DUPLICATE_WINDOW_MS = 1500L' in wake and 'isDuplicateDeviceCommand' in wake)
check('v0.6.4 AI exit classifier', 'classifyExitIntent' in ai_orchestrator and 'emptyList()' in ai_orchestrator)
for test_name in [
    'test_v064_conversation_state.py','test_v064_overlay_exit.py','test_v064_exit_intent.py',
    'test_v064_volume_parser.py','test_v064_volume_execution.py','test_v064_command_prompt_flow.py',
    'test_v064_duplicate_guard.py','test_v064_wake_regression.py'
]:
    check('v0.6.4 test ' + test_name, exists('tools/' + test_name))

secret_re = re.compile(r'\bsk-[A-Za-z0-9_-]{20,}\b')
secret_found = False
for p in ROOT.rglob('*'):
    if not p.is_file() or '.git' in p.parts or p.suffix.lower() not in {'.kt','.kts','.py','.md','.xml','.yml','.yaml','.sh','.ps1','.bat','.txt'}:
        continue
    if secret_re.search(p.read_text('utf-8', errors='ignore')):
        secret_found = True
        break
check('no secret-like sk token', not secret_found)

failed = [name for name, ok in checks if not ok]
for name, ok in checks:
    print(('PASS' if ok else 'FAIL') + ': ' + name)
if failed:
    sys.exit(1)
