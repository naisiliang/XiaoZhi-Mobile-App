from pathlib import Path
import re

from v070_source_contract_utils import strip_kotlin_literals


ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt").read_text("utf-8")
SETTINGS = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/SettingsActivity.kt").read_text("utf-8")
WAKE = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt").read_text("utf-8")
CONTROLLER_PATH = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/runtime/WakeServiceController.kt"
CONTROLLER = CONTROLLER_PATH.read_text("utf-8") if CONTROLLER_PATH.exists() else ""


def collect_missing():
    missing = []

    MAIN_CLEAN = strip_kotlin_literals(MAIN)
    SETTINGS_CLEAN = strip_kotlin_literals(SETTINGS)
    WAKE_CLEAN = strip_kotlin_literals(WAKE)

    def forbid(source, marker, context):
        if marker in source:
            missing.append(f"{context}: still contains {marker}")

    def forbid_regex(source, pattern, context):
        if re.search(pattern, source, re.S):
            missing.append(f"{context}: still contains pattern {pattern}")

    def require_regex(source, pattern, context):
        if not re.search(pattern, source, re.S):
            missing.append(f"{context}: missing pattern {pattern}")

    def function_body(source, function_name):
        source = strip_kotlin_literals(source)
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

    def extract_delimited_block(source, opening_index, opener="(", closer=")"):
        depth = 0
        for index in range(opening_index, len(source)):
            if source[index] == opener:
                depth += 1
            elif source[index] == closer:
                depth -= 1
                if depth == 0:
                    return index
        return None

    def intent_receiver_scope(source, variable_name):
        """Return only the exact receiver block tied to this Intent assignment."""
        constructor = re.search(
            r"=\s*(?:(?:[A-Za-z_]\w*)\.)*Intent\s*\(",
            source,
            re.S,
        )
        if constructor is not None:
            closing = extract_delimited_block(source, constructor.end() - 1)
            if closing is not None:
                suffix = source[closing + 1:]
                scoped = re.match(r"\s*\.\s*(?:apply|also|run)\s*\{", suffix, re.S)
                if scoped is not None:
                    return extract_braced_block(source, closing + 1 + scoped.end() - 1)

        receiver = re.escape(variable_name)
        for scoped in re.finditer(
            rf"\b{receiver}\s*\.\s*(?:apply|also|run)\s*\{{|"
            rf"\bwith\s*\(\s*{receiver}\s*\)\s*\{{",
            source,
            re.S,
        ):
            return extract_braced_block(source, scoped.end() - 1)
        return None

    def local_function_body(container, function_name):
        container = strip_kotlin_literals(container)
        match = re.search(
            rf"(?:private |public |internal |protected )?(?:override )?fun {re.escape(function_name)}\b[^{{]*\{{",
            container,
        )
        if not match:
            return None
        return extract_braced_block(container, match.end() - 1)

    def forbid_unscoped_intents(source, context):
        for constructor in re.finditer(r"\bIntent\s*\(", source, re.S):
            closing = extract_delimited_block(source, constructor.end() - 1)
            if closing is None:
                continue
            arguments = source[constructor.end():closing]
            if not re.search(r"::class\s*\.\s*java\b", arguments, re.S):
                missing.append(f"{context}: unscoped Intent may dispatch a device action")

    def require_any_function_body(source, function_names, marker, context):
        for function_name in function_names:
            body = function_body(source, function_name)
            if body is not None and re.search(marker, body, re.S):
                return
        missing.append(f"{context}: missing {marker}")

    def require_controller_submit_route():
        if not CONTROLLER:
            missing.append(f"WakeServiceController submit route: missing {CONTROLLER_PATH.relative_to(ROOT)}")
            return
        controller_clean = strip_kotlin_literals(CONTROLLER)
        submit_signature = re.search(
            r"\bfun\s+submitText\s*\(([^)]*)\)",
            controller_clean,
            re.S,
        )
        if submit_signature is None:
            missing.append("WakeServiceController submit route: missing submitText parameters")
            return
        text_parameter_names = set()
        for parameter in submit_signature.group(1).split(","):
            parameter_match = re.match(
                r"\s*(?:vararg\s+)?([A-Za-z_]\w*)\s*:\s*([^=]+)",
                parameter,
                re.S,
            )
            if parameter_match and re.search(
                r"\b(?:String|CharSequence)\b",
                parameter_match.group(2),
                re.S,
            ):
                text_parameter_names.add(parameter_match.group(1))
        if not text_parameter_names:
            missing.append("WakeServiceController submit route: submitText has no text parameter")
            return
        body = function_body(CONTROLLER, "submitText")
        if body is None:
            missing.append("WakeServiceController submit route: missing function submitText")
            return
        intent_declarations = list(
            re.finditer(
                r"\b(?:val|var)\s+([A-Za-z_]\w*)"
                r"(?:\s*:\s*[^=\n]+)?\s*=\s*(?:(?:[A-Za-z_]\w*)\.)*Intent\s*\(",
                body,
                re.S,
            )
        )
        if not intent_declarations:
            missing.append("WakeServiceController submit route: missing an assigned Intent")
            return
        for index, declaration in enumerate(intent_declarations):
            variable_name_text = declaration.group(1)
            variable_name = re.escape(variable_name_text)
            end = intent_declarations[index + 1].start() if index + 1 < len(intent_declarations) else len(body)
            route = body[declaration.end():end]
            assignment_route = body[declaration.start():end]
            constructor = re.search(
                r"=\s*(?:(?:[A-Za-z_]\w*)\.)*Intent\s*\(",
                assignment_route,
                re.S,
            )
            if constructor is None:
                continue
            constructor_end = extract_delimited_block(assignment_route, constructor.end() - 1)
            if constructor_end is None:
                continue
            constructor_args = assignment_route[constructor.end():constructor_end]
            if not re.search(r"\bWakeService\b", constructor_args, re.S):
                continue
            receiver_scope = intent_receiver_scope(assignment_route, variable_name_text)
            action_on_intent = re.search(
                rf"\b{variable_name}\s*\.\s*(?:setAction\s*\(\s*|\baction\s*=\s*)"
                rf"WakeService\s*\.\s*ACTION_SUBMIT_TEXT\b",
                route,
                re.S,
            )
            action_in_scope = receiver_scope is not None and re.search(
                r"(?:\bsetAction\s*\(\s*|\baction\s*=\s*)"
                r"WakeService\s*\.\s*ACTION_SUBMIT_TEXT\b",
                receiver_scope,
                re.S,
            )
            payload_names = set(text_parameter_names)
            for _ in range(2):
                for alias in re.finditer(
                    r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*=\s*([^\n;]+)",
                    assignment_route,
                    re.S,
                ):
                    if any(
                        re.search(rf"\b{re.escape(name)}\b", alias.group(2), re.S)
                        for name in payload_names
                    ):
                        payload_names.add(alias.group(1))

            def extra_carries_payload(container, receiver=None):
                receiver_prefix = rf"\b{receiver}\s*\.\s*" if receiver else r"\b"
                return next(
                    (
                        match
                        for match in re.finditer(
                            receiver_prefix
                            + r"putExtra\s*\(\s*WakeService\s*\.\s*EXTRA_TEXT\s*,\s*"
                            r"([A-Za-z_]\w*)\b",
                            container,
                            re.S,
                        )
                        if match.group(1) in payload_names
                    ),
                    None,
                )

            extra_on_intent = extra_carries_payload(route, variable_name_text)
            extra_in_scope = (
                receiver_scope is not None and extra_carries_payload(receiver_scope)
            )
            coupled_configuration = (
                (action_on_intent is not None and extra_on_intent is not None)
                or (action_in_scope is not None and extra_in_scope is not None)
            )
            if not coupled_configuration:
                continue
            configuration_end = 0
            if action_on_intent is not None and extra_on_intent is not None:
                configuration_end = max(action_on_intent.end(), extra_on_intent.end())
            else:
                scope_start = route.find(receiver_scope)
                if scope_start < 0 or action_in_scope is None or extra_in_scope is None:
                    continue
                configuration_end = scope_start + max(
                    action_in_scope.end(),
                    extra_in_scope.end(),
                )
            if not re.search(
                rf"\b(?:startService|startForegroundService)\s*\([^)]*\b{variable_name}\b[^)]*\)",
                route[configuration_end:],
                re.S,
            ):
                continue
            return
        missing.append(
            "WakeServiceController submit route: assigned Intent is not configured with "
            "ACTION_SUBMIT_TEXT/EXTRA_TEXT and dispatched"
        )

    def require_action_branch(source, context):
        body = function_body(source, "onStartCommand")
        if body is None:
            missing.append(f"{context}: missing function onStartCommand")
            return
        body = strip_kotlin_literals(body)

        def has_text_processor(container):
            container = strip_kotlin_literals(container)
            extraction_patterns = (
                r"(?:[A-Za-z_]\w*(?:\?\.)?\s*)?(?:getStringExtra|getCharSequenceExtra|getString|getParcelableExtra)\s*\(\s*EXTRA_TEXT\s*\)",
                r"EXTRA_TEXT\s*=\s*",
            )
            if not any(re.search(pattern, container, re.S) for pattern in extraction_patterns):
                return False
            if not re.search(r"\bAssistantRequestSource\.TEXT\b", container, re.S):
                return False

            assignment_patterns = (
                r"(?:val|var)\s+([A-Za-z_]\w*)\s*=\s*(?:[A-Za-z_]\w*(?:\?\.)?\s*)?(?:getStringExtra|getCharSequenceExtra|getString|getParcelableExtra)\s*\(\s*EXTRA_TEXT\s*\)",
                r"(?:val|var)\s+([A-Za-z_]\w*)\s*=\s*[^\n;]*EXTRA_TEXT\s*\)",
            )
            for assignment in assignment_patterns:
                for match in re.finditer(assignment, container, re.S):
                    value_name = re.escape(match.group(1))
                    tail = container[match.end():]
                    if re.search(
                        rf"processAssistantInput\s*\(\s*{value_name}\b[^;)]*"
                        r"\bAssistantRequestSource\.TEXT\b",
                        tail,
                        re.S,
                    ):
                        return True
                    if re.search(
                        rf"{value_name}\s*\?\.\s*let\s*\{{[\s\S]*?"
                        r"processAssistantInput\s*\(\s*it\b[^;)]*"
                        r"\bAssistantRequestSource\.TEXT\b",
                        tail,
                        re.S,
                    ):
                        return True

            inline_extraction = (
                r"(?:[A-Za-z_]\w*(?:\?\.)?\s*)?(?:getStringExtra|getCharSequenceExtra|getString|getParcelableExtra)\s*\(\s*EXTRA_TEXT\s*\)",
            )
            return any(
                re.search(
                    rf"processAssistantInput\s*\(\s*[^\n;]*{pattern}[^;)]*"
                    r"\bAssistantRequestSource\.TEXT\b",
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
            for call in re.finditer(r"\b([A-Za-z_]\w*)\s*\(([^()\n]*)\)", branch_text):
                helper_name = call.group(1)
                if helper_name in stop_names or helper_name in seen:
                    continue
                if not re.search(r"\bintent\b", call.group(2)):
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
                helper_body = strip_kotlin_literals(helper_body)
                if has_text_processor(helper_body):
                    return True
            return False

        branch_openers = (
            r"if\s*\(\s*[^{}]*(?:intent\?\.\s*action|intent\.\s*action|this\.\s*action)\s*==\s*ACTION_SUBMIT_TEXT[^{}]*\)\s*\{",
            r"if\s*\(\s*ACTION_SUBMIT_TEXT\s*==\s*[^{}]*(?:intent\?\.\s*action|intent\.\s*action|this\.\s*action)[^{}]*\)\s*\{",
        )
        branch_bodies = []
        for opener in branch_openers:
            for match in re.finditer(opener, body, re.S):
                opening = match.end() - 1
                branch_body = extract_braced_block(body, opening)
                if branch_body is not None:
                    branch_bodies.append(strip_kotlin_literals(branch_body))

        when_pattern = r"when\s*\(\s*[^{}]*(?:intent\?\.\s*action|intent\.\s*action)[^{}]*\)\s*\{"
        for when_match in re.finditer(when_pattern, body, re.S):
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
                        branch_bodies.append(strip_kotlin_literals(case_body))

        for branch_body in branch_bodies:
            if branch_satisfies(branch_body):
                return

        missing.append(f"{context}: missing coupled ACTION_SUBMIT_TEXT -> EXTRA_TEXT -> processAssistantInput route")

    forbid_regex(
        MAIN_CLEAN,
        r"ConversationResultBridge\s*\.\s*submitText\s*\(",
        "MainActivity typed submit path",
    )
    require_any_function_body(
        MAIN_CLEAN,
        ("submitText", "onTextResult"),
        r"WakeServiceController\s*\.\s*submitText\s*\(",
        "MainActivity text submission route",
    )
    require_controller_submit_route()
    require_regex(WAKE_CLEAN, r"const val ACTION_SUBMIT_TEXT\b", "WakeService text action constant")
    require_regex(WAKE_CLEAN, r"const val EXTRA_TEXT\b", "WakeService text payload constant")
    require_regex(
        WAKE_CLEAN,
        r"fun\s+processAssistantInput\s*\([^)]*\b(?:source|requestSource)\b[^)]*\)",
        "WakeService shared text processor with request source",
    )
    require_action_branch(WAKE_CLEAN, "WakeService text service action route")
    forbid_regex(
        MAIN_CLEAN,
        r"\bprocessAssistantInput\s*\(",
        "MainActivity typed pipeline local processor",
    )
    forbid_regex(
        SETTINGS_CLEAN,
        r"\bprocessAssistantInput\s*\(",
        "SettingsActivity typed pipeline local processor",
    )
    direct_device_tokens = (
        r"DeviceActionExecutor",
        r"PhoneController",
        r"MapController",
        r"AppLauncher",
        r"LocationProvider",
        r"MediaVolumeController",
        r"TorchController",
        r"CommandRouter",
        r"ToolDispatcher",
        r"SafeToolExecutor",
        r"AppExitController",
        r"DeviceAction",
        r"DeviceExecutionResult",
        r"ExecutionFeedbackCoordinator",
        r"AudioManager",
        r"CameraManager",
        r"LocationManager",
        r"MediaPlayer",
        r"MediaSession",
        r"Vibrator",
        r"PowerManager",
    )
    direct_device_calls = (
        r"openApp",
        r"openMap",
        r"searchNearby",
        r"navigate",
        r"setMediaVolume",
        r"mediaPlay",
        r"mediaPause",
        r"setTorchMode",
        r"setFlashlight",
        r"setStreamVolume",
        r"adjustVolume",
        r"executeCommand",
        r"executeAction",
        r"dispatchTool",
        r"performDeviceAction",
    )
    direct_device_pattern = (
        r"\b(?:" + "|".join(direct_device_tokens) + r")\b"
        r"|\.(?:" + "|".join(direct_device_calls) + r")\s*\("
    )
    forbid_regex(MAIN_CLEAN, direct_device_pattern, "MainActivity direct device execution")
    forbid_regex(SETTINGS_CLEAN, direct_device_pattern, "SettingsActivity direct device execution")
    activity_direct_dispatch_pattern = (
        r"\b(?:startService|startForegroundService|sendBroadcast|sendOrderedBroadcast|"
        r"startActivityForResult)\s*\("
        r"|\b(?:ContextCompat\s*\.\s*)?startForegroundService\s*\("
        r"|\b(?:Intent|Uri)\s*\([^\n;]*(?:ACTION_VIEW|ACTION_CALL|ACTION_DIAL|"
        r"ACTION_SEND|ACTION_MEDIA_BUTTON)"
    )
    forbid_regex(
        MAIN_CLEAN,
        activity_direct_dispatch_pattern,
        "MainActivity direct device dispatch",
    )
    forbid_regex(
        SETTINGS_CLEAN,
        activity_direct_dispatch_pattern,
        "SettingsActivity direct device dispatch",
    )
    forbid_unscoped_intents(MAIN_CLEAN, "MainActivity direct device dispatch")
    forbid_unscoped_intents(SETTINGS_CLEAN, "SettingsActivity direct device dispatch")

    return missing


missing = collect_missing()
if missing:
    raise AssertionError("typed pipeline recovery contract is still missing:\n- " + "\n- ".join(missing))

print("PASS: v0.7 recovery typed pipeline contract")
