"""Reproduce the exact repaired road model; no retraining and no application asset overwrite.

Requires flatc25.12.19. Windows can fetch the hash-pinned official compiler; other
platforms supply --flatc from that same release. All generated files stay in --work.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import urllib.request
import zipfile

ROOT=Path(__file__).resolve().parent.parent
SOURCE='https://huggingface.co/AutowareFoundation/yabloc_pose_initializer/resolve/282a7fa0eb53ab5f8f0f179708ca57eacd25eaf1/saved_model/model_float32.tflite'
SOURCE_SHA='9ac3a1423bc48e83808edc8273fe7a6e53d0bf0cabe2699d5ed1979b3c64f8a8'
EXPECTED='8cbfa699891f82f6edae8493b8a75e648ddbbe8725165a86c6fc4c1f78e6a5b4'
SCHEMA_SHA='da73b89f169b80d40b49e8131092c0d122d3306f24d2269a36c50b8c2d22991c'
FLATC_URL='https://github.com/google/flatbuffers/releases/download/v25.12.19/Windows.flatc.binary.zip'
FLATC_ZIP_SHA='fff9445c9db907227bc64b54cc98743084c4949282aa4e576cff6a955724ddc8'
FLATC_EXE_SHA='eddf0ca8ab85003151a895faabc99e9a7c02c8b4ea4d421fd5725a7a0c34ca8c'

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def download(url,path,expected):
    if not path.exists(): urllib.request.urlretrieve(url,path)
    if sha(path)!=expected: raise RuntimeError('Artifact SHA256 mismatch: '+str(path))

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work',type=Path,default=ROOT/'.tools/road-model-reproduction')
    parser.add_argument('--flatc',type=Path)
    args=parser.parse_args(); work=args.work.resolve(); work.mkdir(parents=True,exist_ok=True)
    schema=ROOT/'evaluation/road-model/schema.fbs'
    if sha(schema)!=SCHEMA_SHA: raise RuntimeError('Pinned TensorFlow Lite schema changed')
    compiler=args.flatc
    if compiler is None:
        import sys
        if sys.platform!='win32': raise RuntimeError('Supply --flatc from official FlatBuffers25.12.19 release')
        archive=work/'flatc.zip'; download(FLATC_URL,archive,FLATC_ZIP_SHA)
        compiler=work/'flatc.exe'
        with zipfile.ZipFile(archive) as bundle:
            candidates=[n for n in bundle.namelist() if Path(n).name=='flatc.exe']
            if len(candidates)!=1: raise RuntimeError('Unexpected compiler archive')
            compiler.write_bytes(bundle.read(candidates[0]))
        if sha(compiler)!=FLATC_EXE_SHA: raise RuntimeError('Unexpected flatc executable')
    compiler=compiler.resolve()
    version=subprocess.check_output([str(compiler),'--version'],text=True).strip()
    if version!='flatc version 25.12.19': raise RuntimeError('Wrong compiler version: '+version)
    source=work/'road_float32.tflite'; download(SOURCE,source,SOURCE_SHA)
    subprocess.run([str(compiler),'--json','--strict-json','--defaults-json','--raw-binary',
        '-o',str(work),str(schema),'--',str(source)],check=True)
    original=json.loads((work/'road_float32.json').read_text(encoding='utf-8'))
    buffers_before=json.dumps(original['buffers'],sort_keys=True).encode()
    count=0
    for graph in original['subgraphs']:
        for operation in graph['operators']:
            if operation.get('builtin_options_type')=='ResizeBilinearOptions':
                options=operation['builtin_options']
                if options['align_corners'] is not False or options['half_pixel_centers'] is not True:
                    raise RuntimeError('Source resize semantics differ from audited conversion')
                options['align_corners']=True; options['half_pixel_centers']=False; count+=1
    if count!=5 or json.dumps(original['buffers'],sort_keys=True).encode()!=buffers_before:
        raise RuntimeError('Expected exactly five option changes with all buffers unchanged')
    modified=work/'road_aligned.json'; modified.write_text(json.dumps(original),encoding='utf-8')
    subprocess.run([str(compiler),'--binary','-o',str(work),str(schema),str(modified)],check=True)
    output=work/'road_aligned.tflite'
    if sha(output)!=EXPECTED: raise RuntimeError('Repaired output differs from pinned shipped model')
    proof={'sourceUrl':SOURCE,'sourceSha256':SOURCE_SHA,'outputSha256':sha(output),'bytes':output.stat().st_size,
        'schemaSha256':SCHEMA_SHA,'compilerVersion':version,'resizeFlagsCorrected':count,
        'allTrainedBuffersUnchanged':True,'scope':'Exact model conversion reproduction; no training, inference or device operations'}
    (work/'reproduction.json').write_text(json.dumps(proof,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps({**proof,'output':str(output)},indent=2))

if __name__=='__main__': main()
