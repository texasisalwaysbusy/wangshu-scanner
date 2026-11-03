package org.wangshu.scan;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Executes the production source gate against real synthetic files; no Android substitutes. */
public final class Vc6RecoverySourceTests {
 static int scenarios,checks,skipped;
 static final String DOC="00000000-0000-0000-0000-000000000001",PAGE="00000000-0000-0000-0000-000000000002",TOKEN="00000000-0000-0000-0000-000000000003";
 static final byte[] GOOD="VC6 SYNTHETIC ORIGINAL - NO PERSONAL CONTENT".getBytes(java.nio.charset.StandardCharsets.UTF_8);
 interface Action{void run()throws Exception;}
 static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
 static void rejected(Action a)throws Exception{checks++;try{a.run();throw new AssertionError("Expected IOException");}catch(IOException expected){}}
 static String sha(byte[] b)throws Exception{StringBuilder s=new StringBuilder();for(byte x:MessageDigest.getInstance("SHA-256").digest(b))s.append(String.format(Locale.ROOT,"%02x",x&255));return s.toString();}
 static final class Fixture {
  final Path root,docs,original,manifest,record;int moves;
  Fixture(Path all,String name,byte[] bytes)throws Exception{
   root=all.resolve(name);Files.createDirectories(root);docs=root.resolve("documents");Path originals=docs.resolve(DOC).resolve("originals");Files.createDirectories(originals);
   original=originals.resolve(PAGE+".image");if(bytes!=null)Files.write(original,bytes,StandardOpenOption.CREATE_NEW);
   manifest=root.resolve("manifest.state");record=root.resolve("recovery-record.bin");
   Files.writeString(manifest,"current-version-v1",StandardOpenOption.CREATE_NEW);Files.writeString(record,"immutable-recovery-metadata",StandardOpenOption.CREATE_NEW);
  }
  EditRecoveryPolicy.Identity source()throws Exception{return new EditRecoveryPolicy.Identity(DOC,PAGE,"originals/"+PAGE+".image",sha(GOOD));}
  Path preserve()throws Exception{
   Path target=original.resolveSibling(PAGE+".preserved-"+(++moves));
   check(original.toRealPath().startsWith(root.toRealPath())&&target.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()),"fixture move stays under own root");
   check(!Files.exists(target),"never overwrite preserved fixture");Files.move(original,target);return target;
  }
  void metadataUnchanged()throws Exception{
   check(Files.readString(manifest).equals("current-version-v1"),"manifest unchanged by verifier");
   check(Files.readString(record).equals("immutable-recovery-metadata"),"recovery record unchanged by verifier");
  }
 }
 static RecoverySourceGate.State state(Object recovery,Object document,Object page,Object session,String owner,String token,String digest,String documentId,String pageId,String version,int generation,int screen,long cycle,boolean live){
  return new RecoverySourceGate.State(recovery,document,page,session,owner,token,digest,documentId,pageId,version,generation,screen,cycle,live);
 }
 public static void main(String[] args)throws Exception{
  Path root=Path.of(args[0]);Files.createDirectories(root);
  scenarios++;{
   Fixture f=new Fixture(root,"normal",GOOD);AtomicInteger reads=new AtomicInteger();
   RecoverySourceGate.Verified<String> result=RecoverySourceGate.verifyAndRead(f.docs.toFile(),f.source(),()->{reads.incrementAndGet();return Files.readString(f.manifest);});
   check(result.value.equals("current-version-v1")&&reads.get()==1,"real source passes before document refresh");result.proof.recheck();
   check(sha(Files.readAllBytes(f.original)).equals(sha(GOOD)),"original not written");f.metadataUnchanged();
  }
  scenarios++;{
   Fixture f=new Fixture(root,"corrupt-initial","DAMAGED SOURCE".getBytes());byte[] prior=Files.readAllBytes(f.original);AtomicInteger reads=new AtomicInteger();
   rejected(()->RecoverySourceGate.verifyAndRead(f.docs.toFile(),f.source(),()->{reads.incrementAndGet();return "not reached";}));
   check(reads.get()==0,"corrupt source prevents load");check(Arrays.equals(prior,Files.readAllBytes(f.original)),"damaged source retained");f.metadataUnchanged();
  }
  scenarios++;{
   Fixture f=new Fixture(root,"missing-initial",null);rejected(()->RecoverySourceGate.verifyAndRead(f.docs.toFile(),f.source(),()->"not reached"));check(!Files.exists(f.original),"missing source not recreated");f.metadataUnchanged();
  }
  scenarios++;{
   Fixture f=new Fixture(root,"old-label-then-corrupt",GOOD);RecoverySourceGate.Proof oldLabel=RecoverySourceGate.verify(f.docs.toFile(),f.source());Path retained=f.preserve();Files.writeString(f.original,"REPLACED AFTER OLD LABEL",StandardOpenOption.CREATE_NEW);
   AtomicInteger reads=new AtomicInteger();rejected(()->RecoverySourceGate.verifyAndRead(f.docs.toFile(),f.source(),()->{reads.incrementAndGet();return "not reached";}));
   check(reads.get()==0,"old list proof never authorizes fresh load");check(sha(Files.readAllBytes(retained)).equals(sha(GOOD)),"old original preserved");rejected(oldLabel::recheck);f.metadataUnchanged();
  }
  scenarios++;{
   Fixture f=new Fixture(root,"old-label-then-missing",GOOD);RecoverySourceGate.verify(f.docs.toFile(),f.source());Path retained=f.preserve();rejected(()->RecoverySourceGate.verifyAndRead(f.docs.toFile(),f.source(),()->"not reached"));check(Files.exists(retained)&&!Files.exists(f.original),"disappearance simulated without deletion");f.metadataUnchanged();
  }
  scenarios++;{
   Fixture f=new Fixture(root,"replace-after-hash",GOOD);final Path[] kept=new Path[1];
   rejected(()->RecoverySourceGate.verifyAndRead(f.docs.toFile(),f.source(),()->{kept[0]=f.preserve();Files.writeString(f.original,"DIFFERENT REPLACEMENT",StandardOpenOption.CREATE_NEW);return Files.readString(f.manifest);}));
   check(sha(Files.readAllBytes(kept[0])).equals(sha(GOOD)),"proof rejects source replaced during document refresh; original retained");f.metadataUnchanged();
  }
  scenarios++;{
   Fixture f=new Fixture(root,"remove-after-hash",GOOD);
   rejected(()->RecoverySourceGate.verifyAndRead(f.docs.toFile(),f.source(),()->{f.preserve();return Files.readString(f.manifest);}));
   check(!Files.exists(f.original),"post-hash missing source rejected without recreate");f.metadataUnchanged();
  }
  scenarios++;{
   Fixture f=new Fixture(root,"equal-bytes-replacement-before-click",GOOD);Path kept=f.preserve();Files.write(f.original,GOOD,StandardOpenOption.CREATE_NEW);
   check(RecoverySourceGate.verifyAndRead(f.docs.toFile(),f.source(),()->"fresh").value.equals("fresh"),"fresh real hash can accept same source bytes at same identity");
   check(sha(Files.readAllBytes(kept)).equals(sha(GOOD)),"prior identical original retained");f.metadataUnchanged();
  }
  scenarios++;{
   Fixture f=new Fixture(root,"document-read-failure",GOOD);
   rejected(()->RecoverySourceGate.verifyAndRead(f.docs.toFile(),f.source(),()->{throw new IOException("synthetic document read failure");}));
   check(sha(Files.readAllBytes(f.original)).equals(sha(GOOD)),"document error does not modify original");f.metadataUnchanged();
  }
  scenarios++;{
   Fixture f=new Fixture(root,"source-read-type-failure",null);Files.createDirectory(f.original);
   rejected(()->RecoverySourceGate.verifyAndRead(f.docs.toFile(),f.source(),()->"not reached"));check(Files.isDirectory(f.original),"non-file source retained");f.metadataUnchanged();
  }
  scenarios++;{
   Fixture f=new Fixture(root,"empty-source",new byte[0]);rejected(()->RecoverySourceGate.verify(f.docs.toFile(),f.source()));check(Files.size(f.original)==0,"empty file retained");f.metadataUnchanged();
  }
  scenarios++;{
   Fixture f=new Fixture(root,"invalid-path",GOOD);EditRecoveryPolicy.Identity invalid=new EditRecoveryPolicy.Identity(DOC,PAGE,"../outside",sha(GOOD));
   rejected(()->RecoverySourceGate.verify(f.docs.toFile(),invalid));check(sha(Files.readAllBytes(f.original)).equals(sha(GOOD)),"invalid path cannot touch original");f.metadataUnchanged();
  }
  scenarios++;{
   Fixture f=new Fixture(root,"interrupted-read",GOOD);Thread.currentThread().interrupt();try{rejected(()->RecoverySourceGate.verify(f.docs.toFile(),f.source()));}finally{Thread.interrupted();}f.metadataUnchanged();
  }
  scenarios++;{
   Fixture f=new Fixture(root,"null-current",GOOD);rejected(()->RecoverySourceGate.verifyAndRead(f.docs.toFile(),f.source(),()->null));f.metadataUnchanged();
  }
  Object r=new Object(),d=new Object(),p=new Object(),session=new Object();String h="a".repeat(64),v="b".repeat(64);
  RecoverySourceGate.State baseline=state(r,d,p,session,DOC,TOKEN,h,DOC,PAGE,v,7,3,9,true);
  RecoverySourceGate.Ticket ticket=new RecoverySourceGate.Ticket(baseline);
  scenarios++;check(ticket.accepts(ticket,baseline),"current full ticket accepts");
  List<RecoverySourceGate.State> late=List.of(
   state(new Object(),d,p,session,DOC,TOKEN,h,DOC,PAGE,v,7,3,9,true),
   state(r,new Object(),p,session,DOC,TOKEN,h,DOC,PAGE,v,7,3,9,true),
   state(r,d,new Object(),session,DOC,TOKEN,h,DOC,PAGE,v,7,3,9,true),
   state(r,d,p,new Object(),DOC,TOKEN,h,DOC,PAGE,v,7,3,9,true),
   state(r,d,p,session,PAGE,TOKEN,h,DOC,PAGE,v,7,3,9,true),
   state(r,d,p,session,DOC,PAGE,h,DOC,PAGE,v,7,3,9,true),
   state(r,d,p,session,DOC,TOKEN,"c".repeat(64),DOC,PAGE,v,7,3,9,true),
   state(r,d,p,session,DOC,TOKEN,h,PAGE,PAGE,v,7,3,9,true),
   state(r,d,p,session,DOC,TOKEN,h,DOC,DOC,v,7,3,9,true),
   state(r,d,p,session,DOC,TOKEN,h,DOC,PAGE,"c".repeat(64),7,3,9,true),
   state(r,d,p,session,DOC,TOKEN,h,DOC,PAGE,v,8,3,9,true),
   state(r,d,p,session,DOC,TOKEN,h,DOC,PAGE,v,7,2,9,true),
   state(r,d,p,session,DOC,TOKEN,h,DOC,PAGE,v,7,3,10,true),
   state(r,d,p,session,DOC,TOKEN,h,DOC,PAGE,v,7,3,9,false));
  for(RecoverySourceGate.State changed:late){scenarios++;check(!ticket.accepts(ticket,changed),"late callback rejects changed identity/state");}
  scenarios++;check(!ticket.accepts(new RecoverySourceGate.Ticket(baseline),baseline),"superseded operation rejected even same UI state");
  scenarios++;check(!ticket.accepts(ticket,null),"missing callback state rejected");
  scenarios++;{
   RecoverySourceGate.State fromLibrary=state(r,null,null,null,DOC,TOKEN,h,null,null,null,2,3,0,true);RecoverySourceGate.Ticket t=new RecoverySourceGate.Ticket(fromLibrary);
   check(t.accepts(t,fromLibrary),"valid recovery selected from home has no prior document/editor");
  }
  scenarios++;{
   Fixture f=new Fixture(root,"symlink-source",null);Path target=f.root.resolve("retained-source");Files.write(target,GOOD,StandardOpenOption.CREATE_NEW);
   try{Files.createSymbolicLink(f.original,target);rejected(()->RecoverySourceGate.verify(f.docs.toFile(),f.source()));check(sha(Files.readAllBytes(target)).equals(sha(GOOD)),"symlink target unmodified");}
   catch(FileSystemException|UnsupportedOperationException noPrivilege){skipped++;System.out.println("SKIP symlink creation unavailable; no settings changed");}
  }
  System.out.println("VC6 listed="+scenarios+" executed="+(scenarios-skipped)+" skipped="+skipped+" assertions="+checks+" PASS; real source verifier/coordinator and UI-ticket policy; Android Store/UI not run");
 }
}
