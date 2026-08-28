from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
manifest = (root/'app/src/main/AndroidManifest.xml').read_text('utf-8')
main = (root/'app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt').read_text('utf-8')
controller_path = root/'app/src/main/java/com/lchuang/xiaozhimobile/AssistantOverlayController.kt'
view_path = root/'app/src/main/java/com/lchuang/xiaozhimobile/AssistantOverlayView.kt'

checks = [
    ('overlay manifest permission', 'android.permission.SYSTEM_ALERT_WINDOW' in manifest),
    ('overlay permission settings action', 'ACTION_MANAGE_OVERLAY_PERMISSION' in main and 'Settings.canDrawOverlays' in main),
    ('overlay controller exists', controller_path.exists()),
    ('overlay view exists', view_path.exists()),
]

if controller_path.exists():
    controller = controller_path.read_text('utf-8')
    checks += [
        ('application overlay window type', 'TYPE_APPLICATION_OVERLAY' in controller),
        ('overlay does not steal focus', 'FLAG_NOT_FOCUSABLE' in controller),
        ('overlay does not block touch', 'FLAG_NOT_TOUCHABLE' in controller),
        ('overlay permission checked', 'Settings.canDrawOverlays' in controller),
    ]
if view_path.exists():
    view = view_path.read_text('utf-8')
    checks += [
        ('custom overlay drawing', 'override fun onDraw' in view and 'drawCircle' in view),
        ('waveform drawing', 'drawLine' in view and 'audioLevel' in view),
        ('assistant prompt copy', '你好，有什么可以帮你？' in view),
        ('overlay animation loop', 'postInvalidateDelayed' in view),
    ]

failed = []
for name, ok in checks:
    print(('PASS' if ok else 'FAIL') + ': ' + name)
    if not ok:
        failed.append(name)
if failed:
    sys.exit(1)
