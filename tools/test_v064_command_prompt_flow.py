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
listening=b.find('ConversationState.LISTENING')
touch=b.find('session.touch')
start_call=b.find('startLocalCommandRecognition')
post=b.find('postDelayed')
assert min(ready,listening,touch,start_call,post) >= 0, (ready,listening,touch,start_call,post)
assert ready < listening < touch < start_call < post, (ready,listening,touch,start_call,post)
start=re.search(r'private fun startLocalCommandRecognition\(\) \{(.*?)commandRecognitionAttempts',wake,re.S)
assert start and 'conversationState != ConversationState.LISTENING' in start.group(1)
assert 'speakCommandConfirmation(local.reply' in wake
assert 'speakCommandConfirmation(executed.spokenText' in wake
print('PASS: command completion -> guarded real listening flow')
