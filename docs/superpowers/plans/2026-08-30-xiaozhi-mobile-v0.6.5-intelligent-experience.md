# XiaoZhi Mobile v0.6.5 Intelligent Experience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 v0.6.4 已验证基线上实现 v0.6.5：通用“退出 App→返回桌面”、真实监听状态、自适应抗噪命令录音、执行前提示与约 120ms 并行设备操作、成功/失败常驻通知结果，以及不破坏现有自定义唤醒和安全工具边界的发布闸门。

**Architecture:** 保持 KWS、自定义唤醒、Paraformer 基础模型与现有安全白名单不变；新增纯 Kotlin 的结构化 `DeviceAction`/事务/格式化/VAD 模块和少量 Android 适配器，把“解析→提示→执行→真实结果→通知→最终 TTS→重新监听”从 `WakeService` 中抽成明确边界。`WakeService` 继续负责会话和硬件生命周期，但只在 `AudioRecord.startRecording()` 成功后进入 `LISTENING`；本地高确定度命令优先执行，AI Tool 先解析成相同的安全 `DeviceAction` 再进入统一执行链。

**Tech Stack:** Android/Kotlin, minSdk 26, target/compileSdk 35, Java 17, sherpa-onnx v1.13.4, offline Paraformer ASR, Android `AudioRecord`, `NoiseSuppressor`, `TextToSpeech`, Python source-contract tests + small `kotlinc` harness tests, GitHub Actions, Gradle 8.9.

**Spec:** `docs/superpowers/specs/2026-08-30-xiaozhi-mobile-v0.6.5-intelligent-experience-design.md`

## Global Constraints

- 基线必须保持为 commit `671a1308826f093349fea7129dde711eeec7dd4d` 的增量演进；基线 APK SHA-256 为 `f112e145905c071725116ccd6d43926babec4c91999e70782e0fe0572f5b86ef`。
- 一级冻结未经用户新授权不得修改：`WakePhraseCompiler.kt`、`WakePhraseManager.kt`、`Pinyin4jProvider.kt`、`WakeService.initKeywordSpotter()`、KWS 模型目录、`keywordsScore = 1.5f`、`keywordsThreshold = 0.20f`、`numTrailingBlanks = 1`、自定义中文 ppinyin 行为和自定义唤醒应用流程。
- 不通过修改 KWS 参数解决命令录音/ASR问题；命令识别优化只发生在唤醒后的命令录音链。
- 继续使用 16kHz 单声道离线 Paraformer，不新增云端 ASR，不更换基础模型。
- “退出/关闭/离开 + App 名称”语义为 Home Intent 返回桌面，不强杀进程；不使用 root、Accessibility、shell、隐藏系统 API。
- 单独“退出/退下/没什么事了/再见”等仍退出小智会话；“退出微信”等不得退出小智会话。
- 执行型指令才播报“某指令正在执行”；普通 AI 问答不播报。
- 设备动作目标启动窗口为 TTS `onStart` 后 100–150ms，默认 `120ms`；TTS 与设备动作可并行，TTS 与命令 ASR 监听绝不并行。
- 只有 `AudioRecord` 初始化成功、`startRecording()` 成功且进入有效采集后才允许 UI 进入 `ConversationState.LISTENING`。
- 命令 VAD 默认目标尾部静音 `650ms`，pre-roll 初始目标 `400ms`；真机反馈可在命令 ASR 层微调，但不得触碰冻结 KWS。
- 通知只复用现有 `NOTIFY_ID`，执行结果保留约 `4000ms`；不会为每条命令堆积新通知。
- `SafeToolExecutor` 白名单不得扩大到支付、转账、删除、自动发送消息、安装卸载、shell、任意 Intent/URI 等高风险操作。
- 所有新增生产行为必须先有失败测试（RED），再写最小实现（GREEN）；一级冻结 guard 必须在每个涉及 `WakeService` 的任务前后运行。

---

## File Structure / Responsibility Map

**Create**

- `app/src/main/java/com/lchuang/xiaozhimobile/DeviceAction.kt` — 纯数据模型：设备动作、解析结果、执行结果、错误类别。
- `app/src/main/java/com/lchuang/xiaozhimobile/AppExitController.kt` — 唯一职责：使用 Home Intent 返回桌面并返回结构化结果。
- `app/src/main/java/com/lchuang/xiaozhimobile/DeviceActionExecutor.kt` — 将 `DeviceAction` 映射到现有 `PhoneController`/`AppExitController`，输出真实 `DeviceExecutionResult`。
- `app/src/main/java/com/lchuang/xiaozhimobile/CommandTransaction.kt` — 一次设备指令从 ASR 文本到最终结果的事务数据。
- `app/src/main/java/com/lchuang/xiaozhimobile/ExecutionIntentFormatter.kt` — 统一执行前提示、通知摘要、成功/失败最终口播。
- `app/src/main/java/com/lchuang/xiaozhimobile/CommandResultNotifier.kt` — 最近执行结果的 4 秒保留策略；通过注入的 `publish(String)` 继续使用现有前台通知。
- `app/src/main/java/com/lchuang/xiaozhimobile/ExecutionFeedbackCoordinator.kt` — 执行型指令时序：TTS onStart → 120ms → execute → result notification → final TTS → finished callback。
- `app/src/main/java/com/lchuang/xiaozhimobile/AdaptiveVoiceActivityDetector.kt` — 纯 Kotlin 自适应底噪、动态阈值、稳定人声、650ms结束判定。
- `app/src/main/java/com/lchuang/xiaozhimobile/AudioEnhancementManager.kt` — `NoiseSuppressor` 可用时启用，不可用/失败时安全降级并释放。

**Modify**

- `app/src/main/java/com/lchuang/xiaozhimobile/CommandRouter.kt` — 新增无副作用 `plan(raw)`；保留 `handle(raw)` 兼容历史行为，二者共享同一解析逻辑。
- `app/src/main/java/com/lchuang/xiaozhimobile/PhoneController.kt` — 不改变现有媒体/地图/App能力；只为统一 executor 暴露现有动作结果所需接口，不添加强杀能力。
- `app/src/main/java/com/lchuang/xiaozhimobile/SafeToolExecutor.kt` — 新增 `plan(call)`，只把现有 allowlist 工具解析为 `DeviceAction`；保留 `execute(call, callback)` 兼容接口。
- `app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt` — 仅修改命令录音/会话编排、TTS progress、统一执行链和通知保留；禁止修改一级冻结区。
- `app/src/main/java/com/lchuang/xiaozhimobile/ConversationState.kt` — 保持现有可见状态文案；不必新增可见状态，内部执行阶段由 transaction/coordinator 表示。
- `app/build.gradle.kts` — `versionCode = 12`, `versionName = "0.6.5"`。
- `.github/workflows/build-apk.yml` — 在模型下载和 Android 编译前增加冻结/历史/新功能/安全测试闸门，APK 改名为 `XiaoZhi-Mobile-v0.6.5-debug.apk`。
- `tools/validate_project.py` — 更新版本断言并登记 v0.6.5 模块/测试/安全约束。

**Create tests**

- `tools/test_v065_frozen_baseline.py`
- `tools/test_v065_device_command_plan.py`
- `tools/test_v065_home_exit.py`
- `tools/test_v065_safe_tool_planning.py`
- `tools/test_v065_execution_copy.py`
- `tools/test_v065_execution_feedback.py`
- `tools/test_v065_listening_truth.py`
- `tools/test_v065_adaptive_vad.py`
- `tools/test_v065_noise_suppressor.py`
- `tools/test_v065_error_recovery.py`
- `tools/test_v065_release_gate.py`

---

### Task 1: Freeze the v0.6.4 golden baseline and open the v0.6.5 release line

**Files:**
- Create: `tools/test_v065_frozen_baseline.py`
- Modify: `app/build.gradle.kts:10-18`
- Test: `tools/test_v064_wake_regression.py`
- Test: `tools/test_v065_frozen_baseline.py`

**Interfaces:**
- Consumes: existing v0.6.4 frozen hashes and `test_v064_wake_regression.py`.
- Produces: a v0.6.5 guard that fails immediately if any level-1 frozen wake implementation changes; app metadata `versionCode=12`, `versionName="0.6.5"`.

- [ ] **Step 1: Write the failing v0.6.5 baseline test**

Create `tools/test_v065_frozen_baseline.py` with exact checks:

```python
from pathlib import Path
import hashlib, re, subprocess

root = Path(__file__).resolve().parents[1]
expected = {
    "app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseCompiler.kt": "6376a9ade23c87856aad3bdfc869f05936faa4ddd3aaae4612101cccebe895cc",
    "app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseManager.kt": "ced9c7276cd98e72d488b4f228d8bf4cfe77a08c184f06ef112425f701a5a608",
    "app/src/main/java/com/lchuang/xiaozhimobile/Pinyin4jProvider.kt": "1fead428ba6b77be1ccbbd0882e9694fb9fe1aee8ac53e2707cb3872edb57f6f",
}
for rel, sha in expected.items():
    assert hashlib.sha256((root / rel).read_bytes()).hexdigest() == sha, rel

wake = (root / "app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt").read_text("utf-8")
block = re.search(r"private fun initKeywordSpotter\(\)\s*\{.*?\n    \}\n\n    private fun initOfflineAsr", wake, re.S)
assert block
assert hashlib.sha256(block.group(0).encode()).hexdigest() == "77071fcc4a9d9c9627e8a30ddb45d0ad831ece80483152d8b71ce8b4c128abcd"
for token in [
    'KWS_MODEL_DIR = "sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20"',
    'keywordsScore = 1.5f', 'keywordsThreshold = 0.20f', 'numTrailingBlanks = 1'
]:
    assert token in wake, token

build = (root / "app/build.gradle.kts").read_text("utf-8")
assert 'versionCode = 12' in build
assert 'versionName = "0.6.5"' in build
subprocess.run(["python", "tools/test_v064_wake_regression.py"], cwd=root, check=True)
print("PASS: v0.6.5 frozen baseline and version")
```

- [ ] **Step 2: Run RED**

Run:

```bash
python tools/test_v065_frozen_baseline.py
```

Expected: FAIL only because build metadata is still `versionCode = 11` / `versionName = "0.6.4"`; all wake hashes must already match.

- [ ] **Step 3: Bump only app metadata**

Change `app/build.gradle.kts`:

```kotlin
versionCode = 12
versionName = "0.6.5"
```

Do not touch KWS files or the `initKeywordSpotter()` block.

- [ ] **Step 4: Run GREEN + frozen regression**

```bash
python tools/test_v065_frozen_baseline.py
python tools/test_v064_wake_regression.py
```

Expected: PASS/PASS.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts tools/test_v065_frozen_baseline.py
git commit -m "test: freeze v0.6.4 baseline for v0.6.5"
```

---

### Task 2: Introduce structured device actions and a side-effect-free local command plan

**Files:**
- Create: `app/src/main/java/com/lchuang/xiaozhimobile/DeviceAction.kt`
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/CommandRouter.kt`
- Test: `tools/test_v065_device_command_plan.py`
- Regression: `tools/test_v031_behavior.py`, `tools/test_v064_exit_intent.py`, `tools/test_v064_volume_parser.py`, `tools/test_v064_volume_execution.py`

**Interfaces:**
- Produces:

```kotlin
sealed interface DeviceAction {
    data class OpenApp(val name: String) : DeviceAction
    data class GoHome(val sourceApp: String?) : DeviceAction
    data class OpenMap(val preference: MapAppPreference) : DeviceAction
    data class SearchNearby(val keyword: String, val preference: MapAppPreference) : DeviceAction
    data class Navigate(val destination: String, val preference: MapAppPreference) : DeviceAction
    data class OpenWeb(val target: String) : DeviceAction
    data object MediaPlay : DeviceAction
    data object MediaPause : DeviceAction
    data object MediaStop : DeviceAction
    data object MediaNext : DeviceAction
    data object MediaPrevious : DeviceAction
    data class SetMediaVolume(val percent: Int) : DeviceAction
    data object MediaVolumeUp : DeviceAction
    data object MediaVolumeDown : DeviceAction
    data class SetFlashlight(val enabled: Boolean) : DeviceAction
}

sealed interface DeviceCommandPlan {
    data class Planned(val action: DeviceAction, val normalized: String) : DeviceCommandPlan
    data object Unhandled : DeviceCommandPlan
}

enum class CommandFailureKind {
    NO_SPEECH, ASR_EMPTY, UNSUPPORTED_COMMAND, APP_NOT_FOUND,
    EXECUTION_FAILED, AI_UNAVAILABLE, SAFETY_REJECTED
}

data class DeviceExecutionResult(
    val success: Boolean,
    val code: String,
    val spokenResult: String,
    val notificationSummary: String,
    val failureKind: CommandFailureKind? = null
)
```

- Extends `CommandRouter` with `fun plan(raw: String): DeviceCommandPlan`.
- Keeps `fun handle(raw: String): CommandRouter.Result` working for historical tests/compatibility; `handle` must derive from `plan` rather than maintain a second parser.

- [ ] **Step 1: Write RED planner tests**

`tools/test_v065_device_command_plan.py` compiles `DeviceAction.kt`, `VolumeCommandParser.kt`, `VoiceCommandNormalizer.kt`, `CommandRouter.kt` with a minimal stub `PhoneController`/map types or, if Android coupling prevents a clean compile, asserts the source contract and separately compiles a pure helper extracted by the implementation. Required behavior cases:

```text
退出微信        -> GoHome("微信")
关闭微信        -> GoHome("微信")
离开微信        -> GoHome("微信")
把微信退了      -> GoHome("微信")
退一下微信      -> GoHome("微信")
微信先关掉      -> GoHome("微信")
回到桌面        -> GoHome(null)
回桌面          -> GoHome(null)
打开微信        -> OpenApp("微信")
音量70          -> SetMediaVolume(70)
音量大一点      -> MediaVolumeUp
打开手电筒      -> SetFlashlight(true)
导航到广州南站  -> Navigate(...)
退出            -> Unhandled   # 交给 ConversationExitDetector
```

Also assert app-exit patterns are checked before generic `关闭...`/unsupported paths.

- [ ] **Step 2: Run RED**

```bash
python tools/test_v065_device_command_plan.py
```

Expected: FAIL because `DeviceAction.kt` and `CommandRouter.plan()` do not exist.

- [ ] **Step 3: Add models and refactor parsing once**

Implement `DeviceAction.kt` exactly as the interface block above. In `CommandRouter`, make `plan(raw)` normalize once and return structured actions. Put app-exit parsing before app-open parsing. Use these concrete regexes as the initial supported grammar:

```kotlin
private val goHomeOnly = Regex("^(?:返回桌面|回到桌面|回桌面)$")
private val appExitPrefix = Regex("^(?:退出|关闭|离开)(.+?)(?:app|应用|软件)?$")
private val appExitNatural = Regex("^(?:把)?(.+?)(?:退了|退掉|退一下|先关掉|先关闭)$")
```

Reject generic targets such as `登录`, `当前账号`, `这个页面`, `页面` from `GoHome`; these remain non-device-exit semantics. Keep single-word `退出` unplanned so `ConversationExitDetector` remains authoritative for assistant exit.

Refactor existing volume/media/flashlight/app/map/browser patterns to return `DeviceAction` from `plan()`.

Implement `handle(raw)` as a compatibility wrapper that switches on `plan(raw)` and performs the same existing side effects/results. This wrapper is temporary compatibility, not used by the new v0.6.5 `WakeService` execution path.

- [ ] **Step 4: Run GREEN + legacy behavior**

```bash
python tools/test_v065_device_command_plan.py
python tools/test_v031_behavior.py
python tools/test_v064_exit_intent.py
python tools/test_v064_volume_parser.py
python tools/test_v064_volume_execution.py
python tools/test_v065_frozen_baseline.py
```

Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lchuang/xiaozhimobile/DeviceAction.kt \
        app/src/main/java/com/lchuang/xiaozhimobile/CommandRouter.kt \
        tools/test_v065_device_command_plan.py
git commit -m "feat: plan structured local device actions"
```

---

### Task 3: Add safe Home navigation and a unified device action executor

**Files:**
- Create: `app/src/main/java/com/lchuang/xiaozhimobile/AppExitController.kt`
- Create: `app/src/main/java/com/lchuang/xiaozhimobile/DeviceActionExecutor.kt`
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/PhoneController.kt` only if a small existing-result helper is needed; do not add app-kill APIs.
- Test: `tools/test_v065_home_exit.py`

**Interfaces:**
- Produces:

```kotlin
class AppExitController(private val context: Context) {
    data class HomeResult(val success: Boolean, val code: String)
    fun goHome(): HomeResult
}

class DeviceActionExecutor(
    private val phone: PhoneController,
    private val appExitController: AppExitController
) {
    fun execute(action: DeviceAction, callback: (DeviceExecutionResult) -> Unit)
}
```

- `goHome()` uses exactly:

```kotlin
Intent(Intent.ACTION_MAIN).apply {
    addCategory(Intent.CATEGORY_HOME)
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
```

- [ ] **Step 1: Write RED Home/executor source + pure mapping tests**

The test must assert:

- Home Intent uses `ACTION_MAIN`, `CATEGORY_HOME`, `FLAG_ACTIVITY_NEW_TASK`.
- No `forceStopPackage`, `killBackgroundProcesses`, root, shell, Accessibility.
- `GoHome("微信")` success maps to `spokenResult="微信已退出"`, `notificationSummary="退出微信"`, code `GO_HOME_OK`.
- `GoHome(null)` success maps to `spokenResult="已返回桌面"`, `notificationSummary="返回桌面"`.
- Failure maps `failureKind=EXECUTION_FAILED` and does not claim success.
- Open app failure distinguishes `PACKAGE_NOT_INSTALLED/PACKAGE_NOT_VISIBLE` as `APP_NOT_FOUND`; launch failures as `EXECUTION_FAILED`.
- Media volume final text uses `actualPercent`, not requested value.

- [ ] **Step 2: Run RED**

```bash
python tools/test_v065_home_exit.py
```

Expected: FAIL because both new classes are absent.

- [ ] **Step 3: Implement Home and executor**

`AppExitController.goHome()` catches `Throwable` and returns `HomeResult(false, "GO_HOME_FAILED")` rather than throwing.

`DeviceActionExecutor.execute()` maps every `DeviceAction` to the already-existing `PhoneController` methods. `SearchNearby` uses its existing callback and calls the executor callback only when `MapActionResult` arrives. For media keys, preserve current semantics. For volume use `MediaVolumeResult.actualPercent` to build result. Do not change the SafeTool allowlist here.

- [ ] **Step 4: Run GREEN + existing controller regressions**

```bash
python tools/test_v065_home_exit.py
python tools/test_v060_app_launch.py
python tools/test_v060_map.py
python tools/test_v064_volume_execution.py
python tools/test_v065_frozen_baseline.py
```

Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lchuang/xiaozhimobile/AppExitController.kt \
        app/src/main/java/com/lchuang/xiaozhimobile/DeviceActionExecutor.kt \
        app/src/main/java/com/lchuang/xiaozhimobile/PhoneController.kt \
        tools/test_v065_home_exit.py
git commit -m "feat: add safe home navigation executor"
```

---

### Task 4: Convert existing AI safe tools into the same DeviceAction plan without expanding authority

**Files:**
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/SafeToolExecutor.kt`
- Test: `tools/test_v065_safe_tool_planning.py`
- Regression: `tools/test_v060_safe_tools.py`, `tools/test_v060_security.py`, `tools/test_v060_ai_orchestrator.py`

**Interfaces:**
- Produces:

```kotlin
sealed interface SafeToolPlan {
    data class Allowed(val action: DeviceAction) : SafeToolPlan
    data class Rejected(val result: ToolExecutionResult) : SafeToolPlan
}

fun plan(call: AiToolCall): SafeToolPlan
```

- Existing `fun execute(call: AiToolCall, callback: (ToolExecutionResult) -> Unit)` remains available for compatibility.
- No `go_home` AI tool is added in v0.6.5; app-exit/Home is local high-confidence parsing only. This prevents an AI planner from acquiring new authority merely because local voice grammar grew.

- [ ] **Step 1: Write RED planning tests**

Test mappings:

```text
open_app(name=微信)           -> Allowed(OpenApp("微信"))
navigate(destination=广州南站)-> Allowed(Navigate(...))
set_volume(percent=70)       -> Allowed(SetMediaVolume(70))
flashlight_on                -> Allowed(SetFlashlight(true))
open_web(javascript:...)     -> Rejected(REJECTED_SCHEME)
unknown_tool                 -> Rejected(REJECTED_NOT_ALLOWED)
set_volume(percent=101)      -> Rejected(INVALID_ARGS_set_volume)
```

Assert exact existing allowlist remains:
`open_app,navigate,search_nearby,open_web,media_play,media_pause,media_next,media_previous,volume_up,volume_down,set_volume,flashlight_on,flashlight_off`.

- [ ] **Step 2: Run RED**

```bash
python tools/test_v065_safe_tool_planning.py
```

Expected: FAIL because `SafeToolPlan`/`plan()` are absent.

- [ ] **Step 3: Implement plan-first SafeToolExecutor**

Extract current argument and scheme validation into `plan(call)`. Make the existing `execute()` switch over the planned action using current `PhoneController` methods so legacy behavior still passes. Do not change `AiOrchestrator.allowedTools` or tool definitions.

- [ ] **Step 4: Run GREEN + safety suite**

```bash
python tools/test_v065_safe_tool_planning.py
python tools/test_v060_safe_tools.py
python tools/test_v060_security.py
python tools/test_v060_ai_orchestrator.py
python tools/test_v065_frozen_baseline.py
```

Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lchuang/xiaozhimobile/SafeToolExecutor.kt \
        tools/test_v065_safe_tool_planning.py
git commit -m "refactor: plan safe AI tools before execution"
```

---

### Task 5: Define command transactions and consistent human-facing copy

**Files:**
- Create: `app/src/main/java/com/lchuang/xiaozhimobile/CommandTransaction.kt`
- Create: `app/src/main/java/com/lchuang/xiaozhimobile/ExecutionIntentFormatter.kt`
- Test: `tools/test_v065_execution_copy.py`

**Interfaces:**
- Produces:

```kotlin
data class CommandTransaction(
    val rawText: String,
    val normalizedText: String,
    val action: DeviceAction,
    val announcement: String,
    val startedAtMs: Long? = null,
    val result: DeviceExecutionResult? = null
)

data class ExecutionCopy(
    val announcement: String,
    val runningNotification: String,
    val successNotification: String?,
    val failureNotification: String?,
    val finalSpoken: String?
)

class ExecutionIntentFormatter {
    fun announcement(action: DeviceAction): String
    fun runningNotification(action: DeviceAction): String
    fun finalCopy(action: DeviceAction, result: DeviceExecutionResult, continuation: String): ExecutionCopy
}
```

- `continuation` is supplied by `WakeService` as either `你有什么需求请说？` for the first successful device action in the session or `请继续说。` afterward. Failures use a specific failure phrase plus `请再试一次。` and do not increment the successful action counter.

- [ ] **Step 1: Write RED pure-Kotlin copy tests**

Required exact examples:

```text
OpenApp("微信") announcement      = "打开微信正在执行"
GoHome("微信") announcement      = "退出微信正在执行"
GoHome(null) announcement        = "返回桌面正在执行"
SetMediaVolume(70) announcement  = "调整媒体音量到百分之七十正在执行"
Navigate("泉水村", ...)          = "导航到泉水村正在执行"
SearchNearby("加油站", ...)      = "搜索附近加油站正在执行"
```

Success notification format is `✅ 已成功执行：<notificationSummary>`; failure is `❌ 执行失败：<action label>`. A volume result with actual 69 must say 69, never 70, in final result/notification summary.

- [ ] **Step 2: Run RED**

```bash
python tools/test_v065_execution_copy.py
```

Expected: FAIL because formatter/transaction do not exist.

- [ ] **Step 3: Implement transaction + formatter**

Use a private Chinese number helper only for 0–100 announcement percentages, covering `0=零`, `10=十`, `50=五十`, `70=七十`, `100=一百`; final volume result remains numeric `%` because it reflects Android actual values.

- [ ] **Step 4: Run GREEN**

```bash
python tools/test_v065_execution_copy.py
python tools/test_v064_volume_parser.py
python tools/test_v065_frozen_baseline.py
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lchuang/xiaozhimobile/CommandTransaction.kt \
        app/src/main/java/com/lchuang/xiaozhimobile/ExecutionIntentFormatter.kt \
        tools/test_v065_execution_copy.py
git commit -m "feat: add device command transaction copy"
```

---

### Task 6: Add retained command-result notification state

**Files:**
- Create: `app/src/main/java/com/lchuang/xiaozhimobile/CommandResultNotifier.kt`
- Test: `tools/test_v065_execution_copy.py` (extend) or `tools/test_v065_execution_feedback.py` (notification section)

**Interfaces:**
- Produces:

```kotlin
class CommandResultNotifier(
    private val publish: (String) -> Unit,
    private val clockMs: () -> Long,
    private val holdMs: Long = 4000L
) {
    fun running(text: String)
    fun success(text: String)
    fun failure(text: String)
    fun publishTransient(text: String)
    fun clearRetention()
    fun retainedText(nowMs: Long = clockMs()): String?
}
```

Rules:
- `running()` publishes immediately but does not start result retention.
- `success()`/`failure()` publish and retain their exact text until `clock + 4000`.
- During retention, `publishTransient("连续会话中…")` republishes/keeps retained result instead of overwriting it.
- `clearRetention()` is called before KWS idle notification so `全离线语音已开启…` always wins at session exit.

- [ ] **Step 1: Write RED deterministic-clock test**

Use a mutable fake clock and assert `✅` text remains at `t+3999`, expires at `t+4000`, and `clearRetention()` immediately removes it.

- [ ] **Step 2: Run RED**

```bash
python tools/test_v065_execution_feedback.py
```

Expected: FAIL on missing notifier.

- [ ] **Step 3: Implement notifier as pure Kotlin state**

Do not import Android notification APIs here. `WakeService` remains the only owner of notification construction/`NOTIFY_ID`; this module only governs text and retention through `publish`.

- [ ] **Step 4: Run GREEN**

```bash
python tools/test_v065_execution_feedback.py
python tools/test_v065_frozen_baseline.py
```

Expected: notifier section PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lchuang/xiaozhimobile/CommandResultNotifier.kt \
        tools/test_v065_execution_feedback.py
git commit -m "feat: retain latest command notification result"
```

---

### Task 7: Orchestrate announcement → 120ms action → real result → final speech

**Files:**
- Create: `app/src/main/java/com/lchuang/xiaozhimobile/ExecutionFeedbackCoordinator.kt`
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/CommandTransaction.kt` if a `copy(...)`-friendly result helper is required.
- Test: `tools/test_v065_execution_feedback.py`

**Interfaces:**
- Produces:

```kotlin
fun interface DelayedScheduler {
    fun postDelayed(delayMs: Long, block: () -> Unit)
}

fun interface DeviceActionRunner {
    fun run(action: DeviceAction, callback: (DeviceExecutionResult) -> Unit)
}

fun interface SpeechDriver {
    fun speak(text: String, onStart: () -> Unit, onDone: () -> Unit)
}

class ExecutionFeedbackCoordinator(
    private val scheduler: DelayedScheduler,
    private val runner: DeviceActionRunner,
    private val speech: SpeechDriver,
    private val formatter: ExecutionIntentFormatter,
    private val notifier: CommandResultNotifier,
    private val actionDelayMs: Long = 120L
) {
    fun execute(
        transaction: CommandTransaction,
        continuation: String,
        onFinished: (CommandTransaction) -> Unit
    )
}
```

Behavior:
1. `notifier.running(formatter.runningNotification(action))`.
2. `speech.speak(announcement, onStart=...)`.
3. In `onStart`, schedule exactly `actionDelayMs=120` then call `runner.run`.
4. If speech engine cannot produce onStart, `SpeechDriver` implementation in `WakeService` must invoke `onStart` immediately before its fallback timer; coordinator itself stays deterministic.
5. When runner returns, publish success/failure result immediately.
6. Do not call `onFinished` until final result TTS `onDone`.
7. Device action may complete while announcement TTS is still playing; final result TTS uses `QUEUE_FLUSH` only after result is available, but must not start before the announcement has emitted `onStart`. The test records event order rather than wall-clock sleeps.

- [ ] **Step 1: Write RED event-order harness**

Test event list must prove:

```text
RUNNING_NOTIFICATION
ANNOUNCEMENT_SPEAK_CALLED
ANNOUNCEMENT_ON_START
SCHEDULE_120
DEVICE_EXECUTE
RESULT_NOTIFICATION
FINAL_SPEAK
FINAL_ON_DONE
FINISHED
```

And assert `DEVICE_EXECUTE` is not scheduled before `ANNOUNCEMENT_ON_START`.

- [ ] **Step 2: Run RED**

```bash
python tools/test_v065_execution_feedback.py
```

Expected: FAIL because coordinator is absent.

- [ ] **Step 3: Implement coordinator**

Use no Android dependencies so the complete timing contract can be tested with `kotlinc` and fake scheduler/speech/runner.

- [ ] **Step 4: Run GREEN**

```bash
python tools/test_v065_execution_feedback.py
python tools/test_v065_execution_copy.py
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lchuang/xiaozhimobile/ExecutionFeedbackCoordinator.kt \
        app/src/main/java/com/lchuang/xiaozhimobile/CommandTransaction.kt \
        tools/test_v065_execution_feedback.py
git commit -m "feat: coordinate spoken device execution feedback"
```

---

### Task 8: Make LISTENING mean the microphone is truly recording

**Files:**
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt:345-474`
- Test: `tools/test_v065_listening_truth.py`
- Regression: `tools/test_v064_command_prompt_flow.py` must be updated to the new truthful contract, not deleted.

**Interfaces:**
- `scheduleListeningAfterSpeech()` leaves UI/state at `READY_TO_LISTEN` and calls `startLocalCommandRecognition()` after the existing 120–180ms relisten guard.
- `startLocalCommandRecognition()` accepts `READY_TO_LISTEN` (not `LISTENING`) as the precondition.
- `captureCommandAudio` is changed to report “recording has started” through an injected callback:

```kotlin
private fun captureCommandAudio(onRecordingStarted: () -> Unit): FloatArray
```

- `onRecordingStarted` executes only after:

```kotlin
record.startRecording()
check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
```

Then main-thread code calls `setConversationState(ConversationState.LISTENING)` and `session.touch(...)`.

- [ ] **Step 1: Write RED truthful-listening test**

The test parses `WakeService.kt` and asserts:

- `scheduleListeningAfterSpeech()` contains `READY_TO_LISTEN` but no `setConversationState(LISTENING)` before `startLocalCommandRecognition()`.
- `record.startRecording()` appears before `onRecordingStarted()`.
- `RECORDSTATE_RECORDING` is checked.
- `onRecordingStarted` is where `LISTENING` is posted.
- after `captureCommandAudio` returns non-empty, `RECOGNIZING` is set before `decodeLocalCommand`.
- `speakThen`/new speech progress code cannot call the command ASR start until TTS completion for ordinary continuation.

- [ ] **Step 2: Run RED**

```bash
python tools/test_v065_listening_truth.py
```

Expected: FAIL because current scheduler sets LISTENING before recording starts.

- [ ] **Step 3: Implement the minimal state-order change**

Do not touch `initKeywordSpotter()` or custom wake application. Keep `commandListening` as the mutual-exclusion/control atomic used by existing KWS/command capture coordination; only change when the visible `ConversationState.LISTENING` is emitted.

In failure paths (`AUDIO_INIT`, start failure), remain out of LISTENING and call the existing recovery path with a concrete audio error.

- [ ] **Step 4: Update the v0.6.4 prompt-flow regression to the new truthful contract**

`tools/test_v064_command_prompt_flow.py` currently asserts `LISTENING` occurs inside `scheduleListeningAfterSpeech`. Replace only that obsolete assertion with:

- `READY_TO_LISTEN` before start call;
- LISTENING appears after `startRecording` in `captureCommandAudio`/callback integration;
- TTS completion still guards relisten.

Keep all other v0.6.4 continuation and session-generation checks.

- [ ] **Step 5: Run GREEN + wake freeze**

```bash
python tools/test_v065_listening_truth.py
python tools/test_v064_command_prompt_flow.py
python tools/test_v064_wake_regression.py
python tools/test_v065_frozen_baseline.py
```

Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt \
        tools/test_v065_listening_truth.py \
        tools/test_v064_command_prompt_flow.py
git commit -m "fix: show listening only during real microphone capture"
```

---

### Task 9: Replace fixed command VAD thresholds with an adaptive pure-Kotlin detector

**Files:**
- Create: `app/src/main/java/com/lchuang/xiaozhimobile/AdaptiveVoiceActivityDetector.kt`
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt:404-488`
- Test: `tools/test_v065_adaptive_vad.py`

**Interfaces:**
- Produces:

```kotlin
data class VadFrameDecision(
    val speechStarted: Boolean,
    val speechEnded: Boolean,
    val noiseFloor: Float,
    val startThreshold: Float,
    val endThreshold: Float
)

class AdaptiveVoiceActivityDetector(
    private val frameMs: Int = 50,
    private val stableSpeechFrames: Int = 2,
    private val endSilenceMs: Int = 650,
    initialNoiseFloor: Float = 0.0045f
) {
    fun reset()
    fun accept(rms: Float): VadFrameDecision
}
```

Initial algorithm constants:

```text
quiet EMA alpha       = 0.08
start threshold       = clamp(max(0.0085, noiseFloor * 2.2), 0.0085, 0.0300)
end threshold         = clamp(max(0.0060, noiseFloor * 1.5), 0.0060, startThreshold * 0.82)
stable speech start   = 2 consecutive frames (100ms)
end silence           = 650ms
```

Noise-floor update rule: before stable speech, update EMA only for frames `rms < startThreshold`; once stable speech begins, do not rapidly update the floor from speech frames. This is the initial balanced-mode implementation, not a KWS parameter.

- [ ] **Step 1: Write RED deterministic VAD harness**

Scenarios:

1. 40 quiet frames around RMS `0.004` lower/settle floor without speech start.
2. One spike at `0.02` followed by quiet does not trigger stable speech.
3. Two consecutive `0.02` frames trigger speech start.
4. During speech, `0.03` frames do not drive floor to `0.03`.
5. After speech, 12 quiet 50ms frames = 600ms does not end; 13th = 650ms ends.
6. A higher background sequence around `0.008` raises dynamic thresholds but remains bounded.

- [ ] **Step 2: Run RED**

```bash
python tools/test_v065_adaptive_vad.py
```

Expected: FAIL because detector is absent.

- [ ] **Step 3: Implement detector and integrate capture loop**

In `WakeService.captureCommandAudio`:

- remove `SPEECH_RMS_THRESHOLD`/`SILENCE_RMS_THRESHOLD` from command decision logic;
- instantiate/reset one detector per command capture;
- keep `COMMAND_FRAME_SAMPLES=800` (50ms);
- set `COMMAND_END_SILENCE_MS` to `650` only if retained for documentation/source checks, but VAD detector becomes authoritative;
- increase `PRE_ROLL_FRAMES` from `6` to `8` (400ms);
- append pre-roll only once stable speech begins;
- keep the existing minimum captured length guard of at least `SAMPLE_RATE / 2` samples before allowing end.

Do not modify KWS audio capture or its constants.

- [ ] **Step 4: Run GREEN + command regressions**

```bash
python tools/test_v065_adaptive_vad.py
python tools/test_local_asr_source.py
python tools/test_voice_command_retry.py
python tools/test_v065_listening_truth.py
python tools/test_v065_frozen_baseline.py
```

Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lchuang/xiaozhimobile/AdaptiveVoiceActivityDetector.kt \
        app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt \
        tools/test_v065_adaptive_vad.py
git commit -m "feat: add adaptive command voice activity detection"
```

---

### Task 10: Add optional Android NoiseSuppressor with guaranteed fallback

**Files:**
- Create: `app/src/main/java/com/lchuang/xiaozhimobile/AudioEnhancementManager.kt`
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt:404-474`
- Test: `tools/test_v065_noise_suppressor.py`

**Interfaces:**
- Produces:

```kotlin
class AudioEnhancementManager {
    fun attach(record: AudioRecord): AutoCloseable
}
```

Contract:
- If `NoiseSuppressor.isAvailable()` is false: return a no-op `AutoCloseable`.
- If create/enable throws or returns null: return no-op; never fail recording.
- If created: set `enabled = true`; returned handle releases it exactly once.
- Attach only to command `AudioRecord`, after it is initialized and before/around `startRecording`; never attach to KWS `AudioRecord` path.

- [ ] **Step 1: Write RED source-contract test**

Assert `NoiseSuppressor.isAvailable`, `NoiseSuppressor.create(record.audioSessionId)`, `enabled = true`, `release()`, and catch/fallback are present. Assert KWS function `startKwsCapture()` does not reference `AudioEnhancementManager`.

- [ ] **Step 2: Run RED**

```bash
python tools/test_v065_noise_suppressor.py
```

Expected: FAIL because manager is absent.

- [ ] **Step 3: Implement manager and scoped lifecycle**

In `captureCommandAudio`, use:

```kotlin
val enhancement = audioEnhancementManager.attach(record)
try {
    record.startRecording()
    ...
} finally {
    enhancement.close()
}
```

Ensure outer `finally`/`releaseAudioRecord()` cannot leak or double-release the effect.

- [ ] **Step 4: Run GREEN + frozen guard**

```bash
python tools/test_v065_noise_suppressor.py
python tools/test_v065_adaptive_vad.py
python tools/test_v064_wake_regression.py
python tools/test_v065_frozen_baseline.py
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lchuang/xiaozhimobile/AudioEnhancementManager.kt \
        app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt \
        tools/test_v065_noise_suppressor.py
git commit -m "feat: enhance command audio with safe noise suppression"
```

---

### Task 11: Integrate the unified execution transaction into WakeService for local and AI Tool commands

**Files:**
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt:50-90, 575-715, 775-800, notification helpers`
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/CommandRouter.kt` only if integration exposes a missing planner helper.
- Test: `tools/test_v065_execution_feedback.py` (integration section)
- Test: `tools/test_v065_error_recovery.py`
- Regression: `tools/test_v060_voice_integration.py`, `tools/test_v064_duplicate_guard.py`, `tools/test_v064_exit_intent.py`

**Interfaces:**
- Add service fields:

```kotlin
private lateinit var appExitController: AppExitController
private lateinit var deviceActionExecutor: DeviceActionExecutor
private lateinit var executionFormatter: ExecutionIntentFormatter
private lateinit var commandResultNotifier: CommandResultNotifier
private lateinit var executionCoordinator: ExecutionFeedbackCoordinator
private lateinit var audioEnhancementManager: AudioEnhancementManager
```

- Replace TTS helper with progress-aware core while retaining `speakThen` compatibility:

```kotlin
private fun speakWithProgress(
    text: String,
    onStart: () -> Unit = {},
    onDone: () -> Unit
)

private fun speakThen(text: String, done: () -> Unit) =
    speakWithProgress(text, onDone = done)
```

- Add one service helper:

```kotlin
private fun executeDeviceAction(
    rawText: String,
    normalized: String,
    action: DeviceAction,
    heard: String
)
```

- [ ] **Step 1: Write RED integration assertions**

Assert new `WakeService` path does all of the following:

- local `router.plan(normalized)` is attempted before AI;
- planned local action calls `executeDeviceAction`, not `router.handle()`;
- AI `AiOutcome.Tool` calls `safeToolExecutor.plan()` then the same `executeDeviceAction` for Allowed action;
- no device execution happens while `commandListening` capture is active;
- `ExecutionFeedbackCoordinator` uses TTS progress and 120ms delay;
- success increments `successfulDeviceActions`; failure does not;
- memory records AI Tool real execution result after callback;
- local failure is reported specifically, not routed to AI merely because `success=false`;
- duplicate guard still applies before action execution.

- [ ] **Step 2: Run RED**

```bash
python tools/test_v065_execution_feedback.py
python tools/test_v065_error_recovery.py
```

Expected: integration assertions FAIL; pure coordinator tests remain PASS.

- [ ] **Step 3: Wire dependencies in `onCreate()`**

Construct:

```kotlin
appExitController = AppExitController(this)
deviceActionExecutor = DeviceActionExecutor(phone, appExitController)
executionFormatter = ExecutionIntentFormatter()
commandResultNotifier = CommandResultNotifier(
    publish = { text -> updateNotificationRaw(text) },
    clockMs = { SystemClock.elapsedRealtime() },
    holdMs = 4000L
)
executionCoordinator = ExecutionFeedbackCoordinator(
    scheduler = DelayedScheduler { delay, block -> mainHandler.postDelayed(block, delay) },
    runner = DeviceActionRunner { action, callback -> deviceActionExecutor.execute(action, callback) },
    speech = SpeechDriver { text, onStart, onDone -> speakWithProgress(text, onStart, onDone) },
    formatter = executionFormatter,
    notifier = commandResultNotifier,
    actionDelayMs = 120L
)
```

Use actual Kotlin SAM syntax that compiles; if fun-interface lambda construction requires named implementations, use small anonymous objects without changing the interfaces.

- [ ] **Step 4: Replace local command execution path**

In `processNonExitUtterance`:

```text
router.plan(normalized)
  Planned -> executeDeviceAction(...); return
  Unhandled -> continue to AI / unsupported logic
```

A planned action whose Android execution fails is still a **handled command**; it must produce `❌` + specific failure speech and must not be reinterpreted by AI as if it were unhandled.

- [ ] **Step 5: Replace AI Tool direct execution path**

`SafeToolPlan.Allowed(action)` → same `executeDeviceAction` pipeline. `SafeToolPlan.Rejected(result)` → `SAFETY_REJECTED` response, no device operation. Keep existing no-dangerous-tools behavior.

- [ ] **Step 6: Implement TTS progress safely**

`UtteranceProgressListener.onStart(id)` calls the passed `onStart` only for that utterance id. `onDone`/both `onError` overloads call `onDone` once. Protect duplicate callbacks with an `AtomicBoolean` per utterance. If TTS is unavailable, post `onStart` immediately then `onDone` after 150ms so device execution still proceeds but state never claims microphone listening during fallback.

- [ ] **Step 7: Integrate notification retention**

Split current notification function:

```kotlin
private fun updateNotificationRaw(text: String) { ...notify... }
private fun updateNotification(text: String) = commandResultNotifier.publishTransient(text)
```

Before `restartWakeListening()` publishes the KWS idle message, call `commandResultNotifier.clearRetention()` and use `updateNotificationRaw(...)` so the wake status replaces the old command result.

- [ ] **Step 8: Run GREEN + voice/security regressions**

```bash
python tools/test_v065_execution_feedback.py
python tools/test_v065_error_recovery.py
python tools/test_v060_voice_integration.py
python tools/test_v060_safe_tools.py
python tools/test_v060_security.py
python tools/test_v064_duplicate_guard.py
python tools/test_v064_exit_intent.py
python tools/test_v064_command_prompt_flow.py
python tools/test_v065_frozen_baseline.py
```

Expected: all PASS.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt \
        app/src/main/java/com/lchuang/xiaozhimobile/CommandRouter.kt \
        tools/test_v065_execution_feedback.py \
        tools/test_v065_error_recovery.py
git commit -m "feat: unify local and AI device execution feedback"
```

---

### Task 12: Separate NO_SPEECH, ASR_EMPTY, unsupported, app-not-found, execution-failed, AI-failed recovery

**Files:**
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt:363-533, 575-662`
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/DeviceAction.kt` only if a helper enum method is required.
- Test: `tools/test_v065_error_recovery.py`
- Regression: `tools/test_voice_command_retry.py`

**Interfaces:**
- Add service recovery helper:

```kotlin
private fun recoverRecognitionFailure(kind: CommandFailureKind)
```

Exact user-visible policy:

```text
NO_SPEECH      -> no TTS; reset attempts as appropriate and immediately schedule another listen
ASR_EMPTY      -> "刚才没有听清，请再说一次。" then relisten
UNSUPPORTED    -> "这个指令我暂时还不会，你可以换一种说法。" then relisten
APP_NOT_FOUND  -> executor-specific "没有找到可启动的…" + "请继续说。"
EXECUTION_FAILED -> executor-specific failure + "请再试一次。"
AI_UNAVAILABLE -> "AI 服务暂时不可用，请稍后再试。"
SAFETY_REJECTED -> "这个操作不能执行。"
```

Recognition quality heuristic before command processing:
- blank ASR → `ASR_EMPTY`;
- normalized string length `<= 1` and not a known one-character command → treat as low-quality/ASR_EMPTY and allow one retry;
- full no-speech capture (VAD never starts) → `NO_SPEECH` and never say “不支持”。

- [ ] **Step 1: Expand RED tests to cover all categories**

Assert current generic `UNKNOWN_COMMAND_REPLY` is no longer used for `ASR_EMPTY`, app not found, execution failure, AI unavailable, or safety rejection. It may remain only as a compatibility constant if another historical source test depends on it, but production routing must use the category-specific copy above.

- [ ] **Step 2: Run RED**

```bash
python tools/test_v065_error_recovery.py
```

Expected: FAIL on current generic retry paths.

- [ ] **Step 3: Implement categorized recovery**

Preserve maximum retry count `MAX_COMMAND_RECOGNITION_ATTEMPTS = 2`. `NO_SPEECH` should not consume a spoken-error turn; `ASR_EMPTY` consumes one retry. Reset attempts after any successfully understood local/AI command, whether execution succeeds or fails, because execution failure is not recognition failure.

- [ ] **Step 4: Run GREEN + retry regression**

```bash
python tools/test_v065_error_recovery.py
python tools/test_voice_command_retry.py
python tools/test_v065_listening_truth.py
python tools/test_v065_frozen_baseline.py
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt \
        app/src/main/java/com/lchuang/xiaozhimobile/DeviceAction.kt \
        tools/test_v065_error_recovery.py
git commit -m "fix: distinguish recognition and execution failures"
```

---

### Task 13: Add v0.6.5 release gate, update source validation, and build the renamed APK

**Files:**
- Create: `tools/test_v065_release_gate.py`
- Modify: `tools/validate_project.py`
- Modify: `.github/workflows/build-apk.yml`
- Test: all v0.6.5 + historical tests

**Interfaces:**
- `tools/test_v065_release_gate.py` is the single local command used by CI for new-version regressions; it invokes focused scripts rather than reimplementing their logic.
- CI order becomes: frozen guard → historical regression → v0.6.5 tests → security → validate → models → Gradle → rename → artifact.

- [ ] **Step 1: Write RED release-gate script**

Use this explicit list (adjust only when a named test is intentionally merged, never silently omit a spec area):

```python
TESTS = [
    "tools/test_v065_frozen_baseline.py",
    "tools/test_v065_device_command_plan.py",
    "tools/test_v065_home_exit.py",
    "tools/test_v065_safe_tool_planning.py",
    "tools/test_v065_execution_copy.py",
    "tools/test_v065_execution_feedback.py",
    "tools/test_v065_listening_truth.py",
    "tools/test_v065_adaptive_vad.py",
    "tools/test_v065_noise_suppressor.py",
    "tools/test_v065_error_recovery.py",
]
```

Run each with `subprocess.run(["python", test], cwd=root, check=True)` and print one final PASS.

- [ ] **Step 2: Run RED**

```bash
python tools/test_v065_release_gate.py
```

Expected: if Tasks 1–12 are complete it should already PASS; if any required test is missing/failing, stop here and fix the owning task before changing CI. This task's RED requirement is satisfied by first adding an assertion to `validate_project.py` that expects v0.6.5 workflow/version tokens before modifying workflow/validator; run validator and observe FAIL.

- [ ] **Step 3: Update `validate_project.py`**

Change version checks to `versionCode = 12` / `versionName = "0.6.5"`, workflow APK name to `XiaoZhi-Mobile-v0.6.5-debug.apk`, and add checks that all new production modules/tests exist. Add source assertions for:

- no level-1 frozen changes (by invoking/depending on the guard test, not duplicating hashes);
- `NoiseSuppressor` only in command enhancement manager;
- Home implementation contains no force-stop/root/shell/Accessibility;
- `SafeToolExecutor` allowlist still contains only the existing tools;
- new execution coordinator and notification retention exist;
- no secret-like `sk-...` token.

- [ ] **Step 4: Update GitHub Actions order**

Before model download:

```yaml
- name: Frozen wake guard
  run: python3 tools/test_v065_frozen_baseline.py

- name: Historical regression
  run: |
    python3 tools/test_v031_behavior.py
    python3 tools/test_v040_voice_flow.py
    python3 tools/test_v050_session.py
    python3 tools/test_v060_security.py
    python3 tools/test_v063_custom_wake_ppinyin.py
    python3 tools/test_v063_wake_error_diagnostics.py
    python3 tools/test_v064_wake_regression.py
    python3 tools/test_v064_exit_intent.py
    python3 tools/test_v064_volume_parser.py
    python3 tools/test_v064_volume_execution.py
    python3 tools/test_v064_command_prompt_flow.py
    python3 tools/test_v064_duplicate_guard.py

- name: v0.6.5 feature regression
  run: python3 tools/test_v065_release_gate.py

- name: Security regression
  run: |
    python3 tools/test_v060_safe_tools.py
    python3 tools/test_v060_security.py

- name: Validate source tree
  run: python3 tools/validate_project.py
```

Keep model fetch/build after these. Rename step:

```yaml
- name: Rename APK
  run: cp app/build/outputs/apk/debug/app-debug.apk XiaoZhi-Mobile-v0.6.5-debug.apk
```

Artifact path must match that file exactly.

- [ ] **Step 5: Run complete local source gate**

```bash
python tools/test_v065_release_gate.py
python tools/validate_project.py
python -m compileall -q tools
```

Then run the historical suite used by the workflow. Expected: all PASS.

- [ ] **Step 6: Verify frozen hashes one final time**

```bash
python tools/test_v064_wake_regression.py
python tools/test_v065_frozen_baseline.py
```

Expected: PASS/PASS.

- [ ] **Step 7: Commit**

```bash
git add tools/test_v065_release_gate.py tools/validate_project.py .github/workflows/build-apk.yml
git commit -m "ci: gate v0.6.5 APK on frozen and feature regressions"
```

---

### Task 14: Full pre-push verification, GitHub build, APK validation, and real-device acceptance

**Files:**
- No production code changes unless verification exposes a defect; any defect returns to the owning task with a new failing regression test first.
- Generated source package: `XiaoZhi-Mobile-v0.6.5-GitHub-ready-FIXxx.zip`
- Final artifact target: `/mnt/data/XiaoZhi-Mobile-v0.6.5-debug.apk`

**Interfaces:**
- Consumes all previous tasks.
- Produces a buildable source ZIP, successful GitHub Actions run, validated APK SHA-256, and a real-device acceptance checklist. A successful GitHub build is **not** the same as successful phone acceptance.

- [ ] **Step 1: Run fresh full verification**

Required commands:

```bash
python tools/test_v065_release_gate.py
python tools/validate_project.py
python -m compileall -q tools
python tools/test_v064_wake_regression.py
python tools/test_v065_frozen_baseline.py
```

Run every historical test referenced by `.github/workflows/build-apk.yml`. Record exact PASS output; do not rely on prior runs.

- [ ] **Step 2: Review git diff against the golden baseline**

```bash
git diff --stat 671a1308826f093349fea7129dde711eeec7dd4d..HEAD
git diff 671a1308826f093349fea7129dde711eeec7dd4d..HEAD -- \
  app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseCompiler.kt \
  app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseManager.kt \
  app/src/main/java/com/lchuang/xiaozhimobile/Pinyin4jProvider.kt
```

Expected: zero diff for all three frozen files. Extract/hash `WakeService.initKeywordSpotter()` through the guard test; it must still match.

- [ ] **Step 3: Scan for secrets and forbidden capabilities**

```bash
python tools/test_v060_security.py
python tools/test_v060_safe_tools.py
python tools/validate_project.py
```

Also grep for `forceStopPackage`, `Runtime.getRuntime().exec`, `ProcessBuilder`, `AccessibilityService`, `BIND_ACCESSIBILITY_SERVICE`; expected no new production capability.

- [ ] **Step 4: Package GitHub-ready source ZIP**

Exclude `.git`, `.gradle`, all `build/`, `__pycache__`, `.pyc`, `.apk`, `.env`, local secrets. Validate ZIP with `zipfile.ZipFile(...).testzip()` and calculate SHA-256. Keep the manual proxy push scripts unchanged unless a separate bug requires them.

- [ ] **Step 5: User pushes through the established manual proxy flow**

User runs `PUSH_TO_GITHUB_MANUAL_PROXY.bat`, enters proxy port `7897`, and sends the final console output. Confirm new main HEAD matches the source package commit.

- [ ] **Step 6: Track GitHub Actions to completion**

Verify the run head SHA, every gate step, `:app:assembleDebug`, rename, and artifact upload. If any step fails, fetch job logs, use systematic debugging, write a failing regression test, then produce the next FIX ZIP; do not declare completion.

- [ ] **Step 7: Download and validate real artifact**

For `XiaoZhi-Mobile-APK` artifact:

- artifact ZIP `testzip() == None`;
- extract `XiaoZhi-Mobile-v0.6.5-debug.apk`;
- APK `testzip() == None`;
- contains `AndroidManifest.xml`, `classes.dex`, `lib/arm64-v8a/*.so`, `libsherpa-onnx-jni.so`;
- contains `sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20` assets;
- contains Paraformer assets;
- calculate and report final APK SHA-256 and byte size.

- [ ] **Step 8: Real-device acceptance — frozen behavior first**

Test in this exact order:

```text
1. 设置自定义“小白小白”并成功唤醒（一级冻结，必须第一个测试）
2. 唤醒回复 TTS 完成；只有真实麦克风开始后显示“正在听你说…”
3. 打开微信 → “打开微信正在执行” → 约120ms快速打开 → ✅通知 → 成功口播 → 自动继续监听
4. 退出微信 → “退出微信正在执行” → 回桌面 → ✅通知 → 微信在后台 → 小智会话仍继续
5. 单独“退出” → 退出小智会话 → 恢复自定义 KWS
6. 音量70% / 最大 / 一半 / 静音 / 大一点 / 小一点，最终口播使用真实音量
7. 高德 / 百度 / 导航 / 附近搜索
8. 音乐播放 / 暂停 / 上一首 / 下一首 / 停止
9. 手电筒开关
10. × 与双击悬浮层退出
11. 安静房间、风扇/空调背景、电视背景下测试短指令与带短暂停顿句子
12. 不存在 App → ❌通知 + “没有找到应用”，不能说“不支持”
13. AI 普通问答不说“正在执行”；AI safe tool 仍先提示后执行
14. TTS 播放期间麦克风不得开始命令监听，避免自听自执行
```

- [ ] **Step 9: Only after phone acceptance, run completion workflow**

Before claiming v0.6.5 complete, invoke `superpowers:verification-before-completion`, then `superpowers:finishing-a-development-branch`. Do not say “已完成/已修复/全部通过” based only on source tests or GitHub compilation.

---

## Plan Self-Review Results

### Spec coverage

- “退出微信/通用 App 退出→Home” → Tasks 2–3, 11, 14.
- 响应速度/本地优先 → Tasks 2, 4, 7, 11.
- 执行前提示 + 100–150ms并行执行 → Tasks 5, 7, 11.
- “正在听你说…”必须对应真实录音 → Task 8.
- 动态抗噪/VAD/pre-roll/650ms → Tasks 9–10.
- 成功/失败常驻通知 + 保留 → Tasks 5–7, 11.
- 错误类型分离 → Task 12.
- AI 安全边界 → Tasks 4, 11, 13.
- 一级/二级冻结 → Tasks 1, 8–14.
- GitHub Actions / Artifact / 真机验收 → Tasks 13–14.

No uncovered spec section found.

### Placeholder scan

Placeholder scan is clean. Every production change has a named test, RED command, implementation contract, GREEN command, and commit.

### Type consistency

- `DeviceAction` is the single action type consumed by `CommandRouter`, `SafeToolExecutor`, `DeviceActionExecutor`, formatter, transaction, and coordinator.
- `DeviceExecutionResult` is the single device result type consumed by formatter/coordinator/WakeService.
- `SafeToolPlan.Allowed` carries `DeviceAction`; AI does not gain `GoHome` as a tool.
- `CommandResultNotifier` remains Android-independent and publishes strings through `WakeService`'s existing notification owner.
- `SpeechDriver` is the only coordinator dependency on TTS progress; `WakeService.speakWithProgress` is the concrete adapter.

