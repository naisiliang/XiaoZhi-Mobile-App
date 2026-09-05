from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt").read_text("utf-8")
SETTINGS = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/SettingsActivity.kt").read_text("utf-8")
WAKE = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt").read_text("utf-8")
BRIDGE = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/conversation/ConversationAdapter.kt").read_text("utf-8")


def collect_missing():
    missing = []

    def require(source, marker, context):
        if marker not in source:
            missing.append(f"{context}: missing {marker}")

    def forbid(source, marker, context):
        if marker in source:
            missing.append(f"{context}: still contains {marker}")

    require(MAIN, "ACTION_SUBMIT_TEXT", "MainActivity typed submit action")
    require(MAIN, "EXTRA_TEXT", "MainActivity typed submit payload")
    require(MAIN, "processAssistantInput", "MainActivity shared assistant-input processor")
    require(SETTINGS, "processAssistantInput", "SettingsActivity shared assistant-input processor")
    require(WAKE, "processAssistantInput", "WakeService shared assistant-input processor")
    require(BRIDGE, "fun submitText(text: String)", "ConversationResultBridge text submission entry")
    forbid(MAIN, "ConversationResultBridge.submitText(text)", "MainActivity typed submit path")

    return missing


missing = collect_missing()
if missing:
    raise AssertionError("typed pipeline recovery contract is still missing:\n- " + "\n- ".join(missing))

print("PASS: v0.7 recovery typed pipeline contract")
