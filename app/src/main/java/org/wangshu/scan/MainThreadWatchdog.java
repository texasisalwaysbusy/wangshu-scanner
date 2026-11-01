package org.wangshu.scan;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.SystemClock;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Debug-only, process-local stall evidence. No text, image, document ID, or network access. */
final class MainThreadWatchdog implements AutoCloseable {
    static final long INTERVAL_MS=500,STALL_MS=2000;
    static final int MAX_REPORTS=8,MAX_FRAMES=32;
    interface Clock{long now();}
    interface Queue{void post(Runnable work);void remove(Runnable work);}
    interface Sink{void save(long waitingMs,String phase,Thread main);}
    /** One outstanding ping, one report per stall, no per-frame allocation. Host-testable. */
    static final class Probe {
        final Clock clock;final Queue queue;final Sink sink;final Thread main;
        boolean waiting,reported;long sent;int reports;
        volatile String phase="create";
        volatile boolean enabled;
        final Runnable heartbeat=()->{synchronized(this){waiting=false;}};
        Probe(Clock clock,Queue queue,Sink sink,Thread main){this.clock=clock;this.queue=queue;this.sink=sink;this.main=main;}
        void tick(){
            long delay=-1;String stage=null;
            synchronized(this){
                if(!enabled||reports>=MAX_REPORTS)return;
                long now=clock.now();
                if(!waiting){waiting=true;reported=false;sent=now;queue.post(heartbeat);}
                else if(!reported&&now-sent>=STALL_MS){reported=true;reports++;delay=now-sent;stage=phase;}
            }
            // Do not hold the heartbeat's monitor while suspending main for its stack.
            if(delay>=0)sink.save(delay,stage,main);
        }
        synchronized void stop(){enabled=false;waiting=false;reported=false;queue.remove(heartbeat);}
    }
    private final Probe probe;
    private final boolean debug;
    private Thread monitor;
    private volatile boolean closed;
    private volatile long monitorGeneration;
    private MainThreadWatchdog(boolean debug,Probe probe){this.debug=debug;this.probe=probe;}
    static MainThreadWatchdog forActivity(Activity activity,Handler ui){
        // Never opt in a release build; flags are read locally without changing system policy.
        boolean debug=(activity.getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0;
        Thread main=ui.getLooper().getThread();File folder=new File(activity.getFilesDir(),"diagnostics/ui-stalls");
        Clock clock=SystemClock::uptimeMillis;
        Queue queue=new Queue(){public void post(Runnable work){ui.post(work);}public void remove(Runnable work){ui.removeCallbacks(work);}};
        Sink sink=(delay,phase,thread)->writeSnapshot(folder,delay,phase,thread);
        return new MainThreadWatchdog(debug,new Probe(clock,queue,sink,main));
    }
    synchronized void start(){
        if(!debug||closed)return;probe.enabled=true;
        if(monitor!=null)return;
        long generation=++monitorGeneration;
        monitor=new Thread(()->{while(!closed&&generation==monitorGeneration){try{Thread.sleep(INTERVAL_MS);if(generation==monitorGeneration)probe.tick();}catch(InterruptedException e){return;}catch(Throwable ignored){/* Diagnostics must not crash the product. */}}},"scanner-ui-watchdog");monitor.setDaemon(true);monitor.setPriority(Thread.MIN_PRIORITY);monitor.start();
    }
    void phase(String value){probe.phase=value;}
    synchronized void stop(){monitorGeneration++;probe.stop();if(monitor!=null){monitor.interrupt();monitor=null;}}
    @Override public synchronized void close(){closed=true;stop();}
    private static void writeSnapshot(File folder,long delay,String phase,Thread main){
        // At most eight files across process restarts/Activity instances. Existing records
        // are never overwritten or removed; a full directory simply stops recording.
        try{
            if(!folder.isDirectory()&&!folder.mkdirs())return;
            File chosen=null;
            for(int i=0;i<MAX_REPORTS;i++){File slot=new File(folder,"stall-"+i+".txt");if(slot.createNewFile()){chosen=slot;break;}}
            if(chosen==null)return;
            StackTraceElement[] frames=main.getStackTrace();
            StringBuilder report=new StringBuilder(8192).append("schema=1\nscope=debug-own-main-thread-only; native RenderThread stack NOT_CAPTURED\n")
                .append("uptime_ms=").append(SystemClock.uptimeMillis()).append("\nwall_ms=").append(System.currentTimeMillis())
                .append("\nheartbeat_wait_ms=").append(delay).append("\nphase=").append(phase)
                .append("\nthread_state=").append(main.getState()).append("\nmain_stack:\n");
            for(int i=0;i<Math.min(MAX_FRAMES,frames.length);i++){String frame=frames[i].toString();report.append(frame,0,Math.min(frame.length(),240)).append('\n');}
            byte[] bytes=report.toString().getBytes(StandardCharsets.UTF_8);
            try(OutputStream out=new FileOutputStream(chosen)){out.write(bytes,0,Math.min(bytes.length,16384));}
        }catch(IOException|SecurityException ignored){/* Read-only/full storage must not affect scanning. */}
    }
}
