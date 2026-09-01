from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/build-apk.yml"
CHECKLIST = ROOT / "docs/release-verification/v0.6.5-fix04-device-checklist.md"


def assert_workflow_runs_before_build(gate_name: str) -> None:
    workflow = WORKFLOW.read_text(encoding="utf-8")
    gate = f"python3 tools/{gate_name}"
    gate_index = workflow.index(gate)
    build_index = workflow.index("gradle :app:assembleDebug")
    upload_index = workflow.index("name: Upload APK")
    assert gate_index < build_index, "FIX04 gate must run before Gradle assemble"
    assert gate_index < upload_index, "FIX04 gate must run before APK upload"


def assert_checklist_contains(tokens: list[str]) -> None:
    checklist = CHECKLIST.read_text(encoding="utf-8")
    numbered_items = re.findall(r"^\d+\.\s+.+$", checklist, flags=re.MULTILINE)
    assert len(numbered_items) == 8, "device checklist must contain exactly eight numbered items"
    assert "小白小白" in numbered_items[0], "custom wake must be the first acceptance item"
    for token in tokens:
        assert token in checklist, f"device checklist missing required token: {token}"


if __name__ == "__main__":
    assert_workflow_runs_before_build("test_v065_fix04_regression_gate.py")
    assert_checklist_contains(["小白小白", "100%", "70%", "50%", "静音", "调大", "调小"])
    print("PASS: v0.6.5 FIX04 release contract")
