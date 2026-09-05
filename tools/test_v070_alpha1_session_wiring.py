from pathlib import Path
import hashlib
import re


ROOT = Path(__file__).resolve().parents[1]
WAKE_SERVICE = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt"
MAIN_ACTIVITY = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt"
WORKFLOW = ROOT / ".github/workflows/build-apk.yml"


def assert_source_contains(source, tokens):
    missing = [token for token in tokens if token not in source]
    assert not missing, f"session wiring is missing: {', '.join(missing)}"


def function_body(source, function_name):
    match = re.search(rf"(?:private |public |internal |protected )?fun {function_name}\b[^{{]*\{{", source)
    assert match, f"function {function_name} is missing"
    body_start = match.end()
    depth = 1
    index = body_start
    while depth and index < len(source):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
        index += 1
    assert depth == 0, f"function {function_name} is not balanced"
    return source[body_start:index - 1]


def assert_call_in_function(source, function_name, call):
    body = function_body(source, function_name)
    assert call in body, f"{call} must be called from {function_name}"


def block_body_after(source, marker):
    marker_index = source.find(marker)
    assert marker_index >= 0, f"missing production branch: {marker}"
    opening = source.find("{", marker_index + len(marker))
    assert opening >= 0, f"missing block after: {marker}"
    depth = 0
    for index in range(opening, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[opening + 1:index]
    raise AssertionError(f"unbalanced block after: {marker}")


def assert_confirmation_waits_without_recovery(source):
    handler = function_body(source, "handleAiToolResult")
    confirmation_marker = 'if (result.debugCode == "CONFIRMATION_REQUIRED")'
    confirmation_at = handler.find(confirmation_marker)
    safety_at = handler.find("if (isSafetyToolResult(result))")
    assert 0 <= confirmation_at < safety_at, (
        "CONFIRMATION_REQUIRED must enter its waiting branch before generic safety rejection"
    )

    confirmation = block_body_after(handler, confirmation_marker)
    for call in (
        "memory.addTurn(",
        "conversationSessionManager.appendConfirmation(",
        "assistantStateStore.onConfirmationRequired()",
        "overlay.update(",
        "updateNotificationRaw(",
        "return",
    ):
        assert call in confirmation, f"confirmation path must contain {call}"

    for forbidden in (
        "reportRejectedToolFeedback",
        "recoverRecognitionFailure",
        "speakThen",
        "continueConversationSession",
        "scheduleListeningAfterSpeech",
        "startLocalCommandRecognition",
        "restartWakeListening",
        "startKwsCapture",
        "executeDeviceAction",
        "safeToolExecutor.execute",
        "deviceActionExecutor.execute",
        "toolDispatcher.dispatch",
        "conversationSessionManager.endSession",
        "requestConversationExit",
        "session.stop",
        "memory.clear",
        "conversationActive = false",
    ):
        assert forbidden not in confirmation, (
            f"confirmation path must stay pending without {forbidden}"
        )


def assert_forbidden_scope_guards(source):
    for marker in (
        "AccessibilityService",
        "BIND_ACCESSIBILITY_SERVICE",
        "MediaProjection",
        "send_message",
        "PluginRuntime",
        "AgentRuntime",
        "Alpha2",
    ):
        assert marker not in source, f"WakeService contains forbidden out-of-scope marker: {marker}"


def assert_alpha1_workflow_checkpoint_is_dispatch_only(workflow):
    assert "workflow_dispatch:" in workflow
    reviewed_input = re.search(
        r"reviewed_ref:\n"
        r"\s+description: (?P<description>[^\n]+)\n"
        r"\s+required: true\n"
        r"\s+type: string",
        workflow,
    )
    assert reviewed_input, "workflow_dispatch must require a reviewed_ref string input"
    assert re.search(r"40[- ]character.*commit SHA", reviewed_input.group("description"), re.I), (
        "reviewed_ref must be documented as an exact 40-character commit SHA"
    )
    validate_reviewed_sha = workflow_step_body(workflow, "Validate Alpha1 reviewed SHA")
    assert "if: ${{ github.event_name == 'workflow_dispatch' }}" in validate_reviewed_sha
    assert "REVIEWED_SHA: ${{ inputs.reviewed_ref }}" in validate_reviewed_sha
    assert 'if [[ ! "$REVIEWED_SHA" =~ ^[0-9a-f]{40}$ ]]; then' in validate_reviewed_sha
    assert "exit 1" in validate_reviewed_sha
    assert re.search(r"\^\[0-9a-f\]\{40\}\$", validate_reviewed_sha), (
        "workflow_dispatch must reject reviewed_ref values that are not 40-character SHAs"
    )
    dispatch_checkout = re.search(
        r"- name: Checkout reviewed ref for Alpha1\n"
        r"\s+if: \$\{\{ github\.event_name == 'workflow_dispatch' \}\}\n"
        r"\s+uses: actions/checkout@v4\n"
        r"\s+with:\n"
        r"\s+ref: \$\{\{ inputs\.reviewed_ref \}\}",
        workflow,
    )
    assert dispatch_checkout, "workflow_dispatch must checkout exactly inputs.reviewed_ref"
    verify_checkout_sha = workflow_step_body(workflow, "Verify Alpha1 checkout SHA")
    assert "if: ${{ github.event_name == 'workflow_dispatch' }}" in verify_checkout_sha
    assert "REVIEWED_SHA: ${{ inputs.reviewed_ref }}" in verify_checkout_sha
    assert "git rev-parse HEAD" in verify_checkout_sha
    assert '[[ "$actual_sha" != "$REVIEWED_SHA" ]]' in verify_checkout_sha
    assert "exit 1" in verify_checkout_sha
    validate_start = workflow.index("- name: Validate Alpha1 reviewed SHA")
    checkout_start = workflow.index("- name: Checkout reviewed ref for Alpha1")
    verify_start = workflow.index("- name: Verify Alpha1 checkout SHA")
    java_setup_start = workflow.index("- uses: actions/setup-java@v4")
    assert validate_start < checkout_start < verify_start < java_setup_start, (
        "reviewed SHA validation and exact checkout verification must precede all build/gate setup"
    )
    push_checkout = re.search(
        r"- name: Checkout push commit\n"
        r"\s+if: \$\{\{ github\.event_name == 'push' \}\}\n"
        r"\s+uses: actions/checkout@v4\n"
        r"\s+with:\n"
        r"\s+ref: \$\{\{ github\.sha \}\}",
        workflow,
    )
    assert push_checkout, "push must checkout exactly github.sha"
    assert workflow.count("uses: actions/checkout@v4") == 2
    assert "ref: ${{ github.event_name == 'workflow_dispatch' && inputs.reviewed_ref || github.sha }}" not in workflow
    assert "ref: ${{ inputs.reviewed_ref || github.sha }}" not in workflow
    assert "if: ${{ github.event_name == 'workflow_dispatch' }}" in workflow
    assert "name: XiaoZhi-Mobile-Alpha1-APK" in workflow
    assert "path: XiaoZhi-Mobile-Alpha1-debug.apk" in workflow
    assert "ref: ${{ inputs.reviewed_ref || github.sha }}" not in workflow
    dispatch_only = r"if: \$\{\{ github\.event_name == 'workflow_dispatch' \}\}"
    for step in (
        "Alpha1 conversation session gate",
        "Alpha1 foundation gates",
        "Rename Alpha1 APK",
        "Upload Alpha1 APK",
    ):
        assert re.search(rf"- name: {re.escape(step)}\n\s+{dispatch_only}", workflow), (
            f"{step} must be workflow_dispatch-only"
        )


def workflow_step_body(workflow, step_name):
    match = re.search(
        rf"- name: {re.escape(step_name)}\n(?P<body>.*?)(?=\n      - (?:name|uses):|\Z)",
        workflow,
        re.S,
    )
    assert match, f"workflow step is missing: {step_name}"
    return match.group("body")


def assert_ci_build_and_alpha1_artifact_contract(workflow):
    assert "gradle-version: '8.9'" in workflow, "CI must use Gradle 8.9"

    unit_test = workflow_step_body(workflow, "Alpha1 focused unit tests")
    assert "if: ${{ github.event_name == 'workflow_dispatch' }}" in unit_test
    assert "gradle :app:testDebugUnitTest" in unit_test
    unit_test_start = workflow.index("- name: Alpha1 focused unit tests")
    build_start = workflow.index("- name: Build debug APK")
    upload_start = workflow.index("- name: Upload Alpha1 APK")
    assert unit_test_start < build_start < upload_start, (
        "workflow_dispatch unit tests must run before APK build and upload"
    )

    alpha_download = workflow_step_body(workflow, "Download Alpha1 APK artifact")
    assert "if: ${{ github.event_name == 'workflow_dispatch' }}" in alpha_download
    assert "uses: actions/download-artifact@v4" in alpha_download
    assert "name: XiaoZhi-Mobile-Alpha1-APK" in alpha_download
    assert "path: alpha1-artifact-verification" in alpha_download

    alpha_verify = workflow_step_body(workflow, "Verify downloaded Alpha1 APK")
    assert "if: ${{ github.event_name == 'workflow_dispatch' }}" in alpha_verify
    assert "XiaoZhi-Mobile-Alpha1-debug.apk" in alpha_verify
    assert "tools/validate_v065_apk.py" in alpha_verify
    assert "artifact-zip" not in alpha_verify, (
        "Alpha1 validation must validate the downloaded APK directly"
    )
    assert "--report" in alpha_verify
    assert "sha256" in alpha_verify.lower()
    assert "cmp" in alpha_verify, "Alpha1 and generic APK bytes must be compared"
    assert "XiaoZhi-Mobile-Alpha1-APK" in alpha_verify, (
        "Alpha1 verification report must identify the Alpha1 artifact"
    )
    assert "--artifact-dir alpha1-apk-validation" in alpha_verify

    generic_download_start = workflow.index("- name: Download exact APK artifact")
    alpha_download_start = workflow.index("- name: Download Alpha1 APK artifact")
    alpha_verify_start = workflow.index("- name: Verify downloaded Alpha1 APK")
    generic_verify_start = workflow.index("- name: Verify downloaded artifact ZIP and APK")
    assert alpha_download_start > generic_download_start
    assert alpha_verify_start > alpha_download_start
    assert generic_verify_start > alpha_verify_start


def assert_local_verification_paths(checklist):
    assert "C:/Users/ASUS/AppData/Local/Temp/codex-kotlinc/jdk17/jdk-17.0.20.1+1" in checklist
    assert "C:/Users/ASUS/AppData/Local/Temp/xiaozhi-gradle-8.9-local/gradle-8.9/bin/gradle.bat" in checklist
    assert "C:/Users/ASUS/AppData/Local/Temp/xiaozhi-android-sdk" in checklist
    assert "C:/Users/ASUS/AppData/Local/Temp/xiaozhi-gradle-8.9-complete/gradle-8.9/bin/gradle.bat" not in checklist
    assert "C:/Users/ASUS/AppData/Local/Temp/xiaozhi-gradle-8.9-full" not in checklist
    assert "C:/Users/ASUS/.jdks/openjdk-20.0.2" not in checklist
    assert "physical arm64 Android device" in checklist
    assert "workflow_dispatch" in checklist and "reviewed_ref" in checklist


def assert_frozen_wake_hash_unchanged():
    source = WAKE_SERVICE.read_text("utf-8")
    block = re.search(
        r"private fun initKeywordSpotter\(\)\s*\{.*?\n    \}\n\n    private fun initOfflineAsr",
        source,
        re.S,
    )
    assert block, "frozen KWS initialization/application/listening block is missing"
    digest = hashlib.sha256(block.group(0).encode()).hexdigest()
    assert digest == "77071fcc4a9d9c9627e8a30ddb45d0ad831ece80483152d8b71ce8b4c128abcd", digest


def main():
    source = WAKE_SERVICE.read_text("utf-8")
    main_activity_source = MAIN_ACTIVITY.read_text("utf-8")
    assert '"${name}将在后续版本接入"' in main_activity_source, (
        "MainActivity must use brace-delimited Kotlin interpolation for unavailable feature names"
    )
    assert_source_contains(
        source,
        [
            "ConversationSessionManager",
            "ConversationSessionStore.manager(this)",
            "startWakeSession",
            "appendUser",
            "appendAssistant",
            "appendSystemAction",
            "appendSystemResult",
            "appendConfirmation",
            "endSession",
        ],
    )
    assert "ConversationRepository(this)" not in source, "WakeService must use the shared session repository"
    assert_call_in_function(source, "handleWakeDetected", "conversationSessionManager.startWakeSession()")
    assert_call_in_function(source, "processUtterance", "conversationSessionManager.appendUser(rawText)")
    assert_call_in_function(source, "processNonExitUtterance", "conversationSessionManager.appendAssistant(answer)")
    assert_call_in_function(source, "executeDeviceAction", "conversationSessionManager.appendAssistant(result.spokenResult)")
    assert_call_in_function(source, "requestConversationExit", 'conversationSessionManager.endSession("conversation_exit")')
    assert_call_in_function(source, "onDestroy", 'conversationSessionManager.endSession("service_destroyed")')
    assert_confirmation_waits_without_recovery(source)
    assert_forbidden_scope_guards(source)
    assert_frozen_wake_hash_unchanged()
    workflow = WORKFLOW.read_text("utf-8")
    checklist = (ROOT / "docs/release-verification/v0.7.0-alpha1-device-checklist.md").read_text("utf-8")
    assert_alpha1_workflow_checkpoint_is_dispatch_only(workflow)
    assert_ci_build_and_alpha1_artifact_contract(workflow)
    assert_local_verification_paths(checklist)
    print("PASS: v0.7.0-alpha1 WakeService session wiring")


if __name__ == "__main__":
    main()
