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
assert not validator.release_gate_delegates_expected_tests(comment_only_frozen_guard, EXPECTED_RELEASE_GATE_TESTS)
assert not validator.has_release_gate_loop_subprocess_run(broken_loop_source)
print("PASS: release gate structural checks enforce delegated frozen guard and in-loop subprocess execution")


print("PASS: v0.6.5 validator contract")
