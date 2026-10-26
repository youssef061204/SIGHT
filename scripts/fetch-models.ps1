[CmdletBinding()]
param([switch]$All)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$manifest = Get-Content -Raw -LiteralPath (Join-Path $repoRoot 'models/manifest.json') | ConvertFrom-Json
foreach ($model in $manifest.models) {
    if (-not $All -and $model.id -notin @('efficientdet_lite0_int8', 'efficientdet_lite2_int8')) { continue }
    $target = Join-Path $repoRoot $model.path
    $needsDownload = -not (Test-Path -LiteralPath $target)
    if (-not $needsDownload) {
        $needsDownload = (Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash.ToLowerInvariant() -ne $model.sha256
    }
    if ($needsDownload) {
        $partial = "$target.partial"
        & curl.exe --fail --location --silent --show-error --output $partial $model.url
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $($model.id)" }
        if ((Get-FileHash -Algorithm SHA256 -LiteralPath $partial).Hash.ToLowerInvariant() -ne $model.sha256) {
            throw "SHA256 mismatch: $($model.id)"
        }
        Move-Item -LiteralPath $partial -Destination $target -Force
    }
    Write-Output "Verified $($model.id) $($model.sha256)"
}
$assetDir = Join-Path $repoRoot 'perception/src/main/assets'
New-Item -ItemType Directory -Force -Path $assetDir | Out-Null
foreach ($bundleId in @('efficientdet_lite0_int8', 'efficientdet_lite2_int8')) {
    $bundle = $manifest.models | Where-Object { $_.id -eq $bundleId }
    if (-not $bundle) { throw "Missing bundled model manifest entry: $bundleId" }
    Copy-Item -LiteralPath (Join-Path $repoRoot $bundle.path) -Destination (Join-Path $assetDir "$bundleId.tflite") -Force
}
