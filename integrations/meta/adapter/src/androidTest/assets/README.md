The HEVC file is a generated mathematical FFmpeg testsrc2 pattern, not recorded camera footage.

Generation command (FFmpeg 7.1 / libx265):
ffmpeg -f lavfi -i testsrc2=size=320x240:rate=7 -t 4 -c:v libx265 -pix_fmt yuv420p -tag:v hvc1 -x265-params keyint=7:repeat-headers=1:log-level=error:pools=2 -an mock-test-pattern-hevc.mp4

Official MDK streams this explicitly simulated media. It establishes transport/decoder/lifecycle behavior, not real glasses imaging, physical timing or perception accuracy. A handset-camera feed was attempted first; this emulator's HEVC encoder failed to emit codec configuration, so the official file-feed path is exercised instead.

## SIGHT 3.0 conversion and inference fixtures

`mock-color-chart-hevc.mp4` is an eight-second generated red/green/blue/white quadrant chart (320 x 240, 7 FPS). Each decoded quadrant is sampled away from codec boundaries to check chroma plane order, RGB conversion and the explicit zero-degree, unmirrored geometry. Expected RGB is compared with a tolerance of 35 per channel for video conversion/compression. It is not a physical camera calibration.

```powershell
ffmpeg -f lavfi -i 'color=c=black:s=320x240:r=7,drawbox=x=0:y=0:w=160:h=120:color=red:t=fill,drawbox=x=160:y=0:w=160:h=120:color=lime:t=fill,drawbox=x=0:y=120:w=160:h=120:color=blue:t=fill,drawbox=x=160:y=120:w=160:h=120:color=white:t=fill' -t 8 -c:v libx265 -pix_fmt yuv420p -color_range tv -colorspace smpte170m -color_primaries smpte170m -color_trc smpte170m -tag:v hvc1 -x265-params 'keyint=7:repeat-headers=1:log-level=error:pools=2' -an mock-color-chart-hevc.mp4
```

`mock-coco-person-hevc.mp4` repeats the existing known COCO smoke photograph for ten seconds at 7 FPS. Source: COCO val2017 image 350148, Bytemarks, **Tweetup at La Pizza Rina**, [original photograph](https://www.flickr.com/photos/bytemarks/3275441569/), [CC BY 2.0](https://creativecommons.org/licenses/by/2.0/). Changes: video encoding, chroma subsampling, and padding to even dimensions. This fixture was selected after historical detector evaluation; it is an integration smoke test, never a held-out accuracy sample. The unmodified source is `app/src/androidTest/assets/coco_350148.jpg`, SHA-256 `887b3caf92b74588d54005ebdf8dfbf925af064c64fb0c6ca64549b121684b46`.

```powershell
ffmpeg -loop 1 -framerate 7 -i app/src/androidTest/assets/coco_350148.jpg -vf 'scale=in_range=full:out_range=limited:out_color_matrix=bt601,format=yuv420p,pad=ceil(iw/2)*2:ceil(ih/2)*2' -t 10 -c:v libx265 -pix_fmt yuv420p -color_range tv -colorspace smpte170m -color_primaries smpte170m -color_trc smpte170m -tag:v hvc1 -x265-params 'keyint=7:repeat-headers=1:log-level=error:pools=2' -an mock-coco-person-hevc.mp4
```

Generated with the same FFmpeg 7.1 / libx265 binary. Both new fixtures explicitly use BT.601 limited-range YUV, matching the tested converter contract. Public DAT frames have no color-range metadata, so this mock result cannot establish the color range of an arbitrary physical stream. SHA-256:

| File | SHA-256 |
|---|---|
| `mock-color-chart-hevc.mp4` | `430a8a2e5a4f373a615f2eb40a816034f92dc35ecff355a14ecc081a209356a1` |
| `mock-coco-person-hevc.mp4` | `d51bfb40aab8bff1618d06a18a010f91a552641017b95f8e6d3847a305e0a785` |

Instrumentation writes actual decoded-chart values and detector/core results to the test host's cache as `meta-color-chart-proof.json` and `meta-real-pipeline-proof.json`. These must be pulled from an executed run before reporting them as evidence.
