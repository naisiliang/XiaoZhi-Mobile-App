# Phase 5 Task 8 report — Agent tool ownership and budgets

Date: 2026-09-08

## Scope

- Replaced the specialized Agent placeholder allowlists with explicit,
  auditable domains: PPT/asset, image, file/artifact, and research.
- Added the corresponding declarative tool names to the registry and central
  Safety Policy. Export tools require confirmation; generation, transformation,
  and validation tools remain policy-governed and bounded.
- Kept the research Agent free of phone, device, messaging, and accessibility
  tools. Any future additional capability must be separately declared and
  permission-checked.
- Extended `AgentOrchestrator` to parse per-step artifact size requests and
  enforce both the Agent limit and a shared composite budget across delegated
  children, alongside existing depth/tool/time/cycle guards.

## TDD evidence

- RED: the new Agent integration contract failed because domain tool sets and
  central policy entries were absent.
- RED: the artifact-budget test failed to compile because
  `ARTIFACT_BUDGET_EXCEEDED` and the shared accounting path were absent.
- GREEN: domain ownership and delegated artifact budget tests passed.
- Regression GREEN: existing AgentRegistry, SkillRegistry, provider, artifact,
  and Golden/P0 tests continued to pass.

## Verification

- `python -B -X utf8 tools/test_v070_agent_integration_contract.py` — PASS
- `python -B -X utf8 tools/test_v070_agent_registry_contract.py` — PASS
- `gradle :app:testDebugUnitTest --tests com.lchuang.xiaozhimobile.extensions.agents.AgentRegistryTest --no-daemon` — BUILD SUCCESSFUL
- `gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --no-daemon` — BUILD SUCCESSFUL
- Artifact/Image, provider, version, Agent, and Phase 0 contracts with the
  configured JDK/Kotlin/Android toolchain — PASS
- `git diff --cached --check` — PASS before commit

## Review

- Delegated review was unavailable after a bounded wait; the timeout was not
  treated as approval.
- Fresh local spec-compliance and code-quality/security review found no
  unresolved Critical or Important issue.
- No Level-1 frozen KWS source or parameter was changed. Device validation
  remains `DEVICE_GATE_PENDING`.

## Commit

- Recorded with the Task 8 implementation commit.
