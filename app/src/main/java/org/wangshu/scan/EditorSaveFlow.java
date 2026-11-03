package org.wangshu.scan;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Durable-save navigation contract. No Android dependencies; UI lifetime remains explicit. */
final class EditorSaveFlow {
    interface Writer { boolean save() throws Exception; }
    interface VersionSource { String version() throws Exception; }
    interface Retention { void retain() throws Exception; }
    interface Transaction<T> { T run() throws Exception; }
    static <T> T afterRecovery(Retention retention,Transaction<T> transaction)throws Exception{
        retention.retain();return transaction.run();
    }
    private static final java.util.concurrent.ConcurrentHashMap<String,String> OBSERVED=new java.util.concurrent.ConcurrentHashMap<>();
    static void observePersisted(String document,String version){if(document!=null&&version!=null)OBSERVED.put(document,version);}
    static String observedVersion(String document){return document==null?null:OBSERVED.get(document);}
    static boolean observationCurrent(long captured,long current,boolean resumed,boolean stateSaved){
        return captured==current&&resumed&&!stateSaved;
    }
    static final class Persisted {
        final String document,version;final List<String> order;
        Persisted(String doc,String version,List<String> order){document=doc;this.version=version;this.order=java.util.Collections.unmodifiableList(new ArrayList<>(order));}
    }
    interface PersistedReader { Persisted read()throws Exception; }
    static Persisted checkPersisted(PersistedReader reader)throws Exception{return reader.read();}
    static boolean permitsNavigation(Saved saved,Persisted disk,String latestObserved){
        return disk!=null&&!saved.version.isEmpty()&&Objects.equals(saved.request.documentId,disk.document)
                &&Objects.equals(saved.version,disk.version)&&Objects.equals(disk.version,latestObserved)
                &&saved.request.order.equals(disk.order);
    }
    static final class Request {
        final Object session;
        final String documentId,pageId,baseline,edited,nextPageId;
        final List<String> order;
        final boolean advance;
        Request(Object session,String doc,String page,String baseline,String edited,List<String> order,boolean advance){
            this.session=session;documentId=doc;pageId=page;this.baseline=baseline;this.edited=edited;
            this.order=java.util.Collections.unmodifiableList(new ArrayList<>(order));this.advance=advance;
            int index=order.indexOf(page);nextPageId=advance&&index+1<order.size()?order.get(index+1):null;
        }
    }
    static final class Saved {
        final Request request;final String version;
        private Saved(Request request){this(request,"");}
        private Saved(Request request,String version){this.request=request;this.version=version==null?"":version;}
    }
    // begin/finish/fail are called by the UI thread; worker observes cancellation through volatile.
    private volatile Request active;
    Request begin(Object session,String doc,String page,String baseline,String edited,List<String> order,boolean reviewed,boolean advance)throws IOException{
        if(active!=null)throw new IOException("本页保存尚未结束，请稍候");
        if(session==null||doc==null||page==null||baseline==null||edited==null||order==null
                ||!order.contains(page)||new HashSet<>(order).size()!=order.size()||new HashSet<>(order).contains(null))
            throw new IOException("编辑会话或页序已变化，请核对当前页面");
        if(advance&&!reviewed)throw new IOException("请先打开四角裁切，核对四角或恢复全图并确认，再保存并下一页");
        Request value=new Request(session,doc,page,baseline,edited,order,advance);active=value;return value;
    }
    boolean hasPending(){return active!=null;}
    /** Caller holds Store.FILE_LOCK across these checks and the actual manifest transaction. */
    Saved commit(Request request,String currentDocument,String currentBaseline,List<String> currentOrder,Writer writer)throws Exception{
        if(active!=request)throw new IOException("编辑会话已结束，未开始本次保存");
        if(!Objects.equals(request.documentId,currentDocument)||!Objects.equals(request.baseline,currentBaseline)
                ||!request.order.equals(currentOrder))
            throw new IOException("页面或页序在编辑期间已变化，本次草稿未覆盖；请核对文稿");
        // Only a confirmed durable writer result may construct a navigation result.
        if(!writer.save())throw new IOException("本页未确认保存，停留当前页；编辑草稿保留");
        return new Saved(request);
    }
    Saved commit(Request request,String document,String baseline,List<String> order,Writer writer,VersionSource versions)throws Exception{
        Saved saved=commit(request,document,baseline,order,writer);
        String version=versions.version();if(version==null||version.isEmpty())throw new IOException("保存后的文稿版本未确认，未自动跳页");
        return new Saved(saved.request,version);
    }
    /** Consume once. Paused/saved-state Activities defer this call until resume. */
    boolean finish(Saved saved,Object session,String doc,String page,String baseline,String edited){
        Request request=saved.request;if(active!=request)return false;active=null;
        return request.session==session&&Objects.equals(request.documentId,doc)&&Objects.equals(request.pageId,page)
                &&Objects.equals(request.baseline,baseline)&&Objects.equals(request.edited,edited);
    }
    boolean sameOrder(Saved saved,List<String> currentOrder){return saved.request.order.equals(currentOrder);}
    void fail(Request request){if(active==request)active=null;}
    enum Restoration { BASELINE, COMMITTED, CONFLICT }
    static Restoration restore(String saved,String baseline,String edited,boolean submitted){
        if(baseline!=null&&Objects.equals(saved,baseline))return Restoration.BASELINE;
        if(baseline!=null&&submitted&&Objects.equals(saved,edited))return Restoration.COMMITTED;
        return Restoration.CONFLICT;
    }
    void abandon(){active=null;}
}
