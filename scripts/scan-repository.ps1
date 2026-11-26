param([switch]$WorkingTree, [switch]$Staged)

$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)

if ($WorkingTree -and $Staged) {
    throw 'Choose one scan scope.'
}
$paths = if ($WorkingTree) {
    git -c core.quotepath=false ls-files --cached --others --exclude-standard
} elseif ($Staged) {
    git -c core.quotepath=false diff --cached --name-only --diff-filter=ACMR
} else {
    git -c core.quotepath=false ls-files --cached
}
if ($LASTEXITCODE -ne 0) {
    throw 'Git could not enumerate files to scan.'
}

$secret = '(-----BEGIN (?:[A-Z]+ )*PRIVATE KEY-----|gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|(?:AKIA|ASIA)[A-Z0-9]{16}|AIza[A-Za-z0-9_-]{35}|xox[baprs]-[A-Za-z0-9-]{20,})'
$privatePath = '(?i)(?:^|[\s"=])(?:[A-Z]:[\\/]Users[\\/]|/Users/[^/]+/|/home/[^/]+/)'
$disallowed = '(?i)(^|/)(?:\.git|\.idea|\.gradle|\.kotlin|\.agents|\.codex|\.aws|build|personal|books|captures)(?:/|$)|(^|/)(?:local\.properties|\.env(?:\..*)?|(?:signing|keystore)\.properties)$|\.(?:jks|keystore|p12|pfx|key|apk|aab|sqlite3?(?:-.*)?|db(?:-.*)?|log|hprof|iml|pdf)$'

foreach ($path in @($paths)) {
    if (-not $path) { continue }
    if ($path -match $disallowed) {
        throw 'Repository scan found a restricted path. Review the selected Git changes.'
    }
    if ($path -eq 'gradle/wrapper/gradle-wrapper.jar' -or $path -match '^app/src/main/res/mipmap-[a-z]+/ic_launcher(?:_round)?\.webp$') {
        continue
    }
    if ($path -match '\.(?:aar|jar|so|zip|webp|png|jpe?g|gif|bin|class)$') {
        throw 'Repository scan found a binary outside the reviewed allowlist.'
    }
    $content = if ($WorkingTree) {
        Get-Content -LiteralPath $path -Raw
    } else {
        (git show ":$path") -join "`n"
        if ($LASTEXITCODE -ne 0) { throw 'Could not inspect a Git index entry.' }
    }
    if ($content -cmatch $secret -or $content -match $privatePath) {
        throw 'Repository scan found a possible credential or local filesystem path. Review the selected files.'
    }
}

Write-Output 'Repository scan passed. Review staged changes and binary assets before commit.'
