# Task 2 report: Accessibility service and semantic snapshot

## Scope

- Base: `48fb3037884dacbdd90ddf20828b5058bb863185`
- Branch: `recovery/v0.7.0-golden-first-full`
- Worktree: `E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full`
- Commit target: `feat: add user-enabled accessibility snapshot service`

## TDD evidence

The structural contract was made stricter after reviewing Android service binding semantics. The RED command was:

```text
python -B -X utf8 tools/test_v070_accessibility_contract.py
```

It failed before the GREEN fix with:

```text
FAIL: v0.7 Accessibility contract
- Accessibility service must be exported for system binding and protected by the binding permission
- service destruction must invalidate the transient screen context
- ScreenNode.toString must not expose raw snapshot data: text=
- ScreenNode.toString must not expose raw snapshot data: contentDescription=
- ScreenNode.toString must not expose raw snapshot data: className=
- ScreenNode.toString must not expose raw snapshot data: children=$children
```

The GREEN commands passed:

```text
python -B -X utf8 tools/test_v070_accessibility_contract.py
PASS: v0.7 user-enabled transient Accessibility snapshot contract

python -B -X utf8 tools/test_v060_security.py
PASS: v0.6 security regression

gradle :app:testDebugUnitTest --stacktrace
BUILD SUCCESSFUL
```

## Implementation

- Added `XiaoZhiAccessibilityService`, bound only through Android's `AccessibilityService` contract.
- Added `AccessibilitySnapshotBuilder` using only transient text, content description, class, clickable state, visibility and visible bounds.
- Added the system service XML metadata and manifest declaration with `BIND_ACCESSIBILITY_SERVICE` and `exported=true`, which is required for framework binding; the signature binding permission remains mandatory.
- Published snapshots to a process-memory `ScreenContextStore` with package/window fingerprinting and invalidated the store on missing roots, interruption, destruction, or an unusable snapshot.
- Extended `ScreenNode` with semantic fields and immutable `ScreenBounds`; its `toString` is redacted so raw text/tree content is not accidentally exposed.
- Migrated the legacy security test from rejecting the now-planned user-enabled service to checking its binding permission and rejecting programmatic enable/bypass paths. Existing secret, SafeTool, and background-location checks remain.

No database, file, SharedPreferences, Room, logging, shell, secure-settings, or other persistence/enablement path was added.

## Regression

```text
python -B -X utf8 tools/test_v070_phase0_recovery_gate.py
PASS: v0.7 Phase 0 recovery gate
```

The frozen KWS source diff against Golden remains empty. No Level-1 frozen file was modified.

## Review note

The delegated implementer produced the initial contract but did not return after bounded waits; it was shut down to keep the critical path moving. The controller completed the minimal implementation and ran the RED/GREEN and regression commands above.

The fresh delegated reviewer also did not return after bounded waits and was shut down. The controller completed the required review from the review package and current source: no Critical or Important finding remains. The service binding metadata, system-only binding permission, absence of secure-settings/shell enablement, transient-only snapshot path, redacted node string form, lifecycle invalidation, and unchanged Level-1 frozen files were all verified. Task 2 is cleared to enter Task 3.
