from pathlib import Path


def assert_file_contains(filename: str, expected_tokens: list[str]) -> None:
    root = Path(__file__).resolve().parents[1]
    content = (root / "app/src/main/java/com/lchuang/xiaozhimobile" / filename).read_text(encoding="utf-8")
    for token in expected_tokens:
        assert token in content, f"{filename} missing {token}"


assert_file_contains(
    "MediaVolumeController.kt",
    [
        "MediaVolumeSnapshot",
        "beforeStep",
        "targetStep",
        "afterStep",
        "actualPercent",
        "isVolumeFixed",
        "STREAM_MUSIC",
    ],
)

phone = (
    Path(__file__).resolve().parents[1]
    / "app/src/main/java/com/lchuang/xiaozhimobile/PhoneController.kt"
).read_text(encoding="utf-8")
assert "MediaVolumeController(" in phone
assert "setPercent(percent)" in phone
assert "adjust(AudioManager.ADJUST_RAISE)" in phone
assert "adjust(AudioManager.ADJUST_LOWER)" in phone
print("PASS: FIX04 Task 1 media volume controller contract")
