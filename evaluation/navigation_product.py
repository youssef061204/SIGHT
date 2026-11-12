"""Collect bounded public routing evidence and construct labelled GNSS-policy replays.

No user's coordinates are used. Replays are constructed on real returned route geometry,
not physical recordings. The Android JVM test executes the production progress engine.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import shutil
import time
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_OUTPUT = ROOT / "evaluation/results/product-navigation"
PUBLIC_TRIPS = (
    ("toronto", "Union Station Toronto", "CN Tower Toronto"),
    ("ottawa", "Ottawa City Hall", "National Gallery of Canada Ottawa"),
    ("kingston", "Kingston City Hall Ontario", "Queen's University Kingston Ontario"),
)


def save_json(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")


def fetch(url):
    started = time.perf_counter()
    request = Request(url, headers={"User-Agent": "SIGHT-navigation-evaluation/1.0 (public-landmark requests)", "Accept": "application/json"})
    with urlopen(request, timeout=25) as response:
        raw = response.read(2_000_001)
        if len(raw) > 2_000_000:
            raise ValueError("Response exceeded 2 MB")
        result = json.loads(raw)
        code = response.status
    time.sleep(1.2)  # Respect the shared demo-server request policy even across providers.
    return result, {"url": url, "httpStatus": code, "requestAndPostThrottleMs": round((time.perf_counter() - started) * 1000, 2)}


def destination(feature):
    props = feature["properties"]
    coords = feature["geometry"]["coordinates"]
    label = ", ".join(dict.fromkeys(str(props[key]) for key in ("name", "street", "city", "country") if props.get(key)))
    return {"id": f"{props.get('osm_type')}:{props.get('osm_id')}", "label": label,
            "latitude": coords[1], "longitude": coords[0]}


def collect(out):
    results = []
    for slug, origin_query, destination_query in PUBLIC_TRIPS:
        entry = {"id": slug, "originQuery": origin_query, "destinationQuery": destination_query,
                 "selectionPolicy": "First geocoder result is used only for this fixed public-provider benchmark, never for user ambiguity resolution"}
        try:
            places = []
            for role, query in (("origin", origin_query), ("destination", destination_query)):
                body, metadata = fetch("https://photon.komoot.io/api/?" + urlencode({"q": query, "limit": 5, "lang": "en"}))
                path = out / f"{slug}-{role}-search.json"
                save_json(path, body)
                metadata["results"] = len(body["features"])
                metadata["responseFile"] = path.name
                entry[role + "Search"] = metadata
                if not body["features"]:
                    raise ValueError("No place results")
                places.append(destination(body["features"][0]))
            start, end = places
            coordinates = f"{start['longitude']:.7f},{start['latitude']:.7f};{end['longitude']:.7f},{end['latitude']:.7f}"
            url = "https://routing.openstreetmap.de/routed-foot/route/v1/foot/" + coordinates + "?overview=full&geometries=geojson&steps=true&alternatives=false"
            body, metadata = fetch(url)
            path = out / f"{slug}-route.json"
            save_json(path, body)
            metadata["providerCode"] = body.get("code")
            metadata["responseFile"] = path.name
            metadata["routes"] = len(body.get("routes", []))
            entry.update({"route": metadata, "origin": start, "destination": end,
                          "status": "returned_route" if body.get("code") == "Ok" and body.get("routes") else "no_route"})
        except Exception as error:
            entry.update({"status": "failed", "error": f"{type(error).__name__}: {error}"})
            time.sleep(1.2)
        results.append(entry)
    summary = {
        "schema": "sight.public-navigation-provider-sample", "recordedAtUtc": datetime.now(timezone.utc).isoformat(),
        "trips": results,
        "scope": "Small fixed public-landmark HTTP availability and response-parsing sample, not field walking, route accessibility, user destination accuracy or provider SLA",
        "attribution": "OpenStreetMap contributors; Photon / komoot; FOSSGIS OSRM foot",
        "timingScope": "requestAndPostThrottleMs includes the explicit1.2second provider-policy wait after the HTTP request; not HTTP-only latency",
    }
    save_json(out / "provider-sample.json", summary)
    return summary


def distance(a, b):
    lat1, lat2 = math.radians(a[1]), math.radians(b[1])
    dlat, dlon = lat2-lat1, math.radians(b[0]-a[0])
    h = math.sin(dlat/2)**2 + math.cos(lat1)*math.cos(lat2)*math.sin(dlon/2)**2
    return 2 * 6_371_008.8 * math.atan2(math.sqrt(h), math.sqrt(max(0, 1-h)))


def point_at(points, along):
    cumulative = 0.0
    for a, b in zip(points, points[1:]):
        length = distance(a, b)
        if length > 0 and cumulative + length >= along:
            fraction = (along-cumulative)/length
            return [a[0]+fraction*(b[0]-a[0]), a[1]+fraction*(b[1]-a[1])]
        cumulative += length
    return points[-1]


def replays(out):
    # Preserve this already-executed public route as an independent pinned scenario source.
    source = ROOT / "evaluation/results/v3-route-services/walking-route.json"
    raw = source.read_bytes()
    body = json.loads(raw)
    route = body["routes"][0]
    points = route["geometry"]["coordinates"]
    total = sum(distance(a,b) for a,b in zip(points,points[1:]))
    selected_destination = {"id": "public-cn-tower-coordinate", "label": "CN Tower public destination coordinate",
                            "latitude": 43.6426, "longitude": -79.3871}

    def frame(point, tick, accuracy=6.0, now_offset=0):
        return {"longitude": point[0], "latitude": point[1], "accuracyMeters": accuracy,
                "capturedElapsedMs": tick, "nowElapsedMs": tick+now_offset}

    regular = []
    drifted = []
    ticks = 1_000
    along = 0.0
    index = 0
    while along < total:
        point = point_at(points, along)
        regular.append(frame(point, ticks))
        drift = [point[0], point[1] + (8 if index%2==0 else -8)/111_195]
        drifted.append(frame(drift,ticks,12.0))
        along += 8
        ticks += 6_000
        index += 1
    regular.append(frame(points[-1],ticks))
    drifted.append(frame(points[-1],ticks,12.0))
    middle = point_at(points,total/2)
    far = [middle[0], middle[1]+.004]
    scenarios = [
        {"id":"mapped-walk", "purpose":"Constructed 8m/6s samples follow actual provider geometry", "expectNoOffRoute":True, "expectNoUncertainty":True, "frames":regular},
        {"id":"ordinary-drift", "purpose":"Same constructed walk with alternating ±8m latitude noise, declared12m accuracy", "expectNoOffRoute":True, "frames":drifted},
        {"id":"endpoint-teleport", "purpose":"A2s GPS jump from origin to endpoint cannot announce arrival", "expectLastState":"GPS_UNCERTAIN", "expectNoArrival":True,
         "frames":[frame(points[0],1_000),frame(points[-1],3_000)]},
        {"id":"inaccurate-endpoint", "purpose":"Poor GNSS accuracy cannot establish arrival", "expectLastState":"GPS_UNCERTAIN", "expectNoArrival":True,
         "frames":[frame(points[-1],1_000,200.0)]},
        {"id":"stale-endpoint", "purpose":"Expired fix cannot establish arrival", "expectLastState":"GPS_UNCERTAIN", "expectNoArrival":True,
         "frames":[frame(points[-1],1_000,6.0,20_000)]},
        {"id":"persistent-off-route", "purpose":"Three well-separated accurate fixes away from all route geometry establish disagreement", "expectLastState":"OFF_ROUTE", "expectNoArrival":True,
         "frames":[frame(far,1_000),frame(far,3_000),frame(far,5_000)]},
    ]
    manifest = {"schema":"sight.navigation-constructed-replay", "sourceFile":source.relative_to(ROOT).as_posix(),
                "sourceSha256":hashlib.sha256(raw).hexdigest(), "destination":selected_destination,
                "geometryLengthMeters":total, "providerRouteLengthMeters":route["distance"],
                "scope":"Constructed location traces over actual public-provider geometry, not recorded human/GNSS trajectories or measured navigation outcomes",
                "scenarios":scenarios}
    save_json(out / "constructed-replays.json",manifest)
    print(f"Wrote {len(scenarios)} constructed scenarios, {sum(len(s['frames']) for s in scenarios)} fix samples")


def report(out):
    provider = json.loads((out / "provider-sample.json").read_text(encoding="utf-8-sig"))
    generated = ROOT / "app/build/reports/product-navigation/evaluation.json"
    evaluated = json.loads(generated.read_text(encoding="utf-8-sig"))
    for name, recorded in evaluated["implementationSha256"].items():
        actual = hashlib.sha256((ROOT / "app/src/main/java/org/sight/app/navigation" / name).read_bytes()).hexdigest()
        if recorded != actual:
            raise SystemExit("Production navigation source changed after JVM evaluation; rerun app tests before recording results")
    if evaluated["failures"]:
        raise SystemExit("Production-engine replay has failures; preserve and investigate its report")
    shutil.copyfile(generated, out / "production-engine-replay.json")
    mapped_path = ROOT / "app/build/reports/product-navigation/valhalla-parser.json"
    mapped = {"status": "not_run", "scope": "No native-format mapped-details parser test result is available"}
    if mapped_path.exists():
        mapped = json.loads(mapped_path.read_text(encoding="utf-8-sig"))
        implementation = ROOT / "app/src/main/java/org/sight/app/navigation/ValhallaWalkingRouteProvider.kt"
        response = out / "valhalla-investigation/toronto-blind-native.json"
        if mapped["implementationSha256"] != hashlib.sha256(implementation.read_bytes()).hexdigest() or \
                mapped["responseSha256"] != hashlib.sha256(response.read_bytes()).hexdigest():
            raise SystemExit("Mapped-details parser or saved response changed; rerun navigation JVM tests")
        shutil.copyfile(mapped_path, out / "valhalla-parser.json")
        mapped = {"status": "passed", **mapped}
    cases = evaluated["scenarios"]
    trips = provider["trips"]
    searches = [trip[key] for trip in trips for key in ("originSearch", "destinationSearch") if key in trip]
    summary = {
        "schema": "sight.navigation-product-evidence-summary",
        "providerSample": {"trips": len(trips), "returnedRoutes": sum(t["status"]=="returned_route" for t in trips),
                           "searches": len(searches), "nonemptySearches": sum(t["results"]>0 for t in searches)},
        "productionEngineReplay": {"scenarios": len(cases), "fixSamples": sum(c["samples"] for c in cases),
                                   "automaticArrivalDeclarations": sum(c["automaticArrivalDeclarations"] for c in cases),
                                   "unsupportedClearanceClaims": sum(c["unsupportedClearanceClaims"] for c in cases),
                                   "failures": evaluated["failures"]},
        "mappedDetailsParser": mapped,
        "limits": [provider["scope"], evaluated["scope"], evaluated["boundaries"]],
    }
    save_json(out / "summary.json", summary)
    print(json.dumps(summary, indent=2))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode",choices=("collect","replays","all","report"))
    parser.add_argument("--output",type=Path,default=DEFAULT_OUTPUT)
    args=parser.parse_args()
    args.output.mkdir(parents=True,exist_ok=True)
    if args.mode in ("collect","all"):
        if (args.output/"provider-sample.json").exists():
            raise SystemExit("Refusing to overwrite a recorded provider sample; choose a fresh --output")
        print(json.dumps(collect(args.output),indent=2))
    if args.mode in ("replays","all"):
        replays(args.output)
    if args.mode == "report":
        report(args.output)


if __name__ == "__main__":
    main()
