from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
SELF = Path(__file__).resolve()
TESTS = [
    "tools/test_v064_volume_parser.py",
    "tools/test_v064_volume_execution.py",
    "tools/test_v065_fix04_media_volume_controller.py",
    "tools/test_v065_fix04_media_volume_algorithm.py",
    "tools/test_v065_fix04_media_volume_steps.py",
    "tools/test_v065_fix04_volume_feedback.py",
    "tools/test_v065_frozen_baseline.py",
    "tools/test_v060_security.py",
    "tools/test_v065_release_gate.py",
]


def main() -> int:
    seen = set()
    for test in TESTS:
        path = (ROOT / test).resolve()
        if path == SELF:
            raise SystemExit("FIX04 gate must not invoke itself recursively")
        if test in seen:
            raise SystemExit(f"duplicate FIX04 gate entry: {test}")
        seen.add(test)
        if not path.exists():
            raise SystemExit(f"FIX04 gate test missing: {test}")
        print(f"RUN: {test}", flush=True)
        completed = subprocess.run([sys.executable, test], cwd=ROOT)
        if completed.returncode != 0:
            return completed.returncode
    print("PASS: FIX04 regression gate")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
