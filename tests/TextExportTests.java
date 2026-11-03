package org.wangshu.scan;
import java.io.*;import java.nio.charset.StandardCharsets;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;

/** Real production format, provenance, Models, mutation rollback and receipt/target protocol. */
public final class TextExportTests {
    static int checks,scenarios;static final String VERSION="a".repeat(64);
    interface Case{void run()throws Exception;}static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}static void test(String name,Case action)throws Exception{action.run();scenarios++;System.out.println("PASS "+name);}
    static void rejected(Case action)throws Exception{try{action.run();throw new AssertionError("expected rejection");}catch(IOException expected){checks++;}}
    static Models.Page page(String id,String text,String origin){Models.Page p=new Models.Page();p.id=id;p.file="originals/"+id+".image";p.sha256="b".repeat(64);p.ocrText=text;p.ocrOrigin=origin;p.confirmCrop();p.ocrKey=OcrEngine.key(p);return p;}
    static Models.Document doc(Models.Page... pages){Models.Document d=new Models.Document();d.savedSnapshot=VERSION;d.title="合成文稿😀";d.pages.addAll(Arrays.asList(pages));return d;}
    static TextExport.Request request(Models.Document d)throws Exception{return new TextExport.Request(d,null,null,null,null,TextExport.Source.SAVED_DOCUMENT,1);}
    static String text(TextExport.Snapshot snapshot)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();TextExport.write(snapshot,out);return out.toString(StandardCharsets.UTF_8);}
    static class Persistence implements SaveJobStore.Persistence {byte[] bytes;public byte[] read(){return bytes==null?null:bytes.clone();}public void write(byte[] value){bytes=value.clone();}}
    static class Sources implements SaveJobStore.Sources {public boolean isFile(File f){return f.isFile();}public long length(File f){return f.length();}public SaveJobStore.SourceHandle open(File f)throws IOException{FileInputStream in=new FileInputStream(f);return new SaveJobStore.SourceHandle(){public InputStream stream(){return in;}public void rewind()throws IOException{in.getChannel().position(0);}public void close()throws IOException{in.close();}};}}
    static class Fixture {
        final Path root;final SaveJobStore jobs;final Persistence persistence=new Persistence();final File source;int opens;
        Fixture(Path parent,String name)throws Exception{root=parent.resolve(name);Files.createDirectories(root.resolve("cache/exports"));Files.createDirectories(root.resolve("files"));source=root.resolve("cache/exports/sample.txt").toFile();Files.writeString(source.toPath(),"合成中文😀\nE = mc²\n  ",StandardCharsets.UTF_8);jobs=new SaveJobStore(root.resolve("cache").toFile(),root.resolve("files").toFile(),persistence,new Sources());}
        SaveJobStore.Job chosen()throws Exception{SaveJobStore.Job j=jobs.begin(source,"text/plain","文稿-文字副本.txt");jobs.picker(j.token);jobs.chosen(j.token);return j;}
        TextDocumentSave.Target target(Path file,boolean fail){return ()->{opens++;RandomAccessFile target=new RandomAccessFile(file.toFile(),"rw");long length=target.length();return new TextDocumentSave.Handle(){public boolean regular(){return true;}public long bytes(){return length;}public OutputStream output(){return new OutputStream(){int count;public void write(int n)throws IOException{if(fail&&count++>3)throw new IOException("synthetic target failure");target.write(n);}};}public void close()throws Exception{target.close();}};};}
    }
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);Files.createDirectories(root);
        test("saved document has numbered empty/current/manual/unknown/stale page states and literal formulas",()->{
            Models.Page a=page("a","中文😀\nE = mc²\n  ","machine"),b=page("b","","manual"),c=page("c","旧文字","");c.brightness=8;
            Models.Document d=doc(a,b,c);String out=text(request(d).snapshot(d));
            check(out.contains("第 1 / 3 页")&&out.contains("第 2 / 3 页")&&out.contains("第 3 / 3 页"),"all positions retained");
            check(out.contains("中文😀\nE = mc²\n  ")&&out.contains("（本页无文字）"),"body, emoji, whitespace and empty page preserved");
            check(out.contains("自动识别")&&out.contains("人工编辑过")&&out.contains("来源未记录")&&out.contains("旧识别 / 图像参数已变化"),"provenance and image validity are separate");
            check(out.contains("是否已保存到文稿：是")&&out.contains("不会更改可搜索 PDF"),"saved source and PDF limitation explicit");
        });
        test("old JSON schema omits unknown origin; new origin roundtrips and invalid origin is never trusted",()->{
            Models.Page p=page("a","旧文本","");String old=p.json().toString();check(!old.contains("ocrOrigin"),"unknown origin omitted for old checkpoint equality");
            check(Models.Page.from(new org.json.JSONObject(old)).json().toString().equals(old),"old JSON exact roundtrip");
            p.ocrOrigin="manual";check(p.copy().ocrOrigin.equals("manual"),"known optional origin preserved");
            org.json.JSONObject bad=p.json();bad.put("ocrOrigin","fully-reviewed");check(Models.Page.from(bad).ocrOrigin.isEmpty(),"unrecognized origin cannot imply review");
        });
        test("new recognition records machine origin and real mutation failure restores previous manual origin",()->{
            Models.Page p=page("a","人工修正","manual");Models.Document d=doc(p);
            try{DocumentMutations.ocr(d,p,"自动重识别",OcrEngine.key(p),"eng",owner->{throw new IOException("injected persistence failure");});throw new AssertionError("expected mutation failure");}catch(DocumentMutations.SaveFailure expected){checks++;}
            check(p.ocrText.equals("人工修正")&&p.ocrOrigin.equals("manual"),"failed recognition rollback includes origin");
            DocumentMutations.ocr(d,p,"自动结果",OcrEngine.key(p),"eng",owner->{});check(p.ocrOrigin.equals("machine"),"new successful recognition provenance");
        });
        test("draft source does not save document, mutate original text or freshen old OCR key",()->{
            Models.Page saved=page("a","已保存","");Models.Document d=doc(saved);Models.Page draft=saved.copy();draft.ocrText="未保存😀\n公式 x²";draft.ocrOrigin="manual";draft.rotation=1;Object session=new Object();
            TextExport.Request r=new TextExport.Request(d,session,draft,saved,saved.json().toString(),TextExport.Source.DRAFT_PAGE,2);
            String out=text(r.snapshot(d));check(out.contains("否（编辑草稿副本）")&&out.contains("未保存😀\n公式 x²")&&out.contains("旧识别 / 图像参数已变化"),"draft and stale render status explicit");
            check(saved.ocrText.equals("已保存")&&d.pages.get(0)==saved&&draft.ocrKey.equals(saved.ocrKey),"no saved-page or OCR-key mutation");
            String savedOut=text(new TextExport.Request(d,session,draft,saved,saved.json().toString(),TextExport.Source.SAVED_PAGE,2).snapshot(d));
            check(savedOut.contains("已保存")&&!savedOut.contains("未保存😀"),"saved-page selection excludes draft");
        });
        test("frozen request rejects reorder, version shift, removed page and changed draft baseline",()->{
            Models.Page a=page("a","A","machine"),b=page("b","B","machine");Models.Document d=doc(a,b);TextExport.Request r=request(d);
            Collections.reverse(d.pages);check(!r.current(d,null,null,1),"reorder invalidates approval");rejected(()->r.snapshot(d));Collections.reverse(d.pages);
            d.savedSnapshot="c".repeat(64);check(!r.current(d,null,null,1),"version invalidates");d.savedSnapshot=VERSION;a.removed=true;check(!r.current(d,null,null,1),"removed page invalidates");a.removed=false;
            Models.Page draft=a.copy();draft.ocrText="draft";TextExport.Request dr=new TextExport.Request(d,new Object(),draft,a,a.json().toString(),TextExport.Source.DRAFT_PAGE,2);a.ocrText="new persisted";rejected(()->dr.snapshot(d));
        });
        test("context rejects document switch, page switch, same-ID replacement and unsaved text drift",()->{
            Models.Page a=page("a","A","machine");Models.Document d=doc(a);Models.Page draft=a.copy();Object session=new Object();TextExport.Context context=new TextExport.Context(d,session,draft,2);
            check(context.current(d,session,draft,2),"initial context accepted");check(!context.current(doc(a),session,draft,2)&&!context.current(d,new Object(),draft,2)&&!context.current(d,session,draft.copy(),2),"identity guards");
            draft.ocrText="changed";check(!context.current(d,session,draft,2),"draft drift invalidates target permission");
        });
        test("no active pages, invalid source and duplicate page identities fail closed",()->{
            rejected(()->request(doc()));Models.Page a=page("a","A","");Models.Document d=doc(a,a);rejected(()->request(d));
            rejected(()->new TextExport.Request(doc(a),null,null,null,null,null,1));
        });
        test("strict UTF-8 rejects malformed surrogate without publishing complete export",()->{
            Models.Page a=page("a","bad\ud800","");Models.Document d=doc(a);Path cache=root.resolve("invalid-cache");Files.createDirectories(cache);rejected(()->TextExport.create(cache.toFile(),request(d).snapshot(d)));
            try(var files=Files.list(cache.resolve("exports"))){check(files.noneMatch(p->p.toString().endsWith(".txt")),"no completed TXT on encoding failure");}
            check(a.ocrText.equals("bad\ud800"),"invalid input remains editable in caller");
        });
        test("large 4.2MB Chinese draft exports fully and original is untouched",()->{
            Models.Page a=page("a","saved","");Models.Document d=doc(a);Models.Page draft=a.copy();draft.ocrText="中".repeat(1_400_000);draft.ocrOrigin="manual";TextExport.Request r=new TextExport.Request(d,new Object(),draft,a,a.json().toString(),TextExport.Source.DRAFT_PAGE,2);
            Path cache=root.resolve("large-cache");Files.createDirectories(cache);TextExport.Result result=TextExport.create(cache.toFile(),r.snapshot(d));String out=Files.readString(result.file.toPath());check(out.contains(draft.ocrText)&&result.bytes>4_200_000,"full large text and metadata");check(a.ocrText.equals("saved"),"saved original preserved");
        });
        test("cancellation interrupts generation without publishing a complete file",()->{
            Path cache=root.resolve("cancel-cache");Files.createDirectories(cache);Models.Document d=doc(page("a","abc",""));Thread.currentThread().interrupt();try{rejected(()->TextExport.create(cache.toFile(),request(d).snapshot(d)));}finally{Thread.interrupted();}
            try(var files=Files.list(cache.resolve("exports"))){check(files.noneMatch(p->p.toString().endsWith(".txt")),"cancelled generation not complete");}
        });
        test("metadata title is single line and filename does not split an emoji",()->{
            Models.Document d=doc(page("a","body",""));d.title="title\n第 99 页\t😀";String out=text(request(d).snapshot(d));check(out.contains("文稿：title\\n第 99 页\\t😀\n"),"title cannot forge metadata line breaks");
            String filename=TextExport.fileTitle("😀".repeat(101)+"/bad");check(filename.codePointCount(0,filename.indexOf('-'))==100&&!filename.contains("/"),"safe bounded name on code point boundary");
        });
        test("new empty target saves once; duplicate callback never reopens it",()->{
            Fixture f=new Fixture(root,"once");SaveJobStore.Job j=f.chosen();Path target=f.root.resolve("new.txt");TextDocumentSave.save(f.jobs,j.token,f.target(target,false));
            check(Files.readString(target).equals(Files.readString(f.source.toPath()))&&f.jobs.read().phase==SaveJobStore.DONE,"literal bytes and DONE receipt");
            rejected(()->TextDocumentSave.save(f.jobs,j.token,f.target(target,false)));check(f.opens==1&&f.jobs.read().phase==SaveJobStore.DONE,"duplicate never opens or corrupts DONE");
        });
        test("nonempty or unknown target is refused without truncation",()->{
            Fixture f=new Fixture(root,"nonempty");SaveJobStore.Job j=f.chosen();Path target=f.root.resolve("old.txt");Files.writeString(target,"USER SYNTHETIC CONTENT");
            rejected(()->TextDocumentSave.save(f.jobs,j.token,f.target(target,false)));check(Files.readString(target).equals("USER SYNTHETIC CONTENT"),"existing content never truncated");
            rejected(()->TextDocumentSave.requireEmpty(true,-1));rejected(()->TextDocumentSave.requireEmpty(false,0));
        });
        test("write failure retains partial target and source; explicit reselect gets a new token and target",()->{
            Fixture f=new Fixture(root,"retry");SaveJobStore.Job old=f.chosen();Path partial=f.root.resolve("partial.txt");rejected(()->TextDocumentSave.save(f.jobs,old.token,f.target(partial,true)));byte[] left=Files.readAllBytes(partial);
            check(f.jobs.read().phase==SaveJobStore.NEEDS_RESELECT&&f.source.isFile(),"failed write marked uncertain with source retained");f.jobs.reselectText(old.token);SaveJobStore.Job next=f.chosen();check(!old.token.equals(next.token),"retry has fresh token");Path fresh=f.root.resolve("retry.txt");TextDocumentSave.save(f.jobs,next.token,f.target(fresh,false));check(Arrays.equals(left,Files.readAllBytes(partial))&&Files.readString(fresh).equals(Files.readString(f.source.toPath())),"retry never touches partial prior target");
        });
        test("cancelled request and changed source cannot open targets",()->{
            Fixture f=new Fixture(root,"cancel-receipt");SaveJobStore.Job j=f.chosen();f.jobs.cancel(j.token);rejected(()->TextDocumentSave.save(f.jobs,j.token,f.target(f.root.resolve("never.txt"),false)));check(f.opens==0,"cancel does not open target");
            Fixture c=new Fixture(root,"changed-source");SaveJobStore.Job cj=c.chosen();Files.writeString(c.source.toPath(),"changed");rejected(()->TextDocumentSave.save(c.jobs,cj.token,c.target(c.root.resolve("never.txt"),false)));check(c.opens==0,"changed source rejected before target");
        });
        test("live writer prevents duplicate claim and reselect without disrupting the first writer",()->{
            Fixture f=new Fixture(root,"live");SaveJobStore.Job j=f.chosen();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);List<Throwable> errors=new ArrayList<>();Path target=f.root.resolve("first.txt");
            Thread first=new Thread(()->{try{TextDocumentSave.save(f.jobs,j.token,()->{entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IOException("timeout");return f.target(target,false).open();});}catch(Throwable e){errors.add(e);}});first.start();check(entered.await(5,TimeUnit.SECONDS),"writer owns claim");
            rejected(()->TextDocumentSave.save(f.jobs,j.token,f.target(f.root.resolve("duplicate.txt"),false)));rejected(()->f.jobs.reselectText(j.token));check(f.jobs.read().phase==SaveJobStore.WRITING,"failed duplicate does not mark live writer uncertain");release.countDown();first.join(6000);check(!first.isAlive()&&errors.isEmpty()&&f.jobs.read().phase==SaveJobStore.DONE,"first writer completes once");
        });
        test("failure holds writer ownership through target close and cannot resurrect confirmed cancellation",()->{
            Fixture f=new Fixture(root,"failure-ownership");SaveJobStore.Job j=f.chosen();boolean[] held={false};
            rejected(()->TextDocumentSave.save(f.jobs,j.token,()->new TextDocumentSave.Handle(){public boolean regular(){return true;}public long bytes(){return 0;}public OutputStream output(){return new OutputStream(){public void write(int b)throws IOException{throw new IOException("synthetic failure");}};}public void close()throws Exception{try{f.jobs.reselectText(j.token);throw new AssertionError("reselect while writer owns claim");}catch(IOException expected){held[0]=true;}}}));
            check(held[0]&&f.jobs.read().phase==SaveJobStore.NEEDS_RESELECT,"live writer covers failure finalization");f.jobs.reselectText(j.token);f.jobs.uncertain(j.token);check(f.jobs.read().phase==SaveJobStore.CANCELLED,"late uncertain cannot revive terminal cancellation");check(!f.chosen().token.equals(j.token),"new save can start after confirmed reselect");
        });
        test("evicted failed text cache can be explicitly closed without deleting or rewriting prior files",()->{
            Fixture f=new Fixture(root,"evicted-cache");SaveJobStore.Job old=f.chosen();Path partial=f.root.resolve("old-target.txt");rejected(()->TextDocumentSave.save(f.jobs,old.token,f.target(partial,true)));byte[] prior=Files.readAllBytes(partial);
            Path kept=f.root.resolve("cache-eviction-preserved.txt");Files.move(f.source.toPath(),kept);rejected(()->f.jobs.reselectText(old.token));
            f.jobs.closeTextRequest(old.token);check(f.jobs.read().phase==SaveJobStore.CANCELLED&&Files.isRegularFile(kept)&&Arrays.equals(prior,Files.readAllBytes(partial)),"explicit end does not need missing source or touch target");
            Files.writeString(f.source.toPath(),"new synthetic snapshot");SaveJobStore.Job fresh=f.chosen();check(!fresh.token.equals(old.token),"fresh generation no longer blocked");rejected(()->f.jobs.closeTextRequest(old.token));check(f.jobs.read().token.equals(fresh.token),"old confirmation cannot close newer receipt");
        });
        System.out.println("SCENARIOS "+scenarios+" ASSERTIONS "+checks+" FAILED 0; actual text/Models/mutations/receipt, real synthetic files; JSON and Android persistence seams; no SAF/device");
    }
}
class Store {static final Object FILE_LOCK=new Object();static void requireCertain(Models.Document d)throws ManifestFence.Uncertain{if(d.manifestUncertain)throw new ManifestFence.Uncertain("synthetic uncertain");}}
class OcrEngine {/* OCR_KEY */}
