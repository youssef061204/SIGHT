"""Copy genuine debug-package JSON proofs without PowerShell binary redirection."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--adb", default=str(ROOT / ".tools/android-sdk/platform-tools/adb.exe"))
    parser.add_argument("--meta", action="store_true")
    args = parser.parse_args()
    out = ROOT / args.output
    out.mkdir(parents=True, exist_ok=True)
    package = "org.sight.meta.test" if args.meta else "org.sight.app"
    def adb(*command):
        return subprocess.run([args.adb, "-s", args.serial, *command], capture_output=True, timeout=30)
    names = ("meta-color-chart-proof.json", "meta-real-pipeline-proof.json") if args.meta else (
        "inspection-runtime-v3.json", "camera-runtime-v3.json", "sight-speech-proof.json", "speech-queue-native-v3.json")
    proofs = {}
    for name in names:
        result = adb("exec-out", "run-as", package, "cat", "cache/" + name)
        if result.returncode == 0:
            try:
                json.loads(result.stdout)
            except (ValueError, UnicodeError):
                proofs[name] = "invalid JSON; not accepted as evidence"
            else:
                (out / name).write_bytes(result.stdout)
                proofs[name] = "copied"
        else:
            proofs[name] = "not available; no capability pass inferred"
    properties = {}
    for prop in ("ro.build.fingerprint", "ro.product.cpu.abilist", "ro.build.version.sdk", "ro.hardware", "sys.boot_completed", "debug.hwui.drawing_enabled"):
        result = adb("shell", "getprop", prop)
        properties[prop] = result.stdout.decode(errors="replace").strip() if result.returncode == 0 else "unavailable"
    for name, command in (("display-size", ("wm", "size")), ("display-density", ("wm", "density"))):
        properties[name] = adb("shell", *command).stdout.decode(errors="replace").strip()
    metadata = {"collectedAtUtc": datetime.now(timezone.utc).isoformat(), "serial": args.serial,
                "package": package, "properties": properties, "proofs": proofs,
                "boundary": "Emulated Android; JSON proofs carry their own measurement scope. Missing artifacts never establish a pass."}
    (out / ("meta-device.json" if args.meta else "device.json")).write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    print(json.dumps(metadata, indent=2))


if __name__ == "__main__":
    main()
