#requires -Version 5.1
[CmdletBinding()]
param(
    # Pass an in-memory token variable (for example: -AccessToken $candidateAccessToken).
    # If omitted, the script prompts for the token with hidden input.
    [string]$AccessToken,

    # Optional: an already-authenticated second synthetic candidate for ownership checks.
    [string]$OtherCandidateAccessToken
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$BaseUrl = 'http://127.0.0.1:8080'
$secureToken = $null
$tokenPointer = [IntPtr]::Zero
$client = $null

function New-SyntheticPdf {
    $stream = [System.IO.MemoryStream]::new()
    $writeText = {
        param([string]$Text)
        $bytes = [System.Text.Encoding]::ASCII.GetBytes($Text)
        $stream.Write($bytes, 0, $bytes.Length)
    }

    & $writeText "%PDF-1.4`n"
    $content = 'BT /F1 12 Tf 72 720 Td (Synthetic RecruitPro resume smoke test) Tj ET'
    $contentLength = [System.Text.Encoding]::ASCII.GetByteCount($content)
    $objects = @(
        '<< /Type /Catalog /Pages 2 0 R >>',
        '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
        '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>',
        "<< /Length $contentLength >>`nstream`n$content`nendstream",
        '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>'
    )
    $offsets = [System.Collections.Generic.List[long]]::new()
    for ($index = 0; $index -lt $objects.Count; $index++) {
        $offsets.Add($stream.Position)
        & $writeText ("{0} 0 obj`n{1}`nendobj`n" -f ($index + 1), $objects[$index])
    }

    $xrefOffset = $stream.Position
    & $writeText "xref`n0 6`n0000000000 65535 f `n"
    foreach ($offset in $offsets) {
        & $writeText ("{0:D10} 00000 n `n" -f $offset)
    }
    & $writeText "trailer`n<< /Size 6 /Root 1 0 R >>`nstartxref`n$xrefOffset`n%%EOF`n"
    $bytes = $stream.ToArray()
    $stream.Dispose()
    return ,$bytes
}

function Invoke-JsonRequest {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Token,
        [System.Net.Http.HttpContent]$Content
    )
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), "$BaseUrl$Path")
    $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Token)
    if ($null -ne $Content) { $request.Content = $Content }
    try {
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        try {
            $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            return [pscustomobject]@{ Status = [int]$response.StatusCode; Body = $body }
        }
        finally { $response.Dispose() }
    }
    finally { $request.Dispose() }
}

function Invoke-DownloadRequest {
    param([Parameter(Mandatory = $true)][string]$Path, [Parameter(Mandatory = $true)][string]$Token)
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Get, "$BaseUrl$Path")
    $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Token)
    try {
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        try {
            return [pscustomobject]@{
                Status = [int]$response.StatusCode
                Bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
                ContentType = if ($null -ne $response.Content.Headers.ContentType) { $response.Content.Headers.ContentType.MediaType } else { '' }
            }
        }
        finally { $response.Dispose() }
    }
    finally { $request.Dispose() }
}

function Assert-Status {
    param([int]$Actual, [int]$Expected, [string]$Step)
    Write-Output ("{0}: HTTP {1}" -f $Step, $Actual)
    if ($Actual -ne $Expected) { throw ("{0} expected HTTP {1}; received HTTP {2}. Response body was suppressed." -f $Step, $Expected, $Actual) }
}

try {
    if ([string]::IsNullOrWhiteSpace($AccessToken)) {
        $secureToken = Read-Host 'Candidate access token (input hidden)' -AsSecureString
        $tokenPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureToken)
        $AccessToken = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($tokenPointer)
    }
    if ([string]::IsNullOrWhiteSpace($AccessToken)) { throw 'A candidate access token is required.' }

    $BaseUrl = $BaseUrl.TrimEnd('/')
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(15)
    $pdfBytes = New-SyntheticPdf
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    $pdfHash = [Convert]::ToBase64String($sha256.ComputeHash($pdfBytes))

    $multipart = [System.Net.Http.MultipartFormDataContent]::new()
    $filePart = [System.Net.Http.ByteArrayContent]::new($pdfBytes)
    $filePart.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse('application/pdf')
    $multipart.Add($filePart, 'file', 'recruitpro-synthetic-resume.pdf')

    $upload = Invoke-JsonRequest -Method 'POST' -Path '/api/v1/candidates/me/resumes' -Token $AccessToken -Content $multipart
    Assert-Status $upload.Status 200 'Upload'
    $uploadData = $upload.Body | ConvertFrom-Json
    $resumeId = [long]$uploadData.data.id
    if ($resumeId -le 0 -or $uploadData.data.fileSize -ne $pdfBytes.Length -or $uploadData.data.contentType -ne 'application/pdf') {
        throw 'Upload response metadata did not match the synthetic PDF; response body was suppressed.'
    }
    Write-Output ("Resume ID: {0}; uploaded bytes: {1}; content type: {2}" -f $resumeId, $uploadData.data.fileSize, $uploadData.data.contentType)

    $list = Invoke-JsonRequest -Method 'GET' -Path '/api/v1/candidates/me/resumes' -Token $AccessToken
    Assert-Status $list.Status 200 'List'
    $listData = $list.Body | ConvertFrom-Json
    $listed = @($listData.data | Where-Object { [long]$_.id -eq $resumeId })
    if ($listed.Count -ne 1) { throw 'Uploaded resume was not present exactly once in the list; response body was suppressed.' }
    Write-Output ("List contains uploaded resume: yes; list item bytes: {0}" -f $listed[0].fileSize)

    $detail = Invoke-JsonRequest -Method 'GET' -Path "/api/v1/candidates/me/resumes/$resumeId" -Token $AccessToken
    Assert-Status $detail.Status 200 'Detail'
    $detailData = $detail.Body | ConvertFrom-Json
    if ([long]$detailData.data.id -ne $resumeId -or [long]$detailData.data.fileSize -ne $pdfBytes.Length) {
        throw 'Resume detail metadata did not match the upload; response body was suppressed.'
    }
    Write-Output ("Detail metadata matches upload: yes; bytes: {0}" -f $detailData.data.fileSize)

    $download = Invoke-DownloadRequest -Path "/api/v1/candidates/me/resumes/$resumeId/download" -Token $AccessToken
    Assert-Status $download.Status 200 'Download'
    $downloadHash = [Convert]::ToBase64String($sha256.ComputeHash($download.Bytes))
    $integrity = $download.Bytes.Length -eq $pdfBytes.Length -and $downloadHash -ceq $pdfHash
    if (-not $integrity) { throw 'Downloaded bytes differ from the uploaded synthetic PDF.' }
    Write-Output ("Download bytes: {0}; content type: {1}; byte integrity: match" -f $download.Bytes.Length, $download.ContentType)

    if (-not [string]::IsNullOrWhiteSpace($OtherCandidateAccessToken)) {
        $otherDetail = Invoke-JsonRequest -Method 'GET' -Path "/api/v1/candidates/me/resumes/$resumeId" -Token $OtherCandidateAccessToken
        Assert-Status $otherDetail.Status 404 'Other-candidate detail ownership check'
        $otherDownload = Invoke-DownloadRequest -Path "/api/v1/candidates/me/resumes/$resumeId/download" -Token $OtherCandidateAccessToken
        Assert-Status $otherDownload.Status 404 'Other-candidate download ownership check'
        $otherDownloadHash = [Convert]::ToBase64String($sha256.ComputeHash($otherDownload.Bytes))
        $resumeContentLeaked = $otherDownload.Bytes.Length -eq $pdfBytes.Length -and $otherDownloadHash -ceq $pdfHash
        if ($resumeContentLeaked -or $otherDownload.ContentType -eq 'application/pdf') {
            throw 'Other-candidate download returned resume content.'
        }
        Write-Output ("Other-candidate response bytes: {0}; uploaded PDF content returned: no" -f $otherDownload.Bytes.Length)
    }

    Write-Output 'Resume smoke test: PASS'
}
catch {
    $exception = $_.Exception
    while ($null -ne $exception) {
        if ($exception.GetType().FullName -eq 'System.Net.Http.HttpRequestException') {
            throw 'Resume smoke test could not reach the backend. No response body or credentials were printed.'
        }
        $exception = $exception.InnerException
    }
    $message = [string]$_.Exception.Message
    if ($message -match '(?i)(expected HTTP|response body was suppressed|differ from|returned resume content)') {
        throw $_.Exception
    }
    throw 'Resume smoke test stopped safely. Response bodies, document content, and credentials were suppressed.'
}
finally {
    if ($sha256) { $sha256.Dispose() }
    if ($client) { $client.Dispose() }
    if ($tokenPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($tokenPointer) }
    if ($secureToken) { $secureToken.Dispose() }
    $AccessToken = $null
    $OtherCandidateAccessToken = $null
    $pdfBytes = $null
}
