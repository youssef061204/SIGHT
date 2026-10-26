# Constructed temporal component ablation

These are deterministic, labelled synthetic image-box trajectories. They are **not** real video or detector measurements. Every variant receives the same observations, including explicitly missing detections and score dips. This evaluates policy mechanisms in the fixture domain only. The overlap-only row ablates prediction in the upgraded engine; it is not a rerun of the complete original SIGHT binary.

| Component configuration | ID switches | IDF1 | Stable identity transitions | Missed observations | Find completions / false | Live utterances | Stale speech admitted | Historical recall | Grounded active prompts |
|---|---:|---:|---:|---:|---:|---:|---:|---|---:|
| overlap_association_only | 5 | 0.671 | 58/63 | 14/84 | 8 / 0 | 8 | 0 | true | 1 |
| without_world_memory | 1 | 0.865 | 62/63 | 14/84 | 8 / 0 | 8 | 0 | false | 1 |
| without_goal_continuation | 1 | 0.865 | 62/63 | 14/84 | 4 / 0 | 4 | 0 | true | 1 |
| without_active_perception | 1 | 0.865 | 62/63 | 14/84 | 8 / 0 | 8 | 0 | true | 0 |
| full_temporal_policy | 1 | 0.865 | 62/63 | 14/84 | 8 / 0 | 8 | 0 | true | 1 |

Scenarios: translation_detector_misses, partial_occlusion_score_dip, same_category_crossing, long_gap_new_identity, single_frame_false_positive. Constant image translation can represent camera or object motion; the engine cannot distinguish them. Occlusion/missed detections never create predicted visible observations. Sudden geometry changes, unrelated repeated objects and long gaps remain identity limitations. Track IDs are image hypotheses.

IDF1 uses a maximum global one-to-one predicted/label identity matching. Missing detector observations count as IDFN, rather than crediting the tracker for invented boxes. Recall correctness is an actual grounded historical-side response after tracker TTL expiry; the world-memory ablation should abstain. Find completions include initial matches and explicitly reverified returns, so a higher count here is not a general task-success estimate. Active prompts require the actual table evidence in a separate missing-target fixture. Zero stale admissions checks the final speech gate at a future time.

This is a controlled component comparison, not the requested full six-stage detector/system comparison. No detector, threshold tuning, external dataset partition or Android runtime is evaluated here. Reproduce from the repository root with `./gradlew.bat :core:temporalEvaluation "-PtemporalOutput=evaluation/results/temporal-v3" --max-workers=2`.
