# Phase 2 checkpoint: WeChat / QQ ordinary-text messaging

## Scope

- Base: `f942dff`
- Checkpoint commit: `ee3ab59249a49e013e7c493d1c298dd2086162d9` (`checkpoint: v070 phase2 messaging`)
- Branch: `recovery/v0.7.0-golden-first-full`
- Worktree: `E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full`

## Acceptance matrix

The completed messaging stage covers unique-contact resolution, same-name
selection, current-chat references, exact-body confirmation, edited-body
invalidation, cancellation, token expiry, page/generation changes, stale live
context rejection, `SEND_FAILED`, `SEND_UNVERIFIED`, exact outgoing-bubble
proof, and zero automatic retries. Tool/Agent-facing declarations expose only
ordinary text and remain behind central `CONFIRM` policy.

## Gate evidence

```text
python tools/test_v070_phase0_recovery_gate.py                  PASS
python tools/test_v070_phase1_screen_gate.py                   PASS
python tools/test_v070_accessibility_contract.py               PASS
python tools/test_v070_sensitive_vision_zero_call.py            PASS
python tools/test_v060_security.py                              PASS
python tools/test_v070_messaging_adapter_contract.py            PASS
python tools/test_v070_messaging_coordinator_contract.py         PASS
python tools/test_v070_messaging_confirmation_contract.py        PASS
python tools/test_v070_messaging_send_result_contract.py         PASS
python tools/test_v070_messaging_tool_contract.py                PASS
gradle :app:testDebugUnitTest :app:assembleDebug                PASS
git diff --check                                                PASS
```

The frozen-source guard confirmed that
`WakePhraseCompiler.kt`, `WakePhraseManager.kt`, and `Pinyin4jProvider.kt`
remain unchanged since Golden commit
`324dd5a53d404490bc4a32ed1f9ce8c45671ed24`.

No Android device or emulator was available. Device validation remains
`DEVICE_GATE_PENDING`; no APK was installed or represented as device-tested.

## Review gate

Two fresh delegated reviewers were requested for the checkpoint range and
both remained running through the bounded 90-second wait; they were closed
without a verdict and are recorded as unavailable, not CLEAN. The controller
performed a read-only spec-compliance and code-quality/security review and
found no unresolved Critical or Important issue. The phase checkpoint is
closed for continuation; device validation remains separately pending.
