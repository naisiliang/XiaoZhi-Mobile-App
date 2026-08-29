from pathlib import Path
import re
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
for forbidden in ['ACCESS_BACKGROUND_LOCATION', 'AccessibilityService', 'BIND_ACCESSIBILITY_SERVICE']:
    if forbidden in manifest:
        raise SystemExit('forbidden Android capability: ' + forbidden)
safe = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/SafeToolExecutor.kt').read_text('utf-8')
for forbidden in ['delete_all_files', 'send_message', 'transfer_money', 'install_app', 'shell_command']:
    if '"' + forbidden + '" ->' in safe:
        raise SystemExit('forbidden tool executable: ' + forbidden)
print('PASS: v0.6 security regression')
