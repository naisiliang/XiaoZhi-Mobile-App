# Task 5 report: shared truthful AssistantStateStore

## Result

DONE. Implemented Task 5 only.

Report metadata history:

- Final code commit: `cfd03b3dd83894dbadd2de88557e05d5c05fed9a` (`fix: route legacy completion through assistant state`).
- Toolchain documentation commit: `66a071bf31bc7f538430d06df2700987e2759196`.
- Report metadata correction commit: `40ccf0e16b551f1a4734da069af6acfb284a209f`.

The current report-only correction is intentionally not embedded as its own SHA, avoiding self-referential metadata; its commit identity is provided by the handoff.

## TDD evidence

RED test was written first at `app/src/test/java/com/lchuang/xiaozhimobile/conversation/AssistantStateStoreTest.kt`. The mandated focused command failed because the new production contract was absent:

```text
e: .../AssistantStateStoreTest.kt:10:21 Unresolved reference 'AssistantStateStore'.
e: .../AssistantStateStoreTest.kt:13:25 Unresolved reference 'AssistantState'.
...
> Task :app:compileDebugUnitTestKotlin FAILED
BUILD FAILED in 1s
```

The minimum state enum/store contract was then added, followed by the focused GREEN run.

Fix Round 2 tests were updated first to assert `onConfirmationRequired()` produces exact `WAITING_CONFIRMATION` and a dedicated outbound render decision. Against the current implementation, the focused run failed as intended with unresolved `AssistantOverlayRender` and `toOverlayRender` references. The minimum explicit confirmation render path was then added.

Fix Round 3 added a regression test first for `READY_TO_LISTEN -> EXITING -> onConversationEnded` and observer notification while already `WAITING_WAKE`. Against the current implementation, the focused run failed at the completion assertion because no observer event was emitted for an unchanged `WAITING_WAKE` state. The store then gained same-state completion publication without adding a seventh state.

Fix Round 4 added the legacy production-path regression test first for `READY_TO_LISTEN -> EXITING -> IDLE_WAKE`, asserting that the returned legacy view states are preserved and the `WAITING_WAKE` completion notification is observed. Against the pre-fix implementation, six tests ran and the new test failed at its observer assertion. The confirmation reset assertion also initially failed to compile because `AssistantOverlayRender.Confirmation.legacyState` was absent. The minimum controller changes then routed legacy `IDLE_WAKE` to `onConversationEnded()` and reset the view-facing state to `IDLE_WAKE` before dedicated confirmation content.

## Implementation summary

- Added the exact six `AssistantState` values: `WAITING_WAKE`, `LISTENING`, `RECOGNIZING`, `EXECUTING`, `SPEAKING`, and `WAITING_CONFIRMATION`.
- Added event-driven `AssistantStateStore`; wake detection transitions to `WAITING_WAKE`, and only `onAudioCaptureStarted()` transitions to `LISTENING`.
- Added concrete capture-stop, execution-start, TTS-start, confirmation-required, and conversation-ended transitions.
- Centralized overlay state ownership in `AssistantOverlayController`; its legacy `ConversationState` adapter routes through the store and maps store state back to the existing view.
- Made the shared `AssistantStateStore` injectable and publicly exposed through the controller boundary for future runtime wiring.
- Added a named `applyLegacyOverlayState` compatibility boundary: `READY_TO_LISTEN` renders only the legacy resume state while retaining store `WAITING_WAKE`; `EXITING` remains view-facing until the real `onConversationEnded()` event.
- Added `AssistantOverlayRender.Confirmation` so `WAITING_CONFIRMATION` renders dedicated confirmation content and never maps to legacy `READY_TO_LISTEN`; initial overlay rendering uses the same decision path as observer updates.
- Made `onConversationEnded()` publish the existing `WAITING_WAKE` value even when already current, so completion is observable without changing the exact six-state model.
- Normalized `AssistantOverlayController.kt` line endings/whitespace; `git diff --check` is clean.
- Preserved `READY_TO_LISTEN` and `EXITING` as legacy ingress/view states while making the final legacy `IDLE_WAKE` signal the observable completion event.

## Verification

DONE. Fix Round 4 focused compatibility tests, full unit smoke, and the FIX04 regression gate all passed.

The verification commands below were run with these recovered process-local settings:

- Gradle executable: `C:/Users/ASUS/AppData/Local/Temp/xiaozhi-gradle-8.9/gradle-8.9/bin/gradle.bat`
- Kotlin executable directory, prepended to `PATH`: `C:/Users/ASUS/AppData/Local/Temp/codex-kotlinc`
- `JAVA_HOME`: `C:/Users/ASUS/AppData/Local/Temp/codex-kotlinc17/jdk17/jdk-17.0.20.1+1`
- `ANDROID_HOME`: `C:/Users/ASUS/AppData/Local/Temp/xiaozhi-android-sdk`
- `ANDROID_SDK_ROOT`: `C:/Users/ASUS/AppData/Local/Temp/xiaozhi-android-sdk`

The bounded Gradle runs also used `GRADLE_OPTS=-Xmx768m -XX:MaxMetaspaceSize=256m -Dfile.encoding=UTF-8`, `-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=256m -Dfile.encoding=UTF-8`, `-Pkotlin.compiler.execution.strategy=in-process`, the process-local bounded-test init script, `--no-daemon`, and `--max-workers=1`. The FIX04 command used the same process-local `JAVA_HOME`, Kotlin `PATH`, and Android SDK settings.

Focused command:

`C:/Users/ASUS/AppData/Local/Temp/xiaozhi-gradle-8.9/gradle-8.9/bin/gradle.bat testDebugUnitTest --tests '*AssistantStateStoreTest*' --no-daemon`

Exact result:

```text
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 37s
22 actionable tasks: 3 executed, 19 up-to-date
```

Fix Round 1 focused adapter command:

`C:/Users/ASUS/AppData/Local/Temp/xiaozhi-gradle-8.9/gradle-8.9/bin/gradle.bat testDebugUnitTest --tests '*AssistantStateStoreTest*' --no-daemon --max-workers=1`

Exact result:

```text
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 23s
22 actionable tasks: 5 executed, 17 up-to-date
```

Fix Round 2 focused command:

`C:/Users/ASUS/AppData/Local/Temp/xiaozhi-gradle-8.9/gradle-8.9/bin/gradle.bat testDebugUnitTest --tests '*AssistantStateStoreTest*' --no-daemon --max-workers=1`

Exact result:

```text
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 21s
22 actionable tasks: 5 executed, 17 up-to-date
```

Fix Round 3 focused command:

`C:/Users/ASUS/AppData/Local/Temp/xiaozhi-gradle-8.9/gradle-8.9/bin/gradle.bat testDebugUnitTest --tests '*AssistantStateStoreTest*' --no-daemon --max-workers=1`

Exact result:

```text
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 24s
22 actionable tasks: 5 executed, 17 up-to-date
```

Fix Round 4 focused command:

`C:/Users/ASUS/AppData/Local/Temp/xiaozhi-gradle-8.9/gradle-8.9/bin/gradle.bat testDebugUnitTest --tests '*AssistantStateStoreTest*' --no-daemon --max-workers=1`

Exact result (bounded process-local Gradle/Kotlin setup):

```text
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 16s
22 actionable tasks: 1 executed, 21 up-to-date
```

Full unit smoke command:

`C:/Users/ASUS/AppData/Local/Temp/xiaozhi-gradle-8.9/gradle-8.9/bin/gradle.bat testDebugUnitTest --no-daemon`

Exact result:

```text
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 12s
22 actionable tasks: 1 executed, 21 up-to-date
```

Fix Round 1 full smoke used the same bounded process-local setup (`--no-daemon --max-workers=1`, `GRADLE_OPTS=-Xmx2g -Dfile.encoding=UTF-8`) and completed successfully:

```text
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 12s
22 actionable tasks: 1 executed, 21 up-to-date
```

Fix Round 2 full smoke rerun used the same bounded setup and completed successfully:

```text
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 13s
22 actionable tasks: 1 executed, 21 up-to-date
```

Fix Round 3 full smoke rerun used the same bounded setup and completed successfully:

```text
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 17s
22 actionable tasks: 1 executed, 21 up-to-date
```

Fix Round 4 full smoke command:

`C:/Users/ASUS/AppData/Local/Temp/xiaozhi-gradle-8.9/gradle-8.9/bin/gradle.bat testDebugUnitTest --no-daemon --max-workers=1`

Exact result (bounded process-local Gradle/Kotlin setup):

```text
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 15s
22 actionable tasks: 1 executed, 21 up-to-date
```

FIX04 command:

`python tools/test_v065_fix04_regression_gate.py`

Exact successful result:

```text
PASS: FIX04 regression gate
```

Process exit code: `0`.

Fix Round 2 rerun used the recovered process-local Kotlin launcher with Java 17-compatible toolchain setup and completed with process exit code `0`; final output was `PASS: FIX04 regression gate`.

Fix Round 3 rerun used the same recovered process-local Kotlin/Java/Gradle/SDK setup and completed with process exit code `0`; final output was:

```text
PASS: v0.6.5 release gate
PASS: FIX04 regression gate
```

Fix Round 4 rerun used the recovered process-local Kotlin/Java/Gradle/SDK setup and completed with process exit code `0`; final output was:

```text
PASS: v0.6.5 release gate
PASS: FIX04 regression gate
```

The successful gate output also included the frozen baseline, security, release, and artifact checks, ending with:

```text
PASS: v0.6.5 frozen baseline and version
PASS: v0.6.5 release gate
PASS: FIX04 regression gate
```

## Historical setup correction note (outside final verification)

An earlier attempt was blocked by the initially supplied stripped Java/Kotlin runtime before the gate could run. The gate reached `tools/test_v064_volume_parser.py`, then failed with:

```text
exception: java.lang.InternalError: Error loading java.security file
subprocess.CalledProcessError: Command '['kotlinc', ...] returned non-zero exit status 2.
```

Environment recovery outside the repository supplied complete Java 17.0.7 and a TEMP `kotlinc17` launcher; the final successful run used the recovered TEMP Kotlin launcher and completed with exit code 0 and `PASS: FIX04 regression gate`. No repository tool or test was modified.

## Changed paths

- `app/src/main/java/com/lchuang/xiaozhimobile/conversation/AssistantState.kt`
- `app/src/main/java/com/lchuang/xiaozhimobile/conversation/AssistantStateStore.kt`
- `app/src/main/java/com/lchuang/xiaozhimobile/AssistantOverlayController.kt`
- `app/src/test/java/com/lchuang/xiaozhimobile/conversation/AssistantStateStoreTest.kt`

Fix Round 1 additive commit changed only the controller, store, and focused test above; `AssistantState.kt` remained unchanged.

Fix Round 2 additive commit changed only `AssistantOverlayController.kt` and `AssistantStateStoreTest.kt`; no store enum/source, legacy state/view, WakeService, frozen, previous-task, later-task, input, or generated path changed.

Fix Round 3 additive commit `28dc786b792c14d9f9ac0acbc53e1b939a7b4ab0` contains only the normalized `AssistantOverlayController.kt`, `AssistantStateStore.kt`, and `AssistantStateStoreTest.kt` changes; no forbidden path changed.

Fix Round 4 additive commit `cfd03b3dd83894dbadd2de88557e05d5c05fed9a` contains only `AssistantOverlayController.kt` and `AssistantStateStoreTest.kt`; no `ConversationState.kt`, `AssistantOverlayView.kt`, `WakeService.kt`, frozen, previous-task, later-task, input, or generated path changed.

The final code commit is `cfd03b3dd83894dbadd2de88557e05d5c05fed9a` (`fix: route legacy completion through assistant state`); its changed paths are the controller and focused test listed above.

Toolchain documentation commit `66a071bf31bc7f538430d06df2700987e2759196` (`docs: document recovered verification toolchain`) changed only this report’s recovered process-local verification settings and command paths.

Report metadata correction commit `40ccf0e16b551f1a4734da069af6acfb284a209f` (`docs: record task 5 report metadata`) changed only this report’s commit metadata.

The current report-only correction is deliberately not listed with an embedded SHA to avoid self-reference; its exact commit is reported by the handoff.

Generated/pre-existing untracked paths were not staged or committed: `.kotlin/`, `hs_err_pid14848.log`, and `tools/__pycache__/`.

## Truthful LISTENING evidence

The focused test executes `onWakeDetected()` and asserts `current != LISTENING`, then executes `onAudioCaptureStarted()` and asserts `current == LISTENING`, followed by `onTtsStarted()` and `current == SPEAKING`. The store contains no wake-to-listening path; `LISTENING` is assigned only by the concrete capture-start event method.

## Frozen/version/input confirmations

- `app/build.gradle.kts` remains `versionCode = 12` and `versionName = "0.6.5"`.
- The frozen KWS baseline files and frozen `WakeService.kt` KWS block were not modified; no Level-1 wake resource or security-policy bypass was added.
- The read-only input package `E:\app_apk\XiaoZhi-v0.7.0-alpha1-Codex-Subagent-Driven-Pack` was not modified.
- No Accessibility, Vision, screenshot, MediaProjection, messaging/media/plugin/agent/MCP/PPT/PDF/DOCX/XLSX/artifact/image-generation, or alpha2 runtime scope was added.

## Concerns / assumptions

- The existing `WakeService` callers still emit legacy `ConversationState`; the overlay controller preserves that API while translating each value into a concrete store event. The existing caller sets legacy `LISTENING` only after capture startup succeeds.
- `EXITING` is deliberately a legacy view-facing state at the controller boundary; the exposed shared store changes only when future runtime wiring calls its real conversation-ended event.
- `WAITING_CONFIRMATION` is deliberately rendered through dedicated content/status (`需要你的确认` / `等待确认…`) rather than the legacy `READY_TO_LISTEN` state.
- `onConversationEnded()` intentionally emits an observer completion notification when already `WAITING_WAKE`; it does not invent a seventh state or alter legacy `EXITING` rendering.
- The legacy controller ingress now treats `IDLE_WAKE` as the post-exit completion signal, so production compatibility wiring reaches the observable completion event without changing `WakeService.kt`.
- Confirmation content resets the view’s legacy state to `IDLE_WAKE` so stale `LISTENING` audio/animation behavior is not retained while the shared store remains `WAITING_CONFIRMATION`.
- All required verification is complete; the historical stripped-runtime issue was corrected outside the repository.
