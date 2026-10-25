$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    if (-not (Test-Path -LiteralPath 'app/src/main/assets/efficientdet_lite0_int8.tflite')) {
        & "$PSScriptRoot/fetch-models.ps1"
    }
    & ./gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Android build or validation failed.' }
    Write-Output 'Installable debug APK: app/build/outputs/apk/debug/app-debug.apk'
} finally { Pop-Location }
