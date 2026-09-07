# Phase 6 Task 1: Unified ExecutionError / RecoveryCoordinator

## TDD and implementation

- RED: `RecoveryCoordinatorTest` initially failed to compile because the
  recovery production types did not exist.
- GREEN: added stable error domains/codes, four recovery levels, side-effect
  classification, bounded retry decisions, degradation, user questioning, and
  stop-action/keep-session decisions.
- Follow-up safety RED/GREEN: local review identified that callers could
  override a terminal category to A; `050c469` now forces all terminal default
  categories back to D and adds regression coverage.

## Verification

- Focused `RecoveryCoordinatorTest` passed after the implementation and again
  after the terminal-category guard.
- Full `tools/test_v070_phase0_recovery_gate.py` passed with the configured
  JDK 17/Kotlin toolchain.
- Full Gradle command passed:
  `:app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`.
- The test suite covers all 27 approved error codes, exact A/B/C/D levels,
  bounded safe retry, unknown/irreversible side-effect rejection,
  `MESSAGE_SEND_UNVERIFIED` retry count zero, and conversation preservation.
- Delegated review was bounded and unavailable; fresh local spec, quality, and
  security review found no unresolved Critical or Important issue.
- `DEVICE_GATE_PENDING`: no authorized Android device or emulator is available
  for APK or runtime validation.
