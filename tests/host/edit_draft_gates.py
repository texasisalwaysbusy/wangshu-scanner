from pathlib import Path
from support import PROJECT, OUTPUT, JAVA
import hashlib,json,subprocess,uuid
root=OUTPUT;src=PROJECT/'app/src/main/java/org/wangshu/scan/MainActivity.java';s=src.read_text();a=s.index('    private static Models.Page strictCheckpointPage(');b=s.index('    private void checkpointEditor()',a);methods=s[a:b].replace('private static','static')
r=root/('gates-'+str(uuid.uuid4()));r.mkdir();(r/'org/json').mkdir(parents=True)
(r/'org/json/JSONObject.java').write_text('package org.json; public class JSONObject {public final String encoded;public JSONObject(String s){encoded=s;}public String toString(){return encoded;}}')
java=r'''import java.io.*;import java.util.*;
class EditDraftStore {static class Draft{String document,baseline,edited;Draft(String d,String b,String e){document=d;baseline=b;edited=e;}}}
class Models {
 static Map<String,Page> decoded=new HashMap<>();
 static class Page {String id="p",file="originals/p",sha256="hash",serialized;boolean removed;Page(String s){serialized=s;}org.json.JSONObject json(){return new org.json.JSONObject(serialized);}static Page from(org.json.JSONObject j)throws IOException{Page p=decoded.get(j.encoded);if(p==null)throw new IOException("malformed JSON");return p;}}
 static class Document{String id="doc";boolean archived,manifestUncertain;List<Page> pages=new ArrayList<>();}
}
public class Gates {
METHODS
 static int n;interface Op{void go()throws Exception;}static void check(boolean b){if(!b)throw new AssertionError();}static void reject(Op o)throws Exception{boolean failed=false;try{o.go();}catch(Exception e){failed=true;}check(failed);n++;}
 public static void main(String[] args)throws Exception{
 Models.Page old=new Models.Page("base"),edited=new Models.Page("edit");Models.decoded.put("base",old);Models.decoded.put("edit",edited);Models.Document d=new Models.Document();d.pages.add(old);EditDraftStore.Draft c=new EditDraftStore.Draft("doc","base","edit");
 check(matchCheckpoint(d,c)==edited);check(d.pages.get(0)==old&&old.serialized.equals("base"));n++;
 c.document="wrong";reject(()->matchCheckpoint(d,c));c.document="doc";
 d.archived=true;reject(()->matchCheckpoint(d,c));d.archived=false;d.manifestUncertain=true;reject(()->matchCheckpoint(d,c));d.manifestUncertain=false;
 edited.removed=true;reject(()->matchCheckpoint(d,c));edited.removed=false;old.removed=true;reject(()->matchCheckpoint(d,c));old.removed=false;
 edited.id="other";reject(()->matchCheckpoint(d,c));edited.id="p";edited.file="different";reject(()->matchCheckpoint(d,c));edited.file="originals/p";edited.sha256="changed";reject(()->matchCheckpoint(d,c));edited.sha256="hash";
 Models.Page changed=new Models.Page("changed baseline");d.pages.set(0,changed);reject(()->matchCheckpoint(d,c));changed.removed=true;reject(()->matchCheckpoint(d,c));d.pages.clear();reject(()->matchCheckpoint(d,c));d.pages.add(old);
 c.edited="malformed";reject(()->matchCheckpoint(d,c));c.edited="edit";
 edited.serialized="normalized edit";reject(()->matchCheckpoint(d,c));edited.serialized="edit";old.serialized="normalized base";reject(()->matchCheckpoint(d,c));old.serialized="base";
 check(d.pages.size()==1&&d.pages.get(0)==old&&old.serialized.equals("base")&&edited.serialized.equals("edit"));n++;
 System.out.println("PASS "+n+" gate scenarios; actual extracted methods, synthetic JSON/Models; no Store/save dependency compiled");
 }
}'''.replace('METHODS',methods)
(r/'Gates.java').write_text(java)
c=subprocess.run([JAVA,'-m','jdk.compiler/com.sun.tools.javac.Main','-d',str(r/'classes'),str(r/'Gates.java'),str(r/'org/json/JSONObject.java')],capture_output=True,text=True,timeout=40);result={'source_sha256':hashlib.sha256(src.read_bytes()).hexdigest(),'methods_sha256':hashlib.sha256(methods.encode()).hexdigest(),'compile_exit':c.returncode,'compile_stderr':c.stderr}
if c.returncode==0:
 p=subprocess.run([JAVA,'-cp',str(r/'classes'),'Gates'],capture_output=True,text=True,timeout=15);result.update(run_exit=p.returncode,stdout=p.stdout,stderr=p.stderr)
(r/'result.json').write_text(json.dumps(result,indent=2));print(r);print(json.dumps(result,indent=2))

raise SystemExit(0 if result.get("compile_exit")==0 and result.get("run_exit")==0 else 1)
