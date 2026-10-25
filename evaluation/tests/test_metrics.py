"""Independent metric oracles; CI uses only the Python standard library."""
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ocr_metrics import distance, evaluate, normalize


class MetricsTest(unittest.TestCase):
    def test_known_edit_distance_and_failed_recognition(self):
        self.assertEqual(distance("kitten", "sitting"), 3)
        result = evaluate([{"reference": "POLICE", "hypothesis": ""}])
        self.assertEqual(result["cer"], 1)
        self.assertEqual(result["wer"], 1)

    def test_insertions_can_exceed_reference_length(self):
        self.assertEqual(evaluate([{"reference": "a", "hypothesis": "abcdef"}])["cer"], 5)

    def test_empty_references_are_not_perfect_accuracy(self):
        result = evaluate([{"reference": "", "hypothesis": "unexpected"}])
        self.assertIsNone(result["cer"])
        self.assertEqual(result["word_errors"], 1)

    def test_normalization_retains_punctuation(self):
        self.assertEqual(normalize("  EXIT\nＡ  "), "exit a")
        self.assertEqual(normalize("$4.50"), "$4.50")


if __name__ == "__main__":
    unittest.main()
