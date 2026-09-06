from pathlib import Path
import re
import xml.etree.ElementTree as ET

from v070_source_contract_utils import strip_kotlin_comments, strip_kotlin_literals


ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt").read_text("utf-8")
ADAPTER = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/conversation/ConversationAdapter.kt").read_text("utf-8")
MAIN_LAYOUT = ROOT / "app/src/main/res/layout/activity_main_chat.xml"
ROLE_LAYOUTS = {
    "user": ROOT / "app/src/main/res/layout/item_message_user.xml",
    "assistant": ROOT / "app/src/main/res/layout/item_message_assistant.xml",
    "operation": ROOT / "app/src/main/res/layout/item_message_operation.xml",
}
DRAWABLES = {
    "user bubble": ROOT / "app/src/main/res/drawable/bg_user_bubble.xml",
    "assistant card": ROOT / "app/src/main/res/drawable/bg_assistant_card.xml",
    "composer": ROOT / "app/src/main/res/drawable/bg_chat_composer.xml",
    "quick chip": ROOT / "app/src/main/res/drawable/bg_quick_chip.xml",
    "assistant avatar": ROOT / "app/src/main/res/drawable/bg_assistant_avatar.xml",
}


def collect_missing():
    missing = []

    def strip_xml_comments(source):
        return re.sub(r"<!--.*?-->", "", source, flags=re.S)

    MAIN_CLEAN = strip_kotlin_comments(MAIN)
    MAIN_STRUCTURE = strip_kotlin_literals(MAIN)

    def forbid(source, marker, context):
        if marker in source:
            missing.append(f"{context}: still contains {marker}")

    def require_body(source, function_name, markers, context):
        match = re.search(
            rf"(?:private |public |internal |protected )?(?:override )?fun {function_name}\b[^{{]*\{{",
            source,
        )
        if not match:
            missing.append(f"{context}: missing function {function_name}")
            return
        body_start = match.end()
        depth = 1
        index = body_start
        while depth and index < len(source):
            if source[index] == "{":
                depth += 1
            elif source[index] == "}":
                depth -= 1
            index += 1
        if depth != 0:
            missing.append(f"{context}: unbalanced function {function_name}")
            return
        body = source[body_start:index - 1]
        for marker in markers:
            if not re.search(marker, body, re.S):
                missing.append(f"{context}: missing {marker}")

    def extract_braced_block(source, opening_index):
        depth = 0
        for index in range(opening_index, len(source)):
            if source[index] == "{":
                depth += 1
            elif source[index] == "}":
                depth -= 1
                if depth == 0:
                    return source[opening_index + 1:index]
        return None

    def ui_entry_function_bodies(source):
        source = strip_kotlin_comments(source)
        pattern = re.compile(
            r"(?:private |public |internal |protected )?(?:override )?fun\s+([A-Za-z_]\w*)\b[^{}]*\{"
        )
        bodies = {}
        for match in pattern.finditer(source):
            body = extract_braced_block(source, match.end() - 1)
            if body is not None:
                bodies[match.group(1)] = body
        return bodies

    def require_marker_in_ui_entry(source, marker, context):
        bodies = ui_entry_function_bodies(source)
        pending = ["onCreate"]
        visited = set()
        while pending:
            function_name = pending.pop()
            if function_name in visited:
                continue
            visited.add(function_name)
            body = bodies.get(function_name)
            if body is None:
                continue
            if re.search(marker, body, re.S):
                return
            pending.extend(
                name for name in re.findall(r"\b([A-Za-z_]\w*)\s*\(", body)
                if name in bodies and name not in visited
            )
        missing.append(f"{context}: missing reachable UI binding in onCreate or a function it invokes")

    def require_xml_tags(path, patterns, context):
        if not path.exists():
            missing.append(f"{context}: missing file {path.relative_to(ROOT)}")
            return
        source = strip_xml_comments(path.read_text("utf-8"))
        if not source.strip():
            missing.append(f"{context}: empty file {path.relative_to(ROOT)}")
            return
        try:
            ET.fromstring(source)
        except ET.ParseError as error:
            missing.append(f"{context}: malformed XML ({error})")
            return
        for pattern in patterns:
            if not re.search(pattern, source, re.S):
                missing.append(f"{context}: missing xml tag matching {pattern}")

    require_xml_tags(
        MAIN_LAYOUT,
        (
            r"<(?:[A-Za-z_]\w*\.)*RecyclerView\b",
            r"<(?:[A-Za-z_]\w*\.)*(?:AppCompatEditText|TextInputEditText|EditText)\b",
        ),
        "MainActivity chat layout",
    )
    for role, path in ROLE_LAYOUTS.items():
        if not path.exists():
            missing.append(f"ConversationAdapter {role} layout: missing file {path.relative_to(ROOT)}")
            continue
        role_source = strip_xml_comments(path.read_text("utf-8"))
        require_xml_tags(path, (r"@\+id/message_text", r"@\+id/message_role"), f"ConversationAdapter {role} layout")
        if "@drawable/" not in role_source:
            missing.append(f"ConversationAdapter {role} layout: missing role-specific background")
    for name, path in DRAWABLES.items():
        if not path.exists():
            missing.append(f"MainActivity {name} drawable: missing file {path.relative_to(ROOT)}")
            continue
        try:
            ET.fromstring(path.read_text("utf-8"))
        except ET.ParseError as error:
            missing.append(f"MainActivity {name} drawable: malformed XML ({error})")
    for marker in (
        "setHasStableIds(true)",
        "override fun getItemViewType",
        "override fun getItemId",
        "R.layout.item_message_user",
        "R.layout.item_message_assistant",
        "R.layout.item_message_operation",
    ):
        if marker not in ADAPTER:
            missing.append(f"ConversationAdapter role/stable-id binding: missing {marker}")
    require_body(
        MAIN_STRUCTURE,
        "onCreate",
        (r"setContentView\s*\(\s*R\.layout\.activity_main_chat\s*\)",),
        "MainActivity XML inflation",
    )
    require_marker_in_ui_entry(
        MAIN_STRUCTURE,
        r"ViewCompat\.setOnApplyWindowInsetsListener",
        "MainActivity insets listener",
    )
    require_marker_in_ui_entry(
        MAIN_STRUCTURE,
        r"WindowCompat\.setDecorFitsSystemWindows",
        "MainActivity window fitting",
    )
    require_marker_in_ui_entry(
        MAIN_STRUCTURE,
        r"chatHeader\.setPadding",
        "MainActivity header inset binding",
    )
    require_marker_in_ui_entry(
        MAIN_STRUCTURE,
        r"composerContainer\.setPadding",
        "MainActivity composer inset binding",
    )
    require_marker_in_ui_entry(
        MAIN_STRUCTURE,
        r"WakeServiceController\.submitText",
        "MainActivity typed composer route",
    )
    require_marker_in_ui_entry(
        MAIN_CLEAN,
        r'(?:\btext\s*=|\bsetText\s*\(|\bsetTitle\s*\()[^\n;]*"\$\{[^"]*assistantName[^"]*\}[^"\n]*智能体"',
        "MainActivity assistant-name title",
    )
    forbid(strip_kotlin_literals(ADAPTER), "android.R.layout.simple_list_item_2", "ConversationAdapter scaffold row")
    debug_literal = r'["\'][^"\']*(?:v0\.6\.5|会话状态机|悬浮层手动退出|自然语言媒体音量)[^"\']*["\']'
    oversized_status = r'(?:\btext\s*=|\bsetText\s*\()[^"\n;]*"[^"\n]{48,}"'
    if re.search(debug_literal, MAIN_CLEAN, re.S) or re.search(oversized_status, MAIN_CLEAN, re.S):
        missing.append("MainActivity debug subtitle: still contains a debug/version or oversized status literal")

    return missing


missing = collect_missing()
if missing:
    raise AssertionError("ui recovery contract is still missing:\n- " + "\n- ".join(missing))

print("PASS: v0.7 recovery UI contract")
