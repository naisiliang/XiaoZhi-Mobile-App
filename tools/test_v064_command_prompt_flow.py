from pathlib import Path
import re
root=Path(__file__).resolve().parents[1]
wake=(root/'app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt').read_text(encoding='utf-8')
for token in [
    'ConversationState.SPEAKING','ConversationState.READY_TO_LISTEN','ConversationState.LISTENING',
    'setConversationState','你有什么需求请说？','请继续说。','IMMEDIATE_LISTEN_DELAY_MS = 120L',
    'scheduleListeningAfterSpeech','sessionGeneration','pendingListenRunnable'
]: assert token in wake, token
m=re.search(r'private fun scheduleListeningAfterSpeech\(.*?\) \{(.*?)\n    \}',wake,re.S)
assert m, 'scheduleListeningAfterSpeech missing'
b=m.group(1)
ready=b.find('ConversationState.READY_TO_LISTEN')
start_call=b.find('startLocalCommandRecognition')
post=b.find('postDelayed')
assert min(ready,start_call,post) >= 0, (ready,start_call,post)
assert ready < start_call < post, (ready,start_call,post)
assert 'setConversationState(ConversationState.LISTENING)' not in b[:start_call]
start=re.search(r'private fun startLocalCommandRecognition\(\) \{(.*?)commandRecognitionAttempts',wake,re.S)
assert start and 'conversationState != ConversationState.READY_TO_LISTEN' in start.group(1)
capture=re.search(r'private fun captureCommandAudio\(onRecordingStarted: \(\) -> Unit\)[^{]*\{(.*?)\n    \}',wake,re.S)
assert capture, 'captureCommandAudio callback missing'
capture_body=capture.group(1)
record_start=capture_body.find('record.startRecording()')
recording_check=capture_body.find('record.recordingState == AudioRecord.RECORDSTATE_RECORDING')
started_callback=capture_body.find('onRecordingStarted()')
assert min(record_start,recording_check,started_callback) >= 0
assert record_start < recording_check < started_callback
capture_integration=wake.find('val samples = captureCommandAudio {')
listening=wake.find('setConversationState(ConversationState.LISTENING)',capture_integration)
touch=wake.find('session.touch(settings.sessionTimeoutSeconds)',listening)
assert capture_integration < listening < touch
speak_then=re.search(r'private fun speakThen\(.*?\) \{(.*?)\n    \}',wake,re.S)
assert speak_then and 'startLocalCommandRecognition()' not in speak_then.group(1)
assert 'if (utteranceId == id) mainHandler.post(done)' in speak_then.group(1)
assert 'speakCommandConfirmation(local.reply' in wake
assert 'speakCommandConfirmation(executed.spokenText' in wake
print('PASS: command completion -> guarded real listening flow')
