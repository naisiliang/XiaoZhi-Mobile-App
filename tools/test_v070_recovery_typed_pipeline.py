from pathlib import Path
import re


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

    require(MAIN, "ACTION_SUBMIT_TEXT", "MainActivity typed submit action")
    require(MAIN, "EXTRA_TEXT", "MainActivity typed submit payload")
    require(MAIN, "processAssistantInput", "MainActivity shared assistant-input processor")
    require(SETTINGS, "processAssistantInput", "SettingsActivity shared assistant-input processor")
    require(WAKE, "processAssistantInput", "WakeService shared assistant-input processor")
    require(BRIDGE, "fun submitText(text: String)", "ConversationResultBridge text submission entry")
    forbid(MAIN, "ConversationResultBridge.submitText(", "MainActivity typed submit path")
    require_body(
        MAIN,
        "onTextResult",
        ("ACTION_SUBMIT_TEXT", "EXTRA_TEXT", "processAssistantInput"),
        "MainActivity typed submit path",
    )

    return missing


missing = collect_missing()
if missing:
    raise AssertionError("typed pipeline recovery contract is still missing:\n- " + "\n- ".join(missing))

print("PASS: v0.7 recovery typed pipeline contract")
