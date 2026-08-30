from pathlib import Path
import re


root = Path(__file__).resolve().parents[1]
wake = (root / "app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt").read_text(
    encoding="utf-8"
)


def function_body(name: str) -> str:
    match = re.search(rf"private fun {re.escape(name)}\b", wake)
    assert match, f"{name} missing"
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
    assert signature_close >= 0, f"{name} signature is not balanced"
    opening = wake.find("{", signature_close)
    assert opening >= 0, f"{name} body missing"
    depth = 0
    for index in range(opening, len(wake)):
        if wake[index] == "{":
            depth += 1
        elif wake[index] == "}":
            depth -= 1
            if depth == 0:
                return wake[opening + 1 : index]
    raise AssertionError(f"{name} body is not balanced")


def braced_block(source: str, opening: int) -> tuple[str, int]:
    assert opening >= 0 and source[opening] == "{", opening
    depth = 0
    for index in range(opening, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[opening + 1 : index], index
    raise AssertionError("block is not balanced")


schedule = function_body("scheduleListeningAfterSpeech")
ready = schedule.find("setConversationState(ConversationState.READY_TO_LISTEN)")
start_call = schedule.find("startLocalCommandRecognition()")
post_delayed = schedule.find("mainHandler.postDelayed")
assert min(ready, start_call, post_delayed) >= 0, (ready, start_call, post_delayed)
assert ready < start_call < post_delayed, (ready, start_call, post_delayed)
for guard in [
    "!running.get() || !conversationActive || exitInProgress",
    "generation != sessionGeneration",
    "conversationState == ConversationState.EXITING",
]:
    assert 0 <= schedule.find(guard) < start_call, guard
assert "setConversationState(ConversationState.LISTENING)" not in schedule[:start_call], (
    "LISTENING must not be visible before command capture really starts"
)

start = function_body("startLocalCommandRecognition")
assert "conversationState != ConversationState.READY_TO_LISTEN" in start
capture_call = start.find("captureCommandAudio {")
empty_check = start.find("if (samples.isEmpty())", capture_call)
barrier = start.find("val recognizingReady = CountDownLatch(1)", empty_check)
recognizing_post = start.find("mainHandler.post", barrier)
post_opening = start.find("{", recognizing_post)
assert min(capture_call, empty_check, barrier, recognizing_post, post_opening) >= 0, (
    "RECOGNIZING must use a main-thread acknowledgement before decode",
    (capture_call, empty_check, barrier, recognizing_post, post_opening),
)
recognizing_block, post_closing = braced_block(start, post_opening)
recognizing = recognizing_block.find(
    "setConversationState(ConversationState.RECOGNIZING)"
)
barrier_finally = recognizing_block.find("finally", recognizing)
barrier_release = recognizing_block.find("recognizingReady.countDown()", recognizing)
assert 0 <= recognizing < barrier_finally < barrier_release, (
    recognizing,
    barrier_finally,
    barrier_release,
)
post_check = start.find('check(recognizingPosted) { "RECOGNIZING_POST" }', post_closing)
barrier_wait = start.find("recognizingReady.await()", post_closing)
decode = start.find("decodeLocalCommand(samples)", barrier_wait)
assert post_closing < post_check < barrier_wait < decode, (
    post_closing,
    post_check,
    barrier_wait,
    decode,
)
capture_callback = start[capture_call:empty_check]
assert "setConversationState(ConversationState.LISTENING)" not in start[:capture_call]
assert "mainHandler.post" in capture_callback
assert "setConversationState(ConversationState.LISTENING)" in capture_callback
assert "session.touch(settings.sessionTimeoutSeconds)" in capture_callback
assert 'retryLocalCommandRecognition(reason)' in start

capture = function_body("captureCommandAudio")
assert "onRecordingStarted: () -> Unit" in wake
record_start = capture.find("record.startRecording()")
recording_check = capture.find(
    "record.recordingState == AudioRecord.RECORDSTATE_RECORDING", record_start
)
started_callback = capture.find("onRecordingStarted()", recording_check)
assert min(record_start, recording_check, started_callback) >= 0, (
    record_start,
    recording_check,
    started_callback,
)
assert record_start < recording_check < started_callback, (
    record_start,
    recording_check,
    started_callback,
)
assert 'throw IllegalStateException("AUDIO_INIT")' in capture[:record_start]
assert 'throw IllegalStateException("AUDIO_START", e)' in capture

speak_progress = function_body("speakWithProgress")
assert "startLocalCommandRecognition()" not in speak_progress
assert "ttsProgressRegistry.register(" in speak_progress
assert "setOnUtteranceProgressListener" not in speak_progress
assert wake.count("setOnUtteranceProgressListener") == 1
speak_then = function_body("speakThen")
assert "speakWithProgress(text, onDone = done)" in speak_then
assert wake.count("startLocalCommandRecognition()") == 2, (
    "command ASR must only be defined once and started by the guarded scheduler"
)

print("PASS: LISTENING is recording and RECOGNIZING completes before decode")
