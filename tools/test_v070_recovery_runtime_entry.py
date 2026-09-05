from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt").read_text("utf-8")
SETTINGS = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/SettingsActivity.kt").read_text("utf-8")
WAKE = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt").read_text("utf-8")


def collect_missing():
    missing = []

    def require(source, marker, context):
        if marker not in source:
            missing.append(f"{context}: missing {marker}")

    def require_any(source, markers, context):
        if not any(marker in source for marker in markers):
            missing.append(f"{context}: missing one of {', '.join(markers)}")

    for label, source in (
        ("MainActivity.kt", MAIN),
        ("SettingsActivity.kt", SETTINGS),
        ("WakeService.kt", WAKE),
    ):
        require(source, "WakeServiceController", f"{label} shared wake controller")

    require(MAIN, "Manifest.permission.RECORD_AUDIO", "MainActivity microphone permission gate")
    require(MAIN, "PackageManager.PERMISSION_GRANTED", "MainActivity granted-permission branch")
    require_any(
        MAIN,
        (
            "WakeServiceController.start(",
            "WakeServiceController.ensureStarted(",
            "startService(Intent(this, WakeService::class.java))",
            "startForegroundService(Intent(this, WakeService::class.java))",
        ),
        "MainActivity reachable wake-service start path",
    )
    require_any(
        SETTINGS,
        (
            "WakeServiceController.shared(",
            "WakeServiceController.getInstance(",
            "WakeServiceController(",
        ),
        "SettingsActivity shared controller access",
    )
    require_any(
        MAIN,
        (
            "WakeServiceController.shared(",
            "WakeServiceController.getInstance(",
            "WakeServiceController(",
        ),
        "MainActivity shared controller access",
    )

    return missing


missing = collect_missing()
if missing:
    raise AssertionError("runtime entry recovery contract is still missing:\n- " + "\n- ".join(missing))

print("PASS: v0.7 recovery runtime entry contract")
