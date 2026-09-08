from pathlib import Path
import re

from v070_source_contract_utils import strip_kotlin_literals


ROOT = Path(__file__).resolve().parents[1]
WAKE = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt").read_text("utf-8")
MAIN = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt").read_text("utf-8")
SOURCE = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/runtime/AssistantRequestSource.kt").read_text("utf-8")
QUEUE = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/runtime/PendingTextRequestQueue.kt").read_text("utf-8")


def function_body(source, name):
    clean = strip_kotlin_literals(source)
    match = re.search(
        rf"(?:private |public |internal |protected )?(?:override )?fun {re.escape(name)}\b[^{{]*\{{",
        clean,
    )
    if not match:
        return None
    depth = 1
    index = match.end()
    while depth and index < len(clean):
        if clean[index] == "{":
            depth += 1
        elif clean[index] == "}":
            depth -= 1
        index += 1
    return clean[match.end():index - 1] if depth == 0 else None


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def main():
    wake = strip_kotlin_literals(WAKE)
    main_source = strip_kotlin_literals(MAIN)
    process = function_body(WAKE, "processAssistantInput")
    asr = function_body(WAKE, "startLocalCommandRecognition")
    text_handler = function_body(WAKE, "handleTextIntent")
    start_command = function_body(WAKE, "onStartCommand")

    require("enum class AssistantRequestSource" in SOURCE, "missing request source enum")
    require("VOICE" in SOURCE and "TEXT" in SOURCE, "request source enum must include voice and text")
    require("class PendingTextRequestQueue" in QUEUE, "missing bounded text queue")
    require("DEFAULT_CAPACITY = 8" in QUEUE, "text queue capacity must default to 8")

    require(process is not None, "missing shared processAssistantInput function")
    require(
        re.search(r"fun\s+processAssistantInput\s*\([^)]*\bsource\s*:\s*AssistantRequestSource", wake),
        "shared processor must accept AssistantRequestSource",
    )
    require("val localPlan = router.plan(normalized)" in process, "shared processor must preserve local command planning")
    require("executeDeviceAction" in process, "local device commands must stay on the safe execution path")
    require("processNonExitUtterance" in process, "shared processor must continue to the AI/safe-tool path")
    text_session = function_body(WAKE, "beginTextConversationIfNeeded")
    require(
        text_session is not None
        and "stopKwsCapture()" in text_session
        and (
            "kwsThread?.join(500)" in text_session
            or "awaitThreadExit(kwsThread" in text_session
        ),
        "text input must stop the wake capture before entering the conversation pipeline",
    )
    require(asr is not None, "missing local ASR function")
    require(
        re.search(r"decodeLocalCommand\s*\([^)]*\)[\s\S]*processAssistantInput\s*\([^)]*AssistantRequestSource\.VOICE", asr),
        "voice ASR completion must call the shared processor with VOICE",
    )
    require("fun processUtterance" not in wake, "old voice-only processor must be extracted")

    require(start_command is not None, "missing onStartCommand")
    require(
        re.search(r"ACTION_SUBMIT_TEXT[\s\S]*handleTextIntent\s*\(\s*intent\s*\)", start_command),
        "text action must enter a dedicated service handler",
    )
    require(text_handler is not None, "missing text action handler")
    require("getStringExtra(EXTRA_TEXT)" in text_handler, "text handler must read EXTRA_TEXT")
    require("pendingTextRequests" in text_handler and ".offer(" in text_handler, "text handler must use bounded queue")
    require("textInputIsBusy()" in text_handler, "text handler must defer while ASR/TTS/assistant work is active")
    require(
        re.search(r"processAssistantInput\s*\([^)]*\bAssistantRequestSource\.TEXT", text_handler),
        "text handler must call shared processor with TEXT",
    )
    non_exit = function_body(WAKE, "processNonExitUtterance")
    text_error = function_body(WAKE, "reportTextAssistantError")
    require(non_exit is not None and "aiOrchestrator.respond(rawText, memory)" in non_exit, "non-local input must use the AI orchestrator")
    require(non_exit is not None and "AiOutcome.Reply" in non_exit, "AI replies must return through the shared response path")
    require(text_error is not None and "appendAssistant(message)" in text_error, "text failures must be visible in the session")
    require(text_error is not None and "continueConversationSession" in text_error, "text failures must keep the session active")

    require(
        "ConversationResultKind.TEXT -> appendToCurrentSession" not in main_source
        and "ConversationResultKind.VOICE -> appendToCurrentSession" not in main_source,
        "MainActivity must not persist typed or voice input as the final processing path",
    )
    require("private fun textInputIsBusy()" in wake, "missing text input busy-state guard")
    require(
        re.search(r"private fun textInputIsBusy\(\)[\s\S]*commandListening\.get\(\)[\s\S]*ttsSpeaking\.get\(\)", wake),
        "text busy-state guard must cover command ASR and TTS",
    )
    drain = function_body(WAKE, "drainPendingTextRequests")
    require(drain is not None and "pendingTextRequests.poll()" in drain, "text queue must dispatch one FIFO request at a time")
    continuation = function_body(WAKE, "continueConversationSession")
    require(
        continuation is not None and "drainPendingTextRequests()" in continuation,
        "completed assistant work must release queued text requests before starting ASR",
    )
    require(
        "WakeServiceController.submitText(this, text)" in main_source,
        "MainActivity text input must dispatch through WakeServiceController",
    )
    print("PASS: v0.7 Task 3 typed and voice shared-pipeline contract")


if __name__ == "__main__":
    main()
