# Pedestrian road-scene evidence

**The road-speech release gate failed.** This is a real purpose-built segmentation subsystem with independent masks and measured failures. Automatic road descriptions remain disabled; the network is available for native diagnostics. It must not supply crossing authorization, physical clearance or absence-of-hazard reassurance.

## Model and conversion integrity

The small Intel road-segmentation-adas-0001 network has four labels: background, road, curb and marking. It has no sidewalk, crosswalk, signal-state, stairs, entrance or depth output. [Model provenance and licenses](../../road-model/NOTICE.md) distinguish Intel Apache-2.0 weights, MIT conversion work and separately licensed evaluation photographs/annotations.

The downloaded conversion did not match Intel’s resize semantics: five bilinear resize operators used align_corners=false/half_pixel_centers=true. One saved reference case had only 98.9301% winning-label agreement and failed the original 99% parity gate. SIGHT changed exactly those five option pairs, preserving all trained buffers. The repaired 817,520-byte artifact has SHA256 `8cbfa699891f82f6edae8493b8a75e648ddbbe8725165a86c6fc4c1f78e6a5b4`. The 8 saved repaired reference cases have winning-label agreement ≥99.999782% and maximum posterior error ≤3.43323e-05. These compare the same raw BGR tensor, not Android image-resize equivalence. The [original failures and repaired parity](../pedestrian/) remain versioned.

Android runs actual LiteRT CPU inference on 896×512 raw BGR float values 0–255. Distinct 4-connected components retain their own bounds. Coverage is accepted component pixels/full output grid; mean softmax posterior is uncalibrated model score. Curbs/markings remain separate labels and have no independent SIGHT accuracy evaluation.

## Corpus and frozen decisions

A fresh balanced corpus contains 100 credited photos from 89 creators, selected by independent GT before inference. Selection has 15 road-positive/15 negative photos; calibration has 15/15; the held-out set has 20/20. A positive needs ≥2% annotated road pixels. Negatives contain zero annotated road and ≥2% other ground/floor class, so ordinary pavement and indoor surfaces are represented. The prior 276 image IDs, prior creators and close perceptual duplicates are excluded. Whole creators remain in one split; the minimum new-corpus dHash distance is 14. [Integrity audit](integrity-audit.json) verifies source bytes and split separation.

Photographs have recovered current CC BY or CC BY-SA licenses and named attribution. Photo/overlay derivatives retain applicable share-alike terms independently of application/model licenses. [Full corpus and attribution](../../data-pedestrian-balanced/ATTRIBUTION.md) record all accepted originals; manifest whitelists keep downloaded rejected candidates local. COCO-Stuff road149/pavement140 masks are independently supplied annotations, not model-generated ground truth.

The [protocol](protocol.json) was written before new-corpus inference. Selection chose the pixel cutoff maximizing strict-road IoU from 0/.35/.5/.65/.75/.85. Unthresholded argmax won: selection IoU 0.5897 versus 0.4592 at the initial .85 cutoff on the identical 30 images. Calibration then chose component coverage ≥.02 and mean posterior ≥.75 from the fixed grid. No parameter was tuned on the final 40.

| Frozen metric | Calibration (30) | Held-out (40) |
|---|---:|---:|
| Road-presence TP / FP / FN / TN |11 /0 /4 /15|17 /1 /3 /19|
| Road-presence precision |1.0000|0.9444|
| Road-presence recall |0.7333|0.8500|
| False predicted road-presence events / image |0.0000|0.0250|
| Strict road pixel precision |0.6927|0.7605|
| Strict road pixel recall |0.7066|0.8091|
| Strict road pixel IoU |0.5380|0.6448|

The unchanged gate required precision ≥.90, false events/image ≤.02, recall ≥.40 and at least 3 true positives. Calibration passed. The final set produced one false event in 40 images: .025 exceeds .02. **Automatic road narration is disabled, despite .9444 final precision.** This failed the full gate.

Illustrative photo-level Wilson 95% intervals are precision [0.742, 0.990], recall [0.640, 0.948] and false events/image [0.004, 0.129]. These do not account for creator clustering or curated prevalence, so they are exploratory. Do not treat millions of correlated pixels as independent trials.

A separately labelled road-or-marking diagnostic has final IoU 0.6634; it is not the primary road score and is never interpreted as crosswalk recognition. 209,505 independently labelled pavement pixels were predicted as road, out of 500,970 pavement pixels. This confusion matters for pedestrian positioning.

## Genuine failures

|Photo|Outcome|Inspection|
|---|---|---|
|[253819](../../../evaluation/data-pedestrian-balanced/images/000000253819.jpg) / [prediction mask](masks/000000253819.png)|Missed road presence|Snow-bordered/wet paved road partly occluded by a skateboarder; the frozen image-presence rule missed this road-positive photo.|
|[423798](../../../evaluation/data-pedestrian-balanced/images/000000423798.jpg) / [prediction mask](masks/000000423798.png)|Missed road presence|Night road with bright lamps and long-exposure light streaks; missed road presence.|
|[273617](../../../evaluation/data-pedestrian-balanced/images/000000273617.jpg) / [prediction mask](masks/000000273617.png)|Missed road presence|Grainy dusk view dominated by sky with a narrow road band; missed road presence.|
|[313588](../../../evaluation/data-pedestrian-balanced/images/000000313588.jpg) / [prediction mask](masks/000000313588.png)|False road presence|Wet stone paving/courtyard; independent annotation labels pavement, not road. Visible vehicles do not justify changing the ground truth.|

These four cases were inspected after the frozen test solely to document failure modes. Ground truth and thresholds were not changed. They do not supply per-weather or real-video reliability measurements.

## Earlier general-photo failure

The existing 180-photo detector corpus was first used as an independent segmentation probe. Only 18 images had annotated road; its 50-image calibration contained five road positives. The initial .85 pixel/mean and .06 coverage rule gave 0 TP/2 FP/5 FN, strict-road pixel precision .118/recall .061/IoU .042. [That failure](../pedestrian/calibration.json) is retained. Its 80-image segmentation holdout was not opened. The new balanced corpus excludes all prior images/creators; improved results across different corpora are not a controlled before/after gain.

## Reproduction

```powershell
uv venv --python 3.12 evaluation/.venv-pedestrian
uv pip install --python evaluation/.venv-pedestrian/Scripts/python.exe -r evaluation/requirements-pedestrian-lock.txt
evaluation/.venv-pedestrian/Scripts/python.exe evaluation/convert_road_model.py
evaluation/.venv-pedestrian/Scripts/python.exe evaluation/prepare_pedestrian_corpus.py
evaluation/.venv-pedestrian/Scripts/python.exe evaluation/pedestrian_balanced_evaluation.py selection --output evaluation/results/pedestrian-reproduction
evaluation/.venv-pedestrian/Scripts/python.exe evaluation/pedestrian_balanced_evaluation.py calibration --output evaluation/results/pedestrian-reproduction
evaluation/.venv-pedestrian/Scripts/python.exe evaluation/pedestrian_balanced_evaluation.py held-out --output evaluation/results/pedestrian-reproduction
evaluation/.venv-pedestrian/Scripts/python.exe evaluation/report_pedestrian.py
```

Original result directories are sealed against prediction overwrite; use a fresh reproduction directory. The final report command summarizes the committed final evidence without inference. Reproduction uses the committed corrected model; regenerating it writes only under.tools. With the full Intel reference installed/fetched, `pedestrian_evaluation.py reference --output evaluation/results/pedestrian-reference-reproduction` checks model conversion on selection inputs.

A genuine native smoke fixture (photo 568290, CC BY-SA 2.0) was selected only from the already evaluated selection split. Its desktop selection IoU .9756 is not independent accuracy. The [fixture provenance](../../../app/src/androidTest/assets/pedestrian/provenance.json) provides original JPEG/GT hashes and desktop component expectations; actual native measurements are recorded separately by the main validation suite.

No CPU timing here is advertised as a benchmark: saved invoke timings occurred under uncontrolled shared-host load. Physical Android speed/energy, signal recognition, road/sidewalk boundary accuracy, occlusion, first-person video, sustained thermal behavior, hazard recall, human accessibility or independent mobility were not measured by this study.
