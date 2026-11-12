"""Summarize frozen road-model evidence; no inference or device operations."""
import json
import math
from pathlib import Path
from collections import Counter, defaultdict
from pedestrian_evaluation import ROOT, sha, write

OUT=ROOT/'evaluation/results/pedestrian-balanced'
DATA=ROOT/'evaluation/data-pedestrian-balanced'

def read(path): return json.loads(path.read_text(encoding='utf-8'))
def wilson(success,total):
    if not total: return None
    z=1.959963984540054
    rate=success/total; denominator=1+z*z/total
    midpoint=(rate+z*z/(2*total))/denominator
    half=z*math.sqrt(rate*(1-rate)/total+z*z/(4*total*total))/denominator
    return [midpoint-half,midpoint+half]

def main():
    manifest=read(DATA/'manifest.json'); selection=read(OUT/'selection.json'); calibration=read(OUT/'calibration.json'); final=read(OUT/'held-out.json')
    protocol=read(OUT/'protocol.json')
    reference=read(ROOT/'evaluation/results/pedestrian/reference-parity.json')
    initial_cutoff=next(row for row in selection['candidates'] if row['pixelCutoff']==.85)
    assert final['datasetManifestSha256']==sha(DATA/'manifest.json')
    assert final['calibrationSha256']==sha(OUT/'calibration.json')
    assert final['selectionSha256']==sha(OUT/'selection.json')
    groups=defaultdict(set); counts=Counter(); positives=Counter(); fingerprints=[]; identities=set()
    for item in manifest['images']:
        identity=item['id']; assert identity not in identities and identity not in manifest['priorExcludedIds']; identities.add(identity)
        creator=item['attribution']['author_url'].rstrip('/').lower()
        assert creator not in {url.rstrip('/').lower() for url in manifest['priorExcludedCreators']}
        groups[creator].add(item['split']); counts[item['split']]+=1; positives[item['split']]+=int(item['roadTruthPresent'])
        fingerprints.append(int(item['dhash64'],16))
        for file_key,hash_key in (('imagePath','imageSha256'),('maskPath','maskSha256')):
            assert sha(ROOT/item[file_key])==item[hash_key]
    assert all(len(splits)==1 for splits in groups.values())
    minimum_distance=min((a^b).bit_count() for i,a in enumerate(fingerprints) for b in fingerprints[i+1:])
    assert minimum_distance>6
    metrics=final['roadPresenceEvents']; gate=protocol['calibrationGate']
    deployment_pass=metrics is not None and metrics['precision']>=gate['minimumPrecision'] and metrics['falseEventsPerImage']<=gate['maximumFalseEventsPerImage'] and metrics['recall']>=gate['minimumRecall'] and metrics['tp']>=gate['minimumTruePositives']
    uncertainty={'roadPresencePrecisionWilson95':wilson(metrics['tp'],metrics['tp']+metrics['fp']),
        'roadPresenceRecallWilson95':wilson(metrics['tp'],metrics['tp']+metrics['fn']),
        'falseEventPerImageWilson95':wilson(metrics['fp'],final['n']),
        'scope':'Illustrative binomial intervals for photos; creator clustering, curated prevalence, domain shift and training overlap are not accounted for. Pixel counts are correlated and receive no naive binomial interval.'}
    audit={'images':len(identities),'creators':len(groups),'splitCounts':dict(counts),'positiveCounts':dict(positives),
        'crossSplitCreators':0,'priorImageIdOverlap':0,'priorCreatorOverlap':0,'minimumWithinCorpusDhashDistance':minimum_distance,
        'datasetManifestSha256':sha(DATA/'manifest.json'),'protocolSha256':sha(OUT/'protocol.json'),
        'fullHeldOutGatePassed':deployment_pass,'automaticRoadDescriptionsEnabled':False,
        'reason':'The unchanged held-out FP/image budget is exceeded:1/40=.025 versus .02 maximum. No post-test threshold change.'}
    write(OUT/'integrity-audit.json',audit); write(OUT/'uncertainty.json',uncertainty)
    failures=[row for row in metrics['events'] if row['fp'] or row['fn']]
    notes={313588:'Wet stone paving/courtyard; independent annotation labels pavement, not road. Visible vehicles do not justify changing the ground truth.',
        253819:'Snow-bordered/wet paved road partly occluded by a skateboarder; the frozen image-presence rule missed this road-positive photo.',
        423798:'Night road with bright lamps and long-exposure light streaks; missed road presence.',
        273617:'Grainy dusk view dominated by sky with a narrow road band; missed road presence.'}
    lines=['# Pedestrian road-scene evidence','',
        '**The road-speech release gate failed.** This is a real purpose-built segmentation subsystem with independent masks and measured failures. Automatic road descriptions remain disabled; the network is available for native diagnostics. It must not supply crossing authorization, physical clearance or absence-of-hazard reassurance.','',
        '## Model and conversion integrity','',
        'The small Intel road-segmentation-adas-0001 network has four labels: background, road, curb and marking. It has no sidewalk, crosswalk, signal-state, stairs, entrance or depth output. [Model provenance and licenses](../../road-model/NOTICE.md) distinguish Intel Apache-2.0 weights, MIT conversion work and separately licensed evaluation photographs/annotations.','',
        f"The downloaded conversion did not match Intel’s resize semantics: five bilinear resize operators used align_corners=false/half_pixel_centers=true. One saved reference case had only 98.9301% winning-label agreement and failed the original 99% parity gate. SIGHT changed exactly those five option pairs, preserving all trained buffers. The repaired 817,520-byte artifact has SHA256 `8cbfa699891f82f6edae8493b8a75e648ddbbe8725165a86c6fc4c1f78e6a5b4`. The {len(reference['cases'])} saved repaired reference cases have winning-label agreement ≥{min(case['argmaxAgreement'] for case in reference['cases'])*100:.6f}% and maximum posterior error ≤{max(case['maximumPosteriorError'] for case in reference['cases']):.6g}. These compare the same raw BGR tensor, not Android image-resize equivalence. The [original failures and repaired parity](../pedestrian/) remain versioned.",'',
        'Android runs actual LiteRT CPU inference on 896×512 raw BGR float values 0–255. Distinct 4-connected components retain their own bounds. Coverage is accepted component pixels/full output grid; mean softmax posterior is uncalibrated model score. Curbs/markings remain separate labels and have no independent SIGHT accuracy evaluation.','',
        '## Corpus and frozen decisions','',
        f"A fresh balanced corpus contains 100 credited photos from {len(groups)} creators, selected by independent GT before inference. Selection has 15 road-positive/15 negative photos; calibration has 15/15; the held-out set has 20/20. A positive needs ≥2% annotated road pixels. Negatives contain zero annotated road and ≥2% other ground/floor class, so ordinary pavement and indoor surfaces are represented. The prior 276 image IDs, prior creators and close perceptual duplicates are excluded. Whole creators remain in one split; the minimum new-corpus dHash distance is {minimum_distance}. [Integrity audit](integrity-audit.json) verifies source bytes and split separation.",'',
        'Photographs have recovered current CC BY or CC BY-SA licenses and named attribution. Photo/overlay derivatives retain applicable share-alike terms independently of application/model licenses. [Full corpus and attribution](../../data-pedestrian-balanced/ATTRIBUTION.md) record all accepted originals; manifest whitelists keep downloaded rejected candidates local. COCO-Stuff road149/pavement140 masks are independently supplied annotations, not model-generated ground truth.','',
        f"The [protocol](protocol.json) was written before new-corpus inference. Selection chose the pixel cutoff maximizing strict-road IoU from 0/.35/.5/.65/.75/.85. Unthresholded argmax won: selection IoU {selection['chosen']['roadOnly']['iou']:.4f} versus {initial_cutoff['roadOnly']['iou']:.4f} at the initial .85 cutoff on the identical 30 images. Calibration then chose component coverage ≥.02 and mean posterior ≥.75 from the fixed grid. No parameter was tuned on the final 40.",'',
        '| Frozen metric | Calibration (30) | Held-out (40) |','|---|---:|---:|',
        f"| Road-presence TP / FP / FN / TN |{calibration['chosen']['tp']} /{calibration['chosen']['fp']} /{calibration['chosen']['fn']} /{calibration['chosen']['tn']}|{metrics['tp']} /{metrics['fp']} /{metrics['fn']} /{metrics['tn']}|",
        f"| Road-presence precision |{calibration['chosen']['precision']:.4f}|{metrics['precision']:.4f}|",
        f"| Road-presence recall |{calibration['chosen']['recall']:.4f}|{metrics['recall']:.4f}|",
        f"| False predicted road-presence events / image |{calibration['chosen']['falseEventsPerImage']:.4f}|{metrics['falseEventsPerImage']:.4f}|",
        f"| Strict road pixel precision |{calibration['roadOnlyPixels']['precision']:.4f}|{final['roadOnlyPixels']['precision']:.4f}|",
        f"| Strict road pixel recall |{calibration['roadOnlyPixels']['recall']:.4f}|{final['roadOnlyPixels']['recall']:.4f}|",
        f"| Strict road pixel IoU |{calibration['roadOnlyPixels']['iou']:.4f}|{final['roadOnlyPixels']['iou']:.4f}|",'',
        'The unchanged gate required precision ≥.90, false events/image ≤.02, recall ≥.40 and at least 3 true positives. Calibration passed. The final set produced one false event in 40 images: .025 exceeds .02. **Automatic road narration is disabled, despite .9444 final precision.** This failed the full gate.','',
        f"Illustrative photo-level Wilson 95% intervals are precision [{uncertainty['roadPresencePrecisionWilson95'][0]:.3f}, {uncertainty['roadPresencePrecisionWilson95'][1]:.3f}], recall [{uncertainty['roadPresenceRecallWilson95'][0]:.3f}, {uncertainty['roadPresenceRecallWilson95'][1]:.3f}] and false events/image [{uncertainty['falseEventPerImageWilson95'][0]:.3f}, {uncertainty['falseEventPerImageWilson95'][1]:.3f}]. These do not account for creator clustering or curated prevalence, so they are exploratory. Do not treat millions of correlated pixels as independent trials.",'',
        f"A separately labelled road-or-marking diagnostic has final IoU {final['roadOrMarkingPixels']['iou']:.4f}; it is not the primary road score and is never interpreted as crosswalk recognition. {final['pavementPredictedAsRoadPixels']:,} independently labelled pavement pixels were predicted as road, out of {final['pavementTruthPixels']:,} pavement pixels. This confusion matters for pedestrian positioning.",'',
        '## Genuine failures','',
        '|Photo|Outcome|Inspection|','|---|---|---|']
    for row in failures:
        item=next(i for i in manifest['images'] if i['id']==row['id'])
        photo='../../../'+item['imagePath']; mask=f"masks/{row['id']:012}.png"
        lines.append(f"|[{row['id']}]({photo}) / [prediction mask]({mask})|{'False road presence' if row['fp'] else 'Missed road presence'}|{notes.get(row['id'],'See saved independent truth and prediction.')}|")
    lines+=['',
        'These four cases were inspected after the frozen test solely to document failure modes. Ground truth and thresholds were not changed. They do not supply per-weather or real-video reliability measurements.','',
        '## Earlier general-photo failure','',
        'The existing 180-photo detector corpus was first used as an independent segmentation probe. Only 18 images had annotated road; its 50-image calibration contained five road positives. The initial .85 pixel/mean and .06 coverage rule gave 0 TP/2 FP/5 FN, strict-road pixel precision .118/recall .061/IoU .042. [That failure](../pedestrian/calibration.json) is retained. Its 80-image segmentation holdout was not opened. The new balanced corpus excludes all prior images/creators; improved results across different corpora are not a controlled before/after gain.','',
        '## Reproduction','',
        '```powershell','uv venv --python 3.12 evaluation/.venv-pedestrian',
        'uv pip install --python evaluation/.venv-pedestrian/Scripts/python.exe -r evaluation/requirements-pedestrian-lock.txt',
        'evaluation/.venv-pedestrian/Scripts/python.exe evaluation/convert_road_model.py',
        'evaluation/.venv-pedestrian/Scripts/python.exe evaluation/prepare_pedestrian_corpus.py',
        'evaluation/.venv-pedestrian/Scripts/python.exe evaluation/pedestrian_balanced_evaluation.py selection --output evaluation/results/pedestrian-reproduction',
        'evaluation/.venv-pedestrian/Scripts/python.exe evaluation/pedestrian_balanced_evaluation.py calibration --output evaluation/results/pedestrian-reproduction',
        'evaluation/.venv-pedestrian/Scripts/python.exe evaluation/pedestrian_balanced_evaluation.py held-out --output evaluation/results/pedestrian-reproduction',
        'evaluation/.venv-pedestrian/Scripts/python.exe evaluation/report_pedestrian.py',
        '```','',
        'Original result directories are sealed against prediction overwrite; use a fresh reproduction directory. The final report command summarizes the committed final evidence without inference. Reproduction uses the committed corrected model; regenerating it writes only under.tools. With the full Intel reference installed/fetched, `pedestrian_evaluation.py reference --output evaluation/results/pedestrian-reference-reproduction` checks model conversion on selection inputs.','',
        'A genuine native smoke fixture (photo 568290, CC BY-SA 2.0) was selected only from the already evaluated selection split. Its desktop selection IoU .9756 is not independent accuracy. The [fixture provenance](../../../app/src/androidTest/assets/pedestrian/provenance.json) provides original JPEG/GT hashes and desktop component expectations; actual native measurements are recorded separately by the main validation suite.','',
        'No CPU timing here is advertised as a benchmark: saved invoke timings occurred under uncontrolled shared-host load. Physical Android speed/energy, signal recognition, road/sidewalk boundary accuracy, occlusion, first-person video, sustained thermal behavior, hazard recall, human accessibility or independent mobility were not measured by this study.']
    (OUT/'report.md').write_text('\n'.join(lines)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps({'audit':audit,'uncertainty':uncertainty},indent=2))

if __name__=='__main__': main()
