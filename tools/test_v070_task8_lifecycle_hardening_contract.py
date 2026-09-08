from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
WAKE = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt").read_text("utf-8")


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def function_body(name):
    match = re.search(rf"(?:private |public |internal |protected )?(?:override )?fun {name}\b[^{{]*\{{", WAKE)
    if not match:
        raise AssertionError(f"missing function {name}")
    depth = 1
    index = match.end()
    while depth and index < len(WAKE):
        if WAKE[index] == "{":
            depth += 1
        elif WAKE[index] == "}":
            depth -= 1
        index += 1
    if depth:
        raise AssertionError(f"unterminated function {name}")
    return WAKE[match.end():index - 1]


require(
    "lifecycleWorkerThreads" in WAKE,
    "startup/settings workers must be retained for coordinated teardown",
)
require(
    "Collections.synchronizedSet" in WAKE
    and "synchronized(lifecycleWorkerThreads)" in WAKE,
    "worker registry snapshots must not wait on the runtime work lock",
)
require(
    "launchLifecycleWorker(\"xiaozhi-wake-settings\"" in WAKE
    and "launchLifecycleWorker(\"xiaozhi-startup\"" in WAKE,
    "startup and wake-setting application must use the tracked worker launcher",
)
require(
    'launchLifecycleWorker("xiaozhi-kws"' in WAKE
    and 'launchLifecycleWorker("xiaozhi-local-asr"' in WAKE,
    "KWS and local ASR workers must use the same tracked launcher",
)
require(
    "audioRecordOwner" in WAKE
    and "audioRecordLock" in WAKE
    and "audioRecordOwners" in WAKE
    and "expected: AudioRecord?" in WAKE
    and "adoptAudioRecord" in WAKE,
    "AudioRecord release must be per-record and ownership-aware",
)
require(
    "interrupt()" in WAKE
    and "awaitThreadExit" in WAKE
    and "RUNTIME_WORKER_JOIN_TIMEOUT_MS" in WAKE
    and "join(" in WAKE,
    "service teardown must interrupt and bounded-wait retained workers before native release",
)
require(
    "kwsThread?.isAlive" in function_body("startKwsCapture")
    and "commandThread?.isAlive" in function_body("startKwsCapture"),
    "KWS restart must reject alive prior capture and command workers",
)
require(
    "kwsThread?.isAlive" in function_body("startLocalCommandRecognition"),
    "local ASR must not overlap an unfinished KWS capture",
)
require(
    "cancelScheduledKwsRestart()" in function_body("stopKwsCapture")
    and "releaseAudioRecord" not in function_body("stopKwsCapture"),
    "stopping KWS must cancel delayed restarts and defer AudioRecord release to its owner",
)
require(
    "restartGeneration" in function_body("scheduleKwsRestart")
    and "restartGeneration != kwsCaptureGeneration.get()" in function_body("scheduleKwsRestart"),
    "delayed KWS callbacks must be generation-bound",
)
require(
    "interruptRuntimeWorkers()" in function_body("onDestroy")
    and "stopAndJoinRuntimeWorkers()" not in function_body("onDestroy"),
    "onDestroy must not synchronously join runtime workers on the service thread",
)
require(
    "stopActiveCommandCapture()" in function_body("requestConversationExit")
    and "releaseAudioRecord" not in function_body("requestConversationExit"),
    "conversation exit must stop command audio without releasing it under a worker",
)
require(
    "nativeRuntimeReleased" in WAKE
    and "releaseNativeRuntime" in WAKE
    and "scheduleNativeRuntimeCleanup" in WAKE,
    "native resources must be released immediately only after workers stop, otherwise deferred",
)
require(
    "kwsCaptureGeneration" in WAKE
    and "captureGeneration == kwsCaptureGeneration" in WAKE,
    "stopped KWS generations must not post stale wake callbacks",
)
require(
    "pendingKwsRestartRunnable" in WAKE
    and "serviceDestroyed.get()" in WAKE[WAKE.find("pendingKwsRestartRunnable"):],
    "delayed KWS restart must be retained and reject callbacks after destroy",
)

print("PASS: Task 8 WakeService lifecycle teardown contract")
