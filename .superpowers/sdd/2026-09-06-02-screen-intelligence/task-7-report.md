# Task 7 report: guarded Vision fallback

## Scope

- Base: `1e32af6`
- Head: `d90f38d`
- Branch: `recovery/v0.7.0-golden-first-full`
- Worktree: `E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full`
- Commit: `d90f38d feat: add guarded vision fallback`

## TDD evidence

The JUnit and static zero-call tests were added before the three Vision
production files. The RED commands were:

```text
gradle :app:testDebugUnitTest --tests '*ScreenVisionAuthorizationTest*' --stacktrace
python tools/test_v070_sensitive_vision_zero_call.py
```

The JUnit command failed at `compileDebugUnitTestKotlin` with unresolved Vision
types; the Python command failed because the required Vision sources were
missing. After the minimal implementation, both commands passed.

## Implementation

- `VisionSessionAuthorization` requires an active session and a verifier for
  user-mediated MediaProjection consent. Its default verifier rejects, grants
  are in-memory, object-bound, session-bound, and process-bound, and session
  end/invalidation clears them.
- `ScreenVisionCaptureCoordinator` checks authorization, exact current
  `ScreenContext`, and `SensitiveScreenDetector` before capture; it captures
  one current frame, validates its generation/package/window/timestamp, and
  rechecks authorization and context before returning it.
- `ScreenVisionAnalyzer` repeats the sensitive-screen gate before the model
  call, revalidates the frame generation before and after analysis, rejects
  duplicate IDs, and binds structured candidates to the current generation.
- Vision frames and candidates contain no coordinates, are not persisted or
  logged, and no Vision class performs UI actions.

No Level-1 frozen KWS source was changed.

## Review

Two fresh delegated reviewers were requested for the final range. Both remained
running through two bounded waits and were closed without a verdict. Their
status is recorded as unavailable, not CLEAN.

The controller performed a read-only review of `1e32af6..d90f38d` and found no
unresolved Critical or Important issue:

- unverified or stale requests cannot reach the capturer;
- sensitive screens cannot reach the capturer or model client;
- a grant cannot be reused across session/process lifecycle;
- frame metadata is validated against the current ScreenContext;
- analyzer output is structured and generation-bound; and
- the Vision layer has no persistence, logging, coordinate, or direct-action
  path.

## Verification

```text
gradle :app:testDebugUnitTest --tests '*ScreenVisionAuthorizationTest*'  PASS
gradle :app:testDebugUnitTest                                      PASS
python tools/test_v070_sensitive_vision_zero_call.py               PASS
python tools/test_v060_security.py                                 PASS
python tools/test_v070_phase0_recovery_gate.py                     PASS
git diff --check                                                    PASS
```

No Android device or emulator was available; device validation remains
`DEVICE_GATE_PENDING`.
