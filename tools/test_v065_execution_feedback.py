from pathlib import Path


root = Path(__file__).resolve().parents[1]
source_path = root / "app/src/main/java/com/lchuang/xiaozhimobile/CommandResultNotifier.kt"
source = source_path.read_text("utf-8")

assert "class CommandResultNotifier(" in source
assert "private val publish: (String) -> Unit" in source
assert "private val clockMs: () -> Long" in source
assert "private val holdMs: Long = 4000L" in source
for signature in [
    "fun running(text: String)",
    "fun success(text: String)",
    "fun failure(text: String)",
    "fun publishTransient(text: String)",
    "fun clearRetention()",
    "fun retainedText(nowMs: Long = clockMs()): String?",
]:
    assert signature in source, signature

# The state contract must use the supplied clock and expire at the exact boundary.
assert "nowMs >= until" in source
assert "retainedUntilMs = clockMs() + holdMs" in source
assert "publish(text)" in source
assert "retainedText() ?: text" in source
assert "retainedText = null" in source
assert "retainedUntilMs = null" in source
assert "import android." not in source
print("PASS: retained command-result notifier contract")
