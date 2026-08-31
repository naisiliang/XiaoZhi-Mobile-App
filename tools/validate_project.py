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
    "tools/test_v065_final_review_fixes.py",
    "tools/test_v065_listening_truth.py",
    "tools/test_v065_adaptive_vad.py",
    "tools/test_v065_noise_suppressor.py",
    "tools/test_v065_release_metadata.py",
    "tools/test_v065_error_recovery.py",
    "tools/test_v065_validator_contract.py",
    "tools/test_v065_apk_validator_contract.py",
]
REQUIRED_HISTORICAL_TESTS = [
    "tools/test_v031_behavior.py",
    "tools/test_v040_voice_flow.py",
    "tools/test_v050_session.py",
    "tools/test_v050_voice_flow.py",
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
    "app/src/main/java/com/lchuang/xiaozhimobile/CommandAudioCaptureFailure.kt",
    "app/src/main/java/com/lchuang/xiaozhimobile/ExecutionFeedbackCoordinator.kt",
    "app/src/main/java/com/lchuang/xiaozhimobile/ExecutionIntentFormatter.kt",
    "app/src/main/java/com/lchuang/xiaozhimobile/TtsProgressRegistry.kt",
    "tools/validate_v065_apk.py",
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


def _is_exact_release_gate_imports(nodes: list[ast.stmt]) -> bool:
    if len(nodes) != 2:
        return False
    pathlib_import, subprocess_import = nodes
    return (
        isinstance(pathlib_import, ast.ImportFrom)
        and pathlib_import.level == 0
        and pathlib_import.module == "pathlib"
        and len(pathlib_import.names) == 1
        and pathlib_import.names[0].name == "Path"
        and pathlib_import.names[0].asname is None
        and isinstance(subprocess_import, ast.Import)
        and len(subprocess_import.names) == 1
        and subprocess_import.names[0].name == "subprocess"
        and subprocess_import.names[0].asname is None
    )


def _is_exact_release_gate_root_assignment(node: ast.stmt) -> bool:
    if not (
        isinstance(node, ast.Assign)
        and node.type_comment is None
        and len(node.targets) == 1
        and isinstance(node.targets[0], ast.Name)
        and node.targets[0].id == "root"
    ):
        return False
    expected = ast.parse("Path(__file__).resolve().parents[1]", mode="eval").body
    return ast.dump(node.value, include_attributes=False) == ast.dump(expected, include_attributes=False)


def _is_exact_release_gate_tests_assignment(node: ast.stmt) -> list[str] | None:
    if not (
        isinstance(node, ast.Assign)
        and node.type_comment is None
        and len(node.targets) == 1
        and isinstance(node.targets[0], ast.Name)
        and node.targets[0].id == "TESTS"
        and isinstance(node.value, ast.List)
    ):
        return None
    tests: list[str] = []
    for item in node.value.elts:
        if not isinstance(item, ast.Constant) or not isinstance(item.value, str):
            return None
        tests.append(item.value)
    return tests


def _is_exact_release_gate_subprocess_run(node: ast.stmt) -> bool:
    if not isinstance(node, ast.Expr) or not isinstance(node.value, ast.Call):
        return False
    call = node.value
    if not (
        isinstance(call.func, ast.Attribute)
        and isinstance(call.func.value, ast.Name)
        and call.func.value.id == "subprocess"
        and call.func.attr == "run"
        and len(call.args) == 1
        and isinstance(call.args[0], ast.List)
        and len(call.args[0].elts) == 2
        and isinstance(call.args[0].elts[0], ast.Constant)
        and call.args[0].elts[0].value == "python"
        and isinstance(call.args[0].elts[1], ast.Name)
        and call.args[0].elts[1].id == "test"
        and len(call.keywords) == 2
        and call.keywords[0].arg == "cwd"
        and call.keywords[1].arg == "check"
    ):
        return False
    keywords = {keyword.arg: keyword.value for keyword in call.keywords}
    return (
        set(keywords) == {"cwd", "check"}
        and isinstance(keywords["cwd"], ast.Name)
        and keywords["cwd"].id == "root"
        and isinstance(keywords["check"], ast.Constant)
        and keywords["check"].value is True
    )


def _is_exact_release_gate_loop_structure(node: ast.stmt) -> bool:
    return (
        isinstance(node, ast.For)
        and isinstance(node.target, ast.Name)
        and node.target.id == "test"
        and isinstance(node.iter, ast.Name)
        and node.iter.id == "TESTS"
        and not node.orelse
        and len(node.body) == 3
        and _is_release_gate_test_exists_check(node.body[0])
        and _is_release_gate_run_print(node.body[1])
        and _is_exact_release_gate_subprocess_run(node.body[2])
    )


def _is_exact_release_gate_pass_print(node: ast.stmt) -> bool:
    return (
        isinstance(node, ast.Expr)
        and isinstance(node.value, ast.Call)
        and isinstance(node.value.func, ast.Name)
        and node.value.func.id == "print"
        and len(node.value.args) == 1
        and not node.value.keywords
        and isinstance(node.value.args[0], ast.Constant)
        and node.value.args[0].value == "PASS: v0.6.5 release gate"
    )


def _parse_exact_release_gate(source: str) -> list[str] | None:
    try:
        module = ast.parse(source)
    except SyntaxError:
        return None
    if module.type_ignores or len(module.body) != 6 or not _is_exact_release_gate_imports(module.body[:2]):
        return None
    if not _is_exact_release_gate_root_assignment(module.body[2]):
        return None
    tests = _is_exact_release_gate_tests_assignment(module.body[3])
    if tests is None:
        return None
    if not _is_exact_release_gate_loop_structure(module.body[4]):
        return None
    if not _is_exact_release_gate_pass_print(module.body[5]):
        return None
    return tests


def _is_tests_name(node: ast.AST) -> bool:
    return isinstance(node, ast.Name) and node.id == "TESTS"


def _tests_assignment_value(node: ast.stmt) -> ast.AST | None:
    if isinstance(node, ast.Assign):
        if any(_is_tests_name(target) for target in node.targets):
            return node.value
        return None
    if isinstance(node, ast.AnnAssign) and _is_tests_name(node.target):
        return node.value
    return None


def _parse_string_list_literal(node: ast.AST | None) -> list[str] | None:
    if not isinstance(node, ast.List):
        return None
    values = []
    for item in node.elts:
        if not isinstance(item, ast.Constant) or not isinstance(item.value, str):
            return None
        values.append(item.value)
    return values


def _target_base_name(node: ast.AST) -> str | None:
    current = node
    while isinstance(current, (ast.Subscript, ast.Attribute)):
        current = current.value
    if isinstance(current, ast.Name):
        return current.id
    return None


def _target_names(node: ast.AST) -> list[str]:
    if isinstance(node, ast.Name):
        return [node.id]
    if isinstance(node, (ast.Tuple, ast.List)):
        names: list[str] = []
        for item in node.elts:
            names.extend(_target_names(item))
        return names
    return []


def _is_direct_tests_alias(node: ast.AST, aliases: set[str]) -> bool:
    return isinstance(node, ast.Name) and node.id in aliases


def _collect_new_tests_aliases(node: ast.AST, aliases: set[str]) -> set[str]:
    new_aliases: set[str] = set()
    for child in ast.walk(node):
        value: ast.AST | None = None
        targets: list[ast.AST] = []
        if isinstance(child, ast.Assign):
            value = child.value
            targets = child.targets
        elif isinstance(child, ast.AnnAssign):
            value = child.value
            targets = [child.target]
        if value is None or not _is_direct_tests_alias(value, aliases):
            continue
        for target in targets:
            new_aliases.update(name for name in _target_names(target) if name not in aliases)
    return new_aliases


def _mutates_tests(node: ast.AST, aliases: set[str]) -> bool:
    for child in ast.walk(node):
        if isinstance(child, (ast.Assign, ast.AnnAssign, ast.AugAssign)):
            targets = child.targets if isinstance(child, ast.Assign) else [child.target]
            if any(_target_base_name(target) in aliases for target in targets):
                return True
        if isinstance(child, ast.Delete) and any(_target_base_name(target) in aliases for target in child.targets):
            return True
        if (
            isinstance(child, ast.Call)
            and isinstance(child.func, ast.Attribute)
            and isinstance(child.func.value, ast.Name)
            and child.func.value.id in aliases
            and child.func.attr in {"append", "clear", "extend", "insert", "pop", "remove", "reverse", "sort"}
        ):
            return True
    return False


def _find_top_level_tests_assignment(module: ast.Module) -> tuple[int, list[str]] | None:
    tests_values: list[tuple[int, list[str]]] = []
    for index, node in enumerate(module.body):
        parsed = _parse_string_list_literal(_tests_assignment_value(node))
        if parsed is not None:
            tests_values.append((index, parsed))
            continue
        if _tests_assignment_value(node) is not None:
            return None
    if len(tests_values) != 1:
        return None
    return tests_values[0]


def parse_release_gate_tests(source: str) -> list[str] | None:
    return _parse_exact_release_gate(source)


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


def _is_release_gate_test_exists_check(node: ast.stmt) -> bool:
    if not isinstance(node, ast.If) or node.orelse or len(node.body) != 1:
        return False
    condition = node.test
    if not (
        isinstance(condition, ast.UnaryOp)
        and isinstance(condition.op, ast.Not)
        and isinstance(condition.operand, ast.Call)
        and not condition.operand.args
        and not condition.operand.keywords
    ):
        return False
    exists_call = condition.operand
    if not (
        isinstance(exists_call.func, ast.Attribute)
        and exists_call.func.attr == "exists"
        and isinstance(exists_call.func.value, ast.BinOp)
        and isinstance(exists_call.func.value.op, ast.Div)
        and isinstance(exists_call.func.value.left, ast.Name)
        and exists_call.func.value.left.id == "root"
        and isinstance(exists_call.func.value.right, ast.Name)
        and exists_call.func.value.right.id == "test"
    ):
        return False
    guarded_statement = node.body[0]
    if not isinstance(guarded_statement, ast.Raise) or guarded_statement.exc is None:
        return False
    exception = guarded_statement.exc
    if not (
        isinstance(exception, ast.Call)
        and isinstance(exception.func, ast.Name)
        and exception.func.id == "SystemExit"
        and len(exception.args) == 1
        and not exception.keywords
    ):
        return False
    message = exception.args[0]
    return (
        isinstance(message, ast.JoinedStr)
        and len(message.values) == 2
        and isinstance(message.values[0], ast.Constant)
        and message.values[0].value == "release gate test missing: "
        and isinstance(message.values[1], ast.FormattedValue)
        and isinstance(message.values[1].value, ast.Name)
        and message.values[1].value.id == "test"
        and message.values[1].format_spec is None
    )


def _is_release_gate_run_print(node: ast.stmt) -> bool:
    if not isinstance(node, ast.Expr) or not isinstance(node.value, ast.Call):
        return False
    call = node.value
    if not isinstance(call.func, ast.Name) or call.func.id != "print":
        return False
    if len(call.args) != 1 or call.keywords:
        return False
    message = call.args[0]
    return (
        isinstance(message, ast.JoinedStr)
        and len(message.values) == 2
        and isinstance(message.values[0], ast.Constant)
        and message.values[0].value == "RUN: "
        and isinstance(message.values[1], ast.FormattedValue)
        and isinstance(message.values[1].value, ast.Name)
        and message.values[1].value.id == "test"
        and message.values[1].format_spec is None
    )


def _is_exact_direct_python_test_run_statement(node: ast.stmt) -> bool:
    if not _is_direct_python_test_run_statement(node):
        return False
    call = node.value
    keywords = {item.arg: item.value for item in call.keywords}
    return len(call.keywords) == 2 and set(keywords) == {"cwd", "check"}


def _is_release_gate_loop_header(node: ast.AST) -> bool:
    return (
        isinstance(node, ast.For)
        and isinstance(node.target, ast.Name)
        and node.target.id == "test"
        and isinstance(node.iter, ast.Name)
        and node.iter.id == "TESTS"
    )


def _is_exact_release_gate_loop(node: ast.AST) -> bool:
    return (
        _is_release_gate_loop_header(node)
        and not node.orelse
        and len(node.body) == 3
        and _is_release_gate_test_exists_check(node.body[0])
        and _is_release_gate_run_print(node.body[1])
        and _is_exact_direct_python_test_run_statement(node.body[2])
    )


def _contains_release_gate_loop_exit(node: ast.AST) -> bool:
    return any(isinstance(child, (ast.Break, ast.Continue, ast.Return, ast.Try)) for child in ast.walk(node))


def _static_string(node: ast.AST) -> str | None:
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        return node.value
    if isinstance(node, ast.BinOp) and isinstance(node.op, ast.Add):
        left = _static_string(node.left)
        right = _static_string(node.right)
        if left is not None and right is not None:
            return left + right
    return None


NAMESPACE_FACTORY_NAMES = {"globals", "locals", "vars"}
SETATTR_NAMES = {"setattr"}


def _is_builtin_namespace_container(node: ast.AST) -> bool:
    if isinstance(node, ast.Name):
        return node.id in {"builtins", "__builtins__"}
    return (
        isinstance(node, ast.Attribute)
        and node.attr == "__dict__"
        and isinstance(node.value, ast.Name)
        and node.value.id in {"builtins", "__builtins__"}
    )


def _is_named_builtin_reference(
    node: ast.AST,
    names: set[str],
    aliases: set[str],
) -> bool:
    if isinstance(node, ast.Name):
        return node.id in aliases
    if isinstance(node, ast.Attribute):
        return node.attr in names
    if isinstance(node, ast.Subscript) and _is_builtin_namespace_container(node.value):
        return _static_string(node.slice) in names
    if (
        isinstance(node, ast.Call)
        and isinstance(node.func, ast.Name)
        and node.func.id == "getattr"
        and len(node.args) >= 2
    ):
        return _static_string(node.args[1]) in names
    if (
        isinstance(node, ast.Call)
        and isinstance(node.func, ast.Attribute)
        and node.func.attr == "get"
        and _is_builtin_namespace_container(node.func.value)
        and node.args
    ):
        return _static_string(node.args[0]) in names
    return False


def _is_namespace_factory_reference(node: ast.AST, aliases: set[str]) -> bool:
    return _is_named_builtin_reference(node, NAMESPACE_FACTORY_NAMES, aliases)


def _is_setattr_reference(node: ast.AST, aliases: set[str]) -> bool:
    return _is_named_builtin_reference(node, SETATTR_NAMES, aliases) or (
        isinstance(node, ast.Attribute) and node.attr == "__setattr__"
    )


def _is_namespace_reference(
    node: ast.AST,
    namespace_aliases: set[str],
    factory_aliases: set[str],
) -> bool:
    return (
        isinstance(node, ast.Name)
        and node.id in namespace_aliases
    ) or (
        isinstance(node, ast.Call)
        and _is_namespace_factory_reference(node.func, factory_aliases)
        and not node.args
        and not node.keywords
    )


def _release_gate_aliases(node: ast.AST) -> tuple[set[str], set[str], set[str]]:
    namespace_aliases: set[str] = set()
    namespace_factory_aliases = set(NAMESPACE_FACTORY_NAMES)
    setattr_aliases = set(SETATTR_NAMES)
    changed = True
    while changed:
        changed = False
        for child in ast.walk(node):
            if isinstance(child, ast.ImportFrom) and child.module == "builtins":
                for imported in child.names:
                    local_name = imported.asname or imported.name
                    if imported.name in NAMESPACE_FACTORY_NAMES and local_name not in namespace_factory_aliases:
                        namespace_factory_aliases.add(local_name)
                        changed = True
                    if imported.name in SETATTR_NAMES and local_name not in setattr_aliases:
                        setattr_aliases.add(local_name)
                        changed = True

            if isinstance(child, ast.Assign):
                value = child.value
                targets = child.targets
            elif isinstance(child, ast.AnnAssign):
                value = child.value
                targets = [child.target]
            else:
                continue

            if _is_namespace_factory_reference(value, namespace_factory_aliases):
                for target in targets:
                    for name in _target_names(target):
                        if name not in namespace_factory_aliases:
                            namespace_factory_aliases.add(name)
                            changed = True
            if _is_namespace_reference(value, namespace_aliases, namespace_factory_aliases):
                for target in targets:
                    for name in _target_names(target):
                        if name not in namespace_aliases:
                            namespace_aliases.add(name)
                            changed = True
            if _is_setattr_reference(value, setattr_aliases):
                for target in targets:
                    for name in _target_names(target):
                        if name not in setattr_aliases:
                            setattr_aliases.add(name)
                            changed = True
    return namespace_aliases, namespace_factory_aliases, setattr_aliases


def _is_dynamic_namespace_mutation(
    node: ast.AST,
    namespace_aliases: set[str],
    namespace_factory_aliases: set[str],
    setattr_aliases: set[str],
) -> bool:
    if isinstance(node, ast.Subscript) and isinstance(node.ctx, (ast.Store, ast.Del)):
        return _is_namespace_reference(node.value, namespace_aliases, namespace_factory_aliases)
    if not isinstance(node, ast.Call):
        return False
    if _is_setattr_reference(node.func, setattr_aliases):
        return True
    return (
        isinstance(node.func, ast.Attribute)
        and node.func.attr in {"clear", "update", "__setitem__", "__delitem__", "pop", "setdefault"}
        and _is_namespace_reference(node.func.value, namespace_aliases, namespace_factory_aliases)
    )


def _has_release_gate_dynamic_bypass(module: ast.Module) -> bool:
    namespace_aliases, namespace_factory_aliases, setattr_aliases = _release_gate_aliases(module)
    for node in ast.walk(module):
        if isinstance(node, ast.Call):
            if isinstance(node.func, ast.Name) and node.func.id in {"eval", "exec"}:
                return True
            if _is_namespace_factory_reference(node.func, namespace_factory_aliases):
                return True
        if _is_dynamic_namespace_mutation(
            node,
            namespace_aliases,
            namespace_factory_aliases,
            setattr_aliases,
        ):
            return True
    return False


def _loop_target_key_may_be_mutated(node: ast.AST, target_name: str) -> bool:
    key = _static_string(node)
    return key is None or key == target_name


def _namespace_subscript_may_mutate_loop_target(
    node: ast.AST,
    target_name: str,
    namespace_aliases: set[str],
    namespace_factory_aliases: set[str],
) -> bool:
    if not isinstance(node, ast.Subscript) or not _is_namespace_reference(
        node.value,
        namespace_aliases,
        namespace_factory_aliases,
    ):
        return False
    return _loop_target_key_may_be_mutated(node.slice, target_name)


def _namespace_mutator_may_mutate_loop_target(
    node: ast.Call,
    target_name: str,
    namespace_aliases: set[str],
    namespace_factory_aliases: set[str],
) -> bool:
    if not isinstance(node.func, ast.Attribute) or not _is_namespace_reference(
        node.func.value,
        namespace_aliases,
        namespace_factory_aliases,
    ):
        return False
    if node.func.attr in {"clear", "update"}:
        return True
    if node.func.attr in {"__setitem__", "__delitem__", "pop", "setdefault"}:
        return bool(node.args) and _loop_target_key_may_be_mutated(node.args[0], target_name)
    return False


def _setattr_may_mutate_loop_target(
    node: ast.Call,
    target_name: str,
    setattr_aliases: set[str],
) -> bool:
    func = node.func
    if isinstance(func, ast.Name) and func.id in setattr_aliases:
        key_index = 1
    elif isinstance(func, ast.Attribute) and func.attr in {"setattr", "__setattr__"}:
        key_index = 1 if func.attr == "setattr" else 0
    else:
        return False
    return len(node.args) > key_index and _loop_target_key_may_be_mutated(node.args[key_index], target_name)


def _contains_release_gate_loop_target_mutation(
    node: ast.AST,
    target_name: str,
    known_namespace_aliases: set[str] | None = None,
    known_namespace_factory_aliases: set[str] | None = None,
    known_setattr_aliases: set[str] | None = None,
) -> bool:
    namespace_aliases, namespace_factory_aliases, setattr_aliases = _release_gate_aliases(node)
    namespace_aliases.update(known_namespace_aliases or set())
    namespace_factory_aliases.update(known_namespace_factory_aliases or set())
    setattr_aliases.update(known_setattr_aliases or set())
    loop_target_nodes = {id(child) for child in ast.walk(node.target)} if isinstance(node, ast.For) else set()
    for child in ast.walk(node):
        if id(child) in loop_target_nodes:
            continue
        if isinstance(child, ast.Name) and child.id == target_name and isinstance(child.ctx, (ast.Store, ast.Del)):
            return True
        if isinstance(child, (ast.Attribute, ast.Subscript)) and _target_base_name(child) == target_name:
            if isinstance(child.ctx, (ast.Store, ast.Del)):
                return True
        if isinstance(child, (ast.Assign, ast.AnnAssign, ast.AugAssign)):
            targets = child.targets if isinstance(child, ast.Assign) else [child.target]
            if any(
                _namespace_subscript_may_mutate_loop_target(
                    target,
                    target_name,
                    namespace_aliases,
                    namespace_factory_aliases,
                )
                for target in targets
            ):
                return True
        if isinstance(child, ast.Delete) and any(
            _namespace_subscript_may_mutate_loop_target(
                target,
                target_name,
                namespace_aliases,
                namespace_factory_aliases,
            )
            for target in child.targets
        ):
            return True
        if isinstance(child, ast.Call):
            if _namespace_mutator_may_mutate_loop_target(
                child,
                target_name,
                namespace_aliases,
                namespace_factory_aliases,
            ):
                return True
            if _setattr_may_mutate_loop_target(child, target_name, setattr_aliases):
                return True
        if isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef, ast.Lambda)):
            if any(argument.arg == target_name for argument in child.args.posonlyargs + child.args.args + child.args.kwonlyargs):
                return True
        if isinstance(child, ast.ExceptHandler) and child.name == target_name:
            return True
        if isinstance(child, ast.alias) and child.asname == target_name:
            return True
        if isinstance(child, (ast.Global, ast.Nonlocal)) and target_name in child.names:
            return True
    return False


def has_release_gate_loop_subprocess_run(source: str) -> bool:
    return _parse_exact_release_gate(source) is not None


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


def _extract_safe_tool_plan_block(source: str) -> str | None:
    return _extract_braced_block(
        source,
        re.compile(r"fun\s+plan\s*\(\s*call\s*:\s*AiToolCall\s*\)\s*:\s*SafeToolPlan\s*=\s*when\s*\(\s*call\.tool\s*\)\s*\{"),
    )


def _extract_when_entries(block: str) -> list[tuple[str, str]]:
    matches = list(re.finditer(r"^\s*([^\n]+?)\s*->", block, flags=re.MULTILINE))
    entries: list[tuple[str, str]] = []
    for index, match in enumerate(matches):
        label = match.group(1).strip()
        body_start = match.end()
        body_end = matches[index + 1].start() if index + 1 < len(matches) else len(block)
        entries.append((label, block[body_start:body_end].strip()))
    return entries


def _normalize_kotlin_fragment(source: str) -> str:
    return re.sub(r"\s+", "", source)


SAFE_TOOL_REJECTION_BODY = 'rejected(ToolExecutionResult(false, "该操作不在安全工具白名单中", "REJECTED_NOT_ALLOWED"))'
SAFE_DEVICE_ACTIONS = {
    "OpenApp",
    "Navigate",
    "SearchNearby",
    "OpenWeb",
    "MediaPlay",
    "MediaPause",
    "MediaNext",
    "MediaPrevious",
    "MediaVolumeUp",
    "MediaVolumeDown",
    "SetMediaVolume",
    "SetFlashlight",
}


def _extract_call_arguments(source: str, pattern: str) -> list[str] | None:
    arguments: list[str] = []
    for match in re.finditer(pattern, source):
        open_index = source.find("(", match.start())
        if open_index == -1:
            return None
        close_index = _matching_delimiter(source, open_index, "(", ")")
        if close_index is None:
            return None
        arguments.append(source[open_index + 1:close_index].strip())
    return arguments


def _is_safe_tool_allowed_argument(argument: str) -> bool:
    match = re.fullmatch(r"DeviceAction\.([A-Za-z_][A-Za-z0-9_]*)(?:\s*\(.*\))?", argument, flags=re.S)
    return match is not None and match.group(1) in SAFE_DEVICE_ACTIONS


def _parse_safe_tool_allowlist(source: str) -> tuple[list[str], list[str]] | None:
    block = _extract_safe_tool_plan_block(source)
    if block is None:
        return None

    labels = []
    else_bodies: list[str] = []
    for raw_entry, body in _extract_when_entries(block):
        stripped = raw_entry.strip()
        if stripped == "else":
            else_bodies.append(body)
            continue
        for raw_label in stripped.split(","):
            label = raw_label.strip()
            match = re.fullmatch(r'"([^"\n]+)"', label)
            if match is None:
                return None
            labels.append(match.group(1))
    return labels, else_bodies


def extract_safe_tool_allowlist(source: str) -> list[str]:
    parsed = _parse_safe_tool_allowlist(source)
    return parsed[0] if parsed is not None else []


def has_safe_tool_terminal_else_rejection(source: str) -> bool:
    block = _extract_safe_tool_plan_block(source)
    if block is None:
        return False
    pattern = re.compile(
        r"if\s*\(\s*terminal\s*\)\s*.*?\s*else\s*"
        + re.escape(SAFE_TOOL_REJECTION_BODY)
        + r"\s*(?:[}\n;]|$)",
        re.S,
    )
    search_from = 0
    while True:
        match = re.search(r"if\s*\(\s*terminal\s*\)", block[search_from:])
        if match is None:
            return True
        start = search_from + match.start()
        if not pattern.match(block[start:]):
            return False
        search_from = start + len("if (terminal)")


def has_single_safe_tool_rejecting_else(source: str) -> bool:
    parsed = _parse_safe_tool_allowlist(source)
    if parsed is None:
        return False
    _, else_bodies = parsed
    return len(else_bodies) == 1 and _normalize_kotlin_fragment(else_bodies[0]) == _normalize_kotlin_fragment(
        SAFE_TOOL_REJECTION_BODY
    )


def _matching_delimiter(source: str, start: int, opening: str, closing: str) -> int | None:
    depth = 0
    in_string = False
    escape = False
    quote = ""
    for index in range(start, len(source)):
        char = source[index]
        if in_string:
            if escape:
                escape = False
            elif char == "\\":
                escape = True
            elif char == quote:
                in_string = False
            continue
        if char in {'"', "'"}:
            in_string = True
            quote = char
        elif char == opening:
            depth += 1
        elif char == closing:
            depth -= 1
            if depth == 0:
                return index
    return None


def _has_conditional_fallthrough(block: str) -> bool:
    for match in re.finditer(r"\bif\s*\(", block):
        condition_start = block.find("(", match.start())
        condition_end = _matching_delimiter(block, condition_start, "(", ")")
        if condition_end is None:
            return True
        remainder = block[condition_end + 1:]
        depths = {"(": 0, "[": 0, "{": 0}
        in_string = False
        escape = False
        quote = ""
        index = 0
        while index < len(remainder):
            char = remainder[index]
            if in_string:
                if escape:
                    escape = False
                elif char == "\\":
                    escape = True
                elif char == quote:
                    in_string = False
                index += 1
                continue
            if char in {'"', "'"}:
                in_string = True
                quote = char
                index += 1
                continue
            if char in depths:
                depths[char] += 1
                index += 1
                continue
            if char in ")]}":
                opening = {')': '(', ']': '[', '}': '{'}[char]
                depths[opening] -= 1
                index += 1
                continue
            if not any(depths.values()):
                if re.match(r"else\b", remainder[index:]):
                    break
                if (index == 0 or remainder[index - 1] == "\n") and re.match(
                    r"\s*(?:\"[^\"\n]+\"|else)\s*->", remainder[index:]
                ):
                    return True
            index += 1
        else:
            return True
    return False


def has_exact_safe_tool_allowlist(source: str, expected: list[str]) -> bool:
    parsed = _parse_safe_tool_allowlist(source)
    if parsed is None:
        return False
    block = _extract_safe_tool_plan_block(source)
    if block is None:
        return False
    allowed_arguments = _extract_call_arguments(block, r"\ballowed\s*\(")
    if not allowed_arguments:
        return False
    if any(not _is_safe_tool_allowed_argument(argument) for argument in allowed_arguments):
        return False
    if _has_conditional_fallthrough(block):
        return False
    labels, _ = parsed
    return (
        labels == expected
        and has_single_safe_tool_rejecting_else(source)
        and has_safe_tool_terminal_else_rejection(source)
    )


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
        "- name: Upload APK",
        "- name: Download exact APK artifact",
        "- name: Verify downloaded APK",
        "- name: Upload APK verification report",
    ]
    check("workflow release-gate order", appears_in_order(workflow, workflow_steps))
    check("workflow historical test commands", appears_in_order(workflow, [
        "python3 tools/test_v031_behavior.py",
        "python3 tools/test_v040_voice_flow.py",
        "python3 tools/test_v050_session.py",
        "python3 tools/test_v050_voice_flow.py",
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
    check("workflow downloads exact APK artifact", all(token in workflow for token in [
        "name: XiaoZhi-Mobile-APK",
        "path: artifact-verification",
        "tools/validate_v065_apk.py",
        "--artifact-dir artifact-verification",
        '--report "$RUNNER_TEMP/xiaozhi-v065-apk-verification.json"',
    ]))
    check("workflow uploads verification report", all(token in workflow for token in [
        "name: XiaoZhi-Mobile-APK-verification",
        "path: ${{ runner.temp }}/xiaozhi-v065-apk-verification.json",
        "if-no-files-found: error",
    ]))

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
