## Ruling 3 — Spec-Alignment Task A9: DiagnosticEvent foundation

**Contract**
- Fields: `timestamp`, `sessionId`, `module`, `action`, `resultCode`, `durationMs`, `safeMetadata`.
- The construction path used by production code must not retain API keys, passwords, OTP/verification-code values, payment information, or raw screenshot data in `safeMetadata`.
- Ordinary safe diagnostic values such as media-volume before/after step may remain.
- This is a foundation only. Do not build the rc2 DiagnosticRecorder/health system here.
