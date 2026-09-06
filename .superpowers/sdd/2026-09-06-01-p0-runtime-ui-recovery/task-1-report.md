# Phase 0 Task 1 Report

Worktree:

`E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full`

Base commit verified before changes:

`954998eb054fef67a63bcfb6ea67a16c3fd059bd`

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
  File "E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full\tools\test_v070_recovery_runtime_entry.py", line 275, in <module>
    raise AssertionError("runtime entry recovery contract is still missing:\n- " + "\n- ".join(missing))
AssertionError: runtime entry recovery contract is still missing:
- MainActivity typed submit route: missing WakeServiceController\s*\.\s*submitText\s*\(
- SettingsActivity wake settings route: missing reachable WakeServiceController\s*\.\s*(?:start|stop|applyWakeSettings)\s*\(
- WakeServiceController shared implementation: missing app\src\main\java\com\lchuang\xiaozhimobile\runtime\WakeServiceController.kt
- MainActivity first-install permission grant path: missing callback/equivalent with permission-grant signal and WakeServiceController.start(
- MainActivity granted microphone branch: missing reachable WakeServiceController.start( in the granted RECORD_AUDIO branch
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
  File "E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full\tools\test_v070_recovery_typed_pipeline.py", line 249, in <module>
    raise AssertionError("typed pipeline recovery contract is still missing:\n- " + "\n- ".join(missing))
AssertionError: typed pipeline recovery contract is still missing:
- MainActivity typed submit path: still contains pattern ConversationResultBridge\s*\.\s*submitText\s*\(
- MainActivity text submission route: missing WakeServiceController\s*\.\s*submitText\s*\(
- WakeServiceController submit route: missing app\src\main\java\com\lchuang\xiaozhimobile\runtime\WakeServiceController.kt
- WakeService text action constant: missing pattern const val ACTION_SUBMIT_TEXT\b
- WakeService text payload constant: missing pattern const val EXTRA_TEXT\b
- WakeService shared text processor: missing pattern fun\s+processAssistantInput\s*\(
- WakeService text service action route: missing coupled ACTION_SUBMIT_TEXT -> EXTRA_TEXT -> processAssistantInput route
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
  File "E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full\tools\test_v070_recovery_ui_contract.py", line 156, in <module>
    raise AssertionError("ui recovery contract is still missing:\n- " + "\n- ".join(missing))
AssertionError: ui recovery contract is still missing:
- MainActivity chat layout: missing file app\src\main\res\layout\activity_main_chat.xml
- MainActivity XML inflation: missing setContentView\s*\(\s*R\.layout\.activity_main_chat\s*\)
- MainActivity insets handling: missing ViewCompat\.setOnApplyWindowInsetsListener
- MainActivity insets handling: missing WindowCompat\.setDecorFitsSystemWindows
- MainActivity assistant-name title: missing reachable UI binding in onCreate or a function it invokes
- ConversationAdapter scaffold row: still contains android.R.layout.simple_list_item_2
- MainActivity debug subtitle: still contains a debug/version or oversized status literal
```

Why this is expected: the planned chat XML layout does not exist yet, MainActivity still uses the old programmatic scaffold, the MainActivity Insets hooks and assistant-name title binding are absent, and the scaffold conversation row plus debug subtitle are still present. Settings XML remains owned by the later Settings parity task.

## Self-review

- Confirmed the task stayed test/evidence only; no production Kotlin, XML, or Gradle files were changed.
- Confirmed the runtime test now checks a reachable granted-microphone start branch, a permission callback tied to the audio/request signal, controller calls reachable from Settings lifecycle/actions, and the shared controller source.
- Confirmed the typed test keeps the bridge submit call forbidden, couples the controller implementation to `ACTION_SUBMIT_TEXT`/`EXTRA_TEXT`, checks the service-side `processAssistantInput` data flow or an intent-carrying helper, and forbids direct device execution from Activities.
- Confirmed the UI test parses the MainActivity XML, matches real opening tags, scopes the assistant title to code reachable from `onCreate`, checks the Insets path, and leaves Settings XML to the later parity task.

## Commit

Final evidence commit is recorded after the last test-only tightening commit.
