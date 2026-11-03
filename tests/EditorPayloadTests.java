package org.wangshu.scan;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Actual byte budgets, codec, files and file-reference restore in a second host JVM. */
public final class EditorPayloadTests {
    static final String DOC="00000000-0000-0000-0000-000000000001",VERSION="a".repeat(64);
    static int assertions,scenarios;
    interface Action{void run()throws Exception;}
    static void check(boolean ok,String message){assertions++;if(!ok)throw new AssertionError(message);}
    static void reject(Action action)throws Exception{try{action.run();throw new AssertionError("expected rejection");}catch(IOException expected){assertions++;}}
    static void test(String name,Action run)throws Exception{run.run();scenarios++;System.out.println("PASS "+name);}
    static int utf8(String value){return value.getBytes(StandardCharsets.UTF_8).length;}
    static EditDraftStore.Draft record(String baseline,String edited){return new EditDraftStore.Draft(DOC,VERSION,baseline,edited,true,0);}
    static Path child(Path root,String name)throws Exception{Path path=root.resolve(name);Files.createDirectories(path);return path;}
    static Path receiptFile(Path files,String folder,EditDraftStore.Entry entry){return files.resolve(folder).resolve(DOC).resolve(entry.token+".draft");}
    static Map<String,String> envelope(EditorLifecycleState state){Map<String,String> out=new HashMap<>();state.write(out::put);return out;}
    static void properties(Path path,Map<String,String> map)throws Exception{Properties p=new Properties();p.putAll(map);try(OutputStream out=Files.newOutputStream(path)){p.store(out,"synthetic bounded references only");}}
    static EditorLifecycleState readProperties(Path path)throws Exception{Properties p=new Properties();try(InputStream in=Files.newInputStream(path)){p.load(in);}return EditorLifecycleState.read(p::getProperty);}
    public static void main(String[] args)throws Exception{
        if(args.length>1){
            EditorLifecycleState ref=readProperties(Path.of(args[1]));
            try{EditorLifecycleState.Snapshot state=ref.load(Path.of(args[0]).toFile());if(ref.memoryOnly)throw new AssertionError("memory crossed JVM");check(state.draft.edited.equals("中".repeat(1_400_000)),"new JVM reads exact large input from real file");System.out.println("FILE_RESTORE_OK");}
            catch(IOException expected){if(!ref.memoryOnly)throw expected;System.out.println("MEMORY_ONLY_LOSS_REPORTED");}
            return;
        }
        Path root=Path.of(args[0]);Files.createDirectories(root);
        test("manifest exact UTF-8 limit minus one, equal and plus one",()->{
            for(int delta:new int[]{-1,0,1}){String json="{\"x\":\""+"a".repeat(SerializedBudget.MANIFEST_BYTES-8+delta)+"\"}";
                if(delta>0)reject(()->SerializedBudget.manifest(json));else check(SerializedBudget.manifest(json).length==SerializedBudget.MANIFEST_BYTES+delta,"exact manifest bytes");}
        });
        test("Chinese emoji and JSON escapes consume actual encoded bytes without truncation",()->{
            String prefix="{\"pages\":[{\"ocrText\":\"中😀\\n\\\"\\\\\\u0001";
            String suffix="\"}]}";int padding=SerializedBudget.MANIFEST_BYTES-utf8(prefix+suffix);
            String exact=prefix+"a".repeat(padding)+suffix;
            check(SerializedBudget.manifest(exact).length==SerializedBudget.MANIFEST_BYTES,"escaped JSON exact limit");
            check(new String(SerializedBudget.manifest(exact),StandardCharsets.UTF_8).equals(exact),"literal full serializer output preserved");
            reject(()->SerializedBudget.manifest(exact+" "));
            reject(()->SerializedBudget.manifest("\ud800"));reject(()->EditDraftStore.validate(record("base","\ud800")));
        });
        test("every page and JSON punctuation count toward the document budget",()->{
            String page="{\"ocrText\":\""+"中".repeat(360_000)+"\"}";
            check(SerializedBudget.manifest("{\"pages\":["+page+"]}").length<SerializedBudget.MANIFEST_BYTES,"one page fits");
            reject(()->SerializedBudget.manifest("{\"pages\":["+page+","+page+"]}"));
        });
        test("actual recovery framing exact record limit and one byte over",()->{
            Path files=child(root,"record-boundary");EditDraftStore store=EditDraftStore.recovery(files.toFile());
            int limit=store.usage(DOC).recordLimit;String baseline="{\"ocrText\":\"中😀\\n\"}";
            int overhead=EditDraftStore.validate(record(baseline,"x"))-1;
            String edited="x".repeat(limit-overhead);EditDraftStore.Draft exact=record(baseline,edited);
            check(EditDraftStore.validate(exact)==limit,"metadata, baseline, field lengths and checksum included");
            EditDraftStore.Entry entry=store.retain(DOC,VERSION,baseline,edited,true);
            check(Files.size(receiptFile(files,"edit-recovery",entry))==limit,"actual on-disk codec matches preflight size");
            check(store.load(DOC,entry).edited.equals(edited),"exact bound readback");
            reject(()->EditDraftStore.validate(record(baseline,edited+"x")));
            reject(()->store.retain(DOC,VERSION,baseline,edited+"x",true));
            check(store.list(DOC).size()==1,"rejection did not write another record");
        });
        test("individually valid fields can exceed the complete record budget",()->{
            String half="a".repeat(2*1024*1024);
            reject(()->EditDraftStore.validate(record(half,half)));
        });
        test("old 4.2MB UTF-8 reproduction is rejected with no records or document writes",()->{
            Path files=child(root,"old-repro");String huge="中".repeat(1_400_000);check(utf8(huge)==4_200_000,"old reproduction exact bytes");
            reject(()->EditDraftStore.validate(record("base",huge)));
            reject(()->EditDraftStore.recovery(files.toFile()).retain(DOC,VERSION,"base",huge,true));
            check(EditDraftStore.recovery(files.toFile()).list(DOC).isEmpty(),"no incomplete record published");
            check(huge.length()==1_400_000,"caller input never truncated");
        });
        test("large unsavable edit has a separate durable lifecycle snapshot and bounded reference",()->{
            Path files=child(root,"lifecycle-large");String huge="中".repeat(1_400_000);
            EditorLifecycleState state=EditorLifecycleState.capture(files.toFile(),new EditorLifecycleState.Snapshot(record("base",huge),false));
            Map<String,String> values=envelope(state);check(values.size()==6,"only six fixed-shape reference fields");
            int bytes=0;for(var item:values.entrySet()){bytes+=utf8(item.getKey())+utf8(item.getValue());check(!item.getValue().contains("中"),"no raw input in reference");}
            check(bytes<512,"app reference policy is bounded metadata, not a claimed Binder limit");
            check(EditorLifecycleState.read(values::get).load(files.toFile()).draft.edited.equals(huge),"full input readback");
            Path meta=files.resolve("reference.properties");properties(meta,values);
            Process child=new ProcessBuilder(System.getProperty("java.home")+"/bin/java","-Xmx256m","-cp",System.getProperty("java.class.path"),EditorPayloadTests.class.getName(),files.toString(),meta.toString()).redirectErrorStream(true).start();
            String output=new String(child.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            check(child.waitFor()==0&&output.contains("FILE_RESTORE_OK"),"real second host process restores from receipt, not static memory");
            EditDraftStore.Entry receipt=new EditDraftStore.Entry(state.token,state.digest,state.created,null);Path path=receiptFile(files,"edit-lifecycle",receipt);
            byte[] original=Files.readAllBytes(path),corrupt=original.clone();corrupt[corrupt.length-1]^=1;Files.write(path,corrupt);
            reject(()->state.load(files.toFile()));check(Arrays.equals(corrupt,Files.readAllBytes(path)),"corrupt snapshot rejected and retained");
        });
        test("lifecycle write failure keeps exact in-process input and reports cross-process limit",()->{
            Path files=child(root,"lifecycle-fail");Files.writeString(files.resolve("edit-lifecycle"),"occupied synthetic file");
            EditorLifecycleState.Snapshot snapshot=new EditorLifecycleState.Snapshot(record("base","中😀\\n"),false);
            reject(()->EditorLifecycleState.capture(files.toFile(),snapshot));
            EditorLifecycleState memory=EditorLifecycleState.keepInProcess(snapshot);Map<String,String> values=envelope(memory);
            check(EditorLifecycleState.read(values::get).load(files.toFile()).draft.edited.equals(snapshot.draft.edited),"same-process input retained exactly");
            Path meta=files.resolve("memory.properties");properties(meta,values);
            Process child=new ProcessBuilder(System.getProperty("java.home")+"/bin/java","-cp",System.getProperty("java.class.path"),EditorPayloadTests.class.getName(),files.toString(),meta.toString()).redirectErrorStream(true).start();
            String output=new String(child.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            check(child.waitFor()==0&&output.contains("MEMORY_ONLY_LOSS_REPORTED"),"different process reports unavailable input explicitly");
            check(Files.readString(files.resolve("edit-lifecycle")).equals("occupied synthetic file"),"failed checkpoint preserves existing data");EditorLifecycleState.release(memory);
        });
        test("fallback registry has an explicit process-wide slot bound and does not evict held input",()->{
            List<EditorLifecycleState> refs=new ArrayList<>();EditorLifecycleState.Snapshot state=new EditorLifecycleState.Snapshot(record("base","完整中文😀"),false);
            for(int i=0;i<8;i++)refs.add(EditorLifecycleState.keepInProcess(state));
            reject(()->EditorLifecycleState.keepInProcess(state));
            for(EditorLifecycleState ref:refs)check(ref.load(root.toFile()).draft.edited.equals(state.draft.edited),"held slot not evicted by failure");
            for(EditorLifecycleState ref:refs)EditorLifecycleState.release(ref);
        });
        System.out.println("SCENARIOS "+scenarios+" ASSERTIONS "+assertions+" FAILED 0; real codec/files/host JVM processes; no Android Bundle/Store/UI");
    }
}
