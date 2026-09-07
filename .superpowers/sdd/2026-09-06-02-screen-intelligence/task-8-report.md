# Task 8 report: Settings/UI integration and Phase 1 gate

## Scope

- Base: `9a99707`
- Checkpoint: `aaf2487`
- Branch: `recovery/v0.7.0-golden-first-full`
- Worktree: `E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full`
- Commit: `aaf2487 checkpoint: v070 phase1 screen intelligence`

## TDD evidence

The Phase 1 static gate was added before the UI/status implementation. The RED
command was:

```text
python tools/test_v070_phase1_screen_gate.py
```

It failed because the Settings layout did not yet contain the
`smart_ui_enabled` control. After the minimal wiring, the Phase 1 gate passed.
The first Phase 0 run also exposed an alpha1 boundary regression because the
new MainActivity status code contained an `Accessibility` runtime marker. The
MainActivity change was reduced to status-only smart-UI display, while the
actual Accessibility inspection remained in SettingsActivity; the full Phase
0 gate then passed.

## Implementation

- Added the persisted, default-off `SettingsStore.smartUiEnabled` opt-in.
- Added Settings controls for the smart-UI switch, user-controlled
  Accessibility status, system Accessibility Settings entry, Vision consent
  status, current-session frame status, and explicit refresh action.
- Accessibility status is read from the framework's enabled service list and
  matches only this app's declared `XiaoZhiAccessibilityService`. The app does
  not write secure settings or attempt to enable/disable the service itself.
- Vision UI text remains fail-closed and truthful: the current session is not
  authorized/capturing by default, capture is limited to a current non-sensitive
  frame, and raw screenshots are not saved.
- MainActivity only appends the persisted smart-UI state to its existing
  runtime status line; it does not execute screen actions or own Accessibility
  permission flows.
- Updated the typed-pipeline contract allowlist for the planned system
  `ACTION_ACCESSIBILITY_SETTINGS` navigation. Existing device-action routes
  remain protected by the same contract.

No Level-1 frozen KWS source was changed.

## Review

Two fresh delegated reviewers were requested for `9a99707..aaf2487`. Both
remained running through two bounded waits and were closed without a verdict.
Their status is recorded as unavailable, not CLEAN.

The controller performed a read-only spec, quality, and security review of the
same range and found no unresolved Critical or Important issue:

- Settings uses the Android framework's read-only Accessibility status API and
  opens only the user-facing system settings page;
- no secure-settings write, shell command, coordinate action, or direct device
  execution was added;
- the smart-UI toggle is persisted with a default-off posture;
- Vision status explicitly remains session-scoped and non-persistent; and
- MainActivity remains a status-only consumer, preserving the alpha1 chat and
  P0 execution paths.

## Verification

```text
python tools/test_v070_phase1_screen_gate.py                  PASS
python tools/test_v070_accessibility_contract.py              PASS
python tools/test_v070_sensitive_vision_zero_call.py          PASS
python tools/test_v070_recovery_settings_parity.py            PASS
python tools/test_v070_recovery_ui_contract.py                 PASS
python tools/test_v070_phase0_recovery_gate.py                 PASS
gradle :app:testDebugUnitTest                                  PASS
gradle :app:assembleDebug                                      PASS
git diff --check                                                PASS
```

No Android device or emulator was available; device validation remains
`DEVICE_GATE_PENDING`.
