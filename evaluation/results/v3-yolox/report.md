# Additional YOLOX experiment: rejected for deployment

After the sealed Lite2 experiment, one further candidate was evaluated: official Apache-2.0 YOLOX-Tiny ONNX release 0.1.1rc0, 20,219,662 bytes, SHA-256 `427cc366d34e27ff7a03e2899b5e3671425c262ea2291f88bb942bc1cc70b0f7`. The [manifest](../../../models/yolox_tiny_manifest.json), pinned upstream sources/license and independently checked adapter preserve exact BGR/float/padding/grid/NMS conventions. It uses a separate Python environment with ONNX Runtime 1.31.0, not the Android application.

The predeclared selection experiment reused only the original 50 development photographs. YOLOX achieved AP50 .5877 and AP50:95 .4087, precision .8873 and recall .4809 at .50. Reference Lite0 selection AP50 was .5150; Lite2 was .6050. Ranked AP and operating precision/recall remain distinct.

A second paired timing run paused SIGHT Gradle/emulator work, pinned the process to logical CPUs 0–3 and rotated model order on 100 identical trials per model. Full synchronous task-call P50/P95 were Lite0 81.36/89.61 ms, Lite2 215.82/231.68 ms and YOLOX 97.99/117.59 ms. Different trial photographs, model mix and host conditions make these numbers distinct from the earlier paired timing experiment; both experiments cap ranked outputs at 100. Other OS load remains uncontrolled. This is desktop CPU evidence, not Android performance. The earlier contended measurements and provisional decision remain archived, explicitly labelled.

YOLOX passed the predeclared speed/AP selection gate but failed the independent calibration gate. Matching reference precision and bounded false positives required threshold .75: precision .9368, recall .2514 and FP/image .12, versus reference Lite0 .9000/.2542/.20. The required recall improvement of .02 was not met. The apparently strong .50 selection recall did not generalize under the conservative precision requirement. No threshold or criterion was changed after observing this outcome.

Therefore YOLOX is **not bundled or integrated into Android**. A further 80 credited photographs, from 77 new creators with earlier IDs/creators/near duplicates excluded, had been sealed before selection; they remain unused for model inference after calibration rejection. Their manifest SHA is `71822f90238c22820eda9cb7ca851991b4be4cb712b073d78ec3179570770d67`. Keeping this holdout unused is preferable to consuming it for an already rejected candidate. This experiment adds no final-test superiority claim and does not replace the historical Lite2 held-out result.

Reproduction must use the separate `evaluation/.venv-yolo` environment and a fresh output directory. The script intentionally rejects attempts to overwrite sealed selection/calibration decisions. `quiet-timing` archives an initial contended decision; do not repeatedly run it to select a favorable speed outcome. Setup and exact download/hash commands are below.

```powershell
uv venv --python 3.12 evaluation/.venv-yolo
uv pip install --python evaluation/.venv-yolo/Scripts/python.exe -r evaluation/requirements-yolox-lock.txt
./scripts/fetch-models.ps1
$sightYoloModel = Get-Content -LiteralPath models/yolox_tiny_manifest.json -Raw | ConvertFrom-Json
Invoke-WebRequest -Uri $sightYoloModel.url -OutFile $sightYoloModel.path
if ((Get-FileHash -LiteralPath $sightYoloModel.path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $sightYoloModel.sha256) { throw 'YOLOX model hash mismatch' }
evaluation/.venv-yolo/Scripts/python.exe evaluation/prepare_yolox_audit_corpus.py
evaluation/.venv-yolo/Scripts/python.exe evaluation/v3_yolox.py verify-reference
evaluation/.venv-yolo/Scripts/python.exe evaluation/v3_yolox.py selection --output evaluation/results/v3-yolox-reproduction
evaluation/.venv-yolo/Scripts/python.exe evaluation/v3_yolox.py calibration --output evaluation/results/v3-yolox-reproduction
```

Use a quiet interval for the selection command because it includes paired CPU timing. In the recorded experiment, the initial contended run was retained and `quiet-timing --output <same-fresh-directory>` ran once after pausing Gradle/emulator work. That command cannot repeat after quiet timing or calibration is sealed. A reproduction that starts quietly need not manufacture a contended run. The fresh output argument changes only artifact destinations; it uses the exact pre-existing selection/calibration/fresh-audit manifests and fixed rules, without repartitioning or tuning the audit. After the recorded rejection, do **not** run `audit`: its gate refuses the rejected candidate and the additional final corpus stays unused.

The fresh corpus command restores missing originals and verifies sealed hashes; it performs no inference. The exact upstream source commit and each reference-file hash are in [source provenance](../../yolox-reference/provenance.json), with the [Apache 2.0 license](../../yolox-reference/LICENSE). The adapter's BGR/top-left padding/float-input and decoded box/NMS results match the selected upstream reference functions on four deterministic aspect-ratio fixtures. This verifies the export conventions; it does not validate an Android port.

An artifact audit confirmed that plan/selection/calibration hashes match, the new corpus manifest still matches the sealed plan, and both saved prediction files contain only their original development-split image IDs. No `audit/` inference directory or `fresh-audit-comparison.json` exists. The calibration artifact records `proceed_to_fresh_audit=false`; that gate stops the CLI before opening audit photos for inference. This checks the available execution artifacts and gate, rather than claiming that file-access history can prove every possible external program avoided those photos.
