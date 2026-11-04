package org.wangshu.scan;
import android.app.*;import android.os.*;import android.content.*;import android.net.Uri;import android.view.*;import android.view.accessibility.*;import android.widget.*;import org.json.*;import java.io.*;import java.lang.reflect.*;import java.util.*;import java.util.concurrent.Callable;import java.nio.charset.StandardCharsets;import java.util.concurrent.*;import java.util.concurrent.atomic.AtomicInteger;
/** Preparation-only diagnostic: creates one owned synthetic document, then launches/imports.
 * Legacy functional helpers below are inactive and cannot record a functional PASS.
 * No generic UI command, cross-app input or screenshot entry point is enabled.
 * PREPARED describes preparation assertions only, never product acceptance.
 */
public final class ScanInstrumentation extends Instrumentation {
 Activity activity;Object store,doc;String owner;Bundle args;File evidence;JSONArray results=new JSONArray();boolean fatal;
 BoundedStage control;UiAutomation automation;String runId;Thread runnerThread;volatile boolean evidenceFailed;int preparedPages;boolean preparedOwner;AtomicInteger uiSequence=new AtomicInteger(),waitSequence=new AtomicInteger();
 static final String SAVE_RETURN="保存本页并返回";
 @Override public void onCreate(Bundle b){super.onCreate(b);args=b==null?new Bundle():b;start();}
 static Class<?> cls(String s)throws Exception{return Class.forName("org.wangshu.scan."+s);}
 static Object get(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
 static void set(Object o,String n,Object v)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
 static Object call(Object o,String n,Object... a)throws Exception{for(Method m:o.getClass().getDeclaredMethods())if(m.getName().equals(n)&&m.getParameterCount()==a.length){Class<?>[] t=m.getParameterTypes();boolean ok=true;for(int i=0;i<a.length;i++)if(a[i]!=null&&!t[i].isInstance(a[i])&&!(t[i]==boolean.class&&a[i] instanceof Boolean)&&!(t[i]==int.class&&a[i] instanceof Integer))ok=false;if(ok){m.setAccessible(true);try{return m.invoke(o,a);}catch(InvocationTargetException e){Throwable x=e.getCause();if(x instanceof Exception)throw (Exception)x;throw e;}}}throw new NoSuchMethodException(n);}
 Object ui(Callable<Object> work)throws Exception{return ui("MAIN_DISPATCH_"+uiSequence.incrementAndGet(),work);}
 Object ui(String id,Callable<Object> work)throws Exception{
   if(Looper.myLooper()==Looper.getMainLooper())throw new IOException("Bounded dispatch cannot wait on main thread");
   return control.post("preparation",id,1500,task->{if(!new Handler(Looper.getMainLooper()).post(task))throw new IllegalStateException("Main dispatch rejected");},()->{if(control.stopped())throw new IOException("Run stopped before UI dispatch");return work.call();});
 }
 void guard()throws Exception{
   if(owner==null||doc==null||!owner.equals(get(doc,"id")))throw new SecurityException("Current document is not this run's created owner");
   if(!("SCAN_SYNTH_"+runId).equals(get(doc,"title")))throw new SecurityException("Synthetic run tag mismatch");
 }
 void idle()throws Exception{
   control.run("preparation","APPLICATION_READY_"+waitSequence.incrementAndGet(),90000,()->{
     long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
     while(System.nanoTime()<deadline){
       if(control.stopped())throw new IOException("Run stopped during application wait");
       boolean ready=(Boolean)ui(()->!((Boolean)get(activity,"busy"))&&!((Boolean)get(activity,"startupPending"))&&((Boolean)get(activity,"resumed")));
       if(ready)return null;Thread.sleep(100);
     }throw new IOException("Application-ready deadline exceeded");
   });
 }
 void saveJson(String name,Object value)throws Exception{
   if(evidence==null||!name.matches("[a-z0-9-]+\\.json"))throw new IOException("Invalid run evidence name");
   try(FileOutputStream output=new FileOutputStream(new File(evidence,name))){output.write(value.toString().getBytes(StandardCharsets.UTF_8));output.getFD().sync();}catch(IOException failure){evidenceFailed=true;throw failure;}
 }
 void record(String id,boolean pass,String detail)throws Exception{
   throw new IOException("Functional assertions are not enabled in preparation-only diagnostic");
 }
 void observe(String id,String detail)throws Exception{saveJson("cache-observation.json",new JSONObject().put("id",id).put("status","OBSERVED_ONLY").put("detail",detail).put("saf_write_verified",false).put("text_semantics_verified",false));}
 void check(String id,boolean pass,String detail)throws Exception{record(id,pass,detail);if(!pass){fatal=true;throw new IOException("Stop dangerous branch: "+id);}}
 View find(View v,String label){if(label.contentEquals(v.getContentDescription()==null?"":v.getContentDescription())||(v instanceof TextView&&label.contentEquals(((TextView)v).getText())))return v;if(v instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)v;for(int i=0;i<group.getChildCount();i++){View x=find(group.getChildAt(i),label);if(x!=null)return x;}}return null;}
 void click(String label)throws Exception{guard();ui(()->{guard();View view=find(activity.getWindow().getDecorView(),label);if(view==null||!view.isEnabled())throw new IOException("Owned widget unavailable");view.requestRectangleOnScreen(new android.graphics.Rect(0,0,view.getWidth(),view.getHeight()),true);if(!view.performClick())throw new IOException("Owned widget rejected click");return null;});}
 AccessibilityNodeInfo node(AccessibilityNodeInfo n,String label){if(n==null)return null;if(label.contentEquals(n.getText()==null?"":n.getText())||label.contentEquals(n.getContentDescription()==null?"":n.getContentDescription()))return n;for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo hit=node(n.getChild(i),label);if(hit!=null)return hit;}return null;}
 void dialog(String label)throws Exception{
   if(!"继续".equals(label))throw new SecurityException("Only the synthetic import confirmation is enabled");
   control.run("preparation","IMPORT_DIALOG_CONTINUE",5000,()->{
     long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
     while(System.nanoTime()<deadline){
       if(control.stopped())throw new IOException("Run stopped before dialog action");guard();
       AccessibilityNodeInfo root=automation.getRootInActiveWindow();
       if(root==null||!"org.wangshu.scan".contentEquals(root.getPackageName()))throw new SecurityException("Foreground window is not the owned scanner");
       AccessibilityNodeInfo found=node(root,label);while(found!=null&&!found.isClickable())found=found.getParent();
       if(found!=null&&found.isEnabled()){if(found.performAction(AccessibilityNodeInfo.ACTION_CLICK))return null;throw new IOException("Owned import confirmation outcome unconfirmed; no retry");}
       Thread.sleep(100);
     }throw new IOException("Import confirmation deadline exceeded");
   });
 }
 Object load()throws Exception{return call(store,"load",owner);}
 List<?> pages(Object document)throws Exception{return (List<?>)call(document,"active");}
 String pageText(Object document,int index)throws Exception{return (String)get(pages(document).get(index),"ocrText");}
 void snapshot(String name)throws Exception{guard();saveJson(name,call(load(),"json"));}

 void open(int index)throws Exception{guard();Object p=pages(doc).get(index);ui(()->call(activity,"openEditor",p));idle();}
 EditText input()throws Exception{return (EditText)ui(()->{View v=find(activity.getWindow().getDecorView(),"本页识别文字纠错，保存本页后生效");if(!(v instanceof EditText))throw new IOException("OCR editor missing");return v;});}
 void text(String value)throws Exception{EditText v=input();ui(()->{v.setText(value);return null;});}
 void crop()throws Exception{click("四角裁切");idle();click("恢复全图");click("确认四角并返回");idle();}
 void refreshDoc()throws Exception{doc=load();ui(()->{set(activity,"document",doc);call(activity,"showDocument");return null;});idle();}
 void setup()throws Exception{
   store=control.run("preparation","STORE_CONSTRUCT",5000,()->{Constructor<?> c=cls("Store").getDeclaredConstructor(Context.class);c.setAccessible(true);return c.newInstance(getTargetContext());});
   doc=control.run("preparation","DOCUMENT_CREATE",5000,()->call(store,"create"));owner=(String)get(doc,"id");
   control.run("preparation","DOCUMENT_TAG_SAVE",5000,()->{set(doc,"title","SCAN_SYNTH_"+runId);call(store,"save",doc);guard();return null;});control.owner(owner);
   control.run("preparation","PREFERENCE_COMMIT",5000,()->{if(!getTargetContext().getSharedPreferences("MainActivity",0).edit().putString("lastDocument",owner).commit())throw new IOException("Synthetic preference commit returned false");return null;});
   Instrumentation.ActivityMonitor monitor=control.run("preparation","ACTIVITY_MONITOR_ADD",5000,()->addMonitor("org.wangshu.scan.MainActivity",null,false));
   try{
     control.run("preparation","ACTIVITY_REQUEST",5000,()->{getTargetContext().startActivity(new Intent().setClassName("org.wangshu.scan","org.wangshu.scan.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return null;});
     activity=control.run("preparation","ACTIVITY_ARRIVAL",21000,()->waitForMonitorWithTimeout(monitor,20000));if(activity==null)throw new IOException("Owned Activity did not arrive before deadline");
   }finally{if(!control.stopped())control.run("preparation","ACTIVITY_MONITOR_REMOVE",5000,()->{removeMonitor(monitor);return null;});}
   idle();ui("VERIFY_CREATED_OWNER",()->{Object current=get(activity,"document");if(current==null||!owner.equals(get(current,"id")))throw new SecurityException("Activity did not open this run's owner");doc=current;guard();store=get(activity,"store");return null;});
   ArrayList<Uri> uris=new ArrayList<>();for(String letter:new String[]{"A","B","C"})uris.add(Uri.parse("content://org.wangshu.scan.test.fixtures/"+letter));
   ui("IMPORT_DISPATCH",()->{guard();return call(activity,"importImages",uris);});idle();dialog("继续");idle();
   ui("VERIFY_IMPORTED_OWNER",()->{doc=get(activity,"document");guard();preparedPages=pages(doc).size();preparedOwner=true;return null;});
   control.run("preparation","SYNTHETIC_IMPORT_RECEIPT",5000,()->{snapshot("01-imported.json");return null;});
 }
 void editAll()throws Exception{
   String[] values={"人工-A-17\n中文😀\n\n尾行  ","B-独立文字\nblank source","人工-C-29\nHello scanner\n第二行"};EditText old=null;
   for(int i=0;i<3;i++){
     if(i==0)open(0);text(values[i]);crop();EditText held=input();
     click(i==2?"保存并返回文稿（末页）":"保存并下一页");idle();
     if(old!=null){Object current=get(activity,"draft");String currentText=current==null?null:(String)get(current,"ocrText");EditText stale=old;ui(()->{stale.setText("LATE_OLD_WIDGET_MUST_NOT_APPLY");return null;});Object after=get(activity,"draft");check("SCAN_STALE_WIDGET_"+(i+1),current==after&&(after==null||Objects.equals(currentText,get(after,"ocrText"))),"Manually triggered obsolete real TextWatcher cannot mutate the current draft; not an actual delayed IME event");}
     Object saved=load();check("SCAN_PAGE_SAVE_"+(i+1),values[i].equals(pageText(saved,i)),"Real EditText/TextWatcher -> save-and-next; emoji/newlines/trailing spaces checked exactly");
     if(i<2)check("SCAN_NEXT_"+(i+1),get(pages(saved).get(i+1),"id").equals(get(get(activity,"draft"),"id")),"Correct next page ID selected");old=held;
   }
   doc=get(activity,"document");check("SCAN_END_RETURNS",((Integer)get(activity,"screen"))==1,"Last page returns to document, no wraparound");snapshot("02-edited.json");
   for(int i=0;i<3;i++)check("SCAN_NO_CROSS_PAGE_"+(i+1),values[i].equals(pageText(load(),i)),"Distinct saved text remains on its own page after old widget callbacks");
   click("第 3 页上移");idle();doc=get(activity,"document");click("第 2 页上移");idle();doc=get(activity,"document");snapshot("03-reordered.json");
   Object saved=load();check("SCAN_SORT",values[2].equals(pageText(saved,0))&&values[0].equals(pageText(saved,1))&&values[1].equals(pageText(saved,2)),"C/A/B order, saved text follows page identity");refreshDoc();open(1);check("SCAN_REOPEN",values[0].equals((String)ui(()->input().getText().toString())),"Reopened actual OCR editor exactly matches saved A");
 }
 void candidate()throws Exception{
   String before=pageText(load(),1),old=before+"\n旧稿-保留-41";text(old);
   click("单页重新识别 · 先比较，保留修订");dialog("中英混合候选");idle();Object result=get(activity,"ocrCandidate");check("SCAN_NATIVE_CANDIDATE",result!=null,"Actual phone JNI single-page chi_sim+eng result, not a recognition seam");
   String proposed=(String)get(result,"text");saveJson("candidate-text.json",new JSONObject().put("priorDraft",old).put("candidate",proposed));
   check("SCAN_CANDIDATE_NOT_SAVED",before.equals(pageText(load(),1)),"Generating candidate does not save or replace persisted page");dialog("保留原修订");check("SCAN_CANDIDATE_CANCEL",old.equals((String)ui(()->input().getText().toString())),"Keep original revision leaves complete unsaved text");
   click("查看本次单页识别候选（未保存）");dialog("复制新候选");check("SCAN_CANDIDATE_COPY_NO_SAVE",before.equals(pageText(load(),1))&&old.equals((String)ui(()->input().getText().toString())),"Copy action executed; no persisted or draft replacement; clipboard content not read");
   click("查看本次单页识别候选（未保存）");dialog("保留旧稿并采用候选");idle();check("SCAN_ADOPT_EXPLICIT",proposed.equals((String)ui(()->input().getText().toString()))&&before.equals(pageText(load(),1)),"Adoption changes draft only; explicit page save still required");
   // Actual recovery area: inspect only records owned by this newly created synthetic document.
   Method recovery=cls("EditDraftStore").getDeclaredMethod("recovery",File.class);recovery.setAccessible(true);Object rs=recovery.invoke(null,getTargetContext().getFilesDir());List<?> entries=(List<?>)call(rs,"list",owner);boolean retained=false;
   for(Object entry:entries){if((Boolean)get(entry,"valid")){Object record=call(rs,"load",owner,entry);if(((String)get(record,"edited")).contains("旧稿-保留-41"))retained=true;}}
   check("SCAN_OLD_DRAFT_RETAINED",retained,"Complete pre-adoption synthetic old draft retained in real recovery file");
   crop();click(SAVE_RETURN);idle();doc=get(activity,"document");check("SCAN_CANDIDATE_SAVE",proposed.equals(pageText(load(),1)),"Explicit save persists adopted candidate on A only");snapshot("04-adopted.json");
   open(1);String oldKey=(String)get(get(activity,"draft"),"ocrKey");click("旋转 90°");Object d=get(activity,"draft");Method key=cls("OcrEngine").getDeclaredMethod("key",cls("Models$Page"));key.setAccessible(true);boolean stale=!oldKey.equals(key.invoke(null,d));text("manual after rotation");check("SCAN_STALE_BASELINE",stale&&oldKey.equals(get(get(activity,"draft"),"ocrKey")),"Changing image makes OCR baseline stale; manual correction does not refresh key");
   // Restore image orientation, reconfirm, preserve original candidate text explicitly.
   click("旋转 90°");click("旋转 90°");click("旋转 90°");text(proposed);crop();click(SAVE_RETURN);idle();doc=get(activity,"document");
 }
 @SuppressWarnings({"unchecked","rawtypes"}) void txt(String source,String name)throws Exception{
   Class<?> e=cls("TextExport$Source");Object value=Enum.valueOf((Class)e,source);ui(()->call(activity,"createTextExport",value));idle();Object prepared=get(activity,"preparedText");File file=(File)get(prepared,"file");String body=new String(java.nio.file.Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);saveJson(name+".json",new JSONObject().put("source",source).put("text",body).put("cache",file.getName()));
   observe("SCAN_TXT_CACHE_"+source,"Formatter cache only; no SAF save or exact text semantic assertion");dialog("取消");
 }
 void exports()throws Exception{
   txt("SAVED_DOCUMENT","txt-document");open(1);txt("SAVED_PAGE","txt-page");String base=(String)ui(()->input().getText().toString());text(base+"\n导出草稿-未存-53\n中文😀\n\n尾行  ");txt("DRAFT_PAGE","txt-draft");
   check("SCAN_TXT_DRAFT_NOT_SAVED",!pageText(load(),1).contains("导出草稿-未存-53"),"Draft export never saves page text");
   text(base);crop();click(SAVE_RETURN);idle();doc=get(activity,"document");
 }
 void recreate()throws Exception{
   open(0);String draft="生命周期-未保存-中文😀\nLine two";text(draft);String oldId=(String)get(get(activity,"draft"),"id");
   Instrumentation.ActivityMonitor monitor=addMonitor("org.wangshu.scan.MainActivity",null,false);ui(()->{activity.recreate();return null;});Activity fresh=waitForMonitorWithTimeout(monitor,20000);removeMonitor(monitor);if(fresh==null)throw new IOException("Activity recreation did not return new activity");activity=fresh;idle();doc=get(activity,"document");guard();
   check("SCAN_ACTIVITY_RECREATE",oldId.equals(get(get(activity,"draft"),"id"))&&draft.equals((String)ui(()->input().getText().toString())),"Real Activity.recreate on synthetic editor; page identity, emoji and unsaved text retained");crop();click(SAVE_RETURN);idle();doc=get(activity,"document");snapshot("05-final.json");
 }

 void programStacks(BoundedStage.Timeout timeout)throws Exception{
   JSONArray rows=new JSONArray();Thread main=Looper.getMainLooper().getThread();
   for(Map.Entry<Thread,StackTraceElement[]> entry:Thread.getAllStackTraces().entrySet()){
     Thread thread=entry.getKey();if(thread!=main&&thread!=runnerThread&&!thread.getName().startsWith("SCAN_"))continue;
     JSONArray frames=new JSONArray();int count=0;for(StackTraceElement frame:entry.getValue()){if(count++==64)break;frames.put(frame.toString());}
     rows.put(new JSONObject().put("role",thread==main?"main":thread==runnerThread?"runner":"bounded-tool-worker").put("frames",frames));
   }
   JSONArray targetFrames=new JSONArray();for(StackTraceElement frame:timeout.stackFrames)targetFrames.put(frame.toString());
   rows.put(new JSONObject().put("role","timed-out-operation").put("target",timeout.targetThread).put("stage",timeout.stage).put("id",timeout.id).put("operation_state",timeout.operationState).put("frames",targetFrames));
   saveJson("program-stacks.json",new JSONObject().put("runId",runId).put("stacks",rows).put("contains_document_text",false));
 }
 void emit(Map<String,Object> event)throws Exception{
   JSONObject object=new JSONObject(event);byte[] line=(object.toString()+"\n").getBytes(StandardCharsets.UTF_8);IOException diskFailure=null;
   try(FileOutputStream output=new FileOutputStream(new File(evidence,"stages.jsonl"),true)){output.write(line);output.getFD().sync();}catch(IOException failure){evidenceFailed=true;diskFailure=failure;}
   try{FutureTask<Void> status=new FutureTask<>(()->{Bundle message=new Bundle();message.putString("wangshu_event",object.toString());sendStatus(1,message);return null;});Thread sender=new Thread(status,"SCAN_STATUS");sender.setDaemon(true);sender.start();status.get(1000,TimeUnit.MILLISECONDS);}catch(Exception failure){evidenceFailed=true;if(diskFailure!=null)failure.addSuppressed(diskFailure);throw failure;}
   if(diskFailure!=null)throw diskFailure;
 }
 @Override public void onStart(){
   runnerThread=Thread.currentThread();runnerThread.setName("SCAN_INSTRUMENTATION_RUNNER");Bundle result=new Bundle();int code=Activity.RESULT_CANCELED;
   try{
     runId=args.getString("runId","");if(!UUID.fromString(runId).toString().equals(runId))throw new IOException("Canonical host runId required");
     if(!"scan".equals(args.getString("suite","scan"))||!"prepare".equals(args.getString("scope","prepare"))||args.containsKey("verb")||args.containsKey("label")||args.containsKey("key"))throw new SecurityException("Only scanner preparation diagnostic is enabled");
     evidence=new File(getTargetContext().getFilesDir(),"scan-prepare-"+runId);if(!evidence.mkdir())throw new IOException("Fresh run evidence directory required");
     control=new BoundedStage(runId,this::emit,this::programStacks);
     automation=control.run("startup","UI_AUTOMATION_OPEN",5000,()->getUiAutomation());
     control.run("preparation","SETUP",300000,()->{setup();return null;});
     control.assertCase("PREP_OWNED_DOCUMENT",preparedOwner);control.assertCase("PREP_IMPORT_3",preparedPages==3);
     if(evidenceFailed)throw new IOException("Evidence persistence not confirmed");control.finish("PREPARED");result.putString("status","PREPARED");code=Activity.RESULT_OK;
   }catch(Throwable failure){fatal=true;result.putString("status","STOPPED");result.putString("error_type",failure.getClass().getSimpleName());if(control!=null)try{control.finish("STOPPED");}catch(Exception ignored){/* Host rejects missing terminal or contradictory result. */}}
   int preparationAssertions=0;if(control!=null)for(Map<String,Object> event:control.events())if("ASSERT".equals(event.get("kind")))preparationAssertions++;
   result.putInt("preparation_assertions",preparationAssertions);result.putInt("case_assertions",0);result.putBoolean("product_acceptance",false);result.putBoolean("remote_cancellation_confirmed",false);result.putString("runId",runId==null?"":runId);result.putString("evidenceDir",evidence==null?"":evidence.getName());result.putString("syntheticDocument",owner==null?"":owner);
   final int resultCode=code;final Bundle terminal=result;FutureTask<Void> finishing=new FutureTask<>(()->{finish(resultCode,terminal);return null;});Thread finisher=new Thread(finishing,"SCAN_FINISH");finisher.setDaemon(true);finisher.start();try{finishing.get(2000,TimeUnit.MILLISECONDS);}catch(Exception unconfirmed){/* No retry and no claim that Android or Binder was cancelled. */}
 }
}
