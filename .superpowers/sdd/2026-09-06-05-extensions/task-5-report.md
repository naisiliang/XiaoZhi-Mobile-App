# Task 5 report — MCP registry, inspector, and safe transport

Date: 2026-09-07

## Scope

- Added explicit user-configured MCP server metadata and validation for STDIO, SSE, and streamable HTTPS transports.
- Added bounded tool-schema inspection. Only the app-owned local capability map can produce a binding; remote permission metadata and prompt text cannot grant local permissions.
- Added registry lifecycle, schema replacement, client attachment matching, enable/disable state, redacted diagnostics, and current-tool failure isolation.
- Added minimal JSON-RPC `tools/call` encoding/response validation with injected transport and app-private credential provider. No process spawning, dynamic loading, or arbitrary code execution is present.
- Added bounds for nested JSON collections after local review found that top-level/request-size limits alone left an avoidable intermediate-allocation risk.

## TDD evidence

- RED: the focused MCP test initially failed to compile because the MCP types did not exist.
- GREEN: the focused MCP tests passed after the minimal registry/inspector/client implementation.
- Review-fix RED: `nestedJsonCollectionsAreBoundedBeforeTransport` failed before collection limits were added.
- Review-fix GREEN: all 15 `McpRegistryTest` tests pass; the test transport now derives successful response ids from the request, removing test-order dependence.

## Verification

- `python tools/test_v070_xzpack_validator_contract.py` — PASS
- `python tools/test_v070_plugin_manager_contract.py` — PASS
- `python tools/test_v070_skill_registry_contract.py` — PASS
- `python tools/test_v070_agent_registry_contract.py` — PASS
- `python tools/test_v070_mcp_contract.py` — PASS
- `gradle :app:testDebugUnitTest assembleDebug --console=plain` — BUILD SUCCESSFUL
- `python tools/test_v070_phase0_recovery_gate.py` — `PASS: v0.7 Phase 0 recovery gate`
- Frozen KWS source guard and v0.6.5 Golden/release regressions remain passing.

## Review

- Two delegated read-only review requests were made after the implementation commit and again after the review fix. Each timed out after 90 seconds; both agents were closed and their lack of output was not treated as approval.
- Fresh local spec-compliance and quality/security review covered the Phase 4 MCP plan and `07_EXTENSION_SECURITY_CONTRACT.md`. No unresolved Critical or Important issue remains.

## Commits

- `452473b feat: add MCP registry and safe transport`
- `a4e2119 fix: bound MCP JSON collections`

Device validation remains `DEVICE_GATE_PENDING`; no authorized Android device or emulator was available.
