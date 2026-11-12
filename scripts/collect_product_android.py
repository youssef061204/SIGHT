"""Collect current product-test evidence from an emulator with adb run-as."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent.parent
PRODUCTS = {
    "android35": ("org.sight.app", ("native-road-pipeline-proof.json", "native-voice-recognition-proof.json", "integrated-navigation-proof.json", "inspection-runtime-v3.json", "camera-runtime-v3.json")),
    "android30": ("org.sight.app", ("native-road-pipeline-proof.json", "native-voice-recognition-proof.json", "integrated-navigation-proof.json", "inspection-runtime-v3.json", "camera-runtime-v3.json")),
    "google-speech": ("org.sight.app", ("sight-speech-proof.json", "speech-queue-native-v3.json")),
    "meta": ("org.sight.meta.test", ("meta-color-chart-proof.json", "meta-real-pipeline-proof.json")),
}

def decode(raw):
    return raw.decode("utf-16" if raw.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8-sig", errors="replace").replace("\r\n", "\n")

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--serial", required=True)
    p.add_argument("--phase", required=True, choices=PRODUCTS)
    p.add_argument("--output", help="Output directory relative to repository; defaults to evaluation/results/product-<phase>")
    p.add_argument("--adb", default=str(ROOT / ".tools/android-sdk/platform-tools/adb.exe"))
    p.add_argument("--suite-file", help="Optional local instrumentation output to copy into the collection")
    p.add_argument("--clear-existing", action="store_true", help="Before the run, delete only this phase's known proof files from app cache to prevent stale-proof reuse")
    args = p.parse_args()
    package, names = PRODUCTS[args.phase]
    collect_names = list(names) + (["sight-speech-proof.wav"] if args.phase == "google-speech" else [])
    out = (ROOT / args.output) if args.output else ROOT / "evaluation/results" / ("product-" + args.phase)
    out.mkdir(parents=True, exist_ok=True)
    def adb(*cmd):
        return subprocess.run([args.adb, "-s", args.serial, *cmd], capture_output=True, timeout=45)
    if args.clear_existing:
        for name in collect_names:
            result = adb("shell", "run-as", package, "rm", "-f", "cache/" + name)
            if result.returncode:
                raise SystemExit("Could not clear prior proof " + name + "; aborting to avoid stale evidence")
        print(json.dumps({"phase": args.phase, "status": "prior_proofs_cleared", "proofCount": len(names)}))
        return
    suite_raw = None
    if args.suite_file:
        suite_src = Path(args.suite_file)
        if not suite_src.is_absolute(): suite_src = ROOT / suite_src
        if suite_src.is_file(): suite_raw = decode(suite_src.read_bytes())
    proofs = {}
    for name in collect_names:
        (out / name).unlink(missing_ok=True)
    for name in ("instrumentation-suite.txt", "app-suite.txt", "meta-suite.txt", "speech-suite.txt", "suite.txt"):
        (out / name).unlink(missing_ok=True)
    for name in collect_names:
        result = adb("exec-out", "run-as", package, "cat", "cache/" + name)
        if result.returncode:
            proofs[name] = {"status": "missing", "detail": "adb run-as could not read proof"}
            continue
        try:
            if name.endswith(".wav"):
                if len(result.stdout) > 44 and result.stdout[:4] == b"RIFF" and result.stdout[8:12] == b"WAVE":
                    (out / name).write_bytes(result.stdout)
                    proofs[name] = {"status": "copied", "validWave": True}
                else:
                    proofs[name] = {"status": "missing_or_invalid", "detail": "valid RIFF/WAVE header not available"}
                continue
            parsed = json.loads(result.stdout.decode("utf-8-sig"))
        except (ValueError, UnicodeError):
            proofs[name] = {"status": "invalid", "detail": "proof was not valid UTF-8 JSON"}
            continue
        (out / name).write_bytes(result.stdout)
        proofs[name] = {"status": "copied", "validJson": True}
    props = {}
    for prop in ("ro.build.fingerprint", "ro.product.cpu.abilist", "ro.build.version.sdk", "ro.hardware", "sys.boot_completed"):
        r = adb("shell", "getprop", prop)
        props[prop] = decode(r.stdout).strip() if r.returncode == 0 else "unavailable"
    for label, cmd in (("display-size", ("wm", "size")), ("display-density", ("wm", "density"))):
        r = adb("shell", *cmd)
        props[label] = decode(r.stdout).strip() if r.returncode == 0 else "unavailable"
    if suite_raw is not None:
        (out / "instrumentation-suite.txt").write_text(suite_raw, encoding="utf-8", newline="\n")
    value = {"collectedAtUtc": datetime.now(timezone.utc).isoformat(), "phase": args.phase,
             "serial": args.serial, "package": package, "properties": props, "proofs": proofs,
             "boundary": "Emulator instrumentation evidence. Proof JSON is copied byte-for-byte; missing or invalid proofs are never treated as passes."}
    (out / "device.json").write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(value, indent=2))

if __name__ == "__main__": main()
