#!/usr/bin/env python3
"""Render a genuine portrait SIGHT Android capture into a landscape demo."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_FFMPEG = ROOT / ".tools/ffmpeg/ffmpeg-9.0.2-essentials_build/bin/ffmpeg.exe"
DEFAULT_RAW = ROOT / ".tools/sight-demo-raw.mp4"
DEFAULT_OUTPUT = ROOT / "docs/media/sight-demo.mp4"
DEFAULT_MANIFEST = ROOT / "docs/media/demo-provenance.json"
CANVAS_W, CANVAS_H = 1280, 900
PANE_W, PANE_H = 760, 850


def digest(path: Path) -> dict[str, int | str]:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    resolved = path.resolve()
    try:
        public_path = resolved.relative_to(ROOT).as_posix()
    except ValueError:
        # Avoid publishing machine-specific directories when callers pass external inputs.
        public_path = resolved.name
    return {"path": public_path, "bytes": path.stat().st_size, "sha256": h.hexdigest()}


def probe(ffprobe: Path, path: Path) -> dict:
    result = subprocess.run(
        [str(ffprobe), "-v", "error", "-show_entries", "format=duration,size:stream=codec_type,codec_name,width,height,r_frame_rate",
         "-of", "json", str(path)],
        check=True, capture_output=True, text=True,
    )
    data = json.loads(result.stdout)
    video = next((s for s in data.get("streams", []) if s.get("codec_type") == "video"), {})
    return {
        "durationSeconds": float(data.get("format", {}).get("duration", 0)),
        "width": video.get("width"), "height": video.get("height"),
        "videoCodec": video.get("codec_name"), "frameRate": video.get("r_frame_rate"),
        "hasAudio": any(s.get("codec_type") == "audio" for s in data.get("streams", [])),
    }


def git_commit() -> str | None:
    try:
        return subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, check=True,
                              capture_output=True, text=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return None


def runtime_source_commit() -> str | None:
    try:
        result = subprocess.run(
            ["git", "log", "-1", "--format=%H", "--", "app/src", "core/src", "perception/src"],
            cwd=ROOT, check=True, capture_output=True, text=True,
        ).stdout.strip()
        return result or None
    except (OSError, subprocess.CalledProcessError):
        return None


def ff_path(path: Path) -> str:
    """Use a filter-script-safe relative path for text files."""
    return path.relative_to(ROOT).as_posix()


def render(args: argparse.Namespace) -> None:
    raw = args.raw.resolve()
    output = args.output.resolve()
    ffmpeg = args.ffmpeg.resolve()
    ffprobe = args.ffprobe.resolve() if args.ffprobe else ffmpeg.with_name("ffprobe.exe")
    for required in (raw, ffmpeg, ffprobe):
        if not required.is_file():
            raise SystemExit(f"Required file not found: {required}")
    if raw == output:
        raise SystemExit("Input and output must be different files.")
    output.parent.mkdir(parents=True, exist_ok=True)

    chapters = json.loads(args.chapters_json.read_text(encoding="utf-8")) if args.chapters_json else None
    timestamps = json.loads(args.timestamps_json.read_text(encoding="utf-8")) if args.timestamps_json else None
    if chapters is not None and not isinstance(chapters, list):
        raise SystemExit("--chapters-json must contain a JSON array.")

    with tempfile.TemporaryDirectory(prefix="sight-demo-render-", dir=ROOT) as temp_name:
        temp = Path(temp_name)
        # File-backed text keeps filter escaping predictable and makes all copy legible.
        lines = [
            ("eyebrow", "SIGHT", "#71D5C2", 48, "bold"),
            ("title", "Android emulator walkthrough", "#FFFFFF", 34, "bold"),
            ("detail1", "Simulated GPS + virtual camera", "#D7E0EA", 25, "regular"),
            ("detail2", "Commands entered in app", "#D7E0EA", 25, "regular"),
            ("detail3", "Live public map services", "#D7E0EA", 21, "regular"),
            ("rule", "", "", 1, "regular"),
            ("notice", "Not a field mobility test", "#F2C879", 23, "bold"),
            ("notice2", "Automatic road voice is disabled", "#D7E0EA", 21, "regular"),
        ]
        positions = {"eyebrow": 92, "title": 172, "detail1": 258, "detail2": 300,
                     "detail3": 342, "rule": 403, "notice": 445, "notice2": 488}
        filters = [
            f"color=c=#111820:s={CANVAS_W}x{CANVAS_H}:r=20:d=360[base]",
            f"[0:v]scale={PANE_W}:{PANE_H}:force_original_aspect_ratio=decrease,setsar=1[phone]",
            f"[base][phone]overlay=x=460+({PANE_W}-w)/2:y=25+({PANE_H}-h)/2:shortest=1[v0]",
        ]
        last = "v0"
        fontfile = "C\\:/Windows/Fonts/segoeuib.ttf"
        regular_font = "C\\:/Windows/Fonts/segoeui.ttf"
        for index, (name, value, color, size, weight) in enumerate(lines):
            text_path = temp / f"{name}.txt"
            text_path.write_text(value, encoding="utf-8")
            path = ff_path(text_path)
            next_label = f"v{index + 1}"
            if name == "rule":
                filters.append(f"[{last}]drawbox=x=52:y=417:w=285:h=2:color=#34414F:t=fill[{next_label}]")
            else:
                font = fontfile if weight == "bold" else regular_font
                filters.append(
                    f"[{last}]drawtext=fontfile='{font}':textfile='{path}':x=52:y={positions[name]}:"
                    f"fontsize={size}:fontcolor={color}:expansion=none[{next_label}]"
                )
            last = next_label
        filters.append(f"[{last}]format=yuv420p[vout]")
        graph = temp / "filters.txt"
        graph.write_text(";\n".join(filters), encoding="utf-8")
        command = [str(ffmpeg), "-hide_banner", "-y", "-i", str(raw),
                   "-filter_complex", graph.read_text(encoding="utf-8"), "-map", "[vout]", "-an",
                   "-r", "20", "-c:v", "libx264", "-preset", "veryfast", "-crf", "27",
                   "-threads", "2", "-movflags", "+faststart", "-metadata", "title=SIGHT Android emulator walkthrough",
                   str(output)]
        subprocess.run(command, cwd=ROOT, check=True)

    raw_probe = probe(ffprobe, raw)
    out_probe = probe(ffprobe, output)
    apk_record = digest(args.apk.resolve()) if args.apk else None
    manifest = {
        "createdAtUtc": datetime.now(timezone.utc).isoformat(),
        "sourceCommit": git_commit(),
        "runtimeSourceCommit": runtime_source_commit(),
        "sourceRecording": {**digest(raw), **raw_probe},
        "renderedVideo": {**digest(output), **out_probe},
        "apk": apk_record,
        "render": {"canvas": f"{CANVAS_W}x{CANVAS_H}", "fps": 20, "crf": 27,
                   "audio": "omitted", "phoneVideo": "aspect ratio preserved; no generated or fabricated app screens"},
        "scope": ["Android emulator capture", "Simulated GPS", "Virtual camera", "Commands entered in app",
                  "Not a field mobility test", "Automatic road voice is disabled"],
        "chapters": chapters,
        "timestamps": timestamps,
    }
    manifest_path = args.manifest.resolve()
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Rendered {output} ({out_probe['durationSeconds']:.1f}s, {output.stat().st_size} bytes)")
    print(f"Provenance: {manifest_path}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raw", type=Path, default=DEFAULT_RAW, help="Actual adb screenrecord MP4")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--ffmpeg", type=Path, default=DEFAULT_FFMPEG)
    parser.add_argument("--ffprobe", type=Path, help="Defaults to ffprobe.exe beside ffmpeg")
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--apk", type=Path, help="APK used for the capture; hash is recorded")
    parser.add_argument("--chapters-json", type=Path, help="Optional JSON array of chapter metadata")
    parser.add_argument("--timestamps-json", type=Path, help="Optional timestamp notes JSON to retain in provenance")
    args = parser.parse_args()
    try:
        render(args)
    except subprocess.CalledProcessError as exc:
        print(f"Command failed with exit code {exc.returncode}: {exc.cmd}", file=sys.stderr)
        raise SystemExit(exc.returncode)


if __name__ == "__main__":
    main()
