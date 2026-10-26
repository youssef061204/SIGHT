param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [string]$Adb = 'adb',
    [switch]$Meta,
    [string]$Classes,
    [string]$Output = 'evaluation/results/emulator-run.txt'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    & $Adb -s $Serial shell input keyevent KEYCODE_WAKEUP | Out-Null
    & $Adb -s $Serial shell wm dismiss-keyguard | Out-Null
    & $Adb -s $Serial shell svc power stayon true | Out-Null
    & $Adb -s $Serial shell settings put system screen_off_timeout 1800000 | Out-Null
    if ($Meta) {
        $apk = 'integrations/meta/adapter/build/outputs/apk/androidTest/debug/adapter-debug-androidTest.apk'
        $runner = 'org.sight.meta.test/androidx.test.runner.AndroidJUnitRunner'
    } else {
        & $Adb -s $Serial install -r 'app/build/outputs/apk/debug/app-debug.apk'
        if ($LASTEXITCODE -ne 0) { throw 'Application install failed.' }
        # Verify bytecode ahead of a UI timeout on emulator cold installs.
        & $Adb -s $Serial shell cmd package compile -m verify -f org.sight.app
        if ($LASTEXITCODE -ne 0) { throw 'Installed bytecode verification failed.' }
        $apk = 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
        $runner = 'org.sight.app.test/androidx.test.runner.AndroidJUnitRunner'
    }
    & $Adb -s $Serial install -r $apk
    if ($LASTEXITCODE -ne 0) { throw 'Instrumentation APK install failed.' }
    if ($Meta) {
        & $Adb -s $Serial shell cmd package compile -m verify -f org.sight.meta.test
        if ($LASTEXITCODE -ne 0) { throw 'Installed Meta test bytecode verification failed.' }
    }
    $instrumentArgs = @('-s', $Serial, 'shell', 'am', 'instrument', '-w')
    if ($Classes) { $instrumentArgs += @('-e', 'class', $Classes) }
    $instrumentArgs += $runner
    $lines = & $Adb @instrumentArgs 2>&1
    $exitCode = $LASTEXITCODE
    $parent = Split-Path -Parent $Output
    if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    $lines | Set-Content -LiteralPath $Output -Encoding UTF8
    $lines | Write-Output
    $raw = $lines -join "`n"
    if ($exitCode -ne 0 -or $raw -notmatch 'OK \(\d+ tests?\)' -or $raw -match 'FAILURES!!!|Process crashed|INSTRUMENTATION_FAILED') {
        throw "Instrumentation failed or did not finish. See $Output"
    }
    # JUnit assumption skips can still appear in OK; inspect speech-proof.json before claiming synthesis.
} finally { Pop-Location }
