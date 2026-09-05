# Task 7 report

## Current Important-finding fix round

This section supersedes the earlier Task 7 review evidence where it overlaps
with the two conversation/session findings. The implementation now uses the
process-local `ConversationSessionStore` and its thread-safe,
observable `ConversationSessionEventSource` from both MainActivity and
WakeService. MainActivity registers its observer after UI/session
initialization and unregisters it on destruction, so service-side appends
refresh the chat home while the service is running. The loaded active session
is the manager's initial session, so typed, voice, and operation results append
to that same session without a second direct repository save.

`ConversationSession` now retains title/status/assistantName and
`ConversationMessage` retains USER, ASSISTANT, SYSTEM_ACTION, SYSTEM_RESULT,
and CONFIRMATION roles plus message status. SQLite save/load preserves those
fields. History renders a session header followed by that session's messages;
it no longer flattens sessions.

The focused gate was strengthened to check shared source ownership,
observer lifecycle, session-bound history, metadata/role persistence, and
out-of-scope guards. New behavioral coverage is in
`ConversationSessionEventSourceTest.kt` for observer publication and
concurrent append serialization. WakeService changes are Task 8-owned
lifecycle wiring only; the frozen KWS block was not edited.

### Current TDD and verification evidence

- RED: `python tools/test_v070_alpha1_chat_home_contract.py` exited `1` with
  missing shared source, observer, metadata/role, and session-bound history
  contracts.
- RED: `python tools/test_v070_alpha1_session_wiring.py` exited `1` with the
  missing shared manager and system event calls.
- GREEN: both focused Python gates printed PASS and exited `0`.
- GREEN: `gradle clean :app:testDebugUnitTest --no-daemon --stacktrace`
  printed `BUILD SUCCESSFUL` and exited `0`; the XML results contain 34 tests,
  0 failures, 0 errors, and 0 skipped.
- Regression verification printed PASS for the Alpha1 chat/session/settings
  gates, v0.6.5 FIX04 regression/release/frozen gates, and
  `tools/validate_project.py`; `git diff --check` exited `0`.

The local Gradle run used the existing temporary Gradle 8.9 distribution and
JDK 17. The Android SDK emitted its pre-existing build-tools location warning;
it did not fail the build. No device or GitHub artifact acceptance is claimed.

## Result

Implemented the chat home/history UI, typed-message persistence, and the
Task 7-owned typed result bridge on base
`0447f7eedc99b69a28523a87cd179637f8699caf`.

Commits:

- `dbe383ad0cdd22fc147cb70add6e994e169f6ae5 feat: replace configuration home with conversation UI`
- `5598485732693e439fa13cabf80c97cb6c576c13 docs: record recovered Task 7 gate verification` (initial docs revision)
- `252b19d7c557d2365bd60aaad7408246ed93f755 fix: restore Task 7 history and session reuse`
- `e02f1e175e73bd5973ba536b73d1524c71aedef5 fix: add Task 7 conversation result bridge`
- `fa453f94e281628a04857a327bb80bb0139dfb1a docs: record Task 7 review verification` (current report revision before this update)
- `f6dd4051e96ae0ca6466a612601ce841054a16d5 docs: correct Task 7 gate environment record`
- `03239fccd34c497602fe2cfdf6dbb5941da1513a fix: expose shared Task 7 result bridge`
- `f8f256bc05d1367e1a3710a831f5fda6639595a4 docs: record Task 7 bridge gate verification`
- `42f79743f9e77bac01e2d235db421f4988f7bd38 fix: marshal Task 7 results on main thread`
- `06b49144193300a850c09bee677aaffe0aad40b7 docs: record shared Task 7 bridge verification`

The functional/source review range is exactly base
`0447f7eedc99b69a28523a87cd179637f8699caf` through source verification commit
`74ac6f01439763b49852030ead671b442bf92ea3`. Every commit after `74ac6f0` in
this Task 7 chain is report-only metadata/documentation, including
`d11d032103210c8f859161d7531f65e2e823534a`,
`df48bf5941a6845b9fb47cabb8fd41def4688ab0`, and
`14984bd0c0cd2d68b26071a5f922ee5da41ca4c3`, and therefore is excluded from
the functional/source range. None of those post-`74ac6f0` commits is the
functional review HEAD.

## TDD evidence

1. Wrote `tools/test_v070_alpha1_chat_home_contract.py` before production code.
2. Initial test harness run failed with `FileNotFoundError` because the two new production files did not yet exist; exit code `1`.
3. Corrected the test harness to treat absent production files as empty source, then observed the intended RED failure: `AssertionError: chat home contract is missing: RecyclerView, 历史会话, 插件与技能, Agents, ConversationRepository`; exit code `1`.
4. Added the minimum scoped production contract.
5. Focused test passed: `PASS: v0.7.0-alpha1 chat home contract`; exit code `0`.
6. Rewrote the focused test as structural checks before the review fix. The RED run reported missing explicit history navigation, shared append/persist handling, and non-exported history manifest registration; exit code `1`.
7. Implemented the review fixes without changing Task 1-6 sources or WakeService/KWS code.
8. Structural GREEN run: `PASS: v0.7.0-alpha1 chat home structural contract`; exit code `0`.
9. Added the round-4 typed bridge, sink lifecycle, and three-ingress assertions before production changes. RED run: `python tools/test_v070_alpha1_chat_home_contract.py`; exit code `1`. Observed failure began `AssertionError: MainActivity result bridge field is missing: private val resultBridge = ConversationResultBridge()` and also reported missing typed result kinds, sink registration/unregistration, and `submitText`/`submitVoice`/`submitOperation` ingress.
10. Implemented `ConversationResultBridge` in the existing Task 7 conversation source; MainActivity now registers/unregisters its typed sink and routes the public result methods through typed bridge ingress to the common append/persist/adapter path.
11. GREEN run: `PASS: v0.7.0-alpha1 chat home structural contract`; exit code `0`.
12. Added the shared-singleton and external-ingress assertions before production changes. RED run: `python tools/test_v070_alpha1_chat_home_contract.py`; exit code `1`. Observed failures included `shared conversation result bridge singleton is missing: object ConversationResultBridge`, the forbidden MainActivity-private instance, and missing `ConversationResultBridge.submitText(text)`/`submitVoice(text)`/`submitOperation(text)` calls.
13. Changed the bridge to the public Task 7-owned `object ConversationResultBridge`; MainActivity now registers/unregisters its sink against the shared object and its public result handlers call the shared typed ingress directly.
14. Removed the obsolete prior-round `class ConversationResultBridge` assertion after the implementation changed the API to an object; no production code was changed for that test correction.
15. Final singleton contract GREEN run: `PASS: v0.7.0-alpha1 chat home structural contract`; exit code `0`.
16. Added round-5 assertions for main-looper marshaling, post-initialization sink registration, and loaded-session-only direct saving before production changes. RED run: `python tools/test_v070_alpha1_chat_home_contract.py`; exit code `1`. Observed failures covered missing `Handler`/`Looper`/`mainHandler.post`, registration order, and missing `loadedSession` save ownership.
17. Implemented the minimum round-5 fix: the shared sink posts all typed result mapping to the main looper, registration follows repository/session/state/UI/current-session/adapter initialization, and manager-backed appends no longer receive a second direct `repository.save(updated)` call.
18. Round-5 GREEN run: `PASS: v0.7.0-alpha1 chat home structural contract`; exit code `0`.

## Changed paths

- `app/src/main/java/com/lchuang/xiaozhimobile/conversation/ConversationAdapter.kt`
- `app/src/main/java/com/lchuang/xiaozhimobile/conversation/ConversationHistoryActivity.kt`
- `app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt`
- `app/src/main/AndroidManifest.xml`
- `app/build.gradle.kts`
- `tools/test_v070_alpha1_chat_home_contract.py`

## Scope and invariant confirmations

- MainActivity is the chat home with RecyclerView, composer, header, and menu entries for new session, history, plugins/skills, Agents, and settings.
- Current and history messages are read through the UI-facing `ConversationRepository`, backed by the existing SQLite schema and `SqliteConversationRepository` save contract.
- Text, voice, and operation result callbacks share `appendToCurrentSession`, which reuses a loaded active `ConversationSession` or starts a new manager session, saves the updated session through `ConversationRepository`, and refreshes the adapter.
- `ConversationResultBridge` provides typed TEXT, VOICE, and OPERATION ingress plus register/unregister sink APIs. MainActivity registers in `onCreate`, unregisters in `onDestroy`, and maps all three kinds through one sink to `appendToCurrentSession` without recursive handler calls.
- The shared result sink marshals all typed result handling through `Handler(Looper.getMainLooper()).post`; registration occurs only after initial repository/session/state/UI/current-session/adapter setup, while unregistration remains first in `onDestroy`.
- `appendToCurrentSession` directly calls `repository.save(updated)` only for the loaded active-session copy branch; manager-backed appends rely on `ConversationSessionManager.appendUser`/`appendAssistant` persistence.
- `AssistantStateStore` remains the UI state source; no new execution, plugin, or agent runtime was added.
- `ConversationHistoryActivity` is registered as non-exported and is launched by an explicit MainActivity intent; SettingsActivity navigation remains explicit.
- Version remains exactly `versionCode = 12` and `versionName = "0.6.5"`; the visible v0.6.5 metadata check passed.
- No WakeService, KWS, Accessibility, message-sending executor, Alpha2, or input-package files were changed.
- The shared bridge is only a typed ingress boundary; no WakeService, plugin, agent, message executor, or accessibility runtime was wired.
- Frozen wake path diff check returned no paths.
- `git diff --cached --check` returned exit code `0` before each commit.
- Pre-existing untracked `.kotlin/`, `hs_err_pid*.log`, and `tools/__pycache__/` artifacts were left untouched.

## Historical setup

- Before the recovered run, the required combined command stopped in `tools/test_v064_volume_parser.py` with `FileNotFoundError: [WinError 2]` for `kotlinc`; combined exit code `1`.
- Before the recovered run, `tools/test_v065_frozen_baseline.py` stopped in compiler-backed `test_v063_custom_wake_ppinyin.py` with the same missing-`kotlinc` error; exit code `1`.
- An earlier attempted recovery configured `JAVA_HOME=C:/Users/ASUS/AppData/Local/Temp/codex-kotlinc17/jdk17/jdk-17.0.20.1+1` and the unavailable temporary Gradle bin `C:/Users/ASUS/AppData/Local/Temp/xiaozhi-gradle-8.9/gradle-8.9/bin`; that setup remains historical.
- The earlier setup also had no Gradle wrapper or system `gradle` command. In the fix-round environment, `GRADLE_WRAPPER_EXISTS=False` and `SUPPLIED_GRADLE_BIN_EXISTS=False`; the Python regression gate does not invoke Gradle, so no Android build result is claimed.
- One post-fix gate retry hit transient native JVM memory pressure in `test_v065_safe_tool_planning.py` while an existing 2 GB Gradle daemon was resident; the isolated failing subtest passed, the idle daemon was stopped, and the subsequent exact combined gate passed.

## Final Verification

- Recovered toolchain configuration:
  - `JAVA_HOME=D:/Program Files (x86)/yinyongdata/FinalShell_fuwuqi/finalshell/jre` (stable full JRE 17.0.7)
  - `ANDROID_HOME=ANDROID_SDK_ROOT=C:/Users/ASUS/AppData/Local/Temp/xiaozhi-android-sdk`
  - `PATH` prepended only with `C:/Users/ASUS/AppData/Local/Temp/codex-kotlinc`
- Exact command `python tools/test_v070_alpha1_chat_home_contract.py && python tools/test_v065_fix04_regression_gate.py`: exit `0`.
- Latest singleton-focused chat contract output: `PASS: v0.7.0-alpha1 chat home structural contract`; exit `0`.
- Latest round-5 combined gate used the same recovered toolchain and exited `0`; the focused chat contract was the first observed line and the required release/FIX04 tail follows below.
- Observed final lines (not the complete command output):
  - `PASS: v0.6.5 release gate`
  - `PASS: FIX04 regression gate`
  - `COMBINED_GATE_EXIT=0`
- `python tools/test_v070_alpha1_chat_home_contract.py`: `PASS: v0.7.0-alpha1 chat home structural contract`, exit `0`.
- `python tools/test_v070_alpha1_settings_migration.py`: PASS, exit `0`.
- `python tools/test_v065_release_metadata.py`: PASS, exit `0`.
- `python tools/test_v065_fix04_release_contract.py`: PASS, exit `0`.
- `python tools/test_v064_wake_regression.py`: PASS, exit `0`.
- `python tools/test_v065_frozen_baseline.py`: PASS, exit `0`.
- Feature commit exists at `dbe383ad0cdd22fc147cb70add6e994e169f6ae5` with subject `feat: replace configuration home with conversation UI`.
- Fix commit exists at `252b19d7c557d2365bd60aaad7408246ed93f755` with subject `fix: restore Task 7 history and session reuse`.
- Shared bridge fix commit exists at `03239fccd34c497602fe2cfdf6dbb5941da1513a` with subject `fix: expose shared Task 7 result bridge`.
- Round-5 fix commit exists at `42f79743f9e77bac01e2d235db421f4988f7bd38` with subject `fix: marshal Task 7 results on main thread`.
