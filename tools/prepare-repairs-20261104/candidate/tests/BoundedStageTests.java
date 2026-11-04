package org.wangshu.scan;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Pure JVM contract. All blocked tasks are daemon threads controlled by latches. */
public final class BoundedStageTests {
    static final String RUN="12345678-1234-1234-1234-123456789abc",OWNER="abcdef12-1234-1234-1234-123456789abc";
    static int scenarios,checks;
    interface Action {void run()throws Exception;}
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static void test(String label,Action action)throws Exception{action.run();scenarios++;System.out.println("PASS "+label);}
    static <T extends Throwable>T rejects(Class<T> type,Action action)throws Exception {
        try{action.run();throw new AssertionError("expected "+type.getSimpleName());}
        catch(Throwable failure){if(type.isInstance(failure)){checks++;return type.cast(failure);}if(failure instanceof Exception)throw (Exception)failure;throw (Error)failure;}
    }
    static BoundedStage stage(BoundedStage.TimeoutListener listener){return new BoundedStage(RUN,e->{},listener,40);}
    static void startup(BoundedStage stage)throws Exception{stage.run("startup","STARTUP",90,()->null);}
    static void prepared(BoundedStage stage)throws Exception{startup(stage);stage.run("preparation","PREPARE",90,()->{stage.owner(OWNER);return null;});}
    static long count(BoundedStage stage,String kind){return stage.events().stream().filter(e->kind.equals(e.get("kind"))).count();}
    static Map<String,Object> last(BoundedStage stage){List<Map<String,Object>> events=stage.events();return events.get(events.size()-1);}
    static void release(CountDownLatch... latches){for(CountDownLatch latch:latches)latch.countDown();}
    static void signal(CountDownLatch done)throws Exception{check(done.await(90,TimeUnit.MILLISECONDS),"controlled worker completed");}
    static void ordered(BoundedStage stage){long seq=0,ns=-1;for(Map<String,Object> e:stage.events()){check(((Number)e.get("seq")).longValue()==++seq,"contiguous sequence");long value=((Number)e.get("mono_ns")).longValue();check(value>=0&&value>=ns,"monotonic event time");ns=value;check("scan".equals(e.get("suite"))&&"prepare".equals(e.get("scope"))&&BoundedStage.PROTOCOL.equals(e.get("protocol")),"fixed protocol scope");}}
    public static void main(String[] args)throws Exception {
        Path output=Path.of(args[0]);Files.createDirectories(output);
        test("success emits Python-compatible startup preparation owner case and one terminal",()->{
            List<Map<String,Object>> sink=new ArrayList<>();BoundedStage s=new BoundedStage(RUN,sink::add,null,90);
            startup(s);int result=s.run("preparation","PREPARE",90,()->{
                int value=s.run("preparation","ASSET_CHECK",70,()->7);
                s.owner(OWNER);return s.post("preparation","MAIN_POST",70,Runnable::run,()->value+1);
            });
            check(result==8,"nested run and posted return values");s.assertCase("CASE_ONE",true);s.finish("PREPARED");
            check(sink.size()==s.events().size()&&"PREPARED".equals(last(s).get("status")),"sink and retained complete evidence");
            Map<String,Object> owner=s.events().stream().filter(e->"OWNER".equals(e.get("kind"))).findFirst().orElseThrow();
            check(OWNER.equals(owner.get("owner"))&&("SCAN_SYNTH_"+RUN).equals(owner.get("title")),"synthetic owner bound to run");ordered(s);
            rejects(UnsupportedOperationException.class,()->s.events().add(new HashMap<>()));rejects(UnsupportedOperationException.class,()->last(s).put("status","PASS"));
            StringBuilder jsonl=new StringBuilder();for(var event:s.events())jsonl.append("WANGSHU_EVENT ").append(json(event)).append('\n');Files.writeString(output.resolve("success.events.txt"),jsonl,StandardCharsets.UTF_8);
        });
        test("budgets identifiers and canonical UUIDs reject before action or event",()->{
            rejects(IllegalArgumentException.class,()->new BoundedStage("NOT-A-UUID",e->{},null));
            for(long budget:new long[]{Long.MIN_VALUE,-1,0,BoundedStage.MAX_BUDGET_MS+1,Long.MAX_VALUE}){BoundedStage s=stage(null);rejects(IllegalArgumentException.class,()->s.run("startup","STARTUP",budget,()->{throw new AssertionError("must not execute");}));check(s.events().isEmpty(),"invalid budget emits no phase");s.finish("STOPPED");}
            BoundedStage s=stage(null);rejects(IllegalArgumentException.class,()->s.run("startup","not valid",90,()->null));rejects(IllegalArgumentException.class,()->s.run("case","CASE",90,()->null));
            rejects(IllegalArgumentException.class,()->s.finish("PASS"));
            s.run("startup","STARTUP",BoundedStage.MAX_BUDGET_MS,()->null);s.finish("STOPPED");check("OK".equals(s.events().get(1).get("status")),"upper budget inclusive");
        });
        test("running daemon timeout is unknown and late completion cannot emit success",()->{
            CountDownLatch release=new CountDownLatch(1),done=new CountDownLatch(1);AtomicBoolean daemon=new AtomicBoolean(),interrupted=new AtomicBoolean();AtomicReference<BoundedStage.Timeout> timeout=new AtomicReference<>();BoundedStage s=stage(timeout::set);
            try{rejects(BoundedStage.StageTimeoutException.class,()->s.run("startup","BLOCKED",25,()->{daemon.set(Thread.currentThread().isDaemon());try{release.await();}finally{interrupted.set(Thread.currentThread().isInterrupted());done.countDown();}return 3;}));
                check(s.stopped()&&timeout.get()!=null&&"UNKNOWN".equals(timeout.get().operationState),"timeout stops without a completion claim");check("RUNNING".equals(timeout.get().dispatchState)&&daemon.get(),"running daemon identified");
                check(timeout.get().stackFrames.size()<=64&&timeout.get().stackFrames.stream().anyMatch(f->f.getClassName().equals(BoundedStageTests.class.getName())),"only bounded target program stack captured");
                int count=s.events().size();release.countDown();signal(done);check(!interrupted.get()&&s.events().size()==count,"late work is not interrupted and cannot emit END OK");
                rejects(BoundedStage.StoppedException.class,()->s.run("startup","AFTER",90,()->null));rejects(BoundedStage.StoppedException.class,()->s.finish("PREPARED"));s.finish("STOPPED");check("STOPPED".equals(last(s).get("status")),"truthful stopped terminal");ordered(s);
            }finally{release(release);}
        });
        test("posted work not started before deadline is prevented from running later",()->{
            AtomicReference<Runnable> queued=new AtomicReference<>();AtomicInteger calls=new AtomicInteger();BoundedStage s=stage(null);
            rejects(BoundedStage.StageTimeoutException.class,()->s.post("startup","QUEUED",20,queued::set,()->calls.incrementAndGet()));
            check(queued.get()!=null&&calls.get()==0,"post was queued only");queued.get().run();check(calls.get()==0,"late delivered Runnable is inert");
            Map<String,Object> end=last(s);check("TIMEOUT".equals(end.get("status"))&&"UNKNOWN".equals(end.get("operation_state"))&&"CANCELLED_BEFORE_START".equals(end.get("dispatch_state")),"timeout and dispatch states remain separate");s.finish("STOPPED");
        });
        test("already running posted thread is never interrupted or declared cancelled",()->{
            CountDownLatch release=new CountDownLatch(1),done=new CountDownLatch(1);AtomicBoolean interrupted=new AtomicBoolean();AtomicInteger calls=new AtomicInteger();AtomicReference<BoundedStage.Timeout> timeout=new AtomicReference<>();BoundedStage s=stage(timeout::set);
            try{rejects(BoundedStage.StageTimeoutException.class,()->s.post("startup","POST_RUNNING",30,r->{Thread t=new Thread(r,"synthetic-main-with-no-user-data");t.setDaemon(true);t.start();},()->{calls.incrementAndGet();try{release.await();}finally{interrupted.set(Thread.currentThread().isInterrupted());done.countDown();}return null;}));
                check(calls.get()==1&&"RUNNING".equals(timeout.get().dispatchState)&&"scanner-stage-posted".equals(timeout.get().targetThread),"running posted operation remains unknown");release.countDown();signal(done);check(!interrupted.get(),"posted thread never interrupted");s.finish("STOPPED");
            }finally{release(release);}
        });
        test("blocking dispatcher is bounded and cannot post executable work after timeout",()->{
            CountDownLatch dispatchRelease=new CountDownLatch(1),dispatchDone=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();BoundedStage s=stage(null);
            try{rejects(BoundedStage.StageTimeoutException.class,()->s.post("startup","DISPATCH_BLOCKED",20,r->{try{dispatchRelease.await();r.run();}catch(InterruptedException e){throw new RuntimeException(e);}finally{dispatchDone.countDown();}},()->calls.incrementAndGet()));
                dispatchRelease.countDown();signal(dispatchDone);check(calls.get()==0,"late dispatcher cannot start work");s.finish("STOPPED");
            }finally{release(dispatchRelease);}
        });
        test("dispatcher exception never authorizes queued product work",()->{
            AtomicReference<Runnable> queued=new AtomicReference<>();AtomicInteger calls=new AtomicInteger();BoundedStage s=stage(null);
            rejects(IllegalStateException.class,()->s.post("startup","DISPATCH_ERROR",90,r->{queued.set(r);throw new IllegalStateException("PRIVATE_DISPATCH_MESSAGE");},()->calls.incrementAndGet()));
            queued.get().run();check(calls.get()==0&&s.stopped(),"failed dispatcher invalidates queued work");check(!s.events().toString().contains("PRIVATE_DISPATCH_MESSAGE"),"dispatch message never retained");s.finish("STOPPED");
        });
        test("action exception preserves type and emits no exception message or false completion",()->{
            BoundedStage s=stage(null);IOException failure=new IOException("PRIVATE_SENTINEL_CONTENT");IOException got=rejects(IOException.class,()->s.run("startup","ERROR",90,()->{throw failure;}));
            check(got==failure&&s.stopped(),"original exception propagated and stages stop");check("ERROR".equals(last(s).get("status"))&&!s.events().toString().contains("PRIVATE_SENTINEL_CONTENT"),"only exception type retained");s.finish("STOPPED");
        });
        test("nested timeout closes child then parent once and prevents further preparation",()->{
            CountDownLatch release=new CountDownLatch(1);AtomicInteger callbacks=new AtomicInteger();BoundedStage s=stage(t->callbacks.incrementAndGet());startup(s);
            try{rejects(BoundedStage.StageTimeoutException.class,()->s.run("preparation","PARENT",90,()->{s.owner(OWNER);return s.run("preparation","CHILD",20,()->{release.await();return null;});}));
                List<Map<String,Object>> ends=s.events().stream().filter(e->"END".equals(e.get("kind"))).toList();check(ends.size()==3&&"CHILD".equals(ends.get(1).get("id"))&&"TIMEOUT".equals(ends.get(1).get("status"))&&"PARENT".equals(ends.get(2).get("id")),"balanced nested stop order");check(callbacks.get()==1,"one timeout notification");s.finish("STOPPED");
            }finally{release(release);}
        });
        test("duplicate terminal is idempotent and all post-terminal operations reject",()->{
            BoundedStage s=stage(null);prepared(s);s.assertCase("CASE_ONE",true);s.finish("PREPARED");int events=s.events().size();s.finish("PREPARED");check(s.events().size()==events&&count(s,"TERMINAL")==1,"same finish emits once");
            rejects(IllegalStateException.class,()->s.finish("STOPPED"));rejects(BoundedStage.StoppedException.class,()->s.assertCase("CASE_TWO",true));rejects(BoundedStage.StoppedException.class,()->s.owner(OWNER));check(s.events().size()==events,"no terminal-following evidence");
        });
        test("failed assertion counts exactly once and cannot produce PREPARED",()->{
            BoundedStage s=stage(null);prepared(s);rejects(IllegalStateException.class,()->s.assertCase("CASE_FALSE",false));rejects(BoundedStage.StoppedException.class,()->s.finish("PREPARED"));s.finish("STOPPED");check(((Number)last(s).get("assertions")).intValue()==1&&last(s).get("cases").equals(List.of("CASE_FALSE")),"failed case counted truthfully");
        });
        test("owner and phase ordering never fabricate synthetic preparation",()->{
            BoundedStage s=stage(null);rejects(IllegalStateException.class,()->s.owner(OWNER));rejects(IllegalStateException.class,()->s.run("preparation","EARLY",90,()->null));startup(s);rejects(IllegalStateException.class,()->s.run("preparation","NO_OWNER",90,()->null));check(s.stopped(),"owner omission stops");s.finish("STOPPED");
            BoundedStage d=stage(null);prepared(d);d.assertCase("ONCE",true);int size=d.events().size();rejects(IllegalArgumentException.class,()->d.assertCase("ONCE",true));check(d.events().size()==size,"duplicate case emits nothing");d.finish("PREPARED");
        });
        test("evidence sink exception prevents action and retains stopped evidence",()->{
            AtomicInteger calls=new AtomicInteger(),writes=new AtomicInteger();BoundedStage s=new BoundedStage(RUN,e->{writes.incrementAndGet();throw new IOException("PRIVATE_EVIDENCE_CONTENT");},null,30);
            rejects(BoundedStage.EvidenceException.class,()->s.run("startup","STARTUP",90,()->calls.incrementAndGet()));s.finish("STOPPED");check(calls.get()==0&&writes.get()==1&&s.stopped(),"failed evidence never authorizes action or retries");check(Boolean.TRUE.equals(last(s).get("evidence_failed"))&&!s.events().toString().contains("PRIVATE_EVIDENCE_CONTENT"),"retained failure without private message");ordered(s);
        });
        test("uncertain terminal delivery is retained as STOPPED and never replayed",()->{
            AtomicInteger terminals=new AtomicInteger();BoundedStage s=new BoundedStage(RUN,e->{if("TERMINAL".equals(e.get("kind"))){terminals.incrementAndGet();throw new IOException("terminal delivery uncertain");}},null,40);
            prepared(s);s.assertCase("CASE_ONE",true);rejects(BoundedStage.EvidenceException.class,()->s.finish("PREPARED"));check("STOPPED".equals(last(s).get("status"))&&s.stopped(),"no retained successful terminal after evidence failure");s.finish("STOPPED");check(terminals.get()==1&&count(s,"TERMINAL")==1,"no terminal replay");
        });
        test("blocked evidence sink has a bounded wait and never starts product work",()->{
            CountDownLatch release=new CountDownLatch(1),done=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();BoundedStage s=new BoundedStage(RUN,e->{try{release.await();}finally{done.countDown();}},null,20);
            try{rejects(BoundedStage.EvidenceException.class,()->s.run("startup","SINK_BLOCKED",90,()->calls.incrementAndGet()));check(s.stopped()&&calls.get()==0,"blocked sink fails closed");s.finish("STOPPED");release.countDown();signal(done);}finally{release(release);}
        });
        test("diagnostic failures and blocked listeners are bounded and never expose messages",()->{
            for(boolean blocked:new boolean[]{false,true}){CountDownLatch workRelease=new CountDownLatch(1),listenerRelease=new CountDownLatch(1);BoundedStage s=new BoundedStage(RUN,e->{},t->{if(blocked)listenerRelease.await();else throw new IOException("PRIVATE_STACK_MESSAGE");},20);
                try{rejects(BoundedStage.StageTimeoutException.class,()->s.run("startup",blocked?"LISTENER_BLOCKED":"LISTENER_ERROR",20,()->{workRelease.await();return null;}));s.finish("STOPPED");check(!((List<?>)last(s).get("diagnostic_errors")).isEmpty()&&!s.events().toString().contains("PRIVATE_STACK_MESSAGE"),"diagnostic failure is typed and non-accepting");}finally{release(workRelease,listenerRelease);}}
        });
        System.out.println("SCENARIOS "+scenarios+" ASSERTIONS "+checks+" FAILED 0; production pure Java stage controller, real controlled daemon threads; no Android/ADB/device/product mutation");
    }
    static String json(Object value){
        if(value==null)return "null";if(value instanceof String)return quote((String)value);if(value instanceof Number||value instanceof Boolean)return value.toString();
        if(value instanceof Map<?,?>){StringJoiner out=new StringJoiner(",","{","}");for(var item:((Map<?,?>)value).entrySet())out.add(quote((String)item.getKey())+":"+json(item.getValue()));return out.toString();}
        if(value instanceof List<?>){StringJoiner out=new StringJoiner(",","[","]");for(Object item:(List<?>)value)out.add(json(item));return out.toString();}throw new IllegalArgumentException("unsupported test JSON value");
    }
    static String quote(String text){StringBuilder out=new StringBuilder("\"");for(char c:text.toCharArray()){switch(c){case '"':out.append("\\\"");break;case '\\':out.append("\\\\");break;case '\n':out.append("\\n");break;case '\r':out.append("\\r");break;case '\t':out.append("\\t");break;default:if(c<32)out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else out.append(c);}}return out.append('"').toString();}
}
