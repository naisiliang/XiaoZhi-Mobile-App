from __future__ import annotations

import hashlib
import importlib.util
import tempfile
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile


ROOT = Path(__file__).resolve().parents[1]
VALIDATOR_PATH = ROOT / "tools/validate_v070_apk.py"


def load_validator_module():
    spec = importlib.util.spec_from_file_location("validate_v070_apk", VALIDATOR_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


validator = load_validator_module()


def write_fixture(path: Path, *, omit: str | None = None, manifest: bytes | None = None) -> None:
    entries = {
        "AndroidManifest.xml": manifest
        or b'<manifest package="com.lchuang.xiaozhimobile" versionName="0.6.5" '
        b'android:name="com.lchuang.xiaozhimobile.accessibility.XiaoZhiAccessibilityService" '
        b'android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE" '
        b'action="android.accessibilityservice.AccessibilityService">',
        "classes.dex": b"dex",
        "lib/arm64-v8a/libsherpa-onnx-jni.so": b"jni",
        "assets/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20/encoder-epoch-13-avg-2-chunk-16-left-64.onnx": b"encoder",
        "assets/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20/decoder-epoch-13-avg-2-chunk-16-left-64.onnx": b"decoder",
        "assets/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20/joiner-epoch-13-avg-2-chunk-16-left-64.onnx": b"joiner",
        "assets/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20/tokens.txt": b"tokens",
        "assets/sherpa-onnx-paraformer-zh-small-2024-03-09/model.int8.onnx": b"asr",
        "assets/sherpa-onnx-paraformer-zh-small-2024-03-09/tokens.txt": b"tokens",
    }
    with ZipFile(path, "w", compression=ZIP_DEFLATED) as archive:
        for name, content in entries.items():
            if name != omit:
                archive.writestr(name, content)


def expect_failure(callable_, expected_fragment: str) -> None:
    try:
        callable_()
    except Exception as error:
        assert expected_fragment.lower() in str(error).lower(), str(error)
        return
    raise AssertionError(f"expected failure containing {expected_fragment!r}")


with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    artifact_dir = root / "artifact"
    artifact_dir.mkdir()
    apk = artifact_dir / validator.EXPECTED_RC_APK_NAME
    write_fixture(apk)
    report = validator.validate_artifact_directory(
        artifact_dir,
        expected_size=apk.stat().st_size,
        expected_sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),
    )
    assert report["filename"] == validator.EXPECTED_RC_APK_NAME
    assert report["manifest"]["package"] == validator.EXPECTED_PACKAGE_NAME
print("PASS: v0.7 APK validator reports exact artifact metadata")

with tempfile.TemporaryDirectory() as tmp:
    path = Path(tmp) / validator.EXPECTED_RC_APK_NAME
    write_fixture(path, omit="classes.dex")
    expect_failure(lambda: validator.validate_apk(path), "classes.dex")
print("PASS: v0.7 APK validator rejects missing required entries")

with tempfile.TemporaryDirectory() as tmp:
    path = Path(tmp) / validator.EXPECTED_RC_APK_NAME
    write_fixture(path, manifest=b'<manifest package="com.lchuang.xiaozhimobile" versionName="0.6.5" />')
    expect_failure(lambda: validator.validate_apk(path), "AccessibilityService")
print("PASS: v0.7 APK validator rejects missing AccessibilityService declaration")

with tempfile.TemporaryDirectory() as tmp:
    path = Path(tmp) / validator.EXPECTED_RC_APK_NAME
    write_fixture(path)
    report = validator.validate_apk(path)
    expect_failure(
        lambda: validator._check_expected(report, report["size_bytes"] + 1, None),
        "size mismatch",
    )
    expect_failure(
        lambda: validator._check_expected(report, None, "0" * 64),
        "sha-256 mismatch",
    )
print("PASS: v0.7 APK validator rejects size and SHA-256 mismatches")

with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    apk = root / validator.EXPECTED_RC_APK_NAME
    write_fixture(apk)
    wrapper = root / "artifact.zip"
    with ZipFile(wrapper, "w", compression=ZIP_DEFLATED) as archive:
        archive.write(apk, validator.EXPECTED_RC_APK_NAME)
    report = validator.validate_artifact_zip(wrapper)
    assert report["apk"]["sha256"] == hashlib.sha256(apk.read_bytes()).hexdigest()
print("PASS: v0.7 APK validator revalidates the contained artifact ZIP APK")

print("PASS: v0.7 APK validator contract")
