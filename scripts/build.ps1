$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    & "$PSScriptRoot/fetch-models.ps1"
    & "$PSScriptRoot/fetch-voice-model.ps1"
    & ./gradlew.bat :core:test :perception:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process' --max-workers=1 --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Android build or validation failed.' }
    Write-Output 'Installable debug APK: app/build/outputs/apk/debug/app-debug.apk'
} finally { Pop-Location }
