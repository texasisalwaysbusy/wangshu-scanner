import org.wangshu.scan.core.SurfaceLifetime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Deterministic producer-close races, no simulated claim of a native-camera fix. */
public final class SurfaceLifetimeTests {
    static int checks;static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    public static void main(String[] args)throws Exception{
        SurfaceLifetime<Object> gate=new SurfaceLifetime<>();Object a=new Object(),b=new Object(),owner1=new Object(),owner2=new Object();AtomicInteger freed=new AtomicInteger();
        check(gate.acquire(a,owner1),"producer acquires live surface");gate.retire(a,freed::incrementAndGet);check(freed.get()==0,"destroyed view does not free a still-used camera surface");check(!gate.acquire(a,owner2),"retired surface cannot accept a new producer");gate.release(a,owner2);check(freed.get()==0,"unrelated close callback cannot release the owner");gate.release(a,owner1);check(freed.get()==1&&!gate.hasOwners(),"final device-close callback releases exactly once");gate.release(a,owner1);gate.retire(a,freed::incrementAndGet);check(freed.get()==1,"duplicate close/destroy callbacks do not double free");
        check(gate.acquire(b,owner1)&&gate.acquire(b,owner2),"overlapping asynchronous producers retain independent identities");gate.retire(b,freed::incrementAndGet);gate.release(b,owner1);check(freed.get()==1&&gate.hasOwners(),"closing one producer preserves the remaining producer surface");gate.release(b,owner2);check(freed.get()==2&&!gate.hasOwners(),"last overlapping producer permits destruction");
        Object c=new Object();gate.retire(c,freed::incrementAndGet);check(freed.get()==3,"unused destroyed view releases immediately");
        Object d=new Object();gate.acquire(d,owner1);gate.release(d,owner1);check(!gate.isRetired(d)&&gate.acquire(d,owner2),"paused producer may close while live view is later reused");gate.release(d,owner2);
        Object e=new Object();gate.acquire(e,owner1);gate.acquire(e,owner2);AtomicInteger concurrent=new AtomicInteger();gate.retire(e,concurrent::incrementAndGet);CountDownLatch go=new CountDownLatch(1);Thread x=new Thread(()->{try{go.await();gate.release(e,owner1);}catch(Exception error){throw new RuntimeException(error);}}),y=new Thread(()->{try{go.await();gate.release(e,owner2);}catch(Exception error){throw new RuntimeException(error);}});x.start();y.start();go.countDown();x.join(3000);y.join(3000);check(!x.isAlive()&&!y.isAlive()&&concurrent.get()==1,"concurrent close callbacks destroy once without hanging");
        Object f=new Object();gate.acquire(f,owner1);AtomicBoolean nested=new AtomicBoolean();gate.retire(f,()->{Thread worker=new Thread(()->{gate.hasOwners();nested.set(true);});worker.start();try{worker.join(1000);}catch(Exception error){throw new RuntimeException(error);}});gate.release(f,owner1);check(nested.get(),"destruction callback runs outside ownership lock");
        System.out.println("PASS "+checks+" host surface lifetime checks; actual Android/native crash regression still required");
    }
}
