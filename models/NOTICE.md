# Model provenance

Google distributes both pinned, metadata-bearing TFLite exports through the official [MediaPipe object detector guide](https://developers.google.com/edge/mediapipe/solutions/vision/object_detector). Exact versioned URLs, byte sizes and SHA256 hashes are in manifest.json; downloads never use `latest`.

EfficientDet's upstream implementation and released model family are maintained in [google/automl/efficientdet](https://github.com/google/automl/tree/master/efficientdet), under Apache 2.0. The included LICENSE-Apache-2.0.txt is from that upstream repository. TensorFlow's SSD MobileNet implementation is in [tensorflow/models](https://github.com/tensorflow/models), under Apache 2.0. MediaPipe source is [google-ai-edge/mediapipe](https://github.com/google-ai-edge/mediapipe), under Apache 2.0. Copyright belongs to the respective upstream contributors.

The TFLite exports embed labels and metadata, but no separate weight-license document. The official guide and upstream license provenance are retained here; confirm distribution terms for these precise exported artifacts as part of any app-store release review. No claim is made that the guide's prose license determines model-weight terms.

Training data: COCO. Detector classes are the 80 COCO categories. They do not cover doors, exits, elevators, keys, stairs or arbitrary open-vocabulary objects. Model confidence is not a calibrated probability, and apparent box size is not metric distance.
