<#
.SYNOPSIS
    RecruitPro Maven Runner for Windows PowerShell.
.DESCRIPTION
    Safely executes mvnw.cmd with proper java.io.tmpdir resolution and argument forwarding,
    preventing Windows path spacing and temporary file access conflicts.
.EXAMPLE
    .\Run-Maven.ps1 test
    .\Run-Maven.ps1 test -Dtest=AdminPortalIntegrationTest
#>
[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$MavenArgs
)

$ErrorActionPreference = 'Stop'
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
Set-Location $ScriptDir

# Ensure target directory exists for temp operations
$TargetDir = Join-Path $ScriptDir "target"
if (-not (Test-Path $TargetDir)) {
    New-Item -ItemType Directory -Path $TargetDir -Force | Out-Null
}

# Resolve 8.3 short path to avoid space-splitting bugs in batch scripts
$ShortTarget = (New-Object -ComObject Scripting.FileSystemObject).GetFolder($TargetDir).ShortPath
$env:MAVEN_OPTS = "-Djava.io.tmpdir=$ShortTarget"

$MvnwCmd = Join-Path $ScriptDir "mvnw.cmd"
if (-not (Test-Path $MvnwCmd)) {
    throw "mvnw.cmd not found at $MvnwCmd"
}

$ArgLineOption = "-DargLine=-Djava.io.tmpdir=$ShortTarget"
$HasArgLine = ($MavenArgs | Where-Object { $_ -like "-DargLine*" }).Count -gt 0

if ($MavenArgs.Count -eq 0) {
    & $MvnwCmd test $ArgLineOption
} elseif ($HasArgLine) {
    & $MvnwCmd @MavenArgs
} else {
    & $MvnwCmd @MavenArgs $ArgLineOption
}
