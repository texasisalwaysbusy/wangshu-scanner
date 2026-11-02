from pathlib import Path
from support import PROJECT, OUTPUT, JAVA
import subprocess,json,hashlib,uuid
root=OUTPUT;src=PROJECT/'app/src/main/java/org/wangshu/scan/CaptureBatch.java';s=src.read_text();lease=s[s.index('    private static final java.util.concurrent.locks.ReentrantLock LOCK'):s.index('    static final class Shot')];latest=s[s.index('    static Batch latestOpen('):s.rfind('\n}')];start=s.index('    private static void requireNotInterrupted(');require=s[start:s.index('\n',start)];r=root/('green-'+str(uuid.uuid4()));r.mkdir()
code=r'''import java.io.*;import java.util.*;import java.util.concurrent.*;
class Context{}class Store{static void safeId(String d){}}
class BatchFence{static int mode,calls;static class Uncertain extends IOException{Uncertain(String s){super(s);}Uncertain(String s,Throwable t){super(s,t);}}static class Candidates{boolean directoryMissing=true;List<String> ids=new ArrayList<>();}static Candidates enumerate(java.nio.file.Path p){calls++;Candidates c=new Candidates();if(mode==1)Thread.currentThread().interrupt();if(mode==2){c.directoryMissing=false;c.ids.add("x");}return c;}}
public class Green{
LEASE
REQUIRE
static Set<String> UNCERTAIN=new HashSet<>();static class Batch{boolean closed;String document;long created;}static File folder(Context c){return new File("unused");}static Batch load(Context c,String id)throws Exception{throw new InterruptedIOException("synthetic inner cancellation");}
LATEST
static void check(boolean x,String m){if(!x)throw new AssertionError(m);}static int checks;
public static void main(String[]args)throws Exception{
 Thread.currentThread().interrupt();boolean rejected=false;try{latestOpen(new Context(),"doc");}catch(InterruptedIOException expected){rejected=true;}check(rejected&&Thread.currentThread().isInterrupted()&&BatchFence.calls==0,"preinterrupt enters body");Thread.interrupted();checks++;
 CountDownLatch done=new CountDownLatch(1);Throwable[] failure={null};boolean[] preserved={false};Thread t=new Thread(()->{try{latestOpen(new Context(),"doc");failure[0]=new AssertionError("body entered");}catch(InterruptedIOException e){preserved[0]=Thread.currentThread().isInterrupted();}catch(Throwable e){failure[0]=e;}finally{done.countDown();}});
 try(BatchLease held=acquireBatch()){t.start();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(!LOCK.hasQueuedThread(t)&&System.nanoTime()<until)Thread.sleep(1);check(LOCK.hasQueuedThread(t),"not queued");t.interrupt();check(done.await(2,TimeUnit.SECONDS),"waiter did not cancel while owner holds");check(preserved[0]&&failure[0]==null&&BatchFence.calls==0,"waiter cancellation broken");check(LOCK.getHoldCount()==1,"waiter released owner lock");}checks++;
 try(BatchLease outer=acquireBatch()){try{try(BatchLease inner=acquireBatch()){check(LOCK.getHoldCount()==2,"not reentrant");throw new IOException("body failure");}}catch(IOException expected){}check(LOCK.getHoldCount()==1,"inner exception did not release exactly once");}check(!LOCK.isLocked(),"outer not released");checks++;
 BatchLease one=acquireBatch();one.close();one.close();check(!LOCK.isLocked(),"double close");checks++;
 BatchFence.mode=1;rejected=false;try{latestOpen(new Context(),"doc");}catch(InterruptedIOException expected){rejected=true;}finally{Thread.interrupted();}check(rejected&&!LOCK.isLocked(),"directoryMissing ignores enumerate cancellation");checks++;
 BatchFence.mode=2;rejected=false;try{latestOpen(new Context(),"doc");}catch(InterruptedIOException expected){rejected=true;}check(rejected&&!LOCK.isLocked(),"inner cancellation wrapped or lock leaked");checks++;
 BatchFence.mode=0;check(latestOpen(new Context(),"doc")==null&&!LOCK.isLocked(),"later normal call broken");checks++;
 System.out.println("PASS "+checks+" actual lease/latestOpen host scenarios; pre/wait cancellation avoids body, nested/exception/double release, enumerate and nested cancellation, later success");
}
}'''.replace('LEASE',lease).replace('REQUIRE',require).replace('LATEST',latest)
(r/'Green.java').write_text(code);c=subprocess.run([JAVA,'-m','jdk.compiler/com.sun.tools.javac.Main','-d',str(r/'classes'),str(r/'Green.java')],capture_output=True,text=True,timeout=20);res={'source_sha256':hashlib.sha256(src.read_bytes()).hexdigest(),'compile_exit':c.returncode,'compile_stderr':c.stderr}
if c.returncode==0:
 p=subprocess.run([JAVA,'-cp',str(r/'classes'),'Green'],capture_output=True,text=True,timeout=10);res.update(run_exit=p.returncode,stdout=p.stdout,stderr=p.stderr)
(r/'result.json').write_text(json.dumps(res,indent=2));print(r);print(json.dumps(res,indent=2))

raise SystemExit(0 if res.get("compile_exit")==0 and res.get("run_exit")==0 else 1)
