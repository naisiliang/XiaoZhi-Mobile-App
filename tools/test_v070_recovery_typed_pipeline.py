from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt").read_text("utf-8")
SETTINGS = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/SettingsActivity.kt").read_text("utf-8")
WAKE = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt").read_text("utf-8")
BRIDGE = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/conversation/ConversationAdapter.kt").read_text("utf-8")


def collect_missing():
    missing = []

    def strip_comments(source):
        source = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
        source = re.sub(r"(?m)//.*$", "", source)
        return source

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
        source = strip_comments(source)
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

    def local_function_body(container, function_name):
        container = strip_comments(container)
        match = re.search(
            rf"(?:private |public |internal |protected )?(?:override )?fun {re.escape(function_name)}\b[^{{]*\{{",
            container,
        )
        if not match:
            return None
        return extract_braced_block(container, match.end() - 1)

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

    def require_action_branch(source, context):
        body = function_body(source, "onStartCommand")
        if body is None:
            missing.append(f"{context}: missing function onStartCommand")
            return
        body = strip_comments(body)

        def has_text_processor(container):
            extraction_patterns = (
                r"(?:val\s+\w+\s*=\s*)?(?:intent\?\.)?(?:getStringExtra|getCharSequenceExtra|getString|getParcelableExtra|extras\?\.getString)\s*\(\s*EXTRA_TEXT\s*\)",
                r"EXTRA_TEXT\s*=\s*",
                r"EXTRA_TEXT\b[\s\S]{0,200}(?:let\s*\{|\?.|!!|processAssistantInput\s*\()",
            )
            process_patterns = (
                r"processAssistantInput\s*\(",
                r"processAssistantInput\b",
            )
            if not any(re.search(pattern, container, re.S) for pattern in extraction_patterns):
                return False
            return any(re.search(pattern, container, re.S) for pattern in process_patterns)

        def inspect_helper(branch_text, whole_text):
            helper_candidates = re.findall(r"\b([A-Za-z_]\w*)\s*\(", branch_text)
            for helper_name in helper_candidates:
                if helper_name in {
                    "if", "when", "for", "while", "return", "require", "check", "set", "get",
                    "val", "var", "println", "recreate", "start", "stop", "run", "let",
                }:
                    continue
                if not re.match(r"^(handle|route|dispatch|forward|submit|process|apply|send)", helper_name, re.I):
                    continue
                helper_body = local_function_body(whole_text, helper_name)
                if helper_body is None:
                    helper_body = function_body(source, helper_name)
                if helper_body is not None and has_text_processor(helper_body):
                    return True
            return False

        def branch_satisfies(branch_text):
            if has_text_processor(branch_text):
                return True
            return inspect_helper(branch_text, body)

        if_match = re.search(
            r"if\s*\(\s*[^{}]*ACTION_SUBMIT_TEXT[^{}]*\)\s*\{",
            body,
            re.S,
        )
        if if_match:
            opening = body.find("{", if_match.end() - 1)
            if opening >= 0:
                branch_body = extract_braced_block(body, opening)
                if branch_body is not None and branch_satisfies(branch_body):
                    return

        when_match = re.search(r"ACTION_SUBMIT_TEXT\s*->", body)
        if when_match:
            after_arrow = body[when_match.end():].lstrip()
            if after_arrow.startswith("{"):
                branch_body = extract_braced_block(after_arrow, 0)
                if branch_body is not None and branch_satisfies(branch_body):
                    return
            else:
                line = after_arrow.splitlines()[0].strip()
                if branch_satisfies(line):
                    return

        missing.append(f"{context}: missing coupled ACTION_SUBMIT_TEXT -> EXTRA_TEXT -> processAssistantInput route")

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
    require_action_branch(WAKE, "WakeService text service action route")
    forbid(MAIN, "processAssistantInput(", "MainActivity typed pipeline local processor")
    forbid(SETTINGS, "processAssistantInput(", "SettingsActivity typed pipeline local processor")
    forbid(MAIN, "DeviceActionExecutor", "MainActivity direct device executor")
    forbid(SETTINGS, "DeviceActionExecutor", "SettingsActivity direct device executor")

    return missing


missing = collect_missing()
if missing:
    raise AssertionError("typed pipeline recovery contract is still missing:\n- " + "\n- ".join(missing))

print("PASS: v0.7 recovery typed pipeline contract")
