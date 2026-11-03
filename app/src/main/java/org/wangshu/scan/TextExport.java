package org.wangshu.scan;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Plain UTF-8 snapshot only. Never changes page data or the searchable PDF text layer. */
final class TextExport {
    static final long MAX_BYTES=32L*1024*1024;
    enum Source { SAVED_DOCUMENT, SAVED_PAGE, DRAFT_PAGE }
    static final class Context {
        final Models.Document owner;final Object session;final Models.Page draft;final int screen;
        final String version,edited;final List<String> order;
        Context(Models.Document owner,Object session,Models.Page draft,int screen)throws Exception{this.owner=owner;this.session=session;this.draft=draft;this.screen=screen;version=owner==null?"":owner.savedSnapshot;order=owner==null?Collections.emptyList():order(owner);edited=screen==2&&draft!=null?draft.json().toString():"";}
        boolean current(Models.Document live,Object liveSession,Models.Page liveDraft,int liveScreen){
            if(owner!=live)return false;
            if(live==null)return screen==liveScreen&&session==liveSession&&draft==liveDraft;
            if(live.archived||live.manifestUncertain||screen!=liveScreen||!version.equals(live.savedSnapshot)||!order.equals(order(live)))return false;
            try{return screen!=2||session==liveSession&&draft==liveDraft&&liveDraft!=null&&edited.equals(liveDraft.json().toString());}catch(Exception e){return false;}
        }
    }
    static final class Request {
        final Models.Document owner;final Object session;final Models.Page editorDraft;
        final Source source;final String document,version,page,baseline,edited;final List<String> order;final int screen;
        final Models.Page frozenDraft;
        Request(Models.Document owner,Object session,Models.Page draft,Models.Page original,String baseline,Source source,int screen)throws Exception{
            if(source==null||owner==null||owner.manifestUncertain||owner.archived||!(screen==1||screen==2)||owner.savedSnapshot.isEmpty())throw new IOException("文稿状态尚未就绪，未导出文字");
            this.owner=owner;this.session=session;editorDraft=draft;this.source=source;this.screen=screen;
            document=owner.id;version=owner.savedSnapshot;order=order(owner);this.baseline=baseline;
            if(order.isEmpty()||new HashSet<>(order).size()!=order.size())throw new IOException("没有可导出的活动页或页序无效");
            page=screen==2&&original!=null?original.id:"";
            if(screen==2&&(session==null||draft==null||!page.equals(draft.id)||!order.contains(page)))throw new IOException("编辑页面身份已变化");
            if(source!=Source.SAVED_DOCUMENT&&screen!=2)throw new IOException("请先打开要导出的页面");
            frozenDraft=source==Source.DRAFT_PAGE?draft.copy():null;
            edited=frozenDraft==null?"":frozenDraft.json().toString();
        }
        boolean current(Models.Document live,Object liveSession,Models.Page liveDraft,int liveScreen){
            if(live!=owner||live==null||live.manifestUncertain||live.archived||liveScreen!=screen||!version.equals(live.savedSnapshot)||!order.equals(order(live)))return false;
            if(screen==2&&(session!=liveSession||editorDraft!=liveDraft||liveDraft==null||!page.equals(liveDraft.id)))return false;
            try{return source!=Source.DRAFT_PAGE||edited.equals(liveDraft.json().toString());}catch(Exception e){return false;}
        }
        Snapshot snapshot(Models.Document saved)throws Exception{
            if(saved==null||!document.equals(saved.id)||!order.equals(order(saved)))throw new IOException("文稿或页序已变化，未导出旧文字");
            ArrayList<Page> pages=new ArrayList<>();ArrayList<Models.Page> active=saved.active();
            for(int i=0;i<active.size();i++){
                Models.Page p=active.get(i);if(source!=Source.SAVED_DOCUMENT&&!page.equals(p.id))continue;
                if(source==Source.DRAFT_PAGE){
                    if(baseline==null||!baseline.equals(p.json().toString())||!p.id.equals(frozenDraft.id)||!p.file.equals(frozenDraft.file)||!p.sha256.equals(frozenDraft.sha256))throw new IOException("当前草稿的已保存基线或原图身份已变化；完整草稿保留");
                    p=frozenDraft;
                }
                pages.add(new Page(i+1,active.size(),p.ocrText,p.ocrOrigin,p.ocrKey.isEmpty()?"无识别图像基线":p.ocrKey.equals(OcrEngine.key(p))?"与快照图像参数匹配（不代表已校对）":"旧识别 / 图像参数已变化，仅供参考",p.cropConfirmed(),source!=Source.DRAFT_PAGE));
            }
            if(pages.isEmpty())throw new IOException("所选页面已不在文稿中");
            return new Snapshot(saved.title,source,pages,System.currentTimeMillis());
        }
    }
    static List<String> order(Models.Document doc){ArrayList<String> out=new ArrayList<>();for(Models.Page p:doc.active())out.add(p.id);return out;}
    static final class Page {
        final int number,total;final String text,origin,imageState;final boolean reviewedCrop,saved;
        Page(int number,int total,String text,String origin,String imageState,boolean reviewedCrop,boolean saved){this.number=number;this.total=total;this.text=text;this.origin=origin;this.imageState=imageState;this.reviewedCrop=reviewedCrop;this.saved=saved;}
    }
    static final class Snapshot {
        final String title;final Source source;final List<Page> pages;final long created;
        Snapshot(String title,Source source,List<Page> pages,long created){this.title=title;this.source=source;this.pages=Collections.unmodifiableList(new ArrayList<>(pages));this.created=created;}
        String description(){return source==Source.SAVED_DOCUMENT?"整本文稿 · 已保存文字":source==Source.SAVED_PAGE?"当前页 · 已保存文字":"当前页 · 未保存草稿副本（不会保存到文稿）";}
    }
    static final class Result {
        final File file;final String digest;final long bytes;final Snapshot snapshot;
        Result(File file,String digest,long bytes,Snapshot snapshot){this.file=file;this.digest=digest;this.bytes=bytes;this.snapshot=snapshot;}
    }
    static String fileTitle(String value){StringBuilder out=new StringBuilder();if(value!=null)value.codePoints().limit(100).forEach(c->out.appendCodePoint(Character.isISOControl(c)||"/\\:*?\"<>|".indexOf(c)>=0?'_':c));return (out.length()==0?"扫描文稿":out.toString())+"-文字副本.txt";}
    static String origin(String value){return "machine".equals(value)?"自动识别（未标记为人工校对）":"manual".equals(value)?"人工编辑过（不代表已校对无误）":"来源未记录（旧文字，不能确认是否人工编辑）";}
    static String heading(String value){if(value==null)return "";return value.replace("\\","\\\\").replace("\r","\\r").replace("\n","\\n").replace("\t","\\t").replace("\0","\\0");}
    static void write(Snapshot snapshot,OutputStream out)throws IOException{
        if(snapshot==null||snapshot.pages.isEmpty())throw new IOException("没有可导出的文字页");
        OutputStream limited=new FilterOutputStream(out){long count;
            public void write(int b)throws IOException{write(new byte[]{(byte)b},0,1);}
            public void write(byte[] b,int off,int len)throws IOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("文字导出已取消；页面与草稿保留");if(len>MAX_BYTES-count)throw new IOException("文字副本超过 32 MiB，未完成导出；完整输入仍在草稿中");out.write(b,off,len);count+=len;}
        };
        Writer writer=new OutputStreamWriter(limited,StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT));
        SimpleDateFormat time=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'",Locale.ROOT);time.setTimeZone(TimeZone.getTimeZone("UTC"));
        writer.write("望舒扫描 · UTF-8 文字快照\n文稿："+heading(snapshot.title)+"\n生成时间："+time.format(new Date(snapshot.created))+"\n来源："+snapshot.description()+"\n导出页数："+snapshot.pages.size()+"\n状态仅对应生成时的快照，不随文稿更新；人工编辑不代表逐字校对无误。\n此纯文本不会更改可搜索 PDF；PDF 文字层仍重新 OCR。公式与正文原样保留。\n");
        for(Page page:snapshot.pages){
            writer.write("\n===== 第 "+page.number+" / "+page.total+" 页 =====\n是否已保存到文稿："+(page.saved?"是（已保存页面快照）":"否（编辑草稿副本）")+"\n文字来源："+origin(page.origin)+"\n图像文字状态："+page.imageState+"\n四角："+(page.reviewedCrop?"已确认":"待核对，文字可能缺失")+"\n----- 正文开始（正文可能包含类似标记的原文）-----\n");
            if(page.text.isEmpty())writer.write("（本页无文字）");else writer.write(page.text);
            writer.write("\n----- 正文结束 -----\n");
        }
        writer.flush(); // Borrowed stream belongs to caller; strict encoder errors never become a complete file.
    }
    static Result create(File cache,Snapshot snapshot)throws Exception{
        Path dir=cache.toPath().resolve("exports");
        if(!Files.exists(dir,LinkOption.NOFOLLOW_LINKS))Files.createDirectory(dir);
        if(!Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS))throw new IOException("文字导出目录不可用，未改动已有数据");
        String name="text-"+UUID.randomUUID();Path partial=dir.resolve(name+".part"),ready=dir.resolve(name+".txt");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(FileChannel file=FileChannel.open(partial,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            OutputStream stream=new DigestOutputStream(Channels.newOutputStream(file),digest);write(snapshot,stream);file.force(true);
        }
        long bytes=Files.size(partial);String hash=hex(digest.digest());
        MessageDigest check=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(partial,LinkOption.NOFOLLOW_LINKS)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("文字导出核对已取消");check.update(b,0,n);}}
        if(bytes<=0||!hash.equals(hex(check.digest())))throw new IOException("文字副本写后校验失败，页面与候选文件保留");
        Files.move(partial,ready);return new Result(ready.toFile(),hash,bytes,snapshot);
    }
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
}
