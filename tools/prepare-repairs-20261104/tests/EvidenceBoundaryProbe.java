package org.wangshu.scan;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Controlled host threads only. No Android calls or claims about the phone. */
public final class EvidenceBoundaryProbe {
    static final String RUN="12345678-1234-1234-1234-123456789abc";
    static final String OWNER="abcdef12-1234-1234-1234-123456789abc";
    static final List<String> MODES=List.of("healthy_blocked_arrival", "begin_visible_then_ack_stall", "end_pre_disk_stall", "end_post_disk_stall", "end_visible_then_ack_stall", "diagnostic_stall", "framework_finish_stall", "end_pre_disk_and_finish_stall");
    static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static long count(List<Map<String,Object>> events,String kind){return events.stream().filter(e->kind.equals(e.get("kind"))).count();}
    static boolean target(Map<String,Object> e,String kind){return kind.equals(e.get("kind"))&&"ACTIVITY_ARRIVAL".equals(e.get("id"));}
    static void write(Path path,List<Map<String,Object>> events)throws Exception{
        StringBuilder out=new StringBuilder();for(Map<String,Object> e:events)out.append(json(e)).append('\n');Files.writeString(path,out);
    }
    static Map<String,Object> probe(String mode,long inner,long outer,long evidenceBudget,Path output)throws Exception {
        List<Map<String,Object>> disk=new CopyOnWriteArrayList<>(),stdout=new CopyOnWriteArrayList<>();
        CountDownLatch actionRelease=new CountDownLatch(1),sinkRelease=new CountDownLatch(1),diagnosticRelease=new CountDownLatch(1),finishRelease=new CountDownLatch(1);
        AtomicInteger actionCalls=new AtomicInteger(),imports=new AtomicInteger(),sinkCalls=new AtomicInteger(),diagnosticCalls=new AtomicInteger();
        AtomicBoolean frameworkFinished=new AtomicBoolean();
        CountDownLatch blockedSinkReturned=new CountDownLatch(1);
        BoundedStage.Sink sink=e->{
            sinkCalls.incrementAndGet();
            if((mode.equals("end_pre_disk_stall")||mode.equals("end_pre_disk_and_finish_stall"))&&target(e,"END"))sinkRelease.await();
            disk.add(e);
            if(mode.equals("end_post_disk_stall")&&target(e,"END"))sinkRelease.await();
            stdout.add(e);
            if((mode.equals("begin_visible_then_ack_stall")&&target(e,"BEGIN"))||(mode.equals("end_visible_then_ack_stall")&&target(e,"END")))sinkRelease.await();
            if((mode.equals("begin_visible_then_ack_stall")&&target(e,"BEGIN"))||(mode.startsWith("end_")&&target(e,"END")))blockedSinkReturned.countDown();
        };
        BoundedStage control=new BoundedStage(RUN,sink,t->{diagnosticCalls.incrementAndGet();if(mode.equals("diagnostic_stall"))diagnosticRelease.await();},evidenceBudget);
        long start=System.nanoTime();String failure="NONE";boolean finishWaitExpired=false;
        try {
            control.run("startup","UI_AUTOMATION_OPEN",5000,()->null);
            try {
                control.run("preparation","SETUP",outer,()->{
                    control.run("preparation","STORE_CONSTRUCT",5000,()->null);
                    control.run("preparation","DOCUMENT_CREATE",5000,()->null);
                    control.run("preparation","DOCUMENT_TAG_SAVE",5000,()->null);
                    control.owner(OWNER);
                    control.run("preparation","PREFERENCE_COMMIT",5000,()->null);
                    control.run("preparation","ACTIVITY_MONITOR_ADD",5000,()->null);
                    control.run("preparation","ACTIVITY_REQUEST",5000,()->null);
                    Object arrived=control.run("preparation","ACTIVITY_ARRIVAL",inner,()->{actionCalls.incrementAndGet();actionRelease.await();return new Object();});
                    if(arrived!=null)imports.incrementAndGet();return null;
                });
                throw new AssertionError("blocked action or failed evidence must stop preparation");
            }catch(BoundedStage.StageTimeoutException|BoundedStage.EvidenceException expected){failure=expected.getClass().getSimpleName();}
            control.finish("STOPPED");
            List<Map<String,Object>> retained=control.events();
            boolean blockFinish=mode.equals("framework_finish_stall")||mode.equals("end_pre_disk_and_finish_stall");
            FutureTask<Void> finishing=new FutureTask<>(()->{if(blockFinish)finishRelease.await();frameworkFinished.set(true);return null;});
            Thread finisher=new Thread(finishing,"host-model-framework-finish");finisher.setDaemon(true);finisher.start();
            try{finishing.get(100,TimeUnit.MILLISECONDS);}catch(TimeoutException expected){finishWaitExpired=true;}
            long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
            require(retained.size()==20,"exact synthetic prefix then child END, parent END, TERMINAL");
            require(count(retained,"TERMINAL")==1&&"STOPPED".equals(retained.get(19).get("status")),"one retained STOPPED terminal");
            require(count(retained,"ASSERT")==0&&imports.get()==0,"zero assertions and no import");
            require(((Number)disk.get(16).get("seq")).intValue()==17&&target(disk.get(16),"BEGIN"),"phone-shaped observed seq17 BEGIN");
            require(actionCalls.get()==(mode.equals("begin_visible_then_ack_stall")?0:1),"BEGIN delivery is not action invocation");
            int diskExpected=20,stdoutExpected=20;
            if(mode.equals("begin_visible_then_ack_stall")||mode.equals("end_pre_disk_stall")||mode.equals("end_pre_disk_and_finish_stall")){diskExpected=17;stdoutExpected=17;}
            if(mode.equals("end_post_disk_stall")){diskExpected=18;stdoutExpected=17;}
            if(mode.equals("end_visible_then_ack_stall")){diskExpected=18;stdoutExpected=18;}
            require(disk.size()==diskExpected&&stdout.size()==stdoutExpected,"expected independently observed event counts");
            require(Boolean.valueOf(diskExpected<20).equals(retained.get(19).get("evidence_failed")),"evidence failure retained separately");
            require(frameworkFinished.get()!=blockFinish&&finishWaitExpired==blockFinish,"framework receipt independent of device terminal");
            write(output.resolve(mode+".device.jsonl"),disk);write(output.resolve(mode+".stdout.jsonl"),stdout);write(output.resolve(mode+".retained.jsonl"),retained);
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("scenario",mode);result.put("inner_ms",inner);result.put("outer_ms",outer);result.put("evidence_budget_ms",evidenceBudget);result.put("elapsed_ms",elapsed);result.put("error_type",failure);
            result.put("device_events",disk.size());result.put("stdout_events",stdout.size());result.put("retained_events",retained.size());result.put("retained_terminal","STOPPED");result.put("retained_evidence_failed",retained.get(19).get("evidence_failed"));
            result.put("arrival_action_calls",actionCalls.get());result.put("import_calls",imports.get());result.put("assertions",0);result.put("sink_calls",sinkCalls.get());result.put("diagnostic_calls",diagnosticCalls.get());result.put("framework_receipt_modeled",true);result.put("framework_receipt_observed",frameworkFinished.get());result.put("android_executed",false);result.put("phone_root_cause_proven",false);
            if(diskExpected<20){
                sinkRelease.countDown();require(blockedSinkReturned.await(2000,TimeUnit.MILLISECONDS),"blocked sink safely released");
                require(count(disk,"TERMINAL")==0&&count(stdout,"TERMINAL")==0,"failed evidence is not replayed after late delivery");
                write(output.resolve(mode+".post-release-device.jsonl"),disk);write(output.resolve(mode+".post-release-stdout.jsonl"),stdout);
                result.put("post_release_device_events",disk.size());result.put("post_release_stdout_events",stdout.size());result.put("post_release_external_terminal_count",0);
            }
            Files.writeString(output.resolve(mode+".summary.json"),json(result)+"\n");
            System.out.println("PASS "+json(result));return result;
        }finally{actionRelease.countDown();sinkRelease.countDown();diagnosticRelease.countDown();finishRelease.countDown();}
    }
    public static void main(String[] args)throws Exception {
        Path output=Path.of(args[0]);Files.createDirectories(output);boolean exact=args.length>1&&args[1].equals("exact");
        List<String> modes=exact?List.of("healthy_blocked_arrival","end_pre_disk_stall","begin_visible_then_ack_stall","end_pre_disk_and_finish_stall"):MODES;
        List<FutureTask<Map<String,Object>>> tasks=new ArrayList<>();
        for(String mode:modes){FutureTask<Map<String,Object>> task=new FutureTask<>(()->probe(mode,exact?21000:300,exact?300000:6000,exact?1000:150,output));Thread t=new Thread(task,"host-boundary-probe");t.setDaemon(true);t.start();tasks.add(task);}
        for(FutureTask<Map<String,Object>> task:tasks)task.get(exact?30000:10000,TimeUnit.MILLISECONDS);
        System.out.println("PASS "+modes.size()+" controlled host fault probes; Android NOT_RUN; product acceptance false");
    }
    static String json(Object value){
        if(value==null)return "null";if(value instanceof String)return quote((String)value);if(value instanceof Number||value instanceof Boolean)return value.toString();
        if(value instanceof Map<?,?>){StringJoiner out=new StringJoiner(",","{","}");for(var e:((Map<?,?>)value).entrySet())out.add(quote((String)e.getKey())+":"+json(e.getValue()));return out.toString();}
        if(value instanceof List<?>){StringJoiner out=new StringJoiner(",","[","]");for(Object item:(List<?>)value)out.add(json(item));return out.toString();}throw new IllegalArgumentException("unsupported JSON type");
    }
    static String quote(String text){StringBuilder out=new StringBuilder("\"");for(char c:text.toCharArray()){switch(c){case '"':out.append("\\\"");break;case '\\':out.append("\\\\");break;case '\n':out.append("\\n");break;case '\r':out.append("\\r");break;case '\t':out.append("\\t");break;default:if(c<32)out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else out.append(c);}}return out.append('"').toString();}
}
