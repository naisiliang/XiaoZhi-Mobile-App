from pathlib import Path
import re
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
HISTORY = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/conversation/ConversationHistoryActivity.kt"
ADAPTER = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/conversation/ConversationAdapter.kt"
HISTORY_LAYOUT = ROOT / "app/src/main/res/layout/activity_conversation_history.xml"
ITEM_LAYOUT = ROOT / "app/src/main/res/layout/item_conversation_history.xml"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def method_body(source, name):
    match = re.search(rf"\bfun\s+{re.escape(name)}\s*\([^)]*\)\s*\{{", source, re.S)
    require(match is not None, f"ConversationHistoryActivity.{name} is missing")
    depth = 1
    index = match.end()
    while depth and index < len(source):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
        index += 1
    require(depth == 0, f"ConversationHistoryActivity.{name} is unbalanced")
    return source[match.end() : index - 1]


def main():
    require(HISTORY_LAYOUT.exists(), "missing XML conversation history layout")
    require(ITEM_LAYOUT.exists(), "missing XML conversation history item layout")
    history = HISTORY.read_text(encoding="utf-8")
    adapter = ADAPTER.read_text(encoding="utf-8")
    layout = HISTORY_LAYOUT.read_text(encoding="utf-8")
    item = ITEM_LAYOUT.read_text(encoding="utf-8")
    for path, source, label in (
        (HISTORY_LAYOUT, layout, "history layout"),
        (ITEM_LAYOUT, item, "history item layout"),
    ):
        try:
            ET.fromstring(source)
        except ET.ParseError as error:
            raise AssertionError(f"{label} is malformed: {error}")

    require("<androidx.recyclerview.widget.RecyclerView" in layout, "history layout must contain RecyclerView")
    require("@+id/history_root" in layout and "@+id/history_header" in layout, "history layout root/header ids are missing")
    require("@+id/history_list" in layout and "clipToPadding" in layout, "history list must be inset-safe")
    for view_id in ("history_session_title", "history_session_meta", "history_session_status"):
        require(f"@+id/{view_id}" in item, f"history item control is missing: {view_id}")

    on_create = method_body(history, "onCreate")
    on_destroy = method_body(history, "onDestroy")
    configure_insets = method_body(history, "configureInsets")
    for marker in (
        "setContentView(R.layout.activity_conversation_history)",
        "historyList.adapter",
        "HistoryAdapter",
    ):
        require(marker in history or marker in on_create, f"history runtime wiring is missing: {marker}")
    require("repository.loadHistoryPage()" in on_create, "history must load a bounded page")
    require("repository.loadHistory()" not in on_create, "history must not load an unbounded session list")
    for marker in (
        "WindowCompat.setDecorFitsSystemWindows(window, false)",
        "ViewCompat.setOnApplyWindowInsetsListener",
        "WindowInsetsCompat.Type.systemBars()",
        "WindowInsetsCompat.Type.ime()",
        "historyHeader.setPadding",
        "historyList.setPadding",
    ):
        require(marker in configure_insets, f"history Insets wiring is missing: {marker}")
    require("startWakeSession(" not in history, "opening history must not start a wake session")
    require("ConversationSessionStore.manager(" not in history, "history must not own a conversation manager")
    require("setPadding(32, 32, 32, 16)" not in history, "history must not use fixed status-bar padding")
    require("DateFormat.getDateTimeInstance" in history, "history must format timestamps for humans")
    require("startedAtMs}" not in history and "startedAtMs}" not in adapter, "history must not render raw epoch timestamps")
    require("history_session_meta" in history, "history adapter must bind readable session metadata")
    require("runtimeStatusStore" not in history, "history must remain read-only and independent of wake runtime")
    require("repository" in on_destroy or "super.onDestroy()" in on_destroy, "history lifecycle must remain explicit")
    print("PASS: v0.7 history Insets and read-only contract")


if __name__ == "__main__":
    main()
