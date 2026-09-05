# Phase 0 Red Evidence

Worktree: `E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full`

Base commit verified: `954998eb054fef67a63bcfb6ea67a16c3fd059bd`

## 1) Runtime entry contract

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

Why this is expected: the current source still has no shared `WakeServiceController`, no controller-based access path in `MainActivity` or `SettingsActivity`, and no reachable start path for WakeService once microphone permission is already granted.

## 2) Typed submit pipeline contract

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

Why this is expected: the current code still sends text straight through `ConversationResultBridge.submitText(text)` and does not expose the required action/payload boundary or shared `processAssistantInput` production references.

## 3) UI contract

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

Why this is expected: the current app still renders the chat and settings UIs programmatically, has no XML chat layouts or inset-aware layout contract yet, still uses the debug subtitle, and the conversation list still renders with `android.R.layout.simple_list_item_2`.
