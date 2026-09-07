# Task 4 report: contextual intent resolution

## Scope

- Base: `3f71bab`
- Branch: `recovery/v0.7.0-golden-first-full`
- Worktree: `E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full`
- Commit target: `feat: resolve contextual screen references`

## TDD evidence

The focused resolver tests were created before the implementation. The RED command was:

```text
gradle :app:testDebugUnitTest --tests '*ContextualIntentResolverTest*' --stacktrace
```

It failed at `compileDebugUnitTestKotlin` with unresolved references to `ContextualIntentResolver`, `ContextResolutionConfidence`, `ContextCandidate`, and the related context types.

The first GREEN attempt exposed one Kotlin data-class constructor error (`Primary constructor of data class must only have property parameters`). That was fixed by making the candidate-options constructor value an explicit property and keeping the public `candidates` view defensive. The focused GREEN command then passed:

```text
gradle :app:testDebugUnitTest --tests '*ContextualIntentResolverTest*' --stacktrace
BUILD SUCCESSFUL in 3s
```

## Implementation

- Added typed `ContextCandidate`, `ContextualHistory`, `ContextResolution`, confidence, and target-kind models.
- Resolved first/second ordinal targets, this/that/current references, next targets, and current video targets.
- Returns HIGH only for one current candidate, MEDIUM with alternatives when ambiguity remains, and LOW without a target when evidence is insufficient.
- Binds every result to the active generation, package, and window fingerprint; stale candidates are excluded before resolution.
- Keeps candidates and history in memory only, performs no UI action/Vision/SafeTool call, and redacts candidate labels from accidental string logging.

No Level-1 frozen KWS source was changed. The final focused resolver suite, full unit suite, legacy security test, and Phase 0 gate were run after the final fix commit.

## Review note

The delegated implementer did not return after bounded waits and was shut down. The controller implemented the task after the recorded RED. The first fresh review returned BLOCK; its findings were verified and addressed before proceeding.

## Fix round 1

Manual review added a regression for resolving `当前视频` from an explicit current-session target when the separate candidate list is empty. The new test first failed with one assertion; the minimal fix merged generation-compatible history candidates for current-video and next resolution. The focused resolver suite, including the new case, then passed. The final full unit suite and Phase 0 gate also passed before the fix commit.

## Review and fix round 2

The first fresh review of the `3f71bab..8094103` package returned BLOCK. The follow-up fix rounds added store-backed exact snapshot validation, assistant-prompt/current-app history sources, ordinary `那个` ambiguity handling, current-video history coverage, bounded ordinal parsing, stale `next` anchor rejection, and constructor invariants. These changes were committed as `639e668` after focused GREEN, full unit, security, and Phase 0 verification.

## Review and fix round 3

The next fresh review of the `3f71bab..639e668` package returned BLOCK with four Important findings: negation applied only to ordinals, live current candidates were omitted from `这个`/`当前`, wrapped Chinese `下一个` was rejected, and recent/prompt video targets were promoted to current video. It also identified conflicting duplicate IDs and incomplete alternatives snapshotting. Regression tests were written first and failed in five cases; the minimal fixes were committed as `79138b3`.

The final package is `3f71bab..79138b3`. Two fresh reviewers were dispatched against it, but both terminated with the account usage-limit error before returning a verdict; this is recorded as unavailable, not CLEAN. The controller performed a read-only spec-compliance and code-quality/security review of the exact package and found no unresolved Critical or Important issue. The review checked the prior BLOCK list, HIGH/MEDIUM/LOW no-guessing behavior, current/recent/prompt/session source separation, identity conflicts, immutable alternatives, privacy-safe string forms, action-time revalidation documentation, and unchanged Level-1 frozen sources.

## Final verification

```text
gradle :app:testDebugUnitTest --tests '*ContextualIntentResolverTest*'  PASS
gradle :app:testDebugUnitTest                                      PASS
python tools/test_v060_security.py                                 PASS
python tools/test_v070_phase0_recovery_gate.py                     PASS
```

The Phase 0 gate was run with the configured JDK 17, Gradle 8.9, Android SDK 35, platform-tools, and Kotlin shim. No Android device or emulator was available; device validation remains `DEVICE_GATE_PENDING`.
