#!/usr/bin/env python3
"""Extract real restoreDraft/openEditor; synthetic JSON/models/render observations only."""
from pathlib import Path
from support import PROJECT, OUTPUT, JAVA
import hashlib,json,subprocess,sys,uuid,re
root=OUTPUT
source=PROJECT/'app/src/main/java/org/wangshu/scan/MainActivity.java'; s=source.read_text()
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
 raise ValueError('Unclosed method')
sigs=['private void restoreDraft(','private void openEditor(Models.Page p)']
# Include all additional overloads exactly from production.
for m in re.finditer(r'private void openEditor\([^\n]*?\)\s*\{',s):
 sig=m.group(0).split('{')[0].rstrip()
 if sig not in sigs:sigs.append(sig)
methods='\n'.join(dict.fromkeys(block(x) for x in sigs))
java=r'''import java.io.*;import java.util.*;
class Models {
 static final Map<String,Page> decoded=new HashMap<>();
 static class Document {List<Page> pages=new ArrayList<>();}
 static class Page {
  String id="page",file="originals/page.image",sha256="sha",mode="COLOR",cropReview="saved";boolean shadows,removed,copyFails;int brightness,rotation;double[] quad={0,0,1,0,1,1,0,1};
  org.json.JSONObject json(){return new org.json.JSONObject(id+"|"+file+"|"+sha256+"|"+mode+"|"+shadows+"|"+removed+"|"+brightness+"|"+rotation+"|"+cropReview+"|"+Arrays.toString(quad));}
  static Page from(org.json.JSONObject j)throws IOException{Page p=decoded.get(j.toString());if(p==null)throw new IOException("unknown encoded model");return p;}
  Page copy()throws IOException{if(copyFails)throw new IOException("copy failed");Page q=new Page();q.id=id;q.file=file;q.sha256=sha256;q.mode=mode;q.cropReview=cropReview;q.shadows=shadows;q.removed=removed;q.brightness=brightness;q.rotation=rotation;q.quad=quad.clone();return q;}
  boolean cropConfirmed(){return cropReview.equals("reviewed");}
 }
}
public class DraftUiReview {
 Models.Document document=new Models.Document();Models.Page originalPage,draft;String originalPageBaseline;int screen,builds,previews,cancels,errors,toasts;String renderedMode;boolean renderedShadows,renderedReview;double[] renderedQuad;int renderedRotation,renderedBrightness;
 void cancelPendingCapture(){cancels++;}void error(Throwable e){errors++;}void toast(String s){toasts++;}
 void requestPreview(){previews++;}
 void buildEditor(){builds++;renderedMode=draft.mode;renderedShadows=draft.shadows;renderedReview=draft.cropConfirmed();renderedQuad=draft.quad.clone();renderedRotation=draft.rotation;renderedBrightness=draft.brightness;requestPreview();}
 __METHODS__
 static int assertions,scenarios,failed;static void check(boolean b,String why){assertions++;if(!b)throw new AssertionError(why);}
 interface Case{void run()throws Exception;}
 static void run(String name,Case c){scenarios++;try{c.run();System.out.println("PASS "+name);}catch(Throwable e){failed++;System.out.println("FAIL "+name+": "+e);}}
 static Models.Page changed()throws Exception{Models.Page p=new Models.Page();p.mode="BW";p.shadows=true;p.brightness=17;p.rotation=2;p.cropReview="reviewed";p.quad=new double[]{.1,.2,.8,.2,.8,.9,.1,.9};return p;}
 static DraftUiReview setup(Models.Page saved){DraftUiReview a=new DraftUiReview();a.document.pages.add(saved);return a;}
 static void restore(DraftUiReview a,Models.Page p,String baseline){Models.decoded.put("encoded",p);a.restoreDraft("encoded",baseline);}
 static void rejected(String label,Case c){run(label,c);}
 public static void main(String[]args)throws Exception {
 run("restored controls follow BW/shadows/crop draft, one render",()->{Models.Page saved=new Models.Page(),p=changed();DraftUiReview a=setup(saved);String baseline=saved.json().toString();restore(a,p,baseline);System.out.println("OBSERVED mode="+a.renderedMode+" shadows="+a.renderedShadows+" cropReview="+a.renderedReview+" builds="+a.builds+" previews="+a.previews);check(a.renderedMode.equals("BW"),"mode from restored draft");check(a.builds==1,"exactly one build");check(a.previews==1,"exactly one preview request");check(a.renderedShadows,"shadows from draft");check(a.renderedReview,"review from draft");check(Arrays.equals(a.renderedQuad,p.quad),"quad from draft");check(a.renderedBrightness==17&&a.renderedRotation==2,"transform parameters from draft");check(a.originalPage==saved&&a.originalPageBaseline.equals(baseline),"save baseline remains saved page");check(a.screen==2,"editor screen");});
 run("normal editor remains saved controls, independent draft copy",()->{Models.Page saved=changed();DraftUiReview a=setup(saved);a.openEditor(saved);check(a.builds==1&&a.previews==1&&a.cancels==1,"normal one build");check(a.renderedMode.equals(saved.mode)&&a.renderedReview==saved.cropConfirmed(),"normal controls");check(a.originalPage==saved&&a.originalPageBaseline.equals(saved.json().toString()),"normal baseline");a.draft.quad[0]=.99;a.draft.mode="GRAY";check(saved.quad[0]==.1&&saved.mode.equals("BW"),"saved page independent");});
 run("restored draft is independent of parsed source object",()->{Models.Page saved=new Models.Page(),p=changed();DraftUiReview a=setup(saved);restore(a,p,saved.json().toString());check(a.draft!=p,"defensive copy");p.quad[0]=.99;p.mode="GRAY";check(a.draft.quad[0]==.1&&a.draft.mode.equals("BW"),"parsed object cannot mutate active draft");a.draft.shadows=false;check(p.shadows,"draft cannot mutate parsed object");});
 for(String issue:List.of("baseline-mismatch","baseline-null","saved-removed","draft-removed","missing-page","path-mismatch","sha-mismatch"))run(issue+" rejects with no editor publication",()->{Models.Page saved=new Models.Page(),p=changed();DraftUiReview a=setup(saved);String b=saved.json().toString();switch(issue){case "baseline-mismatch":b="other";break;case "baseline-null":b=null;break;case "saved-removed":saved.removed=true;break;case "draft-removed":p.removed=true;break;case "missing-page":p.id="unknown";break;case "path-mismatch":p.file="other";break;case "sha-mismatch":p.sha256="other";break;}restore(a,p,b);check(a.builds==0&&a.previews==0,"no UI or preview");check(a.originalPage==null&&a.draft==null&&a.originalPageBaseline==null,"no partial editor state");check(a.toasts==1,"visible rejection");});
 run("malformed JSON rejects before any editor change",()->{DraftUiReview a=setup(new Models.Page());a.restoreDraft("MALFORMED","anything");check(a.builds==0&&a.draft==null&&a.toasts==1,"parse rejection");});
 run("normal copy failure does not partially publish owner or baseline",()->{Models.Page old=changed(),bad=new Models.Page();DraftUiReview a=setup(old);a.openEditor(old);Models.Page previousDraft=a.draft;String previousBaseline=a.originalPageBaseline;int count=a.builds;bad.copyFails=true;a.openEditor(bad);check(a.errors==1,"copy error reported");check(a.builds==count&&a.draft==previousDraft&&a.originalPage==old&&a.originalPageBaseline.equals(previousBaseline),"old state preserved on failed preparation");});
 System.out.println("SCENARIOS "+scenarios+" ASSERTIONS "+assertions+" FAILED "+failed);if(failed!=0)System.exit(1);
 }
}
'''.replace('__METHODS__',methods)
run=root/('run-'+str(uuid.uuid4()));run.mkdir();(run/'DraftUiReview.java').write_text(java);(run/'org/json').mkdir(parents=True);(run/'org/json/JSONObject.java').write_text('package org.json; public class JSONObject { private final String text; public JSONObject(String s){if(s.equals("MALFORMED"))throw new IllegalArgumentException("malformed JSON");text=s;} public String toString(){return text;} }\n')
c=subprocess.run([JAVA,'-m','jdk.compiler/com.sun.tools.javac.Main','-d',str(run/'classes'),str(run/'DraftUiReview.java'),str(run/'org/json/JSONObject.java')],capture_output=True,text=True,timeout=40)
r=subprocess.run([JAVA,'-cp',str(run/'classes'),'DraftUiReview'],capture_output=True,text=True,timeout=20) if not c.returncode else None
receipt={'source_path':str(source),'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'extracted_sha256':hashlib.sha256(methods.encode()).hexdigest(),'compile_exit':c.returncode,'run_exit':r.returncode if r else None,'stdout':r.stdout if r else c.stdout,'stderr':r.stderr if r else c.stderr,'scope':'actual extracted restoreDraft/openEditor overloads; model, JSON and rendering are synthetic. No Android lifecycle/UI/device execution.'}
(run/'result.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+'\n');print(json.dumps(receipt,ensure_ascii=False,indent=2));print('RESULT',run/'result.json');sys.exit(r.returncode if r else c.returncode)
