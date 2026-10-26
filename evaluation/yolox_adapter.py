"""Small ONNX YOLOX-Tiny adapter, independently checked against pinned upstream.

Math follows Apache2 YOLOX preprocessing/demo decode/NMS. The unmodified
reference source, copyright notices and license are in yolox-reference/.
"""
from __future__ import annotations
import ast
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort

REFERENCE = Path(__file__).resolve().parent / "yolox-reference"


def classes():
    tree = ast.parse((REFERENCE / "yolox__data__datasets__coco_classes.py").read_text())
    assignment = next(node for node in tree.body if isinstance(node, ast.Assign))
    return ast.literal_eval(assignment.value)


def preprocess(rgb):
    height, width = rgb.shape[:2]
    scale = min(416 / height, 416 / width)
    scaled_width, scaled_height = int(width * scale), int(height * scale)
    image = np.full((416, 416, 3), 114, dtype=np.uint8)
    # Upstream cv2.imread means BGR, unnormalized float32 values0..255.
    image[:scaled_height, :scaled_width] = cv2.resize(np.ascontiguousarray(rgb[:, :, ::-1]), (scaled_width, scaled_height), interpolation=cv2.INTER_LINEAR)
    return np.ascontiguousarray(image.transpose(2, 0, 1), dtype=np.float32)[None], scale


def decode(raw, scale, score_floor=.05):
    outputs = raw.copy()
    grids, strides = [], []
    for stride in (8, 16, 32):
        x, y = np.meshgrid(np.arange(416 // stride), np.arange(416 // stride))
        grids.append(np.stack((x, y), axis=2).reshape(1, -1, 2))
        strides.append(np.full((1, (416 // stride) ** 2, 1), stride))
    stride_array = np.concatenate(strides, axis=1)
    outputs[..., :2] = (outputs[..., :2] + np.concatenate(grids, axis=1)) * stride_array
    outputs[..., 2:4] = np.exp(outputs[..., 2:4]) * stride_array
    predictions = outputs[0]
    boxes = np.concatenate((predictions[:, :2] - predictions[:, 2:4] / 2,
                            predictions[:, :2] + predictions[:, 2:4] / 2), axis=1) / scale
    scores = predictions[:, 4:5] * predictions[:, 5:]
    categories = scores.argmax(axis=1)
    confidence = scores[np.arange(len(scores)), categories]
    indices = np.flatnonzero(confidence > score_floor)
    indices = indices[np.argsort(confidence[indices])[::-1]]
    keep = []
    while len(indices):
        current = indices[0]
        keep.append(current)
        remaining = indices[1:]
        if not len(remaining):
            break
        left_top = np.maximum(boxes[current, :2], boxes[remaining, :2])
        right_bottom = np.minimum(boxes[current, 2:], boxes[remaining, 2:])
        intersection = np.prod(np.maximum(0, right_bottom - left_top + 1), axis=1)
        area_current = np.prod(boxes[current, 2:] - boxes[current, :2] + 1)
        areas = np.prod(boxes[remaining, 2:] - boxes[remaining, :2] + 1, axis=1)
        indices = remaining[intersection / (area_current + areas - intersection) <= .45]
    return [(boxes[index], float(confidence[index]), int(categories[index])) for index in keep[:100]]


class YoloXTiny:
    def __init__(self, path):
        options = ort.SessionOptions()
        options.intra_op_num_threads = 4
        options.inter_op_num_threads = 1
        options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
        self.session = ort.InferenceSession(str(path), sess_options=options, providers=["CPUExecutionProvider"])
        self.input = self.session.get_inputs()[0]
        if self.input.shape != [1, 3, 416, 416] or self.input.type != "tensor(float)":
            raise RuntimeError("Unexpected YOLOX input shape/type: " + str((self.input.shape, self.input.type)))

    def detect(self, rgb):
        tensor, scale = preprocess(rgb)
        raw = self.session.run(None, {self.input.name: tensor})[0]
        if raw.shape != (1, 3549, 85):
            raise RuntimeError("Unexpected output shape; do not silently guess decoded export format")
        return decode(raw, scale)


def verify_reference():
    # Execute only explicitly selected function definitions from pinned upstream,
    # bypassing unrelated framework imports. Compare pixels/tensors, decoded
    # boxes, combined confidence, NMS and classes on deterministic synthetic data.
    namespace = {"np": np, "cv2": cv2}
    names = {"preproc", "nms", "multiclass_nms_class_agnostic", "demo_postprocess"}
    for filename in ("yolox__data__data_augment.py", "yolox__utils__demo_utils.py"):
        tree = ast.parse((REFERENCE / filename).read_text())
        selected = ast.Module(body=[node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name in names], type_ignores=[])
        exec(compile(selected, filename, "exec"), namespace)
    rng = np.random.default_rng(230)
    checks = []
    for height, width in ((240, 320), (333, 101), (101, 333), (416, 416)):
        rgb = rng.integers(0, 256, (height, width, 3), dtype=np.uint8)
        actual, scale = preprocess(rgb)
        expected, reference_scale = namespace["preproc"](np.ascontiguousarray(rgb[:, :, ::-1]), (416, 416))
        assert scale == reference_scale and np.array_equal(actual[0], expected)
        raw = rng.normal(0, .3, (1, 3549, 85)).astype(np.float32)
        raw[..., 4:] = rng.uniform(0, .15, raw[..., 4:].shape)
        raw[0, :5, 4] = .95
        raw[0, :5, 5:10] = .9
        reference = namespace["demo_postprocess"](raw.copy(), (416, 416))[0]
        reference_boxes = np.concatenate((reference[:, :2] - reference[:, 2:4] / 2, reference[:, :2] + reference[:, 2:4] / 2), axis=1) / scale
        reference_detections = namespace["multiclass_nms_class_agnostic"](reference_boxes, reference[:, 4:5] * reference[:, 5:], .45, .05)
        actual_detections = decode(raw, scale)
        assert len(actual_detections) == len(reference_detections)
        for actual_detection, expected_detection in zip(actual_detections, reference_detections):
            np.testing.assert_array_equal(actual_detection[0], expected_detection[:4])
            assert actual_detection[1] == float(expected_detection[4]) and actual_detection[2] == int(expected_detection[5])
        checks.append({"width": width, "height": height, "pixel_tensor_identical": True, "decode_nms_identical": True, "detections": len(actual_detections)})
    return checks
