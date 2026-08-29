from pathlib import Path
import re
root=Path(__file__).resolve().parents[1]
controller=(root/'app/src/main/java/com/lchuang/xiaozhimobile/AssistantOverlayController.kt').read_text(encoding='utf-8')
view=(root/'app/src/main/java/com/lchuang/xiaozhimobile/AssistantOverlayView.kt').read_text(encoding='utf-8')
assert 'FLAG_NOT_TOUCHABLE' not in controller
assert 'setOnExitRequested' in controller
assert 'FLAG_NOT_FOCUSABLE' in controller
assert 'FLAG_NOT_TOUCH_MODAL' in controller
layout=re.search(r'WindowManager\.LayoutParams\((.*?)PixelFormat\.TRANSLUCENT',controller,re.S)
assert layout
assert 'MATCH_PARENT' not in layout.group(1), 'overlay must be panel-sized, not fullscreen'
assert 'panelWidth' in controller and 'panelHeight' in controller
assert 'GestureDetector' in view
assert 'onDoubleTap' in view
assert 'onExitRequested' in view
assert 'closeHitRect' in view
assert '"×"' in view
assert 'ConversationState' in view and 'setConversationState' in view
print('PASS: panel-sized overlay supports close + double-tap exit')
wake = (root/'app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt').read_text(encoding='utf-8')
for token in ['overlay.setOnExitRequested', 'requestConversationExit', 'pendingListenRunnable', 'ConversationState.EXITING']:
    assert token in wake, f'WakeService missing manual exit integration: {token}'
print('PASS: WakeService wires overlay manual exit')
