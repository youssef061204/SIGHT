# Why the original detector missed objects

The original 16 COCO photos were rerun with MediaPipe 1.1.1 before any new model-selection decision. All 6 raw prediction files were byte-identical to the historical v2 artifacts; 30 AP/count checks matched. These legacy photos are diagnostic only and excluded from the new 180-photo corpus, together with their recovered photographers. [Reproduction evidence](../v3-baseline-reproduction/reproduction-check.json).

At confidence .50, Lite0 produced 39 true positives, 13 false positives and 162 false negatives (precision .750, recall .194). The sample contains 82 small, 90 medium and 29 large nonignored objects. It detected none of the small objects, 23 medium and 16 large. This is a severe small-object/resolution weakness, plus a conservative confidence cutoff, rather than evidence of a single broken class map.

| Confidence | TP | FP | Recall | Precision |
| --- | ---: | ---: | ---: | ---: |
| .25 | 73 | 53 | .363 | .579 |
| .30 | 62 | 41 | .308 | .602 |
| .40 | 52 | 19 | .259 | .732 |
| .50 | 39 | 13 | .194 | .750 |

Lowering the cutoff can recover objects while adding many false positives. These values diagnose the existing policy; they did not select the upgraded threshold, which was chosen only on the separate 50-photo calibration split.

| Controlled diagnostic | AP50 | Interpretation |
| --- | ---: | --- |
| Unchanged native task RGB input | .3614 | Exact reproduced baseline |
| Clip output boxes to image, like Android normalization | .3614 | Coordinate clipping did not explain this sample's misses |
| Extra same-class post-hoc NMS at IoU .30/.50/.70 | .3614 each | No gain; cannot recover proposals already suppressed in the model |
| Manual 320 square stretch, then inverse box scaling | .3428 | Additional resampling changed evidence slightly; native task resize remains preferable |
| Manual black letterbox 320, invert padding/scale | .3048 | Letterboxing did not repair recall on these photos |
| Manual gray 114 letterbox 320, invert padding/scale | .2859 | Another regression; do not add generic YOLO preprocessing to a different export |
| Incorrect BGR channels fed as RGB | .3594 | Aggregate score changed little here; this does not prove channel order is unimportant |
| Wrong 90 degree content orientation, boxes correctly reprojected | .0857 | Orientation matters greatly; box transforms alone cannot fix semantically rotated input |
| Official Lite0 float32 instead of int8 | .3722 | Small AP gain, 39 TP/11 FP at .50; larger 13.84 MB export did not resolve missing objects |

All emitted labels map to real COCO categories; boxes had positive width/height. The greatest count above .50 was 14 per photo, so Android's prior maxResults 20 did not cap away detections in this sample. The scorer uses official COCO crowd matching and all category annotations, not just the visually obvious primary subject. There were 201 nonignored objects, 202 including a crowd region. Dense crowds, clutter and tiny objects make this corpus harder than a single-object demo. Of the 13 unmatched predictions, six overlap a same-class annotation at IoU .1 to .5 (localization mismatch); seven have less overlap. A counted false positive can depict a real object with an insufficiently aligned box. Official annotations are accepted as ground truth; they were not independently reannotated.

The float32 diagnostic uses Google's exact version 1 artifact, SHA256 40338edf5ec70d43e318b0a716a84d4564cd1802759a7a07170c7e43796dbf58. Float and int8 exports can differ beyond quantization, so this is an export comparison, not a causal quantization-only experiment. Python's public detector options expose no NMS-threshold control in the pinned runtime; post-hoc NMS cannot test suppression of already discarded proposals.

Examples below use only photographs with recovered creators. Green is a true positive, red a false positive, amber an annotated object missed at .50. Raw boxes, category counts, exact credits and all diagnostic metrics are in [summary.json](summary.json). These show known failure cases rather than an independent showcase sample:

- [waiting on the bench, 301135](example_000000301135.jpg): 7 TP, 0 FP, 16 FN; missed small people, cars, bags and a bicycle. Photograph by [zoetnet](https://www.flickr.com/photos/zoetnet/4856973633/), [CC BY 2.0](https://creativecommons.org/licenses/by/2.0/).
- [Glazed Donuts at Mr. Donuts & Kolaches, 238866](example_000000238866.jpg): 7 TP, 7 FP, 5 FN; the dense arrangement produces false detections as well as misses. Photograph by [Steve Snodgrass](https://www.flickr.com/photos/stevensnodgrass/6610035555/), [CC BY 2.0](https://creativecommons.org/licenses/by/2.0/).
- [Carrefour - St. Andre des Arts, 184324](example_000000184324.jpg): 2 TP, 1 FP, 29 FN; many small people/vehicles/bags remain undetected. Photograph by [Serge Melki](https://www.flickr.com/photos/sergemelki/3364389746/), [CC BY 2.0](https://creativecommons.org/licenses/by/2.0/).

Doors, stairs, curbs, crosswalks, elevators and keys are absent from the 80 deployed categories. COCO does not provide suitable positives/negatives for those classes here, so neither recall nor absence claims are justified.

This RGB still-photo diagnosis does not validate Android YUV/RGBA conversion, camera rotation/mirroring, sensor clocks, lifecycle or moving-camera tracking. Those are separate automated pixel/native tests. Google documents native preprocessing and box reprojection in the [ObjectDetector graph](https://github.com/google-ai-edge/mediapipe/blob/master/mediapipe/tasks/cc/vision/object_detector/object_detector_graph.cc) and [image preprocessing graph](https://github.com/google-ai-edge/mediapipe/blob/master/mediapipe/tasks/cc/components/processors/image_preprocessing_graph.cc). SIGHT preserves task-native resizing while making external format/orientation operations independently testable.
