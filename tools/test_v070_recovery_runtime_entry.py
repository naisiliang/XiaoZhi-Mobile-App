from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt").read_text("utf-8")
SETTINGS = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/SettingsActivity.kt").read_text("utf-8")
WAKE = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt").read_text("utf-8")


def collect_missing():
    missing = []

    def strip_comments(source):
        source = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
        source = re.sub(r"(?m)//.*$", "", source)
        return source

    def function_body(source, function_name):
        source = strip_comments(source)
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

    def require_permission_branch(source, context):
        body = function_body(source, "requestNeededPermissions")
        if body is None:
            missing.append(f"{context}: missing function requestNeededPermissions")
            return
        body = strip_comments(body)
        if "WakeServiceController.start(" not in body:
            missing.append(f"{context}: missing WakeServiceController.start(")
            return
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
            opening = body.find("{", match.end())
            if opening < 0:
                continue
            depth = 0
            for index in range(opening, len(body)):
                if body[index] == "{":
                    depth += 1
                elif body[index] == "}":
                    depth -= 1
                    if depth == 0:
                        branch_body = body[opening + 1:index]
                        if re.search(r"WakeServiceController\s*\.\s*start\s*\(", branch_body, re.S):
                            return
                        break
        missing.append(
            f"{context}: missing reachable WakeServiceController.start( in the granted RECORD_AUDIO branch"
        )

    def require_callback_start(source, context):
        callback_markers = (
            "onRequestPermissionsResult(",
            "registerForActivityResult(",
        )
        grant_markers = (
            "grantResults",
            "PackageManager.PERMISSION_GRANTED",
            "allGranted",
            "Manifest.permission.RECORD_AUDIO",
        )
        for marker in callback_markers:
            callback_body = block_after_marker(source, marker)
            if callback_body is None:
                continue
            callback_body = strip_comments(callback_body)
            if not re.search(r"WakeServiceController\s*\.\s*start\s*\(", callback_body, re.S):
                continue
            success_patterns = (
                r"if\s*\(\s*[^)]*grantResults[^)]*==[^)]*PackageManager\.PERMISSION_GRANTED[^)]*\)\s*\{",
                r"if\s*\(\s*allGranted\s*\)\s*\{",
                r"if\s*\(\s*[^)]*ContextCompat\.checkSelfPermission[^)]*==[^)]*PackageManager\.PERMISSION_GRANTED[^)]*\)\s*\{",
                r"if\s*\(\s*[^)]*ContextCompat\.checkSelfPermission[^)]*==[^)]*ContextCompat\.PERMISSION_GRANTED[^)]*\)\s*\{",
            )
            for pattern in success_patterns:
                match = re.search(pattern, callback_body, re.S)
                if not match:
                    continue
                opening = callback_body.find("{", match.end())
                if opening < 0:
                    continue
                depth = 0
                for index in range(opening, len(callback_body)):
                    if callback_body[index] == "{":
                        depth += 1
                    elif callback_body[index] == "}":
                        depth -= 1
                        if depth == 0:
                            success_body = callback_body[opening + 1:index]
                            if re.search(r"WakeServiceController\s*\.\s*start\s*\(", success_body, re.S):
                                return
                            break
        missing.append(
            f"{context}: missing callback/equivalent with permission-grant signal and WakeServiceController.start("
        )

    require(MAIN, "Manifest.permission.RECORD_AUDIO", "MainActivity microphone permission gate")
    require(MAIN, "PackageManager.PERMISSION_GRANTED", "MainActivity granted-permission branch")
    require_function_body_any(
        MAIN,
        ("submitText", "onTextResult"),
        r"WakeServiceController\s*\.\s*submitText\s*\(",
        "MainActivity typed submit route",
    )
    require_function_body_any(
        SETTINGS,
        ("saveSettings", "applyWakeSettingsIfRunning"),
        r"WakeServiceController\b",
        "SettingsActivity wake settings route",
    )
    require_callback_start(MAIN, "MainActivity first-install permission grant path")
    require_permission_branch(MAIN, "MainActivity granted microphone branch")
    return missing


missing = collect_missing()
if missing:
    raise AssertionError("runtime entry recovery contract is still missing:\n- " + "\n- ".join(missing))

print("PASS: v0.7 recovery runtime entry contract")
