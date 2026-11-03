package org.wangshu.scan;
import java.io.*;import java.nio.file.*;import java.util.*;

/** Exact MainActivity source/save/retry methods; synthetic controls, scheduler, Store and Android target bridge. */
public final class TextExportUiHost {
    static final int RESULT_OK=-1,RESULT_CANCELED=0,SAVE=103;Models.Document document;Models.Page draft,originalPage;Object editorSession;
    String originalPageBaseline,pendingKind="pdf",activeSaveToken,handledSaveToken,approvedExportDigest;long approvedExportBytes;
    File pendingExport,approvedExportSource,cache,files;PreparedText preparedText;Map<String,PreparedText> textSavePermits=new HashMap<>();HashSet<String> approvedSaveTokens=new HashSet<>();
    Store store;SaveJobStore jobs;EditorSaveFlow editorSaves=new EditorSaveFlow();Object recovery;int screen=1,generation=1,starts,opens,errors,toasts;boolean cropping,busy,resumed=true,stateSaved,resultDrainScheduled,living=true;
    ArrayDeque<Bundle> pendingResults=new ArrayDeque<>();AlertDialog.Builder dialog;Intent launched;Throwable lastError;Runnable beforeCallback;boolean failTarget;
    final TextExportTests.Persistence persistence=new TextExportTests.Persistence();
    TextExportUiHost(Path root)throws Exception{Files.createDirectories(root.resolve("cache"));Files.createDirectories(root.resolve("files"));cache=root.resolve("cache").toFile();files=root.resolve("files").toFile();Models.Page p=TextExportTests.page("page-a","已保存合成文本","machine");document=TextExportTests.doc(p);store=new Store(document);jobs=new SaveJobStore(cache,files,persistence,new TextExportTests.Sources());}
    boolean alive(){return living;}void error(Throwable t){errors++;lastError=t;}void toast(String t){toasts++;}File getCacheDir(){return cache;}String getPackageName(){return "org.wangshu.scan";}SaveJobStore saveJobs(){return jobs;}
    void retainPickerPermission(Uri uri,int flags,boolean writing){}Resolver getContentResolver(){return new Resolver();}
    void startActivityForResult(Intent intent,int code){starts++;launched=intent;}
    interface Task{Object run()throws Exception;}interface Done{void accept(Object value);}
    void runIO(String title,Task task,Done done){runIO(title,task,done,false);}void runIO(String title,Task task,Done done,boolean cancel){if(busy)return;busy=true;try{Object value=task.run();if(beforeCallback!=null){Runnable hook=beforeCallback;beforeCallback=null;hook.run();}busy=false;done.accept(value);}catch(Exception t){busy=false;error(t);}}
    TextDocumentSave.Handle openNewTextDestination(Uri uri,int flags)throws Exception{opens++;RandomAccessFile target=new RandomAccessFile(uri.path.toFile(),"rw");long bytes=target.length();return new TextDocumentSave.Handle(){public boolean regular(){return true;}public long bytes(){return bytes;}public OutputStream output(){return new OutputStream(){int n;public void write(int b)throws IOException{if(failTarget&&n++>7)throw new IOException("synthetic destination write failure");target.write(b);}};}public void close()throws Exception{target.close();}};}
    void editPage()throws Exception{screen=2;originalPage=document.pages.get(0);draft=originalPage.copy();editorSession=new Object();originalPageBaseline=originalPage.json().toString();}
    void chooseSource(int index){textExportOptions();dialog.item(index);}
    void confirmDestination(){dialog.click("选择新文件");}
    Bundle result(Path target)throws Exception{SaveJobStore.Job j=jobs.read();if(j.phase==SaveJobStore.REQUESTED)jobs.picker(j.token);jobs.chosen(j.token);Bundle out=new Bundle();out.putString(SavePickerActivity.EXTRA_TOKEN,j.token);out.putInt("saveAccepted",1);out.putInt("result",RESULT_OK);out.putParcelable("uri",new Uri(target));return out;}
    /* MAIN_METHODS */
    static int checks,scenarios;interface Case{void run()throws Exception;}static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}static void test(String label,Case run)throws Exception{run.run();scenarios++;System.out.println("PASS "+label);}static TextExportUiHost setup(Path root,String name)throws Exception{return new TextExportUiHost(root.resolve(name));}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);
        test("saved export uses explicit source and user target, labels provider behavior and preserves draft",()->{
            TextExportUiHost h=setup(root,"saved");h.editPage();h.draft.ocrText="未保存😀";h.draft.ocrOrigin="manual";h.chooseSource(0);
            check(h.dialog.message.contains("存储或同步取决于所选文档提供者")&&h.starts==0,"no automatic destination selection or offline promise");
            check(Files.readString(h.preparedText.file.toPath()).contains("已保存合成文本")&&!Files.readString(h.preparedText.file.toPath()).contains("未保存😀"),"saved document excludes draft");
            h.confirmDestination();check(h.starts==1&&h.jobs.read().mime.equals("text/plain"),"user confirmation starts private token bridge");
            Bundle result=h.result(root.resolve("saved-target.txt"));h.processSaveResult(result);
            check(h.jobs.read().phase==SaveJobStore.DONE&&h.opens==1&&h.draft.ocrText.equals("未保存😀"),"saved file does not save or discard draft");
            h.processSaveResult(result);check(h.opens==1,"duplicate Activity result cannot reopen target");
        });
        test("draft selection exports literal unpersisted text with unsaved and manual markers",()->{
            TextExportUiHost h=setup(root,"draft");h.editPage();h.draft.ocrText="草稿中文😀\nE=mc²  ";h.draft.ocrOrigin="manual";h.chooseSource(2);
            String text=Files.readString(h.preparedText.file.toPath());check(text.contains(h.draft.ocrText)&&text.contains("否（编辑草稿副本）")&&text.contains("人工编辑过"),"chosen draft markers exact");
            h.confirmDestination();h.processSaveResult(h.result(root.resolve("draft-target.txt")));check(h.document.pages.get(0).ocrText.equals("已保存合成文本"),"original unchanged");
        });
        test("source dialog late action after session/view change cannot generate or launch",()->{
            TextExportUiHost h=setup(root,"old-dialog");h.editPage();h.textExportOptions();AlertDialog.Builder old=h.dialog;h.editorSession=new Object();h.generation++;old.item(2);check(h.preparedText==null&&h.starts==0,"late source action inert");
        });
        test("document switch before preparation callback does not offer a picker",()->{
            TextExportUiHost h=setup(root,"switch-before-callback");h.beforeCallback=()->h.document=TextExportTests.doc(TextExportTests.page("other","other",""));h.chooseSource(0);check(h.preparedText==null&&h.starts==0,"late output never attached to another document");
        });
        test("confirmation after document switch does not start saving",()->{
            TextExportUiHost h=setup(root,"switch-confirm");h.chooseSource(0);AlertDialog.Builder offer=h.dialog;h.document=TextExportTests.doc(TextExportTests.page("other","other",""));offer.click("选择新文件");check(h.starts==0&&h.jobs.read()==null,"stale confirmation rejected");
        });
        for(String change:List.of("document","order","draft","disk-version"))test("picker return rejects "+change+" without opening target",()->{
            TextExportUiHost h=setup(root,"return-"+change);h.document.pages.add(TextExportTests.page("page-b","B",""));h.editPage();h.chooseSource(2);h.confirmDestination();Bundle event=h.result(root.resolve("not-written-"+change+".txt"));
            switch(change){case "document":h.document=TextExportTests.doc(TextExportTests.page("other","other",""));break;case "order":Collections.reverse(h.document.pages);break;case "draft":h.draft.ocrText="new unsaved";break;case "disk-version":h.store.diskVersion="c".repeat(64);break;}
            h.processSaveResult(event);check(h.opens==0&&h.errors>0,"invalid source refused before target");
        });
        test("cancelled picker cancels receipt and leaves source/draft for explicit retry",()->{
            TextExportUiHost h=setup(root,"cancel");h.editPage();h.draft.ocrText="取消后保留";h.chooseSource(2);h.confirmDestination();String token=h.activeSaveToken;h.activeSaveToken=null;
            Bundle cancelled=new Bundle();cancelled.putString(SavePickerActivity.EXTRA_TOKEN,token);cancelled.putInt("saveAccepted",1);cancelled.putInt("result",RESULT_CANCELED);h.processSaveResult(cancelled);
            check(h.jobs.read().phase==SaveJobStore.CANCELLED&&h.preparedText.file.isFile()&&h.draft.ocrText.equals("取消后保留")&&h.opens==0,"cancel retains exact source and draft");
            h.textExportOptions();h.dialog.item(3);h.confirmDestination();check(h.starts==2,"user explicitly retries previous snapshot");
        });
        test("uncertain target failure recovers old snapshot only after confirmation and uses fresh target",()->{
            TextExportUiHost h=setup(root,"recover");h.chooseSource(0);h.confirmDestination();Path partial=root.resolve("partial-target.txt");Bundle event=h.result(partial);String old=h.activeSaveToken;h.failTarget=true;h.processSaveResult(event);byte[] before=Files.readAllBytes(partial);
            check(h.jobs.read().phase==SaveJobStore.NEEDS_RESELECT&&h.lastError instanceof SaveWriteFailure,"write failure surfaced with retained source");
            h.recoverSaveResult(old,null);check(h.dialog.message.contains("旧目标可能不完整"),"history confirmation explicit");h.dialog.click("核对快照并另选新文件");check(h.jobs.read().phase==SaveJobStore.CANCELLED,"old receipt explicitly ended");h.confirmDestination();check(!h.activeSaveToken.equals(old),"new request token");h.failTarget=false;h.processSaveResult(h.result(root.resolve("fresh-retry-target.txt")));
            check(h.jobs.read().phase==SaveJobStore.DONE&&Arrays.equals(before,Files.readAllBytes(partial)),"fresh target succeeds without rewriting partial target");
        });
        test("old recovery confirmation never cancels a newer request for the same source",()->{
            TextExportUiHost h=setup(root,"retry-race");h.chooseSource(0);h.confirmDestination();String old=h.activeSaveToken;h.activeSaveToken=null;h.jobs.cancel(old);h.recoverTextSave(old);AlertDialog.Builder offer=h.dialog;
            SaveJobStore.Job newer=h.jobs.begin(h.preparedText.file,"text/plain","newer.txt");offer.click("核对快照并另选新文件");check(h.jobs.read().token.equals(newer.token)&&h.jobs.read().phase==SaveJobStore.REQUESTED,"newer receipt untouched");
        });
        test("new Activity cannot auto-replay old chosen URI; explicit historical recovery works from library",()->{
            TextExportUiHost h=setup(root,"recreate");h.chooseSource(0);h.confirmDestination();Path never=root.resolve("old-never-opened.txt");Bundle event=h.result(never);h.activeSaveToken=null;h.approvedSaveTokens.clear();h.textSavePermits.clear();h.preparedText=null;h.document=null;h.screen=0;
            h.processSaveResult(event);check(h.opens==0&&h.dialog.message.contains("旧目标可能不完整"),"old chosen target is not replayed");h.dialog.click("核对快照并另选新文件");h.confirmDestination();h.processSaveResult(h.result(root.resolve("library-retry.txt")));check(h.opens==1&&h.jobs.read().phase==SaveJobStore.DONE,"explicit fresh library target works without live document");
        });
        test("nonempty chosen target remains intact and receipt reports retryable failure",()->{
            TextExportUiHost h=setup(root,"nonempty");h.chooseSource(0);h.confirmDestination();Path existing=root.resolve("existing.txt");Files.writeString(existing,"existing synthetic data");h.processSaveResult(h.result(existing));check(Files.readString(existing).equals("existing synthetic data")&&h.jobs.read().phase==SaveJobStore.NEEDS_RESELECT,"no truncation on nonempty target");
        });
        test("evicted cache shows explicit end action and allows new source generation",()->{
            TextExportUiHost h=setup(root,"evicted-ui");h.chooseSource(0);h.confirmDestination();String token=h.activeSaveToken;h.failTarget=true;Path partial=root.resolve("evicted-partial.txt");h.processSaveResult(h.result(partial));byte[] old=Files.readAllBytes(partial);Files.move(h.preparedText.file.toPath(),root.resolve("preserved-evicted-cache.txt"));
            h.recoverTextSave(token);check(h.dialog.title.equals("结束旧文字保存请求？")&&h.dialog.message.contains("均不删除"),"unavailable cache has an honest terminal exit");h.dialog.click("结束旧文字请求");check(h.jobs.read().phase==SaveJobStore.CANCELLED&&Arrays.equals(old,Files.readAllBytes(partial)),"explicit close preserves old partial target");
            h.failTarget=false;h.chooseSource(0);h.confirmDestination();h.processSaveResult(h.result(root.resolve("after-eviction-new.txt")));check(h.jobs.read().phase==SaveJobStore.DONE,"new export works after cache eviction");
        });
        System.out.println("SCENARIOS "+scenarios+" ASSERTIONS "+checks+" FAILED 0; exact MainActivity callbacks with actual format/receipt, synthetic UI/Store/scheduler/descriptor bridge; no Android SAF/lifecycle");
    }
}
class Store {
    static final Object FILE_LOCK=new Object();String diskVersion;Store(Models.Document d){diskVersion=d.savedSnapshot;}
    void assertCurrentReadable(Models.Document d)throws Exception{if(d.manifestUncertain||!diskVersion.equals(d.savedSnapshot))throw new IOException("synthetic disk version changed");}
    Models.Document snapshotForExport(Models.Document d)throws Exception{assertCurrentReadable(d);Models.Document out=Models.Document.from(d.json());out.savedSnapshot=d.savedSnapshot;return out;}
    static void requireCertain(Models.Document d)throws ManifestFence.Uncertain{if(d.manifestUncertain)throw new ManifestFence.Uncertain("synthetic uncertain");}
}
class OcrEngine {/* OCR_KEY */}
class SavePickerActivity {static final String ACTION_START="start",EXTRA_TOKEN="token";static boolean validDestination(Uri uri,String own){return uri!=null;}}
class Intent {String action;Map<String,String> values=new HashMap<>();Intent(Object owner,Class<?> type){}Intent setAction(String a){action=a;return this;}Intent putExtra(String key,String value){values.put(key,value);return this;}}
class Uri {final Path path;Uri(Path path){this.path=path;}}
class Bundle {Map<String,Object> values=new HashMap<>();String getString(String key){return (String)values.get(key);}int getInt(String key){return (int)values.getOrDefault(key,0);}@SuppressWarnings("unchecked") <T>T getParcelable(String key){return (T)values.get(key);}void putString(String key,String value){values.put(key,value);}void putInt(String key,int value){values.put(key,value);}void putParcelable(String key,Object value){values.put(key,value);}}
class Resolver {OutputStream openOutputStream(Uri uri,String mode)throws IOException{throw new IOException("non-text Android path not exercised");}}
class AlertDialog {
    interface Click{void run(Object dialog,int which);}
    static class Builder {String title,message;String[] labels;Click choice;Map<String,Click> actions=new HashMap<>();Builder(TextExportUiHost host){host.dialog=this;}Builder setTitle(String title){this.title=title;return this;}Builder setMessage(String message){this.message=message;return this;}Builder setItems(String[] labels,Click choice){this.labels=labels;this.choice=choice;return this;}Builder setPositiveButton(String text,Click action){actions.put(text,action);return this;}Builder setNeutralButton(String text,Click action){actions.put(text,action);return this;}Builder setNegativeButton(String text,Click action){actions.put(text,action);return this;}void show(){}void item(int index){choice.run(this,index);}void click(String key){Click action=actions.get(key);if(action==null)throw new AssertionError("missing button "+key);action.run(this,0);}}
}
