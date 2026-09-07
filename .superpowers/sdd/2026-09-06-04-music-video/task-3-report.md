# Phase 3 — Task 3 report: four music semantic adapters

## Scope

- Plan: `docs/superpowers/plans/2026-09-06-04-music-video.md`
- Base commit: `ffecbcf`
- Commits: `07946a5`, `06314da`, `db1ef95`, `c0dce98`, `8ea4834`, `85ab888`
- Branch: `recovery/v0.7.0-golden-first-full`
- Worktree: `E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full`

## TDD evidence

Each adapter started with an independent semantic fixture and a failing
focused test for the missing adapter class. The fixtures use different control
role/label variants and cover search, play/pause, previous/next, first/second
result selection, package isolation, and generation binding.

GREEN added:

- `NetEaseMusicAdapter` for `com.netease.cloudmusic`.
- `QishuiMusicAdapter` for `com.luna.music`.
- `KugouMusicAdapter` for `com.kugou.android`.
- `QqMusicAdapter` for `com.tencent.qqmusic`.
- `MusicSemanticAdapter` shared semantic extraction that returns
  context-bound `UiActionProposal` values and retains the typed intent for
  search payloads.

The adapters only describe actions. `OPEN` remains unsupported at this UI
layer because app launch belongs to the resolver/launcher path; no fake click
is emitted. Duplicate controls return `AMBIGUOUS_TARGET`; result selection is
ordered from the current snapshot and carries its generation/package/window
identity. English role matching uses token boundaries to avoid treating the
`player` prefix as a play control.

## Verification

All commands below passed after the final adapter hardening commit:

```text
python tools/test_v070_music_adapters_contract.py
python tools/test_v070_music_intent_contract.py
gradle :app:testDebugUnitTest --tests '*MusicIntentTest*' --tests '*NetEaseMusicAdapterTest*' --tests '*QishuiMusicAdapterTest*' --tests '*KugouMusicAdapterTest*' --tests '*QqMusicAdapterTest*'
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
git diff --check
```

The P0 run passed with the bundled Kotlin shim and v0.6.5 frozen baseline. No
Level-1 frozen KWS file or frozen KWS parameter was modified. No forbidden
accessibility executor, coordinate, or raw click-sequence dependency appears
in the adapter layer.

## Review gate

Two fresh delegated reviewers were started for spec compliance and
quality/security. Neither returned a verdict within the 90-second bounded
wait; both were closed as unavailable. Fresh controller-side review found no
unresolved Critical or Important issue. The review limitation is recorded
explicitly rather than treated as a delegated approval.

## Device gate

`DEVICE_GATE_PENDING` — no authorized Android device or usable emulator was
available. No real-device success is claimed.
