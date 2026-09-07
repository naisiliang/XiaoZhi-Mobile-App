# Task 5 report: revalidated accessibility actions

## Scope

- Base: `e4ca7fe`
- Head: `eb51488`
- Branch: `recovery/v0.7.0-golden-first-full`
- Worktree: `E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full`
- Commit: `eb51488 feat: execute revalidated accessibility actions`

## TDD evidence

The focused tests were added before the production proposal/executor types. The
RED command was:

```text
gradle :app:testDebugUnitTest --tests '*GenericAccessibilityExecutorTest*' --stacktrace
```

It failed during `compileDebugUnitTestKotlin` with unresolved references to
`UiActionProposal`, `UiActionType`, `CurrentAccessibilityNode`,
`AccessibilityNodeFinder`, `AccessibilityActionDriver`, and
`GenericAccessibilityExecutor`.

The minimal GREEN implementation then passed the same focused command. The
tests cover stale generation, missing targets without coordinate fallback,
duplicate targets, unique CLICK/SELECT/BACK/NEXT execution, change during
re-find, permission denial, central policy blocking, sensitive screens, and
semantic-label mismatch.

## Implementation

- Added a context-bound `UiActionProposal` with no coordinate or bounds field.
- Added a live semantic `CurrentAccessibilityNode` plus injected finder and
  action-driver bridges; the live abstraction contains no saved coordinates.
- Routed every action through `ToolDispatcher`, so `PermissionBroker` runs
  before `CentralSafetyPolicyEngine` and no node lookup occurs for denied or
  blocked actions.
- Added only `ui_click`, `ui_select`, `ui_back`, and `ui_next` as ordinary
  central-policy-allowed tool names; the existing SafeTool allowlist and
  destructive restrictions remain unchanged.
- Revalidated the exact current context, package, window, generation, node id,
  and semantic label immediately before invoking the action driver.
- Failed closed for stale context, sensitive screen, missing/ambiguous node,
  bridge exception, and unsuccessful driver execution.

No Level-1 frozen KWS source was changed. The task implementation does not
persist or log screen text, node trees, coordinates, or accessibility data.

## Review

Two fresh delegated reviewers were requested: one for spec compliance and one
for code quality/security. They remained running through two bounded waits and
one concise-result follow-up, returned no verdict, and were then closed. Their
status is recorded as unavailable, not CLEAN.

The controller performed a read-only review of the exact `e4ca7fe..eb51488`
range. It found no unresolved Critical or Important issue:

- the implementation is limited to the Task 5 files plus the required central
  policy registrations;
- permission and policy gates precede finder/driver calls;
- sensitive, stale, mismatched, missing, and ambiguous states fail closed;
- no saved coordinate is accepted or executed; and
- all Level-1 frozen KWS paths remain unchanged.

## Verification

```text
gradle :app:testDebugUnitTest --tests '*GenericAccessibilityExecutorTest*'  PASS
gradle :app:testDebugUnitTest                                      PASS
python tools/test_v070_accessibility_contract.py                   PASS
python tools/test_v060_security.py                                 PASS
python tools/test_v070_phase0_recovery_gate.py                     PASS
git diff --check e4ca7fe..eb51488                                   PASS
```

No Android device or emulator was available; device validation remains
`DEVICE_GATE_PENDING`.
