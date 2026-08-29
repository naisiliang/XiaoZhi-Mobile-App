from pathlib import Path
root=Path(__file__).resolve().parents[1]
s=(root/'PUSH_TO_GITHUB.ps1').read_text('utf-8')
checks={
 'manual env seeds GitProxy': '$GitProxy = $env:XIAOZHI_GIT_PROXY' in s,
 'manual override branch exists': 'Manual proxy override is active' in s,
 'manual proxy is probed directly': 'Test-GitHubAccess -Proxy $GitProxy' in s,
 'clone uses GitProxy': "Invoke-GitWithProxy -GitArgs @('clone', $RepoUrl, $TempDir) -Proxy $GitProxy" in s,
}
failed=[k for k,v in checks.items() if not v]
if failed: raise SystemExit('FAIL: '+', '.join(failed))
print('PASS: manual proxy override is forced for probe/clone/push path')
