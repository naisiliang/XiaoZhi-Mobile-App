from pathlib import Path
import re
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt").read_text("utf-8")
SETTINGS = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/SettingsActivity.kt").read_text("utf-8")
ADAPTER = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/conversation/ConversationAdapter.kt").read_text("utf-8")
MAIN_LAYOUT = ROOT / "app/src/main/res/layout/activity_main_chat.xml"
SETTINGS_LAYOUT = ROOT / "app/src/main/res/layout/activity_settings.xml"


def collect_missing():
    missing = []

    def strip_xml_comments(source):
        return re.sub(r"<!--.*?-->", "", source, flags=re.S)

    def strip_kotlin_comments(source):
        source = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
        source = re.sub(r"(?m)//.*$", "", source)
        return source

    def strip_kotlin_literals(source):
        source = strip_kotlin_comments(source)
        source = re.sub(r'""".*?"""', "", source, flags=re.S)
        source = re.sub(r'"(?:\\.|[^"\\])*"', '""', source, flags=re.S)
        source = re.sub(r"'(?:\\.|[^'\\])*'", "''", source, flags=re.S)
        return source

    MAIN_CLEAN = strip_kotlin_comments(MAIN)
    SETTINGS_CLEAN = strip_kotlin_comments(SETTINGS)

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
            r"(?:private |public |internal |protected )?(?:override )?fun\s+([A-Za-z_]\w*)\b[^{{]*\{{"
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

    def require_vertical_linear_layout(path, context):
        if not path.exists():
            return
        source = strip_xml_comments(path.read_text("utf-8"))
        try:
            root = ET.fromstring(source)
        except ET.ParseError:
            return
        orientation_key = "{http://schemas.android.com/apk/res/android}orientation"
        for element in root.iter():
            tag_name = element.tag.rsplit("}", 1)[-1]
            if tag_name == "LinearLayout" and element.attrib.get(orientation_key) == "vertical":
                return
        missing.append(f"{context}: no LinearLayout with android:orientation=vertical")

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
    require_xml_tags(
        SETTINGS_LAYOUT,
        (
            r"<(?:[A-Za-z_]\w*\.)*ScrollView\b",
            r"<(?:[A-Za-z_]\w*\.)*LinearLayout\b",
            r"android:orientation\s*=\s*[\"']vertical[\"']",
        ),
        "SettingsActivity settings layout",
    )
    require_vertical_linear_layout(SETTINGS_LAYOUT, "SettingsActivity settings layout")
    require_body(
        MAIN_CLEAN,
        "onCreate",
        (r"setContentView\s*\(\s*R\.layout\.activity_main_chat\s*\)",),
        "MainActivity XML inflation",
    )
    require_body(
        SETTINGS_CLEAN,
        "onCreate",
        (r"setContentView\s*\(\s*R\.layout\.activity_settings\s*\)",),
        "SettingsActivity XML inflation",
    )
    require_body(
        MAIN_CLEAN,
        "onCreate",
        (r"ViewCompat\.setOnApplyWindowInsetsListener", r"WindowCompat\.setDecorFitsSystemWindows"),
        "MainActivity insets handling",
    )
    require_body(
        SETTINGS_CLEAN,
        "onCreate",
        (r"ViewCompat\.setOnApplyWindowInsetsListener", r"WindowCompat\.setDecorFitsSystemWindows"),
        "SettingsActivity insets handling",
    )
    require_marker_in_ui_entry(
        MAIN_CLEAN,
        r'(?:\btext\s*=|\bsetText\s*\(|\bsetTitle\s*\()[^\n;]*"\$\{[^"]*assistantName[^"]*\}[^"\n]*智能体"',
        "MainActivity assistant-name title",
    )
    forbid(strip_kotlin_literals(ADAPTER), "android.R.layout.simple_list_item_2", "ConversationAdapter scaffold row")
    if re.search(
        r'["\'][^"\']*(?:v0\.6\.5|会话状态机|悬浮层手动退出|自然语言媒体音量)[^"\']*["\']',
        MAIN_CLEAN,
        re.S,
    ):
        missing.append("MainActivity debug subtitle: still contains a v0.6.5/debug status literal")

    return missing


missing = collect_missing()
if missing:
    raise AssertionError("ui recovery contract is still missing:\n- " + "\n- ".join(missing))

print("PASS: v0.7 recovery UI contract")
