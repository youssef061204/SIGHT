"""Inspect tracked history for credential patterns; never prints candidate secret values."""
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT=Path(__file__).resolve().parent.parent
def git(*args,input=None):
    return subprocess.check_output(['git',*args],cwd=ROOT,input=input)

def main():
    names={}
    for line in git('rev-list','--objects','--all').decode().splitlines():
        fields=line.split(' ',1)
        if len(fields)==2: names.setdefault(fields[0],fields[1])
    checked=git('cat-file','--batch-check=%(objectname) %(objecttype) %(objectsize)',input=('\n'.join(names)+'\n').encode()).decode().splitlines()
    blobs=[]; large=[]; sensitive=[]
    for line in checked:
        sha,kind,size=line.split(); size=int(size)
        if kind!='blob': continue
        path=names[sha]
        if size>90_000_000: large.append({'path':path,'bytes':size,'object':sha})
        if re.search(r'(^|/)(?:\.env(?:\..*)?|local\.properties|credentials[^/]*|[^/]+\.(?:jks|keystore))$',path,re.I) and not path.endswith('.example'):
            sensitive.append({'path':path,'object':sha})
        if size<=2_000_000 and Path(path).suffix.lower() not in {'.jpg','.jpeg','.png','.webp','.tflite','.wav','.jar','.zip'}: blobs.append(sha)
    patterns={
        'github_token':rb'(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{50,})',
        'aws_access_key':rb'(?:AKIA|ASIA)[A-Z0-9]{16}',
        'google_api_key':rb'AIza[A-Za-z0-9_-]{35}',
        'private_key':rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----',
        'credential_url':rb'https?://[^\s/<>"\x27:]+:[^\s/@<>"\x27]+@',
    }
    output=git('cat-file','--batch',input=('\n'.join(blobs)+'\n').encode())
    index=0; findings=[]
    for expected in blobs:
        end=output.index(b'\n',index); sha,kind,size=output[index:end].decode().split(); size=int(size)
        assert sha==expected and kind=='blob'
        body=output[end+1:end+1+size]; index=end+2+size
        if b'\x00' in body: continue
        for name,pattern in patterns.items():
            if re.search(pattern,body): findings.append({'path':names[sha],'object':sha,'pattern':name})
    report={'scope':'All reachable committed history, known credential patterns and sensitive filenames; not a proof of absence of every secret',
        'head':git('rev-parse','HEAD').decode().strip(),'uniqueObjects':len(names),'textBlobsScanned':len(blobs),
        'credentialFindings':findings,'sensitiveFiles':sensitive,'oversizedGitBlobs':large}
    target=ROOT/'evaluation/results/publication-audit.json'; target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report,indent=2))
    if findings or sensitive or large: raise SystemExit(1)

if __name__=='__main__': main()
