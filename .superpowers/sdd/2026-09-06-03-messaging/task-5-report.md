# Messaging Task 5 report: live confirmation UI and revalidation

## Scope

- Base: `7f257d1`
- Commit: `b5e9f1f feat: require live confirmation before message send`
- Branch: `recovery/v0.7.0-golden-first-full`
- Worktree: `E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full`

## TDD evidence

The confirmation tests were added before the `confirm`/`cancel` API,
`MessageSendRequest`, `REVALIDATING` state, and confirmation row existed. The
RED command failed at test compilation with unresolved confirmation methods,
state, and constructor wiring. The minimal implementation then made valid,
edited-body, changed-generation/contact-page, expired-token, cancel, and
one-shot confirmation tests GREEN.

## Implementation

- Added `MessageConfirmationCard` presentation data and a constrained
  `item_message_confirmation.xml` row with explicit confirm/cancel controls.
- Extended `ConversationAdapter` with confirmation rows, replacement/removal
  by token id, and a listener that reports CONFIRM or CANCEL without executing
  UI actions itself.
- Added `MessagingCoordinator.confirm(...)` and `cancel(...)`.
- Confirmation transitions through `REVALIDATING`, checks the exact token,
  TTL, body, package/generation/window identity, live
  `ScreenContextStore.currentIfMatches`, current-chat identity, and message
  controls before returning one `MessageSendRequest` in `SENDING`.
- Invalid or expired confirmation clears the in-memory token and pending
  message; cancel clears it and enters `CANCELLED`; a second confirm cannot
  create another request.
- No send click, text injection, Accessibility API, persistence, or retry was
  introduced. Sending/result verification is intentionally left to Task 6.

No Level-1 frozen KWS source was changed.

## Review

Two fresh delegated reviewers were requested for `7f257d1..b5e9f1f`. Both
remained running through bounded waits and were closed without a verdict.
They are recorded as unavailable, not CLEAN.

The controller performed a read-only spec-compliance and code-quality/
security review of the final range. It found no unresolved Critical or
Important issue: live context is mandatory for confirmation, exact body and
identity changes fail closed, and the UI callback only reports an action.

## Verification

```text
gradle :app:testDebugUnitTest --tests '*MessagingConfirmationTest*'  PASS
python tools/test_v070_messaging_confirmation_contract.py              PASS
python tools/test_v070_messaging_coordinator_contract.py               PASS
python tools/test_v070_messaging_adapter_contract.py                   PASS
python tools/test_v060_security.py                                     PASS
python tools/test_v070_phase0_recovery_gate.py                         PASS
gradle :app:testDebugUnitTest :app:assembleDebug                     PASS
git diff --check                                                       PASS
```

No Android device or emulator was available; device validation remains
`DEVICE_GATE_PENDING`.
