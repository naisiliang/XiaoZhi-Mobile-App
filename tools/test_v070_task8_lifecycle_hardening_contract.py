from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WAKE = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt").read_text("utf-8")


def require(condition, message):
    if not condition:
        raise AssertionError(message)


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
    and "expected: AudioRecord?" in WAKE
    and "adoptAudioRecord" in WAKE,
    "AudioRecord release must be ownership-aware",
)
require(
    "interrupt()" in WAKE
    and "awaitThreadExit" in WAKE
    and "RUNTIME_WORKER_JOIN_TIMEOUT_MS" in WAKE
    and "join(" in WAKE,
    "service teardown must interrupt and bounded-wait retained workers before native release",
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
