$ErrorActionPreference = 'Stop'
$RepoUrl = 'https://github.com/naisiliang/XiaoZhi-Mobile-App.git'
$ProbeUrl = 'https://github.com/git/git.git'
$TempDir = Join-Path $env:TEMP 'XiaoZhi-Mobile-App-upload'
$GitProxy = $null

Write-Host '=== XiaoZhi Mobile v0.2.1 -> GitHub ===' -ForegroundColor Cyan

if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    throw 'Git was not found. Install Git for Windows and run this script again.'
}

function Add-ProxyCandidate {
    param(
        [System.Collections.Generic.List[string]]$List,
        [string]$Value
    )

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return
    }

    $candidate = $Value.Trim()
    if ($candidate -match '^(http|https|socks5)://') {
        # Keep the explicit scheme.
    }
    elseif ($candidate -match '^[^=;]+:\d+$') {
        $candidate = 'http://' + $candidate
    }
    else {
        return
    }

    if (-not $List.Contains($candidate)) {
        $List.Add($candidate)
    }
}

function Get-ProxyCandidates {
    $result = New-Object 'System.Collections.Generic.List[string]'

    # Explicit override for this uploader.
    Add-ProxyCandidate -List $result -Value $env:XIAOZHI_GIT_PROXY

    # Common environment variables.
    Add-ProxyCandidate -List $result -Value $env:HTTPS_PROXY
    Add-ProxyCandidate -List $result -Value $env:HTTP_PROXY
    Add-ProxyCandidate -List $result -Value $env:https_proxy
    Add-ProxyCandidate -List $result -Value $env:http_proxy

    # Windows user proxy settings, often used by Clash/V2Ray clients.
    try {
        $internetSettings = Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' -ErrorAction Stop
        if ($internetSettings.ProxyEnable -eq 1 -and $internetSettings.ProxyServer) {
            $proxyText = [string]$internetSettings.ProxyServer
            if ($proxyText -match ';') {
                $parts = $proxyText.Split(';')
                foreach ($part in $parts) {
                    if ($part -match '^(https|http)=(.+)$') {
                        Add-ProxyCandidate -List $result -Value $Matches[2]
                    }
                }
            }
            elseif ($proxyText -match '=') {
                if ($proxyText -match '(https|http)=([^;]+)') {
                    Add-ProxyCandidate -List $result -Value $Matches[2]
                }
            }
            else {
                Add-ProxyCandidate -List $result -Value $proxyText
            }
        }
    }
    catch {
        # No Windows proxy configured. Continue with local port discovery.
    }

    # Common local proxy ports. Both HTTP and SOCKS are probed.
    # Typical examples: http://127.0.0.1:7890 and http://127.0.0.1:7897
    $ports = @(7890, 7897, 10809, 10808, 1080, 7891, 7893)
    foreach ($port in $ports) {
        $open = $false
        try {
            $open = Test-NetConnection -ComputerName '127.0.0.1' -Port $port -InformationLevel Quiet -WarningAction SilentlyContinue
        }
        catch {
            $open = $false
        }
        if ($open) {
            Add-ProxyCandidate -List $result -Value ("http://127.0.0.1:{0}" -f $port)
            Add-ProxyCandidate -List $result -Value ("socks5://127.0.0.1:{0}" -f $port)
        }
    }

    return $result
}

function Invoke-GitWithProxy {
    param(
        [string[]]$GitArgs,
        [string]$Proxy
    )

    if ([string]::IsNullOrWhiteSpace($Proxy)) {
        & git @GitArgs | ForEach-Object { Write-Host $_ }
    }
    else {
        & git -c ("http.proxy={0}" -f $Proxy) -c ("https.proxy={0}" -f $Proxy) @GitArgs | ForEach-Object { Write-Host $_ }
    }

    return [int]$LASTEXITCODE
}

function Test-GitHubAccess {
    param([string]$Proxy)

    # Probe command: git ls-remote
    if ([string]::IsNullOrWhiteSpace($Proxy)) {
        & git ls-remote $ProbeUrl HEAD 1>$null 2>$null
    }
    else {
        & git -c ("http.proxy={0}" -f $Proxy) -c ("https.proxy={0}" -f $Proxy) ls-remote $ProbeUrl HEAD 1>$null 2>$null
    }

    return ($LASTEXITCODE -eq 0)
}

Write-Host 'Checking GitHub connectivity...'
if (Test-GitHubAccess -Proxy $null) {
    Write-Host 'Direct GitHub connection is available.' -ForegroundColor Green
}
else {
    Write-Host 'Direct GitHub connection failed. Looking for a local proxy...' -ForegroundColor Yellow
    $candidates = Get-ProxyCandidates

    foreach ($candidate in $candidates) {
        Write-Host ("Testing proxy: {0}" -f $candidate)
        if (Test-GitHubAccess -Proxy $candidate) {
            $GitProxy = $candidate
            Write-Host ("Using proxy for this upload only: {0}" -f $GitProxy) -ForegroundColor Green
            break
        }
    }

    if (-not $GitProxy) {
        Write-Host ''
        Write-Host 'No working GitHub connection was found.' -ForegroundColor Red
        Write-Host 'If you use Clash/V2Ray, start it and enable System Proxy or TUN mode.'
        Write-Host 'You can also run this before the BAT file:'
        Write-Host '  set XIAOZHI_GIT_PROXY=http://127.0.0.1:7890'
        Write-Host 'Replace 7890 with the HTTP/Mixed proxy port shown by your proxy app.'
        throw 'Cannot reach GitHub directly or through detected local proxies.'
    }
}

if (Test-Path $TempDir) {
    Remove-Item $TempDir -Recurse -Force
}

Write-Host ("Cloning repository: {0}" -f $RepoUrl)
$cloneExit = Invoke-GitWithProxy -GitArgs @('clone', $RepoUrl, $TempDir) -Proxy $GitProxy
if ($cloneExit -ne 0) {
    throw 'git clone failed even after the connectivity probe.'
}

Write-Host 'Copying project files...'
Get-ChildItem -LiteralPath $PSScriptRoot -Force |
    Where-Object { $_.Name -ne '.git' } |
    ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination $TempDir -Recurse -Force
    }

Push-Location $TempDir
try {
    $userName = git config user.name
    if (-not $userName) {
        git config user.name 'XiaoZhi Mobile Upload'
    }

    $userEmail = git config user.email
    if (-not $userEmail) {
        git config user.email 'xiaozhi-mobile-upload@users.noreply.github.com'
    }

    git add -A
    $changes = git status --porcelain

    if (-not $changes) {
        Write-Host 'Repository already contains the latest project files.' -ForegroundColor Yellow
    }
    else {
        git commit -m 'feat: XiaoZhi Mobile v0.2.1 Android assistant'
        if ($LASTEXITCODE -ne 0) {
            throw 'git commit failed.'
        }
    }

    $branch = (git branch --show-current).Trim()
    if (-not $branch) {
        git checkout -b main
        if ($LASTEXITCODE -ne 0) {
            throw 'Could not create the main branch.'
        }
        $branch = 'main'
    }

    if ($branch -ne 'main') {
        git branch -M main
        if ($LASTEXITCODE -ne 0) {
            throw 'Could not rename the current branch to main.'
        }
    }

    Write-Host 'Pushing to GitHub main branch...'
    $pushExit = Invoke-GitWithProxy -GitArgs @('push', '-u', 'origin', 'main') -Proxy $GitProxy
    if ($pushExit -ne 0) {
        throw 'git push failed. Complete any GitHub sign-in prompt and run the script again.'
    }

    Write-Host ''
    Write-Host 'Upload completed. GitHub Actions should start the APK build automatically.' -ForegroundColor Green
    Write-Host 'Actions: https://github.com/naisiliang/XiaoZhi-Mobile-App/actions'
    Write-Host 'Open the newest Build XiaoZhi Mobile APK run, then download the XiaoZhi-Mobile-APK artifact.'
}
finally {
    Pop-Location
}
