"""Real road-model selection/calibration/test on the independently sealed road-balanced corpus.

No model training or data upload. Pixel cutoff is selected on selection images, then a
road-description presence rule is calibrated. The final test never tunes that rule.
"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import numpy as np
from PIL import Image
from pedestrian_evaluation import MODEL_SHA, ROOT, checked_model, components, image_tensor, infer, interpreter, sha, statistics, binary_counts, write

DATA=ROOT/'evaluation/data-pedestrian-balanced'
OUT=ROOT/'evaluation/results/pedestrian-balanced'
PROTOCOL={
    'modelSha256':MODEL_SHA,
    'pixelCutoffGrid':[0.0,.35,.5,.65,.75,.85],
    'pixelSelection':'Highest strict-road pixel IoU on selection; tie prefers higher cutoff',
    'componentMinimumCoverage':.002,'componentConnectivity':4,'maximumComponents':12,
    'voiceCoverageGrid':[.02,.04,.06,.10,.15,.20,.30,.40,.50],
    'voiceMeanPosteriorGrid':[0.0,.50,.65,.75,.85,.90,.95],
    'truthRoadPresence':'At least 2 percent of original full-image pixels annotated as COCO-Stuff road149',
    'calibrationGate':{'minimumPrecision':.90,'maximumFalseEventsPerImage':.02,'minimumRecall':.40,'minimumTruePositives':3},
    'voiceChoice':'Highest calibration recall meeting gate; then fewer FP, higher mean posterior, larger coverage. None means automatic road description disabled.',
    'heldOutRule':'Evaluate selected pixel cutoff and fixed calibrated voice rule once; never retune on these images',
    'notEvaluated':['curb accuracy','marking accuracy','crosswalks','sidewalks','pedestrian signals','depth','clearance','camera movement','field hazard event recall'],
    'scope':'Balanced static COCO photographs; source-image road presence is not physical navigability or a natural-frequency false-warning estimate',
}

def corpus():
    manifest=json.loads((DATA/'manifest.json').read_text(encoding='utf-8'))
    assert manifest['count']==100 and manifest['splitCounts']=={'selection':30,'calibration':30,'held_out':40}
    for item in manifest['images']:
        for file_key,hash_key in (('imagePath','imageSha256'),('maskPath','maskSha256')):
            if sha(ROOT/item[file_key])!=item[hash_key]: raise RuntimeError('Sealed bytes drift: '+str(item['id']))
    return manifest

def protocol(out):
    value={**PROTOCOL,'datasetManifestSha256':sha(DATA/'manifest.json')}
    path=out/'protocol.json'
    if path.exists() and json.loads(path.read_text(encoding='utf-8'))!=value:
        raise RuntimeError('Refusing to overwrite a sealed protocol')
    if not path.exists(): write(path,value)
    return value

def rows_for(split,out,cutoff=None,all_cutoffs=False):
    manifest=corpus()
    destination=out/(split+'-predictions.jsonl')
    if destination.exists(): raise RuntimeError('Use a fresh reproduction output; saved predictions cannot be overwritten')
    runtime=interpreter(); rows=[]
    for item in manifest['images']:
        if item['split']!=split: continue
        posterior,elapsed=infer(runtime,image_tensor(ROOT/item['imagePath']))
        truth=np.asarray(Image.open(ROOT/item['maskPath']),dtype=np.uint8)
        winner=posterior.argmax(2).astype(np.uint8); confidence=posterior.max(2)
        cutoffs=PROTOCOL['pixelCutoffGrid'] if all_cutoffs else [cutoff]
        measurements=[]
        for value in cutoffs:
            accepted=winner.copy(); accepted[confidence<value]=0
            predicted=np.asarray(Image.fromarray(accepted).resize((item['width'],item['height']),Image.Resampling.NEAREST))
            measurements.append({'pixelCutoff':value,'roadOnly':binary_counts(predicted==1,truth==1,truth!=255),
                'roadOrMarking':binary_counts(np.isin(predicted,[1,3]),truth==1,truth!=255),
                'pavementPredictedAsRoadPixels':int(((predicted==1)&(truth==2)).sum())})
        record={'id':item['id'],'split':split,'roadTruthPresent':item['roadTruthPresent'],
            'modelSha256':MODEL_SHA,'invokeMsUnderCurrentHostLoad':elapsed,'pixelMeasurements':measurements,
            'pavementTruthPixels':item['pavementPixels']}
        if not all_cutoffs:
            record['regions']=components(posterior,cutoff)
            accepted=winner.copy(); accepted[confidence<cutoff]=0
            predicted=np.asarray(Image.fromarray(accepted).resize((item['width'],item['height']),Image.Resampling.NEAREST))
            mask_path=out/'masks'/f"{item['id']:012}.png"; mask_path.parent.mkdir(parents=True,exist_ok=True)
            Image.fromarray(predicted).save(mask_path)
            record.update(maskPath=mask_path.relative_to(out).as_posix(),maskSha256=sha(mask_path))
        rows.append(record)
        print(split,item['id'],'roadGT',item['roadTruthPresent'],flush=True)
    destination.parent.mkdir(parents=True,exist_ok=True)
    destination.write_text(''.join(json.dumps(r)+'\n' for r in rows),encoding='utf-8',newline='\n')
    return rows

def event_metrics(rows,coverage,mean):
    counts=[]
    for row in rows:
        prediction=any(region['category']==1 and region['coverage']>=coverage and region['meanPosterior']>=mean for region in row['regions'])
        truth=row['roadTruthPresent']
        counts.append({'id':row['id'],'tp':int(prediction and truth),'fp':int(prediction and not truth),
            'fn':int(not prediction and truth),'tn':int(not prediction and not truth)})
    stats=statistics(counts)
    return {'minimumCoverage':coverage,'minimumMeanPosterior':mean,**stats,
        'falseEventsPerImage':stats['fp']/len(rows),'events':counts}

def selection(out):
    sealed=protocol(out)
    rows=rows_for('selection',out,all_cutoffs=True)
    candidates=[]
    for index,value in enumerate(PROTOCOL['pixelCutoffGrid']):
        candidates.append({'pixelCutoff':value,'roadOnly':statistics([r['pixelMeasurements'][index]['roadOnly'] for r in rows]),
            'roadOrMarking':statistics([r['pixelMeasurements'][index]['roadOrMarking'] for r in rows])})
    chosen=max(candidates,key=lambda c:(c['roadOnly']['iou'] or 0,c['pixelCutoff']))
    result={'datasetManifestSha256':sealed['datasetManifestSha256'],'protocolSha256':sha(out/'protocol.json'),
        'n':len(rows),'candidates':candidates,'chosen':chosen,'selectedBeforeCalibrationOrHeldOut':True}
    write(out/'selection.json',result); print(json.dumps(result,indent=2))

def calibration(out):
    protocol(out)
    chosen=json.loads((out/'selection.json').read_text(encoding='utf-8'))
    if chosen['protocolSha256']!=sha(out/'protocol.json'): raise RuntimeError('Selection protocol changed')
    cutoff=chosen['chosen']['pixelCutoff']
    rows=rows_for('calibration',out,cutoff)
    candidates=[event_metrics(rows,coverage,mean) for coverage in PROTOCOL['voiceCoverageGrid'] for mean in PROTOCOL['voiceMeanPosteriorGrid']]
    gate=PROTOCOL['calibrationGate']
    passed=[c for c in candidates if (c['precision'] or 0)>=gate['minimumPrecision'] and c['falseEventsPerImage']<=gate['maximumFalseEventsPerImage'] and
        (c['recall'] or 0)>=gate['minimumRecall'] and c['tp']>=gate['minimumTruePositives']]
    selected=max(passed,key=lambda c:(c['recall'],-c['fp'],c['minimumMeanPosterior'],c['minimumCoverage'])) if passed else None
    result={'protocolSha256':sha(out/'protocol.json'),'selectionSha256':sha(out/'selection.json'),'pixelCutoff':cutoff,'n':len(rows),
        'candidates':candidates,'automaticRoadVoiceEligibleByCalibration':selected is not None,'chosen':selected,
        'roadOnlyPixels':statistics([r['pixelMeasurements'][0]['roadOnly'] for r in rows]),
        'roadOrMarkingPixels':statistics([r['pixelMeasurements'][0]['roadOrMarking'] for r in rows])}
    write(out/'calibration.json',result); print('CALIBRATION',json.dumps(selected,indent=2))

def heldout(out):
    protocol(out)
    calibration_path=out/'calibration.json'
    frozen=json.loads(calibration_path.read_text(encoding='utf-8'))
    if frozen['protocolSha256']!=sha(out/'protocol.json'): raise RuntimeError('Calibration protocol changed')
    rows=rows_for('held_out',out,frozen['pixelCutoff'])
    chosen=frozen['chosen']
    metrics=event_metrics(rows,chosen['minimumCoverage'],chosen['minimumMeanPosterior']) if chosen else None
    result={'recordedAtUtc':datetime.now(timezone.utc).isoformat(),'n':len(rows),'roadTruthPresent':sum(r['roadTruthPresent'] for r in rows),
        'modelSha256':MODEL_SHA,'datasetManifestSha256':sha(DATA/'manifest.json'),'protocolSha256':sha(out/'protocol.json'),
        'selectionSha256':sha(out/'selection.json'),'calibrationSha256':sha(calibration_path),'pixelCutoff':frozen['pixelCutoff'],
        'automaticRoadVoiceEligibleByCalibration':frozen['automaticRoadVoiceEligibleByCalibration'],'roadPresenceEvents':metrics,
        'roadOnlyPixels':statistics([r['pixelMeasurements'][0]['roadOnly'] for r in rows]),
        'roadOrMarkingPixels':statistics([r['pixelMeasurements'][0]['roadOrMarking'] for r in rows]),
        'pavementPredictedAsRoadPixels':sum(r['pixelMeasurements'][0]['pavementPredictedAsRoadPixels'] for r in rows),
        'pavementTruthPixels':sum(r['pavementTruthPixels'] for r in rows),'limits':PROTOCOL['notEvaluated']+[PROTOCOL['scope']]}
    write(out/'held-out.json',result); print(json.dumps(result,indent=2))

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stage',choices=['selection','calibration','held-out'])
    parser.add_argument('--output',type=Path,default=OUT)
    args=parser.parse_args(); out=args.output.resolve(); out.mkdir(parents=True,exist_ok=True)
    {'selection':selection,'calibration':calibration,'held-out':heldout}[args.stage](out)

if __name__=='__main__': main()
