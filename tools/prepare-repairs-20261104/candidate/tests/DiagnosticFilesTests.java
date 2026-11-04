package org.wangshu.scan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Production diagnostic writer on the host filesystem; no Android execution. */
public final class DiagnosticFilesTests {
    static int checks;
    static final String RUN="12345678-1234-1234-1234-123456789abc";
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static byte[] record(String id){return ("{\"format\":\""+DiagnosticFiles.FORMAT+"\",\"runId\":\""+RUN+"\",\"stage\":\"preparation\",\"id\":\""+id+"\",\"contains_document_text\":false,\"stacks\":[{\"role\":\"timed-out-operation\",\"stage\":\"preparation\",\"id\":\""+id+"\",\"frames\":[\"Host.示例(Host.java:1)\"]}]}\n").getBytes(StandardCharsets.UTF_8);}
    static <T> FutureTask<T> start(Callable<T> task){FutureTask<T> f=new FutureTask<>(task);Thread t=new Thread(f,"host-diagnostic-test");t.setDaemon(true);t.start();return f;}
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]);Files.createDirectories(root);
        Path exact=Files.createDirectory(root.resolve("exact"));byte[] data=record("ACTIVITY_ARRIVAL");
        Path first=DiagnosticFiles.write(exact,"preparation","ACTIVITY_ARRIVAL",data);
        check(first.getFileName().toString().equals("program-stacks-preparation-activity-arrival.json"),"portable phase filename");
        check(Arrays.equals(data,Files.readAllBytes(first)),"UTF-8 bytes preserved exactly");
        try{DiagnosticFiles.write(exact,"preparation","ACTIVITY_ARRIVAL",record("OTHER"));throw new AssertionError("duplicate accepted");}catch(FileAlreadyExistsException expected){checks++;}
        check(Arrays.equals(data,Files.readAllBytes(first)),"duplicate does not truncate prior evidence");
        Path legacy=exact.resolve("program-stacks.json");Files.writeString(legacy,"legacy preserved");
        DiagnosticFiles.write(exact,"preparation","SETUP",record("SETUP"));
        check(Files.readString(legacy).equals("legacy preserved"),"legacy filename remains untouched");
        Locale before=Locale.getDefault();try{Locale.setDefault(Locale.forLanguageTag("tr-TR"));check(DiagnosticFiles.filename("preparation","IMPORT_DISPATCH").equals("program-stacks-preparation-import-dispatch.json"),"naming is locale independent");}finally{Locale.setDefault(before);}
        for(String id:new String[]{"../x","a","A/B","A-B","A.B","","A".repeat(81)}){
            try{DiagnosticFiles.write(exact,"preparation",id,data);throw new AssertionError("invalid ID accepted");}catch(IllegalArgumentException expected){checks++;}
        }
        try{DiagnosticFiles.write(exact,"../../outside","A",data);throw new AssertionError("invalid phase accepted");}catch(IllegalArgumentException expected){checks++;}
        try{DiagnosticFiles.write(root.resolve("missing"),"startup","A",data);throw new AssertionError("missing directory created");}catch(IOException expected){checks++;}
        check(!Files.exists(root.resolve("missing")),"writer never creates a run directory");

        Path collision=Files.createDirectory(root.resolve("collision"));CountDownLatch race=new CountDownLatch(1);AtomicInteger successes=new AtomicInteger(),conflicts=new AtomicInteger();
        List<FutureTask<Void>> attempts=new ArrayList<>();
        for(int i=0;i<2;i++)attempts.add(start(()->{race.await();try{DiagnosticFiles.write(collision,"preparation","SETUP",record("SETUP"));successes.incrementAndGet();}catch(FileAlreadyExistsException expected){conflicts.incrementAndGet();}return null;}));
        race.countDown();for(FutureTask<Void> f:attempts)f.get(3000,TimeUnit.MILLISECONDS);
        check(successes.get()==1&&conflicts.get()==1,"same phase has exactly one exclusive-create winner");
        check(Arrays.equals(record("SETUP"),Files.readAllBytes(collision.resolve(DiagnosticFiles.filename("preparation","SETUP")))),"racing duplicate leaves one complete unchanged record");

        Path concurrent=Files.createDirectory(root.resolve("concurrent"));CountDownLatch both=new CountDownLatch(2),release=new CountDownLatch(1);AtomicInteger callbacks=new AtomicInteger(),active=new AtomicInteger(),max=new AtomicInteger();
        BoundedStage control=new BoundedStage(RUN,e->{},timeout->{
            callbacks.incrementAndGet();max.accumulateAndGet(active.incrementAndGet(),Math::max);both.countDown();
            try{if(!both.await(2000,TimeUnit.MILLISECONDS))throw new AssertionError("callbacks did not overlap");DiagnosticFiles.write(concurrent,timeout.stage,timeout.id,record(timeout.id));}finally{active.decrementAndGet();}
        },700);
        try{
            control.run("startup","UI_AUTOMATION_OPEN",5000,()->null);
            try{control.run("preparation","SETUP",300,()->{control.owner("abcdef12-1234-1234-1234-123456789abc");return control.run("preparation","ACTIVITY_ARRIVAL",100,()->{release.await();return null;});});throw new AssertionError("blocked action accepted");}
            catch(BoundedStage.StageTimeoutException expected){checks++;}
            control.finish("STOPPED");
            long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(active.get()!=0&&System.nanoTime()<limit)Thread.sleep(5);
            check(callbacks.get()==2&&max.get()==2&&active.get()==0,"unchanged candidate delivered two completed overlapping timeout callbacks");
            for(String id:List.of("ACTIVITY_ARRIVAL","SETUP"))check(Arrays.equals(record(id),Files.readAllBytes(concurrent.resolve(DiagnosticFiles.filename("preparation",id)))),"each callback retained its own complete production-writer record");
            try(var stream=Files.list(concurrent)){check(stream.count()==2,"exactly two independently retained records");}
            check(control.events().stream().filter(e->"TERMINAL".equals(e.get("kind"))).count()==1,"one controller terminal unaffected");
            check(control.events().stream().noneMatch(e->"ASSERT".equals(e.get("kind"))),"no preparation assertion or product acceptance");
        }finally{release.countDown();}
        System.out.println("PASS DiagnosticFiles checks="+checks+"; two concurrent timeout records retained; Android NOT_RUN");
    }
}
