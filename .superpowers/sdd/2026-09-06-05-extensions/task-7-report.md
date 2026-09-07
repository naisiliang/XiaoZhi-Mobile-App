# Task 7 report — Extension Center and Agent Center UI

Date: 2026-09-08

## Scope

- Replaced the MainActivity plugin/skill and Agents placeholder menu actions with real, non-exported ExtensionCenterActivity and AgentCenterActivity entries.
- Added a WindowInsets-aware Extension Center with app-private `.xzpack` SAF import, bounded PluginManager validation/import, visible permission review, enable/disable/remove controls, and health status cards.
- Added plugin, declarative skill, and MCP server card sections. MCP cards consume redacted registry diagnostics and do not render credentials or raw prompts.
- Added built-in Agent cards with local enable/disable state, tool allowlist and execution-budget summaries, and health state without rendering prompt contents.
- Updated the stale alpha1 session-wiring assertion to require the real extension/Agent entries and forbid the removed placeholder.

## TDD evidence

- RED: `test_v070_extension_center_contract.py` failed because the two UI activities and layouts did not exist.
- GREEN: the new contract passed after adding the activities, layouts, manifest entries, SAF import path, permission review, state controls, and real MainActivity launches.
- Focused verification passed for the extension-center contract, updated alpha1 wiring contract, and Android resource/Kotlin compilation.

## Verification

- `python tools/test_v070_extension_center_contract.py` — PASS
- `python tools/test_v070_alpha1_session_wiring.py` — PASS
- `gradle :app:testDebugUnitTest :app:assembleDebug --console=plain` — BUILD SUCCESSFUL
- `python tools/test_v070_phase0_recovery_gate.py` — `PASS: v0.7 Phase 0 recovery gate`
- All existing v0.7 and v0.6.5 static contracts — PASS
- `git show --check efc8457` — PASS
- `lintDebug` was attempted; it reports three pre-existing errors outside this Task's diff (WakeService AudioRecord MissingPermission, camera hardware feature declaration, and QUERY_ALL_PACKAGES), plus existing warnings. No frozen KWS or WakeService KWS code was changed to suppress them.

## Review

- Two delegated read-only review requests were made after the implementation commit; both timed out after 60 seconds and were closed. The timeout was not treated as approval.
- Fresh local spec-compliance and quality/security review covered the Phase 4 Task 7 plan and the extension security contract. No unresolved Critical or Important issue remains.
- The new UI uses the existing guarded PluginManager/SkillRegistry/McpRegistry/AgentRegistry APIs; it contains no dynamic loading, process execution, arbitrary package execution, credential rendering, or raw Agent prompt rendering.

## Commit

- `efc8457 feat: add extension and agent centers`

Device validation remains `DEVICE_GATE_PENDING`; no authorized Android device or emulator was available.
