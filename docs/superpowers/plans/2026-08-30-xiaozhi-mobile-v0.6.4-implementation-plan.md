# XiaoZhi Mobile v0.6.4 Experience Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade XiaoZhi Mobile v0.6.4 so post-wake interaction is state-driven, verbally confirms device actions before safely re-listening, supports close-button/double-tap and intelligent voice exit, and reliably controls Android media volume from natural Chinese.

**Architecture:** Keep the v0.6.3 custom wake/KWS path frozen. Add three focused pure-Kotlin components (`ConversationState`, `ConversationExitDetector`, `VolumeCommandParser`), make `PhoneController` expose verified media-volume results, make the overlay a panel-sized touchable window, and let `WakeService` own an explicit post-wake state machine with guarded TTS→ASR transitions, exit idempotence, and duplicate-command protection.

**Tech Stack:** Android/Kotlin, Android `AudioManager`, `WindowManager`, `GestureDetector`, sherpa-onnx v1.13.4, offline Paraformer ASR, Android system TTS, Python source/regression tests, `kotlinc` JVM harness tests, GitHub Actions Android SDK 35 / JDK 17 / Gradle 8.9.

**Spec:** `docs/superpowers/specs/2026-08-30-xiaozhi-mobile-v0.6.4-experience-design.md`

**Baseline:** Git commit `08b6fdff7cca0d94f16340e6573498f26172fbd9` (v0.6.3).

## Global Constraints

- `versionName = 0.6.4`.
- `versionCode = 11`.
- Artifact name must be `XiaoZhi-Mobile-v0.6.4-debug.apk`.
- Generic voice-volume commands control only `AudioManager.STREAM_MUSIC`.
- Never change ringtone, notification, or alarm volume for generic volume commands.
- v0.6.3 custom wake is frozen: do not modify `WakePhraseCompiler.kt`, `WakePhraseManager.kt`, `Pinyin4jProvider.kt`, KWS model paths/files, KWS threshold/score/trailing-blank parameters, runtime keyword creation rules, or custom wake save/apply semantics.
- `tools/test_v063_custom_wake_ppinyin.py` and `tools/test_v063_wake_error_diagnostics.py` must remain green.
- Local deterministic device commands remain ahead of AI.
- ASR must never be started while session TTS is speaking.
- Every exit path must be idempotent and must resume the already-active custom KWS rather than resetting to the default wake phrase.
- Do not commit or print API keys.
- Every production behavior change starts with a failing test and is verified green before moving to the next task.

---

## File Structure Map

### New production files

- `app/src/main/java/com/lchuang/xiaozhimobile/ConversationState.kt`
  - Pure enum for the post-wake conversation lifecycle.
  - Provides canonical overlay status text.

- `app/src/main/java/com/lchuang/xiaozhimobile/ConversationExitDetector.kt`
  - Pure local classifier.
  - Returns `EXIT`, `CONTINUE`, or `AMBIGUOUS`.
  - Protects app/account/page-targeted “退出...” requests from closing XiaoZhi.

- `app/src/main/java/com/lchuang/xiaozhimobile/VolumeCommandParser.kt`
  - Pure parser for natural Chinese media-volume language.
  - Produces `SetPercent`, `StepUp`, `StepDown`, or `Unhandled`.

### Modified production files

- `app/src/main/java/com/lchuang/xiaozhimobile/PhoneController.kt`
  - Add verified media-volume APIs.
  - Re-read actual `STREAM_MUSIC` after every mutation.
  - Use `FLAG_SHOW_UI`.

- `app/src/main/java/com/lchuang/xiaozhimobile/CommandRouter.kt`
  - Delegate all generic media-volume parsing to `VolumeCommandParser`.
  - Build replies from verified actual media volume.

- `app/src/main/java/com/lchuang/xiaozhimobile/AssistantOverlayView.kt`
  - Render inside a panel-sized window.
  - Add close target and double-tap gesture.
  - Add conversation-state-aware visual behavior.

- `app/src/main/java/com/lchuang/xiaozhimobile/AssistantOverlayController.kt`
  - Create only a panel-sized touchable overlay.
  - Add `setOnExitRequested`.
  - Keep outside-panel touches available to underlying apps.

- `app/src/main/java/com/lchuang/xiaozhimobile/AiOrchestrator.kt`
  - Add one no-tools `classifyExitIntent()` request.
  - Return only `EXIT` or `CONTINUE`.

- `app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt`
  - Integrate explicit conversation state.
  - Sequence completion prompt → TTS done → guard → real listening.
  - Hook manual exit and intelligent exit.
  - Cancel stale re-listen callbacks.
  - Add session generation guard and duplicate-device-command guard.
  - Do not touch the frozen KWS implementation.

- `app/build.gradle.kts`
  - Set version code/name.

- `.github/workflows/build-apk.yml`
  - Rename final v0.6.4 APK.

- `README.md`, `BUILD_NOTES.md`, `GITHUB_BUILD_GUIDE.md`
  - Update v0.6.4 behavior and device-test instructions.

### New test files

- `tools/test_v064_conversation_state.py`
- `tools/test_v064_overlay_exit.py`
- `tools/test_v064_exit_intent.py`
- `tools/test_v064_volume_parser.py`
- `tools/test_v064_volume_execution.py`
- `tools/test_v064_command_prompt_flow.py`
- `tools/test_v064_duplicate_guard.py`
- `tools/test_v064_wake_regression.py`

---

### Task 1: Commit the Approved v0.6.4 Spec and Add a Frozen-Wake Guard

**Files:**
- Create: `docs/superpowers/specs/2026-08-30-xiaozhi-mobile-v0.6.4-experience-design.md`
- Create: `tools/test_v064_wake_regression.py`
- Read-only baseline: `app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseCompiler.kt`
- Read-only baseline: `app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseManager.kt`
- Read-only baseline: `app/src/main/java/com/lchuang/xiaozhimobile/Pinyin4jProvider.kt`
- Read-only baseline: `app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt`

**Interfaces:**
- Consumes: existing v0.6.3 custom wake code and tests.
- Produces: a regression gate that fails if v0.6.4 accidentally changes frozen wake components or KWS constants.

- [ ] **Step 1: Add the approved spec to the repository**

Copy the already-approved document verbatim to:

```text
docs/superpowers/specs/2026-08-30-xiaozhi-mobile-v0.6.4-experience-design.md
```

Do not rewrite the requirements while implementing.

- [ ] **Step 2: Write the compatibility guard before any v0.6.4 production edits**

Create `tools/test_v064_wake_regression.py` so the test compares the working tree directly with the known-good v0.6.3 Git baseline instead of relying on manually copied hashes:

```python
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[1]
baseline = "08b6fdff7cca0d94f16340e6573498f26172fbd9"

frozen_paths = [
    "app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseCompiler.kt",
    "app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseManager.kt",
    "app/src/main/java/com/lchuang/xiaozhimobile/Pinyin4jProvider.kt",
]

subprocess.run(
    ["git", "diff", "--exit-code", baseline, "--", *frozen_paths],
    cwd=root,
    check=True,
)

wake_path = "app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt"
current = (root / wake_path).read_text(encoding="utf-8")
baseline_text = subprocess.check_output(
    ["git", "show", f"{baseline}:{wake_path}"],
    cwd=root,
    text=True,
    encoding="utf-8",
)

def kws_block(text: str) -> str:
    match = re.search(
        r"private fun initKeywordSpotter\(\)\s*\{.*?\n    \}\n\n    private fun initOfflineAsr",
        text,
        flags=re.S,
    )
    assert match, "initKeywordSpotter block not found"
    return match.group(0)

assert kws_block(current) == kws_block(baseline_text), "KWS initialization changed"
assert 'private const val KWS_MODEL_DIR = "sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20"' in current

subprocess.run(["python", "tools/test_v063_custom_wake_ppinyin.py"], cwd=root, check=True)
subprocess.run(["python", "tools/test_v063_wake_error_diagnostics.py"], cwd=root, check=True)
print("PASS: v0.6.4 preserves v0.6.3 custom wake behavior")
```

This is a compatibility characterization guard: it must pass on the untouched v0.6.3 baseline and remain green through every v0.6.4 task.

- [ ] **Step 3: Run the guard on untouched v0.6.3**

Run:

```bash
python tools/test_v064_wake_regression.py
```

Expected: PASS on baseline v0.6.3.

This task is a characterization/compatibility guard, so it is intentionally green before feature implementation; subsequent tasks must keep it green.

- [ ] **Step 4: Commit the spec and guard**

```bash
git add docs/superpowers/specs/2026-08-30-xiaozhi-mobile-v0.6.4-experience-design.md tools/test_v064_wake_regression.py
git commit -m "test: freeze v0.6.3 custom wake for v0.6.4"
```

---

### Task 2: Add the Pure Conversation State Model

**Files:**
- Create: `app/src/main/java/com/lchuang/xiaozhimobile/ConversationState.kt`
- Create: `tools/test_v064_conversation_state.py`

**Interfaces:**
- Produces:
  - `enum class ConversationState`
  - `fun ConversationState.statusText(): String`
- Later consumed by `AssistantOverlayView` and `WakeService`.

- [ ] **Step 1: Write a failing Kotlin harness test**

Create `tools/test_v064_conversation_state.py` that compiles a Kotlin harness against the future `ConversationState.kt` and asserts:

```kotlin
check(ConversationState.IDLE_WAKE.statusText() == "等待唤醒…")
check(ConversationState.LISTENING.statusText() == "正在听你说…")
check(ConversationState.RECOGNIZING.statusText() == "正在识别…")
check(ConversationState.EXECUTING.statusText() == "正在执行…")
check(ConversationState.SPEAKING.statusText() == "正在回复…")
check(ConversationState.READY_TO_LISTEN.statusText() == "准备继续监听…")
check(ConversationState.EXITING.statusText() == "正在退出…")
```

The Python wrapper should use the same `kotlinc`/`java -jar` pattern as `tools/test_v063_custom_wake_ppinyin.py`.

- [ ] **Step 2: Run it and verify RED**

```bash
python tools/test_v064_conversation_state.py
```

Expected: FAIL because `ConversationState.kt` does not exist.

- [ ] **Step 3: Implement the minimal pure enum**

Create:

```kotlin
package com.lchuang.xiaozhimobile

enum class ConversationState {
    IDLE_WAKE,
    LISTENING,
    RECOGNIZING,
    EXECUTING,
    SPEAKING,
    READY_TO_LISTEN,
    EXITING
}

fun ConversationState.statusText(): String = when (this) {
    ConversationState.IDLE_WAKE -> "等待唤醒…"
    ConversationState.LISTENING -> "正在听你说…"
    ConversationState.RECOGNIZING -> "正在识别…"
    ConversationState.EXECUTING -> "正在执行…"
    ConversationState.SPEAKING -> "正在回复…"
    ConversationState.READY_TO_LISTEN -> "准备继续监听…"
    ConversationState.EXITING -> "正在退出…"
}
```

- [ ] **Step 4: Verify GREEN and wake guard**

```bash
python tools/test_v064_conversation_state.py
python tools/test_v064_wake_regression.py
```

Expected: both PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lchuang/xiaozhimobile/ConversationState.kt tools/test_v064_conversation_state.py
git commit -m "feat: add conversation state model"
```

---

### Task 3: Add Intelligent Local Exit Detection

**Files:**
- Create: `app/src/main/java/com/lchuang/xiaozhimobile/ConversationExitDetector.kt`
- Create: `tools/test_v064_exit_intent.py`

**Interfaces:**
- Produces:

```kotlin
enum class ExitDecision { EXIT, CONTINUE, AMBIGUOUS }

class ConversationExitDetector {
    fun classify(raw: String): ExitDecision
}
```

- Later consumed by `WakeService`.

- [ ] **Step 1: Write the failing classifier test**

The Kotlin harness must assert every case below:

```kotlin
val d = ConversationExitDetector()

listOf(
    "退出", "退出吧", "退下", "你退下吧",
    "没什么事了", "没事了", "不用了",
    "先这样吧", "就这样吧", "结束吧", "结束对话",
    "你先休息吧", "可以休息了", "再见", "拜拜",
    "今天先到这里", "暂时没别的事"
).forEach { check(d.classify(it) == ExitDecision.EXIT) { it } }

listOf(
    "退出微信", "退出登录", "退出当前账号",
    "怎么退出这个页面", "帮我关闭高德地图", "关闭微信"
).forEach { check(d.classify(it) == ExitDecision.CONTINUE) { it } }

listOf(
    "好了今天就这样", "你可以先忙你的了"
).forEach { check(d.classify(it) == ExitDecision.AMBIGUOUS) { it } }
```

- [ ] **Step 2: Verify RED**

```bash
python tools/test_v064_exit_intent.py
```

Expected: FAIL because the detector does not exist.

- [ ] **Step 3: Implement local classification**

Use normalization that removes Chinese/ASCII punctuation and whitespace, then:

1. return `CONTINUE` first for explicit non-assistant targets:
   - strings containing `退出微信`, `退出登录`, `退出账号`, `退出当前`, `退出这个页面`, `关闭微信`, `关闭高德`, `关闭百度地图`;
   - regex `(?:退出|关闭).+(?:应用|软件|页面|账号|登录|微信|qq|地图)`.
2. return `EXIT` for exact/contained strong assistant-session phrases.
3. return `AMBIGUOUS` for soft ending language containing combinations such as `今天.*这样`, `先到这`, `没别的`, `先忙你的`.
4. otherwise return `CONTINUE`.

Do not call AI from this class.

- [ ] **Step 4: Verify GREEN**

```bash
python tools/test_v064_exit_intent.py
python tools/test_v064_wake_regression.py
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lchuang/xiaozhimobile/ConversationExitDetector.kt tools/test_v064_exit_intent.py
git commit -m "feat: add intelligent local conversation exit detector"
```

---

### Task 4: Parse Natural Chinese Media-Volume Commands

**Files:**
- Create: `app/src/main/java/com/lchuang/xiaozhimobile/VolumeCommandParser.kt`
- Create: `tools/test_v064_volume_parser.py`

**Interfaces:**
- Produces:

```kotlin
sealed class VolumeAction {
    data class SetPercent(val percent: Int) : VolumeAction()
    data object StepUp : VolumeAction()
    data object StepDown : VolumeAction()
    data object Unhandled : VolumeAction()
}

class VolumeCommandParser {
    fun parse(raw: String): VolumeAction
}
```

- Later consumed by `CommandRouter`.

- [ ] **Step 1: Write failing parser tests**

Required assertions:

```kotlin
val p = VolumeCommandParser()

check(p.parse("把手机音量调到最大") == VolumeAction.SetPercent(100))
check(p.parse("声音开满") == VolumeAction.SetPercent(100))
check(p.parse("音量调到最高") == VolumeAction.SetPercent(100))
check(p.parse("音量调到百分之七十") == VolumeAction.SetPercent(70))
check(p.parse("音量调整到70%") == VolumeAction.SetPercent(70))
check(p.parse("音量调到70") == VolumeAction.SetPercent(70))
check(p.parse("音量调到一半") == VolumeAction.SetPercent(50))
check(p.parse("静音") == VolumeAction.SetPercent(0))
check(p.parse("声音关掉") == VolumeAction.SetPercent(0))
check(p.parse("音量大一点") == VolumeAction.StepUp)
check(p.parse("音量小一点") == VolumeAction.StepDown)
check(p.parse("打开微信") == VolumeAction.Unhandled)

for (n in 0..100 step 10) {
    check((p.parse("音量调到${n}%") as VolumeAction.SetPercent).percent == n)
}
```

Also test Chinese:
`百分之零`, `百分之十`, `百分之二十五`, `百分之五十`, `百分之九十九`, `百分之一百`.

- [ ] **Step 2: Verify RED**

```bash
python tools/test_v064_volume_parser.py
```

Expected: FAIL because parser does not exist.

- [ ] **Step 3: Implement minimal parser**

Implementation rules:

- normalize `手机音量` / `媒体音量` / `声音` to one internal concept;
- check `最大|最高|开满|调满` before numeric regex;
- check `一半|半音量` → 50;
- check `静音|声音关掉|音量关掉` → 0;
- check step-up/down phrases before broad number parsing;
- parse Arabic regex:
  - `(?:百分之)?(\d{1,3})%?`
  - clamp only if `0..100`; values outside return `Unhandled`;
- parse Chinese percent after `百分之` with a dedicated `parseChinese0To100()` that handles:
  - single digits;
  - `十`, `十一`…`十九`;
  - `二十`…`九十九`;
  - `一百`.
- Do not implement general Chinese arithmetic.

- [ ] **Step 4: Verify GREEN**

```bash
python tools/test_v064_volume_parser.py
python tools/test_v064_wake_regression.py
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lchuang/xiaozhimobile/VolumeCommandParser.kt tools/test_v064_volume_parser.py
git commit -m "feat: parse natural Chinese media volume commands"
```

---

### Task 5: Make Media-Volume Execution Verifiable

**Files:**
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/PhoneController.kt`
- Create: `tools/test_v064_volume_execution.py`

**Interfaces:**
- Produces:

```kotlin
data class MediaVolumeResult(
    val requestedPercent: Int?,
    val actualPercent: Int,
    val success: Boolean
)

fun setMediaVolumePercent(percent: Int): MediaVolumeResult
fun stepMediaVolume(direction: Int): MediaVolumeResult
fun currentMediaVolumePercent(): Int
```

Where `direction` is `AudioManager.ADJUST_RAISE` or `AudioManager.ADJUST_LOWER` internally; callers should preferably use wrapper methods:

```kotlin
fun volumeUpVerified(): MediaVolumeResult
fun volumeDownVerified(): MediaVolumeResult
```

- [ ] **Step 1: Write a failing Android-source contract test**

`tools/test_v064_volume_execution.py` must inspect `PhoneController.kt` and require all of these source contracts:

```python
assert "data class MediaVolumeResult" in src
assert "AudioManager.STREAM_MUSIC" in src
assert "AudioManager.FLAG_SHOW_UI" in src
assert "getStreamMaxVolume(AudioManager.STREAM_MUSIC)" in src
assert "getStreamVolume(AudioManager.STREAM_MUSIC)" in src
assert "setStreamVolume(" in src
assert "adjustStreamVolume(" in src
assert "STREAM_RING" not in changed_volume_block
assert "STREAM_NOTIFICATION" not in changed_volume_block
assert "STREAM_ALARM" not in changed_volume_block
```

Also require the read-after-write order in `setMediaVolumePercent`: `setStreamVolume` appears before the final `getStreamVolume`.

- [ ] **Step 2: Verify RED**

```bash
python tools/test_v064_volume_execution.py
```

Expected: FAIL because structured result/read-back/`FLAG_SHOW_UI` do not exist.

- [ ] **Step 3: Implement verified volume APIs**

Add:

```kotlin
data class MediaVolumeResult(
    val requestedPercent: Int?,
    val actualPercent: Int,
    val success: Boolean
)
```

`currentMediaVolumePercent()`:

```kotlin
val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
return ((current * 100.0) / max).toInt().coerceIn(0, 100)
```

`setMediaVolumePercent(percent)`:

```kotlin
val requested = percent.coerceIn(0, 100)
val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
val targetStep = kotlin.math.round(requested * max / 100.0).toInt().coerceIn(0, max)
return try {
    audioManager.setStreamVolume(
        AudioManager.STREAM_MUSIC,
        targetStep,
        AudioManager.FLAG_SHOW_UI
    )
    val actual = currentMediaVolumePercent()
    MediaVolumeResult(requested, actual, kotlin.math.abs(actual - requested) <= kotlin.math.ceil(100.0 / max).toInt())
} catch (_: Throwable) {
    MediaVolumeResult(requested, currentMediaVolumePercent(), false)
}
```

Step-up/down use `adjustStreamVolume(..., FLAG_SHOW_UI)` then re-read actual.

Keep old public wrappers temporarily if needed by existing callers, but route them through the verified APIs so there is one mutation path.

- [ ] **Step 4: Verify GREEN and existing safe-tool behavior**

```bash
python tools/test_v064_volume_execution.py
python tools/test_v060_safe_tools.py
python tools/test_v064_wake_regression.py
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lchuang/xiaozhimobile/PhoneController.kt tools/test_v064_volume_execution.py
git commit -m "fix: verify Android media volume after changes"
```

---

### Task 6: Route Volume Commands Through the New Parser

**Files:**
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/CommandRouter.kt`
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/SafeToolExecutor.kt`
- Test: `tools/test_v064_volume_parser.py`
- Test: `tools/test_v064_volume_execution.py`
- Modify/Create focused assertions in: `tools/test_v064_command_prompt_flow.py` only for reply text if necessary.

**Interfaces:**
- Consumes:
  - `VolumeCommandParser.parse()`
  - verified `PhoneController` volume APIs.
- Produces:
  - success replies based on actual Android media volume.

- [ ] **Step 1: Add failing integration assertions**

Extend `tools/test_v064_volume_execution.py` to require `CommandRouter` contains:

```text
VolumeCommandParser
SetPercent
StepUp
StepDown
actualPercent
```

and to reject the old sole regex-only approach as the only percentage path.

Require `SafeToolExecutor` `set_volume`, `volume_up`, and `volume_down` to use verified volume results rather than fire-and-forget calls.

- [ ] **Step 2: Verify RED**

```bash
python tools/test_v064_volume_execution.py
```

Expected: FAIL on router/executor integration.

- [ ] **Step 3: Implement router integration**

At the top of `CommandRouter.handle()` after blank checking, call:

```kotlin
when (val action = volumeParser.parse(text)) {
    is VolumeAction.SetPercent -> { ... }
    VolumeAction.StepUp -> { ... }
    VolumeAction.StepDown -> { ... }
    VolumeAction.Unhandled -> Unit
}
```

Reply builders:

```kotlin
private fun volumeReply(result: PhoneController.MediaVolumeResult): Result {
    if (!result.success) {
        return Result(true, "媒体音量当前是${result.actualPercent}%，没有完全调整到目标值", false)
    }
    val spoken = when (result.actualPercent) {
        0 -> "媒体音量已经静音"
        100 -> "媒体音量已经调整到最大"
        else -> "媒体音量已经调整到${result.actualPercent}%"
    }
    return Result(true, spoken, true)
}
```

Remove/retire the old broad `Regex("音量.*?(\\d{1,3})")` block once the new parser covers it.

- [ ] **Step 4: Make AI safe tools use the same verified path**

`set_volume`:

```kotlin
val result = phone.setMediaVolumePercent(percent)
callback(
    ToolExecutionResult(
        result.success,
        when (result.actualPercent) {
            0 -> "媒体音量已经静音"
            100 -> "媒体音量已经调整到最大"
            else -> "媒体音量已经调整到${result.actualPercent}%"
        },
        if (result.success) "SET_VOLUME" else "SET_VOLUME_PARTIAL"
    )
)
```

Do the equivalent for volume up/down using actual percent.

- [ ] **Step 5: Verify GREEN**

```bash
python tools/test_v064_volume_parser.py
python tools/test_v064_volume_execution.py
python tools/test_v060_safe_tools.py
python tools/test_v031_behavior.py
python tools/test_v064_wake_regression.py
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/lchuang/xiaozhimobile/CommandRouter.kt app/src/main/java/com/lchuang/xiaozhimobile/SafeToolExecutor.kt tools/test_v064_volume_execution.py
git commit -m "fix: route voice volume through verified media controls"
```

---

### Task 7: Convert the Overlay to a Touchable Panel With × and Double-Tap Exit

**Files:**
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/AssistantOverlayView.kt`
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/AssistantOverlayController.kt`
- Create: `tools/test_v064_overlay_exit.py`

**Interfaces:**
- Produces:

```kotlin
fun AssistantOverlayController.setOnExitRequested(callback: (() -> Unit)?)
fun AssistantOverlayController.updateState(state: ConversationState)
```

`AssistantOverlayView` constructor or setter receives an exit callback.

- [ ] **Step 1: Write failing overlay source-contract tests**

Require:

```python
controller = ...read_text(...)
view = ...read_text(...)

assert "FLAG_NOT_TOUCHABLE" not in controller
assert "MATCH_PARENT" not in the overlay width/height construction block
assert "setOnExitRequested" in controller
assert "GestureDetector" in view
assert "onDoubleTap" in view
assert "onExitRequested" in view
assert 'drawText("×"' in view or '"×"' in view
```

Also require `FLAG_NOT_FOCUSABLE` and `FLAG_NOT_TOUCH_MODAL` remain.

- [ ] **Step 2: Verify RED**

```bash
python tools/test_v064_overlay_exit.py
```

Expected: FAIL because the current overlay is full-screen/non-touchable.

- [ ] **Step 3: Refactor controller window geometry**

Compute:

```kotlin
val metrics = appContext.resources.displayMetrics
val panelWidth = (metrics.widthPixels * 0.88f).toInt()
val panelHeight = dp(225f).toInt()
val topOffset = (metrics.heightPixels * 0.20f).toInt()
```

Use `WindowManager.LayoutParams(panelWidth, panelHeight, TYPE_APPLICATION_OVERLAY, flags, TRANSLUCENT)` with:

```text
FLAG_NOT_FOCUSABLE
FLAG_NOT_TOUCH_MODAL
FLAG_LAYOUT_NO_LIMITS
```

Do not include `FLAG_NOT_TOUCHABLE`.

Place using `Gravity.TOP | Gravity.CENTER_HORIZONTAL`, `y = topOffset`.

- [ ] **Step 4: Refactor view drawing into panel-local coordinates**

The view window itself is now the panel. Draw the rounded panel from a small internal margin to `width/height`, not from `height * 0.20`.

Add:
- close glyph `×` at top-right;
- close hit `RectF` at least 40dp × 40dp;
- `GestureDetector.SimpleOnGestureListener`;
- `onSingleTapConfirmed`: return true but do not exit;
- `onDoubleTap`: invoke exit exactly once;
- `onTouchEvent`: if `ACTION_UP` inside close hit rect, invoke exit; otherwise delegate to gesture detector.

Use a local debounce/atomic boolean reset when the controller creates a fresh overlay view so double events do not emit repeated exit requests.

- [ ] **Step 5: Add state-aware rendering hook**

Add:

```kotlin
private var conversationState = ConversationState.LISTENING

fun setConversationState(value: ConversationState) {
    conversationState = value
    if (value != ConversationState.LISTENING) audioLevel = 0.08f
    invalidate()
}
```

Only `LISTENING` uses microphone-reactive waveform amplitude.

- [ ] **Step 6: Verify GREEN**

```bash
python tools/test_v064_overlay_exit.py
python tools/test_overlay_source.py
python tools/test_v040_voice_flow.py
python tools/test_v064_wake_regression.py
```

If the old overlay test assumes full-screen/non-touchable behavior, update that test only where v0.6.4 intentionally changes the approved interaction. Preserve all other overlay checks.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/lchuang/xiaozhimobile/AssistantOverlayView.kt app/src/main/java/com/lchuang/xiaozhimobile/AssistantOverlayController.kt tools/test_v064_overlay_exit.py tools/test_overlay_source.py
git commit -m "feat: add manual exit controls to assistant overlay"
```

---

### Task 8: Add No-Tools AI Exit Classification

**Files:**
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/AiOrchestrator.kt`
- Extend/Create assertions in: `tools/test_v064_exit_intent.py`
- Existing regression: `tools/test_v060_ai_orchestrator.py`

**Interfaces:**
- Produces:

```kotlin
fun classifyExitIntent(
    userText: String,
    callback: (Result<ExitDecision>) -> Unit
)
```

Only returns `ExitDecision.EXIT` or `ExitDecision.CONTINUE` on success; never returns tool calls.

- [ ] **Step 1: Add failing source and harness assertions**

Require `classifyExitIntent` to:
- call `client.complete(messages, emptyList())`;
- not pass `toolDefinitions`;
- use a system message requiring one literal token: `EXIT` or `CONTINUE`;
- normalize response case/whitespace;
- reject any other output as failure.

- [ ] **Step 2: Verify RED**

```bash
python tools/test_v064_exit_intent.py
python tools/test_v060_ai_orchestrator.py
```

Expected: new v0.6.4 assertions fail.

- [ ] **Step 3: Implement the narrow classifier**

Add:

```kotlin
fun classifyExitIntent(userText: String, callback: (Result<ExitDecision>) -> Unit) {
    val clean = userText.trim()
    if (clean.isBlank()) {
        callback(Result.success(ExitDecision.CONTINUE))
        return
    }
    val identity = settings.assistantName.ifBlank { "小智" }
    val instruction = """
        你只判断用户是否明确想结束与$identity当前这一次对话。
        如果是，只回复 EXIT。
        如果不是或不确定，只回复 CONTINUE。
        “退出微信/退出登录/关闭某个应用”不是退出助手，必须回复 CONTINUE。
        禁止输出解释、JSON、Markdown或工具调用。
    """.trimIndent()
    val messages = listOf(
        ConversationMessage("system", instruction),
        ConversationMessage("user", clean)
    )
    client.complete(messages, emptyList()) { result ->
        if (result.isFailure) {
            callback(Result.failure(result.exceptionOrNull() ?: IllegalStateException("AI退出判断失败")))
            return@complete
        }
        when (result.getOrThrow().text.trim().uppercase()) {
            "EXIT" -> callback(Result.success(ExitDecision.EXIT))
            "CONTINUE" -> callback(Result.success(ExitDecision.CONTINUE))
            else -> callback(Result.failure(IllegalStateException("AI退出判断格式错误")))
        }
    }
}
```

- [ ] **Step 4: Verify GREEN**

```bash
python tools/test_v064_exit_intent.py
python tools/test_v060_ai_orchestrator.py
python tools/test_v060_security.py
python tools/test_v064_wake_regression.py
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lchuang/xiaozhimobile/AiOrchestrator.kt tools/test_v064_exit_intent.py
git commit -m "feat: add no-tools AI conversation exit classifier"
```

---

### Task 9: Convert WakeService Post-Wake Flow to Explicit States

**Files:**
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt`
- Create: `tools/test_v064_command_prompt_flow.py`
- Create: `tools/test_v064_duplicate_guard.py`
- Test: `tools/test_v064_conversation_state.py`
- Test: `tools/test_v064_wake_regression.py`

**Interfaces:**
- Consumes:
  - `ConversationState`
  - `ConversationExitDetector`
  - overlay `setOnExitRequested`
  - `AiOrchestrator.classifyExitIntent`
- Produces:
  - guarded `scheduleListeningAfterSpeech()`
  - idempotent `requestConversationExit()`
  - accurate overlay state updates.

- [ ] **Step 1: Write failing prompt/state-flow source tests**

`tools/test_v064_command_prompt_flow.py` must require:

```text
ConversationState.SPEAKING
ConversationState.READY_TO_LISTEN
ConversationState.LISTENING
setConversationState
"你有什么需求请说？"
"请继续说。"
IMMEDIATE_LISTEN_DELAY_MS
```

It must also verify the source order in the new helper:
1. set `READY_TO_LISTEN`;
2. schedule guard delay;
3. inside runnable set `LISTENING`;
4. touch session timeout;
5. start command recognition.

Require that `startLocalCommandRecognition()` does not run when state is `SPEAKING`, `EXITING`, or `IDLE_WAKE`.

- [ ] **Step 2: Write failing duplicate-command guard test**

`tools/test_v064_duplicate_guard.py` requires `WakeService` to store:
- last normalized device command;
- last timestamp;
- suppression constant between 1200 and 1800 ms;
- a helper that only suppresses when normalized text matches and delta is inside the window;
- reset on new wake session/exit.

- [ ] **Step 3: Verify RED**

```bash
python tools/test_v064_command_prompt_flow.py
python tools/test_v064_duplicate_guard.py
```

Expected: FAIL.

- [ ] **Step 4: Add state fields and a single state setter**

Add:

```kotlin
@Volatile private var conversationState = ConversationState.IDLE_WAKE
private var sessionGeneration = 0L
private var pendingListenRunnable: Runnable? = null
private var lastDeviceCommand = ""
private var lastDeviceCommandAtMs = 0L
private var successfulDeviceActions = 0
```

Add:

```kotlin
private fun setConversationState(state: ConversationState) {
    conversationState = state
    overlay.updateState(state)
}
```

Do not alter `initKeywordSpotter`, KWS config, `WakePhraseManager`, or custom wake apply logic.

- [ ] **Step 5: Make wake entry stateful**

In `handleWakeDetected()`:

1. increment `sessionGeneration`;
2. reset successful action count and duplicate guard;
3. start memory/session;
4. show overlay;
5. enter `SPEAKING`;
6. speak configured wake reply;
7. on done schedule guarded listening.

Do not show `LISTENING` until the microphone is about to start.

- [ ] **Step 6: Implement one guarded listen scheduler**

Add:

```kotlin
private fun scheduleListeningAfterSpeech(delayMs: Long = IMMEDIATE_LISTEN_DELAY_MS) {
    if (!running.get() || !conversationActive || conversationState == ConversationState.EXITING) return
    val generation = sessionGeneration
    pendingListenRunnable?.let(mainHandler::removeCallbacks)
    setConversationState(ConversationState.READY_TO_LISTEN)
    val runnable = Runnable {
        if (!running.get() || !conversationActive) return@Runnable
        if (generation != sessionGeneration) return@Runnable
        if (conversationState == ConversationState.EXITING) return@Runnable
        setConversationState(ConversationState.LISTENING)
        session.touch(settings.sessionTimeoutSeconds)
        startLocalCommandRecognition()
    }
    pendingListenRunnable = runnable
    mainHandler.postDelayed(runnable, delayMs.coerceIn(120L, 180L))
}
```

Use this helper after device-command TTS and normal AI reply TTS.

- [ ] **Step 7: Make recognition state truthful**

At actual recording start: `LISTENING`.
After audio capture ends and before decode: `RECOGNIZING`.
Before router/AI execution: `EXECUTING`.
Before every session TTS: `SPEAKING`.

`startLocalCommandRecognition()` must early-return unless:

```kotlin
conversationState == ConversationState.LISTENING
```

- [ ] **Step 8: Build first-turn and later-turn confirmation text**

Add:

```kotlin
private fun buildDeviceContinuation(result: String): String {
    val clean = result.trim().ifBlank { "指令已经执行完成" }
    return if (successfulDeviceActions == 0) {
        "$clean，你有什么需求请说？"
    } else {
        "$clean，请继续说。"
    }
}
```

Increment `successfulDeviceActions` only after a successful device action has been accepted for execution, not for failed/duplicate commands.

For normal AI answer:

```kotlin
private fun buildAiContinuation(answer: String): String {
    val clean = answer.trim()
    return if (clean.endsWith("？") || clean.endsWith("?")) clean
    else "$clean。你还需要什么？"
}
```

- [ ] **Step 9: Add duplicate-device-command suppression**

Before calling local router execution for device-like commands:

```kotlin
private fun isDuplicateDeviceCommand(normalized: String, nowMs: Long = SystemClock.elapsedRealtime()): Boolean {
    if (normalized != lastDeviceCommand) {
        lastDeviceCommand = normalized
        lastDeviceCommandAtMs = nowMs
        return false
    }
    val duplicate = nowMs - lastDeviceCommandAtMs in 0..DEVICE_DUPLICATE_WINDOW_MS
    lastDeviceCommandAtMs = nowMs
    return duplicate
}
```

Use `DEVICE_DUPLICATE_WINDOW_MS = 1500L`.

If duplicate:
- do not execute router/tool again;
- update overlay status `已忽略重复指令`;
- call `scheduleListeningAfterSpeech(120L)` without TTS.

- [ ] **Step 10: Verify GREEN**

```bash
python tools/test_v064_command_prompt_flow.py
python tools/test_v064_duplicate_guard.py
python tools/test_v064_conversation_state.py
python tools/test_v062_command_confirmation.py
python tools/test_v050_session.py
python tools/test_v064_wake_regression.py
```

Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt tools/test_v064_command_prompt_flow.py tools/test_v064_duplicate_guard.py
git commit -m "feat: make continuous conversation state driven"
```

---

### Task 10: Integrate Manual Exit and Intelligent Voice Exit Into WakeService

**Files:**
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt`
- Test: `tools/test_v064_overlay_exit.py`
- Test: `tools/test_v064_exit_intent.py`
- Test: `tools/test_v064_command_prompt_flow.py`

**Interfaces:**
- Produces:

```kotlin
private fun requestConversationExit(spokenText: String)
private fun completeConversationExit(generation: Long)
```

- [ ] **Step 1: Add failing exit-integration assertions**

Require `WakeService` to:
- call `overlay.setOnExitRequested`;
- cancel `pendingListenRunnable`;
- set `EXITING`;
- invalidate/increment `sessionGeneration`;
- stop command capture;
- clear memory/session;
- speak an exit acknowledgment;
- call existing `restartWakeListening()` without changing active wake phrase;
- use `ConversationExitDetector.classify`;
- use AI classifier only for `AMBIGUOUS`.

- [ ] **Step 2: Verify RED**

```bash
python tools/test_v064_overlay_exit.py
python tools/test_v064_exit_intent.py
```

Expected: integration assertions fail.

- [ ] **Step 3: Wire manual overlay exit in `onCreate()`**

After overlay creation:

```kotlin
overlay.setOnExitRequested {
    mainHandler.post {
        requestConversationExit("好的，有需要再叫我")
    }
}
```

- [ ] **Step 4: Implement idempotent exit**

```kotlin
private fun requestConversationExit(spokenText: String) {
    if (!running.get() || !conversationActive) return
    if (conversationState == ConversationState.EXITING) return

    setConversationState(ConversationState.EXITING)
    sessionGeneration += 1
    val generation = sessionGeneration

    pendingListenRunnable?.let(mainHandler::removeCallbacks)
    pendingListenRunnable = null
    commandListening.set(false)
    try { audioRecord?.stop() } catch (_: Throwable) {}
    releaseAudioRecord()
    memory.clear()
    session.stop()

    speakThen(spokenText) {
        completeConversationExit(generation)
    }
}
```

`completeConversationExit` must no-op if generation no longer matches, then:
- `conversationActive = false`;
- reset counters/duplicate state;
- hide overlay;
- call `restartWakeListening()`.

If TTS is unavailable, existing `speakThen` fallback completes exit.

- [ ] **Step 5: Replace the old flat exit list**

In `processUtterance()`:

```kotlin
when (exitDetector.classify(normalized)) {
    ExitDecision.EXIT -> requestConversationExit("好的，我先退下了，有需要再叫我")
    ExitDecision.CONTINUE -> processNormally()
    ExitDecision.AMBIGUOUS -> classifyWithAiOrContinue(rawText)
}
```

For `AMBIGUOUS`:
- if AI not configured: continue normal processing;
- otherwise call `aiOrchestrator.classifyExitIntent`;
- `EXIT` → request exit;
- `CONTINUE` or failure → continue normal processing.

Do not route AI exit classification through `SafeToolExecutor`.

- [ ] **Step 6: Prevent stale TTS callbacks from reopening listening**

Every TTS continuation that schedules listening must capture the current `sessionGeneration` and check it again before scheduling. Exit increments the generation first, invalidating older callbacks.

- [ ] **Step 7: Verify GREEN**

```bash
python tools/test_v064_overlay_exit.py
python tools/test_v064_exit_intent.py
python tools/test_v064_command_prompt_flow.py
python tools/test_v064_duplicate_guard.py
python tools/test_v040_voice_flow.py
python tools/test_v050_voice_flow.py
python tools/test_v064_wake_regression.py
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt tools/test_v064_overlay_exit.py tools/test_v064_exit_intent.py tools/test_v064_command_prompt_flow.py
git commit -m "feat: add safe manual and intelligent voice exit"
```

---

### Task 11: Update Release Metadata, Validation, and Documentation

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `.github/workflows/build-apk.yml`
- Modify: `README.md`
- Modify: `BUILD_NOTES.md`
- Modify: `GITHUB_BUILD_GUIDE.md`
- Modify: `PUSH_TO_GITHUB.ps1`
- Modify: `tools/validate_project.py`

**Interfaces:**
- Produces: v0.6.4 source package ready for GitHub build.

- [ ] **Step 1: Update version metadata**

`app/build.gradle.kts`:

```kotlin
versionCode = 11
versionName = "0.6.4"
```

- [ ] **Step 2: Update workflow APK name**

The rename step must produce:

```text
XiaoZhi-Mobile-v0.6.4-debug.apk
```

Artifact container name may remain `XiaoZhi-Mobile-APK`.

- [ ] **Step 3: Update upload script visible version/commit message**

Use:

```text
=== XiaoZhi Mobile v0.6.4 -> GitHub ===
```

and commit message:

```text
feat: XiaoZhi Mobile v0.6.4 conversation experience
```

Preserve the already-working forced manual proxy behavior.

- [ ] **Step 4: Update documentation with exact acceptance flow**

Document:
- custom wake is preserved/frozen;
- × and double-tap exit;
- voice exit phrases and false-exit examples;
- natural media-volume commands;
- command confirmation → prompt → real re-listen behavior;
- Android device acceptance checklist.

Do not claim cloud TTS or non-media volume support.

- [ ] **Step 5: Update project validator**

Require:
- version code/name;
- all three new production files;
- all eight v0.6.4 tests;
- workflow APK name;
- no `sk-`/known API-key literals;
- v0.6.3 wake tests still present.

- [ ] **Step 6: Run metadata/security tests**

```bash
python tools/test_push_script_encoding.py
python tools/test_manual_proxy_forced.py
python tools/test_v060_security.py
python tools/validate_project.py
python tools/test_v064_wake_regression.py
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/build.gradle.kts .github/workflows/build-apk.yml README.md BUILD_NOTES.md GITHUB_BUILD_GUIDE.md PUSH_TO_GITHUB.ps1 tools/validate_project.py
git commit -m "chore: prepare XiaoZhi Mobile v0.6.4 release"
```

---

### Task 12: Run the Full Regression Matrix Before Packaging

**Files:**
- No production changes unless a regression exposes a real defect.
- Tests: all project test scripts.

**Interfaces:**
- Produces: verified source tree ready for GitHub Actions.

- [ ] **Step 1: Run v0.6.4 tests individually**

```bash
python tools/test_v064_conversation_state.py
python tools/test_v064_overlay_exit.py
python tools/test_v064_exit_intent.py
python tools/test_v064_volume_parser.py
python tools/test_v064_volume_execution.py
python tools/test_v064_command_prompt_flow.py
python tools/test_v064_duplicate_guard.py
python tools/test_v064_wake_regression.py
```

Expected: all PASS.

- [ ] **Step 2: Run critical historical regression tests**

```bash
python tools/test_v031_behavior.py
python tools/test_v040_voice_flow.py
python tools/test_v050_app_registry.py
python tools/test_v050_session.py
python tools/test_v050_voice_flow.py
python tools/test_v060_ai_endpoint.py
python tools/test_v060_ai_memory.py
python tools/test_v060_ai_orchestrator.py
python tools/test_v060_app_launch.py
python tools/test_v060_map.py
python tools/test_v060_safe_tools.py
python tools/test_v060_security.py
python tools/test_v060_settings.py
python tools/test_v060_tts.py
python tools/test_v060_ui_source.py
python tools/test_v060_voice_integration.py
python tools/test_v060_wake_phrase.py
python tools/test_v061_kws_stop_behavior.py
python tools/test_v061_wake_startup.py
python tools/test_v062_command_confirmation.py
python tools/test_v062_wake_apply.py
python tools/test_v063_custom_wake_ppinyin.py
python tools/test_v063_wake_error_diagnostics.py
```

Expected: all PASS.

If a shell loop times out while repeatedly starting `kotlinc`, rerun the affected tests individually and record their individual pass/fail. A container command timeout is not itself a functional test failure.

- [ ] **Step 3: Run final validator**

```bash
python tools/validate_project.py
```

Expected: all PASS.

- [ ] **Step 4: Inspect git diff for frozen wake files**

```bash
git diff 08b6fdff7cca0d94f16340e6573498f26172fbd9 -- \
  app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseCompiler.kt \
  app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseManager.kt \
  app/src/main/java/com/lchuang/xiaozhimobile/Pinyin4jProvider.kt
```

Expected: no output.

Inspect only the KWS initialization block of `WakeService.kt` and confirm KWS model/config values are unchanged.

- [ ] **Step 5: Commit any test-only adjustments required by intentional v0.6.4 behavior**

Only commit if needed:

```bash
git add tools/
git commit -m "test: complete v0.6.4 regression coverage"
```

Do not weaken tests merely to make them pass.

---

### Task 13: Package FIX14 and Trigger GitHub Actions

**Files:**
- Package repository source excluding `.git`, build caches, APKs, secrets.
- Output local source ZIP: `XiaoZhi-Mobile-v0.6.4-GitHub-ready-FIX14.zip`

**Interfaces:**
- Produces: upload-ready source package.

- [ ] **Step 1: Create deterministic source ZIP**

Include:
- `app/`
- `.github/`
- `docs/`
- `tools/`
- Gradle project files
- push scripts
- README/build notes

Exclude:
- `.git/`
- `.gradle/`
- `build/`
- local APK artifacts
- `.env`
- API keys.

- [ ] **Step 2: Test ZIP integrity**

Use Python `zipfile.ZipFile(...).testzip()`.

Expected: `None`/no corrupt member.

- [ ] **Step 3: Compute SHA-256**

Record source-package SHA-256 for the user.

- [ ] **Step 4: User pushes with the known-good manual proxy script**

On Windows:

```text
PUSH_TO_GITHUB_MANUAL_PROXY.bat
proxy port: 7897
```

Expected upload log:
- forced manual proxy active;
- commit to `main`;
- `main -> main`;
- `Upload completed`.

- [ ] **Step 5: Verify GitHub Actions**

For the new commit:
- source validation success;
- offline KWS/ASR model fetch success;
- `Build debug APK` success;
- rename success;
- artifact upload success.

Do not declare v0.6.4 compiled until GitHub Actions actually reports success.

---

### Task 14: Download, Validate, and Real-Device Accept the v0.6.4 APK

**Files:**
- GitHub Actions artifact ZIP
- Final local APK: `/mnt/data/XiaoZhi-Mobile-v0.6.4-debug.apk`

**Interfaces:**
- Produces: verified installable APK and device acceptance result.

- [ ] **Step 1: Download the `XiaoZhi-Mobile-APK` artifact**

Extract the v0.6.4 debug APK.

- [ ] **Step 2: Validate APK structure**

Verify:
- ZIP integrity;
- `AndroidManifest.xml`;
- `classes.dex`;
- `lib/arm64-v8a/`;
- `libsherpa-onnx-jni.so`;
- KWS model assets;
- Paraformer ASR assets;
- launcher resources.

Compute and publish final APK SHA-256.

- [ ] **Step 3: Frozen custom-wake device regression first**

Before testing any new feature:

```text
助手名字：小白
唤醒词：小白小白
→ 保存并应用
→ 当前实际 KWS：小白小白
→ 回桌面
→ 说“小白小白”
→ 必须成功唤醒
```

If this fails, stop v0.6.4 acceptance and treat it as a release-blocking regression.

- [ ] **Step 4: Test command completion and real re-listening**

```text
小白小白
→ 打开微信
→ 微信实际打开
→ “微信已经打开，你有什么需求请说？”
→ TTS 完成
→ overlay 显示“正在听你说…”
→ 不重新唤醒，直接说“打开高德地图”
→ 高德实际打开
→ “高德地图已经打开，请继续说。”
```

Pass only if second command works without repeating the wake phrase.

- [ ] **Step 5: Test manual overlay exit**

1. wake;
2. tap `×`;
3. overlay closes;
4. wake again with current custom phrase;
5. double-tap panel;
6. overlay closes;
7. verify tapping outside panel still interacts with underlying phone UI.

- [ ] **Step 6: Test voice exit**

Must exit:

```text
退出
退下
没什么事了
先这样吧
今天先到这里
```

Must stay active:

```text
退出微信
退出登录
怎么退出这个页面
```

- [ ] **Step 7: Test verified media volume**

With Android system media-volume UI visible:

```text
把手机音量调到最大
音量调到百分之七十
音量调到一半
静音
音量大一点
音量小一点
```

Pass criteria:
- only media volume changes;
- visible Android value changes;
- XiaoZhi's spoken percentage/result matches the actual re-read state closely;
- ringtone/alarm remain untouched.

- [ ] **Step 8: Test self-listening and duplicate protection**

After XiaoZhi says its completion prompt:
- it must not recognize its own prompt as the next user command;
- one spoken `打开微信` must not trigger two launches;
- intentionally repeating the same command after >1.5 seconds must be allowed.

- [ ] **Step 9: Release decision**

Release v0.6.4 only if:
- frozen custom wake passes;
- manual and voice exits pass;
- natural volume commands pass;
- continuous second command works without re-wake;
- no self-listening loop;
- historical core device controls still work.

If a test fails, capture:
- exact spoken phrase;
- overlay state text;
- notification text;
- whether the actual Android action happened;
- relevant App diagnostics;
then return to the specific failed task rather than applying unrelated fixes.

---

## Plan Self-Review

### Spec coverage

- Frozen v0.6.3 custom wake: Tasks 1, 12, 14.
- Explicit conversation states: Tasks 2, 9.
- Command completion + next prompt + guarded re-listen: Task 9.
- Manual × / double-tap / outside touch-through: Task 7, integrated Task 10.
- Intelligent local exit + false-exit protection: Task 3.
- AI semantic exit fallback without tools: Task 8, integrated Task 10.
- Natural Chinese media volume: Tasks 4, 5, 6.
- Actual volume read-back / media-only / `FLAG_SHOW_UI`: Task 5.
- Duplicate command protection: Task 9.
- Session timeout refreshed on real listening: Task 9.
- Truthful overlay state/animation: Tasks 2, 7, 9.
- Release metadata/docs/security: Task 11.
- Full regression/CI/APK/device acceptance: Tasks 12–14.

### Completeness scan

The plan contains no unresolved implementation markers or unspecified test steps.

### Type/interface consistency

- `ConversationState` is defined in Task 2 before overlay/service use.
- `ExitDecision`/`ConversationExitDetector` are defined in Task 3 before AI/service use.
- `VolumeAction`/`VolumeCommandParser` are defined in Task 4 before router use.
- `MediaVolumeResult` and verified media APIs are defined in Task 5 before router/safe-tool integration.
- overlay callback is defined in Task 7 before service integration.
- AI classifier is defined in Task 8 before service integration.
- `WakeService` integration occurs only after all consumed interfaces exist.
