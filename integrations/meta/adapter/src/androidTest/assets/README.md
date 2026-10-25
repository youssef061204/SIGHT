The HEVC file is a generated mathematical FFmpeg testsrc2 pattern, not recorded camera footage.

Generation command (FFmpeg 7.1 / libx265):
ffmpeg -f lavfi -i testsrc2=size=320x240:rate=7 -t 4 -c:v libx265 -pix_fmt yuv420p -tag:v hvc1 -x265-params keyint=7:repeat-headers=1:log-level=error:pools=2 -an mock-test-pattern-hevc.mp4

Official MDK streams this explicitly simulated media. It establishes transport/decoder/lifecycle behavior, not real glasses imaging, physical timing or perception accuracy. A handset-camera feed was attempted first; this emulator's HEVC encoder failed to emit codec configuration, so the official file-feed path is exercised instead.
