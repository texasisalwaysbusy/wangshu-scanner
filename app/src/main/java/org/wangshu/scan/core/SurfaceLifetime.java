package org.wangshu.scan.core;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Retire a surface only after its last asynchronous producer has fully closed. */
public final class SurfaceLifetime<T> {
    private final IdentityHashMap<T,Set<Object>> owners=new IdentityHashMap<>();
    private final IdentityHashMap<T,Runnable> pending=new IdentityHashMap<>();
    private final Set<T> retired=Collections.newSetFromMap(new IdentityHashMap<>());
    public synchronized boolean acquire(T surface,Object owner){
        if(surface==null||owner==null)throw new IllegalArgumentException("Missing surface owner");
        if(retired.contains(surface))return false;
        return owners.computeIfAbsent(surface,key->Collections.newSetFromMap(new IdentityHashMap<>())).add(owner);
    }
    public synchronized boolean hasOwners(){return !owners.isEmpty();}
    public synchronized boolean isRetired(T surface){return retired.contains(surface);}
    public void retire(T surface,Runnable release){
        Runnable ready=null;
        synchronized(this){if(!retired.add(surface))return;if(owners.containsKey(surface))pending.put(surface,release);else ready=release;}
        if(ready!=null)ready.run();
    }
    public void release(T surface,Object owner){
        Runnable ready=null;
        synchronized(this){Set<Object> using=owners.get(surface);if(using==null||!using.remove(owner))return;if(using.isEmpty()){owners.remove(surface);ready=pending.remove(surface);}}
        if(ready!=null)ready.run();
    }
}
