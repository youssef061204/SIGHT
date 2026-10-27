# SIGHT 3 Android 15 evidence

The final application suite passed **12 tests**, zero failures or ignored tests, in 108.416 seconds. The raw runner output is [app-suite.txt](app-suite.txt). The final source checkpoint is `2814def`; the validation manifest records the APK SHA256. Timing JSON files were copied from the application cache after that suite. They are actual native MediaPipe/ML Kit/CameraX results on an x86_64 AOSP ATD emulator, not physical-phone measurements.

The project's integration suite against the official Meta DAT/Mock Device Kit separately passed **8 tests** in 22.928 seconds. These are project-authored checks, not Meta certification. The raw runner output and two JSON proof files are in this directory. See [the Meta report](../../../docs/meta-v3.md) for the accepted mock calibration, initial failures and hardware boundaries.

## Actual screen captures

Captured directly with `adb exec-out screencap -p` after the timing suite. No generated content, annotations or image edits were added. The ATD drawing flag was enabled for screenshots; it was disabled during instrumentation and timing, as recorded in [device.json](device.json). The virtual-scene camera is simulated. No object hypotheses were inserted for screenshots.

| Screen | Capture |
|---|---|
| Assist, camera active | [assist.png](assist.png) |
| Developer, live source and counters | [developer-live.png](developer-live.png) |
| Developer, frame stages and timestamp basis | [developer-details.png](developer-details.png) |
| Settings, narration controls | [settings.png](settings.png) |
| Settings, actual model choices | [settings-models.png](settings-models.png) |
| Memory, no confirmed context or saved landmarks | [memory.png](memory.png) |
| Assist at Android font scale 2.0, camera paused | [assist-large-text.png](assist-large-text.png) |

Each matching `.xml` file contains the UI Automator hierarchy collected immediately after the corresponding image. Live counters may advance between the image and XML. Screenshot dimensions are 540 × 1200 with density 210; the default ATD physical display is 320 × 640. The screenshot session is separate from the exported timing session.

## Historical comparison

`initial-ef1ba30/` preserves the earlier 11-test app run, its earlier APK identity and runtime samples before the final deletion changes. It is not the final application suite. Earlier SIGHT 2 evidence remains in separate `emulator-api35/` and `validation/` directories.

No audible speaker onset, physical glasses, human TalkBack trial, field navigation, battery impact or sustained physical-device thermal performance was measured here. Missing speech proof files on ATD do not count as a speech capability pass; offline speech uses a separate Google APIs image.
