# ============================================================================
# setup-rejection-matrix.ps1
# Prepares the workflow + fixtures used by
# SignFlow-RejectionMatrix.postman_collection.json
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File setup-rejection-matrix.ps1
#
# Requires: the coordination server running on http://localhost:8080
#           (double-click run-server.cmd first) and curl.exe (Windows 10+).
#
# NOTE: multipart bodies are sent with curl.exe. PowerShell 5.1's
# Invoke-RestMethod corrupts hand-built multipart bodies (verified: the
# server answers with a default 400 before reaching app code), so PS is
# used only for JSON/bytes juggling and curl for HTTP.
# ============================================================================
$ErrorActionPreference = "Stop"

$base = "http://localhost:8080"
$outDir = Join-Path $PSScriptRoot "fixtures"
$rev0     = Join-Path $outDir "rev0.pdf"
$modified = Join-Path $outDir "modified.pdf"
$flipped  = Join-Path $outDir "prefix-flipped.pdf"
$garbage  = Join-Path $outDir "garbage.pdf"
$envFile  = Join-Path $outDir "matrix-env.json"
$tmpLogin = Join-Path $outDir "_login.json"
$tmpReq   = Join-Path $outDir "_request.json"

function Api($method, $path, $token, $extraArgs) {
    $args = @("-s", "-X", $method, "$base$path")
    if ($token) { $args += @("-H", "Authorization: Bearer $token") }
    if ($extraArgs) { $args += $extraArgs }
    return Invoke-Expression "curl.exe $($args -join ' ')"
}

Write-Host "==> 1. Login as alice"
'{"username":"alice","password":"alice-pass"}' | Out-File -Encoding ascii $tmpLogin -NoNewline
$loginJson = curl.exe -s -X POST "$base/api/auth/login" `
    -H "Content-Type: application/json" --data "@$tmpLogin"
$alice = ($loginJson | ConvertFrom-Json).accessToken
if (-not $alice) { throw "login failed: $loginJson" }
Write-Host "    access token acquired"

# Re-run hygiene: cancel the PREVIOUS matrix workflow so cancelled-never
# workflows do not pile up in alice's task list.
if (Test-Path $envFile) {
    try {
        $oldWf = ((Get-Content $envFile -Raw | ConvertFrom-Json).values |
            Where-Object { $_.key -eq 'workflowId' }).value
        if ($oldWf) {
            curl.exe -s -X POST "$base/api/workflows/$oldWf/cancel" `
                -H "Authorization: Bearer $alice" | Out-Null
            Write-Host "    previous matrix workflow $oldWf cancelled"
        }
    } catch {
        Write-Host "    no previous matrix workflow to clean up"
    }
}

Write-Host "==> 2. Create the matrix workflow (2 signers, 64-hex fingerprints)"
# Any 64-hex value passes CREATION validation; signing later requires the
# real bound certificate. For the rejection matrix no signing ever succeeds.
$fp1 = "A1B2C3D4E5F60718293A4B5C6D7E8F90A1B2C3D4E5F60718293A4B5C6D7E8F90"
$fp2 = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"
$requestJson = @{
    documentName = "rejection-matrix.pdf"
    signers = @(
        @{ order = 1; userId = "alice"; certificateSha256 = $fp1 },
        @{ order = 2; userId = "bob";   certificateSha256 = $fp2 }
    )
} | ConvertTo-Json -Depth 5 -Compress
[System.IO.File]::WriteAllText($tmpReq, $requestJson)

$createOut = curl.exe -s -X POST "$base/api/workflows" `
    -H "Authorization: Bearer $alice" `
    -F "request=<$tmpReq;type=application/json" `
    -F "file=@$outDir\original.pdf;type=application/pdf"
$created = $createOut | ConvertFrom-Json
if (-not $created.workflowId) { throw "create failed: $createOut" }
$wf = $created.workflowId
Write-Host "    workflow created: $wf"

Write-Host "==> 3. Download revision 0 (alice is the current signer)"
curl.exe -s -H "Authorization: Bearer $alice" `
    -o $rev0 "$base/api/workflows/$wf/document"
$rev0Bytes = [System.IO.File]::ReadAllBytes($rev0)
Write-Host "    rev0: $($rev0Bytes.Length) bytes -> $rev0"

$taskJson = curl.exe -s -H "Authorization: Bearer $alice" `
    "$base/api/workflows/$wf/task"
$task = $taskJson | ConvertFrom-Json
$rev = $task.documentRevision
$sha = $task.documentSha256
Write-Host "    expected parent: revision $rev, sha256 $sha"

Write-Host "==> 4. Build rejection fixtures"
# (a) APPENDED BYTES: byte-prefix intact (append-only gate passes), garbage
#     appended AFTER the EOF marker -> chain broken -> 422 INVALID_SIGNATURE
#     ("PDF is not signed") - verified live.
$suffix = [System.Text.Encoding]::ASCII.GetBytes("TAMPERED-APPENDIX")
[System.IO.File]::WriteAllBytes($modified, $rev0Bytes + $suffix)
Write-Host "    modified.pdf ($($rev0Bytes.Length + $suffix.Length) bytes)"

# (b) PREFIX TAMPER: one byte flipped in the middle -> the submission no
#     longer starts with the parent's exact bytes -> 422 DOCUMENT_MODIFIED
#     at the append-only gate, before any parsing.
$flippedBytes = [byte[]]($rev0Bytes.Clone())
$mid = [int]($rev0Bytes.Length / 2)
$flippedBytes[$mid] = $flippedBytes[$mid] -bxor 0xFF
[System.IO.File]::WriteAllBytes($flipped, $flippedBytes)
Write-Host "    prefix-flipped.pdf (byte $mid flipped)"

# (c) GARBAGE: not a PDF at all -> fails the byte-prefix gate first ->
#     DOCUMENT_MODIFIED.
[System.IO.File]::WriteAllBytes($garbage, [System.Text.Encoding]::ASCII.GetBytes("definitely not a pdf"))
Write-Host "    garbage.pdf  (13 bytes)"

Write-Host "==> 5. Write Postman environment (fixtures/matrix-env.json)"
# Tokens are intentionally NOT set here - the collection's Login requests
# store them as collection variables (environment variables would take
# precedence and freeze them). This env carries only workflow coordinates.
$envObj = [ordered]@{
    name = "rejection-matrix"
    values = @(
        @{ key = "baseUrl";        value = $base; enabled = $true },
        @{ key = "workflowId";     value = $wf;   enabled = $true },
        @{ key = "parentRevision"; value = "$rev"; enabled = $true },
        @{ key = "parentSha256";   value = $sha;   enabled = $true },
        @{ key = "operationId";    value = [Guid]::NewGuid().ToString(); enabled = $true }
    )
}
# BOM-less UTF-8: PS 5.1's Out-File -Encoding utf8 emits a BOM, which can
# break Postman's environment import.
$envJson = $envObj | ConvertTo-Json -Depth 5
[System.IO.File]::WriteAllText($envFile, $envJson)

# cleanup temp payloads
Remove-Item $tmpLogin, $tmpReq -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "DONE. In Postman:"
Write-Host "  1. Import SignFlow-RejectionMatrix.postman_collection.json"
Write-Host "  2. Import environment: fixtures/matrix-env.json  (and select it)"
Write-Host "  3. Run the requests top to bottom - every one must PASS."
