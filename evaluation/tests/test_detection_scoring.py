"""Detection metric oracles; optional native dependencies are local-eval only."""
import importlib.util
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
HAS_RUNTIME = all(importlib.util.find_spec(name) is not None for name in ("mediapipe", "pycocotools", "numpy"))


@unittest.skipUnless(HAS_RUNTIME, "Install evaluation/requirements.txt for official COCO integration metric checks")
class DetectionScoringTest(unittest.TestCase):
    def make_dataset(self, crowd=False):
        from pycocotools.coco import COCO
        coco = COCO()
        coco.dataset = {"info": {}, "images": [{"id": 1, "width": 100, "height": 100}],
                        "categories": [{"id": 1, "name": "person"}], "annotations": [
                            {"id": 1, "image_id": 1, "category_id": 1, "bbox": [0, 0, 10, 10], "area": 100, "iscrowd": 0},
                            {"id": 2, "image_id": 1, "category_id": 1, "bbox": [50, 50, 10, 10], "area": 100, "iscrowd": int(crowd)}]}
        coco.createIndex()
        return coco

    def test_duplicate_is_false_positive_and_missing_object_false_negative(self):
        from benchmark import score
        predictions = [{"image_id": 1, "category_id": 1, "bbox": [0, 0, 10, 10], "score": .9},
                       {"image_id": 1, "category_id": 1, "bbox": [0, 0, 10, 10], "score": .8}]
        result = score(self.make_dataset(), predictions, .5)
        self.assertEqual((result["tp"], result["fp"], result["fn"]), (1, 1, 1))
        self.assertEqual(result["precision_at_operating_threshold"], .5)
        self.assertEqual(result["recall_at_operating_threshold"], .5)

    def test_crowd_matches_are_ignored_without_hiding_regular_misses(self):
        from benchmark import score
        predictions = [{"image_id": 1, "category_id": 1, "bbox": [50, 50, 5, 5], "score": .9}]
        result = score(self.make_dataset(crowd=True), predictions, .5)
        self.assertEqual((result["tp"], result["fp"], result["fn"]), (0, 0, 1))
        self.assertIsNone(result["precision_at_operating_threshold"])
        self.assertEqual(result["recall_at_operating_threshold"], 0)


if __name__ == "__main__":
    unittest.main()
