$ErrorActionPreference = 'Stop'
$curlBinary = if (Get-Command curl.exe -CommandType Application -ErrorAction SilentlyContinue) {
    (Get-Command curl.exe -CommandType Application | Select-Object -First 1).Source
} else { (Get-Command curl -CommandType Application | Select-Object -First 1).Source }
$projectRoot = Split-Path -Parent $PSScriptRoot
$assetDirectory = Join-Path $projectRoot 'app/src/main/assets'
New-Item -ItemType Directory -Force -Path $assetDirectory | Out-Null
$archive = Join-Path $assetDirectory 'voice-model-small-en-us-0.15.zip'
$expected = '30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498'
$verified = (Test-Path -LiteralPath $archive) -and (Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash.ToLowerInvariant() -eq $expected
if (-not $verified) {
    $partialArchive = "$archive.partial"
    & $curlBinary --fail --location --silent --show-error --output $partialArchive 'https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip'
    if ($LASTEXITCODE -ne 0) { throw 'Offline voice model download failed.' }
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $partialArchive).Hash.ToLowerInvariant() -ne $expected) { throw 'Offline voice model SHA256 mismatch.' }
    Copy-Item -LiteralPath $partialArchive -Destination $archive -Force
    Remove-Item -LiteralPath $partialArchive
}
Write-Output "Verified bundled offline voice model: $expected"
