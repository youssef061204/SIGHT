"""Constructed unit data verifies that regression gates cannot hide changed inputs."""
import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from compare_results import compare


def fixture():
    return {"dataset_manifest_sha256": "unit-fixture", "config": {"score_floor": .05, "operating_score_threshold": .5, "max_results": 100, "iou_threshold": .5},
            "models": {"model": {"variants": {"clean": {"subset_AP50": .4, "recall_at_operating_threshold": .3}}}}}


class RegressionTest(unittest.TestCase):
    def test_refuses_dataset_or_threshold_changes(self):
        base = fixture()
        for mutate in (lambda value: value.update(dataset_manifest_sha256="another-dataset"),
                       lambda value: value["config"].update(operating_score_threshold=.2)):
            changed = copy.deepcopy(base)
            mutate(changed)
            with self.assertRaises(ValueError):
                compare(base, changed)

    def test_one_material_regression_fails_even_if_other_metric_improves(self):
        base, changed = fixture(), fixture()
        changed["models"]["model"]["variants"]["clean"].update(subset_AP50=.35, recall_at_operating_threshold=.6)
        result = compare(base, changed)
        self.assertFalse(result["passed"])
        self.assertFalse(result["comparisons"][0]["passed"])
        self.assertTrue(result["comparisons"][1]["passed"])

    def test_missing_conditions_cannot_silently_pass(self):
        changed = fixture()
        changed["models"]["model"]["variants"] = {}
        with self.assertRaises(ValueError):
            compare(fixture(), changed)


if __name__ == "__main__":
    unittest.main()
