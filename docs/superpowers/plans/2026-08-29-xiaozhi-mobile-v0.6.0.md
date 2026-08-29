# XiaoZhi Mobile v0.6.0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade XiaoZhi Mobile v0.5.0 into a hybrid local + AI Android assistant that can reliably discover/open installed apps, launch map navigation/nearby search, change wake phrase and TTS voice at runtime, keep short conversational context, and execute only a strict allowlist of AI-planned phone actions.

**Architecture:** Preserve the existing offline sherpa-onnx KWS, offline Paraformer ASR, transparent overlay, and continuous-session lifecycle. Split device discovery/launch, maps/location, wake phrase compilation, TTS voice selection, endpoint detection, conversation memory, AI planning, and safe tool execution into focused components; `WakeService` remains the lifecycle coordinator but no longer owns those implementations. Local deterministic commands execute first; ambiguous device commands and normal conversation may go through `AiOrchestrator`, and every AI device action must pass `SafeToolExecutor` before Android execution.

**Tech Stack:** Kotlin, Android SDK 35, minSdk 26, Java 17, sherpa-onnx v1.13.4, Android `PackageManager`, `LocationManager`, `TextToSpeech`, `HttpURLConnection`, `org.json`, pinyin4j 2.5.1, SharedPreferences, Python source-regression tests, Kotlin/JVM harness tests, GitHub Actions Gradle 8.9.

**Spec:** `docs/superpowers/specs/2026-08-29-xiaozhi-mobile-v0.6.0-design.md`

## Global Constraints

- Keep offline sherpa-onnx keyword spotting and offline Paraformer ASR; continuous microphone audio must not be sent to the AI endpoint.
- Keep the transparent overlay, configurable continuous-session timeout, configurable wake acknowledgement, configurable timeout phrase, immediate re-listen after successful local device commands, and custom desktop shortcut icon from v0.5.0.
- Direct-install v0.6.0 may declare `android.permission.QUERY_ALL_PACKAGES`; Google Play distribution work is outside this release.
- Request only foreground `ACCESS_COARSE_LOCATION` and `ACCESS_FINE_LOCATION`; never request background location and never continuously track location while idle.
- Default assistant name is `小智`; default wake phrase is `小智小智`.
- Wake phrase changes must apply at runtime without APK reinstall and must preserve the previous valid phrase if compilation/application fails.
- Keep Android system TTS; do not add paid cloud TTS in v0.6.0.
- AI configuration uses a Base URL and supports `AUTO`, `CHAT_COMPLETIONS`, and `RESPONSES` modes.
- AI conversation memory is session-scoped and stores at most 8 completed user/assistant turns; clear it on timeout, explicit exit, or service stop/restart.
- AI-callable tools are only: `open_app`, `navigate`, `search_nearby`, `open_web`, `media_play`, `media_pause`, `media_next`, `media_previous`, `volume_up`, `volume_down`, `set_volume`, `flashlight_on`, `flashlight_off`.
- Never allow AI to delete data, send messages/email, make payments/transfers/purchases, install/uninstall apps, change passwords, read/export SMS/contacts/private files/credentials, run shell commands, or execute arbitrary model-provided Android intents/URIs/packages.
- The temporary development API key must never appear in source, tests, documentation, logs, screenshots, or build artifacts.
- Keep target ABI `arm64-v8a` and compile/target SDK 35.
- Completion requires a real GitHub Actions Android build and APK integrity verification; source tests alone are not sufficient.

---

## File Structure Map

The implementation should converge on these responsibilities before integration:

```text
app/src/main/java/com/lchuang/xiaozhimobile/
  SettingsStore.kt             persistent settings + v0.5 -> v0.6 migration
  AppNameMatcher.kt            pure app-name normalization/alias/fuzzy scoring
  InstalledAppRegistry.kt      complete discoverable app index + resolution diagnostics
  AppLauncher.kt               structured multi-strategy package launching
  LocationProvider.kt          one-shot foreground location only
  MapController.kt             Amap/Baidu/system navigation + nearby search
  WakePhraseCompiler.kt        pure phrase -> sherpa keyword tokens
  Pinyin4jProvider.kt          offline Chinese pronunciation adapter
  WakePhraseManager.kt         apply/rebuild runtime KWS stream
  TtsVoiceManager.kt           enumerate/apply/preview system voices
  AiModels.kt                  API mode, messages, replies, tool calls, test result types
  AiEndpointResolver.kt        Base URL normalization + endpoint candidates
  AiClient.kt                  low-level HTTP transport for chat/responses/test requests
  AiConversationMemory.kt      at-most-8 completed turns for current wake session
  AiOrchestrator.kt            normal reply + native tools + strict JSON fallback planning
  SafeToolExecutor.kt          allowlist validation + local execution bridge
  CommandRouter.kt             deterministic local parsing, including map phrases
  PhoneController.kt           media/volume/flashlight/web + delegates app/map work
  WakeService.kt               KWS/ASR/session/overlay/TTS lifecycle coordinator
  MainActivity.kt              settings, diagnostics, AI test, wake/TTS controls
```

New tests should be small and purpose-specific:

```text
tools/test_v060_settings.py
tools/test_v060_app_launch.py
tools/test_v060_map.py
tools/test_v060_wake_phrase.py
tools/test_v060_tts.py
tools/test_v060_ai_endpoint.py
tools/test_v060_ai_memory.py
tools/test_v060_safe_tools.py
tools/test_v060_voice_integration.py
tools/test_v060_security.py
```

---

### Task 1: Add v0.6 settings types, migration, permissions, and version-neutral foundations

**Files:**
- Create: `app/src/main/java/com/lchuang/xiaozhimobile/AiModels.kt`
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/SettingsStore.kt:5-46`
- Modify: `app/src/main/AndroidManifest.xml:3-37`
- Test: `tools/test_v060_settings.py`

**Interfaces:**
- Produces `enum class ApiMode { AUTO, CHAT_COMPLETIONS, RESPONSES }`.
- Produces `enum class MapAppPreference { AUTO, AMAP, BAIDU, SYSTEM }`.
- Produces SettingsStore properties:
  - `assistantName: String`
  - `wakePhrase: String`
  - `defaultMapApp: MapAppPreference`
  - `ttsVoiceName: String`
  - `ttsSpeechRate: Float`
  - `ttsPitch: Float`
  - `apiBaseUrl: String`
  - `apiMode: ApiMode`
- Preserves existing `wakeReply`, `timeoutReply`, `sessionTimeoutSeconds`, `appAliases`, `apiKey`, `model`, and `systemPrompt` values.
- `SettingsStore.migrateLegacyApiUrlIfNeeded()` must convert a v0.5 `api_url` that ends in `/v1/chat/completions`, `/v1/responses`, or `/v1` into a Base URL exactly once.

- [ ] **Step 1: Write the failing settings/migration source test**

Create `tools/test_v060_settings.py` with these exact assertions:

```python
from pathlib import Path

root = Path(__file__).resolve().parents[1]
settings = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/SettingsStore.kt').read_text('utf-8')
models = root / 'app/src/main/java/com/lchuang/xiaozhimobile/AiModels.kt'
manifest = (root / 'app/src/main/AndroidManifest.xml').read_text('utf-8')

required = [
    'var assistantName: String', '"小智"',
    'var wakePhrase: String', '"小智小智"',
    'var defaultMapApp: MapAppPreference',
    'var ttsVoiceName: String',
    'var ttsSpeechRate: Float',
    'var ttsPitch: Float',
    'var apiBaseUrl: String',
    'var apiMode: ApiMode',
    'migrateLegacyApiUrlIfNeeded',
    '/v1/chat/completions', '/v1/responses',
]
missing = [x for x in required if x not in settings]
if missing:
    raise SystemExit('missing v0.6 settings: ' + ', '.join(missing))
if not models.exists():
    raise SystemExit('AiModels.kt missing')
model_text = models.read_text('utf-8')
for value in ['AUTO', 'CHAT_COMPLETIONS', 'RESPONSES', 'MapAppPreference']:
    if value not in model_text:
        raise SystemExit('missing settings enum: ' + value)
for permission in ['android.permission.QUERY_ALL_PACKAGES', 'android.permission.ACCESS_COARSE_LOCATION', 'android.permission.ACCESS_FINE_LOCATION']:
    if permission not in manifest:
        raise SystemExit('missing permission: ' + permission)
if 'ACCESS_BACKGROUND_LOCATION' in manifest:
    raise SystemExit('background location must not be requested')
print('PASS: v0.6 settings and permissions')
```

- [ ] **Step 2: Run the test and verify it fails before implementation**

Run:

```bash
python3 tools/test_v060_settings.py
```

Expected: non-zero exit with at least `AiModels.kt missing` or `missing v0.6 settings`.

- [ ] **Step 3: Add typed API/map enums and core AI result types**

Create `AiModels.kt` with the concrete foundation used by later tasks:

```kotlin
package com.lchuang.xiaozhimobile

enum class ApiMode { AUTO, CHAT_COMPLETIONS, RESPONSES }
enum class MapAppPreference { AUTO, AMAP, BAIDU, SYSTEM }

data class ConversationMessage(val role: String, val content: String)

data class AiToolCall(val tool: String, val args: Map<String, Any?>)

sealed class AiOutcome {
    data class Reply(val text: String) : AiOutcome()
    data class Tool(val call: AiToolCall) : AiOutcome()
}

data class AiEndpointTestResult(
    val success: Boolean,
    val httpStatus: Int?,
    val mode: ApiMode?,
    val model: String,
    val latencyMs: Long,
    val reply: String,
    val error: String = ""
)
```

- [ ] **Step 4: Implement settings defaults, clamping, and one-time legacy URL migration**

In `SettingsStore.kt`, use these bounds and semantics:

```kotlin
var assistantName: String
    get() = prefs.getString("assistant_name", "小智") ?: "小智"
    set(value) = prefs.edit().putString("assistant_name", value.trim().ifBlank { "小智" }).apply()

var wakePhrase: String
    get() = prefs.getString("wake_phrase", "小智小智") ?: "小智小智"
    set(value) = prefs.edit().putString("wake_phrase", value.trim().ifBlank { "小智小智" }).apply()

var ttsSpeechRate: Float
    get() = prefs.getFloat("tts_speech_rate", 1.0f).coerceIn(0.6f, 1.6f)
    set(value) = prefs.edit().putFloat("tts_speech_rate", value.coerceIn(0.6f, 1.6f)).apply()

var ttsPitch: Float
    get() = prefs.getFloat("tts_pitch", 1.0f).coerceIn(0.6f, 1.4f)
    set(value) = prefs.edit().putFloat("tts_pitch", value.coerceIn(0.6f, 1.4f)).apply()
```

Implement Base URL migration so these examples produce these exact results:

```text
https://api.example.com/v1/chat/completions -> https://api.example.com
https://api.example.com/v1/responses        -> https://api.example.com
https://api.example.com/v1                  -> https://api.example.com
https://api.example.com/                    -> https://api.example.com
```

Store a Boolean migration marker such as `v060_api_base_migrated=true` so existing settings are not repeatedly rewritten.

- [ ] **Step 5: Add direct-install package visibility and foreground location permissions**

Add exactly these manifest permissions and do not add background location:

```xml
<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

Keep all existing `<queries>` entries.

- [ ] **Step 6: Re-run the settings test**

Run:

```bash
python3 tools/test_v060_settings.py
python3 tools/test_v050_session.py
```

Expected: both PASS.

- [ ] **Step 7: Commit the settings foundation**

```bash
git add app/src/main/java/com/lchuang/xiaozhimobile/AiModels.kt \
        app/src/main/java/com/lchuang/xiaozhimobile/SettingsStore.kt \
        app/src/main/AndroidManifest.xml tools/test_v060_settings.py
git commit -m "feat: add v0.6 assistant settings foundation"
```

---

### Task 2: Replace Boolean app opening with complete discovery, diagnostics, and structured launching

**Files:**
- Create: `app/src/main/java/com/lchuang/xiaozhimobile/AppLauncher.kt`
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/InstalledAppRegistry.kt:8-69`
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/AppNameMatcher.kt`
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/PhoneController.kt:11-107`
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/CommandRouter.kt`
- Test: `tools/test_v060_app_launch.py`

**Interfaces:**
- `InstalledAppRegistry.discover(force: Boolean = false): List<AppEntry>` returns merged launcher-query + installed-package entries.
- `InstalledAppRegistry.resolveDetailed(name: String, aliasesRaw: String = ""): AppResolution` returns match type, score, resolved entry, and explanation.
- `InstalledAppRegistry.lastResolutionExplanation(): String` exposes the last diagnostic string for the UI.
- `AppLauncher.launch(entry: InstalledAppRegistry.AppEntry): AppLaunchResult` returns a typed result.
- `PhoneController.openApp(appName: String): AppLaunchResult` replaces Boolean-only success/failure.
- `CommandRouter.Result.success` remains the public success flag; it maps structured launch failures to concise user replies.

- [ ] **Step 1: Write a failing app-discovery/launch source test**

Create `tools/test_v060_app_launch.py`:

```python
from pathlib import Path

root = Path(__file__).resolve().parents[1]
registry = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/InstalledAppRegistry.kt').read_text('utf-8')
launcher_path = root / 'app/src/main/java/com/lchuang/xiaozhimobile/AppLauncher.kt'
phone = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/PhoneController.kt').read_text('utf-8')
router = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/CommandRouter.kt').read_text('utf-8')

checks = {
    'installed package fallback': 'getInstalledApplications' in registry or 'getInstalledPackages' in registry,
    'launcher activities': 'launchActivities' in registry,
    'discovery source': 'AppDiscoverySource' in registry,
    'detailed resolution': 'resolveDetailed' in registry and 'AppResolution' in registry,
    'launcher class': launcher_path.exists(),
    'structured phone launch': 'AppLaunchResult' in phone,
    'known amap alias': '高德导航' in router or '高德导航' in (root / 'app/src/main/java/com/lchuang/xiaozhimobile/AppNameMatcher.kt').read_text('utf-8'),
}
failed = [k for k,v in checks.items() if not v]
if failed:
    raise SystemExit('missing app launch features: ' + ', '.join(failed))
print('PASS: v0.6 app discovery and launch source')
```

- [ ] **Step 2: Run the app test and verify red state**

```bash
python3 tools/test_v060_app_launch.py
```

Expected: FAIL because `AppLauncher.kt` and the structured types are absent.

- [ ] **Step 3: Expand `AppEntry` and merge discovery sources**

Use these types in `InstalledAppRegistry.kt`:

```kotlin
enum class AppDiscoverySource { LAUNCHER_QUERY, INSTALLED_PACKAGES, KNOWN_FALLBACK }
enum class AppMatchType { USER_ALIAS, EXACT, KNOWN_ALIAS, CONTAINS, FUZZY, NONE }

data class AppEntry(
    val label: String,
    val packageName: String,
    val normalizedLabel: String,
    val launchActivities: List<String>,
    val source: AppDiscoverySource
)

data class AppResolution(
    val requested: String,
    val normalizedQuery: String,
    val entry: AppEntry?,
    val matchType: AppMatchType,
    val score: Double,
    val explanation: String
)
```

`discover()` must merge:

1. current `ACTION_MAIN + CATEGORY_LAUNCHER` query;
2. `PackageManager.getInstalledApplications(...)` under `QUERY_ALL_PACKAGES`;
3. only installed applications that can resolve at least one launchable activity or `getLaunchIntentForPackage`.

Deduplicate by package name, prefer `LAUNCHER_QUERY` metadata when both sources describe the same package, and exclude the XiaoZhi package itself.

- [ ] **Step 4: Add deterministic known aliases before fuzzy matching**

Extend `AppNameMatcher` with a map containing at least:

```kotlin
private val knownAliases = mapOf(
    "高德导航" to "高德地图",
    "高德" to "高德地图",
    "b站" to "哔哩哔哩",
    "扣扣" to "qq",
    "网易云" to "网易云音乐",
    "wps" to "wps"
)
```

Resolution order must be user alias -> exact -> known alias -> contains -> fuzzy. Do not let fuzzy matching override an exact alias match.

- [ ] **Step 5: Implement `AppLauncher` with explicit failure reasons**

Create:

```kotlin
package com.lchuang.xiaozhimobile

enum class AppLaunchError {
    PACKAGE_NOT_VISIBLE,
    PACKAGE_NOT_INSTALLED,
    NO_LAUNCH_ACTIVITY,
    START_ACTIVITY_FAILED
}

sealed class AppLaunchResult {
    data class Success(val packageName: String, val label: String) : AppLaunchResult()
    data class Failure(val error: AppLaunchError, val detail: String) : AppLaunchResult()
}
```

`launch()` attempts in this exact order:

1. `getLaunchIntentForPackage`;
2. `ACTION_MAIN + CATEGORY_LAUNCHER` scoped with `setPackage`;
3. explicit `ComponentName(packageName, activityClassName)` from `entry.launchActivities`.

Every launch intent adds `Intent.FLAG_ACTIVITY_NEW_TASK`. Catch `ActivityNotFoundException`, `SecurityException`, and generic `RuntimeException`, and return sanitized `START_ACTIVITY_FAILED` detail; never leak a stack trace to overlay text.

- [ ] **Step 6: Route PhoneController and CommandRouter through the structured result**

Change `PhoneController.openApp()` to return `AppLaunchResult`. In `CommandRouter`, map:

```text
Success -> handled=true, success=true, reply="正在打开<真实label>"
PACKAGE_NOT_VISIBLE/PACKAGE_NOT_INSTALLED -> success=false, reply="没有找到可启动的“<请求名>”"
NO_LAUNCH_ACTIVITY/START_ACTIVITY_FAILED -> success=false, reply="找到了“<请求名>”，但没有成功启动"
```

Do not return the generic unknown-command phrase from `CommandRouter`; `WakeService` owns the global fallback when a device action fails.

- [ ] **Step 7: Re-run new and old app tests**

```bash
python3 tools/test_v060_app_launch.py
python3 tools/test_v050_app_registry.py
```

Expected: both PASS.

- [ ] **Step 8: Commit app discovery/launch work**

```bash
git add app/src/main/java/com/lchuang/xiaozhimobile/AppLauncher.kt \
        app/src/main/java/com/lchuang/xiaozhimobile/InstalledAppRegistry.kt \
        app/src/main/java/com/lchuang/xiaozhimobile/AppNameMatcher.kt \
        app/src/main/java/com/lchuang/xiaozhimobile/PhoneController.kt \
        app/src/main/java/com/lchuang/xiaozhimobile/CommandRouter.kt \
        tools/test_v060_app_launch.py
git commit -m "feat: add complete installed app launch diagnostics"
```

---

### Task 3: Add one-shot foreground location and Amap/Baidu/system map execution

**Files:**
- Create: `app/src/main/java/com/lchuang/xiaozhimobile/LocationProvider.kt`
- Create: `app/src/main/java/com/lchuang/xiaozhimobile/MapController.kt`
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/PhoneController.kt`
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/CommandRouter.kt`
- Test: `tools/test_v060_map.py`

**Interfaces:**
- `LocationProvider.getCurrentLocation(timeoutMs: Long = 4000L, callback: (Result<Location>) -> Unit)` performs one foreground lookup and stops.
- `MapController.openMap(preference: MapAppPreference): MapActionResult`.
- `MapController.navigate(destination: String, preference: MapAppPreference): MapActionResult`.
- `MapController.searchNearby(keyword: String, preference: MapAppPreference, callback: (MapActionResult) -> Unit)` requests location only for this user action.
- `MapActionResult` carries `success`, `usedMap`, `message`, and a sanitized failure code.

- [ ] **Step 1: Write failing map URI and permission-flow source tests**

Create `tools/test_v060_map.py`:

```python
from pathlib import Path

root = Path(__file__).resolve().parents[1]
map_file = root / 'app/src/main/java/com/lchuang/xiaozhimobile/MapController.kt'
loc_file = root / 'app/src/main/java/com/lchuang/xiaozhimobile/LocationProvider.kt'
router = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/CommandRouter.kt').read_text('utf-8')
if not map_file.exists() or not loc_file.exists():
    raise SystemExit('map/location classes missing')
text = map_file.read_text('utf-8')
checks = [
    'androidamap://keywordNavi',
    'androidamap://poi',
    'com.autonavi.minimap',
    'baidumap://map/navi',
    'baidumap://map/place/nearby',
    'com.baidu.BaiduMap',
    'geo:0,0?q=',
    'searchNearby',
]
for value in checks:
    if value not in text:
        raise SystemExit('map feature missing: ' + value)
for phrase in ['附近', '高德导航', '百度地图']:
    if phrase not in router:
        raise SystemExit('router map phrase missing: ' + phrase)
loc = loc_file.read_text('utf-8')
if 'ACCESS_FINE_LOCATION' not in loc or 'ACCESS_BACKGROUND_LOCATION' in loc:
    raise SystemExit('location permission handling incorrect')
print('PASS: v0.6 map and foreground location source')
```

- [ ] **Step 2: Run the map test and verify it fails**

```bash
python3 tools/test_v060_map.py
```

Expected: FAIL because the map/location files do not exist.

- [ ] **Step 3: Implement one-shot `LocationProvider` using platform APIs only**

Use `LocationManager`, preferring `GPS_PROVIDER` when fine permission is granted and `NETWORK_PROVIDER` otherwise. On API 30+, call `getCurrentLocation`; on API 26-29 use a single-update listener and remove it immediately after the first result. Use a `Handler` timeout of 4000 ms. If a fresh fix cannot be obtained, return the best last-known location if one exists; otherwise return a typed failure string `PERMISSION_DENIED`, `PROVIDER_UNAVAILABLE`, or `TIMEOUT`.

Do not schedule repeated updates and do not retain location after the callback completes.

- [ ] **Step 4: Implement map URI builders and map availability checks**

For Amap use these concrete forms:

```text
androidamap://keywordNavi?sourceApplication=XiaoZhiMobile&keyword=<encoded>&style=2
androidamap://poi?sourceApplication=XiaoZhiMobile&keywords=<encoded>&dev=1
```

When location exists, add a small latitude/longitude bounding box to the Amap POI URI using `lat1/lon1/lat2/lon2`; keep `dev=1` because Android platform location is WGS84 and Amap should perform the coordinate conversion.

For Baidu use:

```text
baidumap://map/navi?query=<encoded>&coord_type=wgs84&src=andr.lchuang.xiaozhimobile
baidumap://map/place/nearby?query=<encoded>&center=<lat>,<lon>&coord_type=wgs84&radius=5000&src=andr.lchuang.xiaozhimobile
```

For system fallback use:

```text
geo:0,0?q=<encoded destination>
geo:<lat>,<lon>?q=<encoded keyword>
```

Always scope Amap/Baidu intents to their known package. The generic geo intent must remain unscoped.

- [ ] **Step 5: Apply saved/explicit map preference rules**

Use this selection order:

```text
explicit AMAP request  -> Amap only, then system fallback if unavailable
explicit BAIDU request -> Baidu only, then system fallback if unavailable
AUTO                    -> Amap -> Baidu -> system
SYSTEM                  -> system only
```

`searchNearby()` obtains location only after the command is parsed as nearby search. If permission is denied or location times out, open the chosen map app with keyword-only search and return a message that the map app will determine the location.

- [ ] **Step 6: Extend local parsing for natural map commands**

In `CommandRouter`, add deterministic parsing before generic `open_app` parsing for:

```text
打开高德导航
打开百度地图
用高德导航到广州南站
用百度地图导航到深圳北站
导航到广州南站
附近帮我找商场
找附近加油站
用高德找附近医院
```

Return a structured action request or call the new PhoneController map methods. Keep generic app launch separate so `打开高德导航` does not resolve to an app label before the navigation parser sees it.

- [ ] **Step 7: Run map and prior routing tests**

```bash
python3 tools/test_v060_map.py
python3 tools/test_voice_command_normalizer.py
python3 tools/test_v050_voice_flow.py
```

Expected: all PASS.

- [ ] **Step 8: Commit map/location support**

```bash
git add app/src/main/java/com/lchuang/xiaozhimobile/LocationProvider.kt \
        app/src/main/java/com/lchuang/xiaozhimobile/MapController.kt \
        app/src/main/java/com/lchuang/xiaozhimobile/PhoneController.kt \
        app/src/main/java/com/lchuang/xiaozhimobile/CommandRouter.kt \
        tools/test_v060_map.py
git commit -m "feat: add map navigation and nearby search"
```

---

### Task 4: Compile user wake phrases offline and apply runtime KWS without APK rebuild

**Files:**
- Create: `app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseCompiler.kt`
- Create: `app/src/main/java/com/lchuang/xiaozhimobile/Pinyin4jProvider.kt`
- Create: `app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseManager.kt`
- Modify: `app/build.gradle.kts:41-43`
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt:65-163`
- Modify: `THIRD_PARTY_NOTICE.md`
- Test: `tools/test_v060_wake_phrase.py`

**Interfaces:**
- `interface PronunciationProvider { fun syllables(ch: Char): List<String> }`.
- `WakePhraseCompiler.compile(phrase: String, tokenInventory: Set<String>, provider: PronunciationProvider): CompileResult`.
- `CompileResult.Success.runtimeKeyword` format is `<space-separated sherpa tokens> @<original phrase>`.
- `WakePhraseManager.applyPhrase(phrase: String): Result<AppliedWakePhrase>` validates, compiles, recreates the stream, and only updates active state after stream creation succeeds.
- `WakePhraseManager.activePhrase(): String` returns the phrase actually accepted by KWS.

- [ ] **Step 1: Write the failing pure Kotlin wake compiler test**

Create `tools/test_v060_wake_phrase.py` that compiles `WakePhraseCompiler.kt` with an injected fake provider:

```python
from pathlib import Path
import subprocess, tempfile, textwrap

root = Path(__file__).resolve().parents[1]
compiler = root / 'app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseCompiler.kt'
manager = root / 'app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseManager.kt'
wake = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt').read_text('utf-8')
if not compiler.exists() or not manager.exists():
    raise SystemExit('wake phrase classes missing')
with tempfile.TemporaryDirectory() as td:
    td = Path(td)
    harness = td / 'WakeHarness.kt'
    harness.write_text(textwrap.dedent('''
        import com.lchuang.xiaozhimobile.*
        fun main() {
            val provider = object : PronunciationProvider {
                override fun syllables(ch: Char): List<String> = mapOf(
                    '小' to listOf("xiǎo"), '智' to listOf("zhì"),
                    '白' to listOf("bái"), '在' to listOf("zài"), '吗' to listOf("ma")
                )[ch] ?: emptyList()
            }
            val tokens = setOf("x", "iǎo", "zh", "ì", "b", "ái", "z", "ài", "m", "a")
            val c = WakePhraseCompiler()
            val a = c.compile("小智小智", tokens, provider) as CompileResult.Success
            check(a.runtimeKeyword.endsWith("@小智小智"))
            val b = c.compile("小白小白", tokens, provider) as CompileResult.Success
            check(b.runtimeKeyword.contains("b ái"))
            check(c.compile("白", tokens, provider) is CompileResult.Failure)
            println("PASS: wake phrase compiler")
        }
    '''), encoding='utf-8')
    jar = td / 'wake.jar'
    subprocess.run(['kotlinc', str(compiler), str(harness), '-include-runtime', '-d', str(jar)], check=True)
    subprocess.run(['java', '-jar', str(jar)], check=True)
for value in ['createStream(', 'settings.wakePhrase', 'activePhrase']:
    if value not in wake and value not in manager.read_text('utf-8'):
        raise SystemExit('runtime KWS integration missing: ' + value)
print('PASS: v0.6 runtime wake phrase source')
```

- [ ] **Step 2: Run the wake phrase test and verify red state**

```bash
python3 tools/test_v060_wake_phrase.py
```

Expected: FAIL because the new files do not exist.

- [ ] **Step 3: Add pinyin4j as the only new pronunciation dependency**

Add:

```kotlin
implementation("com.belerweb:pinyin4j:2.5.1")
```

Keep sherpa-onnx v1.13.4 unchanged. Add pinyin4j and its BSD license notice to `THIRD_PARTY_NOTICE.md`.

- [ ] **Step 4: Implement pure token segmentation in `WakePhraseCompiler`**

Requirements for the compiler:

```text
trim input
reject blank input
reject 1-character phrase as Failure("唤醒短语至少 2 个字符")
allow 2-12 characters, but return a warning string for >6
for each Chinese character, ask PronunciationProvider for ordered pronunciations
for each pronunciation, split the syllable into the fewest valid sherpa tokens using longest-prefix dynamic programming
select the first pronunciation that fully segments against tokenInventory
fail without changing active phrase if any character cannot be represented
emit tokens joined by spaces + " @" + original phrase
```

Do not hardcode only `小智`/`小白`; the token inventory and pronunciation provider drive compilation.

- [ ] **Step 5: Implement `Pinyin4jProvider` with tone marks**

Configure pinyin4j with:

```kotlin
HanyuPinyinOutputFormat().apply {
    caseType = HanyuPinyinCaseType.LOWERCASE
    toneType = HanyuPinyinToneType.WITH_TONE_MARK
    vCharType = HanyuPinyinVCharType.WITH_U_UNICODE
}
```

Return all pronunciations in library order, de-duplicated. Non-Chinese characters may be accepted only when their normalized token sequence exists in the sherpa token inventory; otherwise return no pronunciation so the compiler fails safely.

- [ ] **Step 6: Implement `WakePhraseManager` around sherpa runtime keywords**

Load the KWS token inventory from `assets.open("$KWS_MODEL_DIR/tokens.txt")`. Keep the bundled `keywords.txt` configured because sherpa requires a keyword source when creating the spotter. For every KWS stream, also pass the compiled runtime phrase:

```kotlin
val newStream = spotter.createStream(compiled.runtimeKeyword)
```

Because runtime keywords are added to the bundled default keyword, `WakeService` must accept a detection only when `result.keyword == activePhrase`; if the old bundled `小智小智` is detected after the user switched to another phrase, reset the stream and continue listening instead of waking.

Apply a new phrase atomically: compile -> create new stream -> replace active stream -> update `activePhrase`. If any step fails, keep the previous stream/phrase.

- [ ] **Step 7: Integrate assistant name/wake phrase into notification and service restart flow**

On service start, apply `settings.wakePhrase`. Notification text must use:

```text
全离线语音已开启 · 说“<activePhrase>”
```

When settings request a phrase change while the service is running, support an explicit service action such as `ACTION_APPLY_WAKE_SETTINGS`; stop KWS capture, apply the new phrase, and restart capture without stopping the whole foreground service.

- [ ] **Step 8: Run wake and old KWS tests**

```bash
python3 tools/test_v060_wake_phrase.py
python3 tools/test_local_asr_source.py
python3 tools/test_v040_voice_flow.py
```

Expected: all PASS.

- [ ] **Step 9: Commit dynamic wake phrase support**

```bash
git add app/build.gradle.kts THIRD_PARTY_NOTICE.md \
        app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseCompiler.kt \
        app/src/main/java/com/lchuang/xiaozhimobile/Pinyin4jProvider.kt \
        app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseManager.kt \
        app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt \
        tools/test_v060_wake_phrase.py
git commit -m "feat: add runtime custom wake phrases"
```

---

### Task 5: Add real device TTS voice selection, preview, speech rate, and pitch

**Files:**
- Create: `app/src/main/java/com/lchuang/xiaozhimobile/TtsVoiceManager.kt`
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt`
- Test: `tools/test_v060_tts.py`

**Interfaces:**
- `TtsVoiceManager.availableVoices(): List<VoiceOption>`.
- `TtsVoiceManager.applySavedSettings(): VoiceApplyResult`.
- `TtsVoiceManager.applyVoice(name: String, rate: Float, pitch: Float): VoiceApplyResult`.
- `TtsVoiceManager.preview(text: String, onDone: () -> Unit)`.
- `VoiceOption` includes stable voice name, locale tag, network requirement, and display label.

- [ ] **Step 1: Write failing TTS source assertions**

Create `tools/test_v060_tts.py`:

```python
from pathlib import Path
root = Path(__file__).resolve().parents[1]
manager = root / 'app/src/main/java/com/lchuang/xiaozhimobile/TtsVoiceManager.kt'
wake = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt').read_text('utf-8')
if not manager.exists():
    raise SystemExit('TtsVoiceManager.kt missing')
text = manager.read_text('utf-8')
for value in ['availableVoices', 'setVoice', 'setSpeechRate', 'setPitch', 'preview', 'Locale.CHINESE']:
    if value not in text:
        raise SystemExit('TTS feature missing: ' + value)
if 'TtsVoiceManager' not in wake:
    raise SystemExit('WakeService not using TtsVoiceManager')
print('PASS: v0.6 TTS voice source')
```

- [ ] **Step 2: Run and confirm it fails**

```bash
python3 tools/test_v060_tts.py
```

Expected: FAIL because `TtsVoiceManager.kt` does not exist.

- [ ] **Step 3: Implement voice enumeration and ranking**

`availableVoices()` reads `TextToSpeech.voices`, filters voices whose locale language is `zh`, sorts local/offline-capable voices before network-required voices, then sorts by locale/name. If no Chinese voice is present, return the engine default voice as a final option and mark it accordingly.

Use a stable `VoiceOption.name` equal to Android `Voice.name`; that string is what `SettingsStore.ttsVoiceName` persists.

- [ ] **Step 4: Implement safe voice/rate/pitch application**

Apply persisted values with these exact bounds:

```text
speech rate 0.6 .. 1.6
pitch       0.6 .. 1.4
```

If the saved voice name no longer exists, choose the highest-ranked Chinese voice, apply it, and clear/update the saved name. Always call `setSpeechRate` and `setPitch` after selecting the voice so engine changes do not reset those values.

- [ ] **Step 5: Integrate WakeService TTS through the manager**

`WakeService.onInit()` must initialize `TtsVoiceManager`, call `applySavedSettings()`, and set `ttsReady=true` only after a usable engine state exists. `speakThen()` continues to own utterance completion sequencing but uses the configured engine state. Do not let preview callbacks modify `conversationActive`, `SessionController`, or KWS state.

- [ ] **Step 6: Run TTS and voice-flow regression tests**

```bash
python3 tools/test_v060_tts.py
python3 tools/test_v050_voice_flow.py
```

Expected: both PASS.

- [ ] **Step 7: Commit TTS manager**

```bash
git add app/src/main/java/com/lchuang/xiaozhimobile/TtsVoiceManager.kt \
        app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt \
        tools/test_v060_tts.py
git commit -m "feat: add configurable system TTS voices"
```

---

### Task 6: Convert AI configuration to Base URL and add deterministic endpoint probing/test results

**Files:**
- Create: `app/src/main/java/com/lchuang/xiaozhimobile/AiEndpointResolver.kt`
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/AiClient.kt:1-73`
- Test: `tools/test_v060_ai_endpoint.py`

**Interfaces:**
- `AiEndpointResolver.normalizeBaseUrl(raw: String): String`.
- `AiEndpointResolver.chatUrl(base: String): String`.
- `AiEndpointResolver.responsesUrl(base: String): String`.
- `AiEndpointResolver.modelsUrl(base: String): String`.
- `AiClient.testEndpoint(callback: (AiEndpointTestResult) -> Unit)` sends a minimal `只回复：OK` request and never logs the key.
- `AiClient.complete(messages: List<ConversationMessage>, tools: List<AiToolDefinition>, callback: (Result<RawAiResponse>) -> Unit)` becomes the low-level request entry point used by `AiOrchestrator`.

- [ ] **Step 1: Write an executable pure Kotlin endpoint resolver test plus source assertions**

Create `tools/test_v060_ai_endpoint.py`:

```python
from pathlib import Path
import subprocess, tempfile, textwrap
root = Path(__file__).resolve().parents[1]
resolver = root / 'app/src/main/java/com/lchuang/xiaozhimobile/AiEndpointResolver.kt'
client = root / 'app/src/main/java/com/lchuang/xiaozhimobile/AiClient.kt'
if not resolver.exists():
    raise SystemExit('AiEndpointResolver.kt missing')
with tempfile.TemporaryDirectory() as td:
    td = Path(td)
    harness = td / 'EndpointHarness.kt'
    harness.write_text(textwrap.dedent('''
        import com.lchuang.xiaozhimobile.AiEndpointResolver
        fun main() {
            check(AiEndpointResolver.normalizeBaseUrl("https://a.example/") == "https://a.example")
            check(AiEndpointResolver.normalizeBaseUrl("https://a.example/v1") == "https://a.example")
            check(AiEndpointResolver.normalizeBaseUrl("https://a.example/v1/chat/completions") == "https://a.example")
            check(AiEndpointResolver.chatUrl("https://a.example") == "https://a.example/v1/chat/completions")
            check(AiEndpointResolver.responsesUrl("https://a.example/v1") == "https://a.example/v1/responses")
            println("PASS: AI endpoint resolver")
        }
    '''), encoding='utf-8')
    jar = td / 'endpoint.jar'
    subprocess.run(['kotlinc', str(resolver), str(harness), '-include-runtime', '-d', str(jar)], check=True)
    subprocess.run(['java', '-jar', str(jar)], check=True)
text = client.read_text('utf-8')
for value in ['testEndpoint', 'CHAT_COMPLETIONS', 'RESPONSES', 'latencyMs', '只回复：OK']:
    if value not in text:
        raise SystemExit('AI endpoint feature missing: ' + value)
if 'println(settings.apiKey)' in text or 'Log.' in text and 'apiKey' in text:
    raise SystemExit('API key logging detected')
print('PASS: v0.6 AI endpoint source')
```

- [ ] **Step 2: Run and verify endpoint test fails**

```bash
python3 tools/test_v060_ai_endpoint.py
```

Expected: FAIL because `AiEndpointResolver.kt` is absent.

- [ ] **Step 3: Implement exact URL normalization**

`normalizeBaseUrl()` must strip query/fragment, trailing slash, and one terminal OpenAI path from this set:

```text
/v1/chat/completions
/v1/responses
/v1/models
/v1
```

Reject non-HTTP(S) schemes by throwing `IllegalArgumentException("Base URL 必须使用 http 或 https")`.

- [ ] **Step 4: Refactor AI HTTP request code into mode-specific minimal requests**

Keep `HttpURLConnection`. Add helpers that build:

Chat Completions test body:

```json
{"model":"<model>","messages":[{"role":"user","content":"只回复：OK"}],"stream":false,"max_tokens":8,"temperature":0}
```

Responses test body:

```json
{"model":"<model>","input":"只回复：OK","max_output_tokens":8}
```

Parse chat text from `choices[0].message.content`. Parse Responses text by first accepting top-level `output_text`, then walking `output[*].content[*].text` when needed.

- [ ] **Step 5: Implement AUTO endpoint detection by actual model request**

For `ApiMode.AUTO`, try Chat Completions first and then Responses only when Chat fails with endpoint/protocol-like failures (`404`, `405`, malformed expected shape). Do not fall through on authentication/limit/server failures such as `401`, `403`, `429`, or `5xx`; report those directly because trying a second endpoint would hide the real problem.

`/v1/models` may be used as supplemental diagnostics but must not mark the endpoint usable by itself.

- [ ] **Step 6: Sanitize error results**

Map errors to short summaries:

```text
401 -> API Key 无效或未授权
403 -> 接口拒绝访问
404 -> 接口地址不支持
429 -> 请求过多或额度不足
5xx -> 上游服务异常
SocketTimeoutException -> 连接或响应超时
UnknownHostException -> 域名解析失败
```

Never concatenate request headers or the API key into `AiEndpointTestResult.error`.

- [ ] **Step 7: Run endpoint tests**

```bash
python3 tools/test_v060_ai_endpoint.py
```

Expected: PASS.

- [ ] **Step 8: Commit endpoint layer**

```bash
git add app/src/main/java/com/lchuang/xiaozhimobile/AiEndpointResolver.kt \
        app/src/main/java/com/lchuang/xiaozhimobile/AiClient.kt \
        tools/test_v060_ai_endpoint.py
git commit -m "feat: add AI base URL detection and testing"
```

---

### Task 7: Add 8-turn session conversation memory with deterministic lifecycle

**Files:**
- Create: `app/src/main/java/com/lchuang/xiaozhimobile/AiConversationMemory.kt`
- Test: `tools/test_v060_ai_memory.py`

**Interfaces:**
- `startSession()` clears history and marks memory active.
- `addTurn(userText: String, assistantText: String)` stores one completed turn.
- `messages(): List<ConversationMessage>` returns chronological user/assistant messages for at most 8 completed turns.
- `clear()` erases all history.
- Assistant text stored in history is capped at 1200 characters per turn.

- [ ] **Step 1: Write the failing Kotlin/JVM memory behavior test**

Create `tools/test_v060_ai_memory.py`:

```python
from pathlib import Path
import subprocess, tempfile, textwrap
root = Path(__file__).resolve().parents[1]
memory = root / 'app/src/main/java/com/lchuang/xiaozhimobile/AiConversationMemory.kt'
models = root / 'app/src/main/java/com/lchuang/xiaozhimobile/AiModels.kt'
if not memory.exists():
    raise SystemExit('AiConversationMemory.kt missing')
with tempfile.TemporaryDirectory() as td:
    td = Path(td)
    harness = td / 'MemoryHarness.kt'
    harness.write_text(textwrap.dedent('''
        import com.lchuang.xiaozhimobile.*
        fun main() {
            val m = AiConversationMemory(maxTurns = 8)
            m.startSession()
            for (i in 1..10) m.addTurn("u$i", "a$i")
            val all = m.messages()
            check(all.size == 16)
            check(all.first().content == "u3")
            check(all.last().content == "a10")
            m.clear()
            check(m.messages().isEmpty())
            println("PASS: AI conversation memory")
        }
    '''), encoding='utf-8')
    jar = td / 'memory.jar'
    subprocess.run(['kotlinc', str(models), str(memory), str(harness), '-include-runtime', '-d', str(jar)], check=True)
    subprocess.run(['java', '-jar', str(jar)], check=True)
```

- [ ] **Step 2: Run and verify failure**

```bash
python3 tools/test_v060_ai_memory.py
```

Expected: FAIL because the class is absent.

- [ ] **Step 3: Implement bounded completed-turn storage**

Use an `ArrayDeque<Turn>` where each `Turn` has user and assistant text. `addTurn()` appends, then removes from the head while `size > maxTurns`. Trim user text and cap assistant text with `.take(1200)`. Do not add a half-completed turn before the AI/tool execution succeeds.

- [ ] **Step 4: Run memory test**

```bash
python3 tools/test_v060_ai_memory.py
```

Expected: PASS.

- [ ] **Step 5: Commit memory**

```bash
git add app/src/main/java/com/lchuang/xiaozhimobile/AiConversationMemory.kt tools/test_v060_ai_memory.py
git commit -m "feat: add session scoped AI memory"
```

---

### Task 8: Add SafeToolExecutor and strict allowlist validation before any AI device action

**Files:**
- Create: `app/src/main/java/com/lchuang/xiaozhimobile/SafeToolExecutor.kt`
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/AiModels.kt`
- Test: `tools/test_v060_safe_tools.py`

**Interfaces:**
- `SafeToolExecutor.execute(call: AiToolCall, callback: (ToolExecutionResult) -> Unit)` is the only AI-to-device action bridge.
- `ToolExecutionResult` contains `success`, `spokenText`, `debugCode`.
- `open_app` accepts an app display name only; package names supplied by AI are not trusted as execution authority.
- `open_web` accepts only `http`, `https`, or plain search text; reject `javascript:`, `file:`, `content:`, `intent:`, and custom arbitrary schemes.
- `set_volume` accepts numeric 0-100; reject values outside the range instead of silently executing unexpected values.

- [ ] **Step 1: Write failing allowlist/forbidden-category source tests**

Create `tools/test_v060_safe_tools.py`:

```python
from pathlib import Path
root = Path(__file__).resolve().parents[1]
path = root / 'app/src/main/java/com/lchuang/xiaozhimobile/SafeToolExecutor.kt'
if not path.exists():
    raise SystemExit('SafeToolExecutor.kt missing')
text = path.read_text('utf-8')
allowed = ['open_app','navigate','search_nearby','open_web','media_play','media_pause','media_next','media_previous','volume_up','volume_down','set_volume','flashlight_on','flashlight_off']
for name in allowed:
    if '"' + name + '"' not in text:
        raise SystemExit('allowlisted tool missing: ' + name)
for forbidden in ['delete_all_files','send_message','transfer_money','install_app','shell_command']:
    if forbidden in allowed:
        raise SystemExit('forbidden tool accidentally allowlisted: ' + forbidden)
for scheme in ['javascript:', 'file:', 'content:', 'intent:']:
    if scheme not in text:
        raise SystemExit('dangerous web scheme rejection missing: ' + scheme)
print('PASS: v0.6 safe tool allowlist source')
```

- [ ] **Step 2: Run and verify failure**

```bash
python3 tools/test_v060_safe_tools.py
```

Expected: FAIL because `SafeToolExecutor.kt` does not exist.

- [ ] **Step 3: Define tool argument extraction helpers that reject wrong types**

Implement private helpers such as:

```kotlin
private fun stringArg(call: AiToolCall, name: String): String?
private fun intArg(call: AiToolCall, name: String): Int?
private fun mapPreferenceArg(call: AiToolCall): MapAppPreference
```

A required string must be non-blank after trim. `set_volume` requires an integer in `0..100`; values outside range return `INVALID_ARGS` without touching AudioManager.

- [ ] **Step 4: Implement the exact allowlist switch**

Use one exhaustive `when (call.tool)` with only the 13 approved names. The `else` branch returns `REJECTED_NOT_ALLOWED`. Do not expose any generic `startIntent`, `launchPackage`, shell, reflection, URI, or component execution tool.

`open_app(name)` must call `PhoneController.openApp(name)` so the installed registry validates the actual package. `navigate`/`search_nearby` must call `MapController`; map preference is optional and mapped only from `高德地图/amap`, `百度地图/baidu`, `system`, or `auto`.

- [ ] **Step 5: Implement safe browser policy**

If `query_or_url` parses as a URI and has a scheme, accept only `http` or `https`. If it has no scheme, pass it as a search query. Explicitly reject the dangerous scheme strings asserted in the test.

- [ ] **Step 6: Run safe-tool test**

```bash
python3 tools/test_v060_safe_tools.py
```

Expected: PASS.

- [ ] **Step 7: Commit safe execution boundary**

```bash
git add app/src/main/java/com/lchuang/xiaozhimobile/SafeToolExecutor.kt \
        app/src/main/java/com/lchuang/xiaozhimobile/AiModels.kt \
        tools/test_v060_safe_tools.py
git commit -m "feat: add safe AI phone tool executor"
```

---

### Task 9: Add AiOrchestrator with native tool calls, strict JSON fallback, and local-first planning

**Files:**
- Create: `app/src/main/java/com/lchuang/xiaozhimobile/AiOrchestrator.kt`
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/AiClient.kt`
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/AiModels.kt`
- Test: `tools/test_v060_ai_orchestrator.py`

**Interfaces:**
- `AiOrchestrator.respond(userText: String, memory: AiConversationMemory, callback: (Result<AiOutcome>) -> Unit)`.
- `AiClient` returns a raw normalized response model that can expose assistant text and native tool calls independent of Chat/Responses endpoint shape.
- `AiOrchestrator` validates model output into only `AiOutcome.Reply` or `AiOutcome.Tool`.

- [ ] **Step 1: Write failing orchestrator source tests**

Create `tools/test_v060_ai_orchestrator.py`:

```python
from pathlib import Path
root = Path(__file__).resolve().parents[1]
path = root / 'app/src/main/java/com/lchuang/xiaozhimobile/AiOrchestrator.kt'
if not path.exists():
    raise SystemExit('AiOrchestrator.kt missing')
text = path.read_text('utf-8')
for value in ['tool_calls', '"type"', '"tool_call"', '"reply"', 'AiConversationMemory']:
    if value not in text:
        raise SystemExit('orchestrator feature missing: ' + value)
for name in ['open_app','navigate','search_nearby','open_web','set_volume']:
    if name not in text:
        raise SystemExit('tool schema missing: ' + name)
print('PASS: v0.6 AI orchestrator source')
```

- [ ] **Step 2: Run and verify failure**

```bash
python3 tools/test_v060_ai_orchestrator.py
```

Expected: FAIL because the orchestrator does not exist.

- [ ] **Step 3: Define the model-facing tool schemas without dangerous parameters**

Tool schema definitions must expose only these arguments:

```text
open_app:       name:string
navigate:       destination:string, mapApp?:string
search_nearby:  keyword:string, mapApp?:string
open_web:       query_or_url:string
set_volume:     percent:integer 0..100
other media/flashlight/volume +/- tools: no arguments
```

Do not include package name, activity class, arbitrary URI, Android intent, component, shell command, file path, contact, message recipient, or payment fields.

- [ ] **Step 4: Build a concise system instruction for reply-or-tool behavior**

The planner prompt must state that the model either replies normally or chooses one approved tool, never claims an action succeeded before local execution, and uses Chinese user-visible text. Include the assistant name from `SettingsStore.assistantName` in the normal-conversation persona, but do not include the API key or hidden device diagnostics.

- [ ] **Step 5: Parse native tool calls first**

For Chat Completions, parse `choices[0].message.tool_calls[0].function.name` and JSON `arguments`. For Responses, normalize function-call output entries to the same `AiToolCall`. If more than one tool call is returned in v0.6.0, execute only the first validated call and ignore the rest; multi-step autonomous loops are intentionally out of scope.

- [ ] **Step 6: Implement strict JSON fallback when native tools are unsupported/malformed**

Request a response shaped as one of:

```json
{"type":"reply","text":"..."}
```

or:

```json
{"type":"tool_call","tool":"navigate","args":{"destination":"广州南站","mapApp":"高德地图"}}
```

Strip only Markdown code fences around the whole JSON payload. Reject unknown `type`, missing `tool`, non-object `args`, blank reply text, or unknown tools. Do not attempt to execute free-form text that merely resembles a command.

- [ ] **Step 7: Include memory in every AI request and add history only after completion**

Send `memory.messages()` before the new user message. `AiOrchestrator` itself does not mutate memory; `WakeService` adds a completed turn after a reply is spoken or a tool execution result is known, so failed requests do not pollute context.

- [ ] **Step 8: Run orchestrator and endpoint tests**

```bash
python3 tools/test_v060_ai_orchestrator.py
python3 tools/test_v060_ai_endpoint.py
python3 tools/test_v060_ai_memory.py
python3 tools/test_v060_safe_tools.py
```

Expected: all PASS.

- [ ] **Step 9: Commit AI orchestration**

```bash
git add app/src/main/java/com/lchuang/xiaozhimobile/AiOrchestrator.kt \
        app/src/main/java/com/lchuang/xiaozhimobile/AiClient.kt \
        app/src/main/java/com/lchuang/xiaozhimobile/AiModels.kt \
        tools/test_v060_ai_orchestrator.py
git commit -m "feat: add safe AI intent orchestration"
```

---

### Task 10: Integrate local-first routing, AI planning, safe tools, memory lifecycle, and dynamic assistant identity into WakeService

**Files:**
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt:39-560`
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/CommandRouter.kt`
- Test: `tools/test_v060_voice_integration.py`

**Interfaces:**
- Wake session start calls `memory.startSession()`.
- Explicit exit, timeout exit, service destroy/restart call `memory.clear()`.
- Deterministic local command success remains AI-free and immediately re-listens.
- Local parse failure for a device-like command uses `AiOrchestrator` when AI is configured instead of immediately speaking the generic unknown-command phrase.
- AI tool output goes only through `SafeToolExecutor`.
- AI failure leaves the session alive and never blocks later local commands.

- [ ] **Step 1: Write the failing integration source test**

Create `tools/test_v060_voice_integration.py`:

```python
from pathlib import Path
root = Path(__file__).resolve().parents[1]
wake = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt').read_text('utf-8')
checks = {
    'AI orchestrator field': 'AiOrchestrator' in wake,
    'safe executor field': 'SafeToolExecutor' in wake,
    'conversation memory': 'AiConversationMemory' in wake,
    'memory session start': 'memory.startSession()' in wake,
    'memory clear': 'memory.clear()' in wake,
    'AI tool branch': 'AiOutcome.Tool' in wake,
    'AI reply branch': 'AiOutcome.Reply' in wake,
    'safe tool execution': 'safeToolExecutor.execute' in wake,
    'local immediate relisten preserved': 'continueConversationSession(immediate = true)' in wake,
    'dynamic wake notification': 'settings.wakePhrase' in wake or 'activePhrase()' in wake,
    'assistant name used': 'settings.assistantName' in wake,
}
failed = [k for k,v in checks.items() if not v]
if failed:
    raise SystemExit('missing v0.6 voice integration: ' + ', '.join(failed))
print('PASS: v0.6 voice integration source')
```

- [ ] **Step 2: Run and verify failure**

```bash
python3 tools/test_v060_voice_integration.py
```

Expected: FAIL because the orchestrator/memory/tool fields are not integrated.

- [ ] **Step 3: Construct v0.6 components once in `onCreate()`**

Create and retain one instance each of:

```text
InstalledAppRegistry
AppLauncher
LocationProvider
MapController
PhoneController
SafeToolExecutor
AiClient
AiOrchestrator
AiConversationMemory(maxTurns = 8)
WakePhraseManager
TtsVoiceManager
```

Avoid constructing a fresh registry per command so app diagnostics/cache remain meaningful during the service lifetime.

- [ ] **Step 4: Update session start/end lifecycle**

At `handleWakeDetected()`:

```kotlin
memory.startSession()
```

At `endConversationSession()`, timeout completion, explicit exit completion, `restartWakeListening()` only when it truly closes an active session, and `onDestroy()`:

```kotlin
memory.clear()
```

Do not clear memory between turns inside the same active overlay session.

- [ ] **Step 5: Replace the current `looksLikeDeviceCommand -> immediate unknown` branch**

Use this decision order in `processUtterance()`:

```text
1. normalize + explicit exit
2. deterministic CommandRouter
3. if local success -> immediate re-listen
4. if local failure/ambiguous and AI configured -> AiOrchestrator
5. if normal conversation and AI configured -> AiOrchestrator
6. if no AI -> exact unknown-command fallback
```

A recognized device request that failed local parsing is specifically allowed to use AI planning now.

- [ ] **Step 6: Execute AI outcomes safely and keep the session active**

For `AiOutcome.Reply(text)`: sanitize to one line for overlay, TTS the reply, `memory.addTurn(rawText, text)`, then continue listening.

For `AiOutcome.Tool(call)`: show `正在执行…`, call `safeToolExecutor.execute(call)`, add a completed memory turn containing a concise execution result, then:

- success -> no long TTS confirmation for app/media/map launch; show overlay result and immediately re-listen;
- failure -> speak the existing exact fallback `抱歉，我还不会这个指令，你可以换一个指令继续服务你`, then continue listening.

For AI transport/protocol failure: speak `AI 服务暂时不可用，请稍后再试`, keep the active session, and continue listening.

- [ ] **Step 7: Use dynamic identity in user-visible service text**

Replace fixed notification title/text references where appropriate with `settings.assistantName`. Overlay main greeting may stay `你好，有什么可以帮你？`, but active wake diagnostics must show `WakePhraseManager.activePhrase()` rather than hardcoded `小智小智`.

- [ ] **Step 8: Run integration + full previous voice regressions**

```bash
python3 tools/test_v060_voice_integration.py
python3 tools/test_v050_voice_flow.py
python3 tools/test_v040_voice_flow.py
python3 tools/test_voice_command_retry.py
python3 tools/test_local_asr_source.py
```

Expected: all PASS.

- [ ] **Step 9: Commit WakeService integration**

```bash
git add app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt \
        app/src/main/java/com/lchuang/xiaozhimobile/CommandRouter.kt \
        tools/test_v060_voice_integration.py
git commit -m "feat: integrate local first AI assistant flow"
```

---

### Task 11: Rebuild MainActivity settings/diagnostics around v0.6 capabilities

**Files:**
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt:17-356`
- Test: `tools/test_v060_ui_source.py`

**Interfaces:**
- UI persists assistant name, wake phrase, map preference, TTS voice/rate/pitch, Base URL, model, API mode.
- `保存并应用唤醒词` sends the service action to rebuild KWS if the service is running.
- `测试 AI 接口` calls `AiClient.testEndpoint()` off the UI thread and displays sanitized result fields.
- App diagnostics can refresh/list discovered app label + package and test an app launch.
- TTS preview uses `TtsVoiceManager.preview` and does not start/stop WakeService.

- [ ] **Step 1: Write failing UI source assertions**

Create `tools/test_v060_ui_source.py`:

```python
from pathlib import Path
root = Path(__file__).resolve().parents[1]
main = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt').read_text('utf-8')
for value in [
    '助手名字', '唤醒短语', '保存并应用',
    '声音', '试听', '语速', '音调',
    '默认地图', '位置权限', '查看已发现应用',
    'Base URL', 'API 模式', '测试 AI 接口',
    '最近一次 App 匹配', '当前 KWS 唤醒短语'
]:
    if value not in main:
        raise SystemExit('v0.6 UI feature missing: ' + value)
if 'ACCESS_FINE_LOCATION' not in main:
    raise SystemExit('foreground location permission UI missing')
print('PASS: v0.6 settings/diagnostic UI source')
```

- [ ] **Step 2: Run and verify failure**

```bash
python3 tools/test_v060_ui_source.py
```

Expected: FAIL because v0.5 UI lacks these controls.

- [ ] **Step 3: Add grouped settings fields without changing the app to a new UI framework**

Keep the existing programmatic native Android UI. Add these sections in this order:

```text
语音助手
声音
手机控制与导航
AI 对话
个性化
调试
```

Keep desktop icon controls intact under 个性化.

- [ ] **Step 4: Add assistant/wake controls and active-phrase diagnostic**

Fields/buttons:

```text
助手名字 [EditText]
唤醒短语 [EditText]
[保存并应用唤醒词]
当前 KWS 唤醒短语：<value>
```

When assistant name changes from its previous value and the wake phrase is still the old default `<oldName><oldName>`, prefill `<newName><newName>` but allow the user to edit it before saving. Saving sends `WakeService.ACTION_APPLY_WAKE_SETTINGS`; if the service is not running, settings persist and are applied next start.

- [ ] **Step 5: Add TTS voice/rate/pitch controls and preview**

Populate a `Spinner` from `TtsVoiceManager.availableVoices()`. Use numeric `SeekBar` or editable values that map exactly to the persisted clamped ranges. `试听` speaks:

```text
你好，我是<assistantName>，这是当前语音效果。
```

Add a button that launches `Settings.ACTION_TTS_SETTINGS` when the device has only one usable voice or the user wants to install/configure another engine.

- [ ] **Step 6: Add map/location and app diagnostics**

Request location only when the user presses the location authorization button or when a nearby command actually needs it. UI must show permission state. Add:

```text
默认地图 Spinner(AUTO/高德/百度/系统)
已发现应用：N 个
[刷新应用列表]
[查看已发现应用]
[测试打开应用]
最近一次 App 匹配：<sanitized explanation>
```

The list dialog should show `label — packageName — source` for diagnostics. It must not show private data beyond installed app metadata.

- [ ] **Step 7: Replace complete-endpoint AI field with Base URL + mode + test button**

Label the input `Base URL` and show example `https://api.example.com`. Add API mode spinner values `自动检测 / Chat Completions / Responses`. `测试 AI 接口` temporarily disables itself, runs `AiClient.testEndpoint()`, then displays:

```text
连接状态
HTTP
接口类型
模型
耗时
回复或错误摘要
```

Never display the full API key in the result dialog/status.

- [ ] **Step 8: Extend runtime permission request logic**

Keep microphone/camera/notification behavior. Do not automatically request location at app launch; expose a dedicated location authorization action and request `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION` only when the user authorizes location/uses location functionality.

- [ ] **Step 9: Run UI and icon regressions**

```bash
python3 tools/test_v060_ui_source.py
python3 tools/test_v050_icon.py
python3 tools/test_overlay_source.py
```

Expected: all PASS.

- [ ] **Step 10: Commit v0.6 settings UI**

```bash
git add app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt tools/test_v060_ui_source.py
git commit -m "feat: add v0.6 assistant settings and diagnostics"
```

---

### Task 12: Add security regression checks, update project validator, and protect secrets

**Files:**
- Create: `tools/test_v060_security.py`
- Modify: `tools/validate_project.py`
- Modify: `.gitignore`
- Test: all `tools/test_*.py`

**Interfaces:**
- Security test scans source/docs/workflows for known secret patterns and forbidden Android capabilities.
- Validator asserts v0.6 component presence and architecture requirements before GitHub build downloads large models.

- [ ] **Step 1: Write the security test before updating the validator**

Create `tools/test_v060_security.py`:

```python
from pathlib import Path
import re
root = Path(__file__).resolve().parents[1]
scan_ext = {'.kt', '.kts', '.py', '.md', '.xml', '.yml', '.yaml', '.sh', '.ps1', '.bat', '.txt'}
text_parts = []
for p in root.rglob('*'):
    if p.is_file() and p.suffix.lower() in scan_ext and '.git' not in p.parts:
        text_parts.append((p, p.read_text('utf-8', errors='ignore')))
secret_re = re.compile(r'\bsk-[A-Za-z0-9_-]{20,}\b')
for p, text in text_parts:
    if secret_re.search(text):
        raise SystemExit(f'possible API key committed: {p}')
manifest = (root / 'app/src/main/AndroidManifest.xml').read_text('utf-8')
for forbidden in ['ACCESS_BACKGROUND_LOCATION', 'AccessibilityService', 'BIND_ACCESSIBILITY_SERVICE']:
    if forbidden in manifest:
        raise SystemExit('forbidden Android capability: ' + forbidden)
safe = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/SafeToolExecutor.kt').read_text('utf-8')
for forbidden in ['delete_all_files', 'send_message', 'transfer_money', 'install_app', 'shell_command']:
    if '"' + forbidden + '" ->' in safe:
        raise SystemExit('forbidden tool executable: ' + forbidden)
print('PASS: v0.6 security regression')
```

- [ ] **Step 2: Run the security test immediately**

```bash
python3 tools/test_v060_security.py
```

Expected after prior tasks: PASS. If it fails, remove the secret/capability before continuing; do not weaken the test.

- [ ] **Step 3: Expand `validate_project.py` to cover v0.6 architecture**

Add checks for:

```text
version target will become 0.6.0 in release task
QUERY_ALL_PACKAGES present
foreground location permissions present, background absent
InstalledAppRegistry + AppLauncher structured result
MapController + LocationProvider
WakePhraseCompiler + runtime createStream keyword path
pinyin4j dependency
TtsVoiceManager
apiBaseUrl + ApiMode
AiEndpointResolver
AiConversationMemory(max 8)
AiOrchestrator
SafeToolExecutor allowlist
no AccessibilityService
no secret-like sk- token in scanned project text
manual proxy forced upload behavior retained
```

Keep every existing v0.5 validator check that still applies.

- [ ] **Step 4: Run the complete source regression suite**

Run each test independently so a failure identifies the owning subsystem:

```bash
python3 tools/validate_project.py
python3 tools/test_v060_settings.py
python3 tools/test_v060_app_launch.py
python3 tools/test_v060_map.py
python3 tools/test_v060_wake_phrase.py
python3 tools/test_v060_tts.py
python3 tools/test_v060_ai_endpoint.py
python3 tools/test_v060_ai_memory.py
python3 tools/test_v060_safe_tools.py
python3 tools/test_v060_ai_orchestrator.py
python3 tools/test_v060_voice_integration.py
python3 tools/test_v060_ui_source.py
python3 tools/test_v060_security.py
python3 tools/test_v050_session.py
python3 tools/test_v050_app_registry.py
python3 tools/test_v050_voice_flow.py
python3 tools/test_v050_icon.py
python3 tools/test_v040_voice_flow.py
python3 tools/test_v031_behavior.py
python3 tools/test_local_asr_source.py
python3 tools/test_overlay_source.py
python3 tools/test_voice_command_normalizer.py
python3 tools/test_voice_command_retry.py
python3 tools/test_push_script_encoding.py
python3 tools/test_push_script_network_fallback.py
python3 tools/test_manual_proxy_forced.py
```

Expected: every command exits 0.

- [ ] **Step 5: Commit validation/security coverage**

```bash
git add tools/test_v060_security.py tools/validate_project.py .gitignore
git commit -m "test: add v0.6 security and architecture validation"
```

---

### Task 13: Set v0.6 release metadata, docs, CI artifact name, and GitHub-ready package

**Files:**
- Modify: `app/build.gradle.kts:10-18`
- Modify: `.github/workflows/build-apk.yml:35-44`
- Modify: `README.md`
- Modify: `BUILD_NOTES.md`
- Modify: `GITHUB_BUILD_GUIDE.md`
- Modify: `PUSH_TO_GITHUB.ps1`
- Modify: `tools/validate_project.py`
- Create at delivery time: `/mnt/data/XiaoZhi-Mobile-v0.6.0-GitHub-ready-FIX9.zip`

**Interfaces:**
- `versionCode = 7`.
- `versionName = "0.6.0"`.
- GitHub artifact contains `XiaoZhi-Mobile-v0.6.0-debug.apk`.
- Manual proxy upload remains forced when `XIAOZHI_GIT_PROXY` is supplied.

- [ ] **Step 1: Update version and CI output name**

Set:

```kotlin
versionCode = 7
versionName = "0.6.0"
```

Update workflow rename/upload path to exactly:

```text
XiaoZhi-Mobile-v0.6.0-debug.apk
```

Do not change artifact group name `XiaoZhi-Mobile-APK` so the existing download flow continues to work.

- [ ] **Step 2: Update README/build guide with v0.6 device setup**

Document only user-required steps:

```text
install APK
allow microphone/notification/camera as before
allow overlay
optionally authorize foreground location
configure assistant name/wake phrase and press save/apply
choose/preview TTS voice
configure Base URL/API key/model/API mode
press 测试 AI 接口
start background offline wake
```

State that direct-install v0.6 uses broad app visibility and that a future Google Play build would need separate review.

- [ ] **Step 3: Re-run final source verification after version/docs changes**

Run the full command list from Task 12 Step 4 again. Expected: every command exits 0 and validator now reports version 0.6.0.

- [ ] **Step 4: Verify git state and review the release diff**

```bash
git status --short
git diff --check
git log --oneline --decorate -15
```

Expected before the release commit: only intentional docs/version/CI files are modified; `git diff --check` prints no whitespace errors.

- [ ] **Step 5: Commit release metadata**

```bash
git add app/build.gradle.kts .github/workflows/build-apk.yml README.md BUILD_NOTES.md \
        GITHUB_BUILD_GUIDE.md PUSH_TO_GITHUB.ps1 tools/validate_project.py
git commit -m "chore: finalize XiaoZhi Mobile v0.6.0 release"
```

- [ ] **Step 6: Create GitHub-ready ZIP from the committed tree**

From the repository root:

```bash
git archive --format=zip --output=/mnt/data/XiaoZhi-Mobile-v0.6.0-GitHub-ready-FIX9.zip HEAD
unzip -t /mnt/data/XiaoZhi-Mobile-v0.6.0-GitHub-ready-FIX9.zip
sha256sum /mnt/data/XiaoZhi-Mobile-v0.6.0-GitHub-ready-FIX9.zip
```

Expected: `unzip -t` reports no errors. Record the SHA-256 in the user handoff.

---

### Task 14: Run the real GitHub Actions Android build, inspect APK, and execute device acceptance checklist

**Files:**
- No source changes expected if CI is green.
- Generated artifact: `/mnt/data/XiaoZhi-Mobile-v0.6.0-debug.apk`

**Interfaces:**
- GitHub Actions job must pass source validation, model download, Gradle Android compile, rename, and artifact upload.
- APK must contain `classes.dex`, `AndroidManifest.xml`, `lib/arm64-v8a/*`, Paraformer assets, KWS assets, and launcher icon resources.

- [ ] **Step 1: Push FIX9 to `naisiliang/XiaoZhi-Mobile-App` main**

Use `PUSH_TO_GITHUB_MANUAL_PROXY.bat`. When prompted for the local HTTP/Mixed proxy port, use the user-confirmed local port if still applicable. The script must print `Manual proxy override is active` before cloning when manual proxy mode is used.

- [ ] **Step 2: Inspect the newest `Build XiaoZhi Mobile APK` run**

Verify the run head SHA equals the pushed v0.6.0 commit. Check these steps individually:

```text
Validate source tree
Fetch offline wake + ASR models
Build debug APK
Rename APK
Upload artifact
```

Do not claim completion until `Build debug APK` and artifact upload both conclude `success`.

- [ ] **Step 3: If CI fails, diagnose from the exact failing job log and return to the owning task**

Examples:

```text
Kotlin compile error in WakePhraseCompiler -> Task 4
Manifest/package visibility error           -> Task 1/2
TTS API compile error                       -> Task 5
AI parser/JSON compile error                -> Task 6/9
MainActivity UI compile error               -> Task 11
```

Fix the smallest owning task, rerun its focused tests + full validator, commit, repack, and push. Do not bypass or delete a failing regression assertion merely to make CI green.

- [ ] **Step 4: Download and verify the successful APK**

After artifact download/extraction, run an integrity script equivalent to:

```python
from pathlib import Path
import hashlib, zipfile
apk = Path('/mnt/data/XiaoZhi-Mobile-v0.6.0-debug.apk')
print(hashlib.sha256(apk.read_bytes()).hexdigest())
with zipfile.ZipFile(apk) as z:
    assert z.testzip() is None
    names = z.namelist()
    assert 'classes.dex' in names
    assert 'AndroidManifest.xml' in names
    assert any(n.startswith('lib/arm64-v8a/') for n in names)
    assert any('paraformer' in n.lower() for n in names)
    assert any('kws' in n.lower() or 'zipformer-zh-en' in n.lower() for n in names)
```

Record APK size and SHA-256 in the user handoff.

- [ ] **Step 5: Execute the v0.6 device acceptance checklist**

Test on the user's phone in this order so failures isolate cleanly:

```text
A. App diagnostics: confirm discovered app count is plausible and 夸克/小红书/etc appear by label/package.
B. Arbitrary app: wake -> “打开夸克” -> app opens -> assistant immediately listens.
C. Amap named navigation: “用高德导航到广州南站”.
D. Nearby location: “附近帮我找商场”; verify foreground permission and map search.
E. Custom wake: change assistant name to 小白, wake phrase to 小白小白, apply without reinstall, then wake from desktop.
F. TTS: choose another available Chinese voice, preview, then verify assistant reply uses the saved voice.
G. AI endpoint test: Base URL + model + temporary key -> test displays HTTP/mode/model/latency/reply without exposing the key.
H. AI context: ask about 深圳湾, then “那里晚上适合去吗？” in same session.
I. AI safe tool: “帮我用高德找附近的大商场” -> structured nearby tool -> local execution.
J. Forbidden action: a test model/tool response for delete_all_files is rejected locally and performs no action.
K. AI outage isolation: disable/break AI endpoint, then “打开微信”; local control still succeeds.
L. Existing timeout: remain silent for configured timeout -> configured exit phrase -> overlay hides -> active wake phrase resumes.
```

- [ ] **Step 6: Mark v0.6 complete only after all required acceptance paths pass**

A device-specific app label mismatch may be fixed with a user alias if the registry correctly discovers the package. A missing app from the registry, wrong KWS active phrase, broken map intent, leaked key, unsafe tool execution, or failed Android build is release-blocking and requires a code fix before completion.

---

## Plan Self-Review Result

- **Spec coverage:** Every design section maps to at least one task: app discovery/launch (Task 2), maps/location (Task 3), wake phrase (Task 4), TTS (Task 5), endpoint test (Task 6), memory (Task 7), safe tools (Task 8), AI planning (Task 9), voice/session integration (Task 10), settings/diagnostics (Task 11), security/regression (Task 12), release/CI/device verification (Tasks 13-14).
- **Type consistency:** `ApiMode`, `MapAppPreference`, `AiToolCall`, `AiOutcome`, `AiConversationMemory`, `AppLaunchResult`, and `SafeToolExecutor` are defined before the tasks that consume them.
- **Scope:** No AccessibilityService, message sending, payment, background location, long-term memory, cloud TTS, or autonomous multi-tool loop is introduced.
- **Secret handling:** The plan never embeds a concrete API key and includes an explicit repository secret scan before release.
