# Phase 6 Task 5: Long-run, Permission, Network, and Database Hardening

## TDD and implementation

- RED: bounded-history tests initially failed because neither repository had a
  paged history entry point and the history activity loaded all sessions on the
  UI path. A lifecycle RED also proved that the history repository was not
  closed on activity destruction.
- GREEN: added validated `ConversationHistoryPage`/`ConversationHistoryPagination`,
  bounded `loadHistoryPage()` methods to both conversation repositories, a
  deterministic `LIMIT ... OFFSET ...` query with one look-ahead row, and a
  version-2 migration that only adds indexes. The history activity now uses a
  bounded page and closes its repository. Public page shaping applies offset
  exactly once; database-window shaping avoids a second offset.
- RED: source lifecycle tests proved that `AiClient`'s executor and location
  request handles had no destruction boundary.
- GREEN: `AiClient` now exposes `AutoCloseable` and Settings closes it;
  `LocationProvider` removes the timeout callback, cancels modern requests,
  and removes legacy listeners on every terminal result.
- Existing simulations covered service/session restart, microphone and
  overlay permission paths, accessibility-disabled behavior, location denial,
  offline/timeout/rate-limit result mapping, unsupported provider capability,
  plugin import/permission failure, MCP timeout/unavailable failure, and
  private conversation schema/recovery. No additional product change was
  needed for those already passing boundaries.

## Verification

- Focused RED/GREEN tests passed for pagination windows, invalid page bounds,
  schema migration indexes, history lifecycle cleanup, AI executor cleanup,
  and location request cleanup.
- Full `tools/test_v070_phase0_recovery_gate.py` passed, including Frozen Source
  Guard, historical behavior, P0 runtime/UI, security, alpha1 recovery, and
  project validation contracts. History contracts now explicitly require the
  bounded page and reject unbounded loading in the history activity.
- Full Gradle command passed:
  `:app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`.
- Commits: `e2e823e`, `3d789a2`, `6bc84ab`, and `88bcd8c`.
- Post-commit spec, quality, and security review found no unresolved Critical
  or Important issue. No Level-1 frozen KWS source was changed.
- `DEVICE_GATE_PENDING`: no authorized Android device or emulator is available;
  the 30-minute ANR/leak loop and permission-toggle runtime actions remain
  pending real-device or emulator access.
