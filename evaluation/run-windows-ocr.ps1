[CmdletBinding()]
param(
    [string]$InputPath,
    [string]$OutputPath
)
$ErrorActionPreference = 'Stop'
if (-not $InputPath) { $InputPath = Join-Path $PSScriptRoot 'ocr/references.json' }
if (-not $OutputPath) { $OutputPath = Join-Path $PSScriptRoot 'results/windows-ocr.jsonl' }
Add-Type -AssemblyName System.Runtime.WindowsRuntime
[Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime] | Out-Null
[Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType = WindowsRuntime] | Out-Null
[Windows.Graphics.Imaging.BitmapDecoder, Windows.Foundation, ContentType = WindowsRuntime] | Out-Null
[Windows.Graphics.Imaging.SoftwareBitmap, Windows.Foundation, ContentType = WindowsRuntime] | Out-Null
[Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime] | Out-Null
[Windows.Media.Ocr.OcrResult, Windows.Foundation, ContentType = WindowsRuntime] | Out-Null
[Windows.Globalization.Language, Windows.Globalization, ContentType = WindowsRuntime] | Out-Null
$asTaskMethod = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.IsGenericMethod -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
} | Select-Object -First 1
function Await-WinRt($operation, [Type]$resultType) {
    $method = $asTaskMethod.MakeGenericMethod($resultType)
    $task = $method.Invoke($null, @($operation))
    $task.Wait()
    return $task.Result
}
$language = [Windows.Globalization.Language]::new('en-US')
$engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage($language)
if ($null -eq $engine) { throw 'Windows English OCR language pack is unavailable' }
$results = @()
foreach ($fixture in (Get-Content -Raw -LiteralPath $InputPath | ConvertFrom-Json)) {
    $file = Await-WinRt ([Windows.Storage.StorageFile]::GetFileFromPathAsync($fixture.path)) ([Windows.Storage.StorageFile])
    $stream = Await-WinRt ($file.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
    try {
        $decoder = Await-WinRt ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
        $bitmap = Await-WinRt ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
        try {
            $timer = [System.Diagnostics.Stopwatch]::StartNew()
            $recognized = Await-WinRt ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
            $timer.Stop()
            $results += ([ordered]@{id=$fixture.id; reference=$fixture.reference; hypothesis=$recognized.Text; latency_ms=$timer.Elapsed.TotalMilliseconds; backend='Windows.Media.Ocr en-US (desktop only; app uses ML Kit)'; provenance=$fixture.provenance} | ConvertTo-Json -Compress)
        } finally { $bitmap.Dispose() }
    } finally { $stream.Dispose() }
}
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutputPath) | Out-Null
[System.IO.File]::WriteAllLines($OutputPath, $results, [System.Text.UTF8Encoding]::new($false))
Get-Content -LiteralPath $OutputPath
