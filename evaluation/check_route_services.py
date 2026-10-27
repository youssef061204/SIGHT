"""Two public-place HTTP checks. No device location, camera data or credentials are used."""
from datetime import datetime, timezone
import json
from pathlib import Path
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "evaluation/results/v3-route-services"
REQUESTS = {
    "search": "https://photon.komoot.io/api/?q=Union%20Station%20Toronto&limit=5&lang=en",
    "walking-route": "https://routing.openstreetmap.de/routed-foot/route/v1/foot/-79.3803000,43.6452000;-79.3871000,43.6426000?overview=full&geometries=geojson&steps=true&alternatives=false",
}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    records = {}
    for name, url in REQUESTS.items():
        record = {"url": url, "input": "Fixed public Toronto landmarks; not a user's GPS location"}
        try:
            request = Request(url, headers={"User-Agent": "SIGHT/3.0 engineering-route-check", "Accept": "application/json"})
            with urlopen(request, timeout=20) as response:
                raw = response.read(2_000_001)
                if len(raw) > 2_000_000:
                    raise ValueError("Response exceeded the application byte limit")
                record["httpStatus"] = response.status
            body = json.loads(raw)
            (OUT / (name + ".json")).write_bytes(raw)
            if name == "search":
                record["results"] = len(body.get("features", []))
                record["status"] = "returned_results" if record["results"] else "empty_results"
            else:
                record["providerCode"] = body.get("code")
                routes = body.get("routes", [])
                record["routes"] = len(routes)
                record["steps"] = sum(len(leg.get("steps", [])) for route in routes for leg in route.get("legs", []))
                record["status"] = "returned_route" if record["providerCode"] == "Ok" and record["steps"] else "no_route"
        except Exception as error:
            record.update(status="unavailable", error=type(error).__name__ + ": " + str(error))
        records[name] = record
    value = {"recordedAtUtc": datetime.now(timezone.utc).isoformat(), "requests": records,
             "boundary": "Desktop HTTP availability only. Not an Android, GPS, accessibility or physical safety validation.",
             "attribution": "OpenStreetMap contributors; Photon/komoot search; FOSSGIS OSRM foot routing"}
    (OUT / "summary.json").write_text(json.dumps(value, indent=2), encoding="utf-8")
    print(json.dumps(value, indent=2))


if __name__ == "__main__":
    main()
