# SDD ledger — plan: C:/Users/ASUS/Downloads/XiaoZhi-v0.7.0-Golden-First-Full-Recovery-Codex-Pack-extracted-20260906-054744/XiaoZhi-v0.7.0-Golden-First-Full-Recovery-Codex-Pack/docs/superpowers/plans/2026-09-06-02-screen-intelligence.md

## Preflight plan scan

| Tasks / surface | Producer and consumer relationship | Finding and ruling |
|---|---|---|
| T1 → T2 | T1 `ScreenContext`/generation store is the source consumed by accessibility snapshots. | Compatible. T2 publishes immutable ephemeral snapshots through the T1 store; no raw tree persistence. |
| T1 → T3 | T1 generation/window identity scopes the sensitive-screen decision. | Compatible. T3 receives the current context and never broadens its lifetime. |
| T1 → T4 | T4 resolves references against the current generation. | Compatible. T4 must reject expired or invalidated contexts rather than guess. |
| T1 → T5 | T5 revalidates proposals against the stored generation and semantic node identity. | Compatible. Coordinates are not an execution identity. |
| T1 → T7 | Vision candidates are tied to the current generation. | Compatible. T7 uses the store only for transient structured candidates. |
| T1 → T8 | Settings/status surface reports whether a current context exists. | Compatible. T8 reads status only and does not persist node trees. |
| T2 → T8 | T2 supplies user-enabled Accessibility status/system-settings entry; T8 renders it. | Compatible. Ruling: no API or hidden path will attempt to enable Accessibility programmatically. |
| T3 → T7 | SensitiveScreenDetector gates every Vision path. | Compatible. Ruling: high-risk signals fail closed before capture/analyzer invocation. |
| T4 → T5 | Contextual resolution supplies semantic targets for executor re-find. | Compatible. T5 revalidates again immediately before action. |
| T4 → T7 | Vision candidates may be resolved only within the current context. | Compatible. Ambiguous or stale candidates remain blocked. |
| T5 → T6 | Generic executor is the registry fallback; adapters may enrich semantics. | Compatible. Ruling: adapters cannot bypass CentralSafetyPolicy/PermissionBroker-equivalent safety checks. |
| T5 → T8 | Executor status can be surfaced by the UI integration. | Compatible. T8 changes status/cards only, never execution logic. |
| T6 → T8 | Adapter registry status may be displayed later. | Compatible. No adapter-specific UI is required to complete the stage. |
| T7 → T8 | Vision authorization/capture status is shown in Settings. | Compatible. Ruling: Settings may launch the platform consent flow, but cannot grant or retain a session beyond its lifecycle. |
| T2 ↔ T5 | Both touch the accessibility package but create disjoint classes/config surfaces. | Compatible. T2 owns snapshot publication; T5 owns proposal execution. |
| T3 ↔ T4 | Both read screen semantics. | Compatible. T3 is a safety gate; T4 is a no-guess resolver, with safety winning on conflict. |
| T5 ↔ T6 | Both touch action execution. | Compatible. Registry delegates to adapters or generic executor and preserves the same safety boundary. |
| T7 ↔ T8 | Both touch Vision authorization state. | Compatible. T7 owns authorization/capture lifecycle; T8 only binds status and consent action. |

## Per-task self-consistency

| Task | Tests versus implementation/files | Finding and ruling |
|---|---|---|
| T1 | Store model tests cover generation, fingerprint, expiry, and window invalidation; files are self-contained. | Consistent; no raw node persistence. |
| T2 | Contract checks manifest metadata/config and transient snapshot fields; service/config files provide those surfaces. | Consistent; system Settings is the only enable path. |
| T3 | Unit cases cover high-risk and ordinary screens; detector can fail closed from package/labels/input flags. | Consistent; no Vision call belongs in this task. |
| T4 | Resolver tests cover ordinal/current references and confidence levels; resolution file is separate from model/store. | Consistent; insufficient/ambiguous input is not guessed. |
| T5 | Executor tests cover stale/missing/unique semantic nodes and action types; proposal/executor files are disjoint from adapters. | Consistent; saved coordinates are never executable. |
| T6 | Registry tests cover preferred adapter, generic fallback, and safety preservation; map adapter does not rewrite MapController. | Consistent. |
| T7 | Authorization and zero-call tests cover no grant, sensitive screen, session end, and process restart; coordinator/analyzer remain transient. | Consistent; MediaProjection is session-scoped only. |
| T8 | Phase gate can sequence predecessor gate, screen tests, and Gradle tests; Settings/Main modifications are status-only. | Consistent. |

## Rulings

- Ruling: use the existing `SafeToolExecutor`/central policy boundary when this branch has no class literally named `PermissionBroker` or `CentralSafetyPolicy` — why: the plan requires preservation of the existing safety authority, not a parallel bypass; cost if wrong: a later review may require an adapter layer or rename.
- Ruling: keep all screen snapshots and Vision frames in memory only — why: the recovery spec explicitly forbids persistence of raw screen data; cost if wrong: contextual behavior has less post-restart continuity, but privacy is preserved.
- Ruling: update the legacy v0.6 security contract when Task 2 adds the explicitly planned user-enabled Accessibility service — why: the old contract forbids the capability because it was out of alpha1 scope, while the Phase 1 spec now requires it; the replacement must assert non-programmatic enablement and safety boundaries instead of deleting the security gate; cost if wrong: an over-broad migration could weaken the old capability policy, so the Phase 1 contract must stay fail-closed for sensitive actions.

## Task status

- Task 1: complete (`565c02f..48fb303`)
- Task 2: complete (`2b7254f`)
- Task 3: complete (`c3ea33c`)
- Task 4: complete (`79138b3`)
- Task 5: pending
- Task 6: pending
- Task 7: pending
- Task 8: pending

Task 1 manual review finding: `ScreenNode.children` is exposed as a read-only Kotlin `List`, but the constructor retains the caller's mutable list; this under-proves the brief's immutable-model requirement. Delegated reviewer did not return; fix round 1 is dispatched to add defensive immutable storage and a regression test before Task 2.

Task 1 fix round 1/5: the defensive child snapshot and external-mutation regression were added; RED failed on both source-list and exposed-list mutation, GREEN passed focused JUnit, and the Phase 0 gate passed. Fix commits `2410e29`/`48fb303` (the latter records the report).

Task 1 complete (commits `565c02f..48fb303`, delegated review unavailable; manual review and scoped re-review found no unresolved Critical or Important issue). The store is process-memory-only, generation-scoped, TTL-bounded, fingerprint-checked, and does not clear a current context on stale lookup.

Task 2 complete (`2b7254f`; delegated fresh review unavailable after bounded waits). Controller spec-compliance and code-quality/security review found no unresolved Critical or Important issue: the service is framework-bound with `BIND_ACCESSIBILITY_SERVICE`, requires user system-settings enablement, publishes only transient semantic snapshots, redacts `ScreenNode.toString`, invalidates on lifecycle/snapshot loss, and leaves all Level-1 frozen KWS sources unchanged. The Task 2 contract, migrated v0.6 security contract, unit tests, and Phase 0 gate are GREEN.

Task 3 complete (`c3ea33c`; delegated implementation and fresh review unavailable after bounded waits). Controller review found no unresolved Critical or Important issue: SensitiveScreenDetector covers high-risk semantic/package/password signals, remains conservative for missing context, does not add credential fields to Accessibility snapshots, and preserves ordinary map/list/chat/navigation behavior. Focused/full unit tests and the Phase 0 gate are GREEN.

Task 4 complete (`8094103`, `783b2cd`, `639e668`, `79138b3`; final fresh reviewers terminated with the account usage-limit error). The first fresh review returned BLOCK and all listed Important findings were verified, covered by RED regressions, and fixed: exact live Store snapshot validation, current/recent/prompt/session source separation, current candidates for `THIS`/`CURRENT`, bounded reference parsing and global negation rejection, wrapped Chinese next handling, current-video role filtering, stale next-anchor rejection, duplicate-ID conflict rejection, and immutable resolution alternatives. Controller spec-compliance and code-quality/security review of `3f71bab..79138b3` found no unresolved Critical or Important issue. Focused/full unit tests, legacy security, and the Phase 0 gate are GREEN; device validation remains `DEVICE_GATE_PENDING`.

Task 5 complete (`eb51488`; two fresh delegated reviewers were unavailable after bounded waits and follow-up, so no external CLEAN verdict is claimed). The controller review of `e4ca7fe..eb51488` found no unresolved Critical or Important issue: the generic executor is context-bound, semantic-only, fail-closed for stale/missing/ambiguous/sensitive states, and routed through PermissionBroker plus CentralSafetyPolicy before node lookup. Focused/full unit tests, Accessibility contract, legacy security, and Phase 0 gate are GREEN; device validation remains `DEVICE_GATE_PENDING`.
