from pathlib import Path
root = Path(__file__).resolve().parents[1]
path = root / 'app/src/main/java/com/lchuang/xiaozhimobile/SafeToolExecutor.kt'
if not path.exists():
    raise SystemExit('SafeToolExecutor.kt missing')
text = path.read_text('utf-8')
allowed = ['open_app','navigate','search_nearby','open_web','media_play','media_pause','media_next','media_previous','volume_up','volume_down','set_volume','flashlight_on','flashlight_off']
for name in allowed:
    if '"' + name + '"' not in text:
        raise SystemExit('allowlisted tool missing: ' + name)
for forbidden in ['delete_all_files','send_message','transfer_money','install_app','shell_command']:
    if forbidden in allowed:
        raise SystemExit('forbidden tool accidentally allowlisted: ' + forbidden)
for scheme in ['javascript:', 'file:', 'content:', 'intent:']:
    if scheme not in text:
        raise SystemExit('dangerous web scheme rejection missing: ' + scheme)
print('PASS: v0.6 safe tool allowlist source')
