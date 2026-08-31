from __future__ import annotations

import ast
from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
IGNORED_TEXT_DIRS = {".git", ".gradle", "__pycache__", "build"}
BINARY_SUFFIXES = {
    ".apk",
    ".bin",
    ".class",
    ".db",
    ".dex",
    ".dll",
    ".exe",
    ".gif",
    ".gz",
    ".ico",
    ".jar",
    ".jpeg",
    ".jpg",
    ".mp3",
    ".mp4",
    ".ogg",
    ".onnx",
    ".pdf",
    ".png",
    ".pyc",
    ".so",
    ".sqlite",
    ".ttf",
    ".wav",
    ".webp",
    ".zip",
}
SECRET_RE = re.compile(r"\bsk-[A-Za-z0-9_-]{20,}\b")
HOME_EXIT_FORBIDDEN_PATTERNS = {
    "su": re.compile(r"\bsu\b", re.IGNORECASE),
    "shell": re.compile(r"\bshell\b", re.IGNORECASE),
    "forceStopPackage": re.compile(r"\bforceStopPackage\b"),
    "killBackgroundProcesses": re.compile(r"\bkillBackgroundProcesses\b"),
    "force-stop": re.compile(r"force\s*-\s*stop", re.IGNORECASE),
    "force stop": re.compile(r"force\s+stop", re.IGNORECASE),
    "Runtime.getRuntime": re.compile(r"Runtime\.getRuntime"),
    "ProcessBuilder": re.compile(r"\bProcessBuilder\b"),
    "Accessibility": re.compile(r"\bAccessibility(?:Service)?\b|BIND_ACCESSIBILITY_SERVICE"),
}
EXPECTED_SAFE_TOOLS = [
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
REQUIRED_V065_TESTS = [
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
REQUIRED_HISTORICAL_TESTS = [
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
REQUIRED_V065_SOURCES = [
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


def read(path: str) -> str:
    return (ROOT / path).read_text("utf-8")


def exists(path: str) -> bool:
    return (ROOT / path).exists()


def text(path: str) -> str:
    return read(path) if exists(path) else ""


def appears_in_order(source: str, markers: list[str]) -> bool:
    index = -1
    for marker in markers:
        next_index = source.find(marker, index + 1)
        if next_index == -1:
            return False
        index = next_index
    return True


def parse_release_gate_tests(source: str) -> list[str] | None:
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


def _is_python_test_run(node: ast.AST) -> bool:
    if not isinstance(node, ast.Call):
        return False
    func = node.func
    if not (
        isinstance(func, ast.Attribute)
        and isinstance(func.value, ast.Name)
        and func.value.id == "subprocess"
        and func.attr == "run"
    ):
        return False
    if len(node.args) != 1 or not isinstance(node.args[0], ast.List):
        return False
    args = node.args[0].elts
    if len(args) != 2:
        return False
    if not (
        isinstance(args[0], ast.Constant)
        and args[0].value == "python"
        and isinstance(args[1], ast.Name)
        and args[1].id == "test"
    ):
        return False
    keywords = {item.arg: item.value for item in node.keywords}
    return (
        isinstance(keywords.get("cwd"), ast.Name)
        and keywords["cwd"].id == "root"
        and isinstance(keywords.get("check"), ast.Constant)
        and keywords["check"].value is True
    )


def _is_direct_python_test_run_statement(node: ast.stmt) -> bool:
    return isinstance(node, ast.Expr) and _is_python_test_run(node.value)


def has_release_gate_loop_subprocess_run(source: str) -> bool:
    module = ast.parse(source)
    for node in ast.walk(module):
        if not (
            isinstance(node, ast.For)
            and isinstance(node.target, ast.Name)
            and node.target.id == "test"
            and isinstance(node.iter, ast.Name)
            and node.iter.id == "TESTS"
        ):
            continue
        for statement in node.body:
            if _is_direct_python_test_run_statement(statement):
                return True
            if isinstance(statement, ast.Try) and any(
                _is_direct_python_test_run_statement(child) for child in statement.body
            ):
                return True
    return False


def release_gate_delegates_expected_tests(source: str, expected_tests: list[str]) -> bool:
    return parse_release_gate_tests(source) == expected_tests


def _extract_braced_block(source: str, marker_re: re.Pattern[str]) -> str | None:
    match = marker_re.search(source)
    if match is None:
        return None
    brace_start = source.find("{", match.start())
    if brace_start == -1:
        return None

    depth = 1
    index = brace_start + 1
    in_string = False
    quote = ""
    escape = False
    line_comment = False
    block_comment = False

    while index < len(source):
        char = source[index]
        next_char = source[index + 1] if index + 1 < len(source) else ""

        if line_comment:
            if char == "\n":
                line_comment = False
            index += 1
            continue

        if block_comment:
            if char == "*" and next_char == "/":
                block_comment = False
                index += 2
            else:
                index += 1
            continue

        if in_string:
            if escape:
                escape = False
            elif char == "\\":
                escape = True
            elif char == quote:
                in_string = False
            index += 1
            continue

        if char == "/" and next_char == "/":
            line_comment = True
            index += 2
            continue
        if char == "/" and next_char == "*":
            block_comment = True
            index += 2
            continue
        if char in {'"', "'"}:
            in_string = True
            quote = char
            index += 1
            continue
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return source[brace_start + 1:index]
        index += 1

    return None


def _parse_safe_tool_allowlist(source: str) -> list[str] | None:
    block = _extract_braced_block(source, re.compile(r"when\s*\(\s*call\.tool\s*\)\s*\{"))
    if block is None:
        return None

    labels = []
    for raw_entry in re.findall(r"^\s*([^\n]+?)\s*->", block, flags=re.MULTILINE):
        stripped = raw_entry.strip()
        if stripped == "else":
            continue
        for raw_label in stripped.split(","):
            label = raw_label.strip()
            match = re.fullmatch(r'"([^"\n]+)"', label)
            if match is None:
                return None
            labels.append(match.group(1))
    return labels


def extract_safe_tool_allowlist(source: str) -> list[str]:
    return _parse_safe_tool_allowlist(source) or []


def has_exact_safe_tool_allowlist(source: str, expected: list[str]) -> bool:
    labels = _parse_safe_tool_allowlist(source)
    return labels == expected


def find_home_exit_forbidden_capabilities(source: str) -> list[str]:
    matches = []
    for name, pattern in HOME_EXIT_FORBIDDEN_PATTERNS.items():
        if pattern.search(source):
            matches.append(name)
    return matches


def _is_ignored_text_path(path: Path, root: Path) -> bool:
    try:
        relative = path.relative_to(root)
    except ValueError:
        relative = path
    return any(part in IGNORED_TEXT_DIRS for part in relative.parts)


def _is_probably_binary(path: Path) -> bool:
    if path.suffix.lower() in BINARY_SUFFIXES:
        return True
    sample = path.read_bytes()[:8192]
    if b"\x00" in sample:
        return True
    try:
        sample.decode("utf-8")
    except UnicodeDecodeError:
        return True
    return False


def iter_repository_text_files(root: Path) -> list[Path]:
    files = []
    for path in root.rglob("*"):
        if not path.is_file() or _is_ignored_text_path(path, root) or _is_probably_binary(path):
            continue
        files.append(path)
    return files


def find_secret_like_tokens(root: Path) -> list[Path]:
    hits = []
    for path in iter_repository_text_files(root):
        if SECRET_RE.search(path.read_text("utf-8", errors="ignore")):
            hits.append(path)
    return hits


def build_checks() -> list[tuple[str, bool]]:
    checks: list[tuple[str, bool]] = []

    def check(name: str, ok: bool) -> None:
        checks.append((name, bool(ok)))

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

    check("version 0.6.5", 'versionCode = 12' in build and 'versionName = "0.6.5"' in build)
    check("arm64 target", 'abiFilters += listOf("arm64-v8a")' in build)
    check("compile target 35", "compileSdk = 35" in build and "targetSdk = 35" in build)
    check("microphone foreground service", "FOREGROUND_SERVICE_MICROPHONE" in manifest and 'android:foregroundServiceType="microphone"' in manifest)
    check("workflow fetches KWS and ASR models", "sherpa-onnx-paraformer-zh-small-2024-03-09" in fetch and "kws-models" in fetch)

    parsed_release_gate_tests = parse_release_gate_tests(release_gate)
    check("release gate exact TESTS list", parsed_release_gate_tests == REQUIRED_V065_TESTS)
    check("release gate uses subprocess execution", has_release_gate_loop_subprocess_run(release_gate))
    check("release gate prints final PASS", 'print("PASS: v0.6.5 release gate")' in release_gate)
    check("v0.6.5 frozen guard delegated", release_gate_delegates_expected_tests(release_gate, REQUIRED_V065_TESTS))

    for path in REQUIRED_V065_TESTS:
        check("required v0.6.5 test " + Path(path).name, exists(path))

    for path in REQUIRED_HISTORICAL_TESTS:
        check("required historical test " + Path(path).name, exists(path))

    for path in REQUIRED_V065_SOURCES:
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
        noise_suppressor_files == ["app/src/main/java/com/lchuang/xiaozhimobile/AudioEnhancementManager.kt"],
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
        'HomeResult(true, "GO_HOME_OK")',
        'HomeResult(false, "GO_HOME_FAILED")',
    ]))
    check("home exit avoids force-stop root shell accessibility", not find_home_exit_forbidden_capabilities(app_exit))

    check("safe tool allowlist retained", has_exact_safe_tool_allowlist(safe_tools, EXPECTED_SAFE_TOOLS))
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

    check("no secret-like sk token", not find_secret_like_tokens(ROOT))
    return checks


def main() -> int:
    checks = build_checks()
    failed = [name for name, ok in checks if not ok]
    for name, ok in checks:
        print(("PASS" if ok else "FAIL") + ": " + name)
    if failed:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
