#!/usr/bin/env python3
"""Production single-page OCR method and UI callbacks; actual Models, candidate policy,
preflight, recovery journal and source hashing on synthetic files. Native OCR, render,
Store writer, JSON, controls and scheduler are explicit host seams; no Android execution.
"""
from support import PROJECT,OUTPUT,JAVA
import subprocess,json,hashlib
base=PROJECT/'app/src/main/java/org/wangshu/scan'
def block(text,sig):
 start=text.index(sig);i=text.index('{',start);depth=0;mode='code';escape=False
 while i<len(text):
  c=text[i];pair=text[i:i+2]
  if mode=='line':
   if c=='\n':mode='code'
  elif mode=='comment':
   if pair=='*/':mode='code';i+=1
  elif mode in ('"',"'"):
   if escape:escape=False
   elif c=='\\':escape=True
   elif c==mode:mode='code'
  else:
   if pair=='//':mode='line';i+=1
   elif pair=='/*':mode='comment';i+=1
   elif c in ('"',"'"):mode=c
   elif c=='{':depth+=1
   elif c=='}':
    depth-=1
    if not depth:return text[start:i+1]
  i+=1
 raise ValueError(sig)
activity=(base/'MainActivity.java').read_text();engine=(base/'OcrEngine.java').read_text();store=(base/'Store.java').read_text()
sigs=['private boolean ocrCandidateReady(', 'private boolean ocrCandidateCurrent(', 'private void singlePageOcrOptions(', 'private void runSinglePageOcr(', 'private void showOcrCandidate(', 'private void adoptOcrCandidate(', 'private static void preflightEditorSave(', 'private static EditRecoveryPolicy.Identity identity(', 'private static String ocrPageStatus(']
methods='\n'.join(block(activity,x) for x in sigs)
ocr='\n'.join(block(engine,x) for x in ['static String recognizePage(', 'static String requireRecognizedText(', 'static String key(', 'private static void keyPart('])
store_methods='\n'.join(block(store,x) for x in ['static byte[] manifestBytes(', 'static void requireCertain('])
path=OUTPUT/'OcrCandidateHost.java';path.write_text((PROJECT/'tests/OcrCandidateHost.java').read_text().replace('/* MAIN_METHODS */',methods).replace('/* OCR_METHODS */',ocr).replace('/* STORE_METHODS */',store_methods),encoding='utf-8')
sources=[base/(name+'.java') for name in ['Models','OcrCandidate','TextExport','DocumentMutations','ManifestFence','EditorSaveFlow','EditDraftStore','EditRecoveryPolicy','RecoverySourceGate','SerializedBudget']]+[base/'core/Geometry.java',path]+list((PROJECT/'tests/host/json_seam').rglob('*.java'))
commands=[]
def run(label,args):
 p=subprocess.run(list(map(str,args)),capture_output=True,text=True,encoding='utf-8',timeout=180);(OUTPUT/(label+'.txt')).write_text(p.stdout+p.stderr,encoding='utf-8');commands.append({'label':label,'exit':p.returncode});print(p.stdout+p.stderr)
 if p.returncode:raise RuntimeError(label+' failed')
try:
 run('compile',[JAVA,'-m','jdk.compiler/com.sun.tools.javac.Main','-source','17','-target','17','-encoding','UTF-8','-d',OUTPUT/'classes',*sources])
 run('tests',[JAVA,'-Xmx384m','-cp',OUTPUT/'classes','org.wangshu.scan.OcrCandidateHost',OUTPUT/'fixtures'])
finally:
 (OUTPUT/'result.json').write_text(json.dumps({'production_sha256':{str(p.relative_to(PROJECT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in (base/'MainActivity.java',base/'OcrEngine.java',*[p for p in sources if p.is_relative_to(base)])},'methods_sha256':hashlib.sha256(methods.encode()).hexdigest(),'ocr_methods_sha256':hashlib.sha256(ocr.encode()).hexdigest(),'commands':commands,'compile_exit':next((x['exit'] for x in commands if x['label']=='compile'),None),'run_exit':next((x['exit'] for x in commands if x['label']=='tests'),None),'scope':__doc__,'compiler':'JDK21 source/target17; Java17 API fence NOT_RUN'},indent=2),encoding='utf-8')
