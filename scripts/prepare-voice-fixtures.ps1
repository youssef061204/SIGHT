[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech
$projectRoot = Split-Path -Parent $PSScriptRoot
$fixtureDirectory = Join-Path $projectRoot 'app/src/androidTest/assets/voice'
New-Item -ItemType Directory -Force -Path $fixtureDirectory | Out-Null
$voiceSynthesizer = New-Object System.Speech.Synthesis.SpeechSynthesizer
try {
    $voiceSynthesizer.SelectVoice('Microsoft David Desktop')
    $voiceSynthesizer.Rate = 0
    $voiceSynthesizer.Volume = 100
    $audioFormat = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
    $utterances = @(
        @{name='destination'; text='Take me to the library.'; expectedIntent='route-search'; expectedTarget='the library'},
        @{name='find-chair'; text='Find a chair.'; expectedIntent='find'; expectedTarget='chair'},
        @{name='stop-navigation'; text='Stop navigation.'; expectedIntent='cancel-route'; expectedTarget=''}
    )
    $fixtures = foreach ($utterance in $utterances) {
        $wavePath = Join-Path $fixtureDirectory ($utterance.name + '.wav')
        $voiceSynthesizer.SetOutputToWaveFile($wavePath, $audioFormat)
        $voiceSynthesizer.Speak($utterance.text)
        $voiceSynthesizer.SetOutputToNull()
        [ordered]@{
            file=$utterance.name + '.wav'
            referenceText=$utterance.text
            expectedIntent=$utterance.expectedIntent
            expectedTarget=$utterance.expectedTarget
            bytes=(Get-Item -LiteralPath $wavePath).Length
            sha256=(Get-FileHash -Algorithm SHA256 -LiteralPath $wavePath).Hash.ToLowerInvariant()
        }
    }
    $manifest = [ordered]@{
        schema='sight.synthetic-command-audio.v1'
        source='Windows System.Speech.Synthesis.SpeechSynthesizer'
        voice=$voiceSynthesizer.Voice.Name
        locale=$voiceSynthesizer.Voice.Culture.Name
        pcmFormat='16000 Hz, 16-bit signed little-endian PCM, mono, WAV'
        rate=0
        volume=100
        selection='Three fixed command intents selected before ASR execution; no vocabulary grammar is supplied to Vosk'
        boundary='Synthetic clean single-voice command smoke fixtures; not microphone, noise, accent, destination-name or population ASR evaluation'
        fixtures=@($fixtures)
    }
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $fixtureDirectory 'manifest.json') -Encoding UTF8
} finally { $voiceSynthesizer.Dispose() }
Write-Output 'Generated the three declared local synthetic command-audio fixtures.'
