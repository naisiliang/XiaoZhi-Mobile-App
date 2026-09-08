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
    "launchLifecycleWorker(\"xiaozhi-wake-settings\"" in WAKE
    and "launchLifecycleWorker(\"xiaozhi-startup\"" in WAKE,
    "startup and wake-setting application must use the tracked worker launcher",
)
require(
    "interrupt()" in WAKE and "join()" in WAKE,
    "service teardown must interrupt and join retained workers before native release",
)
require(
    "pendingKwsRestartRunnable" in WAKE
    and "serviceDestroyed.get()" in WAKE[WAKE.find("pendingKwsRestartRunnable"):],
    "delayed KWS restart must be retained and reject callbacks after destroy",
)

print("PASS: Task 8 WakeService lifecycle teardown contract")
