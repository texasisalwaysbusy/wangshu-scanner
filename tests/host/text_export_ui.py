#!/usr/bin/env python3
"""Exact MainActivity text source, confirmation, save result and retry callbacks.
Real Models/formatter/receipts/files, host JSON, UI, scheduler, Store and descriptor bridge.
Structural Android bridge checks are not actual SAF/OS/Activity validation.
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
s=(base/'MainActivity.java').read_text();key=(base/'OcrEngine.java').read_text();key=key[key.index('    static String key('):key.index('    private static TessBaseAPI open(')]
sigs=['private static final class PreparedText','private static final class RecoveredText','private static final class SaveWriteFailure','private boolean textContextCurrent(', 'private void requireTextDiskCurrent(', 'private void textExportOptions(', 'private void createTextExport(', 'private void offerTextDestination(', 'private void recoverTextSave(', 'private void endTextSave(', 'private void saveExport(', 'private void recoverSaveResult(', 'private void processSaveResult(', 'private String exportMime(', 'private static boolean matchesApprovedSource(', 'private void verifyExportSnapshot(']
methods='\n'.join(block(s,x) for x in sigs)
bridge=block(s,'private TextDocumentSave.Handle openNewTextDestination(')
assert 'openFileDescriptor(uri,"rw")' in bridge and 'Os.fstat' in bridge and 'TextDocumentSave.requireEmpty' in bridge and 'DocumentsContract.isDocumentUri' in bridge
assert 'openOutputStream' not in bridge and bridge.index('TextDocumentSave.requireEmpty')<bridge.index('AutoCloseOutputStream')
picker=(base/'SavePickerActivity.java').read_text();assert 'Intent.ACTION_CREATE_DOCUMENT' in picker and 'Intent.EXTRA_LOCAL_ONLY,true' in picker
assert 'textSavePermits' in block(s,'private void processSaveResult(')
fixture=PROJECT/'tests/TextExportUiHost.java';path=OUTPUT/'TextExportUiHost.java';path.write_text(fixture.read_text().replace('/* MAIN_METHODS */',methods).replace('/* OCR_KEY */',key),encoding='utf-8')
# Reuse only the synthetic real-file persistence/source fixture from the core test; exclude its Store/OCR shims.
core=(PROJECT/'tests/TextExportTests.java').read_text().split('\nclass Store {')[0];corepath=OUTPUT/'TextExportTests.java';corepath.write_text(core,encoding='utf-8')
context=OUTPUT/'Context.java';context.write_text('package android.content;public abstract class Context{public abstract java.io.File getCacheDir();public abstract java.io.File getFilesDir();}')
atomic=OUTPUT/'AtomicFile.java';atomic.write_text('package android.util;import java.io.*;public class AtomicFile{public AtomicFile(File f){}public FileInputStream openRead()throws IOException{throw new IOException("unused");}public FileOutputStream startWrite()throws IOException{throw new IOException("unused");}public void finishWrite(FileOutputStream f){}public void failWrite(FileOutputStream f){}}')
sources=[base/(n+'.java') for n in ['TextExport','TextDocumentSave','Models','DocumentMutations','ManifestFence','SaveJobStore','EditorSaveFlow']]+[base/'core/Geometry.java',path,corepath,context,atomic]+list((PROJECT/'tests/host/json_seam').rglob('*.java'))
# Exact production stream-copy implementation only, not raster/PDF Android code.
export=(base/'Exporter.java').read_text();copy=block(export,'static void copy(InputStream in,OutputStream out)')
extra=OUTPUT/'Exporter.java';extra.write_text('package org.wangshu.scan;import java.io.*;class Exporter {'+copy+'}',encoding='utf-8');sources.append(extra)
commands=[]
def run(label,args):
 p=subprocess.run(list(map(str,args)),capture_output=True,text=True,encoding='utf-8',timeout=180);(OUTPUT/(label+'.txt')).write_text(p.stdout+p.stderr,encoding='utf-8');commands.append({'label':label,'exit':p.returncode});print(p.stdout+p.stderr)
 if p.returncode:raise RuntimeError(label+' failed')
try:
 run('compile',[JAVA,'-m','jdk.compiler/com.sun.tools.javac.Main','-source','17','-target','17','-encoding','UTF-8','-d',OUTPUT/'classes',*sources])
 run('tests',[JAVA,'-Xmx384m','-cp',OUTPUT/'classes','org.wangshu.scan.TextExportUiHost',OUTPUT/'fixtures'])
finally:
 (OUTPUT/'result.json').write_text(json.dumps({'activity_sha256':hashlib.sha256((base/'MainActivity.java').read_bytes()).hexdigest(),'methods_sha256':hashlib.sha256(methods.encode()).hexdigest(),'bridge_sha256':hashlib.sha256(bridge.encode()).hexdigest(),'commands':commands,'compile_exit':next((x['exit'] for x in commands if x['label']=='compile'),None),'run_exit':next((x['exit'] for x in commands if x['label']=='tests'),None),'scope':__doc__,'compiler':'source/target17; no Java17 API fence'},indent=2),encoding='utf-8')
