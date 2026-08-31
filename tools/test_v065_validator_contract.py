from __future__ import annotations

import contextlib
import importlib.util
import io
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VALIDATOR_PATH = ROOT / "tools/validate_project.py"
SAFE_TOOL_PATH = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/SafeToolExecutor.kt"
RELEASE_GATE_PATH = ROOT / "tools/test_v065_release_gate.py"
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
EXPECTED_RELEASE_GATE_TESTS = [
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
    "tools/test_v065_validator_contract.py",
    "tools/test_v065_apk_validator_contract.py",
]
failures: list[str] = []


def render_tests(tests: list[str]) -> str:
    return "\n".join(f'    "{test}",' for test in tests)


def render_safe_tool_when(labels: list[str], else_body: str) -> str:
    branches = [f'        "{label}" -> allowed(DeviceAction.MediaPlay)' for label in labels]
    branches.append(f"        else -> {else_body}")
    return "\n".join(branches)


def expect(name: str, ok: bool, detail: str) -> None:
    if ok:
        print(f"PASS: {name}")
        return
    failures.append(f"FAIL: {name}: {detail}")


def load_validator_module():
    spec = importlib.util.spec_from_file_location("validate_project", VALIDATOR_PATH)
    module = importlib.util.module_from_spec(spec)
    with contextlib.redirect_stdout(io.StringIO()):
        spec.loader.exec_module(module)
    return module


validator = load_validator_module()
token_fixture = "sk-" + "abcdefghijklmnopqrstuvwxyz123456"


for forbidden in ("su", "shell", "forceStopPackage", "killBackgroundProcesses"):
    matches = validator.find_home_exit_forbidden_capabilities(f'val reviewFinding = "{forbidden}"')
    assert forbidden in matches, f"missing home exit forbidden capability detection for {forbidden}"
print("PASS: home exit forbidden capability matcher catches review cases")


with tempfile.TemporaryDirectory() as tmp:
    repo = Path(tmp)
    (repo / ".git").mkdir()
    (repo / "build").mkdir()
    (repo / "__pycache__").mkdir()
    (repo / "notes.custom").write_text(token_fixture, encoding="utf-8")
    (repo / ".git/ignored.custom").write_text(token_fixture, encoding="utf-8")
    (repo / "build/ignored.custom").write_text(token_fixture, encoding="utf-8")
    (repo / "__pycache__/ignored.custom").write_text(token_fixture, encoding="utf-8")
    (repo / "binary.bin").write_bytes(b"\x00" + token_fixture.encode("utf-8"))

    hits = validator.find_secret_like_tokens(repo)
    hit_paths = {path.relative_to(repo).as_posix() for path in hits}
    assert "notes.custom" in hit_paths, "text file without approved extension should still be scanned"
    assert ".git/ignored.custom" not in hit_paths, ".git content must be ignored"
    assert "build/ignored.custom" not in hit_paths, "generated build content must be ignored"
    assert "__pycache__/ignored.custom" not in hit_paths, "__pycache__ content must be ignored"
    assert "binary.bin" not in hit_paths, "binary file should not be treated as text"
print("PASS: secret scan covers all repository text files with generated/binary exclusions")


safe_tools_source = SAFE_TOOL_PATH.read_text(encoding="utf-8")
assert validator.extract_safe_tool_allowlist(safe_tools_source) == EXPECTED_SAFE_TOOLS
mutated_safe_tools = safe_tools_source.replace(
    "        else -> rejected(",
    '        "go_home" -> allowed(DeviceAction.GoHome)\n'
    "        else -> rejected(",
    1,
)
assert mutated_safe_tools != safe_tools_source, "safe tool mutation did not apply"
assert validator.extract_safe_tool_allowlist(mutated_safe_tools) == EXPECTED_SAFE_TOOLS + ["go_home"]
assert not validator.has_exact_safe_tool_allowlist(mutated_safe_tools, EXPECTED_SAFE_TOOLS)
named_branch_safe_tools = safe_tools_source.replace(
    "class SafeToolExecutor(private val deviceActionExecutor: DeviceActionExecutor) {\n",
    'class SafeToolExecutor(private val deviceActionExecutor: DeviceActionExecutor) {\n    private companion object {\n        const val EXTRA_TOOL = "go_home"\n    }\n',
    1,
).replace(
    "        else -> rejected(",
    '        EXTRA_TOOL -> allowed(DeviceAction.GoHome(sourceApp = null))\n'
    "        else -> rejected(",
    1,
)
assert named_branch_safe_tools != safe_tools_source, "named branch safe tool mutation did not apply"
assert not validator.has_exact_safe_tool_allowlist(named_branch_safe_tools, EXPECTED_SAFE_TOOLS)
computed_allowed_action_source = safe_tools_source.replace(
    '        "media_play" -> allowed(DeviceAction.MediaPlay)\n',
    '        "media_play" -> {\n'
    '            val action = DeviceAction.MediaPlay\n'
    "            allowed(action)\n"
    "        }\n",
    1,
)
assert computed_allowed_action_source != safe_tools_source, "computed allowed action mutation did not apply"
print("PASS: safe tool allowlist extraction rejects any added when(call.tool) branch")


release_gate_source = RELEASE_GATE_PATH.read_text(encoding="utf-8")
assert validator.parse_release_gate_tests(release_gate_source) == EXPECTED_RELEASE_GATE_TESTS
assert validator.has_release_gate_loop_subprocess_run(release_gate_source)
assert validator.release_gate_delegates_expected_tests(release_gate_source, EXPECTED_RELEASE_GATE_TESTS)

comment_only_frozen_guard = release_gate_source.replace(
    '    "tools/test_v065_frozen_baseline.py",\n',
    "",
    1,
).replace(
    "root = Path(__file__).resolve().parents[1]\n",
    'root = Path(__file__).resolve().parents[1]\n# tools/test_v065_frozen_baseline.py stays only in this comment now\n',
    1,
)
broken_loop_source = """from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
TESTS = [
    "tools/test_v065_frozen_baseline.py",
    "tools/test_v065_device_command_plan.py",
]

for test in TESTS:
    print(f"RUN: {test}")

subprocess.run(["python", test], cwd=root, check=True)
"""
unreachable_nested_run_source = """from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
TESTS = [
    "tools/test_v065_frozen_baseline.py",
    "tools/test_v065_device_command_plan.py",
]

for test in TESTS:
    print(f"RUN: {test}")
    if False:
        subprocess.run(["python", test], cwd=root, check=True)
"""
globals_rebinding_source = f"""from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
TESTS = [
{render_tests(EXPECTED_RELEASE_GATE_TESTS)}
]
globals()["TEST" + "S"] = []

for test in TESTS:
    subprocess.run(["python", test], cwd=root, check=True)
"""
loop_target_reassignment_source = f"""from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
TESTS = [
{render_tests(EXPECTED_RELEASE_GATE_TESTS)}
]

for test in TESTS:
    test = "tools/test_v065_validator_contract.py"
    subprocess.run(["python", test], cwd=root, check=True)
"""
vars_loop_target_reassignment_source = f"""from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
TESTS = [
{render_tests(EXPECTED_RELEASE_GATE_TESTS)}
]

for test in TESTS:
    vars()["test"] = "tools/test_v065_validator_contract.py"
    subprocess.run(["python", test], cwd=root, check=True)
"""
aliased_vars_loop_target_reassignment_source = f"""from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
TESTS = [
{render_tests(EXPECTED_RELEASE_GATE_TESTS)}
]

for test in TESTS:
    namespace = vars()
    namespace["test"] = "tools/test_v065_validator_contract.py"
    subprocess.run(["python", test], cwd=root, check=True)
"""
globals_loop_target_reassignment_source = f"""from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
TESTS = [
{render_tests(EXPECTED_RELEASE_GATE_TESTS)}
]

for test in TESTS:
    globals()["test"] = "tools/test_v065_validator_contract.py"
    subprocess.run(["python", test], cwd=root, check=True)
"""
locals_loop_target_reassignment_source = f"""from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
TESTS = [
{render_tests(EXPECTED_RELEASE_GATE_TESTS)}
]

for test in TESTS:
    locals()["test"] = "tools/test_v065_validator_contract.py"
    subprocess.run(["python", test], cwd=root, check=True)
"""
setattr_loop_target_reassignment_source = f"""from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
TESTS = [
{render_tests(EXPECTED_RELEASE_GATE_TESTS)}
]

for test in TESTS:
    setattr(sys.modules[__name__], "test", "tools/test_v065_validator_contract.py")
    subprocess.run(["python", test], cwd=root, check=True)
"""
setitem_loop_target_reassignment_source = f"""from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
TESTS = [
{render_tests(EXPECTED_RELEASE_GATE_TESTS)}
]

for test in TESTS:
    globals().__setitem__("test", "tools/test_v065_validator_contract.py")
    subprocess.run(["python", test], cwd=root, check=True)
"""
external_namespace_alias_loop_target_reassignment_source = f"""from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
TESTS = [
{render_tests(EXPECTED_RELEASE_GATE_TESTS)}
]
namespace = vars()

for test in TESTS:
    namespace["test"] = "tools/test_v065_validator_contract.py"
    subprocess.run(["python", test], cwd=root, check=True)
"""
vars_callable_alias_chain_loop_target_reassignment_source = f"""from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
TESTS = [
{render_tests(EXPECTED_RELEASE_GATE_TESTS)}
]
factory = vars
factory_alias = factory

for test in TESTS:
    namespace = factory_alias()
    namespace["test"] = "tools/test_v065_validator_contract.py"
    subprocess.run(["python", test], cwd=root, check=True)
"""
globals_callable_alias_chain_loop_target_reassignment_source = f"""from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
TESTS = [
{render_tests(EXPECTED_RELEASE_GATE_TESTS)}
]
factory = globals
factory_alias = factory

for test in TESTS:
    namespace = factory_alias()
    namespace["test"] = "tools/test_v065_validator_contract.py"
    subprocess.run(["python", test], cwd=root, check=True)
"""
locals_callable_alias_chain_loop_target_reassignment_source = f"""from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
TESTS = [
{render_tests(EXPECTED_RELEASE_GATE_TESTS)}
]
factory = locals
factory_alias = factory

for test in TESTS:
    namespace = factory_alias()
    namespace["test"] = "tools/test_v065_validator_contract.py"
    subprocess.run(["python", test], cwd=root, check=True)
"""
builtin_attribute_callable_alias_loop_target_reassignment_source = f"""from pathlib import Path
import builtins
import subprocess

root = Path(__file__).resolve().parents[1]
TESTS = [
{render_tests(EXPECTED_RELEASE_GATE_TESTS)}
]
factory = builtins.vars

for test in TESTS:
    factory()["test"] = "tools/test_v065_validator_contract.py"
    subprocess.run(["python", test], cwd=root, check=True)
"""
builtin_import_callable_alias_loop_target_reassignment_source = f"""from pathlib import Path
from builtins import globals as factory
import subprocess

root = Path(__file__).resolve().parents[1]
TESTS = [
{render_tests(EXPECTED_RELEASE_GATE_TESTS)}
]

for test in TESTS:
    factory()["test"] = "tools/test_v065_validator_contract.py"
    subprocess.run(["python", test], cwd=root, check=True)
"""
builtin_subscript_callable_alias_loop_target_reassignment_source = f"""from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
TESTS = [
{render_tests(EXPECTED_RELEASE_GATE_TESTS)}
]
factory = __builtins__["vars"]

for test in TESTS:
    factory()["test"] = "tools/test_v065_validator_contract.py"
    subprocess.run(["python", test], cwd=root, check=True)
"""
builtin_getattr_callable_alias_loop_target_reassignment_source = f"""from pathlib import Path
import builtins
import subprocess

root = Path(__file__).resolve().parents[1]
TESTS = [
{render_tests(EXPECTED_RELEASE_GATE_TESTS)}
]
factory = getattr(builtins, "locals")

for test in TESTS:
    factory()["test"] = "tools/test_v065_validator_contract.py"
    subprocess.run(["python", test], cwd=root, check=True)
"""
assert not validator.release_gate_delegates_expected_tests(comment_only_frozen_guard, EXPECTED_RELEASE_GATE_TESTS)
assert not validator.has_release_gate_loop_subprocess_run(broken_loop_source)
assert not validator.has_release_gate_loop_subprocess_run(unreachable_nested_run_source)
print("PASS: release gate structural checks enforce delegated frozen guard and in-loop subprocess execution")


try_wrapped_run_source = f"""from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
TESTS = [
{render_tests(EXPECTED_RELEASE_GATE_TESTS)}
]

for test in TESTS:
    print(f"RUN: {{test}}")
    try:
        subprocess.run(["python", test], cwd=root, check=True)
    except subprocess.CalledProcessError:
        raise
"""
duplicate_and_cleared_tests_source = f"""from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
TESTS = [
{render_tests(EXPECTED_RELEASE_GATE_TESTS)}
]
TESTS = list(TESTS)
TESTS.clear()

for test in TESTS:
    subprocess.run(["python", test], cwd=root, check=True)
"""
alias_cleared_tests_source = f"""from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
TESTS = [
{render_tests(EXPECTED_RELEASE_GATE_TESTS)}
]
alias = TESTS
alias.clear()

for test in TESTS:
    subprocess.run(["python", test], cwd=root, check=True)
"""
alias_or_cleared_tests_source = f"""from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
TESTS = [
{render_tests(EXPECTED_RELEASE_GATE_TESTS)}
]
alias = TESTS or []
alias.clear()

for test in TESTS:
    subprocess.run(["python", test], cwd=root, check=True)
"""
slice_cleared_tests_source = f"""from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
TESTS = [
{render_tests(EXPECTED_RELEASE_GATE_TESTS)}
]
TESTS[:] = []

for test in TESTS:
    subprocess.run(["python", test], cwd=root, check=True)
"""
break_before_run_source = f"""from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
TESTS = [
{render_tests(EXPECTED_RELEASE_GATE_TESTS)}
]

for test in TESTS:
    if test.endswith("baseline.py"):
        break
    subprocess.run(["python", test], cwd=root, check=True)
"""
break_after_run_source = f"""from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
TESTS = [
{render_tests(EXPECTED_RELEASE_GATE_TESTS)}
]

for test in TESTS:
    subprocess.run(["python", test], cwd=root, check=True)
    break
"""
continue_after_run_source = f"""from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
TESTS = [
{render_tests(EXPECTED_RELEASE_GATE_TESTS)}
]

for test in TESTS:
    subprocess.run(["python", test], cwd=root, check=True)
    continue
"""
return_after_run_source = f"""from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
TESTS = [
{render_tests(EXPECTED_RELEASE_GATE_TESTS)}
]

for test in TESTS:
    subprocess.run(["python", test], cwd=root, check=True)
    return
"""
outer_try_except_pass_source = f"""from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
TESTS = [
{render_tests(EXPECTED_RELEASE_GATE_TESTS)}
]

try:
    for test in TESTS:
        subprocess.run(["python", test], cwd=root, check=True)
except Exception:
    pass
"""
terminal_else_go_home_source = safe_tools_source.replace(
    '        "media_play" -> allowed(DeviceAction.MediaPlay)\n',
    '        "media_play" -> if (terminal) allowed(DeviceAction.MediaPlay) else allowed(DeviceAction.GoHome(sourceApp = null))\n',
    1,
)
assert terminal_else_go_home_source != safe_tools_source, "terminal safe tool mutation did not apply"
terminal_else_rejection_then_go_home_source = safe_tools_source.replace(
    '        "media_play" -> allowed(DeviceAction.MediaPlay)\n',
    '        "media_play" -> if (terminal) allowed(DeviceAction.MediaPlay) else rejected(ToolExecutionResult(false, "该操作不在安全工具白名单中", "REJECTED_NOT_ALLOWED")).let { allowed(DeviceAction.GoHome(sourceApp = null)) }\n',
    1,
)
assert terminal_else_rejection_then_go_home_source != safe_tools_source, "terminal safe tool rejection-then-go-home mutation did not apply"
conditional_go_home_source = safe_tools_source.replace(
    '        "media_play" -> allowed(DeviceAction.MediaPlay)\n',
    '        "media_play" -> if (call.args["unsafe"] == true) allowed(DeviceAction.GoHome(sourceApp = null)) else allowed(DeviceAction.MediaPlay)\n',
    1,
)
assert conditional_go_home_source != safe_tools_source, "conditional safe tool mutation did not apply"
conditional_fallthrough_source = safe_tools_source.replace(
    '        "media_play" -> allowed(DeviceAction.MediaPlay)\n',
    '        "media_play" -> if (call.args["unsafe"] == true) allowed(DeviceAction.MediaPlay)\n',
    1,
)
assert conditional_fallthrough_source != safe_tools_source, "conditional fallthrough mutation did not apply"
missing_else_safe_tools_source = safe_tools_source.replace(
    '        else -> rejected(ToolExecutionResult(false, "该操作不在安全工具白名单中", "REJECTED_NOT_ALLOWED"))\n',
    "",
    1,
)
assert missing_else_safe_tools_source != safe_tools_source, "missing else safe tool mutation did not apply"
else_go_home_safe_tools_source = safe_tools_source.replace(
    '        else -> rejected(ToolExecutionResult(false, "该操作不在安全工具白名单中", "REJECTED_NOT_ALLOWED"))',
    '        else -> allowed(DeviceAction.GoHome(sourceApp = null))',
    1,
)
assert else_go_home_safe_tools_source != safe_tools_source, "go-home else safe tool mutation did not apply"
decoy_when = f"""private fun decoyPlan(call: AiToolCall): SafeToolPlan = when (call.tool) {{
{render_safe_tool_when(EXPECTED_SAFE_TOOLS, 'rejected(ToolExecutionResult(false, "该操作不在安全工具白名单中", "REJECTED_NOT_ALLOWED"))')}
    }}
"""
decoy_earlier_safe_tools_source = decoy_when + safe_tools_source.replace(
    '        else -> rejected(ToolExecutionResult(false, "该操作不在安全工具白名单中", "REJECTED_NOT_ALLOWED"))',
    '        "go_home" -> allowed(DeviceAction.GoHome(sourceApp = null))\n'
    '        else -> rejected(ToolExecutionResult(false, "该操作不在安全工具白名单中", "REJECTED_NOT_ALLOWED"))',
    1,
)
assert decoy_earlier_safe_tools_source != safe_tools_source, "decoy earlier safe tool mutation did not apply"

expect(
    "release gate rejects try-wrapped subprocess.run",
    not validator.has_release_gate_loop_subprocess_run(try_wrapped_run_source),
    "validator currently accepts subprocess.run wrapped in try inside the TESTS loop",
)
expect(
    "release gate rejects duplicate or cleared TESTS assignment",
    not validator.release_gate_delegates_expected_tests(duplicate_and_cleared_tests_source, EXPECTED_RELEASE_GATE_TESTS),
    "validator currently accepts TESTS after reassignment and clear()",
)
expect(
    "release gate rejects alias.clear on TESTS",
    not validator.release_gate_delegates_expected_tests(alias_cleared_tests_source, EXPECTED_RELEASE_GATE_TESTS),
    "validator currently accepts alias.clear() on the TESTS list",
)
expect(
    "release gate rejects TESTS slice clearing",
    not validator.release_gate_delegates_expected_tests(slice_cleared_tests_source, EXPECTED_RELEASE_GATE_TESTS),
    "validator currently accepts TESTS[:] = [] after the top-level assignment",
)
expect(
    "release gate rejects break before subprocess.run",
    not validator.has_release_gate_loop_subprocess_run(break_before_run_source),
    "validator currently accepts a pre-run break in the TESTS loop",
)
expect(
    "release gate rejects alias = TESTS or [] followed by clear",
    not validator.release_gate_delegates_expected_tests(alias_or_cleared_tests_source, EXPECTED_RELEASE_GATE_TESTS),
    "validator currently accepts an alias expression that can clear the TESTS list",
)
expect(
    "release gate rejects globals rebinding of TESTS",
    not validator.release_gate_delegates_expected_tests(globals_rebinding_source, EXPECTED_RELEASE_GATE_TESTS),
    "validator currently accepts globals()[...] rebinding of TESTS",
)
expect(
    "release gate rejects loop target reassignment before subprocess.run",
    not validator.has_release_gate_loop_subprocess_run(loop_target_reassignment_source),
    "validator currently accepts reassignment of the TESTS loop variable before subprocess.run",
)
expect(
    "release gate rejects vars subscript loop target reassignment",
    not validator.has_release_gate_loop_subprocess_run(vars_loop_target_reassignment_source),
    "validator currently accepts vars()[\"test\"] rebinding of the loop variable",
)
expect(
    "release gate rejects aliased vars subscript loop target reassignment",
    not validator.has_release_gate_loop_subprocess_run(aliased_vars_loop_target_reassignment_source),
    "validator currently accepts an aliased vars() namespace rebinding of the loop variable",
)
expect(
    "release gate rejects globals subscript loop target reassignment",
    not validator.has_release_gate_loop_subprocess_run(globals_loop_target_reassignment_source),
    "validator currently accepts globals()[\"test\"] rebinding of the loop variable",
)
expect(
    "release gate rejects locals subscript loop target reassignment",
    not validator.has_release_gate_loop_subprocess_run(locals_loop_target_reassignment_source),
    "validator currently accepts locals()[\"test\"] rebinding of the loop variable",
)
expect(
    "release gate rejects setattr loop target reassignment",
    not validator.has_release_gate_loop_subprocess_run(setattr_loop_target_reassignment_source),
    "validator currently accepts setattr(..., \"test\", ...) rebinding of the loop variable",
)
expect(
    "release gate rejects namespace __setitem__ loop target reassignment",
    not validator.has_release_gate_loop_subprocess_run(setitem_loop_target_reassignment_source),
    "validator currently accepts namespace.__setitem__(\"test\", ...) rebinding of the loop variable",
)
expect(
    "release gate rejects external namespace alias loop target reassignment",
    not validator.has_release_gate_loop_subprocess_run(external_namespace_alias_loop_target_reassignment_source),
    "validator currently accepts a namespace alias created before the TESTS loop",
)
expect(
    "release gate rejects vars callable alias chains",
    not validator.has_release_gate_loop_subprocess_run(vars_callable_alias_chain_loop_target_reassignment_source),
    "validator currently accepts transitive aliases of vars as a namespace factory",
)
expect(
    "release gate rejects globals callable alias chains",
    not validator.has_release_gate_loop_subprocess_run(globals_callable_alias_chain_loop_target_reassignment_source),
    "validator currently accepts transitive aliases of globals as a namespace factory",
)
expect(
    "release gate rejects locals callable alias chains",
    not validator.has_release_gate_loop_subprocess_run(locals_callable_alias_chain_loop_target_reassignment_source),
    "validator currently accepts transitive aliases of locals as a namespace factory",
)
expect(
    "release gate rejects builtin attribute namespace factory aliases",
    not validator.has_release_gate_loop_subprocess_run(builtin_attribute_callable_alias_loop_target_reassignment_source),
    "validator currently accepts builtins.vars as a namespace factory alias",
)
expect(
    "release gate rejects builtin import namespace factory aliases",
    not validator.has_release_gate_loop_subprocess_run(builtin_import_callable_alias_loop_target_reassignment_source),
    "validator currently accepts a from-builtins namespace factory alias",
)
expect(
    "release gate rejects builtin mapping namespace factory aliases",
    not validator.has_release_gate_loop_subprocess_run(builtin_subscript_callable_alias_loop_target_reassignment_source),
    "validator currently accepts __builtins__[factory] as a namespace factory alias",
)
expect(
    "release gate rejects getattr namespace factory aliases",
    not validator.has_release_gate_loop_subprocess_run(builtin_getattr_callable_alias_loop_target_reassignment_source),
    "validator currently accepts getattr(builtins, factory) as a namespace factory alias",
)
expect(
    "release gate rejects break after direct subprocess.run",
    not validator.has_release_gate_loop_subprocess_run(break_after_run_source),
    "validator currently accepts a post-run break in the TESTS loop",
)
expect(
    "release gate rejects continue after direct subprocess.run",
    not validator.has_release_gate_loop_subprocess_run(continue_after_run_source),
    "validator currently accepts a post-run continue in the TESTS loop",
)
expect(
    "release gate rejects return after direct subprocess.run",
    not validator.has_release_gate_loop_subprocess_run(return_after_run_source),
    "validator currently accepts a post-run return in the TESTS loop",
)
expect(
    "release gate rejects outer try except swallowing the loop",
    not validator.has_release_gate_loop_subprocess_run(outer_try_except_pass_source),
    "validator currently accepts an outer try/except pass around the TESTS loop",
)
expect(
    "safe tool allowlist rejects terminal else GoHome branch",
    not validator.has_exact_safe_tool_allowlist(terminal_else_go_home_source, EXPECTED_SAFE_TOOLS),
    "validator currently accepts an allowed branch that falls back to DeviceAction.GoHome",
)
expect(
    "safe tool allowlist rejects terminal else that only mentions rejection before allowing GoHome",
    not validator.has_exact_safe_tool_allowlist(terminal_else_rejection_then_go_home_source, EXPECTED_SAFE_TOOLS),
    "validator currently accepts a terminal else that mentions rejection text but still returns DeviceAction.GoHome",
)
expect(
    "safe tool allowlist rejects GoHome allowed in any conditional branch",
    not validator.has_exact_safe_tool_allowlist(conditional_go_home_source, EXPECTED_SAFE_TOOLS),
    "validator currently accepts a disallowed Allowed action nested in a conditional",
)
expect(
    "safe tool allowlist rejects conditional fallthrough",
    not validator.has_exact_safe_tool_allowlist(conditional_fallthrough_source, EXPECTED_SAFE_TOOLS),
    "validator currently accepts a conditional Allowed branch without an else",
)
expect(
    "safe tool allowlist rejects indirect allowed(action) branches",
    not validator.has_exact_safe_tool_allowlist(computed_allowed_action_source, EXPECTED_SAFE_TOOLS),
    "validator currently accepts allowed(action) with an indirect DeviceAction value",
)
expect(
    "safe tool allowlist requires a rejecting else branch",
    not validator.has_exact_safe_tool_allowlist(missing_else_safe_tools_source, EXPECTED_SAFE_TOOLS),
    "validator currently accepts SafeToolExecutor.plan without a terminal rejecting else",
)
expect(
    "safe tool allowlist rejects else allowing GoHome",
    not validator.has_exact_safe_tool_allowlist(else_go_home_safe_tools_source, EXPECTED_SAFE_TOOLS),
    "validator currently accepts an else branch that allows DeviceAction.GoHome",
)
expect(
    "safe tool allowlist inspects the actual plan block instead of earlier decoys",
    not validator.has_exact_safe_tool_allowlist(decoy_earlier_safe_tools_source, EXPECTED_SAFE_TOOLS),
    "validator currently accepts an earlier decoy when(call.tool) while the actual plan adds a tool",
)

if failures:
    raise AssertionError("\n".join(failures))


print("PASS: v0.6.5 validator contract")
