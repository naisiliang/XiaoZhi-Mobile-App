# Task 6 report: AppAdapter registry

## Scope

- Base: `dc94d4f`
- Head: `b6499be`
- Branch: `recovery/v0.7.0-golden-first-full`
- Worktree: `E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full`
- Commits: `46aba71`, `b6499be`

## TDD evidence

The registry tests were added before the production adapter types. The RED
command was:

```text
gradle :app:testDebugUnitTest --tests '*AppAdapterRegistryTest*' --stacktrace
```

It failed during `compileDebugUnitTestKotlin` with unresolved references to
`AppAdapterRegistry`, `AppAdapter`, `MapAppAdapter`, `AppPageKind`, and
`UiActionExecutor`.

The first GREEN run passed the five core registry tests. A local review then
added regressions for adapter-list mutation and non-interactive map roots. To
keep the TDD evidence honest, the two production changes were temporarily
removed and the focused suite failed in exactly those two cases. Restoring the
minimal changes made the focused suite GREEN again.

## Implementation

- Added `AppAdapter` for package handling, page classification, semantic object
  extraction, proposal refinement, and result verification. It exposes no
  Android action API.
- Added `AppAdapterRegistry` with ordered dedicated-adapter selection and
  generic fallback. Adapter lists and extracted semantic results are copied at
  the boundary.
- Adapter-refined proposals must retain the exact original `ScreenContext`;
  context-swapping adaptations fall back to the original proposal.
- Every real action is delegated to the injected generic accessibility
  executor. A dedicated adapter can veto a successful result but cannot turn a
  blocked or failed safety result into success.
- Added `MapAppAdapter` for AMap/Baidu package recognition, map page
  classification, and clickable semantic candidates. Existing `MapController`
  was not rewritten or modified.
- Made `GenericAccessibilityExecutor` implement the registry's executor
  boundary without changing its PermissionBroker/CentralSafety pipeline.

No Level-1 frozen KWS source was changed. The adapter layer does not persist or
log raw screen data and does not use coordinates.

## Review

Two fresh delegated reviewers were requested for the final range. Both remained
running through two bounded waits and were then closed without a verdict. Their
status is recorded as unavailable, not CLEAN.

The controller performed a read-only review of `dc94d4f..b6499be` and found no
unresolved Critical or Important issue:

- dedicated selection precedes generic fallback;
- unsupported packages and adapter exceptions fail back to the original
  generic proposal;
- adapter proposals cannot replace the context identity;
- adapters have no direct Android execution path;
- all real actions still go through the Task 5 generic executor and its central
  permission/safety gates; and
- Map semantic enrichment is limited to clickable nodes and leaves
  `MapController` unchanged.

## Verification

```text
gradle :app:testDebugUnitTest --tests '*AppAdapterRegistryTest*'  PASS
gradle :app:testDebugUnitTest                                  PASS
python tools/test_v070_accessibility_contract.py               PASS
python tools/test_v060_security.py                             PASS
python tools/test_v070_phase0_recovery_gate.py                 PASS
git diff --check                                                PASS
```

No Android device or emulator was available; device validation remains
`DEVICE_GATE_PENDING`.
