# SIGHT 3 local speech evidence

The final APK passed **2 native speech tests**, zero failures or ignored tests, in 14.587 seconds on the Android 15 Google APIs x86_64 emulator. [Raw runner output](speech-suite.txt) and [device configuration](device.json) are retained. This is separate from the ATD camera/performance sessions.

The installed `en-us-x-tpf-local` English voice reported `networkRequired: false` and produced **183,886 nonempty PCM bytes** in a 183,930-byte WAV. The native test checked RIFF/WAVE structure and nonzero audio signal. The [original WAV](sight-speech-proof.wav) was copied byte-for-byte from the debug application's cache; [synthesis metadata](sight-speech-proof.json) records the assertions.

The production speech queue also interrupted a long ambient utterance when a task arrived, and the real Android engine reported the task start. The [queue proof](speech-queue-native-v3.json) records request and engine-start timestamps. This single scenario measured 820 ms request-to-engine-start for ambient speech and 1814 ms for the priority task. They are two callbacks, not latency percentiles or camera-to-speech measurements.

No human heard the emulator output during validation, and audible speaker onset was not calibrated. These tests do not prove microphone recognition, physical-phone audio timing, sound masking or glasses audio routing. Missing camera JSON files in this speech-only session are not accepted as perception evidence.
