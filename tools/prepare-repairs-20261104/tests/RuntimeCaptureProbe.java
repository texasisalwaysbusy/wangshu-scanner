package org.wangshu.scan;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Public-API host probe of unchanged controller source. Not Android/ART execution.
 * The custom posted thread overrides getStackTrace to model a runtime call that
 * does not return. No private field writes or controller source edits are used.
 */
public final class RuntimeCaptureProbe {
    static final String RUN="12345678-1234-1234-1234-123456789abc";
    static final String OWNER="abcdef12-1234-1234-1234-123456789abc";
    static final long INNER=180, OUTER=850, EVIDENCE=100;
    static void require(boolean b,String m){if(!b)throw new AssertionError(m);}
    static <T> FutureTask<T> start(String name,Callable<T> action){
        FutureTask<T> f=new FutureTask<>(action);Thread t=new Thread(f,name);t.setDaemon(true);t.start();return f;
    }
    static final class CaptureThread extends Thread {
        final CountDownLatch entered,release;
        final AtomicReference<Thread> captureCaller;
        CaptureThread(Runnable r,CountDownLatch entered,CountDownLatch release,AtomicReference<Thread> captureCaller){super(r,"host-posted-target");this.entered=entered;this.release=release;this.captureCaller=captureCaller;setDaemon(true);}
        @Override public StackTraceElement[] getStackTrace(){
            captureCaller.set(Thread.currentThread());entered.countDown();
            try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
            return new StackTraceElement[]{new StackTraceElement("HostOnly","simulatedRuntimeCapture","RuntimeCaptureProbe.java",1)};
        }
    }
    static long terminals(List<Map<String,Object>> es){return es.stream().filter(e->"TERMINAL".equals(e.get("kind"))).count();}
    public static void main(String[] args)throws Exception{
        boolean candidate=!args[0].equals("archived"), sinkBlocked=args[1].equals("sink_and_capture");
        Path output=Path.of(args[2]);Files.createDirectories(output);
        List<Map<String,Object>> exported=new CopyOnWriteArrayList<>();
        CountDownLatch captureEntered=new CountDownLatch(1),captureRelease=new CountDownLatch(1),actionRelease=new CountDownLatch(1),sinkRelease=new CountDownLatch(1),sinkReturned=new CountDownLatch(1);
        AtomicInteger listeners=new AtomicInteger(),imports=new AtomicInteger(),actions=new AtomicInteger();
        AtomicLong runnerReturnedNs=new AtomicLong();
        AtomicReference<Thread> captureCaller=new AtomicReference<>();
        BoundedStage c=new BoundedStage(RUN,e->{
            if(sinkBlocked&&"ACTIVITY_ARRIVAL".equals(e.get("id"))&&"END".equals(e.get("kind")))sinkRelease.await();
            exported.add(e);
            if(sinkBlocked&&"ACTIVITY_ARRIVAL".equals(e.get("id"))&&"END".equals(e.get("kind")))sinkReturned.countDown();
        },t->listeners.incrementAndGet(),EVIDENCE);
        long start=System.nanoTime();
        FutureTask<String> runner=start("host-instrumentation",()->{
            c.run("startup","UI_AUTOMATION_OPEN",5000,()->null);
            String failed="NONE";
            try{
                c.run("preparation","SETUP",OUTER,()->{
                    for(String id:List.of("STORE_CONSTRUCT","DOCUMENT_CREATE","DOCUMENT_TAG_SAVE"))c.run("preparation",id,5000,()->null);
                    c.owner(OWNER);
                    for(String id:List.of("PREFERENCE_COMMIT","ACTIVITY_MONITOR_ADD","ACTIVITY_REQUEST"))c.run("preparation",id,5000,()->null);
                    c.post("preparation","ACTIVITY_ARRIVAL",INNER,r->new CaptureThread(r,captureEntered,captureRelease,captureCaller).start(),()->{actions.incrementAndGet();actionRelease.await();return null;});
                    imports.incrementAndGet();return null;
                });
            }catch(Exception e){failed=e.getClass().getSimpleName();}
            c.finish("STOPPED");runnerReturnedNs.set(System.nanoTime());return failed;
        });
        try{
            require(captureEntered.await(3000,TimeUnit.MILLISECONDS),"target runtime-capture stub reached");
            FutureTask<Integer> events=start("host-events-observer",()->c.events().size());
            FutureTask<Boolean> stopped=start("host-stopped-observer",c::stopped);
            // Do not race finish against the diagnostic budget. On a bounded
            // implementation the normal runner finishes itself first; this
            // separate call only tests lock accessibility after outer expiry.
            long finishProbeAt=start+TimeUnit.MILLISECONDS.toNanos(OUTER+250);
            while(System.nanoTime()<finishProbeAt)Thread.sleep(5);
            FutureTask<Void> finish=start("host-finish-observer",()->{c.finish("STOPPED");return null;});
            // Observe beyond the configured outer deadline while the injected
            // runtime stall remains held. This is not the phone's 300/420s test.
            long observeUntil=start+TimeUnit.MILLISECONDS.toNanos(OUTER+450);
            while(System.nanoTime()<observeUntil)Thread.sleep(5);
            Map<String,Object> report=new LinkedHashMap<>();
            report.put("generation",args[0]);report.put("scenario",args[1]);
            report.put("inner_ms",INNER);report.put("outer_ms",OUTER);report.put("evidence_ms",EVIDENCE);
            report.put("observed_elapsed_ms",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
            report.put("runner_returned_while_capture_held",runner.isDone());
            report.put("runner_returned_ms",runnerReturnedNs.get()==0?null:TimeUnit.NANOSECONDS.toMillis(runnerReturnedNs.get()-start));
            report.put("events_returned_while_capture_held",events.isDone());
            report.put("stopped_returned_while_capture_held",stopped.isDone());
            report.put("finish_returned_while_capture_held",finish.isDone());
            report.put("exported_events_while_capture_held",exported.size());
            report.put("exported_terminals_while_capture_held",terminals(exported));
            report.put("listener_calls_before_release",listeners.get());report.put("action_calls",actions.get());report.put("import_calls",imports.get());
            report.put("android_executed",false);report.put("phone_root_cause_proven",false);report.put("product_acceptance",false);
            require(runner.isDone()==candidate,"runner boundedness matches source generation");
            require(events.isDone()==candidate&&stopped.isDone()==candidate&&finish.isDone()==candidate,"observer lock accessibility matches source generation");
            require(exported.size()==(sinkBlocked?17:candidate?20:19),"stack-only stall already has ENDs; combined stall can have prefix17");
            require(actions.get()==1&&imports.get()==0,"one injected operation, no import");
            Files.writeString(output.resolve("held-summary.json"),EvidenceBoundaryProbe.json(report)+"\n");
            EvidenceBoundaryProbe.write(output.resolve("held-exported.jsonl"),exported);
            captureRelease.countDown();
            Thread capturedBy=captureCaller.get();require(capturedBy!=null,"capture caller recorded");
            capturedBy.join(3000);require(!capturedBy.isAlive(),"capture caller exited before checking late listeners");
            String failure=runner.get(3000,TimeUnit.MILLISECONDS);events.get(3000,TimeUnit.MILLISECONDS);stopped.get(3000,TimeUnit.MILLISECONDS);finish.get(3000,TimeUnit.MILLISECONDS);
            List<Map<String,Object>> retained=c.events();
            require(retained.size()==20&&terminals(retained)==1,"one terminal and complete retained sequence");
            require("STOPPED".equals(retained.get(19).get("status")),"failure-only terminal");
            require(retained.stream().noneMatch(e->"ASSERT".equals(e.get("kind"))),"zero assertions");
            require(!candidate||listeners.get()==0,"expired candidate capture cannot enter listener after release");
            report.put("eventual_runner_error_type",failure);report.put("retained_events",retained.size());report.put("late_listener_calls",listeners.get());
            report.put("retained_evidence_failed",retained.get(19).get("evidence_failed"));
            report.put("retained_diagnostic_errors",retained.get(19).get("diagnostic_errors"));
            EvidenceBoundaryProbe.write(output.resolve("retained.jsonl"),retained);
            if(sinkBlocked){sinkRelease.countDown();require(sinkReturned.await(3000,TimeUnit.MILLISECONDS),"sink released safely");require(terminals(exported)==0,"failed sink never replays terminal");}
            EvidenceBoundaryProbe.write(output.resolve("released-exported.jsonl"),exported);
            report.put("exported_events_after_release",exported.size());report.put("exported_terminals_after_release",terminals(exported));
            Files.writeString(output.resolve("summary.json"),EvidenceBoundaryProbe.json(report)+"\n");
            System.out.println("PASS "+EvidenceBoundaryProbe.json(report));
        }finally{captureRelease.countDown();actionRelease.countDown();sinkRelease.countDown();}
    }
}
