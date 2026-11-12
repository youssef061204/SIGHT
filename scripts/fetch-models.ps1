[CmdletBinding()]
param([switch]$All)
$ErrorActionPreference = 'Stop'
$curlBinary = if (Get-Command curl.exe -ErrorAction SilentlyContinue) { (Get-Command curl.exe).Source } else { (Get-Command curl -CommandType Application).Source }
$repoRoot = Split-Path -Parent $PSScriptRoot
$manifest = Get-Content -Raw -LiteralPath (Join-Path $repoRoot 'models/manifest.json') | ConvertFrom-Json
foreach ($model in $manifest.models) {
    if (-not $All -and $model.id -notin @('efficientdet_lite0_int8', 'efficientdet_lite2_int8', 'intel_road_segmentation_adas_0001_fp32')) { continue }
    $target = Join-Path $repoRoot $model.path
    $needsDownload = -not (Test-Path -LiteralPath $target)
    if (-not $needsDownload) {
        $needsDownload = (Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash.ToLowerInvariant() -ne $model.sha256
    }
    if ($needsDownload) {
        # A corrected derived model is committed with its provenance. Prefer those verified bytes
        # on a fresh clone; neither model regeneration nor a release download is required to build.
        if ($model.bundled_asset) {
            $bundledSource = Join-Path $repoRoot $model.bundled_asset
            if ((Test-Path -LiteralPath $bundledSource) -and
                (Get-FileHash -Algorithm SHA256 -LiteralPath $bundledSource).Hash.ToLowerInvariant() -eq $model.sha256) {
                Copy-Item -LiteralPath $bundledSource -Destination $target -Force
                $needsDownload = $false
            }
        }
    }
    if ($needsDownload) {
        $partial = "$target.partial"
        & $curlBinary --fail --location --silent --show-error --output $partial $model.url
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
$road = $manifest.models | Where-Object { $_.id -eq 'intel_road_segmentation_adas_0001_fp32' }
Copy-Item -LiteralPath (Join-Path $repoRoot $road.path) -Destination (Join-Path $assetDir 'road_segmentation_adas_0001.tflite') -Force
