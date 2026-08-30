from pathlib import Path
import os
import re
import subprocess
import sys
import tempfile
import textwrap


ROOT = Path(__file__).resolve().parents[1]
WAKE = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt"
wake = WAKE.read_text(encoding="utf-8")


def function_body(name: str) -> str:
    match = re.search(rf"private fun {re.escape(name)}\b", wake)
    assert match, f"WakeService.{name} missing"
    signature_open = wake.find("(", match.end())
    assert signature_open >= 0, name
    signature_depth = 0
    signature_close = -1
    for index in range(signature_open, len(wake)):
        if wake[index] == "(":
            signature_depth += 1
        elif wake[index] == ")":
            signature_depth -= 1
            if signature_depth == 0:
                signature_close = index
                break
    assert signature_close >= 0, f"WakeService.{name} signature is not balanced"
    opening = wake.find("{", signature_close)
    assert opening >= 0, f"WakeService.{name} body missing"
    depth = 0
    for index in range(opening, len(wake)):
        if wake[index] == "{":
            depth += 1
        elif wake[index] == "}":
            depth -= 1
            if depth == 0:
                return wake[opening + 1 : index]
    raise AssertionError(f"WakeService.{name} body is not balanced")


def braced_block(source: str, marker: str) -> str:
    marker_index = source.find(marker)
    assert marker_index >= 0, f"missing block marker: {marker}"
    opening = source.find("{", marker_index)
    assert opening >= 0, marker
    depth = 0
    for index in range(opening, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[opening + 1 : index]
    raise AssertionError(f"unbalanced block after: {marker}")


# Deterministic static integration checks: Android callbacks cannot run in this
# source test, so it proves branch ownership and relies on the compiler-backed
# formatter harness below for concrete failure copy.
process = function_body("processNonExitUtterance")
start_recognition = function_body("startLocalCommandRecognition")
empty_capture = braced_block(start_recognition, "if (samples.isEmpty())")
assert "recoverRecognitionFailure(CommandFailureKind.NO_SPEECH)" in empty_capture, (
    "a VAD capture with no speech must use the typed silent relisten path"
)

utterance = function_body("processUtterance")
assert "recoverRecognitionFailure(CommandFailureKind.ASR_EMPTY)" in utterance, (
    "blank ASR and low-quality one-character ASR must not share unsupported recovery"
)
assert "isLowQualityRecognition(normalized)" in utterance, (
    "one-character ASR must be classified before command routing"
)

recovery = function_body("recoverRecognitionFailure")
for kind in [
    "CommandFailureKind.NO_SPEECH",
    "CommandFailureKind.ASR_EMPTY",
    "CommandFailureKind.UNSUPPORTED_COMMAND",
]:
    assert kind in recovery, f"typed recognition recovery missing: {kind}"
assert "刚才没有听清，请再说一次。" in recovery
assert "这个指令我暂时还不会，你可以换一种说法。" in recovery
assert "UNKNOWN_COMMAND_REPLY" not in recovery
assert "UNKNOWN_COMMAND_REPLY" not in process, (
    "unsupported text must use the category-specific recovery reply"
)

plan_index = process.find("router.plan(normalized)")
ai_index = process.find("aiOrchestrator.respond")
assert 0 <= plan_index < ai_index, "local plans must be resolved before AI"
assert "router.handle(" not in process
planned = braced_block(process, "is DeviceCommandPlan.Planned ->")
assert "executeDeviceAction(rawText, normalized" in planned
assert "return" in planned, "local Android failure must not fall through to AI"

tool_branch = process[process.index("is AiOutcome.Tool ->") :]
allowed = braced_block(tool_branch, "is SafeToolPlan.Allowed ->")
rejected = braced_block(tool_branch, "is SafeToolPlan.Rejected ->")
assert "executeDeviceAction(rawText, normalized" in allowed
assert "executeDeviceAction(" not in rejected, "safety rejection must perform no device operation"
assert "CommandFailureKind.SAFETY_REJECTED" in rejected
assert "这个操作不能执行" in rejected
assert "UNKNOWN_COMMAND_REPLY" not in rejected
assert "AI 服务暂时不可用，请稍后再试。" in process

formatter = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/ExecutionIntentFormatter.kt").read_text(
    encoding="utf-8"
)
assert "CommandFailureKind.APP_NOT_FOUND" in formatter
assert '"请继续说。"' in formatter
assert '"请再试一次。"' in formatter

execute_action = function_body("executeDeviceAction")
result_index = execute_action.find("completed.result")
memory_index = execute_action.find("memory.addTurn(")
assert 0 <= result_index < memory_index, "memory must use the real asynchronous execution result"
assert "if (result.success)" in execute_action
assert execute_action.count("successfulDeviceActions += 1") == 1

compiler = os.environ.get("KOTLINC") or "kotlinc"
compiler_command = ["cmd", "/c", compiler] if compiler.lower().endswith((".bat", ".cmd")) else [compiler]
try:
    subprocess.run(
        [*compiler_command, "-version"],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
except (FileNotFoundError, subprocess.CalledProcessError):
    print(f"FAIL: error-recovery Kotlin harness unavailable or unusable: {compiler}", file=sys.stderr)
    raise SystemExit(1)

sources = [
    ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/DeviceAction.kt",
    ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/ExecutionIntentFormatter.kt",
]
with tempfile.TemporaryDirectory() as temp_dir:
    temp = Path(temp_dir)
    stub = temp / "MapPreferenceStub.kt"
    stub.write_text(
        "package com.lchuang.xiaozhimobile\nenum class MapAppPreference { AUTO, AMAP, BAIDU, SYSTEM }\n",
        encoding="utf-8",
    )
    harness = temp / "ErrorRecoveryHarness.kt"
    harness.write_text(
        textwrap.dedent(
            """
            import com.lchuang.xiaozhimobile.*

            fun main() {
                val formatter = ExecutionIntentFormatter()
                val action = DeviceAction.OpenApp("不存在应用")
                val result = DeviceExecutionResult(
                    success = false,
                    code = "OPEN_APP_NOT_FOUND",
                    spokenResult = "没有找到可启动的“不存在应用”",
                    notificationSummary = "未找到不存在应用",
                    failureKind = CommandFailureKind.APP_NOT_FOUND
                )
                val copy = formatter.finalCopy(action, result, "请继续说。")
                check(copy.successNotification == null)
                check(copy.failureNotification == "❌ 执行失败：打开不存在应用")
                check(copy.finalSpoken == "没有找到可启动的“不存在应用”。请继续说。")
                check(result.failureKind == CommandFailureKind.APP_NOT_FOUND)
                check(CommandFailureKind.SAFETY_REJECTED.name == "SAFETY_REJECTED")
                println("PASS: specific execution failure and safety categories")
            }
            """
        ),
        encoding="utf-8",
    )
    jar = temp / "error-recovery.jar"
    subprocess.run(
        [
            *compiler_command,
            *(str(source) for source in sources),
            str(stub),
            str(harness),
            "-include-runtime",
            "-d",
            str(jar),
        ],
        cwd=ROOT,
        check=True,
    )
    subprocess.run(["java", "-jar", str(jar)], cwd=ROOT, check=True)

print("PASS: static WakeService failure routing uses unified real results")
