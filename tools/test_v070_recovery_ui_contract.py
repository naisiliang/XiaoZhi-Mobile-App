from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt").read_text("utf-8")
SETTINGS = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/SettingsActivity.kt").read_text("utf-8")
ADAPTER = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/conversation/ConversationAdapter.kt").read_text("utf-8")
MAIN_LAYOUT = ROOT / "app/src/main/res/layout/activity_main.xml"
SETTINGS_LAYOUT = ROOT / "app/src/main/res/layout/activity_settings.xml"


def collect_missing():
    missing = []

    def marker_text(marker):
        return marker.encode("unicode_escape").decode("ascii")

    def require_source(source, marker, context):
        if marker not in source:
            missing.append(f"{context}: missing {marker_text(marker)}")

    def forbid_source(source, marker, context):
        if marker in source:
            missing.append(f"{context}: still contains {marker_text(marker)}")

    def require_file(path, context):
        if not path.exists():
            missing.append(f"{context}: missing file {path.relative_to(ROOT)}")

    require_file(MAIN_LAYOUT, "MainActivity XML chat layout")
    require_file(SETTINGS_LAYOUT, "SettingsActivity XML settings layout")
    require_source(MAIN, "R.layout.activity_main", "MainActivity XML inflation")
    require_source(SETTINGS, "R.layout.activity_settings", "SettingsActivity XML inflation")
    require_source(MAIN, "${assistantName}智能体", "MainActivity assistant-name title")
    require_source(MAIN, "WindowInsets", "MainActivity insets handling")
    require_source(SETTINGS, "WindowInsets", "SettingsActivity insets handling")
    forbid_source(ADAPTER, "android.R.layout.simple_list_item_2", "ConversationAdapter scaffold row")
    forbid_source(MAIN, "v0.6.5：会话状态机 + 悬浮层手动退出 + 智能退出 + 自然语言媒体音量", "MainActivity debug subtitle")

    return missing


missing = collect_missing()
if missing:
    raise AssertionError("ui recovery contract is still missing:\n- " + "\n- ".join(missing))

print("PASS: v0.7 recovery UI contract")
