package org.wangshu.scan;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.io.IOException;
public final class EditorSaveFlowRaceTests {
 static int checks;
 static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static void waitFor(CountDownLatch latch)throws Exception{if(!latch.await(5,TimeUnit.SECONDS))throw new AssertionError("Timed out");}
 static EditorSaveFlow.Request begin(EditorSaveFlow f,Object s)throws Exception{return f.begin(s,"d","a","base","edit",List.of("a","b"),true,true);}
 public static void main(String[] args)throws Exception{
  for(boolean fail:new boolean[]{false,true}){
   EditorSaveFlow f=new EditorSaveFlow();Object oldSession=new Object();
   EditorSaveFlow.Request old=begin(f,oldSession);
   CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
   AtomicReference<EditorSaveFlow.Saved> saved=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();
   AtomicInteger durable=new AtomicInteger();
   Thread writer=new Thread(()->{try{
    saved.set(f.commit(old,"d","base",List.of("a","b"),()->{
     entered.countDown();waitFor(release);
     if(fail)throw new IOException("injected delayed IO failure");
     durable.incrementAndGet();return true;
    }));
   }catch(Throwable e){error.set(e);}},"host-frozen-save");
   writer.start();waitFor(entered);
   // UI destruction does not undo a writer that has already crossed into persistence.
   f.abandon();Object newSession=new Object();EditorSaveFlow.Request current=begin(f,newSession);
   release.countDown();writer.join(5000);check(!writer.isAlive(),"writer finished");
   if(fail){check(error.get() instanceof IOException,"late failure surfaced");check(saved.get()==null,"failure has no navigation result");check(durable.get()==0,"failed injected writer never committed");f.fail(old);}
   else{check(error.get()==null,"late commit complete");check(durable.get()==1,"already-started save acknowledged once");
    check(!f.finish(saved.get(),newSession,"d","a","base","edit"),"late old callback rejected");}
   check(f.hasPending(),"old completion/failure cannot unlock new request");
   EditorSaveFlow.Saved now=f.commit(current,"d","base",List.of("a","b"),()->true);
   check(f.finish(now,newSession,"d","a","base","edit"),"new request remains usable");
   check(!f.hasPending(),"new operation released");
  }
  EditorSaveFlow f=new EditorSaveFlow();Object s=new Object();EditorSaveFlow.Request q=begin(f,s);
  EditorSaveFlow.Saved pending=f.commit(q,"d","base",List.of("a","b"),()->true);
  check(f.hasPending(),"completed writer can wait for resumed UI without consuming result");
  try{begin(f,s);throw new AssertionError("Expected busy");}catch(IOException expected){checks++;}
  check(f.finish(pending,s,"d","a","base","edit"),"deferred current result consumed once");
  check(!f.finish(pending,s,"d","a","base","edit"),"deferred result cannot repeat");
  System.out.println("PASS 3 production-coordinator interleaving/deferred-delivery scenarios, "+checks+" assertions; no Android lifecycle execution");
 }
}
