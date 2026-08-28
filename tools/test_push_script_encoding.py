from pathlib import Path

p = Path(__file__).resolve().parents[1] / 'PUSH_TO_GITHUB.ps1'
data = p.read_bytes()
non_ascii = [b for b in data if b > 0x7f]
if non_ascii:
    raise SystemExit(f'FAIL: PUSH_TO_GITHUB.ps1 contains {len(non_ascii)} non-ASCII bytes; Windows PowerShell 5.1 may misdecode UTF-8 without BOM.')
text = data.decode('ascii')
if text.count("'") % 2:
    raise SystemExit('FAIL: odd number of single quotes')
if text.count('{') != text.count('}'):
    raise SystemExit('FAIL: unbalanced braces')
print('PASS: PUSH_TO_GITHUB.ps1 is ASCII-only with balanced quotes/braces')
