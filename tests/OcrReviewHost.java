package org.wangshu.scan;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Template populated with exact production methods; controls and Models are synthetic. */
public final class OcrReviewHost {
    Models.Document document=new Models.Document();Models.Page draft,originalPage,opened;
    Object editorSession=new Object(),recovery;
    final EditorSaveFlow editorSaves=new EditorSaveFlow();
    int generation=1,screen=2,INK=1,MUTED=2;boolean cropping,busy,stateSaved,resumed=true,living=true;
    TextView ocrStatus;EditText lastInput;Button lastButton;AlertDialog.Builder lastDialog;
    final android.content.ClipboardManager clipboard=new android.content.ClipboardManager();
    static final String CLIPBOARD_SERVICE="clipboard";
    boolean alive(){return living;}Object getSystemService(String key){return clipboard;}
    void toast(String message){}void openEditor(Models.Page page){opened=page;}
    TextView text(String value,int size,int color){TextView view=new TextView();view.setText(value);return view;}
    Button button(String label,Runnable action){lastButton=new Button(action);return lastButton;}
    /* PRODUCTION_METHODS */
    static int assertions,scenarios;
    static void check(boolean ok,String why){assertions++;if(!ok)throw new AssertionError(why);}
    interface Case{void run()throws Exception;}
    static void test(String label,Case run)throws Exception{run.run();scenarios++;System.out.println("PASS "+label);}
    static OcrReviewHost ready(){
        OcrReviewHost host=new OcrReviewHost();host.originalPage=new Models.Page();
        host.originalPage.ocrText="旧错字";host.originalPage.ocrKey=OcrEngine.key(host.originalPage);
        host.document.pages.add(host.originalPage);host.draft=host.originalPage.copy();return host;
    }
    static LinearLayout build(OcrReviewHost host){LinearLayout controls=new LinearLayout();host.addOcrEditor(controls);return controls;}
    static void refused(EditorSaveFlow.Writer writer)throws Exception{try{writer.save();throw new AssertionError("expected refusal");}catch(IOException expected){assertions++;}}
    public static void main(String[] args)throws Exception{
        test("exact controls initialize from restored draft without erasing text",()->{
            OcrReviewHost h=ready();h.draft.ocrText="恢复中文\nE = mc²\n𠮷 😀  ";build(h);
            check(h.lastInput.getText().equals(h.draft.ocrText),"initial content exact");
            check(h.lastInput.listeners.size()==1,"one bound listener");
            check(h.ocrStatus.getText().contains("尚未保存"),"restored edit is unsaved");
            h.lastInput.edit("新正字\nE = mc²\n𠮷 😀  ");
            check(h.draft.ocrText.equals("新正字\nE = mc²\n𠮷 😀  "),"Unicode, whitespace, formula preserved literally");
            check(h.originalPage.ocrText.equals("旧错字"),"saved page untouched while typing");
            check(h.draft.ocrKey.equals(h.originalPage.ocrKey)&&h.draft.ocrLanguage.equals("chi_sim+eng"),"key/language never refreshed");
            h.lastButton.click();check(h.clipboard.clip.equals(h.draft.ocrText),"copy gets current draft, not saved version");
            h.lastInput.edit("");check(h.draft.ocrText.isEmpty()&&h.ocrStatus.getText().contains("尚未保存"),"intentional clear retained");
            h.lastInput.edit(h.originalPage.ocrText);check(h.ocrStatus.getText().contains("与已保存版本一致"),"undo to saved text updates label");
        });
        for(String state:List.of("document","draft-same-id","session","generation","screen","crop","busy","dead","saved-state","paused","uncertain","recovery","pending"))test("late text event rejected after "+state,()->{
            OcrReviewHost h=ready();build(h);EditText old=h.lastInput;Models.Page target=h.draft;
            switch(state){
                case "document":h.document=new Models.Document();break;
                case "draft-same-id":h.draft=target.copy();break;
                case "session":h.editorSession=new Object();break;
                case "generation":h.generation++;break;
                case "screen":h.screen=1;break;
                case "crop":h.cropping=true;break;
                case "busy":h.busy=true;break;
                case "dead":h.living=false;break;
                case "saved-state":h.stateSaved=true;break;
                case "paused":h.resumed=false;break;
                case "uncertain":h.document.manifestUncertain=true;break;
                case "recovery":h.recovery=new Object();break;
                case "pending":h.editorSaves.begin(h.editorSession,h.document.id,target.id,"base","edit",List.of(target.id),true,true);break;
            }
            old.edit("WRONG PAGE");check(target.ocrText.equals("旧错字")&&h.draft.ocrText.equals("旧错字"),"neither old nor current draft changed");
            check(old.getText().equals(target.ocrText),"rejected IME text restored to bound model");
        });
        test("old rendered editor cannot edit or copy a new draft of the same page",()->{
            OcrReviewHost h=ready();build(h);EditText old=h.lastInput;Button copy=h.lastButton;
            h.generation++;h.draft=h.draft.copy();build(h);old.edit("late");copy.click();
            check(h.clipboard.clip==null&&h.draft.ocrText.equals("旧错字"),"old callbacks inert");
            h.lastInput.edit("current");check(h.draft.ocrText.equals("current"),"current render usable");
        });
        test("stale, empty and unreviewed OCR remain correctly classified after corrections",()->{
            OcrReviewHost h=ready();build(h);h.lastInput.edit("修订中文");
            Models.Document saved=new Models.Document();saved.pages.add(h.draft);
            check(h.matches(saved,"修订中文"),"current reviewed corrections searchable after save seam");
            h.draft.brightness=9;h.refreshOcrStatus();check(h.ocrStatus.getText().contains("旧识别"),"image change immediately invalidates label");
            h.lastInput.edit("旧稿纠错");check(!h.matches(saved,"旧稿纠错"),"editing cannot revive stale search text");
            check(describeOcrPages(saved).contains("旧识别")&&describeOcrPages(saved).contains("旧稿纠错"),"old text still available for copying");
            h.draft.ocrKey=OcrEngine.key(h.draft);h.draft.reviewed=false;h.refreshOcrStatus();
            check(h.ocrStatus.getText().contains("四角待核对")&&!h.matches(saved,"旧稿纠错"),"crop review gate retained");
            h.draft.reviewed=true;h.draft.ocrKey="";h.lastInput.edit("手动抄录");
            check(h.ocrStatus.getText().contains("尚无识别基线")&&!h.matches(saved,"手动抄录"),"manual text creates no OCR baseline");
        });
        test("selector includes blank pages and opens the exact captured page object",()->{
            OcrReviewHost h=ready();h.screen=1;Models.Page blank=new Models.Page();blank.id="page-b";h.document.pages.add(blank);
            h.showOcrReviewPages();check(h.lastDialog.labels.length==2&&h.lastDialog.labels[1].contains("第 2 页")&&h.lastDialog.labels[1].contains("尚无"),"blank page listed");
            h.lastDialog.choose(1);check(h.opened==blank,"selected page identity, not current index");
        });
        for(String state:List.of("document","replace","removed","busy","screen","saved-state","paused","uncertain"))test("stale selector rejects "+state,()->{
            OcrReviewHost h=ready();h.screen=1;h.showOcrReviewPages();AlertDialog.Builder dialog=h.lastDialog;
            switch(state){
                case "document":h.document=new Models.Document();break;
                case "replace":h.document.pages.set(0,h.originalPage.copy());break;
                case "removed":h.originalPage.removed=true;break;
                case "busy":h.busy=true;break;
                case "screen":h.screen=2;break;
                case "saved-state":h.stateSaved=true;break;
                case "paused":h.resumed=false;break;
                case "uncertain":h.document.manifestUncertain=true;break;
            }
            dialog.choose(0);check(h.opened==null,"no editor publication");
        });
        test("OCR save snapshot survives writer failure, journal readback and explicit retry",()->{
            OcrReviewHost h=ready();build(h);h.lastInput.edit("纠错\n𠮷 😀\nE=mc²  ");
            String base=h.originalPage.encoded(),edited=h.draft.encoded();
            EditorSaveFlow.Request q=h.editorSaves.begin(h.editorSession,h.document.id,h.draft.id,base,edited,List.of(h.draft.id,"page-b"),true,true);
            Path files=Paths.get(args[0]);Files.createDirectories(files);EditDraftStore journal=EditDraftStore.recovery(files.toFile());
            EditDraftStore.Entry entry=journal.retain(h.document.id,h.document.savedSnapshot,base,edited,true);
            EditDraftStore.Draft record=journal.load(h.document.id,entry);
            check(record.edited.equals(edited)&&record.baseline.equals(base)&&record.submitted,"real host journal preserves exact OCR edit");
            check(journal.retain(h.document.id,h.document.savedSnapshot,base,edited,true).token.equals(entry.token),"identical retain deduplicated");
            AtomicInteger writes=new AtomicInteger();
            refused(()->{h.editorSaves.commit(q,h.document.id,base,q.order,()->{writes.incrementAndGet();throw new IOException("injected writer failure");});return true;});
            check(writes.get()==1&&h.editorSaves.hasPending()&&h.draft.encoded().equals(edited),"failure retains draft, has no navigation result");
            h.editorSaves.fail(q);EditorSaveFlow.Request retry=h.editorSaves.begin(h.editorSession,h.document.id,h.draft.id,base,edited,q.order,true,true);
            EditorSaveFlow.Saved result=h.editorSaves.commit(retry,h.document.id,base,q.order,()->{writes.incrementAndGet();return true;},()->"b".repeat(64));
            check(writes.get()==2&&retry.nextPageId.equals("page-b"),"explicit retry commits frozen correct page");
            check(h.editorSaves.finish(result,h.editorSession,h.document.id,h.draft.id,base,edited),"success consumed once");
            check(!h.editorSaves.finish(result,h.editorSession,h.document.id,h.draft.id,base,edited),"duplicate callback cannot advance again");
        });
        test("recovery write failure prevents manifest writer and preserves correction",()->{
            OcrReviewHost h=ready();build(h);h.lastInput.edit("保留纠错");String frozen=h.draft.encoded();AtomicInteger writes=new AtomicInteger();
            refused(()->EditorSaveFlow.afterRecovery(()->{throw new IOException("injected journal failure");},()->{writes.incrementAndGet();return true;}));
            check(writes.get()==0&&h.draft.encoded().equals(frozen),"failed retention never reaches writer or erases draft");
        });
        test("real delayed writer freezes OCR and late old callback cannot navigate next session",()->{
            OcrReviewHost h=ready();build(h);h.lastInput.edit("提交前纠错");String base=h.originalPage.encoded(),edit=h.draft.encoded();
            EditorSaveFlow.Request q=h.editorSaves.begin(h.editorSession,h.document.id,h.draft.id,base,edit,List.of(h.draft.id,"page-b"),true,true);
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicReference<EditorSaveFlow.Saved> saved=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();
            Thread writer=new Thread(()->{try{saved.set(h.editorSaves.commit(q,h.document.id,base,q.order,()->{entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IOException("timeout");return true;}));}catch(Throwable problem){failure.set(problem);}});
            writer.start();check(entered.await(5,TimeUnit.SECONDS),"writer entered");
            h.lastInput.edit("提交后迟到事件");check(h.draft.ocrText.equals("提交前纠错")&&q.edited.equals(edit),"pending save prevents input drift");
            refused(()->{h.editorSaves.begin(h.editorSession,h.document.id,h.draft.id,base,edit,q.order,true,true);return true;});
            h.editorSession=new Object();h.draft=h.draft.copy();release.countDown();writer.join(6000);
            check(!writer.isAlive()&&failure.get()==null,"real writer completed");
            check(!h.editorSaves.finish(saved.get(),h.editorSession,h.document.id,h.draft.id,base,edit),"late callback cannot navigate new session");
        });
        System.out.println("SCENARIOS "+scenarios+" ASSERTIONS "+assertions+" FAILED 0; synthetic UI/Models, real coordinator/journal; no device/OCR/PDF");
    }
}
class Models {
    static class Document {
        String id="12345678-1234-1234-1234-123456789abc",title="Synthetic",savedSnapshot="a".repeat(64);
        boolean manifestUncertain;ArrayList<Page> pages=new ArrayList<>();
        ArrayList<Page> active(){ArrayList<Page> out=new ArrayList<>();for(Page page:pages)if(!page.removed)out.add(page);return out;}
    }
    static class Page {
        String id="page-a",sha256="a".repeat(64),mode="COLOR",ocrText="",ocrKey="",ocrLanguage="chi_sim+eng";
        double[] quad={0,0,1,0,1,1,0,1};int rotation,brightness,bias=8;boolean shadows,removed,reviewed=true;
        boolean cropConfirmed(){return reviewed;}
        Page copy(){Page p=new Page();p.id=id;p.sha256=sha256;p.mode=mode;p.ocrText=ocrText;p.ocrKey=ocrKey;p.ocrLanguage=ocrLanguage;p.quad=quad.clone();p.rotation=rotation;p.brightness=brightness;p.bias=bias;p.shadows=shadows;p.removed=removed;p.reviewed=reviewed;return p;}
        // Synthetic framing; does not replace/test Android org.json serialization.
        String encoded(){StringBuilder out=new StringBuilder();for(String s:List.of(id,ocrText,ocrKey,ocrLanguage))out.append(s.length()).append(':').append(s);return out.toString();}
    }
}
class OcrEngine { /* PRODUCTION_KEY */ }
class TextView {String value="";void setText(CharSequence text){value=text.toString();}String getText(){return value;}}
class EditText extends TextView {
    final List<TextWatcher> listeners=new ArrayList<>();
    EditText(OcrReviewHost host){host.lastInput=this;}void setTextColor(int color){}void setHintTextColor(int color){}void setTextSize(int size){}void setInputType(int type){}void setSaveEnabled(boolean enabled){}void setGravity(int gravity){}void setMinLines(int n){}void setMaxLines(int n){}void setHint(String value){}void setContentDescription(String value){}
    void addTextChangedListener(TextWatcher listener){listeners.add(listener);}
    @Override void setText(CharSequence value){super.setText(value);for(TextWatcher listener:listeners)listener.afterTextChanged(new Editable(value.toString()));}
    void edit(String value){setText(value);}
}
class Editable {final String value;Editable(String value){this.value=value;}public String toString(){return value;}}
interface TextWatcher {void beforeTextChanged(CharSequence s,int start,int count,int after);void onTextChanged(CharSequence s,int start,int before,int count);void afterTextChanged(Editable value);}
class LinearLayout {final List<Object> children=new ArrayList<>();void addView(Object view){children.add(view);}void addView(Object view,LayoutParams params){children.add(view);}static class LayoutParams{LayoutParams(int w,int h){}}}
class Button {final Runnable action;Button(Runnable action){this.action=action;}void click(){action.run();}}
class InputType {static final int TYPE_CLASS_TEXT=1,TYPE_TEXT_FLAG_MULTI_LINE=0x20000;}
class Gravity {static final int TOP=48;}
class ClipData {static Object newPlainText(String label,String value){return value;}}
class AlertDialog {
    interface Choice {void run(Object dialog,int which);}
    static class Builder {
        String[] labels;Choice action;Builder(OcrReviewHost host){host.lastDialog=this;}
        Builder setTitle(String title){return this;}Builder setItems(String[] labels,Choice action){this.labels=labels;this.action=action;return this;}Builder setNegativeButton(String title,Choice action){return this;}void show(){}void choose(int which){action.run(this,which);}
    }
}
