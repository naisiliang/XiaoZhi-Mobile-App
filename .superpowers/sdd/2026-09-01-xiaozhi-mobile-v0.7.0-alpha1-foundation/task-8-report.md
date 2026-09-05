# Task 8 gate/report evidence fix

## Finding and scope

The Task 8 session gate still asserted the superseded local Gradle/JDK20
strings even though the authoritative Alpha1 checklist had moved to JDK17
and local Gradle 8.9. This caused the gate to fail before it evaluated the
session wiring.

This round changes only:

- `tools/test_v070_alpha1_session_wiring.py` — assert the authoritative JDK17,
  local Gradle 8.9, and Android SDK paths, while rejecting the superseded
  checklist references.
- this report — replace stale environment, command, and outcome evidence.

The checklist was already authoritative at the start of this round and was
not changed. No business implementation, input package, controller ledger,
other test domain, or frozen KWS block was changed.

## TDD RED -> Green

The pre-fix focused run reproduced the finding:

```text
python tools/test_v070_alpha1_session_wiring.py
AssertionError in assert_local_verification_paths
exit 1
```

The minimal gate change replaced the stale positive path assertions with
exact assertions for the checklist's current JDK17, local Gradle 8.9, and
Android SDK paths, and retained negative assertions for superseded checklist
references. The focused gate then passed:

```text
python tools/test_v070_alpha1_session_wiring.py
PASS: v0.7.0-alpha1 WakeService session wiring
exit 0
```

The gate remains a text-contract check rather than a reviewer-shell
filesystem check, so it does not depend on an absolute path being present on
the machine running the source gate.

## Authoritative local environment

The Kotlin-backed checks and Gradle run used the prepared local toolchain:

```text
JAVA_HOME=C:/Users/ASUS/AppData/Local/Temp/codex-kotlinc/jdk17/jdk-17.0.20.1+1
ANDROID_HOME=C:/Users/ASUS/AppData/Local/Temp/xiaozhi-android-sdk
ANDROID_SDK_ROOT=C:/Users/ASUS/AppData/Local/Temp/xiaozhi-android-sdk
PATH prefix=C:/Users/ASUS/AppData/Local/Temp/xiaozhi-kotlin-shim
kotlinc=C:/Users/ASUS/AppData/Local/Temp/xiaozhi-kotlin-shim/kotlinc.exe
Kotlin compiler target=C:/Users/ASUS/AppData/Local/Temp/xiaozhi-kotlin-2.0.21/kotlinc
openjdk version "17.0.20.1" 2026-08-18
OpenJDK Runtime Environment Temurin-17.0.20.1+1 (build 17.0.20.1+1)
```

The exact Gradle command from the checklist was:

```text
C:/Users/ASUS/AppData/Local/Temp/xiaozhi-gradle-8.9-local/gradle-8.9/bin/gradle.bat :app:testDebugUnitTest --tests '*ToolDispatcherTest*' --tests '*CentralSafetyPolicyEngineTest*'
```

Its exact final output was:

```text
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 1s
22 actionable tasks: 22 up-to-date
exit 0
```

The run also emitted the existing Android SDK package-location warning; it
did not change the result. No Kotlin-home warning is used as success
evidence.

## Fresh verification results

All commands below were run from the current worktree with the environment
above and the complete Kotlin compiler shim on `PATH`:

```text
python tools/test_v070_alpha1_session_wiring.py
PASS: v0.7.0-alpha1 WakeService session wiring
exit 0

python tools/test_v070_alpha1_settings_migration.py
PASS: v0.7.0-alpha1 settings migration structure
exit 0

python tools/test_v070_alpha1_chat_home_contract.py
PASS: v0.7.0-alpha1 chat home structural contract
exit 0

python tools/test_v065_fix04_regression_gate.py
PASS: FIX04 regression gate
exit 0

python tools/test_v065_frozen_baseline.py
PASS: v0.6.5 frozen baseline and version
exit 0

python tools/validate_project.py
PASS: no secret-like sk token
exit 0

C:/Users/ASUS/AppData/Local/Temp/xiaozhi-gradle-8.9-local/gradle-8.9/bin/gradle.bat :app:testDebugUnitTest --tests '*ToolDispatcherTest*' --tests '*CentralSafetyPolicyEngineTest*'
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 1s
22 actionable tasks: 22 up-to-date
exit 0

git diff --check
exit 0 (no output)
```

The FIX04 gate's nested checks and the frozen gate's nested v0.6.3/v0.6.4
regressions also completed successfully. The Gradle command covered the
Task 8-related `ToolDispatcherTest` and `CentralSafetyPolicyEngineTest`
patterns specified by the checklist.

## Frozen, source-range, and input boundaries

The frozen KWS initialization/application/listening block remains unchanged;
its verified SHA-256 is:

```text
77071fcc4a9d9c9627e8a30ddb45d0ad831ece80483152d8b71ce8b4c128abcd
```

The starting HEAD for this fresh fix was:

```text
9cc8141b0703177ebd5f2c3f077aca3b8776d6aa
```

The previously reviewed Task 8 source range remains anchored at
`5833b8ced759342ccb8eb25fcc7385d8f1550aba`, with the recorded
`aff1af3609bcc854085689998ea0ddebb7645918` compile-fix change already in
its ancestry. This round adds no production-source or workflow changes.

The read-only input package remains outside this worktree at
`E:\app_apk\XiaoZhi-v0.7.0-alpha1-Codex-Subagent-Driven-Pack`; no command in
this round wrote to it. The controller progress ledger remains unchanged.

No real-device acceptance, GitHub Actions run, or Alpha1 artifact upload is
claimed by this local evidence.
