"""Export one already-scored selection photograph/GT, never a held-out choice, for native smoke tests."""
import json
from pathlib import Path
import shutil
from pedestrian_evaluation import ROOT, sha, write, interpreter, image_tensor, infer, components

def main():
    data=ROOT/'evaluation/data-pedestrian-balanced'
    manifest=json.loads((data/'manifest.json').read_text(encoding='utf-8'))
    predictions=[json.loads(row) for row in (ROOT/'evaluation/results/pedestrian-balanced/selection-predictions.jsonl').read_text(encoding='utf-8').splitlines()]
    positives=[row for row in predictions if row['roadTruthPresent']]
    def iou(row):
        counts=row['pixelMeasurements'][0]['roadOnly']
        return counts['tp']/(counts['tp']+counts['fp']+counts['fn'])
    chosen=max(positives,key=lambda row:(iou(row),-row['id']))
    image=next(item for item in manifest['images'] if item['id']==chosen['id'])
    assert image['split']=='selection'
    destination=ROOT/'app/src/androidTest/assets/pedestrian'; destination.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(ROOT/image['imagePath'],destination/'road-context.jpg')
    shutil.copyfile(ROOT/image['maskPath'],destination/'road-context-truth.png')
    posterior,_=infer(interpreter(),image_tensor(ROOT/image['imagePath']))
    metadata={'scope':'Native positive smoke fixture chosen only from previously evaluated selection images; not independent accuracy or live-camera evidence',
        'imageId':image['id'],'split':'selection','imageSha256':sha(destination/'road-context.jpg'),
        'groundTruthMaskSha256':sha(destination/'road-context-truth.png'),'truthMaskClasses':manifest['maskClasses'],
        'attribution':image['attribution'],'modelId':'intel-road-segmentation-adas-0001-fp32','pixelPosteriorCutoff':0.0,
        'desktopSelectionPixelCounts':chosen['pixelMeasurements'][0]['roadOnly'],'desktopSelectionRoadIoU':iou(chosen),
        'desktopReferenceRegions':components(posterior,0.0),'datasetManifestSha256':sha(data/'manifest.json')}
    write(destination/'provenance.json',metadata)
    credit=image['attribution']
    (destination/'ATTRIBUTION.md').write_text(f"# Native road fixture\n\nUnmodified photograph [{credit['title']}]({credit['source_url']}) by [{credit['author_name']}]({credit['author_url']}), [{credit['license']}]({credit['license_url']}). No claim is made that the photograph is a captured phone or glasses scene. The separate ground-truth mask is mapped from COCO-Stuff annotations by Holger Caesar, Jasper Uijlings and Vittorio Ferrari, CC BY4.0. Mask values are0 other,1road,2pavement and255ignored. Image/photographic-derivative rights remain separate from the application's source/model license.\n\nChosen from the already evaluated **selection** split, never from calibration or held-out results. `provenance.json` records exact source hashes and expected desktop reference components. Native tests must measure their actual Android output independently.\n",encoding='utf-8',newline='\n')
    print('Native fixture',image['id'],'selectionIoU',iou(chosen))

if __name__=='__main__': main()
