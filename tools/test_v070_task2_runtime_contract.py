from pathlib import Path
import re

from v070_source_contract_utils import strip_kotlin_literals


ROOT = Path(__file__).resolve().parents[1]
SERVICE = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt").read_text("utf-8")
CONTROLLER = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/runtime/WakeServiceController.kt").read_text("utf-8")
SETTINGS = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/SettingsActivity.kt").read_text("utf-8")


def function_body(source, name):
    clean = strip_kotlin_literals(source)
    match = re.search(rf"(?:private |public |internal |protected )?(?:override )?fun {name}\b[^{{]*\{{", clean)
    if not match:
        raise AssertionError(f"missing function {name}")
    depth = 1
    index = match.end()
    while depth and index < len(clean):
        if clean[index] == "{":
            depth += 1
        elif clean[index] == "}":
            depth -= 1
        index += 1
    if depth:
        raise AssertionError(f"unterminated function {name}")
    return clean[match.end():index - 1]


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def main():
    start_settings = function_body(SETTINGS, "startWakeService")
    permission_callback = function_body(SETTINGS, "onRequestPermissionsResult")
    require(
        "Manifest.permission.RECORD_AUDIO" in start_settings,
        "Settings start must gate WakeService on RECORD_AUDIO",
    )
    require(
        "requestPermissions" in start_settings,
        "Settings start must request RECORD_AUDIO when it is missing",
    )
    require(
        "REQUEST_AUDIO_PERMISSION" in permission_callback
        and "WakeServiceController.start" in permission_callback,
        "Settings must start WakeService after an audio permission grant",
    )

    stop_body = function_body(CONTROLLER, "stop")
    apply_body = function_body(CONTROLLER, "applyWakeSettings")
    require(
        "stopService" in stop_body,
        "stop must use stopService instead of starting a background service",
    )
    require(
        "dispatchCommand" in apply_body,
        "applyWakeSettings must use the foreground-safe command dispatcher",
    )
    require(
        re.search(r"fun dispatchCommand\b[\s\S]*ContextCompat\.startForegroundService", CONTROLLER),
        "command dispatcher must use ContextCompat.startForegroundService on O+",
    )

    require(
        "private val runtimeLifecycleLock = Any()" in SERVICE,
        "startup and wake-setting application must share a lifecycle lock",
    )
    require(
        SERVICE.count("synchronized(runtimeLifecycleLock)") >= 2,
        "startup and wake-setting application must be serialized",
    )
    capture_body = function_body(SERVICE, "startKwsCapture")
    require(
        "synchronized(runtimeLifecycleLock)" in capture_body,
        "delayed KWS restarts must not race with wake-setting application",
    )
    apply_start = SERVICE.index("ACTION_APPLY_WAKE_SETTINGS")
    startup_start = SERVICE.index('"xiaozhi-startup"')
    apply_region = SERVICE[apply_start:startup_start]
    require(
        "stopKwsCapture()" in apply_region and "awaitThreadExit(kwsThread" in apply_region,
        "wake-setting application must wait for the previous KWS thread to stop",
    )

    startup_start = SERVICE.index('launchLifecycleWorker("xiaozhi-startup")')
    startup_error_match = re.search(
        r"catch \(e: Throwable\) \{([\s\S]*?)\n\s*\}\s*\n\s*\}\s*\n\s*\}\s*\n\s*return START_STICKY",
        SERVICE[startup_start:],
    )
    require(startup_error_match, "missing serialized startup worker")
    startup_error = startup_error_match.group(1)
    require(
        "running.set(false)" in startup_error
        and startup_error.index("running.set(false)") < startup_error.index("WakeRuntimeStatus.ERROR"),
        "startup failure must clear running before publishing ERROR",
    )

    kws_capture = function_body(SERVICE, "startKwsCapture")
    require(
        "Manifest.permission.RECORD_AUDIO" in kws_capture
        and "running.set(false)" in kws_capture
        and "WakeRuntimeStatus.ERROR" in kws_capture,
        "missing microphone permission must transition the runtime to ERROR",
    )
    require(
        "running.set(false)" in kws_capture
        and "WakeRuntimeStatus.ERROR" in kws_capture,
        "KWS capture failure must make the runtime restartable",
    )
    print("PASS: Task 2 runtime lifecycle and Settings permission contract")


if __name__ == "__main__":
    main()
