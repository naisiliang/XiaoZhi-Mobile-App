# Messaging Task 7 report: guarded tool and agent integration

## Scope

- Base: `398c6da`
- Commit: `3943b86 feat: expose guarded messaging tools`
- Branch: `recovery/v0.7.0-golden-first-full`
- Worktree: `E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full`

## TDD evidence

The RED test and static contract were added before the registry and adapter.
The static check failed because the messaging declaration and registry did
not exist; the focused Kotlin test failed to compile with unresolved
`ToolRegistry`, `MessagingToolAdapter`, and `MessagingToolResolution` symbols.
The minimal GREEN implementation then made the strict declaration, typed
request conversion, central-confirmation, and no-executor tests pass.

## Implementation

- Added the app-owned `ToolRegistry` declaration boundary and registered one
  new messaging capability: `send_text_message`.
- The declaration is limited to `packageName`, `contactReference`, and `body`,
  and the adapter accepts only the supported WeChat (`com.tencent.mm`) and QQ
  (`com.tencent.mobileqq`) package identities. Missing/extra arguments and
  unsupported packages fail closed.
- `AiOrchestrator` now consumes the registry declaration, so native and strict
  JSON tool paths can request the same ordinary-text capability. Its planner
  instructions explicitly preserve ordinary-message confirmation and reject
  unsupported message types and sensitive/destructive operations.
- The adapter only returns a typed `MessagingRequest`; it has no UI driver,
  Accessibility call, send executor, confirmation bypass, or retry path.
  `ToolDispatcher` plus `CentralSafetyPolicyEngine` still returns
  `CONFIRMATION_REQUIRED` before any registered executor can run.

No Level-1 frozen KWS source was changed.

## Review

Two fresh delegated reviewers were requested for `398c6da..3943b86`. Both
remained running through the bounded 90-second wait and were closed without a
verdict. They are recorded as unavailable, not CLEAN.

The controller performed a read-only spec-compliance and code-quality/
security review of the final range. It found no unresolved Critical or
Important issue: only ordinary text is declared, arguments are strict, the
central policy remains `CONFIRM`, and no lower-level send/UI executor is
exposed to the adapter.

## Verification

```text
python tools/test_v070_messaging_tool_contract.py           PASS
python tools/test_v060_ai_orchestrator.py                   PASS
python tools/test_v060_security.py                          PASS
python tools/test_v070_messaging_send_result_contract.py    PASS
python tools/test_v070_messaging_confirmation_contract.py   PASS
python tools/test_v070_messaging_coordinator_contract.py    PASS
python tools/test_v070_messaging_adapter_contract.py        PASS
python tools/test_v070_phase1_screen_gate.py                PASS
python tools/test_v070_accessibility_contract.py             PASS
python tools/test_v070_sensitive_vision_zero_call.py        PASS
python tools/test_v070_phase0_recovery_gate.py               PASS
gradle :app:testDebugUnitTest :app:assembleDebug             PASS
git diff --check                                           PASS
```

No Android device or emulator was available; device validation remains
`DEVICE_GATE_PENDING`.
