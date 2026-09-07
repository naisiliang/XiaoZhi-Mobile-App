# Phase 6 Task 2: Process Death Invalidation

## TDD and implementation

- RED: `ProcessRecoveryCoordinatorTest` initially failed to compile because
  the process-recovery coordinator, continuation result, and repository
  recovery APIs did not exist.
- GREEN: added one-time, idempotent process recovery that closes active
  sessions as history with `INVALIDATED_ON_PROCESS_RESTART`, reads completed
  sessions, verifies completed Artifact bytes against private-path metadata,
  and changes persisted `STAGED` Artifact records to `INTERRUPTED`.
- Added an explicit continuation result: an interrupted Artifact cannot be
  resumed without user confirmation, and the coordinator never starts a
  generator itself.
- Added concrete invalidator binding for the messaging coordinator, screen
  context/UI proposals, and vision/MediaProjection authorization. Callback
  failures are reported while the remaining boundaries are still invalidated
  fail-closed.
- Tightened the session repository contract with real `loadAll()` support in
  both SQLite implementations and tests.

## Verification

- Focused RED/GREEN tests passed for process recovery, idempotence,
  invalidator failure isolation, concrete message/screen/vision invalidation,
  messaging confirmation invalidation, session persistence, and Artifact
  integrity.
- Full `tools/test_v070_phase0_recovery_gate.py` passed.
- Full Gradle command passed:
  `:app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`.
- Commit: `86ce178 feat: add process death recovery boundaries`.
- Post-commit spec, quality, and security review found no unresolved Critical
  or Important issue. The diff contains no Level-1 frozen KWS source changes.
- `DEVICE_GATE_PENDING`: no authorized Android device or emulator is available
  for APK or runtime validation.
