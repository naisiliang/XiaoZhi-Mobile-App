from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MAIN_ACTIVITY = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt"


source = MAIN_ACTIVITY.read_text(encoding="utf-8")
assert 'text = "v0.6.5：会话状态机 + 悬浮层手动退出 + 智能退出 + 自然语言媒体音量"' in source
assert "v0.6.4" not in source

print("PASS: v0.6.5 visible release metadata")
