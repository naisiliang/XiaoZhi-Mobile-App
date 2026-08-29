from pathlib import Path
import hashlib
import re
import subprocess

root = Path(__file__).resolve().parents[1]

expected_hashes = {
    "app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseCompiler.kt": "6376a9ade23c87856aad3bdfc869f05936faa4ddd3aaae4612101cccebe895cc",
    "app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseManager.kt": "ced9c7276cd98e72d488b4f228d8bf4cfe77a08c184f06ef112425f701a5a608",
    "app/src/main/java/com/lchuang/xiaozhimobile/Pinyin4jProvider.kt": "1fead428ba6b77be1ccbbd0882e9694fb9fe1aee8ac53e2707cb3872edb57f6f",
}

for rel, expected in expected_hashes.items():
    actual = hashlib.sha256((root / rel).read_bytes()).hexdigest()
    assert actual == expected, f"frozen wake file changed: {rel}"

wake_path = root / "app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt"
wake = wake_path.read_text(encoding="utf-8")
match = re.search(
    r"private fun initKeywordSpotter\(\)\s*\{.*?\n    \}\n\n    private fun initOfflineAsr",
    wake,
    flags=re.S,
)
assert match, "initKeywordSpotter block not found"
actual_kws_hash = hashlib.sha256(match.group(0).encode("utf-8")).hexdigest()
assert actual_kws_hash == "77071fcc4a9d9c9627e8a30ddb45d0ad831ece80483152d8b71ce8b4c128abcd", "WakeService KWS initialization changed"

assert 'private const val KWS_MODEL_DIR = "sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20"' in wake
assert "keywordsScore = 1.5f" in wake
assert "keywordsThreshold = 0.20f" in wake
assert "numTrailingBlanks = 1" in wake

subprocess.run(["python", "tools/test_v063_custom_wake_ppinyin.py"], cwd=root, check=True)
subprocess.run(["python", "tools/test_v063_wake_error_diagnostics.py"], cwd=root, check=True)
print("PASS: v0.6.4 preserves v0.6.3 custom wake implementation")
