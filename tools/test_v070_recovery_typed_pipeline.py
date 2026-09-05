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

    def require_regex(source, pattern, context):
        if not re.search(pattern, source, re.S):
            missing.append(f"{context}: missing pattern {pattern}")

    def function_body(source, function_name):
        match = re.search(
            rf"(?:private |public |internal |protected )?(?:override )?fun {re.escape(function_name)}\b[^{{]*\{{",
            source,
        )
        if not match:
            return None
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
            return None
        return source[body_start:index - 1]

    def require_body(source, function_name, markers, context):
        body = function_body(source, function_name)
        if body is None:
            missing.append(f"{context}: missing function {function_name}")
            return
        for marker in markers:
            if not re.search(marker, body, re.S):
                missing.append(f"{context}: missing {marker}")

    def require_any_function_body(source, function_names, marker, context):
        for function_name in function_names:
            body = function_body(source, function_name)
            if body is not None and re.search(marker, body, re.S):
                return
        missing.append(f"{context}: missing {marker}")

    forbid(MAIN, "ConversationResultBridge.submitText(", "MainActivity typed submit path")
    require_any_function_body(
        MAIN,
        ("submitText", "onTextResult"),
        r"WakeServiceController\s*\.\s*submitText\s*\(",
        "MainActivity text submission route",
    )
    require_regex(WAKE, r"const val ACTION_SUBMIT_TEXT\b", "WakeService text action constant")
    require_regex(WAKE, r"const val EXTRA_TEXT\b", "WakeService text payload constant")
    require_regex(WAKE, r"fun\s+processAssistantInput\s*\(", "WakeService shared text processor")
    require_body(
        WAKE,
        "onStartCommand",
        (r"ACTION_SUBMIT_TEXT\b", r"EXTRA_TEXT\b", r"processAssistantInput\s*\("),
        "WakeService text service action route",
    )
    forbid(MAIN, "processAssistantInput(", "MainActivity typed pipeline local processor")
    forbid(SETTINGS, "processAssistantInput(", "SettingsActivity typed pipeline local processor")
    forbid(MAIN, "DeviceActionExecutor", "MainActivity direct device executor")
    forbid(SETTINGS, "DeviceActionExecutor", "SettingsActivity direct device executor")

    return missing


missing = collect_missing()
if missing:
    raise AssertionError("typed pipeline recovery contract is still missing:\n- " + "\n- ".join(missing))

print("PASS: v0.7 recovery typed pipeline contract")
