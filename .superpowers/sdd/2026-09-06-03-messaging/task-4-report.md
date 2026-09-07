# Messaging Task 4 report: safe target resolution

## Scope

- Base: `9e0f790`
- Commit: `7f257d1 feat: resolve messaging targets safely`
- Branch: `recovery/v0.7.0-golden-first-full`
- Worktree: `E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full`

## TDD evidence

The coordinator fixture tests were added before `MessagingCoordinator`,
`ContactCandidate`, and the resolved-contact state existed. The RED command
failed at test compilation with unresolved `MessagingRequest`,
`MessagingCoordinator`, and `CONTACT_RESOLVED` references. The minimal
coordinator then made the unique-contact, same-name-selection, pronoun, and
sensitive-body tests GREEN.

## Implementation

- Added a context-bound `ContactCandidate` and an in-memory
  `MessagingCoordinator` state machine.
- Unique contacts produce an opening-chat semantic CLICK proposal; same-name
  contacts remain in `NEEDS_CONTACT_SELECTION` until an exact id is selected.
- `他/她` and equivalent references resolve only through one unique current
  chat identity. Ambiguous or missing identities fail closed.
- Post-navigation validation checks package and exact current-chat name,
  locates one input and one send control, creates `PendingMessage` and a
  fixed-lifetime `MessageConfirmationToken`, and emits a confirmation card.
- The coordinator stops in `WAITING_CONFIRMATION`; it never types, clicks
  send, invokes Accessibility APIs, persists the body, or retries.
- Credential-like bodies are rejected before the request is retained by the
  coordinator.

No Level-1 frozen KWS source was changed.

## Review

Two fresh delegated reviewers were requested for `9e0f790..7f257d1`. Both
remained running through bounded waits and were closed without a verdict.
They are recorded as unavailable, not CLEAN.

The controller performed a read-only spec-compliance and code-quality/
security review of the final range. It found no unresolved Critical or
Important issue: the state path reaches confirmation without sending, contact
and current-chat ambiguity fails closed, and no direct UI executor is used.

## Verification

```text
gradle :app:testDebugUnitTest --tests '*MessagingCoordinatorTest*'  PASS
python tools/test_v070_messaging_coordinator_contract.py              PASS
python tools/test_v070_messaging_adapter_contract.py                  PASS
python tools/test_v060_security.py                                    PASS
python tools/test_v070_phase0_recovery_gate.py                        PASS
gradle :app:testDebugUnitTest                                        PASS
git diff --check                                                      PASS
```

No Android device or emulator was available; device validation remains
`DEVICE_GATE_PENDING`.
