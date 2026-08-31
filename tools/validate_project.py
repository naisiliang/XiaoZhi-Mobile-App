import ast
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
checks = []


def check(name, ok):
    checks.append((name, bool(ok)))


def read(path):
    return (ROOT / path).read_text("utf-8")


def exists(path):
    return (ROOT / path).exists()


def text(path):
    return read(path) if exists(path) else ""


def appears_in_order(source, markers):
    index = -1
    for marker in markers:
        next_index = source.find(marker, index + 1)
        if next_index == -1:
            return False
        index = next_index
    return True


def parse_release_gate_tests(source):
    module = ast.parse(source)
    for node in module.body:
        if not isinstance(node, ast.Assign):
            continue
        for target in node.targets:
            if isinstance(target, ast.Name) and target.id == "TESTS":
                if not isinstance(node.value, ast.List):
                    return None
                values = []
                for item in node.value.elts:
                    if not isinstance(item, ast.Constant) or not isinstance(item.value, str):
                        return None
                    values.append(item.value)
                return values
    return None


def has_release_gate_subprocess_run(source):
    module = ast.parse(source)
    for node in ast.walk(module):
        if not isinstance(node, ast.Call):
            continue
        func = node.func
        if not (
            isinstance(func, ast.Attribute)
            and isinstance(func.value, ast.Name)
            and func.value.id == "subprocess"
            and func.attr == "run"
        ):
            continue
        if len(node.args) != 1 or not isinstance(node.args[0], ast.List):
            continue
        args = node.args[0].elts
        if len(args) != 2:
            continue
        if not (
            isinstance(args[0], ast.Constant)
            and args[0].value == "python"
            and isinstance(args[1], ast.Name)
            and args[1].id == "test"
        ):
            continue
        keywords = {item.arg: item.value for item in node.keywords}
        if not (
            isinstance(keywords.get("cwd"), ast.Name)
            and keywords["cwd"].id == "root"
            and isinstance(keywords.get("check"), ast.Constant)
            and keywords["check"].value is True
        ):
            continue
        return True
    return False


build = read("app/build.gradle.kts")
workflow = read(".github/workflows/build-apk.yml")
manifest = read("app/src/main/AndroidManifest.xml")
wake = read("app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt")
fetch = read("scripts/fetch-kws-model.sh")
safe_tools = read("app/src/main/java/com/lchuang/xiaozhimobile/SafeToolExecutor.kt")
app_exit = read("app/src/main/java/com/lchuang/xiaozhimobile/AppExitController.kt")
audio_enhancement = read("app/src/main/java/com/lchuang/xiaozhimobile/AudioEnhancementManager.kt")
notifier = read("app/src/main/java/com/lchuang/xiaozhimobile/CommandResultNotifier.kt")
coordinator = read("app/src/main/java/com/lchuang/xiaozhimobile/ExecutionFeedbackCoordinator.kt")
release_gate = read("tools/test_v065_release_gate.py")

required_v065_tests = [
    "tools/test_v065_frozen_baseline.py",
    "tools/test_v065_device_command_plan.py",
    "tools/test_v065_home_exit.py",
    "tools/test_v065_safe_tool_planning.py",
    "tools/test_v065_execution_copy.py",
    "tools/test_v065_execution_feedback.py",
    "tools/test_v065_listening_truth.py",
    "tools/test_v065_adaptive_vad.py",
    "tools/test_v065_noise_suppressor.py",
    "tools/test_v065_error_recovery.py",
]
required_historical_tests = [
    "tools/test_v031_behavior.py",
    "tools/test_v040_voice_flow.py",
    "tools/test_v050_session.py",
    "tools/test_v060_safe_tools.py",
    "tools/test_v060_security.py",
    "tools/test_v063_custom_wake_ppinyin.py",
    "tools/test_v063_wake_error_diagnostics.py",
    "tools/test_v064_wake_regression.py",
    "tools/test_v064_exit_intent.py",
    "tools/test_v064_volume_parser.py",
    "tools/test_v064_volume_execution.py",
    "tools/test_v064_command_prompt_flow.py",
    "tools/test_v064_duplicate_guard.py",
]
required_v065_sources = [
    "app/src/main/java/com/lchuang/xiaozhimobile/AdaptiveVoiceActivityDetector.kt",
    "app/src/main/java/com/lchuang/xiaozhimobile/AppExitController.kt",
    "app/src/main/java/com/lchuang/xiaozhimobile/AudioEnhancementManager.kt",
    "app/src/main/java/com/lchuang/xiaozhimobile/CommandResultNotifier.kt",
    "app/src/main/java/com/lchuang/xiaozhimobile/CommandTransaction.kt",
    "app/src/main/java/com/lchuang/xiaozhimobile/DeviceAction.kt",
    "app/src/main/java/com/lchuang/xiaozhimobile/DeviceActionExecutor.kt",
    "app/src/main/java/com/lchuang/xiaozhimobile/ExecutionFeedbackCoordinator.kt",
    "app/src/main/java/com/lchuang/xiaozhimobile/ExecutionIntentFormatter.kt",
    "app/src/main/java/com/lchuang/xiaozhimobile/TtsProgressRegistry.kt",
]

check("version 0.6.5", 'versionCode = 12' in build and 'versionName = "0.6.5"' in build)
check("arm64 target", 'abiFilters += listOf("arm64-v8a")' in build)
check("compile target 35", "compileSdk = 35" in build and "targetSdk = 35" in build)
check("microphone foreground service", "FOREGROUND_SERVICE_MICROPHONE" in manifest and 'android:foregroundServiceType="microphone"' in manifest)
check("workflow fetches KWS and ASR models", "sherpa-onnx-paraformer-zh-small-2024-03-09" in fetch and "kws-models" in fetch)

parsed_release_gate_tests = parse_release_gate_tests(release_gate)
check("release gate exact TESTS list", parsed_release_gate_tests == required_v065_tests)
check("release gate uses subprocess execution", has_release_gate_subprocess_run(release_gate))
check("release gate prints final PASS", 'print("PASS: v0.6.5 release gate")' in release_gate)
check("v0.6.5 frozen guard delegated", exists("tools/test_v065_frozen_baseline.py") and "tools/test_v065_frozen_baseline.py" in release_gate)

for path in required_v065_tests:
    check("required v0.6.5 test " + Path(path).name, exists(path))

for path in required_historical_tests:
    check("required historical test " + Path(path).name, exists(path))

for path in required_v065_sources:
    check("required v0.6.5 source " + Path(path).name, exists(path))

workflow_steps = [
    "- name: Frozen wake guard",
    "- name: Historical regression",
    "- name: v0.6.5 feature regression",
    "- name: Security regression",
    "- name: Validate source tree",
    "- name: Fetch offline wake + ASR models",
    "- name: Build debug APK",
    "- name: Rename APK",
]
check("workflow release-gate order", appears_in_order(workflow, workflow_steps))
check("workflow historical test commands", appears_in_order(workflow, [
    "python3 tools/test_v031_behavior.py",
    "python3 tools/test_v040_voice_flow.py",
    "python3 tools/test_v050_session.py",
    "python3 tools/test_v060_security.py",
    "python3 tools/test_v063_custom_wake_ppinyin.py",
    "python3 tools/test_v063_wake_error_diagnostics.py",
    "python3 tools/test_v064_wake_regression.py",
    "python3 tools/test_v064_exit_intent.py",
    "python3 tools/test_v064_volume_parser.py",
    "python3 tools/test_v064_volume_execution.py",
    "python3 tools/test_v064_command_prompt_flow.py",
    "python3 tools/test_v064_duplicate_guard.py",
]))
check("workflow security commands", appears_in_order(workflow, [
    "python3 tools/test_v060_safe_tools.py",
    "python3 tools/test_v060_security.py",
]))
check("workflow renamed apk", "run: cp app/build/outputs/apk/debug/app-debug.apk XiaoZhi-Mobile-v0.6.5-debug.apk" in workflow)
check("workflow artifact path", "path: XiaoZhi-Mobile-v0.6.5-debug.apk" in workflow)

noise_suppressor_files = []
for path in ROOT.rglob("*.kt"):
    if ".git" in path.parts:
        continue
    if "NoiseSuppressor" in path.read_text("utf-8", errors="ignore"):
        noise_suppressor_files.append(path.relative_to(ROOT).as_posix())
check(
    "NoiseSuppressor only in command enhancement manager",
    noise_suppressor_files == ["app/src/main/java/com/lchuang/xiaozhimobile/AudioEnhancementManager.kt"]
)
check("command enhancement manager safely degrades", all(token in audio_enhancement for token in [
    "NoiseSuppressor.isAvailable()",
    "NoiseSuppressor.create(record.audioSessionId)",
    "return NO_OP",
    "suppressor.release()",
]))
check("WakeService uses enhancement manager", "AudioEnhancementManager()" in wake)

check("home exit uses HOME intent only", all(token in app_exit for token in [
    "Intent.ACTION_MAIN",
    "Intent.CATEGORY_HOME",
    "Intent.FLAG_ACTIVITY_NEW_TASK",
    "HomeResult(true, \"GO_HOME_OK\")",
    "HomeResult(false, \"GO_HOME_FAILED\")",
]))
check(
    "home exit avoids force-stop root shell accessibility",
    not any([
        "force-stop" in app_exit,
        "force stop" in app_exit,
        "Runtime.getRuntime" in app_exit,
        "ProcessBuilder" in app_exit,
        "Accessibility" in app_exit,
        re.search(r"\\bsu\\b", app_exit) is not None,
        re.search(r"\\bshell\\b", app_exit) is not None,
    ])
)

allowed_tools = [
    "open_app",
    "navigate",
    "search_nearby",
    "open_web",
    "media_play",
    "media_pause",
    "media_next",
    "media_previous",
    "volume_up",
    "volume_down",
    "set_volume",
    "flashlight_on",
    "flashlight_off",
]
check("safe tool allowlist retained", all(f'"{tool}"' in safe_tools for tool in allowed_tools))
check("safe tool allowlist excludes new device authority", all(f'"{tool}"' not in safe_tools for tool in [
    "go_home",
    "shell_command",
    "delete_all_files",
    "send_message",
    "transfer_money",
    "install_app",
]))
check("dangerous web scheme rejection retained", all(scheme in safe_tools for scheme in [
    "javascript:",
    "file:",
    "content:",
    "intent:",
]))
check("go-home stays outside safe tool allowlist", "DeviceAction.GoHome" in safe_tools and "REJECTED_NOT_ALLOWED" in safe_tools)

check("execution coordinator retained", all(token in coordinator for token in [
    "class ExecutionFeedbackCoordinator",
    "actionDelayMs: Long = 120L",
    "notifier.running(",
    "scheduler.postDelayed(actionDelayMs)",
    "copy.successNotification?.let(notifier::success)",
    "copy.failureNotification?.let(notifier::failure)",
]))
check("command result retention retained", all(token in notifier for token in [
    "private var retainedText: String? = null",
    "private var retainedUntilMs: Long? = null",
    "holdMs: Long = 4000L",
    "fun publishTransient(text: String)",
    "retainedText() ?: text",
]))
check("WakeService wires execution feedback", all(token in wake for token in [
    "private lateinit var commandResultNotifier: CommandResultNotifier",
    "private lateinit var executionCoordinator: ExecutionFeedbackCoordinator",
    "CommandResultNotifier(",
    "ExecutionFeedbackCoordinator(",
    "DeviceActionExecutor(phone, appExitController)",
    "TtsProgressRegistry(",
]))

secret_re = re.compile(r"\bsk-[A-Za-z0-9_-]{20,}\b")
secret_found = False
for path in ROOT.rglob("*"):
    if not path.is_file() or ".git" in path.parts:
        continue
    if path.suffix.lower() not in {".kt", ".kts", ".py", ".md", ".xml", ".yml", ".yaml", ".sh", ".ps1", ".bat", ".txt"}:
        continue
    if secret_re.search(path.read_text("utf-8", errors="ignore")):
        secret_found = True
        break
check("no secret-like sk token", not secret_found)

failed = [name for name, ok in checks if not ok]
for name, ok in checks:
    print(("PASS" if ok else "FAIL") + ": " + name)
if failed:
    sys.exit(1)
