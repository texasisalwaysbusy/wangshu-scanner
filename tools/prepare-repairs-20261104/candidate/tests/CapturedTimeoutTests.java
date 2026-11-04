package org.wangshu.scan;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Host-only red/green check: keep acquired frames if the diagnostic listener fails. */
public final class CapturedTimeoutTests {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        boolean repaired=args[0].equals("repaired");
        for(boolean blocked:new boolean[]{false,true}){
            CountDownLatch actionRelease=new CountDownLatch(1),listenerRelease=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
            BoundedStage c=new BoundedStage("12345678-1234-1234-1234-123456789abc",e->{},timeout->{
                calls.incrementAndGet();check(!timeout.stackFrames.isEmpty(),"listener received captured frames before failure");
                if(blocked)listenerRelease.await();else throw new IOException("Synthetic listener failure");
            },150);
            long start=System.nanoTime();
            try{
                BoundedStage.StageTimeoutException caught=null;
                try{c.run("startup","BLOCKED_TARGET",80,()->{actionRelease.await();return null;});throw new AssertionError("timeout missing");}
                catch(BoundedStage.StageTimeoutException expected){caught=expected;}
                check(calls.get()==1,"exactly one listener invocation");
                boolean framesRetained=caught.timeout.stackFrames.stream().anyMatch(frame->frame.getClassName().equals(CapturedTimeoutTests.class.getName()));
                check(framesRetained==repaired,"acquired frames retained according to expected revision");
                check(caught.getSuppressed().length==1,"diagnostic failure remains attached");
                check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1500,"listener failure did not extend the existing bound");
                c.finish("STOPPED");List<Map<String,Object>> events=c.events();
                check(events.stream().filter(e->"TERMINAL".equals(e.get("kind"))).count()==1,"one STOPPED terminal");
                Object errors=events.get(events.size()-1).get("diagnostic_errors");check(errors instanceof List<?> && ((List<?>)errors).size()==1,"diagnostic failure recorded");
                System.out.println("PASS revision="+args[0]+" listener="+(blocked?"blocked":"throws")+" acquired_frames_retained="+framesRetained+" android_executed=false");
            }finally{actionRelease.countDown();listenerRelease.countDown();}
        }
        captureFailure();lateCapture();sharedBudget();
        System.out.println("PASS captured-timeout checks="+checks+"; Android NOT_RUN");
    }

    static void captureFailure()throws Exception {
        CountDownLatch actionRelease=new CountDownLatch(1);AtomicInteger listeners=new AtomicInteger();
        BoundedStage c=new BoundedStage("12345678-1234-1234-1234-123456789abc",e->{},t->listeners.incrementAndGet(),150,target->{throw new IOException("Synthetic capture failure");});
        try{
            try{c.run("startup","CAPTURE_FAIL",80,()->{actionRelease.await();return null;});throw new AssertionError("timeout missing");}
            catch(BoundedStage.StageTimeoutException e){check(e.timeout.stackFrames.isEmpty(),"failed capture has metadata only");check(e.timeout.id.equals("CAPTURE_FAIL")&&e.timeout.budgetMs==80,"failed capture preserves exact timeout identity");check(e.getSuppressed().length==1,"capture failure attached");}
            check(listeners.get()==0,"failed capture never calls listener");c.finish("STOPPED");
            System.out.println("PASS capture_failure_metadata_only=true listener_calls=0 android_executed=false");
        }finally{actionRelease.countDown();}
    }

    static void lateCapture()throws Exception {
        CountDownLatch actionRelease=new CountDownLatch(1),captureRelease=new CountDownLatch(1);AtomicInteger listeners=new AtomicInteger();AtomicReference<Thread> diagnostic=new AtomicReference<>();
        BoundedStage c=new BoundedStage("12345678-1234-1234-1234-123456789abc",e->{},t->listeners.incrementAndGet(),150,target->{diagnostic.set(Thread.currentThread());captureRelease.await();return new StackTraceElement[]{new StackTraceElement("Host","late","Host.java",1)};});
        try{
            BoundedStage.StageTimeoutException caught=null;
            try{c.run("startup","CAPTURE_LATE",80,()->{actionRelease.await();return null;});throw new AssertionError("timeout missing");}catch(BoundedStage.StageTimeoutException e){caught=e;}
            check(caught.timeout.stackFrames.isEmpty(),"pending capture initially has metadata only");BoundedStage.Timeout immutable=caught.timeout;c.finish("STOPPED");
            captureRelease.countDown();Thread worker=diagnostic.get();check(worker!=null,"diagnostic caller recorded");worker.join(3000);check(!worker.isAlive(),"diagnostic caller exited before late-listener assertion");
            check(listeners.get()==0,"expired capture never calls listener");check(caught.timeout==immutable&&caught.timeout.stackFrames.isEmpty(),"already thrown timeout never changes after late capture");
            System.out.println("PASS expired_capture_listener_calls=0 thrown_timeout_immutable=true android_executed=false");
        }finally{captureRelease.countDown();actionRelease.countDown();}
    }

    static void sharedBudget()throws Exception {
        CountDownLatch actionRelease=new CountDownLatch(1),listenerRelease=new CountDownLatch(1);AtomicInteger listeners=new AtomicInteger();AtomicReference<Thread> diagnostic=new AtomicReference<>();
        BoundedStage c=new BoundedStage("12345678-1234-1234-1234-123456789abc",e->{},t->{listeners.incrementAndGet();listenerRelease.await();},500,target->{diagnostic.set(Thread.currentThread());Thread.sleep(300);return new StackTraceElement[]{new StackTraceElement("Host","captured","Host.java",1)};});
        long start=System.nanoTime();
        try{
            try{c.run("startup","SHARED_BUDGET",100,()->{actionRelease.await();return null;});throw new AssertionError("timeout missing");}catch(BoundedStage.StageTimeoutException expected){checks++;}
            long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);check(listeners.get()==1,"listener starts after successful capture");check(elapsed>=500&&elapsed<850,"capture and listener share 500ms, not separate 500ms budgets");c.finish("STOPPED");
            listenerRelease.countDown();Thread worker=diagnostic.get();worker.join(3000);check(!worker.isAlive(),"budget-probe diagnostic exits safely");
            System.out.println("PASS capture_and_listener_budget_ms=500 elapsed_ms="+elapsed+" android_executed=false");
        }finally{listenerRelease.countDown();actionRelease.countDown();}
    }
}
