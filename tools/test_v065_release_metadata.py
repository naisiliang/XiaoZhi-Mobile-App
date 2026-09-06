from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MAIN_ACTIVITY = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt"
BUILD_GRADLE = ROOT / "app/build.gradle.kts"


source = MAIN_ACTIVITY.read_text(encoding="utf-8")
build = BUILD_GRADLE.read_text(encoding="utf-8")
assert 'versionName = "0.6.5"' in build
assert 'versionCode = 12' in build
assert 'assistantTitle.text = "${assistantName}智能体"' in source
assert 'v0.6.5：会话状态机 + 悬浮层手动退出 + 智能退出 + 自然语言媒体音量' not in source
assert "v0.6.4" not in source

print("PASS: v0.6.5 visible release metadata")
