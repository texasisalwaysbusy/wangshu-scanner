from pathlib import Path
from support import PROJECT, OUTPUT, JAVA
import hashlib,json,subprocess,uuid
root=OUTPUT;base=PROJECT/'app/src/main/java/org/wangshu/scan';r=root/('cross-'+str(uuid.uuid4()));r.mkdir()
for name in ['EditDraftStore.java','SaveJobStore.java']:(r/name).write_bytes((base/name).read_bytes())
(r/'Context.java').write_text('package android.content;public abstract class Context{public abstract java.io.File getCacheDir();public abstract java.io.File getFilesDir();}')
(r/'AtomicFile.java').write_text('package android.util;import java.io.*;public class AtomicFile{public AtomicFile(File f){}public FileInputStream openRead()throws IOException{throw new IOException("unused stub");}public FileOutputStream startWrite()throws IOException{throw new IOException("unused stub");}public void finishWrite(FileOutputStream f){throw new AssertionError("unused stub");}public void failWrite(FileOutputStream f){throw new AssertionError("unused stub");}}')
(r/'Cross.java').write_text(r'''package org.wangshu.scan;
import java.io.*;import java.nio.file.*;import java.util.*;
public class Cross {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static class Mem implements SaveJobStore.Persistence {byte[] data;int writes;public byte[] read(){return data==null?null:data.clone();}public void write(byte[] b){data=b.clone();writes++;}}
 static class DiskSource implements SaveJobStore.Sources {public boolean isFile(File f){return f.isFile();}public long length(File f){return f.length();}public SaveJobStore.SourceHandle open(File f)throws IOException{FileInputStream in=new FileInputStream(f);return new SaveJobStore.SourceHandle(){public InputStream stream(){return in;}public void rewind()throws IOException{in.getChannel().position(0);}public void close()throws IOException{in.close();}};}}
 static void blocked(SaveJobStore jobs,EditDraftStore.Export out,Mem mem)throws Exception{byte[] before=mem.data.clone();int writes=mem.writes;String token=jobs.read().token;boolean rejected=false;try{jobs.begin(out.file,"application/octet-stream","second.bin");}catch(IOException e){rejected=true;}check(rejected,"old job replacement accepted");check(Arrays.equals(before,mem.data)&&writes==mem.writes&&jobs.read().token.equals(token),"old receipt changed");}
 public static void main(String[] args)throws Exception{
 Path base=Path.of(args[0]),files=base.resolve("files"),cache=base.resolve("cache");Files.createDirectories(files);Files.createDirectories(cache);
 String owner="00000000-0000-0000-0000-000000000001";EditDraftStore drafts=new EditDraftStore(files.toFile());EditDraftStore.Entry entry=drafts.save(owner,"SYNTHETIC_BASELINE","SYNTHETIC_EDIT");EditDraftStore.Export out=drafts.export(owner,entry,cache.toFile());byte[] original=Files.readAllBytes(files.resolve("edit-drafts").resolve(owner).resolve(entry.token+".draft"));
 Mem mem=new Mem();SaveJobStore jobs=new SaveJobStore(cache.toFile(),files.toFile(),mem,new DiskSource());SaveJobStore.Job job=jobs.begin(out.file,"application/octet-stream","checkpoint.bin");check(job.mime.equals("application/octet-stream")&&job.bytes==out.bytes&&job.sha.equals(out.digest)&&out.digest.equals(entry.digest),"cross-component metadata mismatch");check(job.phase==SaveJobStore.REQUESTED,"wrong initial phase");blocked(jobs,out,mem);
 jobs.picker(job.token);blocked(jobs,out,mem);jobs.chosen(job.token);blocked(jobs,out,mem);
 try(SaveJobStore.Claim claim=jobs.claim(job.token)){check(Arrays.equals(original,claim.input.readAllBytes()),"claim bytes differ from original checkpoint");blocked(jobs,out,mem);jobs.done(job.token);}
 check(jobs.read().phase==SaveJobStore.DONE,"not terminal");check(Arrays.equals(original,Files.readAllBytes(out.file.toPath())),"export source modified");check(Arrays.equals(original,Files.readAllBytes(files.resolve("edit-drafts").resolve(owner).resolve(entry.token+".draft"))),"private checkpoint modified");
 System.out.println("PASS actual save -> export(.bin) -> SaveJobStore.begin -> picker/chosen -> claim full byte verification -> done; old active job blocked unchanged in REQUESTED/PICKER/CHOSEN/WRITING; original and export bytes unchanged");
 }
}''')
c=subprocess.run([JAVA,'-m','jdk.compiler/com.sun.tools.javac.Main','-d',str(r/'classes'),*[str(p) for p in r.glob('*.java')]],capture_output=True,text=True,timeout=40)
result={'sources':{n:hashlib.sha256((r/n).read_bytes()).hexdigest() for n in ['EditDraftStore.java','SaveJobStore.java']},'compile_exit':c.returncode,'compile_stderr':c.stderr,'scope':'actual product classes, real checkpoint/export/source files; fake in-memory receipt persistence and unused Android stubs; no SAF/chooser/device'}
if c.returncode==0:
 p=subprocess.run([JAVA,'-cp',str(r/'classes'),'org.wangshu.scan.Cross',str(r/'fixtures')],capture_output=True,text=True,timeout=20);result.update(run_exit=p.returncode,stdout=p.stdout,stderr=p.stderr)
(r/'result.json').write_text(json.dumps(result,indent=2));print(r);print(json.dumps(result,indent=2))

raise SystemExit(0 if result.get("compile_exit")==0 and result.get("run_exit")==0 else 1)
