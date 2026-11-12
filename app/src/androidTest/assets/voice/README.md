# Declared synthetic command audio

These three WAV files were generated locally by `scripts/prepare-voice-fixtures.ps1` using Windows `System.Speech.Synthesis.SpeechSynthesizer`, Microsoft David Desktop en-US, rate 0, volume 100, 16 kHz mono PCM16. The fixed texts and intended command semantics were selected before ASR execution. Original hashes are recorded in `manifest.json`.

They are synthetic test stimuli, not microphone recordings or evidence of human speech-recognition accuracy. No expected transcript or phrase grammar is supplied to Vosk. Native tests inspect the actual recognized text and pass it to the production command parser. Android 11/15 execution must be reported separately from merely creating these files. The fixture generation tool requires Windows and that installed voice; the committed audio can be used by Android builds on any supported host.
