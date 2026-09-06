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

    MAIN_CLEAN = strip_comments(MAIN)
    SETTINGS_CLEAN = strip_comments(SETTINGS)
    WAKE_CLEAN = strip_comments(WAKE)

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
            container = strip_comments(container)
            extraction_patterns = (
                r"(?:intent\?\.\s*|intent\.\s*|extras\?\.\s*)?(?:getStringExtra|getCharSequenceExtra|getString|getParcelableExtra)\s*\(\s*EXTRA_TEXT\s*\)",
                r"EXTRA_TEXT\s*=\s*",
            )
            if not any(re.search(pattern, container, re.S) for pattern in extraction_patterns):
                return False

            assignment_patterns = (
                r"(?:val|var)\s+([A-Za-z_]\w*)\s*=\s*(?:intent\?\.\s*|intent\.\s*|extras\?\.\s*)?(?:getStringExtra|getCharSequenceExtra|getString|getParcelableExtra)\s*\(\s*EXTRA_TEXT\s*\)",
                r"(?:val|var)\s+([A-Za-z_]\w*)\s*=\s*[^\n;]*EXTRA_TEXT\s*\)",
            )
            for assignment in assignment_patterns:
                for match in re.finditer(assignment, container, re.S):
                    value_name = re.escape(match.group(1))
                    tail = container[match.end():]
                    if re.search(rf"processAssistantInput\s*\(\s*{value_name}\b", tail, re.S):
                        return True
                    if re.search(
                        rf"{value_name}\s*\?\.\s*let\s*\{{[\s\S]*?processAssistantInput\s*\(\s*it\b",
                        tail,
                        re.S,
                    ):
                        return True

            inline_extraction = (
                r"(?:intent\?\.\s*|intent\.\s*|extras\?\.\s*)?(?:getStringExtra|getCharSequenceExtra|getString|getParcelableExtra)\s*\(\s*EXTRA_TEXT\s*\)",
            )
            return any(
                re.search(
                    rf"processAssistantInput\s*\(\s*[^\n;]*{pattern}",
                    container,
                    re.S,
                )
                for pattern in inline_extraction
            )

        def candidate_helpers(branch_text, whole_text):
            stop_names = {
                "if", "when", "for", "while", "return", "require", "check", "set", "get",
                "val", "var", "println", "recreate", "start", "stop", "run", "let", "else",
                "true", "false", "null",
            }
            seen = []
            for helper_name in re.findall(r"\b([A-Za-z_]\w*)\s*\(", branch_text):
                if helper_name in stop_names or helper_name in seen:
                    continue
                seen.append(helper_name)
                helper_body = local_function_body(whole_text, helper_name)
                if helper_body is None:
                    helper_body = function_body(source, helper_name)
                if helper_body is not None:
                    yield helper_name, helper_body

        def branch_satisfies(branch_text):
            if has_text_processor(branch_text):
                return True
            for _, helper_body in candidate_helpers(branch_text, body):
                helper_body = strip_comments(helper_body)
                if has_text_processor(helper_body):
                    return True
            return False

        branch_openers = (
            r"if\s*\(\s*[^{}]*(?:intent\?\.action|action|intent\.action|this\.action)\s*==\s*ACTION_SUBMIT_TEXT[^{}]*\)\s*\{",
            r"if\s*\(\s*ACTION_SUBMIT_TEXT\s*==\s*[^{}]*(?:intent\?\.action|action|intent\.action|this\.action)[^{}]*\)\s*\{",
        )
        branch_bodies = []
        for opener in branch_openers:
            match = re.search(opener, body, re.S)
            if not match:
                continue
            opening = body.find("{", match.end() - 1)
            if opening < 0:
                continue
            branch_body = extract_braced_block(body, opening)
            if branch_body is None:
                continue
            branch_bodies.append(strip_comments(branch_body))

        when_match = re.search(r"when\s*\([^{}]*\)\s*\{", body, re.S)
        if when_match:
            when_body = extract_braced_block(body, when_match.end() - 1)
            if when_body is not None:
                case_match = re.search(r"\bACTION_SUBMIT_TEXT\s*->", when_body, re.S)
                if case_match:
                    case_tail = when_body[case_match.end():].lstrip()
                    if case_tail.startswith("{"):
                        case_body = extract_braced_block(case_tail, 0)
                    else:
                        case_body = case_tail.splitlines()[0]
                    if case_body is not None:
                        branch_bodies.append(strip_comments(case_body))

        for branch_body in branch_bodies:
            if branch_satisfies(branch_body):
                return

        missing.append(f"{context}: missing coupled ACTION_SUBMIT_TEXT -> EXTRA_TEXT -> processAssistantInput route")

    forbid(MAIN_CLEAN, "ConversationResultBridge.submitText(", "MainActivity typed submit path")
    require_any_function_body(
        MAIN_CLEAN,
        ("submitText", "onTextResult"),
        r"WakeServiceController\s*\.\s*submitText\s*\(",
        "MainActivity text submission route",
    )
    require_regex(WAKE_CLEAN, r"const val ACTION_SUBMIT_TEXT\b", "WakeService text action constant")
    require_regex(WAKE_CLEAN, r"const val EXTRA_TEXT\b", "WakeService text payload constant")
    require_regex(WAKE_CLEAN, r"fun\s+processAssistantInput\s*\(", "WakeService shared text processor")
    require_action_branch(WAKE_CLEAN, "WakeService text service action route")
    forbid(MAIN_CLEAN, "processAssistantInput(", "MainActivity typed pipeline local processor")
    forbid(SETTINGS_CLEAN, "processAssistantInput(", "SettingsActivity typed pipeline local processor")
    forbid(MAIN_CLEAN, "DeviceActionExecutor", "MainActivity direct device executor")
    forbid(SETTINGS_CLEAN, "DeviceActionExecutor", "SettingsActivity direct device executor")

    return missing


missing = collect_missing()
if missing:
    raise AssertionError("typed pipeline recovery contract is still missing:\n- " + "\n- ".join(missing))

print("PASS: v0.7 recovery typed pipeline contract")
