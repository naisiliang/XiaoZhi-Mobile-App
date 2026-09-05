# Task A9 Report: DiagnosticEvent privacy fix

## Scope

Removed only `secret` and `auth(?:orization)?` from the generic sensitive-assignment detector. Direct required sensitive keys remain blocked, and bearer/API-key/password/OTP/payment/raw screenshot payloads remain fail-closed through the retained detectors. The two benign generic-value cases are covered by the existing 8-test focused suite.

No Task 1–8 files, input package, `progress.md`, device acceptance, or GitHub acceptance was changed.

## Evidence

- Ruling 3 excerpt: [a9-ruling-3-excerpt.md](a9-ruling-3-excerpt.md).
- FIX04 verification: [task-a9-fix04-verification.log](task-a9-fix04-verification.log).
- TDD Red artifacts: the original generic-value assertions failed in a focused run of 4 tests with 2 failures; the serialized-JSON/MIME additions failed in a focused run of 6 tests with 2 failures; and the direct raw-screenshot/exact-key boundary additions failed in a focused run of 8 tests with 2 failures. These exact outputs are preserved in `task-a9-red.log`, `task-a9-red-round-2.log`, and `task-a9-red-round-3.log`.
- Current focused regression: the newly added plain assignment case passes with the already-corrected sanitizer; the suite completed 9 tests with 9 passed.

## Focused verification

Command:

```text
$env:JAVA_HOME='C:/Users/ASUS/.jdks/openjdk-20.0.2'; $env:ANDROID_SDK_ROOT='C:/Users/ASUS/AppData/Local/Temp/xiaozhi-android-sdk'; $env:ANDROID_HOME='C:/Users/ASUS/AppData/Local/Temp/xiaozhi-android-sdk'; $env:PATH='C:/Users/ASUS/AppData/Local/Temp/codex-kotlinc;'+$env:PATH; C:/Users/ASUS/AppData/Local/Temp/xiaozhi-gradle-8.9-complete/gradle-8.9/bin/gradle.bat :app:testDebugUnitTest --tests com.lchuang.xiaozhimobile.diagnostics.DiagnosticEventTest --no-daemon
```

Exact current result:

```text
BUILD SUCCESSFUL in 17s
9 tests completed, 9 passed
22 actionable tasks: 2 executed, 20 up-to-date
```

FIX04 exact PASS output is recorded unchanged in `task-a9-fix04-verification.log`: `PASS: v0.6.5 release gate` and `PASS: FIX04 regression gate`.

## Commit metadata

- Initial implementation: `852a21f257cdcda141328d2bc71394ee9c9784df` (`feat: add privacy-safe diagnostic event foundation`).
- Privacy implementation/fix sequence: `20f0a57452d2d655edfb80e3ca91423ff4034672` (`fix: tighten diagnostic metadata privacy`), `6bedd8236a44867d337689ae913d08de0e09c4ff` (`fix: handle serialized diagnostic secrets`), `bb37b649e7109ead1c2de49eb869445bd1302a2e` (`fix: narrow diagnostic forbidden exact keys`), and final implementation `e639d2fc44735b7752ae03610686d304903be071` (`fix: preserve benign generic diagnostic metadata`).
- Final test/evidence commit: `76a6ccbe4834b3edcce036ecfab189e398471f17` (`test: cover benign authorization diagnostic assignment`). This is the evidence endpoint of the final reviewed range, not an implementation commit.
- Final review range: `e639d2fc44735b7752ae03610686d304903be071..76a6ccbe4834b3edcce036ecfab189e398471f17`.

The current report-only correction is intentionally omitted to avoid self-reference.
