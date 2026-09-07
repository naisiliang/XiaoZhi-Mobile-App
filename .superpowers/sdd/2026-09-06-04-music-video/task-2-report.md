# Phase 3 — Task 2 report: typed music intents and generic adapter contract

## Scope

- Plan: `docs/superpowers/plans/2026-09-06-04-music-video.md`
- Base commit: `ca1cc0f`
- Implementation commit: `e8d31c6`
- Branch: `recovery/v0.7.0-golden-first-full`
- Worktree: `E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full`

## TDD evidence

RED was confirmed before implementation:

- `python tools/test_v070_music_intent_contract.py` failed because the typed
  intent, resolver, and adapter contract did not exist.
- `gradle :app:testDebugUnitTest --tests '*MusicIntentTest*'` failed to compile
  with unresolved intent/resolver/adapter references.
- A later edge-case RED test demonstrated that an empty search target could
  throw instead of requesting clarification.

GREEN added:

- `MusicIntentType` for open, play, pause, previous, next, search, first, and
  second selection.
- `MusicSearchQuery` with separate song and artist fields, plus invariant
  checks on search-only payloads.
- `MusicIntentResolver` with bounded Chinese command recognition, structured
  song/artist extraction, fail-closed unsupported handling, and explicit
  clarification results for conflicting, ambiguous, or incomplete commands.
- `MusicAppAdapter` and `MusicAdapterResolution`, whose proposed action is a
  `UiActionProposal` bound to the current semantic screen context. The
  contract has no accessibility driver, dispatcher, coordinate sequence, or
  execution entry point.

The empty-search edge case now returns `MISSING_SEARCH_TARGET` clarification
instead of throwing.

## Verification

All commands below passed after the implementation commit:

```text
python tools/test_v070_music_intent_contract.py
gradle :app:testDebugUnitTest --tests '*MusicIntentTest*'
gradle :app:testDebugUnitTest
python tools/test_v070_phase0_recovery_gate.py
python tools/test_v070_phase1_screen_gate.py
python tools/test_v070_messaging_adapter_contract.py
python tools/test_v070_messaging_confirmation_contract.py
python tools/test_v070_messaging_coordinator_contract.py
python tools/test_v070_messaging_send_result_contract.py
python tools/test_v070_messaging_tool_contract.py
python tools/test_v060_settings.py
python tools/test_v060_ui_source.py
python tools/test_v060_security.py
python tools/test_v060_safe_tools.py
git diff --check e8d31c6^ e8d31c6
```

The Phase 0 run used the bundled Kotlin shim and passed the v0.6.5 frozen
baseline. No Level-1 frozen KWS file or frozen KWS parameter was modified.

## Review gate

Two fresh delegated reviewers were started after `e8d31c6`. Neither returned a
verdict within the 90-second bounded wait; both were closed as unavailable.
Fresh controller-side review found no unresolved Critical or Important issue:
the commit boundary is limited to the planned media contract, forbidden UI
execution dependencies are absent, and stale screen identity remains guarded
by `UiActionProposal`.

## Device gate

`DEVICE_GATE_PENDING` — no authorized Android device or usable emulator was
available. No real-device success is claimed.
