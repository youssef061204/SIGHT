"""Query OSV for pinned direct Maven and evaluation Python dependencies (no credentials)."""
import datetime
import argparse
import json
from pathlib import Path
import re
from urllib.request import Request, urlopen


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default="evaluation/results/dependency-audit.json")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    packages = set()
    for relative in ("app/build.gradle.kts", "core/build.gradle.kts", "perception/build.gradle.kts", "integrations/meta/adapter/build.gradle.kts"):
        text = (root / relative).read_text(encoding="utf-8")
        packages.update(("Maven", f"{group}:{artifact}", version) for group, artifact, version in
                        re.findall(r'"([\w.\-]+):([\w.\-]+):([\w.\-]+)"', text))
    for line in (root / "evaluation/requirements-lock.txt").read_text().splitlines():
        if "==" in line and not line.startswith("#"):
            name, version = line.split("==", 1)
            packages.add(("PyPI", name, version))
    queries = [{"package": {"ecosystem": ecosystem, "name": name}, "version": version}
               for ecosystem, name, version in sorted(packages)]
    request = Request("https://api.osv.dev/v1/querybatch", data=json.dumps({"queries": queries}).encode(),
                      headers={"Content-Type": "application/json", "User-Agent": "SIGHT-dependency-audit/1"})
    with urlopen(request, timeout=60) as response:
        results = json.load(response)["results"]
    records = [dict(query=query, vulnerabilities=result.get("vulns", [])) for query, result in zip(queries, results)]
    output = {"schema": "sight.osv-direct-audit.v1", "executedAtUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "scope": "Pinned direct Maven coordinates and full evaluation Python lock; excludes resolved Maven transitives, native binary contents, OS and build plugins",
              "source": "https://osv.dev/", "packages": records}
    target = root / args.output
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")
    affected = [record for record in records if record["vulnerabilities"]]
    print(json.dumps({"queriedPackages": len(queries), "affectedPackages": len(affected),
                      "findings": [{"package": record["query"], "advisoryCount": len(record["vulnerabilities"]), "ids": [v["id"] for v in record["vulnerabilities"]][:5]}
                                   for record in affected], "artifact": str(target)}, indent=2))


if __name__ == "__main__":
    main()
