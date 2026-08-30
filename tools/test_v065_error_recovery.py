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
device_action = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/DeviceAction.kt").read_text(
    encoding="utf-8"
)


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


# Android callbacks cannot run in this harness.  The compiler-backed policy
# checks below exercise the category and retry semantics; these narrow source
# checks verify that WakeService actually delegates worker callbacks to them.
process = function_body("processNonExitUtterance")
start_recognition = function_body("startLocalCommandRecognition")
empty_capture = braced_block(start_recognition, "if (samples.isEmpty())")
assert "recoverRecognitionFailure(CommandFailureKind.NO_SPEECH)" in empty_capture, (
    "a VAD capture with no speech must use the typed silent relisten path"
)
assert "isCurrentCommandSession(generation)" in empty_capture, (
    "a stale no-speech callback must not mutate a newer or closed session"
)

utterance = function_body("processUtterance")
assert "recoverRecognitionFailure(CommandFailureKind.ASR_EMPTY)" in utterance, (
    "blank ASR and low-quality one-character ASR must not share unsupported recovery"
)
assert "isLowQualityRecognition(normalized)" in utterance, (
    "one-character ASR must be classified before command routing"
)

recovery = function_body("recoverRecognitionFailure")
assert "CommandRecoveryPolicy.forFailure(kind, commandRecognitionAttempts)" in recovery
assert "decision.terminal" in recovery
assert "requestConversationExit" in recovery
assert "const val MAX_COMMAND_RECOGNITION_ATTEMPTS = 2" in device_action
assert "UNKNOWN_COMMAND_REPLY" not in recovery
assert "UNKNOWN_COMMAND_REPLY" not in process, "unsupported text must use category recovery"
assert "UNKNOWN_COMMAND_REPLY" not in wake, (
    "the obsolete generic reply must not remain available to typed failure paths"
)

for kind in [
    "NO_SPEECH",
    "ASR_EMPTY",
    "UNSUPPORTED_COMMAND",
    "APP_NOT_FOUND",
    "EXECUTION_FAILED",
    "AI_UNAVAILABLE",
    "SAFETY_REJECTED",
]:
    assert re.search(rf"CommandFailureKind\.{kind}\b", device_action), (
        f"typed recovery policy missing {kind}"
    )

for exact_copy in [
    "刚才没有听清，请再说一次。",
    "这个指令我暂时还不会，你可以换一种说法。",
    "请继续说。",
    "请再试一次。",
    "AI 服务暂时不可用，请稍后再试。",
    "这个操作不能执行。",
]:
    assert exact_copy in device_action, f"typed recovery copy missing: {exact_copy}"

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
assert "recoverRecognitionFailure(failureKind)" in rejected
assert "UNKNOWN_COMMAND_REPLY" not in rejected
assert "recoverRecognitionFailure(CommandFailureKind.AI_UNAVAILABLE)" in process

# Every worker-thread terminal callback must reject stale sessions before it
# changes conversation state or invokes recovery/utterance processing.
no_speech_post = braced_block(start_recognition, "if (samples.isEmpty())")
session_guard = function_body("isCurrentCommandSession")
for token in [
    "running.get()",
    "conversationActive",
    "!exitInProgress",
    "generation == sessionGeneration",
    "conversationState != ConversationState.EXITING",
]:
    assert token in session_guard, f"captured-session guard missing: {token}"
decoded_index = start_recognition.index("processUtterance(text)")
assert "isCurrentCommandSession(generation)" in start_recognition[decoded_index - 240 : decoded_index]
exception_index = start_recognition.index("retryLocalCommandRecognition(reason)")
assert "isCurrentCommandSession(generation)" in start_recognition[exception_index - 240 : exception_index]

assert 'private val knownOneCharacterCommands = setOf("停")' in wake, (
    "the production quality gate must pass through the known one-character command"
)

formatter = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/ExecutionIntentFormatter.kt").read_text(
    encoding="utf-8"
)
assert "CommandRecoveryPolicy.forFailure(" in formatter
assert 'return "$prefix。$next"' in formatter, (
    "executor failures must use a sentence boundary"
)
assert 'return "$prefix，$next"' in formatter, (
    "successful continuations must retain their existing Chinese comma behavior"
)

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
                fun checkRecovery(
                    kind: CommandFailureKind,
                    attempts: Int,
                    reply: String?,
                    continuation: String?,
                    resetAttempts: Boolean,
                    terminal: Boolean
                ) {
                    val decision = CommandRecoveryPolicy.forFailure(kind, attempts)
                    check(decision.spokenReply == reply) { "$kind reply=${decision.spokenReply}" }
                    check(decision.continuation == continuation) { "$kind continuation=${decision.continuation}" }
                    check(decision.resetAttempts == resetAttempts) { "$kind reset=${decision.resetAttempts}" }
                    check(decision.terminal == terminal) { "$kind terminal=${decision.terminal}" }
                }

                check(MAX_COMMAND_RECOGNITION_ATTEMPTS == 2)
                checkRecovery(CommandFailureKind.NO_SPEECH, 1, null, null, true, false)
                checkRecovery(CommandFailureKind.ASR_EMPTY, 1, "刚才没有听清，请再说一次。", null, false, false)
                checkRecovery(CommandFailureKind.ASR_EMPTY, 2, "刚才没有听清，我先退下了，有需要再叫我。", null, true, true)
                checkRecovery(CommandFailureKind.UNSUPPORTED_COMMAND, 1, "这个指令我暂时还不会，你可以换一种说法。", null, true, false)
                checkRecovery(CommandFailureKind.APP_NOT_FOUND, 1, null, "请继续说。", true, false)
                checkRecovery(CommandFailureKind.EXECUTION_FAILED, 1, null, "请再试一次。", true, false)
                checkRecovery(CommandFailureKind.AI_UNAVAILABLE, 1, "AI 服务暂时不可用，请稍后再试。", null, true, false)
                checkRecovery(CommandFailureKind.SAFETY_REJECTED, 1, "这个操作不能执行。", null, true, false)

                check(CommandRecognitionQuality.failureKind("", emptySet()) == CommandFailureKind.ASR_EMPTY)
                check(CommandRecognitionQuality.failureKind("啊", emptySet()) == CommandFailureKind.ASR_EMPTY)
                check(CommandRecognitionQuality.failureKind("停", setOf("停")) == null)
                check(CommandRecognitionQuality.failureKind("打开微信", emptySet()) == null)

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
                val executionFailure = formatter.finalCopy(
                    action,
                    result.copy(
                        code = "OPEN_APP_FAILED",
                        spokenResult = "没有成功打开不存在应用",
                        failureKind = CommandFailureKind.EXECUTION_FAILED
                    ),
                    "这段兼容文案不得覆盖恢复策略"
                )
                check(executionFailure.finalSpoken == "没有成功打开不存在应用。请再试一次。")
                check(result.failureKind == CommandFailureKind.APP_NOT_FOUND)
                check(CommandFailureKind.SAFETY_REJECTED.name == "SAFETY_REJECTED")
                println("PASS: seven recovery categories, retry terminal, and recognition quality")
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

print("PASS: WakeService routes callbacks through executable recovery policy")
