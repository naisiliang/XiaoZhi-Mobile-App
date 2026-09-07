from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VISION_DIR = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/vision"
COORDINATOR = VISION_DIR / "ScreenVisionCaptureCoordinator.kt"
ANALYZER = VISION_DIR / "ScreenVisionAnalyzer.kt"
AUTH = VISION_DIR / "VisionSessionAuthorization.kt"


def require(condition, message, failures):
    if not condition:
        failures.append(message)


def main():
    failures = []
    sources = {}
    for path in (AUTH, COORDINATOR, ANALYZER):
        require(path.is_file(), f"missing Vision source: {path.name}", failures)
        if path.is_file():
            sources[path.name] = path.read_text("utf-8")

    coordinator = sources.get(COORDINATOR.name, "")
    analyzer = sources.get(ANALYZER.name, "")
    auth = sources.get(AUTH.name, "")

    require("isAuthorized" in coordinator, "capture must check session authorization", failures)
    require("SensitiveScreenDetector" in coordinator, "capture must depend on SensitiveScreenDetector", failures)
    require("currentIfMatches" in coordinator, "capture must revalidate current ScreenContext", failures)
    require(
        coordinator.find("sensitiveScreenDetector.detect") < coordinator.find("captureCurrentFrame"),
        "sensitive-screen gate must precede captureCurrentFrame",
        failures,
    )
    require("SensitiveScreenDetector" in analyzer, "analyzer must depend on SensitiveScreenDetector", failures)
    require("currentIfMatches" in analyzer, "analyzer must revalidate captured generation", failures)
    require(
        analyzer.find("sensitiveScreenDetector.detect") < analyzer.find("modelClient.analyze"),
        "sensitive-screen gate must precede modelClient.analyze",
        failures,
    )
    require("beginSession" in auth and "endSession" in auth, "authorization must expose session lifecycle", failures)
    require("SharedPreferences" not in "\n".join(sources.values()), "Vision grant must not persist", failures)
    require("SQLite" not in "\n".join(sources.values()), "Vision grant/frame must not use SQLite", failures)
    require("FileOutputStream" not in "\n".join(sources.values()), "raw frame must not be written to files", failures)
    require("openFileOutput" not in "\n".join(sources.values()), "raw frame must not be persisted", failures)
    require("getBoundsInScreen" not in "\n".join(sources.values()), "Vision must not use saved coordinates", failures)
    require("x: Int" not in "\n".join(sources.values()), "Vision candidates must not carry x coordinates", failures)
    require("y: Int" not in "\n".join(sources.values()), "Vision candidates must not carry y coordinates", failures)
    require("Log." not in "\n".join(sources.values()), "raw Vision data must not be logged", failures)

    if failures:
        raise SystemExit("FAIL: v0.7 sensitive Vision zero-call contract\n- " + "\n- ".join(failures))
    print("PASS: v0.7 sensitive Vision zero-call and transient-frame contract")


if __name__ == "__main__":
    main()
