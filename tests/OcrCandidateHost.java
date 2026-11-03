package org.wangshu.scan;
import org.json.JSONObject;
import java.io.*;import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;import java.util.*;

/** Exact production callbacks with real candidate/Models/journal/source gates and synthetic UI/native seams. */
public final class OcrCandidateHost {
    Models.Document document;Models.Page originalPage,draft;Object editorSession=new Object(),recovery;String originalPageBaseline,editorDocumentVersion,activeSaveToken;
    int generation=1,screen=2,INK,MUTED,errors,builds;long navigationCycle;boolean living=true,resumed=true,stateSaved,busy,cropping;
    final EditorSaveFlow editorSaves=new EditorSaveFlow();OcrCandidate.Result ocrCandidate;Store store;File files;AlertDialog.Builder dialog;Throwable lastError;Runnable beforeCallback;String lastToast;
    static final String CLIPBOARD_SERVICE="clipboard";final android.content.ClipboardManager clipboard=new android.content.ClipboardManager();
    OcrCandidateHost(Path root)throws Exception{
        files=root.toFile();Files.createDirectories(root);document=new Models.Document();document.savedSnapshot="a".repeat(64);document.title="合成候选测试";
        originalPage=new Models.Page();byte[] image="synthetic image bytes; renderer is a host seam".getBytes(StandardCharsets.UTF_8);Path path=root.resolve("documents").resolve(document.id).resolve("originals").resolve(originalPage.id+".image");Files.createDirectories(path.getParent());Files.write(path,image);
        originalPage.file="originals/"+originalPage.id+".image";originalPage.sha256=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));originalPage.confirmCrop();originalPage.ocrText="合成旧修订😀\n公式 a² + b²";originalPage.ocrOrigin="manual";originalPage.ocrKey=OcrEngine.key(originalPage);originalPage.ocrLanguage="chi_sim+eng";
        document.pages.add(originalPage);draft=originalPage.copy();originalPageBaseline=originalPage.json().toString();editorDocumentVersion=document.savedSnapshot;store=new Store(files,document);resetNative();
    }
    boolean alive(){return living;}void toast(String message){lastToast=message;}void error(Throwable value){errors++;lastError=value;}File getFilesDir(){return files;}Object getSystemService(String name){return clipboard;}
    LinearLayout column(){return new LinearLayout();}TextView text(String value,int size,int color){return new TextView(value);}void buildEditor(){builds++;generation++;}
    interface Task{Object run()throws Exception;}interface Done{void accept(Object value);}
    void runIO(String label,Task task,Done done){runIO(label,task,done,false);}void runIO(String label,Task task,Done done,boolean cancellable){if(busy||!alive())return;busy=true;try{Object value=task.run();if(beforeCallback!=null){Runnable hook=beforeCallback;beforeCallback=null;hook.run();}busy=false;done.accept(value);}catch(Exception error){busy=false;error(error);}}
    /* MAIN_METHODS */
    static int checks,scenarios;interface Case{void run()throws Exception;}static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}static void test(String label,Case run)throws Exception{run.run();scenarios++;System.out.println("PASS "+label);}
    static void resetNative(){OcrEngine.current=new TessBaseAPI();Renderer.fail=false;Renderer.hook=null;Renderer.lastPage=null;Bitmap.recycled=0;}
    OcrCandidate.Request request()throws Exception{return new OcrCandidate.Request(document,editorSession,originalPage,draft,originalPageBaseline,editorDocumentVersion,generation,navigationCycle);}
    void recognize(){singlePageOcrOptions();dialog.click("中英混合候选");}
    EditDraftStore records(){return EditDraftStore.recovery(files);}
    void adopt(){dialog.click("保留旧稿并采用候选");}
    static OcrCandidateHost setup(Path root,String name)throws Exception{return new OcrCandidateHost(root.resolve(name));}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);Files.createDirectories(root);
        test("read-only single-page candidate uses current image parameters and leaves manual text untouched",()->{
            OcrCandidateHost h=setup(root,"candidate");h.draft.brightness=7;h.draft.confirmCrop();String before=h.draft.json().toString();h.recognize();
            check(h.ocrCandidate!=null&&h.draft.json().toString().equals(before)&&h.originalPage.ocrOrigin.equals("manual")&&h.store.writes==0,"recognition never saves or replaces manual text");
            check(Renderer.edge==2400&&Renderer.lastPage!=h.draft&&Renderer.lastPage.brightness==7,"frozen current processing parameters");check(Bitmap.recycled==1&&OcrEngine.current.recycled,"native resources released");
            h.dialog.click("复制新候选");check(h.clipboard.text.equals("合成新候选😀\nE = mc²")&&h.draft.json().toString().equals(before),"copy does not adopt");
        });
        test("explicit adoption retains exact old draft and only installs unsaved machine-origin text",()->{
            OcrCandidateHost h=setup(root,"adopt");h.draft.ocrText="尚未保存的人工全文😀\n  ";String old=h.draft.json().toString();String saved=h.originalPage.json().toString();h.recognize();h.adopt();
            check(h.ocrCandidate==null&&h.builds==1&&h.draft.ocrOrigin.equals("machine")&&h.draft.ocrLanguage.equals("chi_sim+eng")&&h.draft.ocrKey.equals(OcrEngine.key(h.draft)),"accepted candidate becomes unsaved machine draft");
            check(h.originalPage.json().toString().equals(saved)&&h.store.writes==0,"saved page unchanged");List<EditDraftStore.Entry> entries=h.records().list(h.document.id);EditDraftStore.Draft kept=h.records().load(h.document.id,entries.get(0));check(kept.edited.equals(old)&&!kept.submitted&&kept.baseline.equals(h.originalPageBaseline),"full prior draft is a real readable recovery record");
        });
        test("blank candidate is explicit; keeping or closing comparison retains original text",()->{
            OcrCandidateHost h=setup(root,"blank");OcrEngine.current.text="";h.recognize();String old=h.draft.ocrText;check(h.dialog.view.allText().contains("采用会清空文字"),"blank warning");h.showOcrCandidate(h.ocrCandidate);check(h.draft.ocrText.equals(old)&&h.records().list(h.document.id).isEmpty(),"view/keep has no persistence effects");h.adopt();check(h.draft.ocrText.isEmpty()&&!h.records().list(h.document.id).isEmpty(),"only explicit adoption empties draft after retention");
        });
        for(String failure:List.of("null","render","native","cancel"))test("recognition "+failure+" failure preserves manual revision and permits retry",()->{
            OcrCandidateHost h=setup(root,"failure-"+failure);String old=h.draft.json().toString();
            switch(failure){case "null":OcrEngine.current.text=null;break;case "render":Renderer.fail=true;break;case "native":OcrEngine.current.fail=true;break;case "cancel":Renderer.hook=()->Thread.currentThread().interrupt();break;}
            h.recognize();Thread.interrupted();check(h.errors==1&&h.ocrCandidate==null&&h.draft.json().toString().equals(old)&&h.store.writes==0,"failure does not mutate content");check(OcrEngine.current.recycled,"API released after failure");resetNative();h.recognize();check(h.ocrCandidate!=null,"explicit retry succeeds");
        });
        test("confirmation after edited input or same-ID session switch never starts recognition",()->{
            OcrCandidateHost h=setup(root,"language-stale");h.singlePageOcrOptions();AlertDialog.Builder old=h.dialog;h.draft.ocrText+="late";old.click("中英混合候选");check(OcrEngine.current.calls==0,"edited draft rejects old language choice");h.singlePageOcrOptions();old=h.dialog;h.editorSession=new Object();old.click("中英混合候选");check(OcrEngine.current.calls==0,"new session rejects old dialog");
        });
        for(String change:List.of("page","document","order","input","parameters","version","lifecycle"))test("late recognition callback rejects "+change+" drift",()->{
            OcrCandidateHost h=setup(root,"late-"+change);h.document.pages.add(new Models.Page());h.beforeCallback=()->{switch(change){case "page":try{h.draft=h.draft.copy();}catch(Exception e){throw new RuntimeException(e);}h.generation++;break;case "document":h.document=new Models.Document();break;case "order":Collections.reverse(h.document.pages);break;case "input":h.draft.ocrText="new manual input";break;case "parameters":h.draft.rotation=1;break;case "version":h.document.savedSnapshot="b".repeat(64);break;case "lifecycle":h.navigationCycle++;h.resumed=false;break;}};
            h.recognize();check(h.ocrCandidate==null&&h.store.writes==0,"no stale proposal publication or document writes");check(h.originalPage.ocrOrigin.equals("manual"),"saved manual origin preserved");
        });
        test("source file tampering and disk-version changes fail before publishing candidate",()->{
            OcrCandidateHost h=setup(root,"source-failure");Files.writeString(h.store.original(h.document,h.draft).toPath(),"tampered synthetic bytes");h.recognize();check(h.errors==1&&OcrEngine.current.calls==0&&h.ocrCandidate==null,"real hash gate before renderer");
            OcrCandidateHost d=setup(root,"disk-failure");d.store.diskVersion="b".repeat(64);d.recognize();check(d.errors==1&&d.ocrCandidate==null,"disk baseline gate");
        });
        test("another Store publication after final read invalidates recognition and adoption delivery",()->{
            OcrCandidateHost h=setup(root,"observed-recognition");String before=h.draft.json().toString();h.beforeCallback=()->{h.store.diskVersion="b".repeat(64);EditorSaveFlow.observePersisted(h.document.id,h.store.diskVersion);};h.recognize();check(h.ocrCandidate==null&&h.draft.json().toString().equals(before),"new observed version blocks late recognition with unchanged old owner");
            OcrCandidateHost a=setup(root,"observed-adoption");a.recognize();String old=a.draft.json().toString();a.beforeCallback=()->{a.store.diskVersion="b".repeat(64);EditorSaveFlow.observePersisted(a.document.id,a.store.diskVersion);};a.adopt();check(a.draft.json().toString().equals(old)&&a.builds==0&&!a.records().list(a.document.id).isEmpty(),"new observed version blocks stale adoption after verified old retention");
        });
        test("retention failure leaves old draft editable and same candidate can retry safely",()->{
            OcrCandidateHost h=setup(root,"retain-retry");h.recognize();OcrCandidate.Result result=h.ocrCandidate;String old=h.draft.json().toString();Path blocked=h.files.toPath().resolve("edit-recovery");Files.writeString(blocked,"synthetic blocking entry");h.adopt();
            check(h.errors==1&&h.draft.json().toString().equals(old)&&h.ocrCandidate==result&&h.recovery==null,"failed retain stays editable without recovery trap");Files.move(blocked,blocked.resolveSibling("preserved-blocking-entry"));h.showOcrCandidate(result);h.adopt();check(h.draft.ocrOrigin.equals("machine")&&h.records().list(h.document.id).size()==1,"retry retained once and adopted");
        });
        test("large prior draft and oversized proposed document reject adoption without truncation",()->{
            OcrCandidateHost h=setup(root,"large-prior");h.draft.ocrText="中".repeat(1_400_000);h.recognize();h.adopt();check(h.errors==1&&h.draft.ocrText.length()==1_400_000&&h.draft.ocrOrigin.equals("manual"),"full old text retained in editable memory after serializer rejection");
            OcrCandidateHost p=setup(root,"large-proposed");OcrEngine.current.text="候".repeat(750_000);p.recognize();String old=p.draft.ocrText;p.adopt();check(p.errors==1&&p.draft.ocrText.equals(old)&&p.records().list(p.document.id).isEmpty(),"whole-document byte preflight before recording/adoption");
        });
        test("late adoption completion and duplicate confirmation cannot overwrite current revision",()->{
            OcrCandidateHost h=setup(root,"late-adopt");h.recognize();OcrCandidate.Result result=h.ocrCandidate;h.beforeCallback=()->h.draft.ocrText="newer user text";h.adopt();check(h.draft.ocrText.equals("newer user text")&&!h.records().list(h.document.id).isEmpty(),"stale apply rejected after successful old retention");
            h.showOcrCandidate(result);check(!h.dialog.actions.containsKey("保留旧稿并采用候选")&&h.dialog.view.allText().contains("已过期"),"stale candidate is copy-only");
            OcrCandidateHost d=setup(root,"duplicate");d.recognize();AlertDialog.Builder once=d.dialog;d.adopt();String accepted=d.draft.json().toString();once.click("保留旧稿并采用候选");check(d.draft.json().toString().equals(accepted)&&d.records().list(d.document.id).size()==1&&d.builds==1,"one-shot duplicate guarded");
        });
        test("older candidate acceptance cannot consume a later proposal",()->{
            OcrCandidateHost h=setup(root,"proposal-race");h.recognize();AlertDialog.Builder old=h.dialog;OcrCandidate.Result first=h.ocrCandidate;h.recognize();OcrCandidate.Result newer=h.ocrCandidate;old.click("保留旧稿并采用候选");check(h.ocrCandidate==newer&&newer!=first&&h.draft.ocrOrigin.equals("manual")&&h.records().list(h.document.id).isEmpty(),"proposal identity guards stale confirmation");
        });
        test("adopted candidate uses existing save coordinator rollback and retry without losing old revision",()->{
            OcrCandidateHost h=setup(root,"save-retry");String prior=h.draft.json().toString();h.recognize();h.adopt();Models.Page next=h.draft.copy();EditorSaveFlow.Request req=h.editorSaves.begin(h.editorSession,h.document.id,h.originalPage.id,h.originalPageBaseline,next.json().toString(),new ArrayList<>(TextExport.order(h.document)),true,false);
            try{h.editorSaves.commit(req,h.document.id,h.originalPage.json().toString(),new ArrayList<>(TextExport.order(h.document)),()->DocumentMutations.replacePage(h.document,h.originalPage,next,d->{throw new IOException("synthetic save failure");}),()->h.document.savedSnapshot);throw new AssertionError("save should fail");}catch(Exception expected){h.editorSaves.fail(req);}
            check(h.document.pages.get(0)==h.originalPage&&h.draft.ocrText.equals(next.ocrText)&&h.records().load(h.document.id,h.records().list(h.document.id).get(0)).edited.equals(prior),"failed page save preserves both draft and prior record");
            EditorSaveFlow.Request retry=h.editorSaves.begin(h.editorSession,h.document.id,h.originalPage.id,h.originalPageBaseline,next.json().toString(),new ArrayList<>(TextExport.order(h.document)),true,false);h.editorSaves.commit(retry,h.document.id,h.originalPage.json().toString(),new ArrayList<>(TextExport.order(h.document)),()->DocumentMutations.replacePage(h.document,h.originalPage,next,d->{d.savedSnapshot="c".repeat(64);}),()->h.document.savedSnapshot);check(h.document.pages.get(0).ocrOrigin.equals("machine"),"explicit retry saves accepted provenance");
        });
        System.out.println("SCENARIOS "+scenarios+" ASSERTIONS "+checks+" FAILED 0; production candidate/engine callbacks, Models/preflight/real journals/source hashes; native OCR/render/UI/Store/JSON/scheduler seams; no Android or accuracy acceptance");
    }
}
class Store {
    static final Object FILE_LOCK=new Object();final File files;Object context=new Object();String diskVersion;int writes;
    Store(File files,Models.Document d){this.files=files;diskVersion=d.savedSnapshot;EditorSaveFlow.observePersisted(d.id,diskVersion);}void assertCurrentReadable(Models.Document d)throws Exception{requireCertain(d);EditorSaveFlow.observePersisted(d.id,diskVersion);if(!diskVersion.equals(d.savedSnapshot))throw new IOException("synthetic persisted version mismatch");}
    File original(Models.Document d,Models.Page p){return new File(new File(new File(files,"documents"),d.id),p.file);}
    /* STORE_METHODS */
}
class OcrEngine {
    static TessBaseAPI current;static TessBaseAPI open(Object context,String language){return current;}
    /* OCR_METHODS */
}
class TessBaseAPI {String text="合成新候选😀\nE = mc²";boolean recycled,fail;int calls;void setImage(Bitmap b){}String getUTF8Text()throws IOException{calls++;if(fail)throw new IOException("synthetic native failure");return text;}void recycle(){recycled=true;}}
class Bitmap {static int recycled;void recycle(){recycled++;}}
class Renderer {static int edge;static boolean fail;static Runnable hook;static Models.Page lastPage;static Bitmap render(File file,Models.Page p,int max)throws IOException{edge=max;lastPage=p;if(fail)throw new IOException("synthetic render failure");if(hook!=null)hook.run();return new Bitmap();}}
class TextView {final String text;TextView(String text){this.text=text;}void setTextIsSelectable(boolean value){}}
class LinearLayout {List<Object> children=new ArrayList<>();void addView(Object child){children.add(child);}String allText(){StringBuilder text=new StringBuilder();for(Object child:children)if(child instanceof TextView t)text.append(t.text);return text.toString();}}
class ScrollView {LinearLayout view;ScrollView(Object host){}void addView(LinearLayout view){this.view=view;}String allText(){return view.allText();}}
class ClipData {String text;static ClipData newPlainText(String label,String value){ClipData data=new ClipData();data.text=value;return data;}}
class android {static class content {static class ClipboardManager {String text;void setPrimaryClip(ClipData data){text=data.text;}}}}
class AlertDialog {
    interface Click{void run(Object dialog,int which);}static class Builder {
        String title,message;ScrollView view;Map<String,Click> actions=new HashMap<>();Builder(OcrCandidateHost host){host.dialog=this;}Builder setTitle(String text){title=text;return this;}Builder setMessage(String text){message=text;return this;}Builder setView(ScrollView view){this.view=view;return this;}Builder setPositiveButton(String text,Click action){actions.put(text,action);return this;}Builder setNeutralButton(String text,Click action){actions.put(text,action);return this;}Builder setNegativeButton(String text,Click action){actions.put(text,action);return this;}void show(){}void click(String label){Click action=actions.get(label);if(action==null)throw new AssertionError("button missing "+label);action.run(this,0);}
    }
}
