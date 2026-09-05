from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
wake = (root / "app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt").read_text(
    encoding="utf-8"
)

match = re.search(
    r"override fun onDestroy\(\) \{(.*?)\n    \}\n\n    override fun onBind",
    wake,
    flags=re.S,
)
assert match, "onDestroy block not found"

block = match.group(1)
required = [
    'conversationSessionManager.endSession("service_destroyed")',
    "assistantStateStore.onConversationEnded()",
    "overlay.release()",
]
missing = [token for token in required if token not in block]
assert not missing, "onDestroy missing required cleanup: " + ", ".join(missing)

assert block.index('conversationSessionManager.endSession("service_destroyed")') < block.index(
    "assistantStateStore.onConversationEnded()"
), "assistant state reset must happen after session end"
assert block.index("assistantStateStore.onConversationEnded()") < block.index(
    "overlay.release()"
), "assistant state reset must happen before overlay release"

print("PASS: WakeService onDestroy resets the shared assistant state")
