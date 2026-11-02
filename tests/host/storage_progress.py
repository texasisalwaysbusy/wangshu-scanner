#!/usr/bin/env python3
"""Execute extracted restore with real host ZIP/files plus explicit failure seams.

Model/JSON/Bitmap/JPEG/save are minimal fakes. File.getUsableSpace is simulated;
stream writes and Files.copy may throw injected ENOSPC or corrupt a copy; force
may throw injected sync failure. This does NOT fill the disk or emulate Android.
Every generated source, fixture and result is retained under a unique run folder.
"""
from pathlib import Path
from support import PROJECT, OUTPUT, JAVA
import hashlib,json,subprocess,sys,uuid
root=PROJECT;candidate=True
source=(root/'app/src/main/java/org/wangshu/scan/Store.java').read_text()
method=source[source.index('    synchronized Models.Document restore('):source.index('    private static void removeTemporary(')]
validator=(root/'app/src/main/java/org/wangshu/scan/StrictJsonValidator.java').read_text()
validator='\n'.join(x for x in validator.splitlines() if not x.startswith(('package ','import '))).replace('final class StrictJsonValidator {','static final class StrictJsonValidator {',1)
instrumented=method
if candidate:
 assert instrumented.count('channel.force(true)')==1 and instrumented.count('channel.write(chunk)')==1
 instrumented=instrumented.replace('channel.force(true)','Faults.force(channel,destination)',1).replace('channel.write(chunk)','Faults.write(channel,chunk)',1)
else:
 assert instrumented.count('java.nio.file.Files.copy(')==1
 instrumented=instrumented.replace('java.nio.file.Files.copy(','Faults.copy(',1)
java=r'''import java.io.*;import java.nio.*;import java.nio.charset.*;import java.nio.file.*;import java.nio.channels.*;import java.util.*;import java.util.zip.*;import java.security.*;
public class StorageRegression {
 static final long RESERVE=32L*1024*1024;
 static File base;static Path fixtureRoot;static int caseNo,saved,readBytes,forceCalls,zeroCalls,ioCalls;static String fault="",expected="";static long capacity=Long.MAX_VALUE;static boolean candidate;
 static class File extends java.io.File {
  File(String s){super(s);}File(java.io.File p,String s){super(p,s);}
  public long getUsableSpace(){return capacity==Long.MAX_VALUE?super.getUsableSpace():Math.max(0,capacity-bytes(base.toPath()));}
 }
 static long bytes(Path dir){try(var paths=Files.walk(dir)){return paths.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).mapToLong(p->{try{return Files.size(p);}catch(IOException e){throw new UncheckedIOException(e);}}).sum();}catch(IOException e){throw new UncheckedIOException(e);}}
 static class FileOutputStream extends java.io.FileOutputStream {
  final File f;boolean once;
  FileOutputStream(File file)throws IOException{super(file);f=file;}
  public void write(byte[] b,int o,int n)throws IOException{
   boolean selected=(fault.equals("raw-write")&&f.getName().equals("received.zip"))||(fault.equals("extract-write")&&f.getPath().contains("restore-intake")&&f.getPath().contains("originals"));
   if(selected&&!once){once=true;super.write(b,o,Math.max(1,n/2));throw new FileSystemException(f.toString(),null,"No space left on device [injected]");}
   super.write(b,o,n);
  }
 }
 static class Faults {
  static Path copy(Path from,Path to)throws IOException {
   if(fault.equals("copy-write")){byte[] b=Files.readAllBytes(from);Files.write(to,Arrays.copyOf(b,17),StandardOpenOption.CREATE_NEW);throw new FileSystemException(to.toString(),null,"No space left on device [injected]");}
   Path p=Files.copy(from,to);
   if(fault.equals("copy-integrity")){byte[] b=Files.readAllBytes(to);b[11]^=1;Files.write(to,b);}
   return p;
  }
  static int write(FileChannel channel,ByteBuffer chunk)throws IOException {if(fault.equals("copy-transient-zero")&&++zeroCalls==1)return 0;if(fault.equals("copy-partial-zero")){ioCalls++;if(ioCalls==1){int limit=chunk.limit();chunk.limit(chunk.position()+16);int n=channel.write(chunk);chunk.limit(limit);return n;}if(++zeroCalls>5)throw new IOException("test-only bailout");return 0;}if(fault.equals("copy-zero-write")){if(++zeroCalls>5)throw new IOException("test-only bailout: progress guard absent");return 0;}if(fault.equals("copy-write")){chunk.limit(Math.min(chunk.limit(),chunk.position()+17));channel.write(chunk);throw new IOException("No space left on device [injected]");}if(fault.equals("copy-short-write")){int limit=chunk.limit();chunk.limit(Math.min(limit,chunk.position()+4096));int n=channel.write(chunk);chunk.limit(limit);return n;}int n=channel.write(chunk);if(fault.equals("copy-space-drop"))capacity=bytes(base.toPath())+RESERVE;if(fault.equals("copy-interrupt"))Thread.currentThread().interrupt();return n;}
  static void force(FileChannel channel,File destination)throws IOException {forceCalls++;if(fault.equals("copy-sync"))throw new IOException("sync failure [injected]");channel.force(true);if(fault.equals("copy-integrity")){byte[] b=Files.readAllBytes(destination.toPath());b[11]^=1;Files.write(destination.toPath(),b);}}
 }
 static class Context {File getFilesDir(){return base;}}final Context context=new Context();
 static class JSONObject {JSONObject(String s){}}
 static class Models {static class Page {String id="11111111-1111-1111-1111-111111111111",file="originals/"+id+".image",sha256=expected;}static class Document {String id,title="doc";boolean archived;List<Page> pages=new ArrayList<>();static Document from(JSONObject o){Document d=new Document();d.pages.add(new Page());return d;}}}
 static class ManifestFence {static class Uncertain extends IOException {}}
 static class CameraInbox {static void requireCompleteIfJpeg(File f)throws IOException{byte[] b=Files.readAllBytes(f.toPath());if(b.length<4||b[b.length-1]!=(byte)217)throw new IOException("fake JPEG guard");}}
 static class BitmapFactory {static class Options {boolean inJustDecodeBounds;int outWidth=10,outHeight=10;}static void decodeFile(String path,Options o){}}
 static byte[] readLimited(InputStream in,int max)throws IOException{byte[] b=in.readNBytes(max+1);if(b.length>max)throw new IOException("too large");return b;}
 static void safeId(String s)throws IOException{if(!s.matches("[a-f0-9-]{36}"))throw new IOException("bad id");}
 static String hex(byte[] b){return HexFormat.of().formatHex(b);}
 File dir(Models.Document d)throws IOException{File f=new File(base,"documents/"+d.id);if(!f.isDirectory()&&!f.mkdirs())throw new IOException("mkdir");return f;}
 void save(Models.Document d)throws IOException{Files.writeString(new File(dir(d),"manifest.stub").toPath(),"saved");saved++;}
''' + validator+instrumented+r'''
 static byte[] zip(byte[] image)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();try(ZipOutputStream z=new ZipOutputStream(b)){for(String name:List.of("manifest.json","originals/11111111-1111-1111-1111-111111111111.image")){byte[] payload=name.equals("manifest.json")?new byte[]{123,125}:image;ZipEntry e=new ZipEntry(name);e.setMethod(ZipEntry.STORED);e.setSize(payload.length);CRC32 crc=new CRC32();crc.update(payload);e.setCrc(crc.getValue());z.putNextEntry(e);z.write(payload);z.closeEntry();}}return b.toByteArray();}
 static void reset(String label,byte[] image)throws Exception{base=new File(fixtureRoot.resolve((++caseNo)+"-"+label).toString());if(!base.mkdirs())throw new IOException("mkdir");Files.createDirectories(base.toPath().resolve("documents/existing"));Files.writeString(base.toPath().resolve("documents/existing/untouched"),"existing document sentinel");saved=readBytes=forceCalls=zeroCalls=ioCalls=0;fault="";capacity=Long.MAX_VALUE;expected=hex(MessageDigest.getInstance("SHA-256").digest(image));}
 static InputStream input(byte[] b){return new ByteArrayInputStream(b){public synchronized int read(byte[] p,int off,int len){if(fault.equals("raw-prefix-zero")){ioCalls++;if(ioCalls==1){int n=super.read(p,off,Math.min(16,len));readBytes+=n;return n;}if(++zeroCalls<=5)return 0;}if(fault.equals("raw-zero-read")&&++zeroCalls<=5)return 0;int n=super.read(p,off,len);if(n>0)readBytes+=n;return n;}};}
 static java.io.File[] stages(){java.io.File[] x=new File(base,"restore-intake").listFiles();return x==null?new java.io.File[0]:x;}
 static boolean raw(){return stages().length==1&&new java.io.File(stages()[0],"received.zip").isFile();}
 static int newDocuments(){java.io.File[] x=new File(base,"documents").listFiles();return (x==null?0:x.length)-1;}
 static void ok(boolean v,String m){if(!v)throw new AssertionError(m);System.out.println("PASS "+m);}
 static void untouched()throws Exception{ok(Files.readString(base.toPath().resolve("documents/existing/untouched")).equals("existing document sentinel"),"existing document bytes unchanged");}
 static Exception attempt(StorageRegression s,byte[] z){try{s.restore(input(z));return null;}catch(Exception e){System.out.println("OBSERVED "+e.getClass().getSimpleName()+": "+e.getMessage());return e;}}
 public static void main(String[] args)throws Exception {
  fixtureRoot=Paths.get(args[0]);candidate=Boolean.parseBoolean(args[1]);StorageRegression s=new StorageRegression();byte[] image=new byte[131072];new Random(7).nextBytes(image);image[0]=(byte)255;image[1]=(byte)216;image[image.length-2]=(byte)255;image[image.length-1]=(byte)217;byte[] archive=zip(image);
  reset("duplicates",image);Models.Document a=s.restore(input(archive)),b=s.restore(input(archive));ok(saved==2&&stages().length==2&&newDocuments()==2&&!a.id.equals(b.id),"KNOWN GAP same ZIP creates two new documents and two retained intakes");long once=archive.length+image.length+2+image.length+5;ok(bytes(base.toPath())==2*once+"existing document sentinel".length(),"duplicate storage accounting exact");System.out.println("MEASURED archive="+archive.length+" image="+image.length+" bytes_per_restore="+once+" repeated_twice="+(2*once));untouched();
  reset("initial-low-space",image);capacity=RESERVE-1;Exception e=attempt(s,archive);if(candidate){ok(e instanceof IOException&&readBytes==0&&stages().length==0&&newDocuments()==0&&saved==0,"known low space rejected before consuming source or creating stage");}else{ok(e==null&&saved==1&&readBytes==archive.length,"BASELINE GAP ignores reported low usable space");}untouched();
  reset("extraction-budget",image);capacity=bytes(base.toPath())+RESERVE+archive.length+2*(image.length+2)-1;e=attempt(s,archive);if(candidate){ok(e instanceof IOException&&saved==0&&raw()&&!new java.io.File(stages()[0],"manifest.json").exists()&&newDocuments()==0,"insufficient two-copy budget rejects before extraction with raw ZIP retained");}else{ok(e==null&&saved==1,"BASELINE GAP no two-copy space preflight");}untouched();
  reset("exact-budget",image);capacity=bytes(base.toPath())+RESERVE+archive.length+2*(image.length+2);e=attempt(s,archive);ok(e==null&&saved==1,"exact conservative preflight budget is accepted");untouched();
  for(String f:List.of("raw-write","extract-write","copy-write")){reset(f,image);fault=f;e=attempt(s,archive);ok(e instanceof IOException&&saved==0&&raw(),"injected "+f+" ENOSPC does not publish a manifest and retains raw/prefix");if(f.equals("copy-write")){ok(newDocuments()==1,"incomplete independent destination remains retained");}untouched();}
  reset("copy-integrity",image);fault="copy-integrity";e=attempt(s,archive);if(candidate){ok(e instanceof IOException&&saved==0&&raw()&&newDocuments()==1,"changed destination bytes rejected before manifest; intake retained");}else{ok(e==null&&saved==1,"BASELINE GAP injected silent copy corruption is not rechecked");}untouched();
  reset("copy-sync",image);fault="copy-sync";e=attempt(s,archive);if(candidate){ok(e instanceof IOException&&forceCalls==1&&saved==0&&raw(),"injected destination sync failure blocks manifest; raw retained");}else{ok(e==null&&forceCalls==0&&saved==1,"BASELINE GAP no destination sync attempt");}untouched();
  reset("unknown-space",image);capacity=0;e=attempt(s,archive);if(candidate){ok(e instanceof IOException&&saved==0&&readBytes==0,"unknown/zero space fails closed before source read");}else{ok(e==null&&saved==1,"BASELINE GAP unknown reported space not checked");}untouched();
  if(candidate){
   byte[] small=Arrays.copyOf(image,256);small[254]=(byte)255;small[255]=(byte)217;byte[] smallZip=zip(small);
   reset("small-zip-eof-headroom",small);capacity=bytes(base.toPath())+RESERVE+65536;e=attempt(s,smallZip);ok(e==null&&saved==1&&readBytes==smallZip.length,"small ZIP succeeds without demanding unused 64 KiB before EOF");untouched();
   reset("small-zip-exact-budget",small);capacity=bytes(base.toPath())+RESERVE+smallZip.length+2*(small.length+2);e=attempt(s,smallZip);ok(e==null&&saved==1,"small ZIP exact two-copy budget accepted with bounded reads");untouched();
   reset("copy-short-write",image);fault="copy-short-write";e=attempt(s,archive);ok(e==null&&saved==1,"short channel writes loop until all destination bytes are verified");untouched();
   for(String f:List.of("copy-space-drop","copy-interrupt")){reset(f,image);fault=f;try{e=attempt(s,archive);}finally{Thread.interrupted();}ok(e instanceof IOException&&saved==0&&raw(),"injected "+f+" stops publication and retains partial bytes");untouched();}
  }
  if(candidate){for(String f:List.of("raw-zero-read","copy-zero-write","copy-transient-zero")){reset(f,image);fault=f;e=attempt(s,archive);ok(e instanceof IOException&&saved==0&&raw()&&zeroCalls==1,"zero progress stops after one call without publishing or spinning: "+f);untouched();}}
  if(candidate){
   reset("raw-prefix-zero",image);fault="raw-prefix-zero";e=attempt(s,archive);ok(e instanceof IOException&&saved==0&&raw()&&ioCalls==2&&zeroCalls==1&&Arrays.equals(Files.readAllBytes(new java.io.File(stages()[0],"received.zip").toPath()),Arrays.copyOf(archive,16)),"prefix then zero retained exactly; no third source call or manifest");untouched();
   reset("copy-partial-zero",image);fault="copy-partial-zero";e=attempt(s,archive);java.io.File[] docs=new File(base,"documents").listFiles(f->!f.getName().equals("existing"));Path output=new java.io.File(docs[0],"originals/11111111-1111-1111-1111-111111111111.image").toPath();ok(e instanceof IOException&&saved==0&&forceCalls==0&&raw()&&ioCalls==2&&zeroCalls==1&&Arrays.equals(Files.readAllBytes(output),Arrays.copyOf(image,16))&&Arrays.equals(Files.readAllBytes(new java.io.File(stages()[0],"received.zip").toPath()),archive),"partial write then legal zero retains exact prefix and full raw; no force/save/retry");untouched();
  }
  System.out.println("DONE real ZIP/host files; simulated capacity and faults; stub JSON/model/JPEG/bitmap/manifest; Android NOT_RUN");
 }
}'''
out=OUTPUT/('storage-'+str(uuid.uuid4()));out.mkdir();j=out/'StorageRegression.java';j.write_text(java)
c=subprocess.run([JAVA,'-m','jdk.compiler/com.sun.tools.javac.Main','-d',str(out/'classes'),str(j)],capture_output=True,text=True)
r=subprocess.run([JAVA,'-cp',str(out/'classes'),'StorageRegression',str(out/'fixtures'),str(candidate).lower()],capture_output=True,text=True) if c.returncode==0 else None
result={'store_sha256':hashlib.sha256(source.encode()).hexdigest(),'extracted_restore_sha256':hashlib.sha256(method.encode()).hexdigest(),'instrumented_restore_sha256':hashlib.sha256(instrumented.encode()).hexdigest(),'seams':['File.getUsableSpace returns simulated capacity minus fixture bytes','FileOutputStream optionally fails writes after partial real output',*(['FileChannel.write redirected to Faults.write (delegates except injected partial write failure)','FileChannel.force redirected to Faults.force (delegates except injected sync failure or destination corruption)'] if candidate else ['Files.copy redirected to Faults.copy (delegates except injected failure or integrity change)'])],'compile_exit':c.returncode,'run_exit':r.returncode if r else None,'stdout':r.stdout if r else c.stdout,'stderr':r.stderr if r else c.stderr,'scope':'Host Linux filesystem and real Java ZIP; model/JSON/bitmap/JPEG/manifest fakes; deterministic storage failures, no physical ENOSPC and no Android'}
(out/'result.json').write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n');print(result['stdout']);print(result['stderr']);print('RESULT',out/'result.json');sys.exit(r.returncode if r else c.returncode)
