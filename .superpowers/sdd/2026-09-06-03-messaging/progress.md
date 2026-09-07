# SDD ledger — plan: 2026-09-06-03-messaging.md

## Task status

- Task 1: complete (`c1c72bd`, hardening `b2cdb33`; delegated review unavailable, local review found no unresolved Critical or Important issue)
- Task 2: complete (`6732b3a`; delegated review unavailable, local review found no unresolved Critical or Important issue)
- Task 3: complete (`cbd4988`, hardening `bf9dfc7` and `96e2ba7`; delegated review unavailable, local review found no unresolved Critical or Important issue)
- Task 4: complete (`7f257d1`; delegated review unavailable, local review found no unresolved Critical or Important issue)
- Task 5: pending
- Task 6: pending
- Task 7: pending
- Task 8: pending

Task 1 is the immutable foundation: its confirmation token binds the exact
ordinary-text messaging request to the app, contact, title, body, generation,
and window, expires after exactly 60,000 ms, and has no send or persistence
side effect. Device validation remains `DEVICE_GATE_PENDING`.

Task 3 adds semantic-only WeChat and QQ adapters. They resolve exact contacts,
refuse ambiguous matches, expose current chat/input/send candidates, and emit
only context-bound proposals; execution and safety revalidation remain in the
central generic executor. Search-result pages are recognized as list context.
