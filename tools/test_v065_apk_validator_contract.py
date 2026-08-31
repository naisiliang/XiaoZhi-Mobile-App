from __future__ import annotations

import hashlib
import importlib.util
import tempfile
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile


ROOT = Path(__file__).resolve().parents[1]
VALIDATOR_PATH = ROOT / "tools/validate_v065_apk.py"
EXPECTED_APK_NAME = "XiaoZhi-Mobile-v0.6.5-debug.apk"
KWS_DIR = "assets/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20/"
ASR_DIR = "assets/sherpa-onnx-paraformer-zh-small-2024-03-09/"


def load_validator_module():
    spec = importlib.util.spec_from_file_location("validate_v065_apk", VALIDATOR_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def write_fixture(path: Path, *, omit: str | None = None) -> None:
    entries = {
        "AndroidManifest.xml": b"manifest",
        "classes.dex": b"dex",
        "lib/arm64-v8a/libsherpa-onnx-jni.so": b"jni",
        KWS_DIR + "encoder-epoch-13-avg-2-chunk-16-left-64.onnx": b"encoder",
        KWS_DIR + "decoder-epoch-13-avg-2-chunk-16-left-64.onnx": b"decoder",
        KWS_DIR + "joiner-epoch-13-avg-2-chunk-16-left-64.onnx": b"joiner",
        KWS_DIR + "tokens.txt": b"tokens",
        ASR_DIR + "model.int8.onnx": b"asr",
        ASR_DIR + "tokens.txt": b"tokens",
    }
    with ZipFile(path, "w", compression=ZIP_DEFLATED) as archive:
        for name, content in entries.items():
            if name != omit:
                archive.writestr(name, content)


def expect_failure(callable_, expected_fragment: str) -> None:
    try:
        callable_()
    except Exception as error:
        assert expected_fragment in str(error), str(error)
        return
    raise AssertionError(f"expected failure containing {expected_fragment!r}")


validator = load_validator_module()

with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    artifact_dir = root / "downloaded"
    artifact_dir.mkdir()
    apk = artifact_dir / EXPECTED_APK_NAME
    write_fixture(apk)

    report = validator.validate_artifact_directory(artifact_dir)
    assert report["filename"] == EXPECTED_APK_NAME
    assert report["size_bytes"] == apk.stat().st_size
    assert report["size_bytes"] > 0
    assert report["sha256"] == hashlib.sha256(apk.read_bytes()).hexdigest()
print("PASS: valid downloaded v0.6.5 APK reports size and SHA-256")

with tempfile.TemporaryDirectory() as tmp:
    artifact_dir = Path(tmp)
    wrong_name = artifact_dir / "different.apk"
    write_fixture(wrong_name)
    expect_failure(
        lambda: validator.validate_artifact_directory(artifact_dir),
        EXPECTED_APK_NAME,
    )
print("PASS: APK validator rejects a different artifact filename")

with tempfile.TemporaryDirectory() as tmp:
    artifact_dir = Path(tmp)
    apk = artifact_dir / EXPECTED_APK_NAME
    write_fixture(apk, omit="classes.dex")
    expect_failure(
        lambda: validator.validate_artifact_directory(artifact_dir),
        "classes.dex",
    )
print("PASS: APK validator rejects missing required entries")

with tempfile.TemporaryDirectory() as tmp:
    artifact_dir = Path(tmp)
    apk = artifact_dir / EXPECTED_APK_NAME
    apk.write_bytes(b"not an APK zip")
    expect_failure(
        lambda: validator.validate_artifact_directory(artifact_dir),
        "ZIP",
    )
print("PASS: APK validator rejects invalid ZIP data")

print("PASS: v0.6.5 APK validator contract")
