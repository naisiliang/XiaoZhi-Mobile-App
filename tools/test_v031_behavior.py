from pathlib import Path
import sys
root = Path(__file__).resolve().parents[1]
router = (root/'app/src/main/java/com/lchuang/xiaozhimobile/CommandRouter.kt').read_text('utf-8')
manifest = (root/'app/src/main/AndroidManifest.xml').read_text('utf-8')
wake = (root/'app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt').read_text('utf-8')
phone = (root/'app/src/main/java/com/lchuang/xiaozhimobile/PhoneController.kt').read_text('utf-8')
checks = [
    ('stop music synonym', '停止音乐' in router and '停止播放' in router),
    ('wechat package visibility', '<package android:name="com.tencent.mm"' in manifest),
    ('qq package visibility', '<package android:name="com.tencent.mobileqq"' in manifest),
    ('explicit app fallback', 'setPackage(' in phone or 'componentName' in phone),
    ('continuous session helper', 'continueConversationSession' in wake),
    ('local command continues session', 'speakThen(local.reply.ifBlank { "好的" }) { continueConversationSession() }' in wake),
    ('ai answer continues session', 'speakThen(answer) { continueConversationSession() }' in wake),
]
failed=[]
for name, ok in checks:
    print(('PASS' if ok else 'FAIL') + ': ' + name)
    if not ok: failed.append(name)
if failed:
    sys.exit(1)
