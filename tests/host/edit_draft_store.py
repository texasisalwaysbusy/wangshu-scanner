from pathlib import Path
from support import PROJECT, OUTPUT, JAVA
import hashlib,json,subprocess,uuid
root=OUTPUT
source=PROJECT/'app/src/main/java/org/wangshu/scan/EditDraftStore.java'
r=root/('run-'+str(uuid.uuid4()));r.mkdir();(r/'EditDraftStore.java').write_bytes(source.read_bytes())
(r/'Harness.java').write_text(r'''package org.wangshu.scan;
import java.io.*;import java.nio.file.*;import java.security.*;import java.util.*;
public class Harness {
 static int tests;static Path root;static final String A="00000000-0000-0000-0000-000000000001",B="00000000-0000-0000-0000-000000000002";
 interface Op{void go()throws Exception;}
 static void check(boolean b,String msg){if(!b)throw new AssertionError(msg);}
 static void reject(Op op)throws Exception {boolean caught=false;try{op.go();}catch(IOException e){caught=true;}check(caught,"expected IOException");tests++;}
 static EditDraftStore store(String name)throws Exception {Path p=root.resolve(name);Files.createDirectories(p);return new EditDraftStore(p.toFile());}
 static Path file(String name,EditDraftStore.Entry e){return root.resolve(name).resolve("edit-drafts").resolve(A).resolve(e.token+".draft");}
 static byte[] hash(byte[] b)throws Exception{return MessageDigest.getInstance("SHA-256").digest(b);}
 static byte[] sealed(int magic,long time,String owner,byte[] baseline,byte[] edited)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream o=new DataOutputStream(b);o.writeInt(magic);o.writeLong(time);for(byte[] s:new byte[][]{owner.getBytes("UTF-8"),baseline,edited}){o.writeInt(s.length);o.write(s);}o.flush();b.write(hash(b.toByteArray()));return b.toByteArray();}
 static void damaged(String name,byte[] data)throws Exception{EditDraftStore s=store(name);EditDraftStore.Entry e=s.save(A,"base","edit");Files.write(file(name,e),data);List<EditDraftStore.Entry> list=s.list(A);check(list.size()==1&&!list.get(0).valid,"bad record accepted "+name);reject(()->s.load(A,list.get(0)));check(Arrays.equals(data,Files.readAllBytes(file(name,e))),"bad record modified");}
 public static void main(String[] args)throws Exception{
 root=Path.of(args[0]);
 EditDraftStore s=store("roundtrip");Path manifest=root.resolve("roundtrip/manifest.json");Files.writeString(manifest,"ORIGINAL MANIFEST");byte[] manifestBefore=Files.readAllBytes(manifest);check(s.list(A).isEmpty(),"empty list");tests++;
 EditDraftStore.Entry e=s.save(A,"基线","草稿");EditDraftStore fresh=new EditDraftStore(root.resolve("roundtrip").toFile());EditDraftStore.Draft d=fresh.load(A,fresh.list(A).get(0));check(d.document.equals(A)&&d.baseline.equals("基线")&&d.edited.equals("草稿")&&d.created>=0,"roundtrip");check(Arrays.equals(manifestBefore,Files.readAllBytes(manifest)),"manifest modified");tests++;
 reject(()->s.save("../escape","b","e"));reject(()->s.save(A,null,"e"));reject(()->s.load(A,null));
 EditDraftStore emptyInput=store("empty-input");reject(()->emptyInput.save(A,"","e"));reject(()->emptyInput.save(A,"b",""));check(emptyInput.list(A).isEmpty(),"empty input left record");
 EditDraftStore quota=store("quota");for(int i=0;i<16;i++)quota.save(A,"base","edit"+i);check(quota.list(A).size()==16,"16 allowed");tests++;reject(()->quota.save(A,"base","17"));check(quota.list(A).size()==16,"quota altered originals");
 EditDraftStore changes=store("change");EditDraftStore.Entry selected=changes.save(A,"b","old");byte[] replacement=sealed(0x57454431,1,A,"b".getBytes(),"new".getBytes());Files.write(file("change",selected),replacement);reject(()->changes.load(A,selected));check(changes.list(A).get(0).valid,"replacement not internally valid");tests++;
 byte[] valid=sealed(0x57454431,1,A,"b".getBytes(),"e".getBytes());damaged("truncate",Arrays.copyOf(valid,valid.length-1));damaged("trailing",Arrays.copyOf(valid,valid.length+1));byte[] flip=valid.clone();flip[20]^=1;damaged("flip",flip);
 damaged("owner",sealed(0x57454431,1,B,"b".getBytes(),"e".getBytes()));damaged("version",sealed(17,1,A,"b".getBytes(),"e".getBytes()));damaged("time",sealed(0x57454431,-1,A,"b".getBytes(),"e".getBytes()));damaged("empty",sealed(0x57454431,1,A,new byte[0],"e".getBytes()));damaged("utf8",sealed(0x57454431,1,A,new byte[]{(byte)0xff},"e".getBytes()));
 EditDraftStore limit=store("oversize");reject(()->limit.save(A,"x".repeat(4*1024*1024),"e"));check(limit.list(A).isEmpty(),"oversize wrote entries");
 EditDraftStore cancel=store("cancel");Thread.currentThread().interrupt();try{reject(()->cancel.save(A,"b","e"));check(Thread.currentThread().isInterrupted(),"interrupt cleared");}finally{Thread.interrupted();}check(cancel.list(A).isEmpty(),"cancel wrote entry");
 Path outside=root.resolve("outside");Files.createDirectories(outside);Files.writeString(outside.resolve("sentinel"),"unchanged");
 EditDraftStore rootlink=store("rootlink");Files.createSymbolicLink(root.resolve("rootlink/edit-drafts"),outside);reject(()->rootlink.save(A,"b","e"));reject(()->rootlink.list(A));
 EditDraftStore ownerlink=store("ownerlink");Files.createDirectories(root.resolve("ownerlink/edit-drafts"));Files.createSymbolicLink(root.resolve("ownerlink/edit-drafts").resolve(A),outside);reject(()->ownerlink.save(A,"b","e"));reject(()->ownerlink.list(A));
 EditDraftStore leaflink=store("leaflink");EditDraftStore.Entry seed=leaflink.save(A,"b","e");String tok="00000000-0000-0000-0000-000000000003";Path link=root.resolve("leaflink/edit-drafts").resolve(A).resolve(tok+".draft");Files.createSymbolicLink(link,file("leaflink",seed));List<EditDraftStore.Entry> entries=leaflink.list(A);EditDraftStore.Entry bad=entries.stream().filter(x->x.token.equals(tok)).findFirst().get();check(!bad.valid,"leaf symlink valid");reject(()->leaflink.load(A,bad));
 check(Files.readString(outside.resolve("sentinel")).equals("unchanged"),"symlink outside mutated");check(Files.list(outside).count()==1,"outside writes");tests++;
 EditDraftStore unknown=store("unknown");unknown.save(A,"b","e");Files.writeString(root.resolve("unknown/edit-drafts").resolve(A).resolve("alien"),"abc");check(unknown.list(A).stream().anyMatch(x->!x.valid),"unknown not surfaced");tests++;
 EditDraftStore tooMany=store("toomany");tooMany.save(A,"b","e");for(int i=0;i<16;i++)Files.writeString(root.resolve("toomany/edit-drafts").resolve(A).resolve("unknown"+i),"x");reject(()->tooMany.list(A));reject(()->tooMany.save(A,"b","e"));
 Path alias=root.resolve("filesalias");Files.createSymbolicLink(alias,outside);EditDraftStore filesalias=new EditDraftStore(alias.toFile());reject(()->filesalias.list(A));reject(()->filesalias.save(A,"b","e"));
 byte[] malformed=valid.clone();java.nio.ByteBuffer.wrap(malformed).putInt(12,-1);byte[] payload=Arrays.copyOf(malformed,malformed.length-32);System.arraycopy(hash(payload),0,malformed,payload.length,32);damaged("negative-length",malformed);
 Path cache=root.resolve("export-cache");Files.createDirectories(cache);byte[] originalCheckpoint=Files.readAllBytes(file("roundtrip",e));EditDraftStore.Export exported=s.export(A,e,cache.toFile());check(Arrays.equals(originalCheckpoint,Files.readAllBytes(exported.file.toPath())),"export differs");check(Arrays.equals(originalCheckpoint,Files.readAllBytes(file("roundtrip",e))),"export mutates source");check(exported.digest.equals(e.digest)&&exported.bytes==originalCheckpoint.length,"export identity");check(exported.file.getName().endsWith(".bin"),"export incompatible with save whitelist");tests++;
 reject(()->changes.export(A,selected,cache.toFile()));reject(()->s.export(B,e,cache.toFile()));reject(()->s.export(A,null,cache.toFile()));
 Path cacheLink=root.resolve("export-cache-link");Files.createSymbolicLink(cacheLink,cache);reject(()->s.export(A,e,cacheLink.toFile()));
 Path exportsLinkParent=root.resolve("export-dir-link");Files.createDirectories(exportsLinkParent);Files.createSymbolicLink(exportsLinkParent.resolve("exports"),outside);reject(()->s.export(A,e,exportsLinkParent.toFile()));
 Thread.currentThread().interrupt();try{reject(()->s.export(A,e,cache.toFile()));check(Thread.currentThread().isInterrupted(),"export clears cancel");}finally{Thread.interrupted();}
 EditDraftStore.Entry fabricated=new EditDraftStore.Entry(tok,seed.digest,seed.created,null);reject(()->leaflink.export(A,fabricated,cache.toFile()));
 check(Files.list(cache.resolve("exports")).count()==1,"rejected export created records");check(Arrays.equals(originalCheckpoint,Files.readAllBytes(file("roundtrip",e))),"rejected export mutates source");tests++;
 System.out.println("PASS "+tests+" scenarios; actual EditDraftStore with real files; all fixtures retained");
 }
}
''')
c=subprocess.run([JAVA,'-m','jdk.compiler/com.sun.tools.javac.Main','-d',str(r/'classes'),str(r/'EditDraftStore.java'),str(r/'Harness.java')],capture_output=True,text=True,timeout=40)
result={'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'compile_exit':c.returncode,'compile_stderr':c.stderr}
if c.returncode==0:
 (r/'fixtures').mkdir();p=subprocess.run([JAVA,'-cp',str(r/'classes'),'org.wangshu.scan.Harness',str(r/'fixtures')],capture_output=True,text=True,timeout=40);result.update(run_exit=p.returncode,stdout=p.stdout,stderr=p.stderr)
(r/'result.json').write_text(json.dumps(result,indent=2));print(r);print(json.dumps(result,indent=2))

raise SystemExit(0 if result.get("compile_exit")==0 and result.get("run_exit")==0 else 1)
