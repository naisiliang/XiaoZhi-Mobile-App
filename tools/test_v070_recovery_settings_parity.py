from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
SETTINGS = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/SettingsActivity.kt"
STORE = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/SettingsStore.kt"
LAYOUT = ROOT / "app/src/main/res/layout/activity_settings.xml"
SECTION = ROOT / "app/src/main/res/layout/view_settings_section.xml"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def method_body(source, name):
    match = re.search(rf"\bfun\s+{re.escape(name)}\s*\([^)]*\)\s*\{{", source, re.S)
    require(match is not None, f"SettingsActivity.{name} is missing")
    depth = 1
    index = match.end()
    while depth and index < len(source):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
        index += 1
    require(depth == 0, f"SettingsActivity.{name} is not balanced")
    return source[match.end() : index - 1]


def main():
    require(LAYOUT.exists(), "missing XML activity_settings layout")
    require(SECTION.exists(), "missing reusable settings section layout")

    settings = SETTINGS.read_text(encoding="utf-8")
    store = STORE.read_text(encoding="utf-8")
    layout = LAYOUT.read_text(encoding="utf-8")
    section = SECTION.read_text(encoding="utf-8")
    manifest = MANIFEST.read_text(encoding="utf-8")

    for title in ("语音", "声音", "手机控制", "智能 UI", "AI", "高级"):
        require(title in layout or title in section, f"missing Settings section title: {title}")
    require(layout.count("@layout/view_settings_section") >= 6, "Settings must render all six reusable sections")
    require("settings_section_title" in section, "section layout must expose a title view")

    required_ids = (
        "assistant_name", "wake_phrase", "wake_reply", "timeout_reply", "timeout_seconds",
        "prefer_offline_asr", "background_wake_enabled", "runtime_status",
        "start_wake_service", "stop_wake_service", "apply_wake_settings",
        "tts_voice", "tts_speech_rate", "tts_pitch", "scan_tts_voices", "preview_tts",
        "default_map_app", "location_status", "request_location_permission",
        "app_aliases", "app_test_name", "scan_apps", "test_app", "app_discovery_status",
        "api_base_url", "api_key", "model", "api_mode", "system_prompt", "test_ai_endpoint", "ai_test_status",
        "overlay_status", "request_overlay_permission", "diagnostics_output", "refresh_diagnostics",
    )
    for view_id in required_ids:
        require(f"@+id/{view_id}" in layout or f"@id/{view_id}" in layout, f"missing Settings control id: {view_id}")
    require("textPassword" in layout, "API key must use password input")
    require("fillViewport" in layout and "clipToPadding" in layout, "Settings scroll container must be inset-safe")

    on_create = method_body(settings, "onCreate")
    on_destroy = method_body(settings, "onDestroy")
    save = method_body(settings, "saveSettings")
    persist = method_body(settings, "persistSettings")
    apply = method_body(settings, "applyWakeSettings")
    start = method_body(settings, "startWakeService")
    stop = method_body(settings, "stopWakeService")

    for marker in (
        "setContentView(R.layout.activity_settings)",
        "WindowCompat.setDecorFitsSystemWindows(window, false)",
        "ViewCompat.setOnApplyWindowInsetsListener",
        "WakeRuntimeStatusStoreProvider.instance()",
        "runtimeStatusStore.addObserver(runtimeObserver)",
        "findViewById(R.id.assistant_name)",
        "findViewById(R.id.tts_voice)",
        "findViewById(R.id.api_key)",
    ):
        require(marker in settings or marker in on_create, f"Settings runtime wiring is missing: {marker}")
    require("runtimeStatusStore.removeObserver(runtimeObserver)" in on_destroy, "runtime observer must be removed")

    for marker in (
        "WakeServiceController.start(this)",
        "WakeServiceController.stop(this)",
        "WakeServiceController.applyWakeSettings(this)",
        "WakeServiceController.isRunning(this)",
        "WakeServiceController.setBackgroundWakeEnabled(this",
        "requestPermissions",
        "Manifest.permission.RECORD_AUDIO",
    ):
        require(marker in settings, f"Settings runtime control is missing: {marker}")
    require(persist.index("settings.wakePhrase") >= 0, "persistSettings must persist wake settings")
    require(save.index("persistSettings()") < save.find("WakeServiceController.applyWakeSettings", save.index("persistSettings()")), "save must persist before applying wake settings")
    require(save.index("persistSettings()") < save.find("WakeServiceController.start", save.index("persistSettings()")), "save must persist before starting wake service")
    require("WakeServiceController" not in apply.replace("WakeServiceController.applyWakeSettings(this)", ""), "apply helper must not create a second service entry point")
    require("startService(" not in settings and "startForegroundService(" not in settings, "SettingsActivity must dispatch service actions through the controller")

    for marker in (
        "TextToSpeech",
        "TtsVoiceManager",
        "availableVoices()",
        "applyVoice(",
        "preview(",
        "scanTtsVoices",
        "previewTts",
    ):
        require(marker in settings, f"TTS Settings parity is missing: {marker}")
    for marker in (
        "InstalledAppRegistry",
        "discover(force = true)",
        "resolveDetailed",
        "LocationProvider",
        "ACCESS_FINE_LOCATION",
        "Settings.canDrawOverlays",
        "ACTION_MANAGE_OVERLAY_PERMISSION",
        "AiClient",
        "testEndpoint",
        "diagnostics_output",
    ):
        require(marker in settings, f"Settings diagnostics/control parity is missing: {marker}")

    for field in (
        "assistantName", "wakePhrase", "wakeReply", "timeoutReply", "sessionTimeoutSeconds",
        "ttsVoiceName", "ttsSpeechRate", "ttsPitch", "defaultMapApp", "appAliases",
        "apiBaseUrl", "apiKey", "model", "apiMode", "systemPrompt", "preferOfflineAsr",
    ):
        require(re.search(rf"\bvar\s+{re.escape(field)}\b", store), f"SettingsStore field missing: {field}")
    require("apiKey" not in method_body(settings, "renderDiagnostics"), "diagnostics must not expose API key")
    require(re.search(r'<activity\b(?=[^>]*android:name="\.SettingsActivity")(?=[^>]*android:exported="false")[^>]*>', manifest), "SettingsActivity must remain non-exported")
    print("PASS: v0.7 Settings Golden parity and runtime controls")


if __name__ == "__main__":
    main()
