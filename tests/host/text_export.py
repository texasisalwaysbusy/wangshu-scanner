#!/usr/bin/env python3
"""Actual UTF-8 export/Models/provenance/receipt/target protocol on synthetic files.
Host JSON and Android Context/AtomicFile seams. No Android SAF, UI or device execution.
"""
from support import PROJECT,OUTPUT,JAVA
import subprocess,json,hashlib
base=PROJECT/'app/src/main/java/org/wangshu/scan'
s=(base/'OcrEngine.java').read_text();key=s[s.index('    static String key('):s.index('    private static TessBaseAPI open(')]
fixture=PROJECT/'tests/TextExportTests.java';test=OUTPUT/'TextExportTests.java';test.write_text(fixture.read_text().replace('/* OCR_KEY */',key),encoding='utf-8')
context=OUTPUT/'Context.java';context.write_text('package android.content;public abstract class Context{public abstract java.io.File getCacheDir();public abstract java.io.File getFilesDir();}',encoding='utf-8')
atomic=OUTPUT/'AtomicFile.java';atomic.write_text('package android.util;import java.io.*;public class AtomicFile{public AtomicFile(File f){}public FileInputStream openRead()throws IOException{throw new IOException("unused");}public FileOutputStream startWrite()throws IOException{throw new IOException("unused");}public void finishWrite(FileOutputStream f){}public void failWrite(FileOutputStream f){}}',encoding='utf-8')
sources=[base/(n+'.java') for n in ['TextExport','TextDocumentSave','Models','DocumentMutations','ManifestFence','SaveJobStore']]+[base/'core/Geometry.java',test,context,atomic]+list((PROJECT/'tests/host/json_seam').rglob('*.java'))
commands=[]
def run(label,args):
 p=subprocess.run(list(map(str,args)),capture_output=True,text=True,encoding='utf-8',timeout=180);(OUTPUT/(label+'.txt')).write_text(p.stdout+p.stderr,encoding='utf-8');commands.append({'label':label,'exit':p.returncode});print(p.stdout+p.stderr)
 if p.returncode:raise RuntimeError(label+' failed')
try:
 run('compile',[JAVA,'-m','jdk.compiler/com.sun.tools.javac.Main','-source','17','-target','17','-encoding','UTF-8','-d',OUTPUT/'classes',*sources])
 run('tests',[JAVA,'-Xmx384m','-cp',OUTPUT/'classes','org.wangshu.scan.TextExportTests',OUTPUT/'fixtures'])
finally:
 (OUTPUT/'result.json').write_text(json.dumps({'sources':{str(p.relative_to(PROJECT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources if p.is_relative_to(PROJECT)},'commands':commands,'compile_exit':next((x['exit'] for x in commands if x['label']=='compile'),None),'run_exit':next((x['exit'] for x in commands if x['label']=='tests'),None),'scope':__doc__,'compiler':'source/target17; Java17 API fence NOT_RUN'},indent=2),encoding='utf-8')
