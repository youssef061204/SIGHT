# Bundled offline English speech recognition

The phone application prefers Android's declared on-device recognition service when available. If it is unavailable or cannot initialize, a requested single utterance uses **Vosk Android 0.3.75** and **vosk-model-small-en-us-0.15**. It never falls back to a network recognizer. There is no wake-word listener.

The official [Vosk model list](https://alphacephei.com/vosk/models) identifies this small English model as Apache 2.0. The [Android library](https://github.com/alphacep/vosk-api/tree/master/android) is Apache 2.0; its published POM declares the same license. Copyright and upstream authorship remain with Alpha Cephei and the respective Vosk/Kaldi contributors. The upstream [Vosk Apache 2.0 license text](LICENSE-Vosk.txt) is retained in this directory. The transitive **JNA 5.18.1** Android AAR is used under its Apache 2.0 option; [JNA's dual-license notice](LICENSE-JNA.txt) and its [upstream source](https://github.com/java-native-access/jna/blob/5.18.1/LICENSE) identify that choice.

| Artifact | Pinned identity |
|---|---|
| Vosk Android | `com.alphacephei:vosk-android:0.3.75` |
| Native interface | `net.java.dev.jna:jna:5.18.1@aar`, declared transitively by Vosk |
| Model source | `https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip` |
| Model ZIP bytes | 41,205,931 |
| Model ZIP SHA256 | `30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498` |
| Expanded bytes | 70,898,967 |
| Native ABIs in the inspected Android artifacts | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |

`scripts/fetch-voice-model.ps1` downloads and verifies the fixed model into an ignored asset ZIP. The build verifies it again before APK packaging. Application use never downloads a model: the first fallback utterance verifies and extracts the bundled ZIP into an allowlisted app-private no-backup directory. Subsequent loads verify its completion marker and each expected file size. Microphone capture is 16 kHz mono PCM16, limited to one utterance and at most 15 seconds of recording; audio is not saved or uploaded. The model is closed after each fallback utterance to avoid permanently retaining its native working memory. Model preparation occurs before the capture window and may delay the first prompt; cancelling invalidates the request and any later callbacks.

The small-model upstream memory estimate is approximately 300 MB; this is a planning figure, not a measured SIGHT memory result. Actual Android process PSS and preparation/decode timings must come from the native proof file. Native ABI presence is not proof of successful execution on each ABI.

## Reproducible native check

`NativeVoiceRecognitionTest` passes actual WAV PCM through the same `LocalVoiceDecoder` used for microphone fallback, then through SIGHT's production navigation or visual-command parser. The three fixed files in `app/src/androidTest/assets/voice/` were synthesized locally using Windows `System.Speech` with Microsoft David Desktop, en-US, rate 0, 16 kHz mono PCM16. They contain ordinary predefined command text, not user recordings. The fixture manifest records original byte hashes and intent labels; no phrase grammar is supplied to the recognizer. `scripts/prepare-voice-fixtures.ps1` records the generation recipe. A one-second zero-PCM negative case checks that silence produces no command.

This is a clean, single-synthetic-voice smoke check. It does not measure physical microphone capture, real users, accents, noise, arbitrary destination names, population word error rate or outdoor hands-free usability. The test writes `native-voice-recognition-proof.json`, including actual recognized transcripts, even when a command assertion fails. An unexecuted test or ABI list is never reported as a pass.
