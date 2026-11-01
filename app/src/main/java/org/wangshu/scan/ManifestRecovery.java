package org.wangshu.scan;

import org.json.JSONObject;
import org.json.JSONArray;
import java.math.BigDecimal;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

/** Worker-only, explicit-choice, copy-out recovery. Never changes a source candidate. */
final class ManifestRecovery {
    private static final long IMAGE_LIMIT=100L*1024*1024,TOTAL_LIMIT=512L*1024*1024,SAFETY_MARGIN=16L*1024*1024;
    static final class Incomplete extends IOException {
        private static final long serialVersionUID=1L;final String copyId;
        Incomplete(String id,Throwable cause){super("恢复未完成；原文稿未更改，已创建的副本文件保留（编号 "+id+"）。请先检查，不要自动重试。",cause);copyId=id;}
    }
    static final class Candidate {
        final String kind,title,token;final int pageCount,activePageCount,removedCount;final boolean selectable;final String message;
        Candidate(String k,String t,String h,int n,int active,boolean valid,String m){kind=k;title=t;token=h;pageCount=n;activePageCount=active;removedCount=n-active;selectable=valid;message=m;}
    }
    static final class Limit extends IOException {private static final long serialVersionUID=1L;Limit(String message){super(message);}}
    private static final String[] NAMES={"manifest.json","manifest.json.new","manifest.json.bak"};
    private static void interrupted()throws InterruptedIOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("恢复已中断；原文稿和已复制的文件均保留。");}
    private static boolean exists(File file)throws IOException{
        try{Files.readAttributes(file.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);return true;}
        catch(NoSuchFileException absent){return false;}catch(SecurityException e){throw new IOException("无法检查候选文件",e);}
    }
    private static byte[] readCandidate(File file)throws IOException{
        if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("候选不是普通文件");
        try(InputStream in=Files.newInputStream(file.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){return Store.readLimited(in,2*1024*1024);}
    }
    private static String hash(byte[] data)throws Exception{return Store.hex(MessageDigest.getInstance("SHA-256").digest(data));}
    private static void exactId(String id)throws IOException{
        try{if(id==null||!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}
        catch(IllegalArgumentException invalid){throw new IOException("标识无效",invalid);}
    }
    private static File sourceDirectory(Store store,String id)throws IOException{
        exactId(id);File directory=new File(store.root,id);
        if(!Files.isDirectory(directory.toPath(),LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(directory.toPath()))throw new IOException("原文稿目录不可读取");return directory;
    }
    private static File candidatePath(File dir,String kind)throws IOException{
        for(String name:NAMES)if(name.equals(kind))return new File(dir,name);throw new IOException("未知候选类型");
    }
    private static boolean sameJson(Object a,Object b)throws Exception{
        if(a instanceof JSONObject&&b instanceof JSONObject){JSONObject x=(JSONObject)a,y=(JSONObject)b;HashSet<String> keys=new HashSet<>();for(Iterator<String> i=x.keys();i.hasNext();)keys.add(i.next());HashSet<String> other=new HashSet<>();for(Iterator<String> i=y.keys();i.hasNext();)other.add(i.next());if(!keys.equals(other))return false;for(String key:keys)if(!sameJson(x.get(key),y.get(key)))return false;return true;}
        if(a instanceof JSONArray&&b instanceof JSONArray){JSONArray x=(JSONArray)a,y=(JSONArray)b;if(x.length()!=y.length())return false;for(int i=0;i<x.length();i++)if(!sameJson(x.get(i),y.get(i)))return false;return true;}
        if(a instanceof Number&&b instanceof Number){try{return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()))==0;}catch(NumberFormatException invalid){return false;}}
        return Objects.equals(a,b);
    }
    private static Models.Document parse(byte[] raw,String id)throws Exception{
        StrictJsonValidator.validate(raw);
        java.nio.charset.CharsetDecoder decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        JSONObject original=new JSONObject(decoder.decode(ByteBuffer.wrap(raw)).toString());
        // Check the array limit before constructing a potentially large model.
        JSONArray originalPages=original.getJSONArray("pages");
        if(originalPages.length()>1000)throw new Limit("候选超过当前恢复上限 1000 页；源文件保留，暂无法使用此恢复入口。");
        // Version-1 documents predating OCR legitimately omit these three fields.
        // Add only their exact empty defaults in this temporary object; the source
        // bytes remain untouched and all other unknown or normalized data is rejected.
        for(int i=0;i<originalPages.length();i++){JSONObject page=originalPages.getJSONObject(i);for(String key:new String[]{"ocrText","ocrKey","ocrLanguage"})if(!page.has(key))page.put(key,"");}
        Models.Document doc=Models.Document.from(original);
        // Reject normalization, ignored/unknown fields or data loss instead of
        // silently treating a partial parse as a faithful recovery candidate.
        if(!sameJson(original,doc.json()))throw new IOException("候选含不支持或会被改变的参数");
        if(!id.equals(doc.id))throw new IOException("候选文稿标识不匹配");
        HashSet<String> names=new HashSet<>();for(Models.Page p:doc.pages){exactId(p.id);String expected="originals/"+p.id+".image";
            if(!expected.equals(p.file)||!names.add(p.file)||p.sha256==null||!p.sha256.matches("[a-f0-9]{64}"))throw new IOException("候选页面路径或校验值无效");}
        return doc;
    }
    /** This only validates metadata; image bytes are checked after explicit selection. */
    static ArrayList<Candidate> inspect(Store store,String id)throws Exception{
        synchronized(Store.FILE_LOCK){File dir=sourceDirectory(store,id);ArrayList<Candidate> result=new ArrayList<>();
            for(String kind:NAMES){interrupted();File f=candidatePath(dir,kind);if(!exists(f))continue;
                try{byte[] raw=readCandidate(f);Models.Document doc=parse(raw,id);result.add(new Candidate(kind,DocumentMutations.title(doc.title),hash(raw),doc.pages.size(),doc.active().size(),true,"候选记录可读取；选择后才核验原图并复制"));}
                catch(Exception invalid){if(invalid instanceof InterruptedIOException)throw invalid;result.add(new Candidate(kind,"", "",0,0,false,invalid instanceof Limit?invalid.getMessage():"候选无法安全解析，原文件保留"));}
            }return result;}
    }
    private static File original(File sourceDir,Models.Page p)throws IOException{
        File originals=new File(sourceDir,"originals");if(!Files.isDirectory(originals.toPath(),LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(originals.toPath()))throw new IOException("原图目录不可安全读取");
        File f=new File(sourceDir,p.file);if(!f.getCanonicalFile().getParentFile().equals(originals.getCanonicalFile())||!Files.isRegularFile(f.toPath(),LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(f.toPath()))throw new IOException("原图缺失或路径无效");return f;
    }
    interface CopyHook {void afterOpen()throws Exception;void beforeReadback()throws Exception;}
    private static final CopyHook NO_HOOK=new CopyHook(){public void afterOpen(){}public void beforeReadback(){}};
    /** CREATE_NEW and NOFOLLOW; never deletes partial target on failure. */
    static long copyVerified(File source,File target,String expected,long already)throws Exception{return copyVerified(source,target,expected,already,NO_HOOK);}
    // Controlled hooks enable deterministic host fault tests; production uses NO_HOOK.
    static long copyVerified(File source,File target,String expected,long already,CopyHook hook)throws Exception{
        interrupted();if(!expected.matches("[a-f0-9]{64}"))throw new IOException("原图校验值无效");
        BasicFileAttributes attrs=Files.readAttributes(source.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attrs.isRegularFile()||attrs.size()<=0||attrs.size()>IMAGE_LIMIT||already> TOTAL_LIMIT-attrs.size())throw new Limit("单张原图超过 100 MiB、总计超过 512 MiB，或大小无效；源文件保留，暂无法使用此恢复入口。");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0;
        try(InputStream in=Files.newInputStream(source.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);
            FileChannel out=FileChannel.open(target.toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            hook.afterOpen();byte[] data=new byte[65536];int n;while((n=in.read(data))!=-1){interrupted();count+=n;if(count>attrs.size()||count>IMAGE_LIMIT)throw new IOException("复制期间原图发生变化");digest.update(data,0,n);ByteBuffer buffer=ByteBuffer.wrap(data,0,n);while(buffer.hasRemaining()){interrupted();out.write(buffer);}}
            out.force(true);
        }
        if(count!=attrs.size()||!Store.hex(digest.digest()).equals(expected))throw new IOException("原图校验不符；原文件和副本保留");
        // Independently reopen the newly created target without following symlinks.
        // Neither a short nor altered on-disk copy may be published as recovered.
        hook.beforeReadback();MessageDigest readback=MessageDigest.getInstance("SHA-256");long checked=0;
        try(InputStream in=Files.newInputStream(target.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            byte[] data=new byte[65536];int n;while((n=in.read(data))!=-1){interrupted();checked+=n;if(checked>count)throw new IOException("恢复副本长度变化");readback.update(data,0,n);}
        }
        if(checked!=count||!Store.hex(readback.digest()).equals(expected))throw new IOException("恢复副本校验不符；原文件和副本保留");return count;
    }
    /** Explicit user selection; creates a new document and keeps every source and partial copy. */
    static Models.Document recover(Store store,String id,Candidate choice)throws Exception{
        synchronized(Store.FILE_LOCK){
            if(choice==null||!choice.selectable)throw new IOException("请选择可读取的候选记录");File dir=sourceDirectory(store,id);File source=candidatePath(dir,choice.kind);
            byte[] raw=readCandidate(source);if(!hash(raw).equals(choice.token))throw new IOException("候选已变化，请重新检查后选择");Models.Document restored=parse(raw,id);
            long total=0;ArrayList<File> originals=new ArrayList<>();for(Models.Page p:restored.pages){interrupted();File f=original(dir,p);BasicFileAttributes attrs=Files.readAttributes(f.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(!attrs.isRegularFile()||attrs.size()<=0||attrs.size()>IMAGE_LIMIT||total>TOTAL_LIMIT-attrs.size())throw new Limit("单张原图超过 100 MiB、总计超过 512 MiB，或大小无效；源文件保留，暂无法使用此恢复入口。");total+=attrs.size();originals.add(f);}
            long usable=store.root.getUsableSpace();if(usable<=0||usable<total+SAFETY_MARGIN)throw new IOException("空间不足或无法确认空间；未创建恢复副本");
            interrupted();String newId=UUID.randomUUID().toString();File destination=new File(store.root,newId);File images=new File(destination,"originals");boolean created=false;
            try{
                Files.createDirectory(destination.toPath());created=true;Files.createDirectory(images.toPath());
                long copied=0;for(int i=0;i<restored.pages.size();i++){Models.Page p=restored.pages.get(i);File copy=new File(images,p.id+".image");copied+=copyVerified(originals.get(i),copy,p.sha256,copied);
                    CameraInbox.requireCompleteIfJpeg(copy);android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();bounds.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeFile(copy.getPath(),bounds);
                    if(bounds.outWidth<2||bounds.outHeight<2||(long)bounds.outWidth*bounds.outHeight>150_000_000L)throw new IOException("恢复副本无法安全解码；原图及副本保留");}
                interrupted();if(!hash(readCandidate(source)).equals(choice.token))throw new IOException("候选在复制期间变化，未提交恢复副本");
                restored.id=newId;String title=DocumentMutations.title(restored.title);int max=100-"（恢复副本）".codePointCount(0,"（恢复副本）".length());if(title.codePointCount(0,title.length())>max)title=title.substring(0,title.offsetByCodePoints(0,max));restored.title=title+"（恢复副本）";restored.archived=false;restored.savedSnapshot="";restored.manifestUncertain=false;
                store.save(restored);return restored;
            }catch(ManifestFence.Uncertain uncertain){throw new ManifestFence.Uncertain("恢复副本（编号 "+newId+"）发布结果未知；原文稿和副本文件保留，请勿重试，先检查。",uncertain);}
            catch(Exception failure){if(created)throw new Incomplete(newId,failure);throw new IOException("未创建恢复副本；原文稿及已有目录未更改。",failure);}
            catch(OutOfMemoryError|LinkageError failure){if(created)throw new Incomplete(newId,failure);throw failure;}
        }
    }
}
