# SDD ledger — plan: 2026-09-06-07-hardening-release.md

## Task status

- Task 1: complete (`a617313` implementation, `050c469` terminal-category guard; delegated review bounded separately, fresh local spec/quality/security review found no unresolved Critical or Important issue)
- Task 2: complete (`86ce178` implementation and process-bound invalidation; focused tests, full Phase 0 gate, and full Gradle build passed; fresh local spec/quality/security review found no unresolved Critical or Important issue)
- Task 3: complete (`a845f8f`, `fae7df8`; focused tests, full Phase 0 gate, and full Gradle build passed; fresh local spec/quality/security review found no unresolved Critical or Important issue)
- Task 4: complete (`4c1660e`; focused diagnostics/health/notifier tests, full
  Phase 0 gate, and full Gradle build passed; fresh local spec/quality/security
  review found no unresolved Critical or Important issue)
- Task 5: complete (`e2e823e`, `3d789a2`, `6bc84ab`, `88bcd8c`; focused
  RED/GREEN hardening tests, full Phase 0 gate, full unit tests, and full
  Gradle build passed; fresh local spec/quality/security review found no
  unresolved Critical or Important issue)
- Task 6: complete (responsive UI evidence captured on API 35 emulator at all
  four required dp sizes and both font scales; 24/24 hierarchy boundary checks
  passed; IME composer controls stayed above the keyboard; source UI contracts
  passed; real spoken custom-wake acceptance remains DEVICE_GATE_PENDING)
- Task 7: complete (`15f76fd`; RED/GREEN release-gate and APK-validator
  contracts, all final-gate stages, unit tests, Debug assembly, and exact RC
  APK validation passed; delegated review timed out, but fresh local
  spec/quality/security review found no unresolved Critical or Important issue)
- Task 8: pending

The recovery branch remains isolated from `main` and
`feature/v0.7.0-smart-agent`. Level-1 KWS sources remain frozen against
Golden commit `324dd5a53d404490bc4a32ed1f9ce8c45671ed24`. Device validation
remains `DEVICE_GATE_PENDING`.
