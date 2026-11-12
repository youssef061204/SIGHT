# SIGHT emulator demo media

The portfolio demo is [sight-demo.mp4](sight-demo.mp4), rendered from an actual Android 15 emulator screen recording. It shows the SIGHT app using live public Photon/OSRM route lookup, simulated GPS, and the emulator virtual camera. Commands are typed in the app; the recording does not include microphone capture. The side panel labels these conditions, states that this is not a field mobility test, and notes that automatic road voice is disabled.

The recording is genuine app output framed on a landscape canvas. The phone image keeps its aspect ratio; no generated app screens or invented product interactions are used. The video has no audio track. The side panel provides the context that would otherwise be narrated.

## Genuine Android captures

All captures are 540×1200. [Screenshot provenance](screenshots.json) and [video provenance](demo-provenance.json) record hashes and runtime source. The stationary simulated GPS becomes stale during the visual task; the visible expiry warning is actual app behavior.

| Capture | App state |
|---|---|
| [Route consent](routes-consent.png) | Public map services initially off |
| [Numbered matches](destination-choices.png) | Actual live Photon results |
| [Walking instruction](assist-route.png) | Live OSRM route and simulated GPS |
| [Route screen](route-guidance.png) | Retained route context |
| [Camera assistance](assist-camera.png) | CameraX processing virtual-scene frames |
| [Entrance text search](entrance-text-search.png) | Disclosed OCR fallback; route retained |
| [Developer camera](developer-camera.png) | Actual virtual camera and diagnostic counters |
| [Settings](settings.png) | Speech and interaction controls |
| [Double-size settings](large-text-settings.png) | Android font scale 2.0 |
| [Stopped assistance](assistance-stopped.png) | Sensors and navigation stopped |

## Render

After capturing `.tools/sight-demo-raw.mp4` from the running emulator, render it with a locally installed Windows [FFmpeg build](https://ffmpeg.org/download.html). FFmpeg binaries are not included in the repository; the renderer uses Windows Segoe UI fonts. Supply `--ffmpeg C:/path/to/ffmpeg.exe` if the executable is outside its default local tooling path:

```powershell
python scripts/render_product_demo.py --raw .tools/sight-demo-raw.mp4 --apk app/build/outputs/apk/debug/app-debug.apk
```

The default outputs are `docs/media/sight-demo.mp4` and `docs/media/demo-provenance.json`. The provenance file records SHA-256 hashes and sizes of the raw recording, output video, and optional APK; repository-relative paths; the current Git commit and the latest commit touching runtime source (`app/src`, `core/src`, `perception/src`); input/output dimensions, duration, codec and frame rate; and the demo boundaries. Optional chapter notes and a timestamp JSON can be included with `--chapters-json <file>` and `--timestamps-json <file>`.

The renderer creates a 1280×900 canvas, preserves the portrait capture, outputs 20 fps H.264 at CRF 27 with no audio, and uses two FFmpeg threads. Keep the resulting MP4 below 90 MB for GitHub and target 15 MB for the portfolio. If a longer capture exceeds those limits, trim unneeded pauses in the raw recording before rendering; do not speed up or alter app interactions to meet a size target.

## Provenance and limits

The demo demonstrates the app running in an emulator with simulated location and virtual camera input. It is not a field mobility test, microphone demonstration, or evidence that routes or road conditions are safe. Public route services are live at capture time. Automatic road voice is disabled. See [product walkthrough](../product-demo.md) for usage context and limitations.
