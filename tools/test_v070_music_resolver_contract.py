from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MUSIC = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/media/MusicApp.kt"
RESOLVER = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/media/MusicAppResolver.kt"
SETTINGS_STORE = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/SettingsStore.kt"
SETTINGS_ACTIVITY = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/SettingsActivity.kt"
SETTINGS_LAYOUT = ROOT / "app/src/main/res/layout/activity_settings.xml"


def require(condition, message):
    if not condition:
        raise SystemExit(message)


music = MUSIC.read_text(encoding="utf-8") if MUSIC.exists() else ""
resolver = RESOLVER.read_text(encoding="utf-8") if RESOLVER.exists() else ""
store = SETTINGS_STORE.read_text(encoding="utf-8")
activity = SETTINGS_ACTIVITY.read_text(encoding="utf-8")
layout = SETTINGS_LAYOUT.read_text(encoding="utf-8")

for marker in (
    "data class MusicApp",
    "enum class MusicAppKind",
    "com.netease.cloudmusic",
    "com.luna.music",
    "com.kugou.android",
    "com.tencent.qqmusic",
    "fromInstalled",
):
    require(marker in music, f"missing music app catalog contract: {marker}")

for marker in (
    "class MusicAppResolver",
    "savedDefaultPackage",
    "persistDefaultPackage",
    "activeMediaSession",
    "MusicResolutionSource.EXPLICIT",
    "MusicResolutionSource.SAVED_DEFAULT",
    "MusicResolutionSource.ACTIVE_MEDIA_SESSION",
    "MusicResolutionSource.USER_CHOICE_REQUIRED",
    "repairedMissingDefault",
    "fun saveDefault",
):
    require(marker in resolver, f"missing music resolver contract: {marker}")

require("var defaultMusicApp" in store, "SettingsStore must persist defaultMusicApp")
for marker in (
    "default_music_app",
    "default_music_app",
    "defaultMusicApp",
    "scanMusicApps",
):
    require(marker in activity or marker in store, f"missing Settings music wiring: {marker}")
for marker in ("@+id/default_music_app", "@+id/scan_music_apps", "@+id/music_app_status"):
    require(marker in layout, f"missing music settings view: {marker}")

print("PASS: v0.7 music app resolver and Settings contract")
