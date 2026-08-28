from pathlib import Path

text = Path(__file__).resolve().parents[1].joinpath('PUSH_TO_GITHUB.ps1').read_text(encoding='ascii')

required = [
    'Get-ProxyCandidates',
    'Test-GitHubAccess',
    'Invoke-GitWithProxy',
    'HTTPS_PROXY',
    'ProxyServer',
    '127.0.0.1:7890',
    '127.0.0.1:7897',
    'git ls-remote',
]
missing = [item for item in required if item not in text]
if missing:
    raise SystemExit('Missing network fallback features: ' + ', '.join(missing))

for forbidden in ['git config --global http.proxy', 'git config --global https.proxy']:
    if forbidden in text:
        raise SystemExit('Script must not modify global Git proxy settings: ' + forbidden)

print('PASS: GitHub upload script has local proxy fallback without global Git changes')
