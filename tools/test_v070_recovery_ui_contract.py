from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt").read_text("utf-8")
SETTINGS = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/SettingsActivity.kt").read_text("utf-8")
ADAPTER = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/conversation/ConversationAdapter.kt").read_text("utf-8")
MAIN_LAYOUT = ROOT / "app/src/main/res/layout/activity_main_chat.xml"
SETTINGS_LAYOUT = ROOT / "app/src/main/res/layout/activity_settings.xml"


def collect_missing():
    missing = []

    def require(source, marker, context):
        if marker not in source:
            missing.append(f"{context}: missing {marker}")

    def forbid(source, marker, context):
        if marker in source:
            missing.append(f"{context}: still contains {marker}")

    def require_regex(source, pattern, context):
        if not re.search(pattern, source, re.S):
            missing.append(f"{context}: missing pattern {pattern}")

    def require_body(source, function_name, markers, context):
        match = re.search(rf"(?:private |public |internal |protected )?fun {function_name}\b[^{{]*\{{", source)
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
            if marker not in body:
                missing.append(f"{context}: missing {marker}")

    def require_file(path, markers, context):
        if not path.exists():
            missing.append(f"{context}: missing file {path.relative_to(ROOT)}")
            return
        source = path.read_text("utf-8")
        if not source.strip():
            missing.append(f"{context}: empty file {path.relative_to(ROOT)}")
            return
        for marker in markers:
            if marker not in source:
                missing.append(f"{context}: missing {marker}")

    require_file(
        MAIN_LAYOUT,
        ("RecyclerView", "EditText"),
        "MainActivity chat layout",
    )
    require_file(
        SETTINGS_LAYOUT,
        ("ScrollView", "LinearLayout", "android:orientation=\"vertical\""),
        "SettingsActivity settings layout",
    )
    require_body(
        MAIN,
        "onCreate",
        ("setContentView(R.layout.activity_main_chat)",),
        "MainActivity XML inflation",
    )
    require_body(
        SETTINGS,
        "onCreate",
        ("setContentView(R.layout.activity_settings)",),
        "SettingsActivity XML inflation",
    )
    require_body(
        MAIN,
        "onCreate",
        ("ViewCompat.setOnApplyWindowInsetsListener", "WindowCompat.setDecorFitsSystemWindows"),
        "MainActivity insets handling",
    )
    require_body(
        SETTINGS,
        "onCreate",
        ("ViewCompat.setOnApplyWindowInsetsListener", "WindowCompat.setDecorFitsSystemWindows"),
        "SettingsActivity insets handling",
    )
    require_regex(
        MAIN,
        r'"\$\{[^"]*assistantName[^"]*\}.*智能体"',
        "MainActivity assistant-name title",
    )
    forbid(ADAPTER, "android.R.layout.simple_list_item_2", "ConversationAdapter scaffold row")
    forbid(MAIN, "v0.6.5：会话状态机 + 悬浮层手动退出 + 智能退出 + 自然语言媒体音量", "MainActivity debug subtitle")

    return missing


missing = collect_missing()
if missing:
    raise AssertionError("ui recovery contract is still missing:\n- " + "\n- ".join(missing))

print("PASS: v0.7 recovery UI contract")
