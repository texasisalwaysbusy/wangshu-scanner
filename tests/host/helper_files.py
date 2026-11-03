"""Actual helpers and source safety, with fake Android and receipt persistence only."""
from support import PROJECT, OUTPUT, JAVA
import subprocess,json,os
from pathlib import Path
root=PROJECT/'app/src/main/java/org/wangshu/scan'
tests=Path(__file__).resolve().parent
work=OUTPUT/'helpers';work.mkdir()
(work/'Context.java').write_text('package android.content;public abstract class Context{public abstract java.io.File getCacheDir();public abstract java.io.File getFilesDir();}',encoding='utf-8')
(work/'AtomicFile.java').write_text('package android.util;import java.io.*;public class AtomicFile{public static int reads;public AtomicFile(File f){}public FileInputStream openRead()throws IOException{reads++;throw new FileNotFoundException();}public FileOutputStream startWrite()throws IOException{throw new IOException();}public void finishWrite(FileOutputStream f){}public void failWrite(FileOutputStream f){}}',encoding='utf-8')
sources=[root/name for name in ['RestoreIntake.java','SaveJobStore.java','EditDraftStore.java','OcrAssetIntegrity.java']]+[tests/name for name in ['IntakeHost.java','SaveExistingHost.java','Utf8Green.java','OcrAssetsHost.java']]+list(work.glob('*.java'))
classes=work/'classes'
p=subprocess.run([JAVA,'-m','jdk.compiler/com.sun.tools.javac.Main','-encoding','UTF-8','-d',str(classes),*map(str,sources)],capture_output=True,text=True,timeout=60)
print(p.stdout,end='');print(p.stderr,end='')
if p.returncode:raise SystemExit(p.returncode)
for name in ['IntakeHost','SaveExistingHost','Utf8Green','OcrAssetsHost']:
 p=subprocess.run([JAVA,'-cp',str(classes),'org.wangshu.scan.'+name,str(work/name)],capture_output=True,text=True,timeout=60)
 print(p.stdout,end='');print(p.stderr,end='')
 if p.returncode:
  # The real helper failed closed on a provider without secure directory identity.
  # Do not replace that provider or mock success; the positive IntakeHost stage is unavailable.
  if name=='IntakeHost' and os.name=='nt' and '文件系统不提供可验证的身份' in p.stderr:
   message='SKIP IntakeHost positive secure-directory path: actual production helper rejected unsupported Windows provider'
   print(message);(work/'IntakeHost-platform-skip.json').write_text(json.dumps({'status':'SKIP','reason':message,'actual_exit':p.returncode,'positive_path_executed':False}),encoding='utf-8')
   continue
  raise SystemExit(p.returncode)
print('HOST ONLY: real files/helpers; fake Android AtomicFile/Context/persistence; no JNI or real OCR assets.')
