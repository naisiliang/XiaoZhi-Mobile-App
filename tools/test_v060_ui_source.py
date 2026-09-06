from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
MAIN_SOURCE = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt").read_text("utf-8")
SETTINGS_SOURCE = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/SettingsActivity.kt").read_text("utf-8")
SETTINGS_LAYOUT = (ROOT / "app/src/main/res/layout/activity_settings.xml").read_text("utf-8")
MAIN_LAYOUT = (ROOT / "app/src/main/res/layout/activity_main_chat.xml").read_text("utf-8")


def extract_block(source, opening_brace, description):
    if opening_brace < 0 or source[opening_brace] != "{":
        raise AssertionError(f"could not locate opening brace for {description}")

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
    raise AssertionError(f"unclosed block for {description}")


def class_body(source, class_name):
    match = re.search(rf"\bclass\s+{re.escape(class_name)}\b", source)
    if not match:
        raise AssertionError(f"class {class_name} is missing")
    return extract_block(source, source.find("{", match.end()), f"class {class_name}")


def method_body(source, method_name):
    match = re.search(rf"\bfun\s+{re.escape(method_name)}\s*\(", source)
    if not match:
        raise AssertionError(f"method {method_name} is missing")
    return extract_block(source, source.find("{", match.end()), f"method {method_name}")


def require(source, marker, context):
    if marker not in source:
        raise AssertionError(f"{context} is missing: {marker}")


def require_order(source, markers, context):
    positions = []
    for marker in markers:
        position = source.find(marker)
        if position < 0:
            raise AssertionError(f"{context} is missing: {marker}")
        positions.append(position)
    if positions != sorted(positions):
        raise AssertionError(f"{context} is out of order: {markers}")


settings_class = class_body(SETTINGS_SOURCE, "SettingsActivity")
settings_bind_views = method_body(settings_class, "bindViews")
settings_load = method_body(settings_class, "loadSettings")
settings_save = method_body(settings_class, "saveSettings")
settings_persist = method_body(settings_class, "persistSettings")
main_on_create = method_body(MAIN_SOURCE, "onCreate")
main_bind_views = method_body(MAIN_SOURCE, "bindChatViews")
main_menu = method_body(MAIN_SOURCE, "showHomeMenu")
permissions = method_body(MAIN_SOURCE, "requestNeededPermissions")

for control in (
    "assistantName", "wakePhrase", "wakeReply", "timeoutReply", "timeoutSeconds",
    "ttsVoiceName", "ttsSpeechRate", "ttsPitch", "apiBaseUrl", "apiKey", "model",
    "systemPrompt", "appAliases",
):
    require(settings_class, f"private lateinit var {control}: EditText", "SettingsActivity editable control")
    xml_id = {
        "assistantName": "assistant_name",
        "wakePhrase": "wake_phrase",
        "wakeReply": "wake_reply",
        "timeoutReply": "timeout_reply",
        "timeoutSeconds": "timeout_seconds",
        "appAliases": "app_aliases",
        "ttsVoiceName": "tts_voice_name",
        "ttsSpeechRate": "tts_speech_rate",
        "ttsPitch": "tts_pitch",
        "apiBaseUrl": "api_base_url",
        "apiKey": "api_key",
        "model": "model",
        "systemPrompt": "system_prompt",
    }[control]
    require(SETTINGS_LAYOUT, f"@+id/{xml_id}", "SettingsActivity XML control")
    require(settings_bind_views, f"{control} = findViewById(R.id.{xml_id})", "SettingsActivity view binding")

for control in ("defaultMapApp", "apiMode"):
    require(settings_class, f"private lateinit var {control}: Spinner", "SettingsActivity spinner")
    xml_id = "default_map_app" if control == "defaultMapApp" else "api_mode"
    require(SETTINGS_LAYOUT, f"@+id/{xml_id}", "SettingsActivity spinner XML")
    require(settings_bind_views, f"{control} = findViewById(R.id.{xml_id})", "SettingsActivity spinner binding")

require(settings_class, "private lateinit var preferOfflineAsr: Switch", "SettingsActivity offline ASR control")
require(SETTINGS_LAYOUT, "@+id/prefer_offline_asr", "SettingsActivity offline ASR XML")
require(settings_bind_views, "preferOfflineAsr = findViewById(R.id.prefer_offline_asr)", "SettingsActivity offline ASR binding")

for marker in (
    'android:tag="语音"',
    'android:tag="声音"',
    'android:tag="手机控制"',
    'android:tag="AI"',
    'android:text="默认地图"',
    'android:hint="Base URL，例如 https://api.example.com"',
    'android:hint="API Key（仅保存在本机）"',
    'android:hint="模型名，例如 gpt-5.6"',
    '@+id/api_mode',
):
    require(SETTINGS_LAYOUT, marker, "SettingsActivity migrated settings UI")

for marker in (
    "assistantName.setText(settings.assistantName)",
    "wakePhrase.setText(settings.wakePhrase)",
    "ttsVoiceName.setText(settings.ttsVoiceName)",
    "ttsSpeechRate.setText(settings.ttsSpeechRate.toString())",
    "ttsPitch.setText(settings.ttsPitch.toString())",
    "defaultMapApp.setSelection(settings.defaultMapApp.ordinal)",
    "apiBaseUrl.setText(settings.apiBaseUrl)",
    "apiMode.setSelection(settings.apiMode.ordinal)",
):
    require(settings_load, marker, "SettingsActivity loadSettings binding")

for marker in (
    "settings.assistantName = assistantName.text.toString()",
    "settings.wakePhrase = wakePhrase.text.toString()",
    "settings.ttsVoiceName = ttsVoiceName.text.toString()",
    "settings.ttsSpeechRate = ttsSpeechRate.text.toString().toFloatOrNull() ?: 1.0f",
    "settings.ttsPitch = ttsPitch.text.toString().toFloatOrNull() ?: 1.0f",
    "settings.defaultMapApp = MapAppPreference.entries.getOrElse(defaultMapApp.selectedItemPosition)",
):
    require(settings_persist, marker, "SettingsActivity persistSettings binding")

if not re.search(r"findViewById(?:<[^>]+>)?\(R\.id\.save_settings\).*setOnClickListener", SETTINGS_SOURCE, re.S):
    raise AssertionError("SettingsActivity save button is not wired to saveSettings")
require(SETTINGS_LAYOUT, "android:inputType=\"textPassword\"", "SettingsActivity API key protection")

require(MAIN_SOURCE, "private lateinit var repository: ConversationRepository", "MainActivity chat repository")
require(MAIN_SOURCE, "private var currentSession: ConversationSession? = null", "MainActivity active session")
for marker in ("<androidx.recyclerview.widget.RecyclerView", "<EditText", 'android:hint="点击输入或按住说话"', 'android:text="＋"', 'android:text="⋮"', 'android:text="☰"'):
    require(MAIN_LAYOUT, marker, "MainActivity chat home")
require(main_bind_views, "conversationList = findViewById(R.id.conversation_list)", "MainActivity RecyclerView binding")
require(main_bind_views, "conversationList.adapter = conversationAdapter", "MainActivity adapter binding")
require(main_bind_views, "composer = findViewById(R.id.message_input)", "MainActivity composer binding")

require(main_menu, 'menu.add("历史会话").setOnMenuItemClickListener', "MainActivity history entry")
require(main_menu, "Intent(this@MainActivity, ConversationHistoryActivity::class.java)", "MainActivity history navigation")
require(main_menu, 'menu.add("设置").setOnMenuItemClickListener', "MainActivity settings entry")
require(main_menu, "Intent(this@MainActivity, SettingsActivity::class.java)", "MainActivity settings navigation")

require(main_on_create, "stateStore = AssistantStateStore", "MainActivity diagnostic state source")
require(MAIN_SOURCE, "private val stateObserver: (AssistantState) -> Unit = { state ->", "MainActivity diagnostic state observer")
require(MAIN_SOURCE, "mainHandler.post {\n            assistantState = state\n            renderStatus()\n        }", "MainActivity diagnostic status update")
require(main_on_create, "stateStore.addObserver(stateObserver)", "MainActivity diagnostic observer registration")
require_order(
    main_on_create,
    ("assistantState = stateStore.current", "runtimeStatus = runtimeStatusStore.current", "renderStatus()"),
    "MainActivity initial diagnostic status",
)
require(MAIN_LAYOUT, 'android:text="小白智能体"', "MainActivity assistant title")
require(MAIN_SOURCE, 'assistantTitle.text = "${assistantName}智能体"', "MainActivity assistant title binding")
require(permissions, "Manifest.permission.RECORD_AUDIO", "MainActivity microphone permission")
require(permissions, "Manifest.permission.CAMERA", "MainActivity camera permission")
require(permissions, "Manifest.permission.POST_NOTIFICATIONS", "MainActivity notification permission")

require_order(
    main_on_create,
    (
        "setContentView(R.layout.activity_main_chat)",
        "bindChatViews()",
        "configureQuickActions()",
        "configureInsets()",
        "currentSession = sessionManager.currentSession() ?: repository.loadCurrent()",
        "conversationAdapter.submitSession(currentSession)",
        "ConversationSessionStore.observe(this, sessionObserver)",
        "ConversationResultBridge.registerSink(resultSink)",
        "requestNeededPermissions()",
    ),
    "MainActivity chat-home and diagnostic lifecycle",
)

for stale_marker in (
    "private lateinit var assistantName",
    "private lateinit var wakePhrase",
    "private lateinit var apiBaseUrl",
    "private lateinit var ttsSpeechRate",
    "private lateinit var defaultMapApp",
    "private lateinit var appAliases",
    "loadSettings()",
    "saveSettings()",
    "助手名字",
    "默认地图",
    "测试 AI 接口",
    "最近一次 App 匹配",
):
    if stale_marker in MAIN_SOURCE:
        raise AssertionError(f"migrated settings/diagnostics remain in MainActivity: {stale_marker}")

print("PASS: v0.6 settings migrated and chat-home diagnostics remain wired")
