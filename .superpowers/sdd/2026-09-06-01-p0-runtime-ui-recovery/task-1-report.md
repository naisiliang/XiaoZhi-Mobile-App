# Phase 0 Task 1 Report

Worktree:

`E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full`

Base commit verified before changes:

`954998eb054fef67a63bcfb6ea67a16c3fd059bd`

Commit created:

`7bbdeb7e6697fa2815778219fed6a4ee20c59084`

## Files changed

- `tools/test_v070_recovery_runtime_entry.py`
- `tools/test_v070_recovery_typed_pipeline.py`
- `tools/test_v070_recovery_ui_contract.py`
- `.superpowers/sdd/2026-09-06-v070-full-recovery/phase0-red.md`

## RED commands and output

### 1) Runtime entry contract

Command:

```powershell
python -X utf8 tools/test_v070_recovery_runtime_entry.py
```

Output:

```text
Traceback (most recent call last):
  File "E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full\tools\test_v070_recovery_runtime_entry.py", line 64, in <module>
    raise AssertionError("runtime entry recovery contract is still missing:\n- " + "\n- ".join(missing))
AssertionError: runtime entry recovery contract is still missing:
- MainActivity.kt shared wake controller: missing WakeServiceController
- SettingsActivity.kt shared wake controller: missing WakeServiceController
- WakeService.kt shared wake controller: missing WakeServiceController
- MainActivity reachable wake-service start path: missing one of WakeServiceController.start(, WakeServiceController.ensureStarted(, startService(Intent(this, WakeService::class.java)), startForegroundService(Intent(this, WakeService::class.java))
- SettingsActivity shared controller access: missing one of WakeServiceController.shared(, WakeServiceController.getInstance(, WakeServiceController(
- MainActivity shared controller access: missing one of WakeServiceController.shared(, WakeServiceController.getInstance(, WakeServiceController(
```

Why the failure is expected:

- No shared `WakeServiceController` exists in the current source.
- `MainActivity` does not expose a granted-permission path that starts WakeService.
- `SettingsActivity` still talks to `WakeService` directly instead of a shared controller.

### 2) Typed submit pipeline contract

Command:

```powershell
python -X utf8 tools/test_v070_recovery_typed_pipeline.py
```

Output:

```text
Traceback (most recent call last):
  File "E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full\tools\test_v070_recovery_typed_pipeline.py", line 35, in <module>
    raise AssertionError("typed pipeline recovery contract is still missing:\n- " + "\n- ".join(missing))
AssertionError: typed pipeline recovery contract is still missing:
- MainActivity typed submit action: missing ACTION_SUBMIT_TEXT
- MainActivity typed submit payload: missing EXTRA_TEXT
- MainActivity shared assistant-input processor: missing processAssistantInput
- SettingsActivity shared assistant-input processor: missing processAssistantInput
- WakeService shared assistant-input processor: missing processAssistantInput
- MainActivity typed submit path: still contains ConversationResultBridge.submitText(text)
```

Why the failure is expected:

- The current code still terminates the text path at `ConversationResultBridge.submitText(text)`.
- No `ACTION_SUBMIT_TEXT` / `EXTRA_TEXT` intent contract exists yet.
- No shared `processAssistantInput` production entry point exists in the current source.

### 3) UI contract

Command:

```powershell
python -X utf8 tools/test_v070_recovery_ui_contract.py
```

Output:

```text
Traceback (most recent call last):
  File "E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full\tools\test_v070_recovery_ui_contract.py", line 45, in <module>
    raise AssertionError("ui recovery contract is still missing:\n- " + "\n- ".join(missing))
AssertionError: ui recovery contract is still missing:
- MainActivity XML chat layout: missing file app\src\main\res\layout\activity_main.xml
- SettingsActivity XML settings layout: missing file app\src\main\res\layout\activity_settings.xml
- MainActivity XML inflation: missing R.layout.activity_main
- SettingsActivity XML inflation: missing R.layout.activity_settings
- MainActivity assistant-name title: missing ${assistantName}\u667a\u80fd\u4f53
- MainActivity insets handling: missing WindowInsets
- SettingsActivity insets handling: missing WindowInsets
- MainActivity debug subtitle: still contains v0.6.5\uff1a\u4f1a\u8bdd\u72b6\u6001\u673a + \u60ac\u6d6e\u5c42\u624b\u52a8\u9000\u51fa + \u667a\u80fd\u9000\u51fa + \u81ea\u7136\u8bed\u8a00\u5a92\u4f53\u97f3\u91cf
```

Why the failure is expected:

- The app still renders the main chat and settings UIs programmatically.
- There are no XML chat layouts yet.
- Insets handling is not present in the current source.
- The current scaffold still contains the debug subtitle and the conversation list still uses `android.R.layout.simple_list_item_2`.

## Self-review

- Confirmed the work stayed in the isolated worktree only.
- Confirmed the base commit matched `954998eb054fef67a63bcfb6ea67a16c3fd059bd` before editing.
- Confirmed no production Kotlin, XML, or Gradle files were modified.
- Confirmed all three new tests fail for missing recovery capabilities rather than syntax or import errors.
- Confirmed the red-evidence file records the exact command/output pairs.

## Commit

`7bbdeb7e6697fa2815778219fed6a4ee20c59084` - `test: capture v070 recovery regressions`

# Fix Round 1

## What changed

- Tightened `tools/test_v070_recovery_ui_contract.py` so it forbids `android.R.layout.simple_list_item_2` instead of requiring the scaffold row token.
- Tightened `tools/test_v070_recovery_runtime_entry.py` so the granted microphone path must couple to a shared-controller startup token and no longer treats direct `startService` / `startForegroundService` usage as a satisfying fallback.
- Tightened `tools/test_v070_recovery_typed_pipeline.py` so it forbids any `ConversationResultBridge.submitText(` call and ties the submit path to `ACTION_SUBMIT_TEXT`, `EXTRA_TEXT`, and `processAssistantInput` within `onTextResult`.

## Verification rerun

### 1) Runtime entry contract

Command:

```powershell
python -X utf8 tools/test_v070_recovery_runtime_entry.py
```

Output:

```text
Traceback (most recent call last):
  File "E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full\tools\test_v070_recovery_runtime_entry.py", line 101, in <module>
    raise AssertionError("runtime entry recovery contract is still missing:\n- " + "\n- ".join(missing))
AssertionError: runtime entry recovery contract is still missing:
- MainActivity.kt shared wake controller: missing WakeServiceController
- SettingsActivity.kt shared wake controller: missing WakeServiceController
- WakeService.kt shared wake controller: missing WakeServiceController
- MainActivity reachable wake-service start path: missing one of WakeServiceController.start(, WakeServiceController.ensureStarted(
- SettingsActivity shared controller access: missing one of WakeServiceController.shared(, WakeServiceController.getInstance(, WakeServiceController(
- MainActivity shared controller access: missing one of WakeServiceController.shared(, WakeServiceController.getInstance(, WakeServiceController(
- MainActivity granted microphone branch: missing if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
- MainActivity granted microphone branch: missing WakeServiceController.start(
- MainActivity controller startup path: missing WakeServiceController.ensureStarted(
```

### 2) Typed submit pipeline contract

Command:

```powershell
python -X utf8 tools/test_v070_recovery_typed_pipeline.py
```

Output:

```text
Traceback (most recent call last):
  File "E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full\tools\test_v070_recovery_typed_pipeline.py", line 64, in <module>
    raise AssertionError("typed pipeline recovery contract is still missing:\n- " + "\n- ".join(missing))
AssertionError: typed pipeline recovery contract is still missing:
- MainActivity typed submit action: missing ACTION_SUBMIT_TEXT
- MainActivity typed submit payload: missing EXTRA_TEXT
- MainActivity shared assistant-input processor: missing processAssistantInput
- SettingsActivity shared assistant-input processor: missing processAssistantInput
- WakeService shared assistant-input processor: missing processAssistantInput
- MainActivity typed submit path: still contains ConversationResultBridge.submitText(
- MainActivity typed submit path: missing ACTION_SUBMIT_TEXT
- MainActivity typed submit path: missing EXTRA_TEXT
- MainActivity typed submit path: missing processAssistantInput
```

### 3) UI contract

Command:

```powershell
python -X utf8 tools/test_v070_recovery_ui_contract.py
```

Output:

```text
Traceback (most recent call last):
  File "E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full\tools\test_v070_recovery_ui_contract.py", line 45, in <module>
    raise AssertionError("ui recovery contract is still missing:\n- " + "\n- ".join(missing))
AssertionError: ui recovery contract is still missing:
- MainActivity XML chat layout: missing file app\src\main\res\layout\activity_main.xml
- SettingsActivity XML settings layout: missing file app\src\main\res\layout\activity_settings.xml
- MainActivity XML inflation: missing R.layout.activity_main
- SettingsActivity XML inflation: missing R.layout.activity_settings
- MainActivity assistant-name title: missing ${assistantName}\u667a\u80fd\u4f53
- MainActivity insets handling: missing WindowInsets
- SettingsActivity insets handling: missing WindowInsets
- ConversationAdapter scaffold row: still contains android.R.layout.simple_list_item_2
- MainActivity debug subtitle: still contains v0.6.5\uff1a\u4f1a\u8bdd\u72b6\u6001\u673a + \u60ac\u6d6e\u5c42\u624b\u52a8\u9000\u51fa + \u667a\u80fd\u9000\u51fa + \u81ea\u7136\u8bed\u8a00\u5a92\u4f53\u97f3\u91cf
```

## Self-review

- The fix stayed within the existing isolated worktree.
- Only the three red tests and this report file were edited.
- The corrected assertions now match the review findings: forbidden adapter row, coupled runtime startup path, and bridge-call prohibition independent of argument spelling.
- All three focused tests still fail for the intended missing recovery capabilities.

## Verification rerun after runtime-coupling refinement

### 1) Runtime entry contract

Command:

```powershell
python -X utf8 tools/test_v070_recovery_runtime_entry.py
```

Output:

```text
Traceback (most recent call last):
  File "E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full\tools\test_v070_recovery_runtime_entry.py", line 117, in <module>
    raise AssertionError("runtime entry recovery contract is still missing:\n- " + "\n- ".join(missing))
AssertionError: runtime entry recovery contract is still missing:
- MainActivity.kt shared wake controller: missing WakeServiceController
- SettingsActivity.kt shared wake controller: missing WakeServiceController
- WakeService.kt shared wake controller: missing WakeServiceController
- MainActivity reachable wake-service start path: missing one of WakeServiceController.start(, WakeServiceController.ensureStarted(
- SettingsActivity shared controller access: missing one of WakeServiceController.shared(, WakeServiceController.getInstance(, WakeServiceController(
- MainActivity shared controller access: missing one of WakeServiceController.shared(, WakeServiceController.getInstance(, WakeServiceController(
- MainActivity granted microphone branch: missing if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
- MainActivity granted microphone branch: missing one of WakeServiceController.start(, WakeServiceController.ensureStarted(
```

### 2) Typed submit pipeline contract

Command:

```powershell
python -X utf8 tools/test_v070_recovery_typed_pipeline.py
```

Output:

```text
Traceback (most recent call last):
  File "E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full\tools\test_v070_recovery_typed_pipeline.py", line 64, in <module>
    raise AssertionError("typed pipeline recovery contract is still missing:\n- " + "\n- ".join(missing))
AssertionError: typed pipeline recovery contract is still missing:
- MainActivity typed submit action: missing ACTION_SUBMIT_TEXT
- MainActivity typed submit payload: missing EXTRA_TEXT
- MainActivity shared assistant-input processor: missing processAssistantInput
- SettingsActivity shared assistant-input processor: missing processAssistantInput
- WakeService shared assistant-input processor: missing processAssistantInput
- MainActivity typed submit path: still contains ConversationResultBridge.submitText(
- MainActivity typed submit path: missing ACTION_SUBMIT_TEXT
- MainActivity typed submit path: missing EXTRA_TEXT
- MainActivity typed submit path: missing processAssistantInput
```

### 3) UI contract

Command:

```powershell
python -X utf8 tools/test_v070_recovery_ui_contract.py
```

Output:

```text
Traceback (most recent call last):
  File "E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full\tools\test_v070_recovery_ui_contract.py", line 45, in <module>
    raise AssertionError("ui recovery contract is still missing:\n- " + "\n- ".join(missing))
AssertionError: ui recovery contract is still missing:
- MainActivity XML chat layout: missing file app\src\main\res\layout\activity_main.xml
- SettingsActivity XML settings layout: missing file app\src\main\res\layout\activity_settings.xml
- MainActivity XML inflation: missing R.layout.activity_main
- SettingsActivity XML inflation: missing R.layout.activity_settings
- MainActivity assistant-name title: missing ${assistantName}\u667a\u80fd\u4f53
- MainActivity insets handling: missing WindowInsets
- SettingsActivity insets handling: missing WindowInsets
- ConversationAdapter scaffold row: still contains android.R.layout.simple_list_item_2
- MainActivity debug subtitle: still contains v0.6.5\uff1a\u4f1a\u8bdd\u72b6\u6001\u673a + \u60ac\u6d6e\u5c42\u624b\u52a8\u9000\u51fa + \u667a\u80fd\u9000\u51fa + \u81ea\u7136\u8bed\u8a00\u5a92\u4f53\u97f3\u91cf
```

# Fix Round 2

## What changed

- Reworked `tools/test_v070_recovery_runtime_entry.py` so the microphone-granted startup path is checked by extracting the exact `if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)` block and requiring a shared-controller start token inside that block.
- Kept the earlier UI and typed-pipeline fixes intact.

## Verification rerun

### 1) Runtime entry contract

Command:

```powershell
python -X utf8 tools/test_v070_recovery_runtime_entry.py
```

Output:

```text
Traceback (most recent call last):
  File "E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full\tools\test_v070_recovery_runtime_entry.py", line 133, in <module>
    raise AssertionError("runtime entry recovery contract is still missing:\n- " + "\n- ".join(missing))
AssertionError: runtime entry recovery contract is still missing:
- MainActivity.kt shared wake controller: missing WakeServiceController
- SettingsActivity.kt shared wake controller: missing WakeServiceController
- WakeService.kt shared wake controller: missing WakeServiceController
- MainActivity reachable wake-service start path: missing one of WakeServiceController.start(, WakeServiceController.ensureStarted(
- SettingsActivity shared controller access: missing one of WakeServiceController.shared(, WakeServiceController.getInstance(, WakeServiceController(
- MainActivity shared controller access: missing one of WakeServiceController.shared(, WakeServiceController.getInstance(, WakeServiceController(
- MainActivity granted microphone branch: missing if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
```

Why this is expected: the current source still has no shared `WakeServiceController`, so the granted-permission branch cannot contain the required controller start call yet.

### 2) Typed submit pipeline contract

Command:

```powershell
python -X utf8 tools/test_v070_recovery_typed_pipeline.py
```

Output:

```text
Traceback (most recent call last):
  File "E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full\tools\test_v070_recovery_typed_pipeline.py", line 64, in <module>
    raise AssertionError("typed pipeline recovery contract is still missing:\n- " + "\n- ".join(missing))
AssertionError: typed pipeline recovery contract is still missing:
- MainActivity typed submit action: missing ACTION_SUBMIT_TEXT
- MainActivity typed submit payload: missing EXTRA_TEXT
- MainActivity shared assistant-input processor: missing processAssistantInput
- SettingsActivity shared assistant-input processor: missing processAssistantInput
- WakeService shared assistant-input processor: missing processAssistantInput
- MainActivity typed submit path: still contains ConversationResultBridge.submitText(
- MainActivity typed submit path: missing ACTION_SUBMIT_TEXT
- MainActivity typed submit path: missing EXTRA_TEXT
- MainActivity typed submit path: missing processAssistantInput
```

### 3) UI contract

Command:

```powershell
python -X utf8 tools/test_v070_recovery_ui_contract.py
```

Output:

```text
Traceback (most recent call last):
  File "E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full\tools\test_v070_recovery_ui_contract.py", line 45, in <module>
    raise AssertionError("ui recovery contract is still missing:\n- " + "\n- ".join(missing))
AssertionError: ui recovery contract is still missing:
- MainActivity XML chat layout: missing file app\src\main\res\layout\activity_main.xml
- SettingsActivity XML settings layout: missing file app\src\main\res\layout\activity_settings.xml
- MainActivity XML inflation: missing R.layout.activity_main
- SettingsActivity XML inflation: missing R.layout.activity_settings
- MainActivity assistant-name title: missing ${assistantName}\u667a\u80fd\u4f53
- MainActivity insets handling: missing WindowInsets
- SettingsActivity insets handling: missing WindowInsets
- ConversationAdapter scaffold row: still contains android.R.layout.simple_list_item_2
- MainActivity debug subtitle: still contains v0.6.5\uff1a\u4f1a\u8bdd\u72b6\u6001\u673a + \u60ac\u6d6e\u5c42\u624b\u52a8\u9000\u51fa + \u667a\u80fd\u9000\u51fa + \u81ea\u7136\u8bed\u8a00\u5a92\u4f53\u97f3\u91cf
```

## Self-review

- The runtime assertion is now branch-structural, not just token-based.
- The UI and typed-pipeline assertions remain unchanged apart from earlier round-1 fixes.
- No production Kotlin, XML, or Gradle files were modified.

# Fix Round 3

## What changed

- Updated `tools/test_v070_recovery_runtime_entry.py` so the shared-controller contract is tied to `MainActivity.onTextResult`, `SettingsActivity.applyWakeSettingsIfRunning`, the exact granted microphone branch, and a flexible first-install permission-grant callback/equivalent path.
- Updated `tools/test_v070_recovery_typed_pipeline.py` so `MainActivity` must route text through `WakeServiceController.submitText(...)`, `SettingsActivity` must still stay on the shared-controller wake-settings path, and `WakeService` must own `ACTION_SUBMIT_TEXT`/`EXTRA_TEXT`/`processAssistantInput`.
- Replaced `tools/test_v070_recovery_ui_contract.py` so it uses the planned `activity_main_chat.xml` filename, asserts executable `setContentView(...)` and insets paths, and reads the XML layouts for real chat/settings structure markers.
- Kept the task tests-only; no production Kotlin/XML/Gradle files were changed.

## Verification rerun

### 1) Runtime entry contract

Command:

```powershell
python -X utf8 tools/test_v070_recovery_runtime_entry.py
```

Output:

```text
Traceback (most recent call last):
  File "E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full\tools\test_v070_recovery_runtime_entry.py", line 94, in <module>
    raise AssertionError("runtime entry recovery contract is still missing:\n- " + "\n- ".join(missing))
AssertionError: runtime entry recovery contract is still missing:
- MainActivity shared controller contract: missing WakeServiceController
- SettingsActivity shared controller contract: missing WakeServiceController
- MainActivity text submission route: missing WakeServiceController.submitText(text)
- SettingsActivity wake settings route: missing WakeServiceController.
- MainActivity granted microphone branch: missing if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
- MainActivity first-install permission grant path: missing one of onRequestPermissionsResult(, registerForActivityResult(, ActivityResultContracts.RequestPermission, ActivityResultContracts.RequestMultiplePermissions
```

### 2) Typed submit pipeline contract

Command:

```powershell
python -X utf8 tools/test_v070_recovery_typed_pipeline.py
```

Output:

```text
Traceback (most recent call last):
  File "E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full\tools\test_v070_recovery_typed_pipeline.py", line 75, in <module>
    raise AssertionError("typed pipeline recovery contract is still missing:\n- " + "\n- ".join(missing))
AssertionError: typed pipeline recovery contract is still missing:
- MainActivity typed submit path: still contains ConversationResultBridge.submitText(
- MainActivity shared controller contract: missing WakeServiceController
- SettingsActivity shared controller contract: missing WakeServiceController
- MainActivity text submission route: missing WakeServiceController.submitText(text)
- SettingsActivity wake settings route: missing WakeServiceController.
- WakeService text action constant: missing const val ACTION_SUBMIT_TEXT
- WakeService text payload constant: missing const val EXTRA_TEXT
- WakeService shared text processor: missing fun processAssistantInput(
- WakeService text service action route: missing ACTION_SUBMIT_TEXT
- WakeService text service action route: missing EXTRA_TEXT
- WakeService text service action route: missing processAssistantInput(
```

### 3) UI contract

Command:

```powershell
python -X utf8 tools/test_v070_recovery_ui_contract.py
```

Output:

```text
Traceback (most recent call last):
  File "E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full\tools\test_v070_recovery_ui_contract.py", line 109, in <module>
    raise AssertionError("ui recovery contract is still missing:\n- " + "\n- ".join(missing))
AssertionError: ui recovery contract is still missing:
- MainActivity chat layout: missing file app\src\main\res\layout\activity_main_chat.xml
- SettingsActivity settings layout: missing file app\src\main\res\layout\activity_settings.xml
- MainActivity XML inflation: missing setContentView(R.layout.activity_main_chat)
- SettingsActivity XML inflation: missing setContentView(R.layout.activity_settings)
- MainActivity insets handling: missing ViewCompat.setOnApplyWindowInsetsListener
- MainActivity insets handling: missing WindowCompat.setDecorFitsSystemWindows
- SettingsActivity insets handling: missing ViewCompat.setOnApplyWindowInsetsListener
- SettingsActivity insets handling: missing WindowCompat.setDecorFitsSystemWindows
- MainActivity assistant-name title: missing pattern "\$\{[^"]*assistantName[^"]*\}.*智能体"
- ConversationAdapter scaffold row: still contains android.R.layout.simple_list_item_2
- MainActivity debug subtitle: still contains v0.6.5：会话状态机 + 悬浮层手动退出 + 智能退出 + 自然语言媒体音量
```

## Self-review

- The runtime test now expresses the grant-path structure and the first-install callback/equivalent requirement without shared/getInstance/constructor assertions.
- The typed test now keeps `ConversationResultBridge.submitText(` forbidden while moving the submit contract onto `WakeServiceController` and the service-side action processor.
- The UI test now keys off the planned `activity_main_chat.xml` filename and real layout structure markers instead of the old programmatic scaffold.
- `SettingsActivity` wake-settings ownership remains only asserted as a controller-backed path; background wake state ownership itself is documented as Task 2-owned rather than forced into Task 1.
