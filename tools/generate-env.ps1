<#
.SYNOPSIS
    Creates or completes the local .env with freshly generated secrets (Windows PowerShell 5.1 / PowerShell 7).
.DESCRIPTION
    Same behaviour as tools/generate_env.py, without needing Python: fills JWT_SECRET, AI_SERVICE_TOKEN,
    AGENT_SERVICE_TOKEN and APP_ENCRYPTION_KEY (exactly 32 random bytes, Base64) using the OS cryptographic random
    generator. Existing values are kept unless -Force is given. Secrets are never printed. Never commit .env.
.EXAMPLE
    .\tools\generate-env.ps1
    .\tools\generate-env.ps1 -Force
#>
[CmdletBinding()]
param([switch]$Force)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $root '.env'
$example = Join-Path $root '.env.example'

function New-RandomBase64([int]$count) {
    $bytes = New-Object byte[] $count
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes)
}
function New-UrlSafeToken([int]$count) {
    return (New-RandomBase64 $count).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

$generators = [ordered]@{
    'JWT_SECRET'          = { New-RandomBase64 48 }
    'AI_SERVICE_TOKEN'    = { New-UrlSafeToken 48 }
    'AGENT_SERVICE_TOKEN' = { New-UrlSafeToken 48 }
    'APP_ENCRYPTION_KEY'  = { New-RandomBase64 32 }
}

if (-not (Test-Path $envPath)) {
    if (Test-Path $example) { Copy-Item $example $envPath } else { New-Item -ItemType File -Path $envPath | Out-Null }
    Write-Host "Created $envPath"
}

$lines = [System.Collections.Generic.List[string]]::new()
$seen = @{}
$changed = @()
foreach ($line in [System.IO.File]::ReadAllLines($envPath)) {
    if ($line -match '^(\s*)([A-Z0-9_]+)=(.*)$' -and $generators.Contains($Matches[2])) {
        $name = $Matches[2]
        $seen[$name] = $true
        if ($Force -or [string]::IsNullOrWhiteSpace($Matches[3])) {
            $lines.Add("$($Matches[1])$name=$(& $generators[$name])")
            $changed += $name
            continue
        }
    }
    $lines.Add($line)
}
foreach ($name in $generators.Keys) {
    if (-not $seen.ContainsKey($name)) {
        $lines.Add("$name=$(& $generators[$name])")
        $changed += $name
    }
}
[System.IO.File]::WriteAllText($envPath, (($lines -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding $false))

if ($changed.Count -gt 0) {
    Write-Host "Generated new values for: $($changed -join ', ') (not shown). Never commit .env."
} else {
    Write-Host "All secrets already set; nothing changed (use -Force to rotate them)."
}
