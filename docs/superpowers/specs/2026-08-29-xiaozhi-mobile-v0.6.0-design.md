# XiaoZhi Mobile v0.6.0 Design

## Status

Approved design inputs collected from the user on 2026-08-29. This document is the implementation contract for v0.6.0. Implementation must not start until the user reviews and approves this written specification.

## Primary Goals

v0.6.0 upgrades XiaoZhi Mobile from a mostly rule-driven local assistant into a hybrid local + AI mobile assistant while preserving offline wake/ASR and safe Android execution boundaries.

The release must deliver four outcomes:

1. Open and control arbitrary installed launchable apps reliably enough to diagnose and handle OEM/package-visibility differences.
2. Provide richer navigation and nearby-place commands, especially for Amap (高德地图), with user-approved foreground location access.
3. Let the user customize the assistant name, wake phrase, and TTS voice without rebuilding the APK.
4. Make AI conversation more capable and stateful while allowing AI to call only a strict safe tool allowlist.

Existing v0.5.0 behavior that must remain intact:

- Offline sherpa-onnx keyword spotting.
- Offline Paraformer ASR after wake.
- Transparent system overlay.
- User-configurable continuous-session timeout and timeout phrase.
- User-configurable wake acknowledgement phrase.
- Immediate re-listen after successful local device commands.
- User-configurable desktop shortcut icon.
- Local device controls continue working even if AI is unavailable.

---

## 1. High-Level Architecture

The main service must stop owning every behavior directly. v0.6.0 introduces focused components with narrow interfaces:

- `WakePhraseManager`
  - Compiles and validates user wake phrases.
  - Creates/recreates the sherpa-onnx KWS stream with runtime keywords.
  - Owns active wake phrase diagnostics.

- `TtsVoiceManager`
  - Enumerates device TTS voices.
  - Filters/preferentially ranks Chinese voices.
  - Applies selected voice, speech rate, and pitch.
  - Supports preview playback.

- `InstalledAppRegistry`
  - Keeps the existing launcher-index strategy.
  - Adds broader package visibility diagnostics and fallback discovery paths.
  - Exposes a diagnostic app list and match explanation.

- `AppLauncher`
  - Starts a resolved package using multiple safe launcher strategies.
  - Returns structured success/failure reasons instead of a Boolean only.

- `MapController`
  - Handles map-app selection, Amap/Baidu/system map launch, navigation, and nearby search.
  - Reads foreground location only when a location-dependent command needs it.

- `LocationProvider`
  - Requests/reads foreground coarse/fine location.
  - Never requests background location.
  - Does not continuously track the user.

- `AiEndpointResolver`
  - Treats the configured URL as a Base URL.
  - Resolves candidate OpenAI-compatible endpoints.
  - Supports automatic capability detection.

- `AiConversationMemory`
  - Stores the active wake-session conversation only.
  - Keeps at most 8 user/assistant turns.
  - Clears when the active session ends.

- `AiOrchestrator`
  - Sends normal conversation requests.
  - Requests structured device intent when local routing cannot fully understand a command.
  - Supports native tool calling when available and strict JSON fallback when not.

- `SafeToolExecutor`
  - Is the only bridge from AI output to Android device actions.
  - Validates tool name, argument types, limits, and required permissions.
  - Rejects all unapproved tools.

- `WakeService`
  - Remains the lifecycle owner for KWS, ASR, continuous session, overlay, and TTS sequencing.
  - Delegates app discovery, maps, wake phrase compilation, voice selection, AI planning, and tool execution to the components above.

The intended flow is:

```text
Microphone
  -> offline KWS
  -> offline Paraformer ASR
  -> local normalizer/router
       -> direct safe local action when confidently matched
       -> AiOrchestrator when normal conversation or ambiguous intent
            -> reply OR structured tool request
            -> SafeToolExecutor
                 -> AppLauncher / MapController / PhoneController
  -> overlay + TTS
  -> immediate continuous listening
```

---

## 2. Installed App Discovery and Launching

### 2.1 Problem being solved

v0.5.0 can parse general phrases such as `打开...`, but on some Android/OEM devices the launcher query returns an incomplete app set because of package visibility behavior. When resolution fails, the current voice flow reports the unknown-command fallback even though the app is installed.

v0.6.0 must separate these failure classes:

- ASR did not hear the requested app name correctly.
- Command parsing failed.
- App is not visible to the package manager.
- App was discovered but name matching failed.
- App name resolved to the wrong package.
- Package was resolved but no launch activity could be started.

### 2.2 Package visibility strategy

For the direct-install/sideload build used in this project, v0.6.0 will add `QUERY_ALL_PACKAGES` so the app can build a much more complete local app index.

The manifest will still keep targeted `<queries>` entries for known critical apps and common VIEW intents.

Important distribution rule:

- The direct-install build may use `QUERY_ALL_PACKAGES`.
- If a future Google Play release is created, package visibility must be reviewed separately because Play restricts broad package visibility. A Play-compatible flavor may need narrower discovery.

### 2.3 Registry data

Each discoverable launchable app is represented as:

```text
AppEntry
- label
- packageName
- normalizedLabel
- launchActivities[]
- aliases[]
- source (launcher_query / installed_packages / known_fallback)
```

The registry must expose:

- discovered app count;
- searchable app list;
- package name;
- display label;
- resolution explanation for diagnostics.

### 2.4 Match order

Resolution order:

1. Explicit user alias.
2. Exact normalized display label.
3. Exact known alias, e.g. `高德导航 -> 高德地图`.
4. Prefix/suffix containment.
5. Token-aware/abbreviation aliases.
6. Fuzzy similarity with a conservative threshold.
7. AI name interpretation only if local matching is ambiguous; AI cannot invent a package and execution still requires registry validation.

Examples:

```text
打开高德导航 -> 高德地图 -> com.autonavi.minimap
打开B站 -> 哔哩哔哩 -> installed package
打开夸克浏览器 -> 夸克 -> installed package
打开WPS -> WPS Office -> installed package
```

### 2.5 Launch fallback order

`AppLauncher.launch(packageName)` attempts:

1. `getLaunchIntentForPackage`.
2. Resolve `ACTION_MAIN + CATEGORY_LAUNCHER` scoped to that package.
3. Explicit launch of the best launcher activity.

It returns a structured result:

```text
SUCCESS
PACKAGE_NOT_VISIBLE
PACKAGE_NOT_INSTALLED
NO_LAUNCH_ACTIVITY
START_ACTIVITY_FAILED
```

No silent Boolean-only failure should remain in the new path.

### 2.6 App diagnostics in MainActivity

Add a diagnostic area:

```text
已发现应用：87 个
[刷新应用列表]
[查看已发现应用]
[测试打开应用]
```

For a failed app command, the user can see what label/package XiaoZhi actually found. This is a required debugging feature, not optional UI polish.

---

## 3. Navigation and Nearby Search

### 3.1 Location permission scope

v0.6.0 may request:

- `ACCESS_COARSE_LOCATION`
- `ACCESS_FINE_LOCATION`

It must NOT request background location.

Location is read only for a user-initiated command that needs nearby context, such as:

- `附近帮我找商场`
- `附近的加油站`
- `最近的医院`

The assistant must not continuously poll location while idle.

### 3.2 Map preference

Settings add:

```text
默认地图
- 自动
- 高德地图
- 百度地图
- 系统默认
```

Rules:

- If the user explicitly says `用高德...`, that command overrides the saved default.
- `自动` prefers Amap when installed, then Baidu Map, then a generic system geo intent.

### 3.3 Map intents

`MapController` supports:

- open a map app;
- navigate to a textual destination;
- nearby POI search around current location;
- route to a selected nearby destination when one is unambiguous.

Examples:

```text
打开高德导航
导航到广州南站
用高德导航到深圳北站
附近帮我找商场
找附近加油站
用百度地图找附近医院
```

### 3.4 Nearby command behavior

For `附近帮我找商场`:

1. Verify foreground location permission.
2. Get a current/recent location with a bounded timeout.
3. Open the preferred map app with a nearby-search intent using the keyword and location context.
4. If location cannot be obtained, fall back to opening the map app's keyword search and tell the user that the map app will determine the location.

The assistant itself does not need to maintain a POI database in v0.6.0.

### 3.5 Permission failure handling

If the user denies location permission:

- Do not keep prompting in a loop.
- Explain that nearby search needs location permission.
- Offer a map-only fallback when possible.
- Ordinary app control and navigation to a named place must still work.

---

## 4. Custom Assistant Name and Wake Phrase

### 4.1 Separate identity from wake phrase

Settings add two separate fields:

```text
助手名字: 小智
唤醒短语: 小智小智
```

Changing the assistant name to `小白` proposes `小白小白` as the wake phrase, but the user may override it, for example:

```text
助手名字: 小白
唤醒短语: 小白在吗
```

The assistant name is used in UI/notification text where appropriate. The wake phrase is the actual KWS trigger.

### 4.2 Runtime KWS

The current sherpa-onnx KWS API supports runtime keywords on stream creation. v0.6.0 will stop relying solely on the bundled fixed `keywords.txt` stream.

`WakePhraseManager` creates a runtime keyword string and calls the KWS stream creation API with that compiled keyword.

Saving a new wake phrase must:

1. Validate the phrase.
2. Compile it into KWS tokens.
3. Stop the active KWS capture safely.
4. Recreate the KWS stream with the new runtime keyword.
5. Restart KWS listening.
6. Update notification/debug UI with the active phrase.

No APK rebuild is required.

### 4.3 Wake phrase compilation

The current KWS model uses pinyin-style keyword tokens. v0.6.0 must add a deterministic local `WakePhraseCompiler`.

For Chinese input the compiler must produce the tokenized keyword form expected by the bundled sherpa model, including the original phrase marker used by KWS.

The implementation may use a compact local pinyin/token conversion dependency or bundled mapping, but it must satisfy all of these requirements:

- fully offline at runtime;
- deterministic;
- tone-aware enough for the model's expected token format;
- no network call when saving a wake phrase;
- validated against the model's bundled token inventory before activating.

If compilation fails, keep the previous valid wake phrase active and show a clear error instead of breaking wake detection.

### 4.4 Wake phrase constraints

Recommended user-facing constraints:

- 2-6 Chinese characters for the most reliable experience;
- warn for one-character phrases because of false-wake risk;
- allow longer phrases but mark them as potentially less responsive;
- v0.6.0 prioritizes Chinese wake phrases; mixed Chinese/English may be accepted only if compilation validates against the current model.

### 4.5 Wake test UI

Add:

```text
当前助手名字：小白
当前唤醒短语：小白小白
状态：正在监听“小白小白”
[保存并应用]
```

A save is successful only after the new runtime KWS stream is created successfully.

---

## 5. TTS Voice Customization

### 5.1 Scope

v0.6.0 keeps Android system TTS as the default speech engine. It does not add a paid cloud TTS dependency in this release.

### 5.2 Settings

Add:

```text
声音: [device Chinese voice]
[试听]
语速: 1.00x
音调: 1.00x
[恢复默认]
[打开系统语音合成设置]
```

Persist:

- selected voice name/id;
- speech rate;
- pitch.

### 5.3 Voice enumeration

`TtsVoiceManager` reads the actual voices provided by the installed TTS engine and ranks Chinese voices first.

If only one suitable Chinese voice is installed, the UI must say so instead of presenting fake choices.

If a saved voice disappears after a system/TTS-engine update, fall back to the default Chinese voice and update settings gracefully.

### 5.4 Preview

Preview speaks a short fixed sample such as:

```text
你好，我是小智，这是当前语音效果。
```

Preview must not alter the active continuous-session state machine.

---

## 6. AI Endpoint Configuration and Test

### 6.1 Base URL semantics

The user enters a Base URL, not a full endpoint.

Example configuration:

```text
Base URL: https://example.com
```

Candidate endpoints are derived internally, including:

```text
/v1/models
/v1/chat/completions
/v1/responses
```

The code must normalize trailing slashes and must also tolerate a user pasting a URL that already ends in `/v1`.

### 6.2 API mode

Add:

```text
API 模式
- 自动检测
- Chat Completions
- Responses
```

`自动检测` is the default.

Detection must be conservative: a successful `/v1/models` request alone does not prove a response endpoint works. The test flow must actually perform a minimal model request.

### 6.3 Test AI button

Add a `测试 AI 接口` button. It sends a minimal, low-token request equivalent to:

```text
只回复：OK
```

The result screen shows:

- success/failure;
- HTTP status;
- selected/detected API mode;
- model;
- total latency;
- short reply;
- sanitized error reason.

The full API key must never be displayed in logs, status text, notifications, or test result details.

### 6.4 Secret handling

The temporary test API key supplied during development must never be committed to source, tests, documentation, screenshots, or build artifacts.

Runtime API keys remain user-entered local settings. v0.6.0 may keep SharedPreferences compatibility for this release, but no new debug path may print the key. A future release may move secrets to Android Keystore-backed encrypted storage.

---

## 7. AI Conversation Memory

### 7.1 Session-scoped memory

AI conversation becomes contextual within the active wake session.

Keep at most 8 completed user/assistant turns.

Example:

```text
User: 今天深圳天气怎么样？
Assistant: ...
User: 那明天呢？
```

The second request includes enough prior context for `明天` to refer to Shenzhen.

### 7.2 Memory lifecycle

Memory starts when a wake session starts and clears when:

- the user says an explicit exit phrase;
- the configured silence timeout exits the overlay/session;
- the service is stopped/restarted.

No long-term memory is added in v0.6.0.

### 7.3 Token control

Only the last 8 completed turns are retained. Oversized assistant replies are truncated/summarized for history storage if necessary so a single response cannot permanently inflate every later request.

---

## 8. AI Intent Planning and Safe Tool Calls

### 8.1 Local-first rule

The existing local router remains first priority for deterministic commands:

```text
打开微信
播放音乐
停止音乐
音量大一点
打开手电筒
```

AI is not required for commands that local logic can confidently execute.

### 8.2 When AI planning is used

Use `AiOrchestrator` when:

- the utterance is a normal conversational question;
- the utterance looks like a device command but local parsing is incomplete or ambiguous;
- the request combines multiple concepts, e.g. `用高德带我去最近的麦当劳`.

### 8.3 Supported tool allowlist

The only AI-callable tools in v0.6.0 are:

```text
open_app(name)
navigate(destination, mapApp?)
search_nearby(keyword, mapApp?)
open_web(query_or_url)
media_play()
media_pause()
media_next()
media_previous()
volume_up()
volume_down()
set_volume(percent)
flashlight_on()
flashlight_off()
```

No other tool name is executable.

### 8.4 Explicitly forbidden AI actions

AI must not be able to directly invoke:

- delete files, photos, messages, or chat history;
- payments, purchases, transfers, or financial transactions;
- send messages or emails;
- install/uninstall apps;
- change passwords/account security;
- read/export SMS, contacts, private files, or credentials;
- arbitrary shell commands;
- arbitrary Android intents supplied by the model.

If the model requests an unapproved action, `SafeToolExecutor` returns a rejection and nothing is executed.

### 8.5 Native tool calls + JSON fallback

The AI integration uses two modes:

1. Prefer native tool/function calling when the detected endpoint/model supports it.
2. Fall back to strict structured JSON when native tool calls are unsupported or malformed.

JSON fallback shape:

```json
{
  "type": "tool_call",
  "tool": "navigate",
  "args": {
    "destination": "广州南站",
    "mapApp": "高德地图"
  }
}
```

Normal conversation shape:

```json
{
  "type": "reply",
  "text": "..."
}
```

The parser rejects:

- unknown fields that change execution meaning;
- missing required args;
- wrong argument types;
- numeric values outside safe bounds;
- unknown tools;
- attempts to smuggle an arbitrary intent/URI/package into an unrelated tool.

### 8.6 SafeToolExecutor validation

Examples:

- `set_volume(120)` -> clamp/reject according to defined 0-100 policy.
- `open_app("不存在的软件")` -> registry validation fails; do not invent a package.
- `navigate("")` -> reject missing destination.
- `open_web("javascript:...")` -> reject unsupported dangerous scheme.
- `delete_all_files()` -> reject unknown tool.

AI output is advisory until the local executor validates it.

### 8.7 AI failure isolation

If AI is unavailable, times out, or returns invalid structured output:

- local app/media/volume/flashlight/map commands that can be parsed locally continue to work;
- the session stays active unless timeout expires;
- conversational requests get a concise failure message such as `AI 服务暂时不可用，请稍后再试`;
- invalid AI tool output never causes a crash or arbitrary action.

---

## 9. Voice and Overlay Behavior

v0.6.0 preserves the v0.5.0 session semantics:

- wake -> configured wake acknowledgement -> listen;
- user speech refreshes session deadline;
- local successful command -> immediately listen again without long spoken confirmation;
- AI answer -> TTS -> immediately listen again;
- silence until configured timeout -> configured timeout phrase -> hide overlay -> return to KWS.

Overlay status should become more diagnostic during complex actions:

```text
我听到：用高德带我去广州南站
正在理解…
正在打开高德导航…
```

For app-launch failure:

```text
没有找到可启动的“夸克”
```

For AI failure:

```text
AI 服务暂时不可用
```

Do not expose API keys, raw stack traces, or sensitive internal error text on the overlay.

---

## 10. Settings UI Structure

The existing single-page native UI may remain visually simple in v0.6.0, but settings should be grouped clearly.

Recommended sections:

```text
语音助手
- 后台离线唤醒
- 悬浮层权限
- 助手名字
- 唤醒短语
- 唤醒后回复
- 连续会话超时
- 超时退出回复

声音
- TTS Voice
- 试听
- 语速
- 音调

手机控制
- 默认地图
- 位置权限状态
- 已发现应用数量
- 查看/刷新应用列表
- App 别名

AI 对话
- Base URL
- API Key
- 模型
- API 模式
- 测试 AI 接口

个性化
- 桌面图标

调试
- 本地指令测试
- 当前 KWS 唤醒短语
- 最近一次 App 匹配说明
- 最近一次 AI 接口测试摘要
```

---

## 11. Data Model / Settings Additions

`SettingsStore` adds at minimum:

```text
assistantName: String = "小智"
wakePhrase: String = "小智小智"
wakeReply: String = "我在"
timeoutReply: String
sessionTimeoutSeconds: Int
appAliases: String
defaultMapApp: enum/string = "auto"
ttsVoiceName: String = ""
ttsSpeechRate: Float = 1.0
ttsPitch: Float = 1.0
apiBaseUrl: String
apiKey: String
model: String
apiMode: auto/chat_completions/responses
```

Existing keys should be migrated without losing the user's v0.5.0 settings.

If legacy `api_url` already contains `/v1/chat/completions`, migration should derive the Base URL instead of producing a duplicated endpoint.

---

## 12. Error Handling Requirements

No new subsystem may fail silently.

Required structured error families:

- `WakePhraseError`
  - invalid text
  - unsupported token
  - stream creation failure

- `AppLaunchError`
  - not visible
  - not installed
  - no launcher
  - start failure

- `LocationError`
  - permission denied
  - provider unavailable
  - timeout

- `MapError`
  - requested map app unavailable
  - URI/intent failure

- `AiConnectionError`
  - DNS/connect timeout
  - HTTP 401/403/404/429/5xx
  - unsupported endpoint
  - empty/malformed response

- `ToolValidationError`
  - unknown tool
  - invalid args
  - forbidden action

User-facing messages stay concise; detailed sanitized reasons can appear in the debug section.

---

## 13. Testing Strategy

v0.6.0 requires both pure JVM/source tests and a real Android GitHub Actions build.

### 13.1 Wake phrase tests

- default `小智小智` compiles;
- `小白小白` compiles;
- custom 2-6 character phrases compile when supported;
- invalid/unsupported phrase does not replace the previous working phrase;
- KWS stream creation uses runtime keyword input;
- notification/debug UI reports the active phrase.

### 13.2 App registry tests

- exact label;
- user alias;
- known alias `高德导航 -> 高德地图`;
- fuzzy label;
- AI-suggested app name still requires registry validation;
- structured failure reasons;
- diagnostics expose discovered app count and labels.

### 13.3 Map tests

- `打开高德导航`;
- named destination navigation;
- explicit map override;
- default-map preference;
- nearby-search command;
- denied location permission fallback;
- missing Amap fallback to Baidu/system map.

### 13.4 TTS tests

- enumerate voices;
- selected voice persisted;
- missing saved voice falls back;
- speech rate and pitch clamped to safe supported ranges;
- preview does not mutate assistant session state.

### 13.5 AI endpoint tests

Use a fake/local test transport for deterministic tests:

- Base URL normalization;
- `/v1` handling;
- Chat Completions parse;
- Responses parse;
- auto mode fallback;
- 401/404/429/5xx reporting;
- API key never appears in logs/test strings;
- minimal test request remains small.

Real endpoint validation is performed from the installed Android app using the `测试 AI 接口` button because the user's actual network path is what matters.

### 13.6 Conversation tests

- 8-turn maximum history;
- history cleared on timeout;
- history cleared on explicit exit;
- context retained within the same session;
- local device commands do not require AI;
- AI failure does not break local control.

### 13.7 Safe tool tests

Every allowlisted tool has success + invalid-args tests.

Every forbidden category has a rejection test.

At minimum verify rejection of:

```text
delete_all_files
send_message
transfer_money
install_app
shell_command
```

### 13.8 Regression tests

All v0.5.0 regression tests must continue to pass, including:

- offline KWS/ASR source validation;
- overlay source behavior;
- 20-second/configurable session timeout behavior;
- immediate local-command re-listen;
- unknown-command fallback;
- desktop icon behavior;
- manual proxy forced upload behavior.

### 13.9 Build verification

Completion requires a real GitHub Actions Android build, artifact upload, APK extraction, and integrity verification. Source tests alone are not sufficient to claim v0.6.0 is complete.

---

## 14. Acceptance Scenarios

v0.6.0 is accepted only if the following device scenarios work on the user's phone.

### Scenario A: arbitrary app

```text
User: 小白小白
Assistant: configured wake reply
User: 打开夸克
Result: installed Quark app opens
Assistant: immediately listens again
```

### Scenario B: Amap navigation

```text
User: 用高德导航到广州南站
Result: Amap opens route/navigation flow for 广州南站
```

### Scenario C: nearby search

```text
User: 附近帮我找商场
Result: foreground location is obtained (with user permission), preferred map opens nearby 商场 search
```

### Scenario D: custom wake phrase

```text
Settings: 助手名字=小白, 唤醒短语=小白小白
User returns to desktop and says: 小白小白
Result: overlay appears and session starts without APK reinstall
```

### Scenario E: voice selection

```text
User selects another installed Chinese TTS voice and presses 试听
Result: preview uses that voice; later assistant replies use the same saved voice
```

### Scenario F: AI conversation

```text
User: 给我介绍一下深圳湾
Assistant: ...
User: 那里晚上适合去吗？
Result: AI understands the second turn refers to 深圳湾
```

### Scenario G: AI tool planning

```text
User: 帮我用高德找附近的大商场
AI plan: search_nearby(keyword="商场", mapApp="高德地图")
SafeToolExecutor validates
Result: Amap nearby search opens
```

### Scenario H: forbidden AI action

```text
Model output: delete_all_files
Result: rejected locally, no Android destructive action occurs
```

### Scenario I: AI outage

```text
AI endpoint unreachable
User: 打开微信
Result: WeChat still opens locally
```

---

## 15. Out of Scope for v0.6.0

The following are deliberately deferred:

- AccessibilityService-driven UI automation inside arbitrary third-party apps.
- Automatic sending of WeChat/QQ messages.
- Payments or financial actions.
- Background location tracking.
- Long-term AI memory across wake sessions.
- Always-on cloud speech recognition.
- Full-duplex echo-cancelled barge-in while TTS is speaking.
- Paid cloud TTS voices.
- Google Play distribution compliance work for broad package visibility.

These exclusions keep v0.6.0 focused on safe, testable assistant behavior.

---

## 16. Delivery Definition

The implementation is considered complete only when all of the following exist:

1. v0.6.0 source with the architecture above.
2. Updated source validation and regression tests.
3. No temporary API key or user secret in the repository/artifact.
4. GitHub-ready source package.
5. Successful GitHub Actions Android build.
6. Downloadable `XiaoZhi-Mobile-v0.6.0-debug.apk`.
7. APK integrity and architecture checks.
8. Device test checklist covering custom wake phrase, arbitrary app launch, map navigation, nearby search, TTS selection, AI endpoint test, conversational memory, and safe tool rejection.
