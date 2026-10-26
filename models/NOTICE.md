# Model provenance

Google distributes the three pinned, metadata-bearing TFLite exports through the official [MediaPipe object detector guide](https://developers.google.com/edge/mediapipe/solutions/vision/object_detector). Exact versioned URLs, byte sizes and SHA256 hashes are in manifest.json; downloads never use `latest`. SIGHT3 retains EfficientDet-Lite0 int8 for FAST updates and adds EfficientDet-Lite2 int8 for selective INSPECT updates. SSD MobileNetV2 remains an evaluation comparator, not a bundled runtime profile.

EfficientDet's upstream implementation and released model family are maintained in [google/automl/efficientdet](https://github.com/google/automl/tree/master/efficientdet), under Apache 2.0. The included LICENSE-Apache-2.0.txt is from that upstream repository. TensorFlow's SSD MobileNet implementation is in [tensorflow/models](https://github.com/tensorflow/models), under Apache 2.0. MediaPipe source is [google-ai-edge/mediapipe](https://github.com/google-ai-edge/mediapipe), under Apache 2.0. Copyright belongs to the respective upstream contributors.

The TFLite exports embed labels and metadata, but no separate weight-license document. The official guide and upstream license provenance are retained here; confirm distribution terms for these precise exported artifacts as part of any app-store release review. No claim is made that the guide's prose license determines model-weight terms.

Training data: COCO. Detector classes are the 80 COCO categories. They do not cover doors, exits, elevators, keys, stairs or arbitrary open-vocabulary objects. Model confidence is not a calibrated probability, and apparent box size is not metric distance.

The50/50/80photo selection/calibration/test separation, exact inference outputs, model comparison and chosen operating threshold are documented under `evaluation/results/v3-detection/`. Those public COCO photographs are evaluation artifacts only, not production model assets. This subset cannot establish first-person assistive-use reliability or absence of model-training contamination.

Alternative model research is in `evaluation/results/v3-detection/model-research.md`. No Ultralytics, YOLO-World or other copyleft model/code is bundled. YOLOX is a credible Apache 2.0/ONNX/ncnn future comparator; it has not been integrated or benchmarked in this build.
