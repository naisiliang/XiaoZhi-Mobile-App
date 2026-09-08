from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]

FROZEN_SOURCE_GUARD = (
    "tools/test_v065_frozen_baseline.py",
)

HISTORICAL_REGRESSION = (
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
    "tools/test_v065_release_gate.py",
    "tools/test_v063_custom_wake_ppinyin.py",
    "tools/test_v063_wake_error_diagnostics.py",
    "tools/test_v064_command_prompt_flow.py",
    "tools/test_v064_duplicate_guard.py",
    "tools/test_v064_exit_intent.py",
    "tools/test_v064_volume_execution.py",
    "tools/test_v064_volume_parser.py",
    "tools/test_v064_wake_regression.py",
)

P0_RECOVERY = (
    "tools/test_v070_alpha1_settings_migration.py",
    "tools/test_v070_alpha1_session_wiring.py",
    "tools/test_v070_alpha1_chat_home_contract.py",
    "tools/test_v070_task2_runtime_contract.py",
    "tools/test_v070_recovery_runtime_entry.py",
    "tools/test_v070_recovery_typed_pipeline.py",
    "tools/test_v070_recovery_settings_parity.py",
    "tools/test_v070_recovery_ui_contract.py",
    "tools/test_v070_recovery_history_ui.py",
    "tools/test_v070_task3_typed_source_contract.py",
)

SCREEN_INTELLIGENCE = (
    "tools/test_v070_phase1_screen_gate.py",
    "tools/test_v070_accessibility_contract.py",
    "tools/test_v070_sensitive_vision_zero_call.py",
    "tools/test_v070_provider_capability_contract.py",
)

MESSAGING = (
    "tools/test_v070_messaging_adapter_contract.py",
    "tools/test_v070_messaging_confirmation_contract.py",
    "tools/test_v070_messaging_coordinator_contract.py",
    "tools/test_v070_messaging_send_result_contract.py",
    "tools/test_v070_messaging_tool_contract.py",
)

MUSIC_VIDEO = (
    "tools/test_v070_music_adapters_contract.py",
    "tools/test_v070_music_intent_contract.py",
    "tools/test_v070_music_resolver_contract.py",
    "tools/test_v070_media_session_fallback_contract.py",
    "tools/test_v070_video_save_contract.py",
)

EXTENSIONS = (
    "tools/test_v070_extension_center_contract.py",
    "tools/test_v070_plugin_manager_contract.py",
    "tools/test_v070_skill_registry_contract.py",
    "tools/test_v070_agent_registry_contract.py",
    "tools/test_v070_agent_integration_contract.py",
    "tools/test_v070_mcp_contract.py",
)

ARTIFACTS_IMAGE = (
    "tools/test_v070_artifact_core_contract.py",
    "tools/test_v070_artifact_generators_contract.py",
    "tools/test_v070_structured_artifact_contract.py",
    "tools/test_v070_ppt_pipeline_contract.py",
    "tools/test_v070_artifact_versions_contract.py",
    "tools/test_v070_artifact_export_contract.py",
    "tools/test_v070_image_generation_contract.py",
    "tools/test_v070_phase5_artifacts_image_checkpoint.py",
)

SECURITY = (
    "tools/test_v060_safe_tools.py",
    "tools/test_v060_security.py",
    "tools/test_v070_xzpack_validator_contract.py",
    "tools/test_v070_reviewer_runtime_findings.py",
)

ANDROID_PROJECT_VALIDATION = (
    "tools/test_v070_final_release_gate_contract.py",
    "tools/test_local_asr_source.py",
    "tools/validate_project.py",
)

STAGES = {
    "frozen": FROZEN_SOURCE_GUARD,
    "historical": HISTORICAL_REGRESSION,
    "p0": P0_RECOVERY,
    "screen": SCREEN_INTELLIGENCE,
    "messaging": MESSAGING,
    "music-video": MUSIC_VIDEO,
    "extensions": EXTENSIONS,
    "artifacts-image": ARTIFACTS_IMAGE,
    "security": SECURITY,
    "android": ANDROID_PROJECT_VALIDATION,
}


def run_stage(stage_name: str, tests: tuple[str, ...]) -> None:
    print(f"STAGE: {stage_name}", flush=True)
    for relative_path in tests:
        path = ROOT / relative_path
        if not path.is_file():
            raise SystemExit(f"missing release-gate test: {relative_path}")
        print(f"RUN: {relative_path}", flush=True)
        subprocess.run(
            [sys.executable, "-B", "-X", "utf8", relative_path],
            cwd=ROOT,
            check=True,
        )
    print(f"PASS: {stage_name}", flush=True)


def main() -> int:
    parser = argparse.ArgumentParser(description="Run the v0.7.0 pre-build release gate")
    parser.add_argument("--stage", choices=tuple(STAGES))
    args = parser.parse_args()

    if args.stage is not None:
        run_stage(args.stage, STAGES[args.stage])
    else:
        for stage_name, tests in STAGES.items():
            run_stage(stage_name, tests)
    print("PASS: v0.7.0 final pre-build release gate", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
