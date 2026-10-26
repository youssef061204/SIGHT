"""Guard the experiment's scene separation, deployment cap, and geometry probes."""
import importlib.util
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "evaluation"))
HAS_RUNTIME = all(importlib.util.find_spec(name) is not None for name in ("mediapipe", "numpy", "pycocotools"))


class V3CorpusTest(unittest.TestCase):
    @unittest.skipUnless((ROOT / "evaluation/data-v3/manifest.json").exists(), "Run prepare_v3_corpus.py")
    def test_creators_and_near_duplicate_groups_never_cross_splits_or_old_ids(self):
        data = json.loads((ROOT / "evaluation/data-v3/manifest.json").read_text())
        old = json.loads((ROOT / "evaluation/data/manifest.json").read_text())
        old_ids = {image["id"] for image in old["images"]}
        creators, groups = {}, {}
        for image in data["images"]:
            self.assertNotIn(image["id"], old_ids)
            self.assertEqual(image["attribution"]["status"], "resolved")
            self.assertIn(image["attribution"]["license"], ("CC BY 2.0", "CC BY 4.0"))
            creators.setdefault(image["attribution"]["author_url"], set()).add(image["split"])
            groups.setdefault(image["scene_group"], set()).add(image["split"])
        self.assertTrue(all(len(splits) == 1 for splits in creators.values()))
        self.assertTrue(all(len(splits) == 1 for splits in groups.values()))
        self.assertEqual(len({image["id"] for image in data["images"]}), 180)
        for index, image in enumerate(data["images"]):
            for other in data["images"][:index]:
                if (int(image["dhash64"], 16) ^ int(other["dhash64"], 16)).bit_count() <= 6:
                    self.assertEqual(image["split"], other["split"])


@unittest.skipUnless(HAS_RUNTIME, "Install pinned evaluation requirements")
class V3ScoringTest(unittest.TestCase):
    def test_deployment_cap_is_per_image_across_categories_and_preserves_score_order(self):
        from v3_detection import cap_predictions
        predictions = [dict(image_id=1, category_id=1, score=.6), dict(image_id=2, category_id=1, score=.9),
                       dict(image_id=1, category_id=2, score=.95), dict(image_id=1, category_id=3, score=.8),
                       dict(image_id=2, category_id=2, score=.2)]
        self.assertEqual(cap_predictions(predictions, .5, 2), [predictions[2], predictions[3], predictions[1]])

    def test_letterbox_maps_output_coordinates_back_to_original_nonsquare_photo(self):
        import numpy as np
        from diagnose_v3_baseline import preprocess
        rgb = np.full((100, 200, 3), 255, dtype=np.uint8)
        boxed, project = preprocess(rgb, "manual_letterbox_0")
        self.assertEqual(boxed.shape, (320, 320, 3))
        self.assertEqual(int(boxed[0, 0, 0]), 0)
        self.assertEqual(int(boxed[80, 0, 0]), 255)
        self.assertEqual(project([0, 80, 320, 160]), [0, 0, 200, 100])


if __name__ == "__main__":
    unittest.main()
