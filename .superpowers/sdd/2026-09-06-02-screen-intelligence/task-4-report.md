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

The full unit suite, legacy security test, and Phase 0 gate are the required next verification before commit. No Level-1 frozen KWS source was changed.

## Review note

The delegated implementer did not return after bounded waits and was shut down. The controller implemented the task after the recorded RED. A fresh review will be performed after the task commit before Task 5.

## Fix round 1

Manual review added a regression for resolving `当前视频` from an explicit current-session target when the separate candidate list is empty. The new test first failed with one assertion; the minimal fix merged generation-compatible history candidates for current-video and next resolution. The focused resolver suite, including the new case, then passed. The final full unit suite and Phase 0 gate also passed before the fix commit.
