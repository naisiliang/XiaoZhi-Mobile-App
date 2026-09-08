from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/build-apk.yml"
FINAL_GATE = ROOT / "tools/test_v070_final_release_gate.py"
APK_VALIDATOR = ROOT / "tools/validate_v070_apk.py"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


require(FINAL_GATE.is_file(), "missing v0.7 final release gate")
require(APK_VALIDATOR.is_file(), "missing v0.7 APK validator")
workflow = WORKFLOW.read_text(encoding="utf-8")
gate = FINAL_GATE.read_text(encoding="utf-8")
validator = APK_VALIDATOR.read_text(encoding="utf-8")

for marker in (
    "workflow_dispatch",
    "reviewed_ref",
    "git rev-parse HEAD",
    "XiaoZhi-Mobile-v0.7.0-rc-debug.apk",
    "tools/test_v070_final_release_gate.py",
    "scripts/fetch-kws-model.sh",
    "tools/validate_v070_apk.py",
    "actions/upload-artifact@v4",
    "actions/download-artifact@v4",
    "sha256",
):
    require(marker in workflow, f"workflow missing final-release marker: {marker}")

ordered_workflow_markers = (
    "- name: Frozen Source Guard",
    "- name: Historical Regression",
    "- name: P0 Recovery",
    "- name: Screen Intelligence",
    "- name: Messaging",
    "- name: Music and Video",
    "- name: Extensions",
    "- name: Artifacts and Image",
    "- name: Security",
    "- name: Android Project Validation",
    "- name: Fetch Offline Models",
    "- name: Unit Tests",
    "- name: Assemble Debug APK",
    "- name: Validate RC APK before upload",
    "- name: Upload v0.7.0 RC APK",
    "- name: Download v0.7.0 RC APK",
    "- name: Verify downloaded v0.7.0 RC APK",
)
positions = [workflow.find(marker) for marker in ordered_workflow_markers]
require(all(position >= 0 for position in positions), "workflow is missing an ordered release step")
require(positions == sorted(positions), "workflow release steps are out of order")
upload_position = workflow.index("- name: Upload v0.7.0 RC APK")
for marker in (
    "- name: Frozen Source Guard",
    "- name: Historical Regression",
    "- name: P0 Recovery",
    "- name: Security",
    "- name: Android Project Validation",
    "- name: Fetch Offline Models",
    "- name: Unit Tests",
    "- name: Assemble Debug APK",
    "- name: Validate RC APK before upload",
):
    require(workflow.index(marker) < upload_position, f"pre-upload gate is after upload: {marker}")

for marker in (
    "EXPECTED_RC_APK_NAME",
    "AndroidManifest.xml",
    "classes.dex",
    "lib/arm64-v8a/libsherpa-onnx-jni.so",
    "sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20",
    "sherpa-onnx-paraformer-zh-small-2024-03-09",
    "versionName",
    "AccessibilityService",
    "sha256",
    "byte",
):
    require(marker in validator, f"APK validator missing required check marker: {marker}")

for marker in (
    "FROZEN_SOURCE_GUARD",
    "HISTORICAL_REGRESSION",
    "P0_RECOVERY",
    "SCREEN_INTELLIGENCE",
    "MESSAGING",
    "MUSIC_VIDEO",
    "EXTENSIONS",
    "ARTIFACTS_IMAGE",
    "SECURITY",
):
    require(marker in gate, f"final gate missing stage: {marker}")

print("PASS: v0.7 final release gate contract")
