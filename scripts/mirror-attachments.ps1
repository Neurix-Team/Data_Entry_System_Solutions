#Requires -Version 5.1
<#
Mirrors every attachment reachable through /api/v1/export into a local folder,
verifying SHA-256 as it goes and writing a manifest that mirrors the server's
ticket_documents table shape. Rate-limited to stay under the token's 120 req/min cap.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)] [string] $Token,
    [Parameter(Mandatory=$true)] [string] $BaseUrl,
    [Parameter(Mandatory=$true)] [string] $OutDir,
    [int] $ThrottleMs = 550,          # 550 ms ⇒ ~109 req/min, safely under 120
    [int] $MaxRetries = 3
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }
$attachDir  = Join-Path $OutDir 'attachments'
if (-not (Test-Path $attachDir)) { New-Item -ItemType Directory -Path $attachDir -Force | Out-Null }
$manifestPath = Join-Path $OutDir 'manifest.json'
$logPath      = Join-Path $OutDir 'mirror.log'
"start $(Get-Date -Format o)" | Set-Content -Path $logPath -Encoding utf8

$headers = @{ Authorization = "Bearer $Token" }

# ---- 1. paginate the ticket listing ----------------------------------------
$tickets = New-Object System.Collections.Generic.List[object]
$cursor  = $null
$pages   = 0
do {
    $url = "$BaseUrl/api/v1/export/tickets?size=100"
    if ($cursor) { $url += "&cursor=$cursor" }
    $raw = Invoke-WebRequest -Uri $url -Headers $headers -UseBasicParsing
    $page = [System.Text.Encoding]::UTF8.GetString($raw.RawContentStream.ToArray()) | ConvertFrom-Json
    $tickets.AddRange([object[]]$page.items)
    $cursor = $page.nextCursor
    $pages++
    Start-Sleep -Milliseconds $ThrottleMs
} while ($page.hasMore -and $pages -lt 100)

$docCount = ($tickets | ForEach-Object { $_.documents } | Where-Object { $_ }).Count
Add-Content -Path $logPath -Value "listing: $($tickets.Count) tickets, $docCount documents, $pages pages"
Write-Host "listing: $($tickets.Count) tickets, $docCount documents"

# ---- 2. download each attachment -------------------------------------------
$rows      = New-Object System.Collections.Generic.List[object]
$ok        = 0
$hashOk    = 0
$hashSkip  = 0
$missing   = 0
$errors    = 0
$i         = 0

$invalidChars = [IO.Path]::GetInvalidFileNameChars() + [char[]]@('<','>',':','"','/','\','|','?','*')

foreach ($t in $tickets) {
    foreach ($d in $t.documents) {
        $i++
        $ticketDir = Join-Path $attachDir ([string]$t.id)
        if (-not (Test-Path $ticketDir)) { New-Item -ItemType Directory -Path $ticketDir -Force | Out-Null }

        # Sanitize filename for Windows and dodge collisions per-ticket-dir.
        $safe = $d.originalFilename
        foreach ($ch in $invalidChars) { $safe = $safe.Replace($ch, '_') }
        if ([string]::IsNullOrWhiteSpace($safe)) { $safe = "doc-$($d.id)" }
        $safe = "$($d.id)__$safe"          # id-prefix keeps every entry unique
        $target = Join-Path $ticketDir $safe

        $status = 'ok'
        $localHash = $null
        if (Test-Path $target) {
            # Resume: skip if size + hash already match. Cheap re-run guard.
            $existing = Get-Item $target
            if ($existing.Length -eq $d.sizeBytes) {
                $existingHash = (Get-FileHash $target -Algorithm SHA256).Hash.ToLower()
                if ($d.contentHash -and $existingHash -eq $d.contentHash) {
                    $status = 'skip-cached'
                    $localHash = $existingHash
                    $ok++
                    $hashOk++
                }
            }
        }

        if ($status -ne 'skip-cached') {
            $downloadUrl = "$BaseUrl$($d.downloadUrl)"
            $attempt = 0
            $done = $false
            while (-not $done -and $attempt -lt $MaxRetries) {
                $attempt++
                try {
                    Invoke-WebRequest -Uri $downloadUrl -Headers $headers -OutFile $target -UseBasicParsing -TimeoutSec 300 | Out-Null
                    $done = $true
                } catch {
                    $code = 0
                    if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode.value__ }
                    if ($code -eq 429) {
                        Start-Sleep -Seconds 60         # honour Retry-After from ApiTokenAuthFilter
                    } elseif ($code -eq 404) {
                        $status = 'missing-on-server'
                        $done = $true
                    } elseif ($attempt -ge $MaxRetries) {
                        $status = "error-$code"
                        Add-Content -Path $logPath -Value "doc $($d.id): $($_.Exception.Message)"
                        $done = $true
                    } else {
                        Start-Sleep -Milliseconds ($ThrottleMs * 4)
                    }
                }
            }

            if ($status -eq 'ok') {
                $fi = Get-Item $target
                $localHash = (Get-FileHash $target -Algorithm SHA256).Hash.ToLower()
                if ($fi.Length -ne $d.sizeBytes) {
                    $status = "size-mismatch(local=$($fi.Length),server=$($d.sizeBytes))"
                    $errors++
                } elseif ($d.contentHash) {
                    if ($localHash -eq $d.contentHash) { $hashOk++ } else { $status = 'hash-mismatch'; $errors++ }
                    $ok++
                } else {
                    $hashSkip++       # server row has null contentHash (old data)
                    $ok++
                }
            } elseif ($status -eq 'missing-on-server') {
                $missing++
            } else {
                $errors++
            }
        }

        $rows.Add([pscustomobject]@{
            id               = $d.id
            ticket_id        = $t.id
            name             = $d.name
            original_filename= $d.originalFilename
            content_type     = $d.contentType
            size_bytes       = $d.sizeBytes
            content_hash     = $d.contentHash
            uploaded_at      = $d.uploadedAt
            local_path       = ($target -replace [regex]::Escape($OutDir), '').TrimStart('\')
            local_sha256     = $localHash
            mirror_status    = $status
        })

        if ($i % 20 -eq 0 -or $status -ne 'ok' -and $status -ne 'skip-cached') {
            Write-Host ("  [{0}/{1}] doc #{2}  {3}  ({4})" -f $i, $docCount, $d.id, $status, $d.originalFilename)
        }

        Start-Sleep -Milliseconds $ThrottleMs
    }
}

# ---- 3. write manifest ------------------------------------------------------
$manifest = [pscustomobject]@{
    generatedAt  = (Get-Date).ToString('o')
    source       = $BaseUrl
    ticketCount  = $tickets.Count
    fileCount    = $docCount
    okCount      = $ok
    hashVerified = $hashOk
    hashMissing  = $hashSkip
    missing      = $missing
    errors       = $errors
    documents    = $rows
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content -Path $manifestPath -Encoding utf8

Add-Content -Path $logPath -Value "done $(Get-Date -Format o): ok=$ok verified=$hashOk skipped=$hashSkip missing=$missing errors=$errors"
Write-Host ''
Write-Host '=== SUMMARY ==='
Write-Host "  tickets      : $($tickets.Count)"
Write-Host "  documents    : $docCount"
Write-Host "  saved OK     : $ok"
Write-Host "  hash verified: $hashOk"
Write-Host "  hash missing : $hashSkip  (old rows w/ null content_hash)"
Write-Host "  missing 404  : $missing"
Write-Host "  errors       : $errors"
Write-Host "  manifest     : $manifestPath"
