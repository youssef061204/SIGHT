import sys
from pathlib import Path
import unittest
import numpy as np

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from pedestrian_balanced_evaluation import event_metrics
from pedestrian_evaluation import binary_counts


class PedestrianScoringTest(unittest.TestCase):
    def test_ignored_pixels_do_not_turn_void_or_a_marking_into_road_truth(self):
        truth=np.array([1,0,255,2],dtype=np.uint8)
        prediction=np.array([1,3,1,1],dtype=np.uint8)
        self.assertEqual({'tp':1,'fp':1,'fn':0,'tn':1},binary_counts(prediction==1,truth==1,truth!=255))

    def test_road_presence_requires_road_class_and_both_calibrated_component_criteria(self):
        records=[
            {'id':1,'roadTruthPresent':True,'regions':[{'category':1,'coverage':.03,'meanPosterior':.9}]},
            {'id':2,'roadTruthPresent':False,'regions':[{'category':3,'coverage':.9,'meanPosterior':.99}]},
            {'id':3,'roadTruthPresent':True,'regions':[{'category':1,'coverage':.019,'meanPosterior':.99}]},
            {'id':4,'roadTruthPresent':False,'regions':[{'category':1,'coverage':.4,'meanPosterior':.749}]},
        ]
        measured=event_metrics(records,.02,.75)
        self.assertEqual((1,0,1,2),(measured['tp'],measured['fp'],measured['fn'],measured['tn']))
        self.assertEqual(.5,measured['recall'])
        self.assertEqual(1.0,measured['precision'])

if __name__=='__main__': unittest.main()
