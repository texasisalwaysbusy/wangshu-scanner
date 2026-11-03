package org.wangshu.scan;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** No raw page/OCR strings in new saved-state envelopes. Immutable local files are reverified. */
final class EditorLifecycleState {
    interface Read {String get(String key);}
    interface Write {void put(String key,String value);}
    static final class Snapshot {
        final EditDraftStore.Draft draft;final boolean recovery;
        Snapshot(EditDraftStore.Draft draft,boolean recovery){this.draft=draft;this.recovery=recovery;}
    }
    private static final ConcurrentHashMap<String,Snapshot> MEMORY=new ConcurrentHashMap<>();
    final String owner,token,digest;final long created;final boolean recovery,memoryOnly;
    private EditorLifecycleState(String owner,String token,String digest,long created,boolean recovery,boolean memoryOnly){
        this.owner=owner;this.token=token;this.digest=digest;this.created=created;this.recovery=recovery;this.memoryOnly=memoryOnly;
    }
    static EditorLifecycleState capture(File files,Snapshot snapshot)throws IOException {
        EditDraftStore.Draft d=snapshot.draft;
        EditDraftStore.Entry entry=EditDraftStore.lifecycle(files).snapshot(d);
        return new EditorLifecycleState(d.document,entry.token,entry.digest,entry.created,snapshot.recovery,false);
    }
    // Only on a reported checkpoint failure. Keeps exact input across same-process Activity replacement.
    // Process death cannot restore memory-only state; load reports that explicitly instead of inventing data.
    private static final int MEMORY_SLOTS=8,MEMORY_UTF8_BYTES=16*1024*1024;
    static EditorLifecycleState keepInProcess(Snapshot snapshot)throws IOException{return keepInProcess(snapshot,null);}
    static EditorLifecycleState keepInProcess(Snapshot snapshot,EditorLifecycleState replacing)throws IOException{
        synchronized(MEMORY){
            long bytes=memoryBytes(snapshot);int slots=1;
            for(var item:MEMORY.entrySet())if(replacing==null||!replacing.memoryOnly||!item.getKey().equals(replacing.token)){bytes+=memoryBytes(item.getValue());slots++;}
            if(slots>MEMORY_SLOTS||bytes>MEMORY_UTF8_BYTES)throw new IOException("内存草稿退路已达应用上限；当前输入仍在编辑器，尚未获得可恢复快照，请保存或复制后再离开");
            String token=UUID.randomUUID().toString();MEMORY.put(token,snapshot);
            if(replacing!=null&&replacing.memoryOnly)MEMORY.remove(replacing.token);
            return new EditorLifecycleState(snapshot.draft.document,token,"",0,snapshot.recovery,true);
        }
    }
    private static long memoryBytes(Snapshot value){EditDraftStore.Draft d=value.draft;long bytes=0;for(String s:new String[]{d.document,d.version,d.baseline,d.edited})if(s!=null)bytes+=s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;return bytes;}
    static void release(EditorLifecycleState state){if(state!=null&&state.memoryOnly)synchronized(MEMORY){MEMORY.remove(state.token);}}
    Snapshot load(File files)throws IOException {
        if(memoryOnly){Snapshot value=MEMORY.get(token);if(value==null||!owner.equals(value.draft.document))throw new IOException("草稿未能写入生命周期快照，且原进程已结束；未恢复未落盘输入。请保留应用数据核对。");return value;}
        EditDraftStore.Draft d=EditDraftStore.lifecycle(files).load(owner,new EditDraftStore.Entry(token,digest,created,null));
        return new Snapshot(d,recovery);
    }
    void write(Write out){
        out.put("editorState.owner",owner);out.put("editorState.token",token);out.put("editorState.digest",digest);
        out.put("editorState.created",Long.toString(created));out.put("editorState.recovery",Boolean.toString(recovery));out.put("editorState.memory",Boolean.toString(memoryOnly));
    }
    static EditorLifecycleState read(Read in){
        String owner=in.get("editorState.owner"),token=in.get("editorState.token"),digest=in.get("editorState.digest");
        if(owner==null&&token==null)return null;
        // New envelopes only contain fixed-shape identities; never accept arbitrary raw text here.
        if(owner==null||!owner.matches("[a-f0-9-]{36}")||token==null||!token.matches("[a-f0-9-]{36}")||digest==null||!(digest.isEmpty()||digest.matches("[a-f0-9]{64}")))throw new IllegalArgumentException("草稿快照引用无效");
        long created;try{created=Long.parseLong(in.get("editorState.created"));}catch(Exception e){throw new IllegalArgumentException("草稿快照时间无效",e);}
        boolean memory="true".equals(in.get("editorState.memory"));
        if(created<0||(!memory&&digest.isEmpty()))throw new IllegalArgumentException("草稿快照回执无效");
        return new EditorLifecycleState(owner,token,digest,created,"true".equals(in.get("editorState.recovery")),memory);
    }
}
