from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
MAIN_SOURCE = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt").read_text("utf-8")
SETTINGS_SOURCE = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/SettingsActivity.kt").read_text("utf-8")


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
settings_build_ui = method_body(settings_class, "buildUi")
settings_load = method_body(settings_class, "loadSettings")
settings_save = method_body(settings_class, "saveSettings")
main_on_create = method_body(MAIN_SOURCE, "onCreate")
main_build_ui = method_body(MAIN_SOURCE, "buildChatHome")
main_menu = method_body(MAIN_SOURCE, "showHomeMenu")
permissions = method_body(MAIN_SOURCE, "requestNeededPermissions")

for control in (
    "assistantName", "wakePhrase", "wakeReply", "timeoutReply", "timeoutSeconds",
    "ttsVoiceName", "ttsSpeechRate", "ttsPitch", "apiBaseUrl", "apiKey", "model",
    "systemPrompt", "appAliases",
):
    require(settings_class, f"private lateinit var {control}: EditText", "SettingsActivity editable control")
    if not re.search(rf"\b{re.escape(control)}\s*=\s*add(?:Edit|MultilineEdit)\(", settings_build_ui):
        raise AssertionError(f"SettingsActivity buildUi does not construct migrated control: {control}")

for control in ("defaultMapApp", "apiMode"):
    require(settings_class, f"private lateinit var {control}: Spinner", "SettingsActivity spinner")
    require(settings_build_ui, f"{control} = Spinner(this)", "SettingsActivity spinner construction")

require(settings_class, "private lateinit var preferOfflineAsr: Switch", "SettingsActivity offline ASR control")
require(settings_build_ui, "preferOfflineAsr = Switch(this)", "SettingsActivity offline ASR construction")

for marker in (
    'text = "唤醒"',
    'text = "声音"',
    'text = "手机控制与导航"',
    'text = "AI 对话"',
    'text = "默认地图"',
    'apiBaseUrl = addEdit(root, "Base URL，例如 https://api.example.com")',
    'apiKey = addEdit(root, "API Key（仅保存在本机）")',
    'model = addEdit(root, "模型名，例如 gpt-5.6")',
    'text = "API 模式"',
):
    require(settings_build_ui, marker, "SettingsActivity migrated settings UI")

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
    require(settings_save, marker, "SettingsActivity saveSettings binding")

if not re.search(r"setOnClickListener\s*\{\s*saveSettings\(\)\s*\}", settings_build_ui, re.S):
    raise AssertionError("SettingsActivity save button is not wired to saveSettings")
require(settings_class, "InputType.TYPE_TEXT_VARIATION_PASSWORD", "SettingsActivity API key protection")

require(MAIN_SOURCE, "private lateinit var repository: ConversationRepository", "MainActivity chat repository")
require(MAIN_SOURCE, "private var currentSession: ConversationSession? = null", "MainActivity active session")
for marker in ("RecyclerView", "ConversationAdapter", 'hint = "输入消息"', 'text = "发送"', 'text = "⋮"'):
    require(main_build_ui, marker, "MainActivity chat home")

require(main_menu, 'menu.add("历史会话").setOnMenuItemClickListener', "MainActivity history entry")
require(main_menu, "Intent(this@MainActivity, ConversationHistoryActivity::class.java)", "MainActivity history navigation")
require(main_menu, 'menu.add("设置").setOnMenuItemClickListener', "MainActivity settings entry")
require(main_menu, "Intent(this@MainActivity, SettingsActivity::class.java)", "MainActivity settings navigation")

require(main_on_create, "stateStore = AssistantStateStore", "MainActivity diagnostic state source")
require(MAIN_SOURCE, "private val stateObserver: (AssistantState) -> Unit = { state ->", "MainActivity diagnostic state observer")
require(MAIN_SOURCE, "mainHandler.post {\n            if (::status.isInitialized) status.text = stateLabel(state)\n        }", "MainActivity diagnostic status update")
require(main_on_create, "stateStore.addObserver(stateObserver)", "MainActivity diagnostic observer registration")
require(main_on_create, "status.text = stateLabel(stateStore.current)", "MainActivity initial diagnostic status")
require(main_build_ui, 'text = "v0.6.5：会话状态机 + 悬浮层手动退出 + 智能退出 + 自然语言媒体音量"', "MainActivity diagnostic summary")
require(permissions, "Manifest.permission.RECORD_AUDIO", "MainActivity microphone permission")
require(permissions, "Manifest.permission.CAMERA", "MainActivity camera permission")
require(permissions, "Manifest.permission.POST_NOTIFICATIONS", "MainActivity notification permission")

require_order(
    main_on_create,
    (
        "setContentView(buildChatHome())",
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
