# Task 6 report: move old configuration UI into SettingsActivity

## TDD evidence

1. RED: wrote `tools/test_v070_alpha1_settings_migration.py` before production changes.
2. Ran `python tools/test_v070_alpha1_settings_migration.py`.
   - Exit code: 1.
   - Intended failure: `FileNotFoundError` for `app/src/main/java/com/lchuang/xiaozhimobile/SettingsActivity.kt`.
3. Added the minimum `SettingsActivity` contract, MainActivity navigation entry, and manifest declaration.
4. GREEN: ran `python tools/test_v070_alpha1_settings_migration.py`.
   - Exact output: `PASS: v0.7.0-alpha1 settings migrated to SettingsActivity`
   - Exit code: 0.

## Review round 1 TDD evidence

1. RED: strengthened `tools/test_v070_alpha1_settings_migration.py` with explicit navigation, manifest, SettingsStore load/save, KWS-isolation, and MainActivity-removal assertions.
2. Ran `python tools/test_v070_alpha1_settings_migration.py` before the production correction.
   - Exit code: 1.
   - Intended failure: `AssertionError: MainActivity.kt still contains migrated configuration markers`, including the legacy fields, UI assignments, `settings.*` writes/reads, `loadSettings()`, and `saveSettings()`.
3. GREEN: removed the legacy configuration form and migrated setting state from MainActivity, retaining only the home, permission, overlay, and wake-service shell behavior.
4. Re-ran `python tools/test_v070_alpha1_settings_migration.py`.
   - Exact output: `PASS: v0.7.0-alpha1 settings migrated to SettingsActivity`
   - Exit code: 0.

## Review round 1 test-quality follow-up

1. Test-first change: replaced global positive string checks with readable helpers that locate balanced class and method bodies, then assert lifecycle, save-listener, per-setting load/save bindings, guarded wake application, isolation markers, MainActivity removal markers, and manifest attributes in their relevant scopes.
2. Ran `python tools/test_v070_alpha1_settings_migration.py` immediately after the test-only change.
   - Exact output: `PASS: v0.7.0-alpha1 settings migration structure`
   - Exit code: 0.
3. The current production implementation satisfied the strengthened regression contract, so no production correction was invented or made in this follow-up.

## Fresh important-finding correction

1. RED: expanded `tools/test_v070_alpha1_settings_migration.py` before the production correction to inventory every user-editable `SettingsStore` property, require a real control and lifecycle binding for each one, and require password input plus no API-key exposure in UI/log paths.
2. Ran `python tools/test_v070_alpha1_settings_migration.py` before the production correction.
   - Exit code: 1.
   - Intended failure: `AssertionError: SettingsActivity control declaration for assistantName is missing: private lateinit var assistantName: EditText`.
3. GREEN: added the missing SettingsActivity controls and SettingsStore load/save bindings for assistant name, wake/timeout replies, session timeout, offline ASR preference, TTS voice/rate/pitch, API key/model/mode, and system prompt; preserved the existing five controls and guarded wake action.
4. Re-ran `python tools/test_v070_alpha1_settings_migration.py`.
   - Exact output: `PASS: v0.7.0-alpha1 settings migration structure`
   - Exit code: 0.

## Verification

The required command was run from the worktree with the recovered bounded toolchain environment:

```text
python tools/test_v070_alpha1_settings_migration.py && python tools/test_v065_fix04_regression_gate.py
```

Resolved compiler executable:

```text
C:\Users\ASUS\AppData\Local\Temp\codex-kotlinc\kotlinc.exe
```

Exit code: `0`.

Exact final PASS output from the recovered rerun:

```text
PASS: v0.7.0-alpha1 settings migration structure
PASS: natural Chinese media volume parser
PASS: FIX04 media volume controller behavior
PASS: FIX04 phone controller media-volume mapping
PASS: FIX04 Task 1 media volume controller regression coverage
PASS: FIX04 Task 2 media volume delayed readback behavior
PASS: FIX04 Task 2 media volume algorithm contract
PASS: FIX04 Task 3 stepped media volume verification
PASS: FIX04 Task 3 media volume step contract
PASS: FIX04 Task 4 verified media volume result feedback states
PASS: v0.6.5 frozen baseline and version
PASS: v0.6 security regression
PASS: v0.6.5 release gate
PASS: FIX04 regression gate
```

The gate also emitted the existing Kotlin compiler warning below while compiling its fixtures; it did not affect the successful exit:

```text
warning: unable to find kotlin-script-runtime.jar in the Kotlin home directory. Pass either '-no-stdlib' to prevent adding it to the classpath, or the correct '-kotlin-home'
```

The focused test and the complete prior-stage FIX04 regression gate therefore pass with the recovered toolchain.

Additional preserved-contract checks completed with exit code 0:

```text
PASS: v0.6.2 custom wake phrase is automatically applied and actual runtime phrase is visible
PASS: v0.6.5 visible release metadata
PASS: preserved wake-apply and release metadata checks
```

## Historical environment setup

- Earlier pre-recovery attempts are historical only: the gate failed at `tools/test_v064_volume_parser.py` with `FileNotFoundError: [WinError 2]` because `kotlinc` was not on PATH, and the offline Gradle attempt used `C:\Users\ASUS\.gradle\wrapper\dists\gradle-7.6.1-bin\2clxi94ab3brv6467628wnxmd\gradle-7.6.1\bin\gradle.bat` and failed because AGP requires Gradle 8.9.
- Successful recovery used `JAVA_HOME=C:\Users\ASUS\AppData\Local\Temp\codex-kotlinc17\jdk17\jdk-17.0.20.1+1`.
- Successful recovery used `ANDROID_HOME=C:\Users\ASUS\AppData\Local\Temp\xiaozhi-android-sdk` and `ANDROID_SDK_ROOT=C:\Users\ASUS\AppData\Local\Temp\xiaozhi-android-sdk`.
- Successful recovery prepended `C:\Users\ASUS\AppData\Local\Temp\codex-kotlinc` and `C:\Users\ASUS\AppData\Local\Temp\xiaozhi-gradle-8.9\gradle-8.9\bin` to PATH.
- No repository source files were changed for environment setup.
- The requested Android compile path `C:\Users\ASUS\AppData\Local\Temp\xiaozhi-gradle-8.9\gradle-8.9\bin\gradle.bat` was checked after the source fix but does not exist in this environment; PowerShell reported the path was not recognized, so no compile result was claimed.

The historical Gradle path note above predates the fresh correction. For this correction, Gradle 8.9 was downloaded outside the repository and the focused Android test completed successfully; see Fresh verification below.

## Changed paths

- `app/src/main/java/com/lchuang/xiaozhimobile/SettingsActivity.kt`
- `app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt`
- `app/src/main/AndroidManifest.xml`
- `tools/test_v070_alpha1_settings_migration.py`
- Review round 1 source/test fix: `app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt`, `tools/test_v070_alpha1_settings_migration.py`
- Review round 1 report correction: `.superpowers/sdd/2026-09-01-xiaozhi-mobile-v0.7.0-alpha1-foundation/task-6-report.md`
- Review round 1 test-quality follow-up: `tools/test_v070_alpha1_settings_migration.py`, `.superpowers/sdd/2026-09-01-xiaozhi-mobile-v0.7.0-alpha1-foundation/task-6-report.md`
- Current report finalization: `.superpowers/sdd/2026-09-01-xiaozhi-mobile-v0.7.0-alpha1-foundation/task-6-report.md`
- Fresh important-finding correction: `app/src/main/java/com/lchuang/xiaozhimobile/SettingsActivity.kt`, `tools/test_v070_alpha1_settings_migration.py`, and this report.

## Commit

- Implementation commit: `78b8a7852bb0a6c9c158e0e17de48884ea6d9124` (`feat: move configuration into settings activity`).
- Initial verification/report commit: `ef769c918c515a86cb1e079d792e801e5842821d` (`docs: record successful Task 6 regression gate`).
- Implementation fix commit: `0a637f159fdd685ad8918d07bf3f88578b568901` (`fix: move settings ownership to settings activity`).
- Review-fix verification report commit: `7e35c28fedae8f98703f611e814e5cb5afba3fdd` (`docs: record Task 6 review fix verification`).
- Focused contract-test commit: `9e33d07572e788d3d19647cd4687e537c4f6eb4d` (`test: strengthen Task 6 settings migration contract`).
- Structural-test verification report commit: `1be544431be062859abc9758ba452427a9081d0a` (`docs: record Task 6 structural test verification`).
- Reviewed-gate metadata commit: `0447f7eedc99b69a28523a87cd179637f8699caf` (`docs: finalize Task 6 commit history metadata`).
- Post-gate current-state correction: `ee1e674c6ad9e9c622e6a217d20816cfed265cdf` (`fix: keep all settings editable after migration`), updating `SettingsActivity`, its focused contract, and this report. This commit is outside Jason's reviewed range; it carries no new reviewer verdict.

The current report-only correction is intentionally not embedded here to avoid a self-referential SHA.

## Scope and invariant confirmations

- `SettingsActivity` exposes and persists all 16 user-editable SettingsStore properties: `assistantName`, `wakePhrase`, `wakeReply`, `timeoutReply`, `sessionTimeoutSeconds`, `appAliases`, `defaultMapApp`, `ttsVoiceName`, `ttsSpeechRate`, `ttsPitch`, `apiBaseUrl`, `apiKey`, `model`, `apiMode`, `systemPrompt`, and `preferOfflineAsr`. Runtime-only `activeWakePhrase` remains outside the editable settings form.
- Settings are loaded and saved through the existing `SettingsStore`.
- `apiKey` is loaded/saved through a password-variation `EditText`; this Activity has no log, status, notification, or test-detail path that includes the key.
- Wake changes use the existing `WakeService.ACTION_APPLY_WAKE_SETTINGS` entry point only when the service is already running.
- `SettingsActivity.kt` does not contain `initKeywordSpotter` and does not reimplement KWS internals.
- `WakePhraseCompiler.kt`, `WakePhraseManager.kt`, and `WakeService.kt` are unchanged.
- `versionCode = 12` and `versionName = "0.6.5"` are unchanged.
- No Task 7 conversation UI or Task 8 WakeService wiring was added.
- This fresh correction changed only SettingsActivity, the Task 6 focused contract test, and this report; it did not modify Task 7/Conversation code, WakeService/KWS code, or frozen files.
- Existing unrelated untracked crash logs, `.kotlin/`, and `tools/__pycache__/` artifacts were preserved.

## Concerns

- The Kotlin fixture runs emitted the existing `kotlin-script-runtime.jar` warning; they completed successfully with exit code 0. Gradle emitted the existing Android SDK package-location warning, an AssistantOverlayView deprecation warning, and a multiple-Kotlin-daemon warning; the focused Android test still completed successfully.

## Fresh verification

Environment for the Kotlin fixture runs:

```text
JAVA_HOME=C:\Users\ASUS\AppData\Local\Temp\codex-kotlinc\jdk17\jdk-17.0.20.1+1
PATH prepended with C:\Users\ASUS\AppData\Local\Temp\codex-kotlinc
```

Environment for the Android focused test:

```text
JAVA_HOME=C:\Users\ASUS\AppData\Local\Temp\codex-kotlinc\jdk17\jdk-17.0.20.1+1
ANDROID_HOME=C:\Users\ASUS\AppData\Local\Temp\xiaozhi-android-sdk
ANDROID_SDK_ROOT=C:\Users\ASUS\AppData\Local\Temp\xiaozhi-android-sdk
Gradle=C:\Users\ASUS\AppData\Local\Temp\codex-gradle-8.9\dist\gradle-8.9\bin\gradle.bat
```

Fresh commands and results:

```text
python tools/test_v070_alpha1_settings_migration.py
PASS: v0.7.0-alpha1 settings migration structure
EXIT=0

python tools/test_v060_settings.py
PASS: v0.6 settings and permissions
EXIT=0

python tools/test_v060_tts.py
PASS: v0.6 TTS voice source
EXIT=0

python tools/test_v060_voice_integration.py
PASS: v0.6 voice integration source
EXIT=0

python tools/test_v060_ai_endpoint.py
PASS: AI endpoint resolver
PASS: v0.6 AI endpoint source
EXIT=0

python tools/test_v060_security.py
PASS: v0.6 security regression
EXIT=0

python tools/test_v070_alpha1_chat_home_contract.py
PASS: v0.7.0-alpha1 chat home structural contract
EXIT=0

python tools/test_v070_alpha1_session_wiring.py
PASS: v0.7.0-alpha1 WakeService session wiring
EXIT=0

python tools/test_v065_frozen_baseline.py
PASS: v0.6.5 frozen baseline and version
EXIT=0

python tools/test_v065_fix04_regression_gate.py
PASS: FIX04 regression gate
EXIT=0

gradle :app:testDebugUnitTest --no-daemon --offline
BUILD SUCCESSFUL in 29s
22 actionable tasks: 5 executed, 17 up-to-date
EXIT=0

git diff --check
EXIT=0 (no output)
```

The earlier status saying `tools/test_v060_tts_settings_action.py` and `tools/test_v060_ui_source.py` still failed is historical: it described the pre-`765e0dd569356b2159e1d5b1f076f3e8a2bdf33b` test contracts after the Alpha1 migration. Following that test-alignment commit, both scripts were rerun in a clean checkout of the committed `765e0dd569356b2159e1d5b1f076f3e8a2bdf33b` tree and passed:

```text
PASS: v0.6 TTS settings persist in reachable SettingsActivity
PASS: v0.6 settings migrated and chat-home diagnostics remain wired
```

This is the current result of the committed branch state after `765e0dd`, not a new Task 6 reviewer verdict. The following sentence describes the transient pre-commit worktree state at the time of the original report: a separate uncommitted source/test change caused an in-place `test_v060_ui_source.py` failure because an unrelated `MainActivity` change removed the legacy marker. That historical state is not attributed to `765e0dd` or to Task 6's reviewed range. The current-branch source changes are covered by the later whole-branch fresh review; this report does not claim that historical transient state is present now. The initial available Gradle 7.6.1 attempt also failed before compilation because AGP requires Gradle 8.9; the explicit Gradle 8.9 run above is the authoritative compile result.
