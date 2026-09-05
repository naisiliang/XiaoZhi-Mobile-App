from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt").read_text("utf-8")
SETTINGS = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/SettingsActivity.kt").read_text("utf-8")
WAKE = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt").read_text("utf-8")


def collect_missing():
    missing = []

    def function_body(source, function_name):
        match = re.search(
            rf"(?:private |public |internal |protected )?(?:override )?fun {function_name}\b[^{{]*\{{",
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

    def require(source, marker, context):
        if marker not in source:
            missing.append(f"{context}: missing {marker}")

    def require_body(source, function_name, marker, context):
        body = function_body(source, function_name)
        if body is None:
            missing.append(f"{context}: missing function {function_name}")
            return
        if marker not in body:
            missing.append(f"{context}: missing {marker}")

    def require_any_function_body(source, marker, context):
        for match in re.finditer(
            r"(?:private |public |internal |protected )?(?:override )?fun\s+[A-Za-z_]\w*\b[^{{]*\{",
            source,
        ):
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
                continue
            if marker in source[body_start:index - 1]:
                return
        missing.append(f"{context}: missing {marker}")

    def require_callback_path(source, markers, required_marker, context):
        for marker in markers:
            marker_index = source.find(marker)
            if marker_index < 0:
                continue
            opening = source.find("{", marker_index + len(marker))
            if opening < 0:
                continue
            depth = 0
            for index in range(opening, len(source)):
                if source[index] == "{":
                    depth += 1
                elif source[index] == "}":
                    depth -= 1
                    if depth == 0:
                        body = source[opening + 1:index]
                        if required_marker in body:
                            return
                        break
        missing.append(
            f"{context}: missing reachable {required_marker} inside {', '.join(markers)}"
        )

    def require_if_branch(source, condition, required_marker, context):
        marker_index = source.find(condition)
        if marker_index < 0:
            missing.append(f"{context}: missing {condition}")
            return
        opening = source.find("{", marker_index + len(condition))
        if opening < 0:
            missing.append(f"{context}: missing branch body for {condition}")
            return
        depth = 0
        for index in range(opening, len(source)):
            if source[index] == "{":
                depth += 1
            elif source[index] == "}":
                depth -= 1
                if depth == 0:
                    body = source[opening + 1:index]
                    if required_marker in body:
                        return
                    break
        missing.append(
            f"{context}: missing reachable {required_marker} inside {condition}"
        )

    require(MAIN, "Manifest.permission.RECORD_AUDIO", "MainActivity microphone permission gate")
    require(MAIN, "PackageManager.PERMISSION_GRANTED", "MainActivity granted-permission branch")
    require_any_function_body(
        MAIN,
        "WakeServiceController.submitText(",
        "MainActivity typed submit route",
    )
    require_any_function_body(
        SETTINGS,
        "WakeServiceController",
        "SettingsActivity wake settings route",
    )
    require_callback_path(
        MAIN,
        (
            "onRequestPermissionsResult(",
            "registerForActivityResult(",
            "ActivityResultContracts.RequestPermission",
            "ActivityResultContracts.RequestMultiplePermissions",
        ),
        "WakeServiceController.start(",
        "MainActivity first-install permission grant path",
    )
    require_if_branch(
        MAIN,
        "if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)",
        "WakeServiceController.start(",
        "MainActivity granted microphone branch",
    )
    return missing


missing = collect_missing()
if missing:
    raise AssertionError("runtime entry recovery contract is still missing:\n- " + "\n- ".join(missing))

print("PASS: v0.7 recovery runtime entry contract")
