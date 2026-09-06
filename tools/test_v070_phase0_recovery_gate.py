from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
GOLDEN = "324dd5a53d404490bc4a32ed1f9ce8c45671ed24"
FROZEN_PATHS = (
    "app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseCompiler.kt",
    "app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseManager.kt",
    "app/src/main/java/com/lchuang/xiaozhimobile/Pinyin4jProvider.kt",
)

PYTHON_TESTS = (
    # Golden/frozen and historical behavior.
    "tools/test_v065_frozen_baseline.py",
    "tools/test_v031_behavior.py",
    "tools/test_v040_voice_flow.py",
    "tools/test_v050_session.py",
    "tools/test_v050_voice_flow.py",
    "tools/test_v060_settings.py",
    "tools/test_v060_tts.py",
    "tools/test_v060_tts_settings_action.py",
    "tools/test_v060_ui_source.py",
    "tools/test_v060_safe_tools.py",
    "tools/test_v060_security.py",
    "tools/test_v060_ai_orchestrator.py",
    # The complete v0.6.5 gate remains the non-regression authority.
    "tools/test_v065_release_gate.py",
    # Alpha1 shared session/UI foundations.
    "tools/test_v070_alpha1_settings_migration.py",
    "tools/test_v070_alpha1_chat_home_contract.py",
    "tools/test_v070_alpha1_session_wiring.py",
    # Phase 0 recovery contracts.
    "tools/test_v070_task2_runtime_contract.py",
    "tools/test_v070_recovery_runtime_entry.py",
    "tools/test_v070_recovery_typed_pipeline.py",
    "tools/test_v070_recovery_settings_parity.py",
    "tools/test_v070_recovery_ui_contract.py",
    "tools/test_v070_recovery_history_ui.py",
    "tools/validate_project.py",
)


def run(command):
    printable = " ".join(str(part) for part in command)
    print(f"RUN: {printable}", flush=True)
    subprocess.run(command, cwd=ROOT, check=True)


def verify_frozen_sources():
    run(["git", "cat-file", "-e", f"{GOLDEN}^{{commit}}"])
    branch_result = subprocess.run(
        ["git", "symbolic-ref", "--short", "-q", "HEAD"],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    branch = branch_result.stdout.strip() or "(detached HEAD)"
    if branch in {"main", "feature/v0.7.0-smart-agent"}:
        raise SystemExit(f"Phase 0 gate refuses protected branch: {branch}")
    print(f"BRANCH: {branch}", flush=True)
    for path in FROZEN_PATHS:
        result = subprocess.run(
            ["git", "diff", "--quiet", GOLDEN, "--", path],
            cwd=ROOT,
        )
        if result.returncode != 0:
            raise SystemExit(f"Frozen Golden source changed: {path}")
        print(f"PASS: frozen source unchanged {path}", flush=True)


def main():
    verify_frozen_sources()
    for relative_path in PYTHON_TESTS:
        run([sys.executable, "-B", "-X", "utf8", relative_path])
    print("PASS: v0.7 Phase 0 recovery gate", flush=True)


if __name__ == "__main__":
    main()
