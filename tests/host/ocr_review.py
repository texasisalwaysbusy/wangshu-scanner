#!/usr/bin/env python3
"""Execute exact OCR editor, selector, status and search methods with synthetic controls/models.
Runs production EditorSaveFlow/EditDraftStore on JVM/host files. No Android UI,
org.json, Store/AtomicFile, process recreation, native OCR or PDF execution.
"""
from support import PROJECT, OUTPUT, JAVA
import hashlib, json, subprocess
source=PROJECT/'app/src/main/java/org/wangshu/scan/MainActivity.java'
s=source.read_text(encoding='utf-8')
def block(text,signature):
    start=text.index(signature);i=text.index('{',start);depth=0;mode='code';escaped=False
    while i<len(text):
        c=text[i];pair=text[i:i+2]
        if mode=='line':
            if c=='\n':mode='code'
        elif mode=='comment':
            if pair=='*/':mode='code';i+=1
        elif mode in ('"',"'"):
            if escaped:escaped=False
            elif c=='\\':escaped=True
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
    raise ValueError('Unclosed production method')
sigs=['private static String ocrPageStatus(', 'private void showOcrReviewPages(',
      'private static String ocrReviewStatus(', 'private boolean updateOcrDraft(',
      'private void addOcrEditor(', 'private void refreshOcrStatus(',
      'private boolean matches(', 'private static String describeOcrPages(']
methods='\n'.join(block(s,sig) for sig in sigs)
# Structural checks only: these do not execute Android save/lifecycle wiring.
assert 'addOcrEditor(controls)' in block(s,'private void buildEditor(')
assert 'refreshOcrStatus()' in block(s,'private void requestPreview(')
assert 'next=draft.copy()' in block(s,'private void saveEditor(boolean advance)')
assert 'draft.json().toString()' in block(s,'@Override protected void onSaveInstanceState(')
assert 'openEditor(page)' in block(s,'private void deliverPendingEditorSave(')
engine=PROJECT/'app/src/main/java/org/wangshu/scan/OcrEngine.java'
key='\n'.join(block(engine.read_text(encoding='utf-8'),sig) for sig in ['static String key(', 'private static void keyPart('])
fixture=PROJECT/'tests/OcrReviewHost.java'
java=fixture.read_text(encoding='utf-8').replace('/* PRODUCTION_METHODS */',methods).replace('/* PRODUCTION_KEY */',key)
work=OUTPUT/'ocr-review';work.mkdir();(work/'android/content').mkdir(parents=True)
path=work/'OcrReviewHost.java';path.write_text(java,encoding='utf-8')
clipboard=work/'android/content/ClipboardManager.java'
clipboard.write_text('package android.content;public class ClipboardManager {public Object clip;public void setPrimaryClip(Object value){clip=value;}}',encoding='utf-8')
production=[PROJECT/'app/src/main/java/org/wangshu/scan'/f'{name}.java' for name in ['EditorSaveFlow','EditDraftStore']]
c=subprocess.run([JAVA,'-m','jdk.compiler/com.sun.tools.javac.Main','-source','17','-target','17','-encoding','UTF-8','-d',str(work/'classes'),str(path),str(clipboard),*map(str,production)],capture_output=True,text=True,encoding='utf-8',timeout=60)
p=subprocess.run([JAVA,'-cp',str(work/'classes'),'org.wangshu.scan.OcrReviewHost',str(work/'fixtures')],capture_output=True,text=True,encoding='utf-8',timeout=60) if c.returncode==0 else None
receipt={'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'fixture_sha256':hashlib.sha256(fixture.read_bytes()).hexdigest(),'methods_sha256':hashlib.sha256(methods.encode()).hexdigest(),'production_sha256':{x.name:hashlib.sha256(x.read_bytes()).hexdigest() for x in production},'compile_exit':c.returncode,'run_exit':p.returncode if p else None,'compiler_scope':'Java17 syntax/classfile target using installed JDK; no Java17 API fence','compile_output':c.stdout+c.stderr,'stdout':p.stdout if p else '', 'stderr':p.stderr if p else '', 'scope':__doc__}
(work/'result.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(receipt,ensure_ascii=False,indent=2));raise SystemExit(p.returncode if p else c.returncode)
