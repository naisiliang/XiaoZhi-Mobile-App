from pathlib import Path
import hashlib, re, subprocess

root = Path(__file__).resolve().parents[1]
expected = {
    "app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseCompiler.kt": "6376a9ade23c87856aad3bdfc869f05936faa4ddd3aaae4612101cccebe895cc",
    "app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseManager.kt": "ced9c7276cd98e72d488b4f228d8bf4cfe77a08c184f06ef112425f701a5a608",
    "app/src/main/java/com/lchuang/xiaozhimobile/Pinyin4jProvider.kt": "1fead428ba6b77be1ccbbd0882e9694fb9fe1aee8ac53e2707cb3872edb57f6f",
}
for rel, sha in expected.items():
    assert hashlib.sha256((root / rel).read_bytes()).hexdigest() == sha, rel

wake = (root / "app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt").read_text("utf-8")
block = re.search(r"private fun initKeywordSpotter\(\)\s*\{.*?\n    \}\n\n    private fun initOfflineAsr", wake, re.S)
assert block
assert hashlib.sha256(block.group(0).encode()).hexdigest() == "77071fcc4a9d9c9627e8a30ddb45d0ad831ece80483152d8b71ce8b4c128abcd"
for token in [
    'KWS_MODEL_DIR = "sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20"',
    'keywordsScore = 1.5f', 'keywordsThreshold = 0.20f', 'numTrailingBlanks = 1'
]:
    assert token in wake, token

build = (root / "app/build.gradle.kts").read_text("utf-8")
assert 'versionCode = 12' in build
assert 'versionName = "0.6.5"' in build
subprocess.run(["python", "tools/test_v064_wake_regression.py"], cwd=root, check=True)
print("PASS: v0.6.5 frozen baseline and version")
