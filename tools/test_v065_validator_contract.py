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
    "class SafeToolExecutor(private val phone: PhoneController) {\n",
    'class SafeToolExecutor(private val phone: PhoneController) {\n    private companion object {\n        const val EXTRA_TOOL = "go_home"\n    }\n',
    1,
).replace(
    "        else -> rejected(",
    '        EXTRA_TOOL -> allowed(DeviceAction.GoHome(sourceApp = null))\n'
    "        else -> rejected(",
    1,
)
assert named_branch_safe_tools != safe_tools_source, "named branch safe tool mutation did not apply"
assert not validator.has_exact_safe_tool_allowlist(named_branch_safe_tools, EXPECTED_SAFE_TOOLS)
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
