package org.wangshu.scan;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

/** One private save receipt. Call only on a worker; never replays an output URI. */
final class SaveJobStore {
    static final int REQUESTED=0,PICKER=1,CHOSEN=2,WRITING=3,DONE=4,CANCELLED=5,NEEDS_RESELECT=6;
    private static final Object LOCK=new Object();
    private static final Map<String,Claim> LIVE_WRITERS=new HashMap<>();
    interface Persistence {byte[] read()throws IOException;void write(byte[] bytes)throws IOException;}
    interface SourceHandle extends Closeable {InputStream stream();void rewind()throws IOException;}
    interface Sources {boolean isFile(File f);long length(File f);SourceHandle open(File f)throws IOException;}
    static final class Job {
        final String token,path,sha,mime,title;final long bytes;final int phase;
        Job(String token,String path,long bytes,String sha,String mime,String title,int phase){this.token=token;this.path=path;this.bytes=bytes;this.sha=sha;this.mime=mime;this.title=title;this.phase=phase;}
        Job phase(int value){return new Job(token,path,bytes,sha,mime,title,value);}
    }
    static final class Claim implements Closeable {
        final Job job;final InputStream input;private final SourceHandle handle;private final String ownerKey;private boolean closed;
        Claim(Job j,SourceHandle h,String key)throws IOException{
            job=j;handle=h;ownerKey=key;MessageDigest digest=digest();
            input=new FilterInputStream(h.stream()){
                long count;boolean verified;
                @Override public int read()throws IOException{byte[] one=new byte[1];int n=read(one,0,1);return n<0?-1:one[0]&255;}
                @Override public int read(byte[] b,int off,int length)throws IOException{
                    int n=in.read(b,off,length);if(n>0){count+=n;if(count>j.bytes)throw new IOException("导出源已变化，目标可能不完整，请重新生成并另存");digest.update(b,off,n);}
                    if(n<0&&!verified){verified=true;if(count!=j.bytes||!hex(digest.digest()).equals(j.sha))throw new IOException("导出源校验不符，目标可能不完整，请重新生成并另存");}return n;
                }
                @Override public void close(){/* Claim owns the source handle. */}
            };
        }
        @Override public void close()throws IOException{synchronized(LOCK){if(closed)return;closed=true;}try{handle.close();}finally{synchronized(LOCK){LIVE_WRITERS.remove(ownerKey,this);}}}
    }
    private final File cache,files;private final Persistence persistence;private final Sources sources;
    SaveJobStore(Context context){this(context.getCacheDir(),context.getFilesDir(),new Disk(new File(context.getFilesDir(),"single-save-job.bin")),new DiskSources());}
    SaveJobStore(File cache,File files,Persistence persistence,Sources sources){this.cache=cache;this.files=files;this.persistence=persistence;this.sources=sources;}
    static boolean validToken(String token){try{return token!=null&&UUID.fromString(token).toString().equals(token);}catch(IllegalArgumentException e){return false;}}
    static boolean validMime(String mime){return Arrays.asList("application/pdf","application/zip","image/png","image/jpeg","image/webp","image/heic","image/gif","application/octet-stream").contains(mime);}
    private static boolean rootMatches(File parent,File allowed)throws IOException{
        File absolute=parent.getAbsoluteFile();return (absolute.equals(allowed.getAbsoluteFile())||absolute.equals(allowed.getCanonicalFile()))&&parent.getCanonicalFile().equals(allowed.getCanonicalFile());
    }
    private static String mimeFor(String name){
        if(name.endsWith(".pdf"))return "application/pdf";if(name.endsWith(".zip"))return "application/zip";if(name.endsWith(".png"))return "image/png";if(name.endsWith(".jpg"))return "image/jpeg";if(name.endsWith(".webp"))return "image/webp";if(name.endsWith(".heic"))return "image/heic";if(name.endsWith(".gif"))return "image/gif";return "application/octet-stream";
    }
    File ownedPath(File candidate)throws IOException{
        File full=candidate.getAbsoluteFile(),parent=full.getParentFile();String name=full.getName();
        if(parent==null||!name.matches("[a-zA-Z0-9_.-]+")||name.contains(".."))throw new IOException("导出源路径不属于本应用，未打开保存位置");
        File canonical=full.getCanonicalFile();
        // Android's trusted data-root alias (/data/user/0 versus /data/data) is
        // allowed; a symlink leaf or arbitrary outside alias is not.
        if(!canonical.equals(new File(parent.getCanonicalFile(),name)))throw new IOException("导出源不能是符号链接，未打开保存位置");
        boolean export=rootMatches(parent,new File(cache,"exports"))&&name.matches(".+\\.(pdf|zip|png|jpg|webp|heic|gif|bin)");
        boolean camera=(rootMatches(parent,new File(files,"camera"))||rootMatches(parent,new File(cache,"camera")))&&name.startsWith("capture-")&&name.endsWith(".jpg")&&validToken(name.substring(8,name.length()-4));
        if(!export&&!camera)throw new IOException("导出源不是已完成的本地导出或拍照原图，未打开保存位置");return canonical;
    }
    File recoverSource(String token,String legacyPath)throws IOException{
        synchronized(LOCK){File f;if(token!=null){Job j=require(token);f=new File(j.path);}else{if(legacyPath==null)throw new IOException("旧保存请求没有可靠源身份，请重新生成并另存");f=new File(legacyPath);}
            f=ownedPath(f);if(!sources.isFile(f)||sources.length(f)<=0)throw new IOException("导出缓存已不可用，请重新生成；文稿和原图保留");return f;}
    }
    Job begin(File source,String mime,String title)throws IOException{
        synchronized(LOCK){if(LIVE_WRITERS.containsKey(files.getCanonicalPath()))throw new IOException("另一保存仍在结束，未打开新位置；请稍后重试");Job prior=read();if(prior!=null&&prior.phase!=DONE&&prior.phase!=CANCELLED)throw new IOException("先前保存请求仍未确认；旧记录和导出源保留，未创建新请求");File f=ownedPath(source);if(!validMime(mime)||!mime.equals(mimeFor(f.getName()))||title==null||title.length()>300||title.indexOf('\0')>=0)throw new IOException("保存请求格式无效");
            long bytes=sources.length(f);if(!sources.isFile(f)||bytes<=0)throw new IOException("导出源不可用，请重新生成；未打开保存位置");String sha;
            try(SourceHandle h=sources.open(f)){sha=hash(h.stream(),bytes);}Job j=new Job(UUID.randomUUID().toString(),f.getPath(),bytes,sha,mime,title,REQUESTED);persist(j);return j;}
    }
    Job picker(String token)throws IOException{return move(token,REQUESTED,PICKER);}
    Job chosen(String token)throws IOException{return move(token,PICKER,CHOSEN);}
    private Job move(String token,int from,int to)throws IOException{synchronized(LOCK){Job j=require(token);if(j.phase!=from)throw new IOException("保存请求阶段已变化，请重新选择位置");j=j.phase(to);persist(j);return j;}}
    Claim claim(String token)throws IOException{
        synchronized(LOCK){String key=files.getCanonicalPath();if(LIVE_WRITERS.containsKey(key))throw new IOException("另一保存仍在结束，未打开新位置");Job j=require(token);if(j.phase!=CHOSEN)throw new IOException("此保存请求已处理或未确认；未再次打开旧位置，请重新选择");File f=ownedPath(new File(j.path));
            if(!sources.isFile(f)||sources.length(f)!=j.bytes)throw new IOException("导出源已变化或不可用，未打开保存位置");SourceHandle h=sources.open(f);
            try{if(!hash(h.stream(),j.bytes).equals(j.sha))throw new IOException("导出源校验不符，未打开保存位置");h.rewind();Job writing=j.phase(WRITING);persist(writing);if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("保存已中断，未打开位置");Claim claim=new Claim(writing,h,key);LIVE_WRITERS.put(key,claim);return claim;}catch(IOException|RuntimeException e){try{h.close();}catch(IOException close){e.addSuppressed(close);}throw e;}
        }
    }
    void done(String token)throws IOException{move(token,WRITING,DONE);}
    void cancel(String token)throws IOException{synchronized(LOCK){Job j=require(token);if(j.phase==DONE||j.phase==CANCELLED||j.phase==NEEDS_RESELECT)return;persist(j.phase(j.phase==WRITING?NEEDS_RESELECT:CANCELLED));}}
    void uncertain(String token)throws IOException{synchronized(LOCK){Job j=require(token);if(j.phase!=DONE)persist(j.phase(NEEDS_RESELECT));}}
    private Job require(String token)throws IOException{if(!validToken(token))throw new IOException("保存请求身份无效，未打开保存位置");Job j=read();if(j==null||!j.token.equals(token))throw new IOException("较早保存请求已过期，未打开旧位置；导出源仍保留");return j;}
    Job read()throws IOException{synchronized(LOCK){
        byte[] bytes=persistence.read();if(bytes==null)return null;if(bytes.length>32768)throw new IOException("保存记录无法读取，请重新选择位置");
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes))){if(in.readInt()!=0x53535631)throw new IOException("保存记录版本无效");Job j=new Job(in.readUTF(),in.readUTF(),in.readLong(),in.readUTF(),in.readUTF(),in.readUTF(),in.readInt());
            if(in.read()!=-1||!validToken(j.token)||j.bytes<=0||!j.sha.matches("[a-f0-9]{64}")||!validMime(j.mime)||!j.mime.equals(mimeFor(new File(j.path).getName()))||j.title.length()>300||j.title.indexOf('\0')>=0||j.phase<REQUESTED||j.phase>NEEDS_RESELECT)throw new IOException("保存记录无效，请重新选择位置");ownedPath(new File(j.path));return j;}
    }}
    private void persist(Job j)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream out=new DataOutputStream(bytes)){out.writeInt(0x53535631);out.writeUTF(j.token);out.writeUTF(j.path);out.writeLong(j.bytes);out.writeUTF(j.sha);out.writeUTF(j.mime);out.writeUTF(j.title);out.writeInt(j.phase);}byte[] encoded=bytes.toByteArray();if(encoded.length>32768)throw new IOException("保存记录过大");persistence.write(encoded);
        if(!Arrays.equals(encoded,persistence.read()))throw new IOException("保存状态未能确认，未授权下一步操作");
    }
    private static MessageDigest digest()throws IOException{try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IOException(e);}}
    private static String hash(InputStream in,long expected)throws IOException{MessageDigest digest=digest();byte[] b=new byte[65536];long total=0;int n;while((n=in.read(b))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("保存准备已中断，未打开位置");total+=n;if(total>expected)throw new IOException("导出源在准备时发生变化");digest.update(b,0,n);}if(total!=expected)throw new IOException("导出源长度已变化");return hex(digest.digest());}
    private static String hex(byte[] bytes){StringBuilder b=new StringBuilder();for(byte v:bytes)b.append(String.format(Locale.ROOT,"%02x",v&255));return b.toString();}
    private static final class Disk implements Persistence {
        final AtomicFile file;final File base;Disk(File f){base=f;file=new AtomicFile(f);}
        public byte[] read()throws IOException{
            // AtomicFile.openRead may itself reconcile or remove sidecars. Do not
            // call it while any candidate is unresolved, and never infer absence
            // from a FileNotFoundException or an unknown filesystem status.
            boolean present=false;File[] candidates={base,new File(base.getPath()+".bak"),new File(base.getPath()+".new")};
            for(int i=0;i<candidates.length;i++){
                try{BasicFileAttributes a=Files.readAttributes(candidates[i].toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                    if(i!=0||!a.isRegularFile()||a.isSymbolicLink())throw new IOException("保存记录存在待核对候选，未创建新请求");present=true;}
                catch(NoSuchFileException absent){/* Check each candidate before accepting absence. */}
                catch(SecurityException denied){throw new IOException("无法确认已有保存记录，未创建新请求",denied);}
            }
            if(!present)return null;
            try(InputStream in=file.openRead()){ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] row=new byte[1024];int n;while((n=in.read(row))!=-1){if(b.size()+n>32768)throw new IOException("保存记录过大");b.write(row,0,n);}return b.toByteArray();}
            catch(FileNotFoundException failure){throw new IOException("已有保存记录无法读取，未创建新请求",failure);}
        }
        public void write(byte[] bytes)throws IOException{FileOutputStream out=null;try{out=file.startWrite();out.write(bytes);out.getFD().sync();file.finishWrite(out);}catch(IOException|RuntimeException e){if(out!=null)file.failWrite(out);throw e;}}
    }
    private static final class DiskSources implements Sources {
        public boolean isFile(File f){return f.isFile();}public long length(File f){return f.length();}
        public SourceHandle open(File f)throws IOException{FileInputStream in=new FileInputStream(f);return new SourceHandle(){public InputStream stream(){return in;}public void rewind()throws IOException{in.getChannel().position(0);}public void close()throws IOException{in.close();}};}
    }
}
