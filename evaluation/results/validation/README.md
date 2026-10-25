# Executed validation

[summary.json](summary.json) records test outcomes, APK/model hashes and emulator fingerprints. JUnit XML copies are adjacent. Python tool output is [retained](../python-tests.txt).

Final app APK: 125,450,803 bytes, SHA-256 `96231bdd0bb95c092f039db3eadd63a64f9950b2cc8fff169d992cb06dd79232`.

- [Final Android 11 suite: 3 passed](../emulator-api30/final-suite.txt).
- [Final Android 15 AOSP suite: 3 passed](../emulator-api35/atd-final-suite.txt).
- [Official MDK on Android 15 AOSP: 5 passed](../emulator-api35/atd-meta-instrumentation.txt).
- [Official MDK on Android 15 Google APIs: 5 passed](../emulator-api35/meta-instrumentation.txt).
- [Local TTS metadata](../emulator-api35/speech-proof.json): nonempty PCM synthesized on the Google APIs image; WAV was not copied to the repository and no listening test is claimed.
- [Current paired desktop timing](../paired-clean-latency-v2/report.md) and [current accuracy](../desktop-coco-smoke-v2/report.md).

Raw older PowerShell logs are UTF-16LE; the collector explicitly detects that encoding. New final-suite logs are UTF-8. Google-image combined rerun failures and initial implementation failures are preserved. Final camera acceptance used AOSP ATD; freshness limits stayed unchanged.

These are native Android emulator and official simulated-media tests. Physical cameras, glasses, audible routing, participant usability and independent mobility remain unvalidated.
