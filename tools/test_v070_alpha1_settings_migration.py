from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
MAIN_ACTIVITY = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt").read_text("utf-8")
SETTINGS_SOURCE = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/SettingsActivity.kt").read_text("utf-8")
SETTINGS_STORE = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/SettingsStore.kt").read_text("utf-8")
MANIFEST = (ROOT / "app/src/main/AndroidManifest.xml").read_text("utf-8")


def fail(message):
    raise AssertionError(message)


def extract_braced_block(source, opening_brace, description):
    if opening_brace < 0 or source[opening_brace] != "{":
        fail(f"could not locate opening brace for {description}")

    depth = 0
    quote = None
    escaped = False
    line_comment = False
    block_comment = False
    index = opening_brace
    while index < len(source):
        character = source[index]
        next_character = source[index + 1] if index + 1 < len(source) else ""

        if line_comment:
            if character == "\n":
                line_comment = False
        elif block_comment:
            if character == "*" and next_character == "/":
                block_comment = False
                index += 1
        elif quote:
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == quote:
                quote = None
        elif character == "/" and next_character == "/":
            line_comment = True
            index += 1
        elif character == "/" and next_character == "*":
            block_comment = True
            index += 1
        elif character in ('"', "'"):
            quote = character
        elif character == "{":
            depth += 1
        elif character == "}":
            depth -= 1
            if depth == 0:
                return source[opening_brace + 1:index]
        index += 1

    fail(f"unclosed block for {description}")


def find_class_body(source, class_name):
    class_match = re.search(rf"\bclass\s+{re.escape(class_name)}\b", source)
    if not class_match:
        fail(f"class {class_name} is missing")
    return extract_braced_block(source, source.find("{", class_match.end()), f"class {class_name}")


def find_method_body(source, method_name):
    method_match = re.search(rf"\bfun\s+{re.escape(method_name)}\s*\(", source)
    if not method_match:
        fail(f"method {method_name} is missing")
    return extract_braced_block(source, source.find("{", method_match.end()), f"method {method_name}")


def assert_contains(source, marker, context):
    if marker not in source:
        fail(f"{context} is missing: {marker}")


def assert_not_contains(source, forbidden_markers, context):
    found = [marker for marker in forbidden_markers if marker in source]
    if found:
        fail(f"{context} contains forbidden markers: {', '.join(found)}")


settings_class = find_class_body(SETTINGS_SOURCE, "SettingsActivity")
on_create = find_method_body(settings_class, "onCreate")
build_ui = find_method_body(settings_class, "buildUi")
load_settings = find_method_body(settings_class, "loadSettings")
save_settings = find_method_body(settings_class, "saveSettings")
apply_wake_settings = find_method_body(settings_class, "applyWakeSettingsIfRunning")

persisted_settings = [
    "assistantName",
    "wakePhrase",
    "wakeReply",
    "timeoutReply",
    "sessionTimeoutSeconds",
    "appAliases",
    "defaultMapApp",
    "ttsVoiceName",
    "ttsSpeechRate",
    "ttsPitch",
    "apiBaseUrl",
    "apiKey",
    "model",
    "apiMode",
    "systemPrompt",
    "preferOfflineAsr",
]
for setting_name in persisted_settings:
    if not re.search(rf"\bvar\s+{re.escape(setting_name)}\b", SETTINGS_STORE):
        fail(f"SettingsStore setting inventory is missing: {setting_name}")

edit_text_settings = [
    "assistantName",
    "wakePhrase",
    "wakeReply",
    "timeoutReply",
    "timeoutSeconds",
    "appAliases",
    "ttsVoiceName",
    "ttsSpeechRate",
    "ttsPitch",
    "apiBaseUrl",
    "apiKey",
    "model",
    "systemPrompt",
]
for control_name in edit_text_settings:
    assert_contains(
        settings_class,
        f"private lateinit var {control_name}: EditText",
        f"SettingsActivity control declaration for {control_name}",
    )
    if not re.search(rf"\b{re.escape(control_name)}\s*=\s*add(?:Edit|MultilineEdit)\(", build_ui):
        fail(f"SettingsActivity.buildUi must create editable control {control_name} with addEdit/addMultilineEdit")

for control_name in ("defaultMapApp", "apiMode"):
    assert_contains(
        settings_class,
        f"private lateinit var {control_name}: Spinner",
        f"SettingsActivity spinner declaration for {control_name}",
    )
    if not re.search(rf"\b{re.escape(control_name)}\s*=\s*Spinner\(this\)", build_ui):
        fail(f"SettingsActivity.buildUi must create spinner control {control_name}")

assert_contains(settings_class, "private lateinit var preferOfflineAsr: Switch", "offline ASR control declaration")
assert_contains(build_ui, "preferOfflineAsr = Switch(this)", "offline ASR control construction")

assert_contains(MAIN_ACTIVITY, "startActivity(Intent(this@MainActivity, SettingsActivity::class.java))", "MainActivity settings navigation")
if not re.search(
    r'<activity\b(?=[^>]*android:name="\.SettingsActivity")(?=[^>]*android:exported="false")[^>]*>',
    MANIFEST,
):
    fail("AndroidManifest must declare SettingsActivity with android:exported=\"false\"")

assert_contains(on_create, "settings = SettingsStore(this)", "SettingsActivity.onCreate")
assert_contains(on_create, "loadSettings()", "SettingsActivity.onCreate")
if not re.search(r"setOnClickListener\s*\{\s*saveSettings\(\)\s*\}", build_ui, re.S):
    fail("SettingsActivity save button listener must call saveSettings()")

settings_bindings = {
    "assistantName": ("assistantName.setText(settings.assistantName)", "settings.assistantName = assistantName.text.toString()"),
    "wakePhrase": ("wakePhrase.setText(settings.wakePhrase)", "settings.wakePhrase = wakePhrase.text.toString()"),
    "wakeReply": ("wakeReply.setText(settings.wakeReply)", "settings.wakeReply = wakeReply.text.toString()"),
    "timeoutReply": ("timeoutReply.setText(settings.timeoutReply)", "settings.timeoutReply = timeoutReply.text.toString()"),
    "sessionTimeoutSeconds": ("timeoutSeconds.setText(settings.sessionTimeoutSeconds.toString())", "settings.sessionTimeoutSeconds ="),
    "ttsVoiceName": ("ttsVoiceName.setText(settings.ttsVoiceName)", "settings.ttsVoiceName = ttsVoiceName.text.toString()"),
    "apiBaseUrl": ("apiBaseUrl.setText(settings.apiBaseUrl)", "settings.apiBaseUrl = apiBaseUrl.text.toString()"),
    "ttsSpeechRate": ("ttsSpeechRate.setText(settings.ttsSpeechRate.toString())", "settings.ttsSpeechRate ="),
    "ttsPitch": ("ttsPitch.setText(settings.ttsPitch.toString())", "settings.ttsPitch ="),
    "defaultMapApp": ("defaultMapApp.setSelection(settings.defaultMapApp.ordinal)", "settings.defaultMapApp ="),
    "appAliases": ("appAliases.setText(settings.appAliases)", "settings.appAliases = appAliases.text.toString()"),
    "apiKey": ("apiKey.setText(settings.apiKey)", "settings.apiKey = apiKey.text.toString()"),
    "model": ("model.setText(settings.model)", "settings.model = model.text.toString()"),
    "apiMode": ("apiMode.setSelection(settings.apiMode.ordinal)", "settings.apiMode ="),
    "systemPrompt": ("systemPrompt.setText(settings.systemPrompt)", "settings.systemPrompt = systemPrompt.text.toString()"),
    "preferOfflineAsr": ("preferOfflineAsr.isChecked = settings.preferOfflineAsr", "settings.preferOfflineAsr = preferOfflineAsr.isChecked"),
}
for setting_id, (load_marker, save_marker) in settings_bindings.items():
    assert_contains(load_settings, load_marker, f"SettingsActivity.loadSettings for {setting_id}")
    assert_contains(save_settings, save_marker, f"SettingsActivity.saveSettings for {setting_id}")

assert_contains(build_ui, "InputType.TYPE_TEXT_VARIATION_PASSWORD", "API key password input")
if re.search(r"(?:Log\.\w+|Toast\.makeText|append(?:Line)?|text\s*=)[^\n]*apiKey", SETTINGS_SOURCE):
    fail("SettingsActivity must not expose apiKey through logs, status text, notifications, or test details")

if not re.search(r"if\s*\(\s*!isWakeServiceRunning\(\)\s*\)\s*return", apply_wake_settings, re.S):
    fail("applyWakeSettingsIfRunning must return early when isWakeServiceRunning() is false")
assert_contains(
    apply_wake_settings,
    "startService(Intent(this, WakeService::class.java).setAction(WakeService.ACTION_APPLY_WAKE_SETTINGS))",
    "SettingsActivity.applyWakeSettingsIfRunning",
)
action_markers = re.findall(r"WakeService\.ACTION_[A-Z0-9_]+", apply_wake_settings)
if action_markers != ["WakeService.ACTION_APPLY_WAKE_SETTINGS"]:
    fail(f"applyWakeSettingsIfRunning must use only the existing wake action, found: {action_markers}")

assert_not_contains(
    SETTINGS_SOURCE,
    [
        "initKeywordSpotter",
        "WakePhraseCompiler",
        "WakePhraseManager",
        "Task 7",
        "Task 8",
        "Conversation",
        "conversation",
    ],
    "SettingsActivity.kt",
)
assert_not_contains(
    MAIN_ACTIVITY,
    [
        "private lateinit var wakePhrase",
        "private lateinit var apiBaseUrl",
        "private lateinit var speechRate",
        "private lateinit var mapSpinner",
        "private lateinit var appAliases",
        "wakePhrase =",
        "apiBaseUrl =",
        "speechRate =",
        "mapSpinner =",
        "appAliases =",
        "settings.wakePhrase",
        "settings.apiBaseUrl",
        "settings.ttsSpeechRate",
        "settings.defaultMapApp",
        "settings.appAliases",
        "wakePhrase.text",
        "apiBaseUrl.text",
        "speechRate.text",
        "mapSpinner.selectedItemPosition",
        "appAliases.text",
        "loadSettings()",
        "private fun loadSettings",
        "saveSettings()",
        "private fun saveSettings",
        "助手名字",
        "保存并应用唤醒词",
        "语音助手",
        "试听声音",
        "默认地图",
        "位置权限",
        "查看已发现应用",
        "Base URL",
        "API 模式",
        "测试 AI 接口",
        "最近一次 App 匹配",
        "保存全部设置",
    ],
    "MainActivity.kt",
)
print("PASS: v0.7.0-alpha1 settings migration structure")
