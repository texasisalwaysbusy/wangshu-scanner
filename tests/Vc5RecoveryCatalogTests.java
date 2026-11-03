package org.wangshu.scan;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.io.*;

/** Executes production presentation policy and real journal/usage filesystem code. No Android substitutes. */
public final class Vc5RecoveryCatalogTests {
 static int checks,scenarios;
 static final String DOC="00000000-0000-0000-0000-000000000001",OTHER="00000000-0000-0000-0000-000000000002";
 static final String PAGE="00000000-0000-0000-0000-000000000003",TOKEN="00000000-0000-0000-0000-000000000004";
 static final String V1="a".repeat(64),V2="b".repeat(64),SHA="c".repeat(64);
 static final long CAP=64L*1024*1024;static final int MAX=4*1024*1024;
 interface Action{void run()throws Exception;}
 static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static void rejected(Action a)throws Exception{checks++;try{a.run();throw new AssertionError("Expected IOException");}catch(IOException expected){}}
 static EditRecoveryPolicy.Identity id(String d,String p,String f,String sha){return new EditRecoveryPolicy.Identity(d,p,f,sha);}
 static final EditRecoveryPolicy.Identity ID=id(DOC,PAGE,"originals/"+PAGE+".image",SHA);
 static EditRecoveryCatalog.Page page(EditRecoveryPolicy.Identity i,String json,boolean removed,boolean verified){return new EditRecoveryCatalog.Page(i,json,removed,verified);}
 static EditRecoveryCatalog.Current current(String title,String version,boolean editable,EditRecoveryCatalog.Page... pages){return new EditRecoveryCatalog.Current(DOC,title,version,editable,pages);}
 static EditRecoveryCatalog.Record record(boolean submitted,String version,String baseline,String edit){return new EditRecoveryCatalog.Record(new EditDraftStore.Entry(TOKEN,"hash",1700000000000L,null),new EditDraftStore.Draft(DOC,version,baseline,edit,submitted,1700000000000L),ID,ID,false);}
 static void status(EditRecoveryCatalog.Status expected,EditRecoveryCatalog.Record r,EditRecoveryCatalog.Current c){scenarios++;check(EditRecoveryCatalog.describe(DOC,r,c).status==expected,"status "+expected);}
 static Path files(Path root,String name)throws IOException{Path p=root.resolve(name);Files.createDirectories(p);return p;}
 static Path directory(Path files)throws IOException{Path p=files.resolve("edit-recovery").resolve(DOC);Files.createDirectories(p);return p;}
 static void length(Path p,long n)throws IOException{try(FileChannel ch=FileChannel.open(p,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){if(n>0){ch.position(n-1);ch.write(ByteBuffer.wrap(new byte[]{0}));}}}
 public static void main(String[] args)throws Exception{
  Path root=Path.of(args[0]);Files.createDirectories(root);
  EditRecoveryCatalog.Record draft=record(true,V1,"base","edit");
  status(EditRecoveryCatalog.Status.BASELINE,draft,current("same",V1,true,page(ID,"base",false,true)));
  status(EditRecoveryCatalog.Status.SAVED,draft,current("same",V2,true,page(ID,"edit",false,true)));
  status(EditRecoveryCatalog.Status.CONFLICT,draft,current("same",V2,true,page(ID,"base",false,true)));
  status(EditRecoveryCatalog.Status.CONFLICT,record(false,V1,"base","edit"),current("same",V2,true,page(ID,"edit",false,true)));
  status(EditRecoveryCatalog.Status.SOURCE_UNVERIFIED,draft,current("same",V1,true,page(id(DOC,PAGE,ID.file,"d".repeat(64)),"base",false,true)));
  status(EditRecoveryCatalog.Status.SOURCE_UNVERIFIED,draft,current("same",V1,true,page(id(DOC,PAGE,"other.image",SHA),"base",false,true)));
  status(EditRecoveryCatalog.Status.SOURCE_UNVERIFIED,draft,current("same",V1,true,page(ID,"base",false,false)));
  status(EditRecoveryCatalog.Status.PAGE_GONE,draft,current("same",V1,true,page(ID,"base",true,true)));
  status(EditRecoveryCatalog.Status.PAGE_GONE,draft,current("same",V1,true));
  status(EditRecoveryCatalog.Status.DOCUMENT_UNAVAILABLE,draft,null);
  status(EditRecoveryCatalog.Status.ARCHIVED,draft,current("same",V1,false,page(ID,"base",false,true)));
  status(EditRecoveryCatalog.Status.CONFLICT,draft,current("same",V1,true,page(ID,"base",false,true),page(ID,"base",false,true)));
  status(EditRecoveryCatalog.Status.INVALID,new EditRecoveryCatalog.Record(new EditDraftStore.Entry(TOKEN,"",0,"damaged"),null,null,null,false),null);
  status(EditRecoveryCatalog.Status.INVALID,new EditRecoveryCatalog.Record(draft.entry,draft.draft,ID,id(OTHER,PAGE,ID.file,SHA),false),null);
  status(EditRecoveryCatalog.Status.INVALID,new EditRecoveryCatalog.Record(draft.entry,new EditDraftStore.Draft(OTHER,V1,"base","edit",true,0),ID,ID,false),null);
  status(EditRecoveryCatalog.Status.INVALID,new EditRecoveryCatalog.Record(draft.entry,draft.draft,ID,ID,true),null);
  scenarios++;{
   EditRecoveryCatalog.Current a=current("同名\n\u202E标题",V1,true,page(ID,"base",false,true));
   String first=EditRecoveryCatalog.document(DOC,a),second=EditRecoveryCatalog.document(OTHER,new EditRecoveryCatalog.Current(OTHER,a.title,V1,true,a.pages));
   check(first.contains("当前标题"),"current not historic title");check(!first.contains("\n")&&!first.contains("\u202E"),"control/bidi display cleaned");check(!first.equals(second),"same titles distinguished by ID suffix");
   check(EditRecoveryCatalog.document(DOC,current(null,V1,true)).contains("未命名"),"missing title fallback");
   check(EditRecoveryCatalog.time(0).contains("未知"),"no fabricated time");check(EditRecoveryCatalog.time(1700000000000L).contains("首次保留"),"time is first retained");
   check(EditRecoveryCatalog.clean("😀".repeat(70)).codePointCount(0,EditRecoveryCatalog.clean("😀".repeat(70)).length())==65,"codepoint-safe bounded title");
  }
  scenarios++;{
   EditRecoveryCatalog.Current a=current("x",V1,true,page(id(DOC,"gone","gone",SHA),"gone",true,true),page(ID,"base",false,true));
   check(EditRecoveryCatalog.describe(DOC,draft,a).page.equals("当前第 1 页"),"active current ordinal only");
   check(EditRecoveryCatalog.describe(DOC,record(true,V1,"base","edit"),a).status!=EditRecoveryCatalog.Status.SAVED,"submitted flag alone not saved");
  }
  scenarios++;{
   Path f=files(root,"empty");EditDraftStore s=EditDraftStore.recovery(f.toFile());EditDraftStore.Usage u=s.usage(DOC);
   check(u.complete&&u.count==0&&u.bytes==0&&u.remainingFiles()==256&&u.remainingBytes()==CAP,"empty quota");check(u.recordLimit==MAX,"shared4MiB");check(!Files.exists(f.resolve("edit-recovery")),"usage does not create dirs");
   check(u.canAdd(MAX)&&!u.canAdd(MAX+1L)&&!u.canAdd(0),"single record exact cap");
  }
  scenarios++;{
   Path f=files(root,"count-boundary"),dir=directory(f);for(int i=0;i<255;i++)Files.write(dir.resolve("unknown-"+i),new byte[]{1});
   EditDraftStore s=EditDraftStore.recovery(f.toFile());EditDraftStore.Usage before=s.usage(DOC);check(before.complete&&before.count==255&&before.bytes==255&&before.remainingFiles()==1,"unknown regular files counted");
   EditDraftStore.Entry e=s.retain(DOC,V1,"base","edit",true);EditDraftStore.Usage full=s.usage(DOC);
   check(full.count==256&&full.full()&&full.remainingFiles()==0,"256th accepted then full");
   EditDraftStore.Entry same=s.retain(DOC,V1,"base","edit",true);check(same.token.equals(e.token)&&same.created==e.created,"full exact retry dedup unchanged first time");
   rejected(()->s.retain(DOC,V1,"base","different",true));check(s.usage(DOC).count==256,"failure adds no new record");
   check(s.inventory(DOC).entries.size()==256,"invalid names still visible");
   check(EditRecoveryCatalog.quota(full).contains("相同记录重试"),"full does not disable dedup retries");
   Files.write(dir.resolve("extra-unknown"),new byte[]{2});check(s.usage(DOC).count==257&&s.inventory(DOC).entries.size()==257,"over-limit entries measured and retained");
  }
  scenarios++;{
   Path f=files(root,"bytes-boundary"),dir=directory(f);length(dir.resolve("unknown-large"),CAP-1);EditDraftStore s=EditDraftStore.recovery(f.toFile());EditDraftStore.Usage u=s.usage(DOC);
   check(u.complete&&u.remainingBytes()==1&&u.canAdd(1)&&!u.canAdd(2),"exact byte remainder not rounded");
   rejected(()->s.retain(DOC,V1,"base","edit",true));check(s.usage(DOC).bytes==CAP-1,"insufficient bytes preserved");
   length(dir.resolve("last-byte"),1);u=s.usage(DOC);check(u.full()&&u.remainingBytes()==0&&!u.canAdd(1),"64MiB exact full");
   length(dir.resolve("over-byte"),1);check(s.usage(DOC).bytes==CAP+1&&s.usage(DOC).remainingBytes()==0,"over-byte not negative free");
  }
  scenarios++;{
   Path f=files(root,"unknown-directory"),dir=directory(f);Files.createDirectory(dir.resolve("unknown-subdir"));EditDraftStore s=EditDraftStore.recovery(f.toFile());EditDraftStore.Usage u=s.usage(DOC);
   check(u.count==1&&!u.complete&&!u.canAdd(1),"nonregular consumes slot and unknown bytes");check(EditRecoveryCatalog.quota(u).contains("余量未知"),"unknown never zero/free claim");rejected(()->s.retain(DOC,V1,"base","edit",true));check(Files.isDirectory(dir.resolve("unknown-subdir")),"unknown retained");
  }
  scenarios++;{
   Path f=files(root,"record-limit");EditDraftStore s=new EditDraftStore(f.toFile());EditDraftStore.Entry e=s.save(DOC,"b","x".repeat(MAX-93));Path record=f.resolve("edit-drafts").resolve(DOC).resolve(e.token+".draft");
   check(Files.size(record)==MAX,"encoded exactly4MiB accepted");rejected(()->s.save(DOC,"b","x".repeat(MAX-92)));check(s.usage(DOC).count==1,"over4MiB no added file");
  }
  scenarios++;{
   EditDraftStore.Usage nearCount=new EditDraftStore.Usage(205,0,true,256,CAP),nearBytes=new EditDraftStore.Usage(0,CAP*4/5,true,256,CAP);
   check(nearCount.near()&&nearBytes.near(),"count and byte warnings use actual constants");check(!new EditDraftStore.Usage(0,0,true,256,CAP).near(),"empty not warning");check(EditRecoveryCatalog.quota(nearCount).contains("接近上限"),"near warning text");
   EditDraftStore.Usage huge=new EditDraftStore.Usage(Long.MAX_VALUE,Long.MAX_VALUE,true,256,CAP);check(huge.full()&&huge.remainingBytes()==0&&!huge.canAdd(1),"no overflow free space");
  }
  scenarios++;{
   Path f=files(root,"symlink"),dir=directory(f),outside=root.resolve("symlink-target");Files.write(outside,new byte[19]);
   try{Files.createSymbolicLink(dir.resolve("unknown-link"),outside);EditDraftStore.Usage u=EditDraftStore.recovery(f.toFile()).usage(DOC);check(u.count==1&&!u.complete&&u.bytes==0,"symlink not followed");check(Files.size(outside)==19,"target intact");}catch(FileSystemException|UnsupportedOperationException denied){System.out.println("SKIP symlink creation unavailable; no security change");}
  }
  System.out.println("VC5 scenarios="+scenarios+" assertions="+checks+" PASS; production labels and journal quota/filesystem only; Android UI/Store not run");
 }
}
