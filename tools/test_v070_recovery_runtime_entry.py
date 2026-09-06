from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt").read_text("utf-8")
SETTINGS = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/SettingsActivity.kt").read_text("utf-8")
CONTROLLER_PATH = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/runtime/WakeServiceController.kt"
CONTROLLER = CONTROLLER_PATH.read_text("utf-8") if CONTROLLER_PATH.exists() else ""


def collect_missing():
    missing = []

    def strip_comments(source):
        source = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
        source = re.sub(r"(?m)//.*$", "", source)
        return source

    def strip_kotlin_literals(source):
        source = strip_comments(source)
        source = re.sub(r'""".*?"""', "", source, flags=re.S)
        source = re.sub(r'"(?:\\.|[^"\\])*"', '""', source, flags=re.S)
        source = re.sub(r"'(?:\\.|[^'\\])*'", "''", source, flags=re.S)
        return source

    def function_body(source, function_name):
        source = strip_kotlin_literals(source)
        pattern = re.compile(
            rf"(?:private |public |internal |protected )?(?:override )?fun {re.escape(function_name)}\b[^{{]*\{{"
        )
        match = pattern.search(source)
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

    def require(source, marker, context):
        if marker not in source:
            missing.append(f"{context}: missing {marker}")

    def require_regex(source, pattern, context):
        if not re.search(pattern, source, re.S):
            missing.append(f"{context}: missing pattern {pattern}")

    def require_body(source, function_name, marker, context):
        body = function_body(source, function_name)
        if body is None:
            missing.append(f"{context}: missing function {function_name}")
            return
        if marker not in body:
            missing.append(f"{context}: missing {marker}")

    def block_after_marker(source, marker):
        source = strip_kotlin_literals(source)
        marker_index = source.find(marker)
        if marker_index < 0:
            return None
        opening = source.find("{", marker_index + len(marker))
        if opening < 0:
            return None
        depth = 0
        for index in range(opening, len(source)):
            if source[index] == "{":
                depth += 1
            elif source[index] == "}":
                depth -= 1
                if depth == 0:
                    return strip_comments(source[opening + 1:index])
        return None

    def require_function_body_any(source, function_names, marker, context):
        for function_name in function_names:
            body = function_body(source, function_name)
            if body is not None and re.search(marker, body, re.S):
                return
        missing.append(f"{context}: missing {marker}")

    def require_marker_in_reachable_functions(source, entry_names, marker, context):
        clean_source = strip_kotlin_literals(source)
        functions = {}
        for match in re.finditer(
            r"(?:private |public |internal |protected )?(?:override )?fun\s+([A-Za-z_]\w*)\b[^{{]*\{{",
            clean_source,
        ):
            body = extract_block(clean_source, match.end() - 1)
            if body is not None:
                functions[match.group(1)] = body
        pending = list(entry_names)
        visited = set()
        while pending:
            function_name = pending.pop()
            if function_name in visited:
                continue
            visited.add(function_name)
            body = functions.get(function_name)
            if body is None:
                continue
            if re.search(marker, body, re.S):
                return
            pending.extend(
                name for name in re.findall(r"\b([A-Za-z_]\w*)\s*\(", body)
                if name in functions and name not in visited
            )
        missing.append(f"{context}: missing reachable {marker}")

    def require_shared_controller(context):
        if not CONTROLLER:
            missing.append(f"{context}: missing {CONTROLLER_PATH.relative_to(ROOT)}")
            return
        controller_clean = strip_kotlin_literals(CONTROLLER)
        if not re.search(r"\b(?:object|class)\s+WakeServiceController\b", controller_clean):
            missing.append(f"{context}: missing WakeServiceController declaration")
        shared_reference = (
            r"(?:import\s+com\.lchuang\.xiaozhimobile\.runtime\.WakeServiceController\b|"
            r"\bcom\.lchuang\.xiaozhimobile\.runtime\.WakeServiceController\b)"
        )
        for source, source_name in ((MAIN, "MainActivity"), (SETTINGS, "SettingsActivity")):
            if not re.search(shared_reference, strip_kotlin_comments(source), re.S):
                missing.append(f"{context}: {source_name} does not reference the shared runtime controller")

    def require_permission_branch(source, context):
        body = function_body(source, "requestNeededPermissions")
        if body is None:
            missing.append(f"{context}: missing function requestNeededPermissions")
            return
        body = strip_kotlin_literals(body)
        branch_patterns = (
            r"if\s*\(\s*checkSelfPermission\(\s*Manifest\.permission\.RECORD_AUDIO\s*\)\s*==\s*PackageManager\.PERMISSION_GRANTED\s*\)",
            r"if\s*\(\s*PackageManager\.PERMISSION_GRANTED\s*==\s*checkSelfPermission\(\s*Manifest\.permission\.RECORD_AUDIO\s*\)\s*\)",
            r"if\s*\(\s*ContextCompat\.checkSelfPermission\(\s*this(?:@MainActivity)?\s*,\s*Manifest\.permission\.RECORD_AUDIO\s*\)\s*==\s*PackageManager\.PERMISSION_GRANTED\s*\)",
            r"if\s*\(\s*ContextCompat\.checkSelfPermission\(\s*this(?:@MainActivity)?\s*,\s*Manifest\.permission\.RECORD_AUDIO\s*\)\s*==\s*ContextCompat\.PERMISSION_GRANTED\s*\)",
        )
        for pattern in branch_patterns:
            match = re.search(pattern, body, re.S)
            if not match:
                continue
            tail = body[match.end():]
            whitespace = len(tail) - len(tail.lstrip())
            opening = match.end() + whitespace
            if opening < len(body) and body[opening] == "{":
                branch_body = extract_block(body, opening)
                if branch_body is None:
                    continue
                if re.search(r"WakeServiceController\s*\.\s*start\s*\(", branch_body, re.S):
                    closing = opening
                    depth = 0
                    while closing < len(body):
                        if body[closing] == "{":
                            depth += 1
                        elif body[closing] == "}":
                            depth -= 1
                            if depth == 0:
                                break
                        closing += 1
                    after_branch = body[closing + 1:].lstrip() if closing < len(body) else ""
                    if after_branch.startswith("else"):
                        else_opening = after_branch.find("{")
                        if else_opening >= 0:
                            else_body = extract_block(after_branch, else_opening)
                            if else_body is not None and re.search(
                                r"WakeServiceController\s*\.\s*start\s*\(",
                                else_body,
                                re.S,
                            ):
                                continue
                    return
            else:
                tail_statement = tail.lstrip()
                start_match = re.match(r"WakeServiceController\s*\.\s*start\s*\(", tail_statement)
                if start_match:
                    else_match = re.search(r"\belse\b", tail_statement, re.S)
                    if else_match is None or start_match.end() <= else_match.start():
                        return
        missing.append(
            f"{context}: missing reachable WakeServiceController.start( in the granted RECORD_AUDIO branch"
        )

    def require_callback_start(source, context):
        callback_markers = (
            "onRequestPermissionsResult(",
            "registerForActivityResult(",
        )
        success_patterns = (
            (
                r"if\s*\(\s*[^{}]*grantResults[^{}]*==[^{}]*PackageManager\.PERMISSION_GRANTED[^{}]*\)\s*\{",
                True,
            ),
            (
                r"if\s*\(\s*[^{}]*\[[^\]]*Manifest\.permission\.RECORD_AUDIO[^\]]*\]\s*==\s*true[^{}]*\)\s*\{",
                False,
            ),
            (
                r"if\s*\(\s*[^{}]*ContextCompat\.checkSelfPermission[^{}]*Manifest\.permission\.RECORD_AUDIO[^{}]*==[^{}]*PackageManager\.PERMISSION_GRANTED[^{}]*\)\s*\{",
                False,
            ),
            (
                r"if\s*\(\s*[^{}]*ContextCompat\.checkSelfPermission[^{}]*Manifest\.permission\.RECORD_AUDIO[^{}]*==[^{}]*ContextCompat\.PERMISSION_GRANTED[^{}]*\)\s*\{",
                False,
            ),
        )
        for marker in callback_markers:
            callback_body = block_after_marker(source, marker)
            if callback_body is None:
                continue
            if not re.search(
                r"Manifest\.permission\.RECORD_AUDIO|REQUEST_PERMISSIONS",
                callback_body,
                re.S,
            ):
                continue
            for pattern, requires_request_code in success_patterns:
                match = re.search(pattern, callback_body, re.S)
                if not match:
                    continue
                if requires_request_code and not re.search(
                    r"\bREQUEST_PERMISSIONS\b",
                    callback_body[:match.end()],
                    re.S,
                ):
                    continue
                opening = match.end() - 1
                success_body = extract_block(callback_body, opening)
                if success_body is None:
                    continue
                if re.search(r"WakeServiceController\s*\.\s*start\s*\(", success_body, re.S):
                    return
        missing.append(
            f"{context}: missing callback/equivalent with permission-grant signal and WakeServiceController.start("
        )

    def extract_block(source, opening_index):
        depth = 0
        for index in range(opening_index, len(source)):
            if source[index] == "{":
                depth += 1
            elif source[index] == "}":
                depth -= 1
                if depth == 0:
                    return source[opening_index + 1:index]
        return None

    require(MAIN, "Manifest.permission.RECORD_AUDIO", "MainActivity microphone permission gate")
    require(MAIN, "PackageManager.PERMISSION_GRANTED", "MainActivity granted-permission branch")
    require_function_body_any(
        MAIN,
        ("submitText", "onTextResult"),
        r"WakeServiceController\s*\.\s*submitText\s*\(",
        "MainActivity typed submit route",
    )
    require_marker_in_reachable_functions(
        SETTINGS,
        ("onCreate", "saveSettings", "applyWakeSettingsIfRunning"),
        r"WakeServiceController\s*\.\s*(?:start|stop|applyWakeSettings)\s*\(",
        "SettingsActivity wake settings route",
    )
    require_shared_controller("WakeServiceController shared implementation")
    require_callback_start(MAIN, "MainActivity first-install permission grant path")
    require_permission_branch(MAIN, "MainActivity granted microphone branch")
    return missing


missing = collect_missing()
if missing:
    raise AssertionError("runtime entry recovery contract is still missing:\n- " + "\n- ".join(missing))

print("PASS: v0.7 recovery runtime entry contract")
