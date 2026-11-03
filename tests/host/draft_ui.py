#!/usr/bin/env python3
"""Actual extracted restoreDraft/openEditor/strictCheckpointPage/identity plus real
EditRecoveryPolicy. Models, JSON and rendering remain synthetic host seams.
No Android Activity lifecycle, org.json runtime or device claim.
"""
from pathlib import Path
from support import PROJECT,OUTPUT,JAVA
import hashlib,json,subprocess,sys,re
source=PROJECT/'app/src/main/java/org/wangshu/scan/MainActivity.java';s=source.read_text(encoding='utf-8')
def block(sig):
 start=s.index(sig);i=s.index('{',start);depth=0;mode='code';esc=False
 while i<len(s):
  c=s[i];n=s[i:i+2]
  if mode=='line':
   if c=='\n':mode='code'
  elif mode=='comment':
   if n=='*/':mode='code';i+=1
  elif mode in ('"',"'"):
   if esc:esc=False
   elif c=='\\':esc=True
   elif c==mode:mode='code'
  else:
   if n=='//':mode='line';i+=1
   elif n=='/*':mode='comment';i+=1
   elif c in ('"',"'"):mode=c
   elif c=='{':depth+=1
   elif c=='}':
    depth-=1
    if depth==0:return s[start:i+1]
  i+=1
 raise ValueError('Unclosed production method')
sigs=['private void restoreDraft(','private static Models.Page strictCheckpointPage(','private static EditRecoveryPolicy.Identity identity(']
sigs += [m.group(0).split('{')[0].rstrip() for m in re.finditer(r'private void openEditor\([^\n]*?\)\s*\{',s)]
methods='\n'.join(block(x) for x in dict.fromkeys(sigs))
java=r'''package org.wangshu.scan;
import java.io.*;import java.util.*;
class Models {
 static final Map<String,Page> decoded=new HashMap<>();
 static class Document {
  String id="doc",savedSnapshot="v1";List<Page> pages=new ArrayList<>();
  List<Page> active(){ArrayList<Page> active=new ArrayList<>();for(Page p:pages)if(!p.removed)active.add(p);return active;}
 }
 static class Page {
  String id="page",file="originals/page.image",sha256="sha",mode="COLOR",cropReview="saved",ocrText="",ocrKey="",ocrLanguage="";
  boolean shadows,removed,copyFails;int brightness,rotation;double[] quad={0,0,1,0,1,1,0,1};
  org.json.JSONObject json(){
   String key=id+"|"+file+"|"+sha256+"|"+mode+"|"+shadows+"|"+removed+"|"+brightness+"|"+rotation+"|"+cropReview+"|"+Arrays.toString(quad)+"|"+ocrText+"|"+ocrKey+"|"+ocrLanguage;
   decoded.put(key,this);return new org.json.JSONObject(key);
  }
  static Page from(org.json.JSONObject j)throws IOException{Page p=decoded.get(j.toString());if(p==null)throw new IOException("unknown synthetic encoding");return p;}
  Page copy()throws IOException{if(copyFails)throw new IOException("injected model copy failure");Page q=new Page();q.id=id;q.file=file;q.sha256=sha256;q.mode=mode;q.cropReview=cropReview;q.shadows=shadows;q.removed=removed;q.brightness=brightness;q.rotation=rotation;q.quad=quad.clone();q.ocrText=ocrText;q.ocrKey=ocrKey;q.ocrLanguage=ocrLanguage;return q;}
  boolean cropConfirmed(){return cropReview.equals("reviewed");}
 }
}
public class DraftUiReview {
 Models.Document document=new Models.Document();Models.Page originalPage,draft;
 String originalPageBaseline,editorDocumentVersion;Object editorSession,recovery;
 int screen,builds,previews,cancels,errors,toasts,holds;String renderedMode;boolean renderedShadows,renderedReview;double[] renderedQuad;int renderedRotation,renderedBrightness;
 void cancelPendingCapture(){cancels++;}void error(Throwable e){errors++;}void toast(String s){toasts++;}
 void showRecovery(String s){holds++;}void requestPreview(){previews++;}
 void buildEditor(){builds++;renderedMode=draft.mode;renderedShadows=draft.shadows;renderedReview=draft.cropConfirmed();renderedQuad=draft.quad.clone();renderedRotation=draft.rotation;renderedBrightness=draft.brightness;requestPreview();}
 __METHODS__
 static int assertions,scenarios;static void check(boolean b,String why){assertions++;if(!b)throw new AssertionError(why);}
 interface Case{void run()throws Exception;}static void run(String name,Case c)throws Exception{c.run();scenarios++;System.out.println("PASS "+name);}
 static Models.Page changed(){Models.Page p=new Models.Page();p.mode="BW";p.shadows=true;p.brightness=17;p.rotation=2;p.cropReview="reviewed";p.quad=new double[]{.1,.2,.8,.2,.8,.9,.1,.9};return p;}
 static DraftUiReview setup(Models.Page saved){DraftUiReview a=new DraftUiReview();a.document.pages.add(saved);return a;}
 static void restore(DraftUiReview a,Models.Page p,String baseline,String version,boolean submitted)throws Exception{
  String encoded=p.json().toString();a.restoreDraft(encoded,baseline,version,submitted);
 }
 static void reject(DraftUiReview a,Models.Page edited,String baseline,String version)throws Exception{
  try{restore(a,edited,baseline,version,false);throw new AssertionError("accepted invalid recovery");}catch(Exception expected){}
  check(a.builds==0&&a.previews==0&&a.originalPage==null&&a.draft==null,"no editor published");
 }
 public static void main(String[] args)throws Exception{
 run("four argument restore uses real BASELINE policy and restored controls",()->{
  Models.Page saved=new Models.Page(),p=changed();DraftUiReview a=setup(saved);String baseline=saved.json().toString();
  restore(a,p,baseline,"v1",false);check(a.renderedMode.equals("BW")&&a.renderedShadows&&a.renderedReview,"restored controls");
  check(a.builds==1&&a.previews==1,"one build/preview");check(Arrays.equals(a.renderedQuad,p.quad)&&a.renderedRotation==2&&a.renderedBrightness==17,"transform");
  check(a.originalPage==saved&&a.originalPageBaseline.equals(baseline),"current baseline retained");
  check(a.editorSession!=null&&a.editorDocumentVersion.equals("v1")&&a.screen==2,"real editor session/version wired");
 });
 run("restored OCR correction retains literal text and stale recognition provenance",()->{
  Models.Page saved=new Models.Page(),p=changed();p.ocrText="中文纠错\nE=mc²\n𠮷 😀  ";p.ocrKey="stale-key";p.ocrLanguage="chi_sim+eng";
  DraftUiReview a=setup(saved);restore(a,p,saved.json().toString(),"v1",false);
  check(a.draft.ocrText.equals(p.ocrText)&&a.draft.ocrKey.equals("stale-key")&&a.draft.ocrLanguage.equals("chi_sim+eng"),"OCR fields survive actual restore/open seam");
  check(saved.ocrText.isEmpty()&&saved.ocrKey.isEmpty(),"saved page never overwritten by restoring");
  a.draft.ocrText="continued correction";check(!p.ocrText.equals(a.draft.ocrText),"restored OCR edit independent");
 });
 run("normal editor keeps saved controls and independent copy",()->{
  Models.Page saved=changed();DraftUiReview a=setup(saved);a.openEditor(saved);check(a.builds==1&&a.previews==1&&a.cancels==1,"normal build");
  a.draft.quad[0]=.99;a.draft.mode="GRAY";check(saved.quad[0]==.1&&saved.mode.equals("BW"),"saved object unchanged");
 });
 run("parsed edit remains independent",()->{
  Models.Page saved=new Models.Page(),p=changed();DraftUiReview a=setup(saved);restore(a,p,saved.json().toString(),"v1",false);
  check(a.draft!=p,"defensive copy");p.quad[0]=.99;p.mode="GRAY";check(a.draft.quad[0]==.1&&a.draft.mode.equals("BW"),"no parsed alias");
 });
 for(String issue:List.of("baseline-mismatch","baseline-null","version-mismatch","version-null","version-empty","saved-removed","missing-page","path-mismatch","sha-mismatch"))run(issue+" rejects before editor publication",()->{
  Models.Page saved=new Models.Page(),p=changed();DraftUiReview a=setup(saved);String b=saved.json().toString(),v="v1";
  switch(issue){case "baseline-mismatch":b=changed().json().toString();break;case "baseline-null":b=null;break;case "version-mismatch":v="v2";break;case "version-null":v=null;break;case "version-empty":v="";break;case "saved-removed":saved.removed=true;break;case "missing-page":p.id="unknown";break;case "path-mismatch":p.file="other";break;case "sha-mismatch":p.sha256="other";break;}
  reject(a,p,b,v);
 });
 run("submitted contents already saved opens current without replay",()->{
  Models.Page old=new Models.Page(),current=changed();DraftUiReview a=setup(current);a.document.savedSnapshot="v2";
  restore(a,current.copy(),old.json().toString(),"v1",true);
  check(a.originalPage==current&&a.draft!=current&&a.toasts==1,"current version acknowledged");
  check(a.editorDocumentVersion.equals("v2")&&a.builds==1,"uses current saved version");
 });
 run("unsubmitted content equality does not acknowledge old save",()->{
  Models.Page old=new Models.Page(),current=changed();DraftUiReview a=setup(current);a.document.savedSnapshot="v2";reject(a,current.copy(),old.json().toString(),"v1");
 });
 run("malformed synthetic JSON throws to caller",()->{
  DraftUiReview a=setup(new Models.Page());try{a.restoreDraft("MALFORMED","anything","v1",false);throw new AssertionError("accepted malformed");}catch(IllegalArgumentException expected){}
  check(a.builds==0&&a.draft==null,"parse rejects without editor");
 });
 run("held recovery blocks editor publication",()->{
  Models.Page p=new Models.Page();DraftUiReview a=setup(p);a.recovery=new Object();a.openEditor(p);
  check(a.holds==1&&a.builds==0&&a.draft==null,"existing hold not bypassed");
 });
 run("normal copy failure preserves old editor state",()->{
  Models.Page old=changed(),bad=new Models.Page();DraftUiReview a=setup(old);a.openEditor(old);Models.Page previous=a.draft;Object session=a.editorSession;int builds=a.builds;bad.copyFails=true;a.openEditor(bad);
  check(a.errors==1&&a.builds==builds&&a.draft==previous&&a.originalPage==old&&a.editorSession==session,"no partial publication");
 });
 System.out.println("SCENARIOS "+scenarios+" ASSERTIONS "+assertions+" FAILED 0");
 }
}
'''.replace('__METHODS__',methods)
work=OUTPUT/'draft-ui';work.mkdir();(work/'org/json').mkdir(parents=True)
(work/'DraftUiReview.java').write_text(java,encoding='utf-8')
(work/'org/json/JSONObject.java').write_text('package org.json;public class JSONObject {private final String text;public JSONObject(String text){if(text==null||text.equals("MALFORMED"))throw new IllegalArgumentException("malformed synthetic JSON");this.text=text;}public String toString(){return text;}}',encoding='utf-8')
policy=PROJECT/'app/src/main/java/org/wangshu/scan/EditRecoveryPolicy.java'
c=subprocess.run([JAVA,'-m','jdk.compiler/com.sun.tools.javac.Main','--release','17','-encoding','UTF-8','-d',str(work/'classes'),str(work/'DraftUiReview.java'),str(work/'org/json/JSONObject.java'),str(policy)],capture_output=True,text=True,encoding='utf-8',timeout=60)
p=subprocess.run([JAVA,'-cp',str(work/'classes'),'org.wangshu.scan.DraftUiReview'],capture_output=True,text=True,encoding='utf-8',timeout=30) if c.returncode==0 else None
receipt={'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'production_policy_sha256':hashlib.sha256(policy.read_bytes()).hexdigest(),'methods_sha256':hashlib.sha256(methods.encode()).hexdigest(),'compile_exit':c.returncode,'run_exit':p.returncode if p else None,'stdout':p.stdout if p else c.stdout,'stderr':p.stderr if p else c.stderr,'scope':__doc__}
(work/'result.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(receipt,ensure_ascii=False,indent=2))
raise SystemExit(p.returncode if p else c.returncode)

