# Phase 0 Red Evidence

Worktree: `E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full`

Base commit verified: `954998eb054fef67a63bcfb6ea67a16c3fd059bd`

## Canonical RED snapshot

### 1) Runtime entry contract

Command:

```powershell
python -X utf8 tools/test_v070_recovery_runtime_entry.py
```

Output:

```text
Traceback (most recent call last):
  File "E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full\tools\test_v070_recovery_runtime_entry.py", line 153, in <module>
    raise AssertionError("runtime entry recovery contract is still missing:\n- " + "\n- ".join(missing))
AssertionError: runtime entry recovery contract is still missing:
- MainActivity typed submit route: missing WakeServiceController\s*\.\s*submitText\s*\(
- SettingsActivity wake settings route: missing WakeServiceController\b
- MainActivity first-install permission grant path: missing callback/equivalent with permission-grant signal and WakeServiceController.start(
- MainActivity granted microphone branch: missing WakeServiceController.start(
```

Why this is expected: the current source still has no shared-controller submit path, no controller-backed settings route, no permission-grant callback/equivalent with controller startup, and no granted microphone branch that starts through the controller.

### 2) Typed submit pipeline contract

Command:

```powershell
python -X utf8 tools/test_v070_recovery_typed_pipeline.py
```

Output:

```text
Traceback (most recent call last):
  File "E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full\tools\test_v070_recovery_typed_pipeline.py", line 89, in <module>
    raise AssertionError("typed pipeline recovery contract is still missing:\n- " + "\n- ".join(missing))
AssertionError: typed pipeline recovery contract is still missing:
- MainActivity typed submit path: still contains ConversationResultBridge.submitText(
- MainActivity text submission route: missing WakeServiceController\s*\.\s*submitText\s*\(
- WakeService text action constant: missing pattern const val ACTION_SUBMIT_TEXT\b
- WakeService text payload constant: missing pattern const val EXTRA_TEXT\b
- WakeService shared text processor: missing pattern fun\s+processAssistantInput\s*\(
- WakeService text service action route: missing ACTION_SUBMIT_TEXT\b
- WakeService text service action route: missing EXTRA_TEXT\b
- WakeService text service action route: missing processAssistantInput\s*\(
```

Why this is expected: the current code still terminates the typed submit path at `ConversationResultBridge.submitText(text)` and does not yet expose the shared controller/service action contract required by the plan.

### 3) UI contract

Command:

```powershell
python -X utf8 tools/test_v070_recovery_ui_contract.py
```

Output:

```text
Traceback (most recent call last):
  File "E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full\tools\test_v070_recovery_ui_contract.py", line 134, in <module>
    raise AssertionError("ui recovery contract is still missing:\n- " + "\n- ".join(missing))
AssertionError: ui recovery contract is still missing:
- MainActivity chat layout: missing file app\src\main\res\layout\activity_main_chat.xml
- SettingsActivity settings layout: missing file app\src\main\res\layout\activity_settings.xml
- MainActivity XML inflation: missing setContentView\s*\(\s*R\.layout\.activity_main_chat\s*\)
- SettingsActivity XML inflation: missing setContentView\s*\(\s*R\.layout\.activity_settings\s*\)
- MainActivity insets handling: missing ViewCompat\.setOnApplyWindowInsetsListener
- MainActivity insets handling: missing WindowCompat\.setDecorFitsSystemWindows
- SettingsActivity insets handling: missing ViewCompat\.setOnApplyWindowInsetsListener
- SettingsActivity insets handling: missing WindowCompat\.setDecorFitsSystemWindows
- MainActivity assistant-name title: missing pattern "\$\{[^"]*assistantName[^"]*\}.*智能体"
- ConversationAdapter scaffold row: still contains android.R.layout.simple_list_item_2
- MainActivity debug subtitle: still contains v0.6.5：会话状态机 + 悬浮层手动退出 + 智能退出 + 自然语言媒体音量
```

Why this is expected: the planned chat/settings XML layouts do not exist yet, the activities still use the old programmatic scaffold, insets hooks are absent, the assistant-name title marker is absent, and the scaffold conversation row plus debug subtitle are still present.
