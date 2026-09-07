# Task 6 report — Provider capability profile and health

Date: 2026-09-08

## Scope

- Added `ProviderCapabilityProfile` with independent statuses for Text, Responses API, Chat Completions, Function Calling, Structured Output, Vision, Image Generation, File Input, Code Interpreter, MCP, and Native Skills.
- Added bounded `ProviderCapabilityProbe` with injectable transport, safe low-cost probe request construction, actual HTTP transport, per-operation response mapping, and configuration-keyed caching.
- Base URL, model, API mode, and credential fingerprint are part of the cache key; a changed provider configuration probes again. The implementation does not infer capabilities from an OpenAI-compatible name.
- Added bounded provider response/native-skill parsing, endpoint validation, no-redirect credential handling, and redacted configuration/request diagnostics.
- Added `CapabilityHealthMonitor`: transient failures progress HEALTHY → DEGRADED → UNHEALTHY → SUSPENDED, security violations suspend immediately, and only explicit `recheck` clears suspension.
- Added Settings UI controls for real capability detection and separate local XiaoZhi Skills / provider Native Skills display without rendering API keys.

## TDD evidence

- RED: provider/health tests initially failed to compile because the requested types did not exist.
- GREEN: focused probe and health tests passed after the minimum implementation.
- Review-fix RED: Android JVM tests exposed unmocked `org.json` request construction; the API-specific wire-format test exposed Responses/Chat probes using the selected mode instead of their own format; the invalid endpoint test exposed a transport cast exception.
- Review-fix GREEN: replaced request construction with a bounded JSON writer, used the existing declarative parser for native skills, forced API-specific wire modes, validated transport endpoints/methods, and disabled redirects.

## Verification

- `python tools/test_v070_provider_capability_contract.py` — PASS
- `gradle :app:testDebugUnitTest --tests ...ProviderCapabilityProbeTest --tests ...CapabilityHealthMonitorTest --console=plain` — BUILD SUCCESSFUL
- `python tools/test_v070_xzpack_validator_contract.py` — PASS
- `python tools/test_v070_plugin_manager_contract.py` — PASS
- `python tools/test_v070_skill_registry_contract.py` — PASS
- `python tools/test_v070_agent_registry_contract.py` — PASS
- `python tools/test_v070_mcp_contract.py` — PASS
- `gradle :app:testDebugUnitTest assembleDebug --console=plain` — BUILD SUCCESSFUL
- `python tools/test_v070_phase0_recovery_gate.py` — `PASS: v0.7 Phase 0 recovery gate`
- Frozen KWS guard, v0.6.5 Golden/release and historical recovery regressions remain passing.

## Review

- Two delegated read-only review requests were made after the implementation commit; both timed out after 90 seconds and were closed. The timeout was not treated as approval.
- Fresh local spec-compliance and quality/security review covered the Phase 4 Task 6 plan and design spec sections 22/34. No unresolved Critical or Important issue remains.

## Commit

- `ccb294d feat: add provider capability health`

Device validation remains `DEVICE_GATE_PENDING`; no authorized Android device or emulator was available.
