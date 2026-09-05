from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
MAIN_ACTIVITY = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt").read_text("utf-8")
ADAPTER_SOURCE = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/conversation/ConversationAdapter.kt").read_text("utf-8")
MODELS_SOURCE = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/conversation/ConversationModels.kt").read_text("utf-8")
SOURCE_SOURCE = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/conversation/ConversationSessionEventSource.kt").read_text("utf-8") if (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/conversation/ConversationSessionEventSource.kt").exists() else ""
SQLITE_SOURCE = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/conversation/SqliteConversationRepository.kt").read_text("utf-8")
HISTORY_SOURCE = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/conversation/ConversationHistoryActivity.kt").read_text("utf-8")
MANIFEST = (ROOT / "app/src/main/AndroidManifest.xml").read_text("utf-8")
GRADLE = (ROOT / "app/build.gradle.kts").read_text("utf-8")
FAILURES = []


def extract_braced_block(source, opening_brace, description):
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


def method_body(source, method_name):
    method = re.search(rf"\bfun\s+{re.escape(method_name)}\s*\(", source)
    if not method:
        return ""
    opening_brace = source.find("{", method.end())
    return extract_braced_block(source, opening_brace, f"method {method_name}")


def lambda_body(source, marker, description):
    marker_position = source.find(marker)
    if marker_position < 0:
        return ""
    opening_brace = source.find("{", marker_position + len(marker))
    return extract_braced_block(source, opening_brace, description)


def require(source, marker, context):
    if marker not in source:
        FAILURES.append(f"{context} is missing: {marker}")


def forbid(source, marker, context):
    if marker in source:
        FAILURES.append(f"{context} is forbidden: {marker}")


def require_regex(source, pattern, context):
    if not re.search(pattern, source, re.S):
        FAILURES.append(f"{context} does not match: {pattern}")


def require_count(source, marker, expected, context):
    actual = source.count(marker)
    if actual != expected:
        FAILURES.append(f"{context} expected {expected} occurrence(s), found {actual}: {marker}")


def require_order(source, markers, context):
    positions = []
    for marker in markers:
        position = source.find(marker)
        if position < 0:
            FAILURES.append(f"{context} is missing: {marker}")
            return
        positions.append(position)
    if positions != sorted(positions):
        FAILURES.append(f"{context} is out of order: {markers}")


main_on_create = method_body(MAIN_ACTIVITY, "onCreate")
main_on_destroy = method_body(MAIN_ACTIVITY, "onDestroy")
main_build_ui = method_body(MAIN_ACTIVITY, "buildChatHome")
main_menu = method_body(MAIN_ACTIVITY, "showHomeMenu")
main_append = method_body(MAIN_ACTIVITY, "appendToCurrentSession")
main_result_dispatch = lambda_body(MAIN_ACTIVITY, "mainHandler.post", "MainActivity result dispatch")
history_on_create = method_body(HISTORY_SOURCE, "onCreate")

require(MAIN_ACTIVITY, "private lateinit var repository: ConversationRepository", "MainActivity repository field")
require(MAIN_ACTIVITY, "private var currentSession: ConversationSession? = null", "MainActivity current session field")
require(main_on_create, "repository = ConversationSessionStore.repository(this)", "MainActivity.onCreate shared repository initialization")
require(main_on_create, "currentSession = sessionManager.currentSession() ?: repository.loadCurrent()", "MainActivity.onCreate current session loading")
require(MAIN_ACTIVITY, "import android.os.Handler", "MainActivity Handler import")
require(MAIN_ACTIVITY, "import android.os.Looper", "MainActivity Looper import")
require(MAIN_ACTIVITY, "private val mainHandler = Handler(Looper.getMainLooper())", "MainActivity main-looper handler")
require(MAIN_ACTIVITY, "mainHandler.post", "MainActivity result main-looper dispatch")
require(MAIN_ACTIVITY, "when (result.kind)", "result handling inside main-looper dispatch")
require_order(
    main_on_create,
    (
        "repository = ConversationSessionStore.repository(this)",
        "sessionManager = ConversationSessionStore.manager(this)",
        "stateStore = AssistantStateStore",
        "setContentView(buildChatHome())",
        "currentSession = sessionManager.currentSession() ?: repository.loadCurrent()",
        "conversationAdapter.submitSession(currentSession)",
        "ConversationSessionStore.observe(this, sessionObserver)",
        "removeSessionObserver = { ConversationSessionStore.removeObserver(this, sessionObserver) }",
        "ConversationResultBridge.registerSink(resultSink)",
    ),
    "MainActivity initialization before shared sink registration",
)
require(ADAPTER_SOURCE, "object ConversationResultBridge", "shared conversation result bridge singleton")
forbid(MAIN_ACTIVITY, "private val resultBridge = ConversationResultBridge()", "MainActivity-private result bridge instance")
require(MAIN_ACTIVITY, "ConversationResultBridge.Sink", "MainActivity typed result sink")
require(main_on_create, "ConversationResultBridge.registerSink(resultSink)", "MainActivity shared result sink registration")
require(main_on_destroy, "ConversationResultBridge.unregisterSink(resultSink)", "MainActivity shared result sink unregistration")
require(main_on_destroy, "removeSessionObserver?.invoke()", "MainActivity session observer unregistration")
require(MAIN_ACTIVITY, "ConversationSessionStore", "MainActivity shared session source")
require(SOURCE_SOURCE, "class ConversationSessionEventSource", "shared observable session source implementation")
require(SOURCE_SOURCE, "synchronized", "shared session source thread safety")
require(SOURCE_SOURCE, "fun addObserver", "shared session source observation API")
require(SOURCE_SOURCE, "fun startWakeSession", "shared session source lifecycle API")
require(SOURCE_SOURCE, "fun appendSystemAction", "shared system action lifecycle API")
require(SOURCE_SOURCE, "fun appendSystemResult", "shared system result lifecycle API")
require(SOURCE_SOURCE, "fun appendConfirmation", "shared confirmation lifecycle API")
require(main_menu, 'menu.add("新会话")', "new-session menu item")
require(main_menu, 'menu.add("历史会话")', "history menu item")
require(main_menu, 'menu.add("插件与技能")', "plugins menu item")
require(main_menu, 'menu.add("Agents")', "Agents menu item")
require(main_menu, 'menu.add("设置")', "settings menu item")
require(main_menu, "Intent(this@MainActivity, ConversationHistoryActivity::class.java)", "explicit history navigation")
require(main_menu, "Intent(this@MainActivity, SettingsActivity::class.java)", "explicit settings navigation")
require(main_build_ui, "RecyclerView", "chat home RecyclerView")
require(main_build_ui, "composer = EditText(this)", "chat home composer")
require(main_build_ui, "adapter = conversationAdapter", "chat home adapter binding")

for handler, ingress in (
    ("onTextResult", "submitText"),
    ("onVoiceResult", "submitVoice"),
    ("onOperationResult", "submitOperation"),
):
    handler_body = method_body(MAIN_ACTIVITY, handler)
    require(MAIN_ACTIVITY, f"fun {handler}(text: String)", f"public {handler} handler")
    require(handler_body, f"ConversationResultBridge.{ingress}(text)", f"{handler} external typed bridge ingress")

require(ADAPTER_SOURCE, "enum class ConversationResultKind", "typed conversation result kind")
for result_kind in ("TEXT", "VOICE", "OPERATION"):
    require(ADAPTER_SOURCE, result_kind, f"typed result kind {result_kind}")
require(ADAPTER_SOURCE, "fun registerSink", "conversation result sink registration API")
require(ADAPTER_SOURCE, "fun unregisterSink", "conversation result sink unregistration API")
for ingress in ("submitText", "submitVoice", "submitOperation"):
    require(ADAPTER_SOURCE, f"fun {ingress}", f"conversation result bridge {ingress} ingress")
require(MAIN_ACTIVITY, "when (result.kind)", "typed sink dispatch")
require(
    MAIN_ACTIVITY,
    "ConversationResultKind.TEXT -> appendToCurrentSession(ConversationMessage.Role.USER, result.text)",
    "MainActivity TEXT shared append path",
)
require(
    MAIN_ACTIVITY,
    "ConversationResultKind.VOICE -> appendToCurrentSession(ConversationMessage.Role.USER, result.text)",
    "MainActivity VOICE shared append path",
)
require(
    MAIN_ACTIVITY,
    "ConversationResultKind.OPERATION -> appendToCurrentSession(ConversationMessage.Role.ASSISTANT, result.text)",
    "MainActivity OPERATION shared append path",
)

require(MAIN_ACTIVITY, "private fun appendToCurrentSession(", "shared append helper")
require(main_append, "ConversationMessage.Role", "shared append role")
require(main_append, "currentSession", "shared append current-session reuse")
require(main_append, "status != ConversationSession.Status.ACTIVE", "shared append active-session reuse")
require(main_append, "sessionManager.startWakeSession()", "shared append new-session fallback")
require(main_append, "sessionManager.appendUser(text)", "shared append user persistence")
require(main_append, "sessionManager.appendAssistant(text)", "shared append assistant persistence")
forbid(main_append, "repository.save(updated)", "shared append duplicate direct repository save")
require(main_append, "currentSession = updated", "shared append state update")
require(main_append, "conversationAdapter.submitSession(updated)", "shared append adapter update")

require(ADAPTER_SOURCE, "ConversationSession", "ConversationAdapter session contract")
require(ADAPTER_SOURCE, "fun submitSession(session: ConversationSession?)", "ConversationAdapter submitSession contract")
require(ADAPTER_SOURCE, "RecyclerView.Adapter", "ConversationAdapter RecyclerView implementation")
require(history_on_create, "repository = ConversationSessionStore.repository(this)", "history shared repository initialization")
require(history_on_create, "repository.loadHistory()", "history repository loading")
require(history_on_create, "ConversationAdapter()", "history adapter binding")
require(history_on_create, "it.submitHistory(sessions)", "history session-bound adapter binding")
forbid(HISTORY_SOURCE, ".flatMap", "history session boundaries")
require_regex(
    MANIFEST,
    r'<activity\b(?=[^>]*android:name="\.conversation\.ConversationHistoryActivity")(?=[^>]*android:exported="false")[^>]*>',
    "history activity manifest registration",
)
require(GRADLE, 'implementation("androidx.recyclerview:recyclerview:1.3.2")', "RecyclerView dependency")

for marker in (
    "title: String",
    "assistantName: String",
    "enum class Status",
    "SYSTEM_ACTION",
    "SYSTEM_RESULT",
    "CONFIRMATION",
):
    require(MODELS_SOURCE, marker, "conversation model contract")
for marker in (
    'arrayOf("id", "title", "started_at", "ended_at", "status", "assistant_name")',
    'arrayOf("timestamp", "role", "content", "status")',
):
    require(ADAPTER_SOURCE, marker, "conversation repository metadata/role contract")
for marker in ("session.title", "session.status", "session.assistantName", "message.status"):
    require(SQLITE_SOURCE, marker, "conversation persistence metadata/role contract")
for marker in (
    "ConversationSessionStore.repository(this)",
    "ConversationSessionStore.manager(this)",
):
    require(MAIN_ACTIVITY, marker, "shared repository/session ownership")

for forbidden in (
    "WakeService",
    "WakePhrase",
    "KWS",
    "initKeywordSpotter",
    "Accessibility",
    "screenshot",
    "api_key",
    "password",
    "otp",
    "SecurityPolicy",
    "PermissionBroker",
    "ToolDispatcher",
    "SafeToolExecutor",
    "Task 8",
    "Alpha2",
    "PluginRuntime",
    "AgentRuntime",
):
    for source_name, source in (
        ("MainActivity.kt", MAIN_ACTIVITY),
        ("ConversationAdapter.kt", ADAPTER_SOURCE),
        ("ConversationHistoryActivity.kt", HISTORY_SOURCE),
    ):
        if forbidden in source:
            FAILURES.append(f"{source_name} contains out-of-scope marker: {forbidden}")

if FAILURES:
    raise AssertionError("\n".join(FAILURES))

print("PASS: v0.7.0-alpha1 chat home structural contract")
