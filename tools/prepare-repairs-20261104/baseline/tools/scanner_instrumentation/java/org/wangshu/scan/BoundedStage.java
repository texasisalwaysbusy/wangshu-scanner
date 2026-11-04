package org.wangshu.scan;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Preparation diagnostics only. A timeout ends a wait, never an Android operation.
 * Invoke waits from the instrumentation/controller thread, not the Android UI thread.
 * The caller finishes with STOPPED after any failure; neither tasks nor evidence are retried.
 */
public final class BoundedStage {
    public static final String PROTOCOL="wangshu.scan.instrumentation.v1";
    public static final long MAX_BUDGET_MS=420_000;
    private static final long CASE_BUDGET_MS=1_000;
    @FunctionalInterface public interface Sink {void accept(Map<String,Object> event)throws Exception;}
    @FunctionalInterface public interface TimeoutListener {void onTimeout(Timeout timeout)throws Exception;}
    public static final class Timeout {
        public final String stage,id,operationState="UNKNOWN",dispatchState,targetThread;
        public final long budgetMs;
        public final List<StackTraceElement> stackFrames;
        private Timeout(Phase p){
            stage=p.stage;id=p.id;budgetMs=p.budgetMs;
            int state=p.state.get();dispatchState=state==0||state==3?"CANCELLED_BEFORE_START":state==1?"RUNNING":"FINISHED";
            Thread target=p.runner!=null?p.runner:p.dispatcher;
            targetThread=target==null?"scanner-stage-not-started":p.runner!=null?(p.posted?"scanner-stage-posted":"scanner-stage-worker"):"scanner-stage-dispatch";
            StackTraceElement[] frames=target==null?new StackTraceElement[0]:target.getStackTrace();
            stackFrames=Collections.unmodifiableList(Arrays.asList(Arrays.copyOf(frames,Math.min(64,frames.length))));
        }
    }
    public static final class StageTimeoutException extends TimeoutException {
        public final Timeout timeout;
        private StageTimeoutException(Timeout timeout){super("Bounded preparation wait expired: "+timeout.id);this.timeout=timeout;}
    }
    public static final class EvidenceException extends IOException {
        private EvidenceException(Throwable cause){super("Protocol evidence could not be delivered; preparation stopped",cause);}
    }
    public static final class StoppedException extends IllegalStateException {
        private StoppedException(){super("Preparation is stopped or terminal");}
    }
    private static final class Phase {
        final String stage,id;final long budgetMs,started=System.nanoTime();final boolean posted;
        // All start/cancel decisions are made while holding the controller monitor.
        final AtomicInteger state=new AtomicInteger(); // queued, running, finished, cancelled-before-start
        volatile Thread runner,dispatcher;FutureTask<?> task;boolean closed;
        Phase(String stage,String id,long budgetMs,boolean posted){this.stage=stage;this.id=id;this.budgetMs=budgetMs;this.posted=posted;}
        long remaining(){return TimeUnit.MILLISECONDS.toNanos(budgetMs)-(System.nanoTime()-started);}
    }
    private final Object lock=new Object();
    private final String runId;private final Sink sink;private final TimeoutListener listener;private final long evidenceBudgetMs;
    private final ExecutorService evidence;
    private final long origin=System.nanoTime();private long sequence,lastNs;
    private final List<Map<String,Object>> retained=new ArrayList<>();private final Deque<Phase> phases=new ArrayDeque<>();
    private final Set<String> identities=new HashSet<>();private final List<String> cases=new ArrayList<>(),diagnosticErrors=new ArrayList<>();
    private String owner,terminal;private int assertions;private boolean startup,prepared,stopped,evidenceFailed;

    public BoundedStage(String runId,Sink sink,TimeoutListener listener){this(runId,sink,listener,1_000);}
    /** Evidence delivery is also bounded. Sinks must not call back into this controller. */
    public BoundedStage(String runId,Sink sink,TimeoutListener listener,long evidenceBudgetMs){
        this.runId=uuid(runId);this.sink=Objects.requireNonNull(sink,"sink");this.listener=listener;
        if(evidenceBudgetMs<=0||evidenceBudgetMs>1_000)throw new IllegalArgumentException("evidence budget must be 1..1000ms");
        this.evidenceBudgetMs=evidenceBudgetMs;
        evidence=Executors.newSingleThreadExecutor(r->daemon("scanner-stage-evidence",r));
    }
    public boolean stopped(){synchronized(lock){return stopped;}}
    /** Retained even when the external evidence sink fails; contains no exception messages. */
    public List<Map<String,Object>> events(){synchronized(lock){return Collections.unmodifiableList(new ArrayList<>(retained));}}
    public <T>T run(String stage,String id,long budgetMs,Callable<T> action)throws Exception {
        return execute(stage,id,budgetMs,null,action);
    }
    /** Dispatch itself and the posted work share one deadline. No interrupt is sent to a posted thread. */
    public <T>T post(String stage,String id,long budgetMs,Consumer<Runnable> dispatcher,Callable<T> action)throws Exception {
        return execute(stage,id,budgetMs,Objects.requireNonNull(dispatcher,"dispatcher"),action);
    }
    private <T>T execute(String stage,String id,long budgetMs,Consumer<Runnable> dispatcher,Callable<T> action)throws Exception {
        Objects.requireNonNull(action,"action");Phase p=begin(stage,id,budgetMs,dispatcher!=null);
        FutureTask<T> work=new FutureTask<>(()->{
            synchronized(lock){
                if(stopped||p.closed)throw new StoppedException();
                if(p.remaining()<=0)throw new TimeoutException();
                if(!p.state.compareAndSet(0,1))throw new StoppedException();
                p.runner=Thread.currentThread();
            }
            try{return action.call();}finally{p.state.compareAndSet(1,2);}
        });
        synchronized(lock){p.task=work;if(p.closed){work.cancel(false);throw new StoppedException();}}
        try{
            if(p.remaining()<=0)throw new TimeoutException();
            if(dispatcher==null)daemon("scanner-stage-worker",work).start();
            else{
                FutureTask<Void> dispatch=new FutureTask<>(()->{dispatcher.accept(work);return null;});
                p.dispatcher=daemon("scanner-stage-dispatch",dispatch);p.dispatcher.start();await(dispatch,p);
            }
            T value=await(work,p);
            synchronized(lock){
                if(stopped||p.closed)throw new StoppedException();
                if(p.remaining()<=0)throw new TimeoutException();
                if("preparation".equals(p.stage)&&phases.size()==1&&owner==null)throw new IllegalStateException("preparation requires a synthetic owner");
                end(p,"OK","COMPLETED",null);
            }
            return value;
        }catch(TimeoutException expired){throw timedOut(p);}
        catch(ExecutionException failed){
            Throwable cause=failed.getCause();stop(p,"ERROR",cause);
            if(cause instanceof Exception)throw (Exception)cause;
            if(cause instanceof Error)throw (Error)cause;
            throw new IOException("Preparation action failed");
        }catch(InterruptedException interrupted){stop(p,"STOPPED",interrupted);Thread.currentThread().interrupt();throw interrupted;}
        catch(Exception failed){stop(p,"ERROR",failed);throw failed;}
        catch(Error failed){stop(p,"ERROR",failed);throw failed;}
    }
    private static <T>T await(Future<T> future,Phase p)throws Exception {
        long remaining=p.remaining();if(remaining<=0)throw new TimeoutException();return future.get(remaining,TimeUnit.NANOSECONDS);
    }
    private Phase begin(String stage,String id,long budgetMs,boolean posted)throws EvidenceException {
        budget(budgetMs);identifier(id);
        synchronized(lock){
            ready();
            if(!"startup".equals(stage)&&!"preparation".equals(stage))throw new IllegalArgumentException("phase must be startup or preparation");
            if("startup".equals(stage)?startup||prepared||!phases.isEmpty():!startup||prepared||!phases.isEmpty()&&(!"preparation".equals(phases.peekLast().stage)||phases.peekLast().runner!=Thread.currentThread()))throw new IllegalStateException("phase order or caller mismatch");
            if(!identities.add(id))throw new IllegalArgumentException("duplicate identity");
            Phase p=new Phase(stage,id,budgetMs,posted);phases.addLast(p);
            try{emit("BEGIN",stage,id,fields("budget_ms",budgetMs));}catch(EvidenceException failure){stop(p,"STOPPED",failure);throw failure;}
            return p;
        }
    }
    public void owner(String ownerUuid)throws Exception {
        String value=uuid(ownerUuid);
        synchronized(lock){
            ready();Phase p=phases.peekLast();
            if(owner!=null||p==null||!"preparation".equals(p.stage)||p.runner!=Thread.currentThread())throw new IllegalStateException("owner requires the active preparation action");
            owner=value;emit("OWNER","preparation",p.id,fields("owner",owner,"title","SCAN_SYNTH_"+runId));
        }
    }
    /** Java's case keyword cannot be a method name. One case has one explicit RESULT assertion. */
    public void assertCase(String id,boolean passed)throws Exception {
        identifier(id);
        synchronized(lock){
            ready();if(!prepared||!phases.isEmpty()||owner==null)throw new IllegalStateException("cases require completed preparation");
            if(!identities.add(id))throw new IllegalArgumentException("duplicate identity");
            Phase p=new Phase("case",id,CASE_BUDGET_MS,false);p.state.set(1);p.runner=Thread.currentThread();phases.addLast(p);
            try{
                emit("BEGIN","case",id,fields("budget_ms",CASE_BUDGET_MS,"owner",owner));
                assertions++;emit("ASSERT","case",id,fields("owner",owner,"assertion_id","RESULT","passed",passed));
                if(p.remaining()<=0)throw timedOut(p);
                cases.add(id);end(p,passed?"PASS":"FAIL","COMPLETED",fields("owner",owner,"assertions",1));
                if(!passed){stopped=true;throw new IllegalStateException("Preparation assertion failed");}
            }catch(Exception failed){stop(p,"ERROR",failed);throw failed;}
        }
    }
    public void finish(String status)throws Exception {
        if(!"PREPARED".equals(status)&&!"STOPPED".equals(status))throw new IllegalArgumentException("terminal must be PREPARED or STOPPED");
        synchronized(lock){
            if(terminal!=null){if(terminal.equals(status))return;throw new IllegalStateException("terminal already recorded");}
            if("PREPARED".equals(status)&&(stopped||evidenceFailed||!startup||!prepared||!phases.isEmpty()||cases.isEmpty()))throw new StoppedException();
            if("STOPPED".equals(status)){stop(null,"STOPPED",null);stopped=true;}
            terminal=status;
            try{emit("TERMINAL","terminal","RUNNER",fields("status",status,"owner",owner,"cases",Collections.unmodifiableList(new ArrayList<>(cases)),"assertions",assertions,"evidence_failed",evidenceFailed,"diagnostic_errors",Collections.unmodifiableList(new ArrayList<>(diagnosticErrors))));}
            catch(EvidenceException failure){
                // External delivery is unknown. Do not replay a possibly delivered terminal.
                // The retained record must never advertise successful preparation after this failure.
                terminal="STOPPED";Map<String,Object> failed=new LinkedHashMap<>(retained.get(retained.size()-1));
                failed.put("status","STOPPED");failed.put("evidence_failed",true);retained.set(retained.size()-1,Collections.unmodifiableMap(failed));throw failure;
            }
            finally{evidence.shutdown();}
        }
    }
    private StageTimeoutException timedOut(Phase p){
        Timeout timeout;
        synchronized(lock){stop(p,"TIMEOUT",null);timeout=new Timeout(p);}
        StageTimeoutException failure=new StageTimeoutException(timeout);
        if(listener!=null){
            FutureTask<Void> diagnostic=new FutureTask<>(()->{listener.onTimeout(timeout);return null;});daemon("scanner-stage-diagnostic",diagnostic).start();
            try{diagnostic.get(evidenceBudgetMs,TimeUnit.MILLISECONDS);}catch(Exception error){synchronized(lock){diagnosticErrors.add(error instanceof ExecutionException&&error.getCause()!=null?error.getCause().getClass().getName():error.getClass().getName());}failure.addSuppressed(error);}
        }
        return failure;
    }
    private void end(Phase p,String status,String operationState,Map<String,Object> more)throws EvidenceException {
        if(p.closed)return;
        if(phases.peekLast()!=p)throw new IllegalStateException("unclosed nested phase");
        p.closed=true;phases.removeLast();Map<String,Object> values=fields("status",status,"operation_state",operationState);
        if(more!=null)values.putAll(more);
        if("case".equals(p.stage)&&!values.containsKey("assertions")){values.put("owner",owner);values.put("assertions",0);}
        emit("END",p.stage,p.id,values);
        if("OK".equals(status)){if("startup".equals(p.stage))startup=true;else if("preparation".equals(p.stage)&&phases.isEmpty())prepared=true;}
    }
    /** Caller holds lock. Late worker completions never emit another END or mutate terminal state. */
    private void stop(Phase cause,String status,Throwable failure){
        synchronized(lock){
            stopped=true;
            while(!phases.isEmpty()){
                Phase p=phases.peekLast();if(p.state.compareAndSet(0,3)&&p.task!=null)p.task.cancel(false);
                Map<String,Object> extra=fields("dispatch_state",p.state.get()==3?"CANCELLED_BEFORE_START":p.state.get()==1?"RUNNING":"FINISHED");
                if(failure!=null)extra.put("error_type",failure.getClass().getName());
                try{end(p,p==cause?status:"STOPPED","UNKNOWN",extra);}catch(EvidenceException ignored){/* Retained events remain the source of failure evidence. */}
            }
        }
    }
    private void ready(){if(stopped||terminal!=null)throw new StoppedException();}
    private void emit(String kind,String stage,String id,Map<String,Object> values)throws EvidenceException {
        long elapsed=Math.max(0,System.nanoTime()-origin);lastNs=Math.max(lastNs,elapsed);
        Map<String,Object> event=fields("protocol",PROTOCOL,"suite","scan","scope","prepare","run_id",runId,"seq",++sequence,"mono_ns",lastNs,"thread",threadLabel(),"kind",kind,"stage",stage,"id",id);event.putAll(values);
        Map<String,Object> frozen=Collections.unmodifiableMap(event);retained.add(frozen);
        if(evidenceFailed)return;
        try{
            Future<?> delivered=evidence.submit(()->{try{sink.accept(frozen);}catch(Exception failure){throw new CompletionException(failure);}});
            long wait=TimeUnit.MILLISECONDS.toNanos(evidenceBudgetMs);Phase active=phases.peekLast();
            if(active!=null&&!kind.equals("END"))wait=Math.min(wait,Math.max(1,active.remaining()));
            delivered.get(wait,TimeUnit.NANOSECONDS);
        }
        catch(Exception failed){evidenceFailed=true;stopped=true;throw new EvidenceException(failed);}
    }
    private static String threadLabel(){String name=Thread.currentThread().getName();return name.equals("scanner-stage-worker")||name.equals("scanner-stage-dispatch")?name:"scanner-stage-caller";}
    private static Thread daemon(String name,Runnable action){Thread t=new Thread(action,name);t.setDaemon(true);return t;}
    private static String uuid(String value){try{if(value!=null&&UUID.fromString(value).toString().equals(value))return value;}catch(IllegalArgumentException ignored){}throw new IllegalArgumentException("canonical UUID required");}
    private static void identifier(String value){if(value==null||!value.matches("[A-Z][A-Z0-9_]{0,79}"))throw new IllegalArgumentException("invalid protocol identifier");}
    private static void budget(long value){if(value<=0||value>MAX_BUDGET_MS)throw new IllegalArgumentException("budget must be 1..420000ms");}
    private static Map<String,Object> fields(Object... pairs){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;}
}
