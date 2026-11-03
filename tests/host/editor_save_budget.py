#!/usr/bin/env python3
"""Exact MainActivity save/preflight/restore/lifecycle methods; actual Models and mutation classes.
JSON is an explicit host typed implementation, not Android org.json. Store writer,
controls and scheduling are seams. Actual journal/files; no Android API/lifecycle execution.
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
activity=(base/'MainActivity.java').read_text(encoding='utf-8');store=(base/'Store.java').read_text(encoding='utf-8')
sigs=['private boolean updateOcrDraft(', 'private void saveEditor()', 'private void saveEditor(boolean advance)', 'private static void preflightEditorSave(', 'private static ArrayList<String> editorOrder(', 'private void leaveEditor(', 'private void saveEditorLifecycle(', 'private void releaseLifecycleMemory(', 'private void resumeUnverifiedEditor(', 'private void openLifecycleDraft(', 'private static Models.Page recoveryEdited(', 'private void keepRecoverySnapshotAndLeave(', 'private static boolean sameLifecycleSnapshot(', 'private void restoreDraft(', 'private static final class StartupValue', 'private void completeStartup(', 'private void retainRecovery(', 'private static Models.Page strictCheckpointPage(', 'private static EditRecoveryPolicy.Identity identity(']
methods='\n'.join(block(activity,sig) for sig in sigs)
store_methods='\n'.join(block(store,sig) for sig in ['static byte[] manifestBytes(', 'static void requireCertain('])
fixture=PROJECT/'tests/EditorSaveBudgetHost.java';path=OUTPUT/'EditorSaveBudgetHost.java';path.write_text(fixture.read_text().replace('/* MAIN_METHODS */',methods).replace('/* STORE_METHODS */',store_methods),encoding='utf-8')
sources=[base/(name+'.java') for name in ['Models','DocumentMutations','ManifestFence','EditorSaveFlow','EditDraftStore','EditRecoveryState','EditRecoveryPolicy','SerializedBudget','EditorLifecycleState']]+[base/'core/Geometry.java',path]+list((PROJECT/'tests/host/json_seam').rglob('*.java'))
commands=[]
def run(label,args):
 p=subprocess.run(list(map(str,args)),capture_output=True,text=True,encoding='utf-8',timeout=180);(OUTPUT/(label+'.txt')).write_text(p.stdout+p.stderr,encoding='utf-8');commands.append({'label':label,'exit':p.returncode});print(p.stdout+p.stderr)
 if p.returncode:raise RuntimeError(label+' failed')
try:
 run('compile',[JAVA,'-m','jdk.compiler/com.sun.tools.javac.Main','-source','17','-target','17','-encoding','UTF-8','-d',OUTPUT/'classes',*sources])
 run('tests',[JAVA,'-Xmx384m','-cp',OUTPUT/'classes','org.wangshu.scan.EditorSaveBudgetHost',OUTPUT/'fixtures'])
finally:
 (OUTPUT/'save-budget-result.json').write_text(json.dumps({'activity_sha256':hashlib.sha256((base/'MainActivity.java').read_bytes()).hexdigest(),'methods_sha256':hashlib.sha256(methods.encode()).hexdigest(),'commands':commands,'scope':__doc__,'compiler':'source/target17 on installed JDK; Java17 API fence NOT_RUN'},indent=2),encoding='utf-8')
