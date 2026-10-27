# SIGHT 3 Android 11 evidence

The final unchanged application suite passed **12 tests**, zero failures or ignored tests, in 103.184 seconds on the x86 AOSP ATD emulator. [Raw runner output](app-suite.txt), [device configuration](device.json), [fixed-photo timings](inspection-runtime-v3.json) and [live CameraX stages](camera-runtime-v3.json) are retained. Application source checkpoint: `2814def`; the validation manifest records the exact APK SHA256.

The first run passed 11 checks and failed the independent-scroll UI assertion. An inherited density override of 210 dpi on a 1080 × 2280 display made the whole Assist page fit in a large viewport, so `performScrollTo` correctly performed no scrolling. Its output, configuration and measurements remain under `initial-ui-scroll-failure/`. Restoring the AVD's default **440 dpi** established the intended phone viewport. The complete same 12-test suite then passed, without changing source, assertions, timeouts or freshness limits.

Fixed-photo measurements use three warmups and 30 timed calls per profile in the shared instrumentation process. FAST P50/P95 was **270/307 ms**, INSPECT **686/775 ms**. Instance initialization was 247/582 ms and whole-process PSS 145,461/169,221 KiB respectively. The live trace is a separate source and workload; no fixed-photo rate is labelled camera FPS. ATD drawing was disabled during these tests.

This proves execution of the pixel conversion, real models, bundled OCR, live camera lifecycle, UI/session and privacy checks on the older 32-bit runtime. It does not prove physical ARM performance, offline speech availability, human TalkBack usability, field navigation, glasses hardware or battery behavior. Missing ATD speech proof files are not accepted as capability passes.
