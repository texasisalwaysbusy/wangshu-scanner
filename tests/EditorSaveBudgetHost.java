package org.wangshu.scan;
import org.json.JSONObject;
import java.io.*;import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.util.*;
/** Exact injected MainActivity methods and real Models/mutations, with explicit JSON/UI/Store seams. */
public final class EditorSaveBudgetHost {
    Models.Document document;Models.Page originalPage,draft;Object editorSession=new Object();
    String originalPageBaseline,editorDocumentVersion,startupDocumentId,startupDocumentVersion,startupOriginal,startupDraft;
    boolean startupEditorSaveSubmitted,startupPending,cropping,busy,stateSaved,resumed=true;int screen=2,generation=1,errors,toasts;
    EditRecoveryState recovery;final EditorSaveFlow editorSaves=new EditorSaveFlow();EditorSaveFlow.Saved pendingEditorSave;
    EditorLifecycleState lifecycleState,startupState;EditorLifecycleState.Snapshot lifecycleSnapshot;Throwable lifecycleProblem;
    File files;Store store;CropView cropView=new CropView();AlertDialog.Builder dialog;
    EditorSaveBudgetHost(Path path)throws Exception{Files.createDirectories(path);files=path.toFile();document=new Models.Document();document.savedSnapshot="a".repeat(64);originalPage=new Models.Page();originalPage.id="page-a";originalPage.file="originals/a.image";originalPage.sha256="b".repeat(64);originalPage.confirmCrop();document.pages.add(originalPage);draft=originalPage.copy();originalPageBaseline=originalPage.json().toString();editorDocumentVersion=document.savedSnapshot;store=new Store();EditorSaveFlow.observePersisted(document.id,document.savedSnapshot);}
    boolean alive(){return true;}File getFilesDir(){return files;}void error(Throwable t){errors++;}void toast(String message){toasts++;}
    void buildEditor(){screen=2;generation++;}void showDocument(){screen=1;}void showLibrary(String query){screen=0;}void importImages(ArrayList<Uri> uris){}void showRecovery(String message){screen=3;editorSession=null;}
    void openEditor(Models.Page page){openEditor(page,null);}void openEditor(Models.Page page,Models.Page restored){originalPage=page;try{draft=restored==null?page.copy():restored.copy();originalPageBaseline=page.json().toString();}catch(Exception problem){throw new RuntimeException(problem);}editorSession=new Object();buildEditor();}
    void deliverPendingEditorSave(){}interface Task{Object run()throws Exception;}interface Done{void accept(Object value);}
    void runIO(String label,Task task,Done done){busy=true;try{Object value=task.run();busy=false;done.accept(value);}catch(Exception problem){busy=false;error(problem);}}
    /* MAIN_METHODS */
    static int checks,scenarios;interface Case{void run()throws Exception;}
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}static void test(String name,Case action)throws Exception{action.run();scenarios++;System.out.println("PASS "+name);}
    static EditorSaveBudgetHost setup(Path root,String name)throws Exception{return new EditorSaveBudgetHost(root.resolve(name));}
    static void edit(EditorSaveBudgetHost h,String value){check(h.updateOcrDraft(h.editorSession,h.document,h.draft,h.generation,value),"current editor accepts exact input");}
    static String serializedDocument(EditorSaveBudgetHost h)throws Exception{return h.document.json().toString(2);}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);
        test("old 4.2MB reproduction stays in exact editor, copyable and editable; trim and retry saves",()->{
            EditorSaveBudgetHost h=setup(root,"oversize");String original=serializedDocument(h),huge="中".repeat(1_400_000);Object session=h.editorSession;
            edit(h,huge);h.saveEditor(false);
            check(h.screen==2&&h.recovery==null&&h.editorSession==session&&!h.editorSaves.hasPending(),"no unverified recovery trap");
            check(h.draft.ocrText.equals(huge)&&h.store.writes==0&&serializedDocument(h).equals(original),"all input and old document intact");
            h.leaveEditor();check(h.dialog.actions.containsKey("继续编辑"),"real leave handler remains reachable");
            edit(h,"缩短中文😀\n\"\\\t");h.saveEditor(false);
            check(h.store.writes==1&&h.document.pages.get(0).ocrText.equals(h.draft.ocrText),"explicit trim retry persists exact corrected target");
            h.saveEditor(false);check(h.store.writes==1,"duplicate click cannot repeat completed pending save");
        });
        test("multi-page total and JSON escape overhead use actual Models serialization path",()->{
            EditorSaveBudgetHost h=setup(root,"multi");Models.Page b=new Models.Page();b.id="page-b";b.ocrText="中".repeat(360_000);h.document.pages.add(b);
            String before=serializedDocument(h);edit(h,"中".repeat(360_000));h.saveEditor(false);
            check(h.screen==2&&h.recovery==null&&h.store.writes==0&&serializedDocument(h).equals(before),"aggregate overflow preserves both original pages");
            edit(h,"\n".repeat(1_100_000));h.saveEditor(false);check(h.store.writes==0&&h.draft.ocrText.length()==1_100_000,"escaped newlines are not budgeted as raw bytes");
        });
        test("document preflight exact boundary below and above reserves full timestamp",()->{
            EditorSaveBudgetHost h=setup(root,"manifest-bound");edit(h,"中😀\n\"\\\t");
            org.json.JSONObject candidate=h.document.json();candidate.getJSONArray("pages").put(0,h.draft.json());candidate.put("updated",Long.MAX_VALUE);
            int overhead=candidate.toString(2).getBytes(StandardCharsets.UTF_8).length;
            String start=h.draft.ocrText;edit(h,start+"a".repeat(SerializedBudget.MANIFEST_BYTES-overhead));
            EditDraftStore.Draft record=new EditDraftStore.Draft(h.document.id,h.editorDocumentVersion,h.originalPageBaseline,h.draft.json().toString(),true,0);
            preflightEditorSave(h.document,h.originalPage,h.draft,record);check(h.store.writes==0,"exact bound preflight has no mutations");
            edit(h,h.draft.ocrText+"a");h.saveEditor(false);check(h.store.writes==0&&h.recovery==null&&h.screen==2,"one byte over stays editable");
        });
        test("real recovery I/O failure remains editable; retry after preserving obstructing file",()->{
            EditorSaveBudgetHost h=setup(root,"io-fail");Path obstruction=h.files.toPath().resolve("edit-recovery");Files.writeString(obstruction,"synthetic occupied file");
            edit(h,"完整输入😀");h.saveEditor(false);
            check(h.recovery==null&&h.screen==2&&h.store.writes==0&&h.draft.ocrText.equals("完整输入😀"),"retention failure never traps or truncates");
            Files.move(obstruction,h.files.toPath().resolve("preserved-obstruction"));h.saveEditor(false);
            check(h.store.writes==1&&Files.readString(h.files.toPath().resolve("preserved-obstruction")).equals("synthetic occupied file"),"actual retry succeeds without deleting prior data");
        });
        test("manifest writer failure keeps verified independent correction and original document",()->{
            EditorSaveBudgetHost h=setup(root,"writer-fail");String before=serializedDocument(h);edit(h,"纠错保留");h.store.fail=true;h.saveEditor(false);
            check(h.recovery!=null&&h.recovery.verified&&h.screen==3&&serializedDocument(h).equals(before),"real mutation rollback plus verified recovery");
            check(EditDraftStore.recovery(h.files).load(h.document.id,h.recovery.entry).edited.contains("纠错保留"),"real journal contains complete correction");
        });
        test("wrong page cannot be substituted during preflight",()->{
            EditorSaveBudgetHost h=setup(root,"identity");Models.Page wrong=h.draft.copy();wrong.id="page-b";
            try{preflightEditorSave(h.document,h.originalPage,wrong,new EditDraftStore.Draft(h.document.id,h.editorDocumentVersion,h.originalPageBaseline,wrong.json().toString(),true,0));throw new AssertionError("wrong page");}catch(IOException expected){checks++;}
            check(h.store.writes==0,"no wrong-page write");
        });
        test("exact lifecycle MainActivity hook uses file ref for full unsavable input; no raw state",()->{
            EditorSaveBudgetHost h=setup(root,"life-hook");String huge="中".repeat(1_400_000);edit(h,huge);Map<String,String> out=new HashMap<>();h.saveEditorLifecycle(out::put);
            check(!out.containsKey("draft")&&!out.containsKey("editRecovery.edited")&&out.size()==6,"new hook emits only bounded reference");
            EditorLifecycleState.Snapshot snapshot=EditorLifecycleState.read(out::get).load(h.files);
            check(snapshot.draft.edited.equals(h.draft.json().toString())&&snapshot.draft.baseline.equals(h.originalPageBaseline),"real snapshot preserves actual Models page serialization");
            h.restoreDraft(snapshot.draft.edited,snapshot.draft.baseline,snapshot.draft.version,false);
            check(h.draft.ocrText.equals(huge)&&h.screen==2,"actual restore method reopens full unsavable editable draft");
        });
        test("lifecycle failure retries persistence even without further edits",()->{
            EditorSaveBudgetHost h=setup(root,"life-fail");Path obstruction=h.files.toPath().resolve("edit-lifecycle");Files.writeString(obstruction,"preserve");edit(h,"中文😀");Map<String,String> out=new HashMap<>();h.saveEditorLifecycle(out::put);
            check(h.lifecycleState.memoryOnly&&h.lifecycleProblem!=null&&h.draft.ocrText.equals("中文😀"),"failure explicitly reported with exact input intact");
            Files.move(obstruction,h.files.toPath().resolve("preserved-lifecycle-obstruction"));h.saveEditorLifecycle(out::put);
            check(!h.lifecycleState.memoryOnly&&EditorLifecycleState.read(out::get).load(h.files).draft.edited.contains("中文😀"),"same text can retry failed checkpoint");
        });
        test("unverified legacy oversized record can return to exact current baseline editor",()->{
            EditorSaveBudgetHost h=setup(root,"legacy-resume");edit(h,"中".repeat(1_400_000));
            h.recovery=new EditRecoveryState(h.document.id,new EditDraftStore.Draft(h.document.id,h.editorDocumentVersion,h.originalPageBaseline,h.draft.json().toString(),true,0));
            h.screen=3;h.editorSession=null;h.resumeUnverifiedEditor(h.recovery);
            check(h.recovery==null&&h.screen==2&&h.draft.ocrText.length()==1_400_000,"legacy failure has a source/version-gated editable exit");
        });
        test("changed baseline blocks legacy resume without dropping any input",()->{
            EditorSaveBudgetHost h=setup(root,"legacy-conflict");edit(h,"完整保留");
            EditRecoveryState held=new EditRecoveryState(h.document.id,new EditDraftStore.Draft(h.document.id,"c".repeat(64),h.originalPageBaseline,h.draft.json().toString(),true,0));
            h.recovery=held;h.screen=3;h.resumeUnverifiedEditor(held);
            check(h.recovery==held&&h.screen==3&&held.data.edited.contains("完整保留"),"conflict never applies to a different version");
        });
        test("memory fallback replacement releases obsolete snapshots and explicit discard releases last",()->{
            EditorSaveBudgetHost h=setup(root,"memory-release");Files.writeString(h.files.toPath().resolve("edit-lifecycle"),"occupied");
            java.lang.reflect.Field field=EditorLifecycleState.class.getDeclaredField("MEMORY");field.setAccessible(true);Map<?,?> memory=(Map<?,?>)field.get(null);int before=memory.size();
            for(int i=0;i<16;i++){edit(h,"中".repeat(100_000)+i);h.saveEditorLifecycle(new HashMap<String,String>()::put);}
            check(memory.size()==before+1,"one current fallback rather than unlimited history");h.releaseLifecycleMemory();check(memory.size()==before,"explicit discard/save frees current fallback");
        });
        test("transient startup load failure keeps retryable receipt through next saved state",()->{
            EditorSaveBudgetHost h=setup(root,"startup-failure");edit(h,"未保存中文😀");Map<String,String> initial=new HashMap<>();h.saveEditorLifecycle(initial::put);
            EditorLifecycleState ref=EditorLifecycleState.read(initial::get);Models.Document saved=h.document;
            h.startupState=ref;h.startupDocumentId=saved.id;h.startupDocumentVersion="";h.startupPending=true;h.lifecycleState=null;h.lifecycleSnapshot=null;h.draft=null;h.document=null;
            h.completeStartup(h.generation,new StartupValue(saved,new IOException("synthetic transient read failure"),null),false,new ArrayList<>());
            Map<String,String> next=new HashMap<>();h.saveEditorLifecycle(next::put);
            check(ref.token.equals(next.get("editorState.token")),"failed receipt survives subsequent state save");
            EditorLifecycleState.Snapshot loaded=EditorLifecycleState.read(next::get).load(h.files);h.document=null;h.startupState=ref;h.startupPending=true;
            h.completeStartup(h.generation,new StartupValue(saved,null,loaded),false,new ArrayList<>());
            check(h.screen==2&&h.draft.ocrText.equals("未保存中文😀"),"actual completeStartup/restore reopens the preserved snapshot");
        });
        test("old large lifecycle snapshot conflict leaves document usable and immutable file intact",()->{
            EditorSaveBudgetHost h=setup(root,"large-conflict");h.screen=1;String huge="中".repeat(1_400_000);
            EditDraftStore.Draft old=new EditDraftStore.Draft(h.document.id,"c".repeat(64),h.originalPageBaseline,h.draft.json().toString()+huge,true,0);
            // A valid, oversized page JSON with a conflicting document version.
            Models.Page edited=h.draft.copy();edited.ocrText=huge;old=new EditDraftStore.Draft(h.document.id,"c".repeat(64),h.originalPageBaseline,edited.json().toString(),true,0);
            EditorLifecycleState ref=EditorLifecycleState.capture(h.files,new EditorLifecycleState.Snapshot(old,false));
            try{h.openLifecycleDraft(h.document,old);throw new AssertionError("conflict accepted");}catch(IOException expected){checks++;}
            check(h.screen==1&&h.recovery==null&&h.store.writes==0,"conflict never enters a blocking recovery screen");
            check(ref.load(h.files).draft.edited.equals(old.edited),"complete snapshot remains available");
            EditRecoveryState held=new EditRecoveryState(h.document.id,old);h.recovery=held;h.screen=3;h.keepRecoverySnapshotAndLeave(held);
            check(h.screen==1&&h.recovery==null&&h.lifecycleState.load(h.files).draft.edited.equals(old.edited),"legacy unverified conflict can preserve full file and explicitly leave");
        });
        test("legacy receipt still loading is preserved before startup worker verification",()->{
            EditorSaveBudgetHost h=setup(root,"legacy-loading");EditDraftStore.Entry entry=EditDraftStore.recovery(h.files).retain(h.document.id,h.editorDocumentVersion,h.originalPageBaseline,h.draft.json().toString(),true);
            h.recovery=new EditRecoveryState(h.document.id,null);h.recovery.entry=entry;h.draft=null;h.startupPending=true;
            Map<String,String> out=new HashMap<>();h.saveEditorLifecycle(out::put);
            check(entry.token.equals(out.get("editRecovery.token"))&&!h.recovery.verified&&!out.containsKey("editRecovery.edited"),"bounded unverified receipt survives without pretending verification or losing it");
        });
        test("loader publishing raw data between receipt fields cannot put OCR into saved state",()->{
            EditorSaveBudgetHost h=setup(root,"legacy-loading-race");EditDraftStore.Draft raw=new EditDraftStore.Draft(h.document.id,h.editorDocumentVersion,h.originalPageBaseline,h.draft.json().toString(),true,0);
            EditDraftStore.Entry entry=EditDraftStore.recovery(h.files).retain(h.document.id,raw.version,raw.baseline,raw.edited,true);
            h.recovery=new EditRecoveryState(h.document.id,null);h.recovery.entry=entry;h.draft=null;h.startupPending=true;
            Map<String,String> out=new HashMap<>();h.saveEditorLifecycle((key,value)->{out.put(key,value);if(key.equals("editRecovery.owner"))h.recovery.data=raw;});
            check(out.size()==4&&entry.token.equals(out.get("editRecovery.token"))&&!out.containsKey("editRecovery.edited"),"fixed receipt ignores intermediate data publication");
        });
        System.out.println("SCENARIOS "+scenarios+" ASSERTIONS "+checks+" FAILED 0; exact production methods/Models/mutations/codec; host JSON, Store and UI seams; no Android lifecycle");
    }
}
class Store {
    static final Object FILE_LOCK=new Object();int writes;boolean fail;
    /* STORE_METHODS */
    void saveIfCurrent(Models.Document document)throws Exception{if(fail)throw new IOException("synthetic writer failure");manifestBytes(document.json());writes++;document.savedSnapshot="b".repeat(64);}
}
class CropView{double[] getQuad(){return new double[]{0,0,1,0,1,1,0,1};}}
class AlertDialog {
    interface Callback{void run(Object dialog,int which);}
    static class Builder {final Map<String,Callback> actions=new HashMap<>();Builder(EditorSaveBudgetHost host){host.dialog=this;}Builder setTitle(String text){return this;}Builder setMessage(String text){return this;}Builder setPositiveButton(String text,Callback cb){actions.put(text,cb);return this;}Builder setNegativeButton(String text,Callback cb){actions.put(text,cb);return this;}Builder setNeutralButton(String text,Callback cb){actions.put(text,cb);return this;}void show(){}}
}

class Uri {}
