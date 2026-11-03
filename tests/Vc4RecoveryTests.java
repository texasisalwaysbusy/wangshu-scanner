package org.wangshu.scan;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import java.util.concurrent.atomic.*;

/** Real production codecs, filesystem journal and policies; no Android substitutes. */
public final class Vc4RecoveryTests {
 static int checks,scenarios;
 static final String DOC="00000000-0000-0000-0000-000000000001",OTHER="00000000-0000-0000-0000-000000000002";
 static final String V1="a".repeat(64),V2="b".repeat(64),V3="c".repeat(64),SHA="d".repeat(64);
 interface Action {void run()throws Exception;}
 static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static void rejected(Action a)throws Exception{checks++;try{a.run();throw new AssertionError("Expected IOException");}catch(IOException expected){}}
 static EditRecoveryPolicy.Identity identity(String d,String page,String file,String sha){return new EditRecoveryPolicy.Identity(d,page,file,sha);}
 static EditorSaveFlow.Request request(EditorSaveFlow f,Object s,List<String> order,String page,String baseline,String edited)throws Exception{
  return f.begin(s,DOC,page,baseline,edited,order,true,true);
 }
 static void disk(Path file,String version,String order)throws Exception{Files.writeString(file,version+"\n"+order);}
 static EditorSaveFlow.Persisted read(Path file)throws Exception{
  List<String> lines=Files.readAllLines(file);return new EditorSaveFlow.Persisted(DOC,lines.get(0),Arrays.asList(lines.get(1).split(",")));
 }
 public static void main(String[] args)throws Exception{
  Path root=Path.of(args[0]);Files.createDirectories(root);
  scenarios++;{
   Path file=root.resolve("persisted-order.state");EditorSaveFlow f=new EditorSaveFlow();Object s=new Object();
   EditorSaveFlow.Request q=request(f,s,List.of("a","b","c"),"a","base","edit");
   EditorSaveFlow.Saved saved=f.commit(q,DOC,"base",q.order,()->{disk(file,V2,"a,b,c");EditorSaveFlow.observePersisted(DOC,V2);return true;},()->V2);
   // A memory order stays old while another writer changes the actual persisted fixture.
   disk(file,V3,"a,c,b");EditorSaveFlow.observePersisted(DOC,V3);
   EditorSaveFlow.Persisted fresh=EditorSaveFlow.checkPersisted(()->read(file));
   check(f.sameOrder(saved,q.order),"old memory-only gate would pass");
   check(!EditorSaveFlow.permitsNavigation(saved,fresh,EditorSaveFlow.observedVersion(DOC)),"persisted reorder blocks old successor");
   check(f.finish(saved,s,DOC,"a","base","edit"),"still-current UI can acknowledge without navigating");
  }
  scenarios++;{
   Path file=root.resolve("callback-late-write.state");EditorSaveFlow f=new EditorSaveFlow();Object s=new Object();
   EditorSaveFlow.Request q=request(f,s,List.of("a","b"),"a","base","edit");
   EditorSaveFlow.Saved saved=f.commit(q,DOC,"base",q.order,()->{disk(file,V2,"a,b");EditorSaveFlow.observePersisted(DOC,V2);return true;},()->V2);
   EditorSaveFlow.Persisted readBeforePause=EditorSaveFlow.checkPersisted(()->read(file));
   check(EditorSaveFlow.permitsNavigation(saved,readBeforePause,V2),"stable disk may navigate");
   disk(file,V3,"b,a");EditorSaveFlow.observePersisted(DOC,V3);
   check(!EditorSaveFlow.permitsNavigation(saved,readBeforePause,EditorSaveFlow.observedVersion(DOC)),"write between read and callback invalidates observation");
   check(!f.finish(saved,new Object(),DOC,"a","base","edit"),"old session cannot consume into new editor");
  }
  scenarios++;{
   EditorSaveFlow f=new EditorSaveFlow();Object s=new Object();AtomicInteger writes=new AtomicInteger();
   EditorSaveFlow.Request q=request(f,s,List.of("only"),"only","same","same");
   EditorSaveFlow.Saved saved=f.commit(q,DOC,"same",q.order,()->{writes.incrementAndGet();return true;},()->V2);
   check(writes.get()==1,"unchanged page still performs valid save");
   check(q.nextPageId==null,"single/last page does not loop");
   check(EditorSaveFlow.permitsNavigation(saved,new EditorSaveFlow.Persisted(DOC,V2,List.of("only")),V2),"unchanged-page post-save version verified");
   check(f.finish(saved,s,DOC,"only","same","same"),"unchanged page deliverable");
  }
  scenarios++;{
   EditorSaveFlow f=new EditorSaveFlow();
   rejected(()->f.begin(new Object(),DOC,"a","base","edit",List.of("a"),false,true));
   check(!f.hasPending(),"unreviewed corners still rejected");
  }
  EditRecoveryPolicy.Identity id=identity(DOC,"a","originals/a.image",SHA);
  for(EditRecoveryPolicy.Identity bad:List.of(identity(OTHER,"a","originals/a.image",SHA),identity(DOC,"b","originals/a.image",SHA),identity(DOC,"a","other.image",SHA),identity(DOC,"a","originals/a.image","e".repeat(64)))){
   scenarios++;check(!EditRecoveryPolicy.sameSource(id,bad),"document/page/file/digest identity mismatch");
   check(EditRecoveryPolicy.decide(id,bad,V1,V1,"base","edit","base",false)==EditRecoveryPolicy.Decision.CONFLICT,"foreign source cannot restore");
  }
  scenarios++;{
   check(EditRecoveryPolicy.decide(id,id,V1,V1,"base","edit","base",false)==EditRecoveryPolicy.Decision.BASELINE,"same exact original version manually recoverable");
   check(EditRecoveryPolicy.decide(id,id,V1,V3,"base","edit","base",false)==EditRecoveryPolicy.Decision.CONFLICT,"equal page but changed document version rejected");
   check(EditRecoveryPolicy.decide(id,id,"",V1,"base","edit","base",false)==EditRecoveryPolicy.Decision.CONFLICT,"missing legacy version cannot auto-rebase");
   check(EditRecoveryPolicy.decide(id,id,V1,V2,"base","edit","edit",true)==EditRecoveryPolicy.Decision.CONTENTS_SAVED,"already-saved draft acknowledged, never replayed");
   check(EditRecoveryPolicy.decide(id,id,V1,V3,"base","edit","newer",true)==EditRecoveryPolicy.Decision.CONFLICT,"successful old draft cannot cover newer page");
  }
  Path files=root.resolve("app-files");Files.createDirectories(files);
  EditDraftStore store=EditDraftStore.recovery(files.toFile());
  EditDraftStore.Entry entry=store.retain(DOC,V1,"baseline-json","edited-json",true);
  scenarios++;{
   EditDraftStore.Draft original=store.load(DOC,entry);
   check(original.version.equals(V1)&&original.submitted,"V2 format retains version/submission");
   check(original.document.equals(DOC)&&original.baseline.equals("baseline-json")&&original.edited.equals("edited-json"),"full data preserved");
   EditDraftStore.Entry duplicate=store.retain(DOC,V1,"baseline-json","edited-json",true);
   check(duplicate.token.equals(entry.token)&&duplicate.digest.equals(entry.digest),"exact retry deduplicates");
   check(store.list(DOC).size()==1,"dedup never adds a second record");
   check(new EditDraftStore(files.toFile()).list(DOC).isEmpty(),"recovery and explicit checkpoints isolated");
   rejected(()->store.load(OTHER,entry));
   check(store.owners().equals(new ArrayList<>(List.of(DOC))),"owner discoverable from home without a loaded document");
  }
  scenarios++;{
   EditRecoveryState state=new EditRecoveryState(DOC,new EditDraftStore.Draft(DOC,V1,"baseline-json","edited-json",true,0));
   Map<String,String> raw=new HashMap<>();state.write(raw::put);
   EditRecoveryState recreated=EditRecoveryState.read(raw::get);
   check(recreated!=null&&!recreated.verified&&recreated.entry==null,"unretained saved state does not fabricate persistence");
   check(recreated.owner.equals(DOC)&&recreated.data.version.equals(V1)&&recreated.data.edited.equals("edited-json"),"raw conflict survives state map roundtrip");
   check(recreated.data.baseline.equals("baseline-json")&&recreated.data.submitted,"original baseline identity not replaced");
   state.retained(entry);Map<String,String> ref=new HashMap<>();state.write(ref::put);
   check(!ref.containsKey("editRecovery.edited"),"verified receipt avoids duplicate large Bundle payload");
   EditRecoveryState restored=EditRecoveryState.read(ref::get);
   check(restored.entry.token.equals(entry.token)&&!restored.verified&&restored.data==null,"receipt requires background revalidation");
   restored.loaded(EditDraftStore.recovery(files.toFile()).load(restored.owner,restored.entry));
   check(restored.verified&&restored.data.version.equals(V1)&&restored.data.edited.equals("edited-json"),"new store instance restores full conflict record");
   check(EditRecoveryPolicy.decide(id,id,restored.data.version,V3,restored.data.baseline,restored.data.edited,"newer",true)==EditRecoveryPolicy.Decision.CONFLICT,"state recovery cannot overwrite later saved data");
  }
  scenarios++;{
   AtomicInteger writes=new AtomicInteger();AtomicReference<EditDraftStore.Entry> retained=new AtomicReference<>();
   rejected(()->EditorSaveFlow.afterRecovery(()->retained.set(store.retain(DOC,V1,"before-failure","unsaved-after-failure",true)),()->{
    writes.incrementAndGet();throw new IOException("injected manifest/version failure");
   }));
   check(writes.get()==1,"document attempted after recovery succeeded");
   EditDraftStore.Draft recovered=new EditDraftStore(files.toFile()).recovery(files.toFile()).load(DOC,retained.get());
   check(recovered.edited.equals("unsaved-after-failure"),"failed writer leaves independent on-disk record");
  }
  scenarios++;{
   Path blocked=root.resolve("blocked-files");Files.createDirectories(blocked);Files.writeString(blocked.resolve("edit-recovery"),"ordinary file");
   AtomicInteger writes=new AtomicInteger();
   rejected(()->EditorSaveFlow.afterRecovery(()->EditDraftStore.recovery(blocked.toFile()).retain(DOC,V1,"base","edit",true),()->{writes.incrementAndGet();return true;}));
   check(writes.get()==0,"failed retention prevents document write");
   check(Files.readString(blocked.resolve("edit-recovery")).equals("ordinary file"),"failed journal leaves blocking file untouched");
  }
  scenarios++;{
   Path corrupt=files.resolve("edit-recovery").resolve(DOC).resolve(entry.token+".draft");byte[] bytes=Files.readAllBytes(corrupt);bytes[bytes.length-1]^=1;Files.write(corrupt,bytes);
   rejected(()->store.load(DOC,entry));check(Files.exists(corrupt),"damaged file retained, not deleted");
   check(store.list(DOC).stream().anyMatch(e->e.token.equals(entry.token)&&!e.valid),"damage visible instead of silently lost");
  }
  scenarios++;{
   Path legacy=root.resolve("legacy-files");Files.createDirectories(legacy);EditDraftStore explicit=new EditDraftStore(legacy.toFile());
   EditDraftStore.Entry old=explicit.save(DOC,"old-base","old-edit");EditDraftStore.Draft decoded=explicit.load(DOC,old);
   check(decoded.version.isEmpty()&&!decoded.submitted&&decoded.edited.equals("old-edit"),"WED1 records remain readable");
  }
  scenarios++;{
   Path bounded=root.resolve("bounded-files");Files.createDirectories(bounded);EditDraftStore capacity=EditDraftStore.recovery(bounded.toFile());
   for(int i=0;i<256;i++)capacity.retain(DOC,V1,"b","e"+i,true);
   rejected(()->capacity.retain(DOC,V1,"b","overflow",true));
   check(capacity.list(DOC).size()==256,"capacity failure keeps all prior records");
   String huge="x".repeat(4*1024*1024+1);
   rejected(()->capacity.retain(OTHER,V1,huge,"e",true));
   check(capacity.list(OTHER).isEmpty(),"oversized record never pretends retained");
  }
  System.out.println("PASS "+scenarios+" vc4 production policy/filesystem/state scenarios, "+checks+" assertions; no Android Store/AtomicFile/Bundle/lifecycle execution");
 }
}
