"""Restore fixed upstream OCR source; --download requires applicable installation authorization."""
import argparse
import hashlib
import json
from pathlib import Path
import tarfile
import urllib.request
import subprocess

root=Path(__file__).resolve().parents[1]
repo=root.parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--download',action='store_true')
args=parser.parse_args()
lock=json.loads((root/'docs/OCR-DEPENDENCIES.json').read_text())['tesseract4android']
archive=repo/'.tools/downloads/tesseract4android-4.9.0.tar.gz'
if not archive.exists():
    if not args.download:raise SystemExit('Source archive missing. Reauthorize official installation/download scope, then use --download.')
    archive.parent.mkdir(parents=True,exist_ok=True)
    urllib.request.urlretrieve(lock['url'],archive)
if hashlib.sha256(archive.read_bytes()).hexdigest()!=lock['sha256']:raise SystemExit('Upstream source archive checksum mismatch; do not execute')
destination=repo/'.tools/sources'
if not (destination/'Tesseract4Android-4.9.0').exists():
    destination.mkdir(parents=True,exist_ok=True)
    with tarfile.open(archive) as source:source.extractall(destination,filter='data')
vendor=destination/'Tesseract4Android-4.9.0'
for item in json.loads((root/'docs/OCR-DEPENDENCIES.json').read_text()).get('patches',[]):
    patch=root/item['patch']
    if hashlib.sha256(patch.read_bytes()).hexdigest()!=item['sha256']:raise SystemExit('Patch checksum mismatch')
    target=vendor/item['file'];current=hashlib.sha256(target.read_bytes()).hexdigest()
    if current==item['patched_sha256']:continue
    if current!=item['original_sha256']:raise SystemExit('Unexpected vendor source modification; inspect before building')
    subprocess.run(['patch','--batch','-p1','-i',str(patch)],cwd=vendor,check=True)
    if hashlib.sha256(target.read_bytes()).hexdigest()!=item['patched_sha256']:raise SystemExit('Patched source checksum mismatch')
print('Fixed OCR source archive verified; version 4.9.0')
