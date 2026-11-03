package org.wangshu.scan;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs the production coordinator, not a duplicated model or Android replacement. */
public final class EditorSaveFlowTests {
    static int checks,scenarios;
    interface Action {void run() throws Exception;}
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static void rejected(Action action)throws Exception{checks++;try{action.run();throw new AssertionError("Expected IOException");}catch(IOException expected){}}
    static EditorSaveFlow.Request begin(EditorSaveFlow flow,Object session,List<String> order,String page,boolean reviewed,boolean advance)throws Exception{
        return flow.begin(session,"doc",page,"baseline","edited",order,reviewed,advance);
    }
    static EditorSaveFlow.Saved commit(EditorSaveFlow f,EditorSaveFlow.Request q,EditorSaveFlow.Writer writer)throws Exception{
        return f.commit(q,"doc","baseline",q.order,writer);
    }
    static boolean finish(EditorSaveFlow f,EditorSaveFlow.Saved saved,Object session,String page){
        return f.finish(saved,session,"doc",page,"baseline","edited");
    }
    public static void main(String[] args)throws Exception{
        // Single/first/middle/last of each order length: no wrap, exact successor ID.
        for(int size=1;size<=12;size++)for(int index=0;index<size;index++){
            scenarios++;ArrayList<String> order=new ArrayList<>();for(int i=0;i<size;i++)order.add("p"+i);
            String page=order.get(index);EditorSaveFlow f=new EditorSaveFlow();Object session=new Object();
            EditorSaveFlow.Request q=begin(f,session,order,page,true,true);
            check(f.hasPending(),"synchronous busy token");
            check(Objects.equals(q.nextPageId,index+1<size?"p"+(index+1):null),"successor or terminal");
            AtomicInteger writes=new AtomicInteger();
            EditorSaveFlow.Saved result=commit(f,q,()->{check(f.hasPending(),"token holds during writer");writes.incrementAndGet();return true;});
            check(writes.get()==1,"exactly one durable writer");
            check(f.sameOrder(result,order),"stable order");
            check(finish(f,result,session,page),"current editor may deliver");
            check(!finish(f,result,session,page),"completion consumed once");
            check(!f.hasPending(),"token released");
        }
        scenarios++;{
            EditorSaveFlow f=new EditorSaveFlow();Object session=new Object();ArrayList<String> order=new ArrayList<>(List.of("a","b","c"));
            EditorSaveFlow.Request q=begin(f,session,order,"a",true,true);order.set(1,"replacement");
            check(q.nextPageId.equals("b"),"request freezes successor");check(q.order.equals(List.of("a","b","c")),"request freezes order");
            rejected(()->begin(f,session,List.of("a"),"a",true,true));
            AtomicInteger writes=new AtomicInteger();
            rejected(()->f.commit(q,"doc","baseline",order,()->{writes.incrementAndGet();return true;}));
            check(writes.get()==0,"reorder rejected before persistence");
            f.fail(q);check(!f.hasPending(),"failure unlocks same draft retry");
        }
        for(String[] bad:new String[][]{{"other","baseline"},{"doc","changed"},{"doc",null}}){
            scenarios++;EditorSaveFlow f=new EditorSaveFlow();EditorSaveFlow.Request q=begin(f,new Object(),List.of("a","b"),"a",true,true);
            AtomicInteger writes=new AtomicInteger();
            rejected(()->f.commit(q,bad[0],bad[1],q.order,()->{writes.incrementAndGet();return true;}));
            check(writes.get()==0,"version/doc conflict must not write");f.fail(q);
        }
        for(int failure=0;failure<3;failure++){
            scenarios++;EditorSaveFlow f=new EditorSaveFlow();Object session=new Object();EditorSaveFlow.Request q=begin(f,session,List.of("a","b"),"a",true,true);
            final int type=failure;AtomicInteger writes=new AtomicInteger();
            rejected(()->commit(f,q,()->{writes.incrementAndGet();if(type==0)throw new IOException("injected IO");
                if(type==1)throw new IOException("injected uncertain manifest");return false;}));
            check(writes.get()==1,"failure writer once");check(q.edited.equals("edited"),"failed edit snapshot retained");
            check(f.hasPending(),"failure cannot invent completion");f.fail(q);check(!f.hasPending(),"explicit failure release");
            EditorSaveFlow.Request retry=begin(f,session,List.of("a","b"),"a",true,true);check(retry!=q,"fresh retry token");f.fail(retry);
        }
        scenarios++;{
            EditorSaveFlow f=new EditorSaveFlow();Object s=new Object();EditorSaveFlow.Request q=begin(f,s,List.of("a","b"),"a",true,true);
            try{commit(f,q,()->{throw new OutOfMemoryError("injected");});throw new AssertionError("Expected OOM");}catch(OutOfMemoryError expected){checks++;}
            check(q.edited.equals("edited"),"OOM retains frozen edit");f.fail(q);
        }
        scenarios++;{
            EditorSaveFlow f=new EditorSaveFlow();Object s=new Object();
            rejected(()->begin(f,s,List.of("a","b"),"a",false,true));
            check(!f.hasPending(),"unreviewed next never locks or writes");
            EditorSaveFlow.Request q=begin(f,s,List.of("a"),"a",false,false);
            check(!q.advance&&q.nextPageId==null,"ordinary save does not bypass review into next");
            f.fail(q);
            rejected(()->begin(f,s,List.of("a","a"),"a",true,true));
            rejected(()->begin(f,s,Arrays.asList("a",null),"a",true,true));
            rejected(()->begin(f,s,List.of("b"),"a",true,true));
            rejected(()->f.begin(null,"doc","a","baseline","edited",List.of("a"),true,true));
        }
        for(int stale=0;stale<6;stale++){
            scenarios++;EditorSaveFlow f=new EditorSaveFlow();Object s=new Object();EditorSaveFlow.Request q=begin(f,s,List.of("a","b"),"a",true,true);
            EditorSaveFlow.Saved result=commit(f,q,()->true);
            check(!f.finish(result,stale==0?new Object():s,stale==1?"other":"doc",stale==2?"b":"a",
                    stale==3?"changed":"baseline",stale==4?"changed":stale==5?null:"edited"),"stale UI cannot navigate");
        }
        scenarios++;{
            EditorSaveFlow f=new EditorSaveFlow();Object s=new Object();EditorSaveFlow.Request q=begin(f,s,List.of("a","b","c"),"b",true,true);
            EditorSaveFlow.Saved result=commit(f,q,()->true);
            check(!f.sameOrder(result,List.of("c","a","b")),"post-commit reorder blocks advancement");
            check(!f.sameOrder(result,List.of("a","b")),"post-commit removal blocks advancement");
            check(!f.sameOrder(result,List.of("a","b","c","d")),"post-commit append safely stops");
            check(finish(f,result,s,"b"),"durable save still acknowledged without jumping");
        }
        scenarios++;{
            EditorSaveFlow f=new EditorSaveFlow();Object old=new Object();EditorSaveFlow.Request q=begin(f,old,List.of("a","b"),"a",true,true);
            EditorSaveFlow.Saved result=commit(f,q,()->true);f.abandon();
            check(!finish(f,result,old,"a"),"destroyed editor cannot deliver");
            Object next=new Object();EditorSaveFlow.Request newRequest=begin(f,next,List.of("a","b"),"a",true,true);
            check(!finish(f,result,next,"a"),"old result cannot finish new operation");
            f.fail(q);check(f.hasPending(),"old failure cannot unlock new request");
            f.fail(newRequest);
        }
        scenarios++;{
            EditorSaveFlow f=new EditorSaveFlow();Object s=new Object();EditorSaveFlow.Request q=begin(f,s,List.of("a","b"),"a",true,true);
            f.abandon();AtomicInteger writes=new AtomicInteger();
            rejected(()->commit(f,q,()->{writes.incrementAndGet();return true;}));check(writes.get()==0,"abandoned queued save never starts");
        }
        // Actual production recovery policy for saved-state/late-commit races.
        for(boolean submitted:new boolean[]{false,true}){
            scenarios++;
            check(EditorSaveFlow.restore("baseline","baseline","edited",submitted)==EditorSaveFlow.Restoration.BASELINE,"failed/not-yet-committed edit restored");
            check(EditorSaveFlow.restore("edited","baseline","edited",submitted)==(submitted?EditorSaveFlow.Restoration.COMMITTED:EditorSaveFlow.Restoration.CONFLICT),"only submitted matching save acknowledged");
            check(EditorSaveFlow.restore("other","baseline","edited",submitted)==EditorSaveFlow.Restoration.CONFLICT,"external edit never overwritten");
            check(EditorSaveFlow.restore("edited",null,"edited",submitted)==EditorSaveFlow.Restoration.CONFLICT,"missing baseline rejected");
        }
        System.out.println("PASS "+scenarios+" scenarios, "+checks+" assertions against production EditorSaveFlow; no Android/device UI or Store durability execution");
    }
}
