# Road segmentation model provenance

The deployed 817,520-byte model is a corrected derivative of Intel Open Model Zoo's `road-segmentation-adas-0001`. Intel's [model manifest](https://github.com/openvinotoolkit/open_model_zoo/blob/master/models/intel/road-segmentation-adas-0001/model.yml) explicitly identifies Apache-2.0 licensing, copyright Intel Corporation2023–2024. [Model documentation](https://github.com/openvinotoolkit/open_model_zoo/tree/master/models/intel/road-segmentation-adas-0001) specifies four outputs: background, road, curb and marking. Its vendor-reported accuracy on its own dataset is not SIGHT's measured accuracy.

The starting TFLite conversion is preserved at [Autoware Foundation's pinned revision](https://huggingface.co/AutowareFoundation/yabloc_pose_initializer/tree/282a7fa0eb53ab5f8f0f179708ca57eacd25eaf1/saved_model). Conversion work from [PINTO model zoo](https://github.com/PINTO0309/PINTO_model_zoo) carries the MIT notice, copyright2024 Katsuya Hyodo, retained in [LICENSE-conversion-MIT.txt](LICENSE-conversion-MIT.txt). This conversion notice does not replace Intel's original-model terms. Application code and independently licensed photographs/annotations remain separate.

The unmodified conversion had five bilinear resize operators configured with `align_corners=false, half_pixel_centers=true`, unlike the official Intel graph. SIGHT changed exactly those five option pairs to `true,false`. All trained buffers are unchanged. No training, distillation or new dataset fitting was performed. [convert_road_model.py](../convert_road_model.py) reproduces the corrected bytes and asserts both the option count and unchanged buffers.

| Artifact | SHA256 |
|---|---|
| Original TFLite,815444 bytes | `9ac3a1423bc48e83808edc8273fe7a6e53d0bf0cabe2699d5ed1979b3c64f8a8` |
| Corrected TFLite,817520 bytes | `8cbfa699891f82f6edae8493b8a75e648ddbbe8725165a86c6fc4c1f78e6a5b4` |
| Official Intel FP32 XML | `228e4ef841a6cea468fc8cdf2c17b2cb27e7acc153ea81cf18773094043eadf9` |
| Official Intel FP32 weights | `e708aff5c4cd9813b575c72011f2cef9a4f5adb7aa382d81dc7adbb395dc9d8c` |
| Retained TensorFlow Lite schema | `da73b89f169b80d40b49e8131092c0d122d3306f24d2269a36c50b8c2d22991c` |

The exact retained [schema.fbs](schema.fbs) preserves its TensorFlow Authors copyright and Apache-2.0 header. Reproduction uses [FlatBuffers25.12.19](https://github.com/google/flatbuffers/releases/tag/v25.12.19); the script verifies its Windows archive/executable or requires that version through `--flatc`. Compiler binaries and cached reference artifacts are not required to clone/build Android and are not committed.

Android uses LiteRT1.4.2's CPU interpreter so the package retains x86/API30 support. The actual deployed preprocessing is an already oriented image stretched to896×512, raw BGR float values0–255, NHWC. The output is NHWC four-class softmax. Posterior scores are not calibrated probabilities of correct classification. Distinct4-connected regions stay separate; coverage is component pixels divided by the entire896×512 grid. Pixel cutoff0 was selected on the new selection split. No marking is re-labelled as a crosswalk.

The [independent study](../results/pedestrian-balanced/report.md) failed the full automatic road-speech false-event gate. Curbs/markings have no independent SIGHT accuracy labels. This network establishes neither sidewalks, pedestrian signal states, stairs, doors/entrances, physical depth, safe crossings nor passable space.
