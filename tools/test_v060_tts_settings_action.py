from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
MAIN_SOURCE = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt").read_text("utf-8")
SETTINGS_SOURCE = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/SettingsActivity.kt").read_text("utf-8")


def extract_block(source, opening_brace, description):
    if opening_brace < 0 or source[opening_brace] != "{":
        raise AssertionError(f"could not locate opening brace for {description}")

    depth = 0
    quote = None
    escaped = False
    line_comment = False
    block_comment = False
    index = opening_brace
    while index < len(source):
        character = source[index]
        next_character = source[index + 1] if index + 1 < len(source) else ""
        if line_comment:
            if character == "\n":
                line_comment = False
        elif block_comment:
            if character == "*" and next_character == "/":
                block_comment = False
                index += 1
        elif quote:
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == quote:
                quote = None
        elif character == "/" and next_character == "/":
            line_comment = True
            index += 1
        elif character == "/" and next_character == "*":
            block_comment = True
            index += 1
        elif character in ('"', "'"):
            quote = character
        elif character == "{":
            depth += 1
        elif character == "}":
            depth -= 1
            if depth == 0:
                return source[opening_brace + 1:index]
        index += 1
    raise AssertionError(f"unclosed block for {description}")


def class_body(source, class_name):
    match = re.search(rf"\bclass\s+{re.escape(class_name)}\b", source)
    if not match:
        raise AssertionError(f"class {class_name} is missing")
    return extract_block(source, source.find("{", match.end()), f"class {class_name}")


def method_body(source, method_name):
    match = re.search(rf"\bfun\s+{re.escape(method_name)}\s*\(", source)
    if not match:
        raise AssertionError(f"method {method_name} is missing")
    return extract_block(source, source.find("{", match.end()), f"method {method_name}")


settings_class = class_body(SETTINGS_SOURCE, "SettingsActivity")
settings_build_ui = method_body(settings_class, "buildUi")
settings_load = method_body(settings_class, "loadSettings")
settings_save = method_body(settings_class, "saveSettings")
main_menu = method_body(MAIN_SOURCE, "showHomeMenu")

for control in ("ttsVoiceName", "ttsSpeechRate", "ttsPitch"):
    if not re.search(rf"\bprivate\s+lateinit\s+var\s+{control}:\s+EditText\b", settings_class):
        raise AssertionError(f"SettingsActivity TTS control declaration is missing: {control}")

for marker in (
    'text = "声音"',
    'ttsVoiceName = addEdit(root, "TTS 声音名称（留空使用默认）")',
    'ttsSpeechRate = addEdit(root, "语速 0.6 - 1.6")',
    'ttsPitch = addEdit(root, "音调 0.6 - 1.4")',
):
    if marker not in settings_build_ui:
        raise AssertionError(f"SettingsActivity TTS UI wiring is missing: {marker}")

load_markers = (
    "ttsVoiceName.setText(settings.ttsVoiceName)",
    "ttsSpeechRate.setText(settings.ttsSpeechRate.toString())",
    "ttsPitch.setText(settings.ttsPitch.toString())",
)
save_markers = (
    "settings.ttsVoiceName = ttsVoiceName.text.toString()",
    "settings.ttsSpeechRate = ttsSpeechRate.text.toString().toFloatOrNull() ?: 1.0f",
    "settings.ttsPitch = ttsPitch.text.toString().toFloatOrNull() ?: 1.0f",
)
for marker in load_markers:
    if marker not in settings_load:
        raise AssertionError(f"SettingsActivity TTS load binding is missing: {marker}")
for marker in save_markers:
    if marker not in settings_save:
        raise AssertionError(f"SettingsActivity TTS save binding is missing: {marker}")

if not re.search(r"setOnClickListener\s*\{\s*saveSettings\(\)\s*\}", settings_build_ui, re.S):
    raise AssertionError("SettingsActivity save action is not wired to persisted TTS settings")

if 'menu.add("设置").setOnMenuItemClickListener' not in main_menu:
    raise AssertionError("MainActivity home menu does not expose the SettingsActivity entry")
settings_entry = main_menu[main_menu.index('menu.add("设置")') :]
if "startActivity(Intent(this@MainActivity, SettingsActivity::class.java))" not in settings_entry:
    raise AssertionError("MainActivity SettingsActivity entry is not triggerable")

# The migrated Activity does not currently expose a system TTS-settings button.
# Do not claim the removed v0.6 MainActivity action still exists or is reachable.
for stale_action in (
    "com.android.settings.TTS_SETTINGS",
    "Settings.ACTION_TTS_SETTINGS",
    "Settings.ACTION_SETTINGS",
):
    if stale_action in MAIN_SOURCE:
        raise AssertionError(f"obsolete MainActivity TTS settings action remains: {stale_action}")

print("PASS: v0.6 TTS settings persist in reachable SettingsActivity")
