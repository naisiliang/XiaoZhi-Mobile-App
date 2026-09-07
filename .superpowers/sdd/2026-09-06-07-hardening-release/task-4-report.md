# Phase 6 Task 4: Diagnostics Privacy and Health

## TDD and implementation

- RED: `DiagnosticRecorderTest` and `CommandResultNotifierTest` initially
  failed to compile because the central recorder and notification deduplication
  behavior did not exist; the health monitor also had no diagnostic sink.
- GREEN: added a bounded, synchronized in-memory `DiagnosticRecorder`.
  `DiagnosticEvent` remains the privacy boundary for metadata; notification
  errors are fingerprinted after sanitization and deduplicated for a bounded
  window. Media-volume events retain the real requested/before/target/after/max
  step values, actual percentage, fixed-volume flag, and retry count.
- Integrated optional safe health events into `CapabilityHealthMonitor`.
  Failure reasons are never copied into diagnostics; only capability, state,
  failure count, and a boolean reason-present marker are recorded.
- Tightened `CommandResultNotifier.failure()` so identical error text is not
  republished during the retained notification window. Success and explicit
  retention clearing reset the failure dedup boundary.

## Verification

- Focused tests passed for diagnostic privacy, volume step preservation,
  notification-error deduplication, health-event redaction, and existing
  diagnostic/health behavior.
- Full `tools/test_v070_phase0_recovery_gate.py` passed after adding the
  configured Kotlin shim to PATH.
- Full Gradle command passed:
  `:app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`.
- Commit: `4c1660e feat: harden diagnostics and notification health`.
- Post-commit spec, quality, and security review found no unresolved Critical
  or Important issue. No Level-1 frozen KWS source was changed.
- `DEVICE_GATE_PENDING`: no authorized Android device or emulator is available
  for APK or runtime validation.
