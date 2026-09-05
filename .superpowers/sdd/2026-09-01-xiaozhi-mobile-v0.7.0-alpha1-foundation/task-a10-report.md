# Task A10 Report: truthful TaskProgressTracker foundation

## Scope

This bounded fix adds focused coverage to the existing Stage-1 TaskProgressTracker foundation from exact Ruling 4:

- `app/src/test/java/com/lchuang/xiaozhimobile/tasks/TaskProgressTrackerTest.kt`
- [Ruling 4 excerpt](a10-ruling-4-excerpt.md)
- [auditable RED log](task-a10-red.log)

The tracker retains exactly `QUEUED`, `RUNNING`, `WAITING_USER`,
`WAITING_PERMISSION`, `WAITING_CONFIRMATION`, `COMPLETED`, `FAILED`,
`CANCELLED`, `BLOCKED`, and `INTERRUPTED`. Its immutable snapshot exposes the
actual state, phase, completed item count, total item count, and a nullable
percentage derived only from those counts. A zero-item task has no percentage;
no AI-provided arbitrary percentage is accepted. Negative counts and completed
counts greater than total are rejected. Every terminal state rejects later
updates and transitions.

No Artifact/Agent cancellation integration was added. No Task 1–8/A9
production code, input package, or progress ledger was modified. No device or
GitHub acceptance is claimed.

## TDD evidence

### RED

The all-terminal-state regression test was written first. To observe RED
against the existing implementation, the `update(...)` terminal-state guard
was temporarily removed, the focused test was run, and the guard was restored
unchanged. The auditable output is in [task-a10-red.log](task-a10-red.log).

Command:

```text
$env:JAVA_HOME='C:/Users/ASUS/.jdks/openjdk-20.0.2'; $env:ANDROID_SDK_ROOT='C:/Users/ASUS/AppData/Local/Temp/xiaozhi-android-sdk'; $env:ANDROID_HOME='C:/Users/ASUS/AppData/Local/Temp/xiaozhi-android-sdk'; $env:PATH='C:/Users/ASUS/AppData/Local/Temp/codex-kotlinc;'+$env:PATH; C:/Users/ASUS/AppData/Local/Temp/xiaozhi-gradle-8.9-complete/gradle-8.9/bin/gradle.bat :app:testDebugUnitTest --tests com.lchuang.xiaozhimobile.tasks.TaskProgressTrackerTest --no-daemon
```

Observed RED output:

```text
TaskProgressTrackerTest > every terminal state rejects later updates and transitions FAILED

    java.lang.AssertionError at TaskProgressTrackerTest.kt:107
8 tests completed, 1 failed

BUILD FAILED in 16s
EXIT_CODE=1
```

The failure was the expected runtime assertion for the missing terminal update
guard, not a compilation or test-harness error.

### GREEN

After restoring the minimal existing guard, the same focused command produced:

```text
BUILD SUCCESSFUL in 14s
22 actionable tasks: 5 executed, 17 up-to-date
EXIT_CODE=0
```

The focused class contains 8 passing tests, including both update and
transition rejection for each of `COMPLETED`, `FAILED`, `CANCELLED`,
`BLOCKED`, and `INTERRUPTED`. The generated test result records
`tests="8"`, `failures="0"`, and `errors="0"`.

## Alpha1/frozen regression

Command environment used:

```text
JAVA_HOME=C:/Users/ASUS/.jdks/openjdk-20.0.2
ANDROID_SDK_ROOT=C:/Users/ASUS/AppData/Local/Temp/xiaozhi-android-sdk
ANDROID_HOME=C:/Users/ASUS/AppData/Local/Temp/xiaozhi-android-sdk
PATH=C:/Users/ASUS/AppData/Local/Temp/xiaozhi-kotlinc-no-stdlib;C:/Users/ASUS/AppData/Local/Temp/codex-kotlinc;<existing PATH>
```

The first PATH entry is a temporary native `kotlinc` shim used only for this
verification. It invokes the real `codex-kotlinc/kotlinc.exe` with
`-no-stdlib`; it does not capture, suppress, or filter compiler output. This
is required because the Python regression harness launches `kotlinc` directly
and cannot invoke the available batch wrapper.

Command:

```text
python tools/test_v070_alpha1_settings_migration.py; python tools/test_v070_alpha1_session_wiring.py; python tools/test_v070_alpha1_chat_home_contract.py; python tools/test_v065_frozen_baseline.py; $exitCode=$LASTEXITCODE; Write-Output "EXIT_CODE=$exitCode"; exit $exitCode
```

Exact output:

```text
PASS: v0.7.0-alpha1 settings migration structure
PASS: v0.7.0-alpha1 WakeService session wiring
PASS: v0.7.0-alpha1 chat home structural contract
PASS: v0.6.3 normalizes pinyin4j tone marks and emits official ppinyin tokens
PASS: v0.6.3 custom wake failures expose a concrete reason and keep previous stream
PASS: v0.6.4 preserves v0.6.3 custom wake implementation
PASS: v0.6.5 frozen baseline and version
EXIT_CODE=0
```

The corrected frozen-baseline check emits no Kotlin runtime warning; all
listed checks passed.

## Commit

Commit subject: `fix: remove Kotlin runtime warning from A10 evidence`.
