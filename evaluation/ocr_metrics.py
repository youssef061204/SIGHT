"""Score imported OCR JSONL references/hypotheses without inventing recognition."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import re
import unicodedata


def distance(reference, hypothesis):
    previous = list(range(len(hypothesis) + 1))
    for index, expected in enumerate(reference, start=1):
        current = [index]
        for position, actual in enumerate(hypothesis, start=1):
            current.append(min(previous[position] + 1, current[-1] + 1, previous[position - 1] + (expected != actual)))
        previous = current
    return previous[-1]


def normalize(value):
    return re.sub(r"\s+", " ", unicodedata.normalize("NFKC", value).casefold()).strip()


def evaluate(rows):
    details = []
    totals = {"character_errors": 0, "reference_characters": 0, "word_errors": 0, "reference_words": 0}
    for row in rows:
        reference, hypothesis = normalize(row["reference"]), normalize(row["hypothesis"])
        counts = {"character_errors": distance(reference, hypothesis), "reference_characters": len(reference),
                  "word_errors": distance(reference.split(), hypothesis.split()), "reference_words": len(reference.split())}
        for key, value in counts.items():
            totals[key] += value
        details.append({**row, "normalized_reference": reference, "normalized_hypothesis": hypothesis, **counts})
    return {"count": len(details), **totals, "cer": totals["character_errors"] / totals["reference_characters"] if totals["reference_characters"] else None,
            "wer": totals["word_errors"] / totals["reference_words"] if totals["reference_words"] else None,
            "normalization": "Unicode NFKC, casefold, collapse whitespace; punctuation retained. Edit distance / reference length; insertion errors can exceed 1. Null when no reference units.", "rows": details}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", type=Path, default=Path("evaluation/results/ocr-metrics.json"))
    args = parser.parse_args()
    rows = [json.loads(line) for line in args.input.read_text(encoding="utf-8-sig").splitlines() if line.strip()]
    result = evaluate(rows)
    result["synthetic_only"] = evaluate([row for row in rows if row.get("provenance", "").startswith("SYNTHETIC:")])
    result["real_only"] = evaluate([row for row in rows if row.get("provenance", "").startswith("REAL ")])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key not in ("rows", "synthetic_only", "real_only")}, indent=2))


if __name__ == "__main__":
    main()
