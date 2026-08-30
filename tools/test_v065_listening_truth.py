from pathlib import Path
import re


root = Path(__file__).resolve().parents[1]
wake = (root / "app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt").read_text(
    encoding="utf-8"
)


def function_body(name: str) -> str:
    match = re.search(rf"private fun {re.escape(name)}\b[^{{]*\{{", wake)
    assert match, f"{name} missing"
    opening = match.end() - 1
    depth = 0
    for index in range(opening, len(wake)):
        if wake[index] == "{":
            depth += 1
        elif wake[index] == "}":
            depth -= 1
            if depth == 0:
                return wake[opening + 1 : index]
    raise AssertionError(f"{name} body is not balanced")


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
recognizing = start.find("setConversationState(ConversationState.RECOGNIZING)", empty_check)
decode = start.find("decodeLocalCommand(samples)", recognizing)
assert min(capture_call, empty_check, recognizing, decode) >= 0, (
    capture_call,
    empty_check,
    recognizing,
    decode,
)
capture_callback = start[capture_call:empty_check]
assert "setConversationState(ConversationState.LISTENING)" not in start[:capture_call]
assert "mainHandler.post" in capture_callback
assert "setConversationState(ConversationState.LISTENING)" in capture_callback
assert "session.touch(settings.sessionTimeoutSeconds)" in capture_callback
assert empty_check < recognizing < decode, (empty_check, recognizing, decode)
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

speak_then = function_body("speakThen")
assert "startLocalCommandRecognition()" not in speak_then
assert "if (utteranceId == id) mainHandler.post(done)" in speak_then
assert wake.count("startLocalCommandRecognition()") == 2, (
    "command ASR must only be defined once and started by the guarded scheduler"
)

print("PASS: LISTENING begins only after microphone recording is confirmed")
