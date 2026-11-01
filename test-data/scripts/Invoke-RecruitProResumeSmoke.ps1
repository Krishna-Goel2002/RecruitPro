#requires -Version 5.1
[CmdletBinding()]
param(
    # Base URL of the running backend (default: http://127.0.0.1:8080)
    [string]$BaseUrl = 'http://127.0.0.1:8080',

    # Optional: candidate access token. If omitted, a temporary synthetic candidate is registered via the live API.
    [string]$AccessToken,

    # Optional: second candidate access token for ownership isolation check.
    # If omitted, a second temporary synthetic candidate is registered via the live API.
    [string]$OtherCandidateAccessToken
)

$ErrorActionPreference = 'Stop'
Set-ExecutionPolicy -Scope Process Bypass -Force
Add-Type -AssemblyName System.Net.Http

$BaseUrl = $BaseUrl.TrimEnd('/')
$smokeScript = Join-Path $PSScriptRoot 'Test-RecruitProResumeSmoke.ps1'
if (-not (Test-Path -LiteralPath $smokeScript -PathType Leaf)) {
    throw "Smoke test script not found at $smokeScript"
}

# 1. Verify backend health
Write-Host "Probing backend health at $BaseUrl/api/v1/health..."
$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(5)
try {
    $healthRequest = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Get, "$BaseUrl/api/v1/health")
    $healthResponse = $client.SendAsync($healthRequest).GetAwaiter().GetResult()
    try {
        if ([int]$healthResponse.StatusCode -ne 200) {
            throw "Backend health returned HTTP $([int]$healthResponse.StatusCode); expected 200."
        }
        Write-Host "Backend health: UP (HTTP 200)"
    }
    finally { $healthResponse.Dispose() }
}
catch [System.Net.Http.HttpRequestException] {
    Write-Error "Backend is not reachable at $BaseUrl. Ensure Spring Boot is running before executing smoke tests."
    exit 2
}
finally {
    if ($healthRequest) { $healthRequest.Dispose() }
    if ($client) { $client.Dispose() }
}

$createdPrimary = $false
$createdOther = $false
$primaryRefreshToken = $null
$otherRefreshToken = $null

function Register-SyntheticCandidate {
    param([string]$Url, [string]$RolePrefix)
    $suffix = [System.Guid]::NewGuid().ToString('N')
    $email = "$RolePrefix-$suffix@example.test"
    $bytes = [byte[]]::new(16)
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($bytes)
    $rng.Dispose()
    $pwd = "SmokePass!" + [Convert]::ToBase64String($bytes).Substring(0, 10) + "1a"

    $payload = @{
        firstName = "Smoke"
        lastName = "Candidate"
        email = $email
        password = $pwd
    } | ConvertTo-Json -Compress

    $http = [System.Net.Http.HttpClient]::new()
    try {
        $content = [System.Net.Http.StringContent]::new($payload, [System.Text.Encoding]::UTF8, 'application/json')
        $resp = $http.PostAsync("$Url/api/v1/auth/register", $content).GetAwaiter().GetResult()
        try {
            if ([int]$resp.StatusCode -ne 201) {
                throw "Synthetic candidate registration returned HTTP $([int]$resp.StatusCode)"
            }
            $body = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            $json = $body | ConvertFrom-Json
            return [pscustomobject]@{
                AccessToken = [string]$json.data.accessToken
                RefreshToken = [string]$json.data.refreshToken
            }
        }
        finally { $resp.Dispose() }
    }
    finally { $http.Dispose() }
}

function Revoke-Token {
    param([string]$Url, [string]$RefreshToken)
    if ([string]::IsNullOrWhiteSpace($RefreshToken)) { return }
    $http = [System.Net.Http.HttpClient]::new()
    try {
        $payload = @{ refreshToken = $RefreshToken } | ConvertTo-Json -Compress
        $content = [System.Net.Http.StringContent]::new($payload, [System.Text.Encoding]::UTF8, 'application/json')
        $resp = $http.PostAsync("$Url/api/v1/auth/logout", $content).GetAwaiter().GetResult()
        $resp.Dispose()
    }
    catch { }
    finally { $http.Dispose() }
}

try {
    # 2. Provision credentials if not provided
    if ([string]::IsNullOrWhiteSpace($AccessToken)) {
        Write-Host "Registering temporary synthetic primary candidate for resume smoke test..."
        $auth = Register-SyntheticCandidate -Url $BaseUrl -RolePrefix 'smoke-resume-primary'
        $AccessToken = $auth.AccessToken
        $primaryRefreshToken = $auth.RefreshToken
        $createdPrimary = $true
        Write-Host "Primary candidate registered successfully."
    }

    if ([string]::IsNullOrWhiteSpace($OtherCandidateAccessToken)) {
        Write-Host "Registering temporary synthetic second candidate for ownership isolation..."
        $otherAuth = Register-SyntheticCandidate -Url $BaseUrl -RolePrefix 'smoke-resume-other'
        $OtherCandidateAccessToken = $otherAuth.AccessToken
        $otherRefreshToken = $otherAuth.RefreshToken
        $createdOther = $true
        Write-Host "Second candidate registered successfully."
    }

    # 3. Execute smoke test
    Write-Host "Executing Test-RecruitProResumeSmoke.ps1..."
    & $smokeScript -AccessToken $AccessToken -OtherCandidateAccessToken $OtherCandidateAccessToken
    Write-Host "Resume smoke verification: SUCCESS"
}
finally {
    # 4. Clean up sessions
    if ($createdPrimary -and -not [string]::IsNullOrWhiteSpace($primaryRefreshToken)) {
        Revoke-Token -Url $BaseUrl -RefreshToken $primaryRefreshToken
    }
    if ($createdOther -and -not [string]::IsNullOrWhiteSpace($otherRefreshToken)) {
        Revoke-Token -Url $BaseUrl -RefreshToken $otherRefreshToken
    }
    $AccessToken = $null
    $OtherCandidateAccessToken = $null
    $primaryRefreshToken = $null
    $otherRefreshToken = $null
}
