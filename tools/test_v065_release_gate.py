from pathlib import Path
import subprocess


root = Path(__file__).resolve().parents[1]

TESTS = [
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


for test in TESTS:
    if not (root / test).exists():
        raise SystemExit(f"release gate test missing: {test}")
    print(f"RUN: {test}")
    subprocess.run(["python", test], cwd=root, check=True)

print("PASS: v0.6.5 release gate")
