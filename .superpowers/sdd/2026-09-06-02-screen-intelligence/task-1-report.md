# Task 1 Report: ScreenContext model and generation store

## RED

Test added first at `app/src/test/java/com/lchuang/xiaozhimobile/screen/ScreenContextStoreTest.kt`.

Commands and evidence:

```text
PS> .\gradlew.bat :app:testDebugUnitTest --tests com.lchuang.xiaozhimobile.screen.ScreenContextStoreTest
The term '.\gradlew.bat' is not recognized as a name of a cmdlet...

PS> gradle :app:testDebugUnitTest --tests com.lchuang.xiaozhimobile.screen.ScreenContextStoreTest
The term 'gradle' is not recognized as a name of a cmdlet...
```

The checkout has no Gradle wrapper, system Gradle, or `kotlinc`; therefore the intended compile-time missing-implementation failure could not be reached. No production implementation existed when the focused commands were attempted.

## GREEN

Implemented the minimum in-memory model/store:

- immutable `GenerationId`, `ScreenContext`, and `ScreenNode` value/data models;
- monotonically increasing generation IDs across publish/invalidate/expiry;
- package and window fingerprint matching;
- positive short TTL (default 5 seconds), checked on read;
- automatic invalidation when a new package/window fingerprint is published;
- explicit invalidation;
- no database, file, or log persistence of the raw node tree.

The focused GREEN command was attempted with the same command:

```text
gradle :app:testDebugUnitTest --tests com.lchuang.xiaozhimobile.screen.ScreenContextStoreTest
The term 'gradle' is not recognized as a name of a cmdlet...
```

Consequently, there is no honest focused-test pass output in this environment.

## Phase 0 gate

Command:

```text
python tools/test_v070_phase0_recovery_gate.py
```

Observed output before the environment failure:

```text
BRANCH: recovery/v0.7.0-golden-first-full
PASS: frozen source unchanged app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseCompiler.kt
PASS: frozen source unchanged app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseManager.kt
PASS: frozen source unchanged app/src/main/java/com/lchuang/xiaozhimobile/Pinyin4jProvider.kt
```

The gate then failed in the existing frozen-baseline chain because `kotlinc` was not found (`FileNotFoundError: [WinError 2]`). No pycache was produced by the gate's `-B` invocation.

## Changed files

- `app/src/main/java/com/lchuang/xiaozhimobile/screen/ScreenContext.kt`
- `app/src/main/java/com/lchuang/xiaozhimobile/screen/ScreenNode.kt`
- `app/src/main/java/com/lchuang/xiaozhimobile/screen/ScreenContextStore.kt`
- `app/src/test/java/com/lchuang/xiaozhimobile/screen/ScreenContextStoreTest.kt`

This report is the required task artifact; no other production modules were changed.

## Commit

Commit message: `feat: add ephemeral screen context model`

Commit SHA: `565c02f7653999e5d4632969d2cda83400cd7d8a`

## Self-review findings

- Store state is process-memory only and raw node trees are never serialized or logged.
- Generation allocation is independent of expiry and invalidation, so IDs do not repeat or decrease within a store instance.
- Reads reject both stale contexts and package/window mismatches.
- `ScreenNode` exposes child nodes through Kotlin's read-only `List` type; callers should provide immutable node values.

## Concerns

- Focused JUnit and the Phase 0 gate could not complete because this host lacks Gradle, the Gradle wrapper, and `kotlinc`. Re-run both commands in the Android/Kotlin build environment before merging.
- No source changes were made to the frozen KWS files; the gate confirmed all three unchanged before its toolchain failure.

## Controller verification and follow-up

The blocked report above came from an implementer environment that did not inherit the host toolchain. The controller reran the required commands with the verified absolute JDK 17 / Gradle 8.9 / Android SDK 35 / kotlinc shim paths:

```text
gradle :app:testDebugUnitTest --tests '*ScreenContextStoreTest*' --stacktrace
BUILD SUCCESSFUL

python -B -X utf8 tools/test_v070_phase0_recovery_gate.py
PASS: v0.7 Phase 0 recovery gate
```

The controller also verified `git diff --check`, the Frozen Golden source diff, and no changes outside the Task 1 files plus the follow-up store fix. The follow-up removes destructive clearing when a stale caller queries a different package/window; a mismatched read now returns null without discarding the current context. The implementation remains process-memory-only and no raw node tree is persisted or logged.

The original RED phase did create the focused test before the implementation, but the implementer could not execute the compile-time RED command because of its isolated environment. The current focused test and all predecessor gates are GREEN under the host toolchain; this environment limitation remains a recorded concern rather than a claimed RED execution.
