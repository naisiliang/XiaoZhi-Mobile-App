from pathlib import Path
import re
import xml.etree.ElementTree as ET
root = Path(__file__).resolve().parents[1]
scan_ext = {'.kt', '.kts', '.py', '.md', '.xml', '.yml', '.yaml', '.sh', '.ps1', '.bat', '.txt'}
text_parts = []
for p in root.rglob('*'):
    if p.is_file() and p.suffix.lower() in scan_ext and '.git' not in p.parts:
        text_parts.append((p, p.read_text('utf-8', errors='ignore')))
secret_re = re.compile(r'\bsk-[A-Za-z0-9_-]{20,}\b')
for p, text in text_parts:
    if secret_re.search(text):
        raise SystemExit(f'possible API key committed: {p}')
manifest = (root / 'app/src/main/AndroidManifest.xml').read_text('utf-8')
for forbidden in ['ACCESS_BACKGROUND_LOCATION']:
    if forbidden in manifest:
        raise SystemExit('forbidden Android capability: ' + forbidden)
android = '{http://schemas.android.com/apk/res/android}'
manifest_root = ET.fromstring(manifest)
accessibility_service = next((service for service in manifest_root.findall('./application/service')
    if service.get(android + 'name') == '.accessibility.XiaoZhiAccessibilityService'), None)
if accessibility_service is not None:
    if accessibility_service.get(android + 'permission') != 'android.permission.BIND_ACCESSIBILITY_SERVICE':
        raise SystemExit('Accessibility must require system binding permission')
    if accessibility_service.get(android + 'exported') != 'true':
        raise SystemExit('Accessibility service must be exported for system binding')
    production = '\n'.join(text for p, text in text_parts if 'app/src/main' in p.as_posix())
    for forbidden in ['Settings.Secure', 'enabled_accessibility_services', 'accessibility_enabled',
                      'WRITE_SECURE_SETTINGS', 'executeShellCommand', 'pm grant', 'appops set']:
        if forbidden.lower() in production.lower():
            raise SystemExit('forbidden Accessibility enable bypass: ' + forbidden)
safe = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/SafeToolExecutor.kt').read_text('utf-8')
for forbidden in ['delete_all_files', 'send_message', 'transfer_money', 'install_app', 'shell_command']:
    if '"' + forbidden + '" ->' in safe:
        raise SystemExit('forbidden tool executable: ' + forbidden)
print('PASS: v0.6 security regression')
