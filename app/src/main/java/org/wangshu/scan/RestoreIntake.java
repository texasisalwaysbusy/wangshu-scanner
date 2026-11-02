package org.wangshu.scan;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;

/** Read-only intake discovery; export is an explicit verified copy, never cleanup. */
final class RestoreIntake {
    static final long MAX=512L*1024*1024, RESERVE=32L*1024*1024;
    private final Path root,exports;
    RestoreIntake(Path files,Path cache){root=files.resolve("restore-intake");exports=cache.resolve("exports");}
    static final class Entry {
        final String name,issue; final long bytes; final Object rootKey,dirKey,fileKey; final FileTime modified;
        Entry(String name,String issue,long bytes,Object rootKey,Object dirKey,Object fileKey,FileTime modified){this.name=name;this.issue=issue;this.bytes=bytes;this.rootKey=rootKey;this.dirKey=dirKey;this.fileKey=fileKey;this.modified=modified;}
        boolean selectable(){return issue==null&&bytes>0;}
    }
    static final class Snapshot {final Entry entry;final String sha;Snapshot(Entry e,String sha){entry=e;this.sha=sha;}}
    private static IOException problem(String text){return new IOException(text+"；暂存及原有文稿未删除");}
    private static void checkInterrupted()throws IOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("已取消；暂存未删除");}
    private static boolean validName(String s){if(!s.startsWith("restore-"))return false;String id=s.substring(8);try{return UUID.fromString(id).toString().equals(id);}catch(IllegalArgumentException e){return false;}}
    private static Object identity(BasicFileAttributes a)throws IOException{if(a.fileKey()==null)throw problem("文件系统不提供可验证的身份，不能安全读取");return a.fileKey();}
    private static BasicFileAttributes attrs(SecureDirectoryStream<Path> dir)throws IOException{return dir.getFileAttributeView(BasicFileAttributeView.class).readAttributes();}
    private static BasicFileAttributes attrs(SecureDirectoryStream<Path> dir,Path name)throws IOException{return dir.getFileAttributeView(name,BasicFileAttributeView.class,LinkOption.NOFOLLOW_LINKS).readAttributes();}
    private static SecureDirectoryStream<Path> secure(Path path)throws IOException{
        BasicFileAttributes before=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!before.isDirectory()||before.isSymbolicLink())throw problem("暂存目录类型异常");Object key=identity(before);DirectoryStream<Path> stream=Files.newDirectoryStream(path);
        if(!(stream instanceof SecureDirectoryStream)){stream.close();throw problem("文件系统不支持安全目录句柄");}
        SecureDirectoryStream<Path> result=(SecureDirectoryStream<Path>)stream;
        try{BasicFileAttributes after=attrs(result);if(!after.isDirectory()||!key.equals(identity(after)))throw problem("目录身份已变化");return result;}
        catch(IOException|RuntimeException e){result.close();throw e;}
    }
    private static SecureDirectoryStream<Path> openStage(SecureDirectoryStream<Path> base,String name,Object expected)throws IOException{
        if(!validName(name))throw problem("暂存编号无效");Path child=Paths.get(name);BasicFileAttributes before=attrs(base,child);
        if(!before.isDirectory()||before.isSymbolicLink())throw problem("暂存目录类型异常");Object key=identity(before);if(expected!=null&&!expected.equals(key))throw problem("暂存目录身份已变化");
        SecureDirectoryStream<Path> result=base.newDirectoryStream(child,LinkOption.NOFOLLOW_LINKS);
        try{BasicFileAttributes after=attrs(result);if(!after.isDirectory()||!key.equals(identity(after)))throw problem("暂存目录身份已变化");return result;}
        catch(IOException|RuntimeException e){result.close();throw e;}
    }
    private static BasicFileAttributes raw(SecureDirectoryStream<Path> stage)throws IOException{
        BasicFileAttributes a=attrs(stage,Paths.get("received.zip"));if(!a.isRegularFile()||a.isSymbolicLink()||a.size()>MAX)throw problem("已接收文件类型或大小异常");identity(a);return a;
    }
    List<Entry> list()throws IOException{
        try(SecureDirectoryStream<Path> base=secure(root)){
            Object rootKey=identity(attrs(base));ArrayList<Entry> entries=new ArrayList<>();
            for(Path item:base){checkInterrupted();if(entries.size()>=1000)throw problem("暂存项超过 1000 项，请先人工核对");String name=item.getFileName().toString();
                if(!validName(name)){entries.add(new Entry(name,"未知名称，不可读取",0,rootKey,null,null,null));continue;}
                try(SecureDirectoryStream<Path> stage=openStage(base,name,null)){Object dirKey=identity(attrs(stage));BasicFileAttributes a=raw(stage);entries.add(new Entry(name,null,a.size(),rootKey,dirKey,identity(a),a.lastModifiedTime()));}
                catch(IOException|SecurityException failure){entries.add(new Entry(name,"无法安全读取或尚无已接收文件",0,rootKey,null,null,null));}
            }
            entries.sort(Comparator.comparing(x->x.name));return entries;
        }catch(NoSuchFileException noRoot){return Collections.emptyList();}
    }
    private static void same(Entry entry,SecureDirectoryStream<Path> base,SecureDirectoryStream<Path> stage)throws IOException{
        if(!entry.rootKey.equals(identity(attrs(base)))||!entry.dirKey.equals(identity(attrs(stage))))throw problem("暂存目录身份已变化");BasicFileAttributes a=raw(stage);
        if(!entry.fileKey.equals(identity(a))||a.size()!=entry.bytes||!entry.modified.equals(a.lastModifiedTime()))throw problem("已接收文件已变化，请重新选择");
    }
    private static String digest(SecureDirectoryStream<Path> stage,SeekableByteChannel output,long limit)throws IOException{
        MessageDigest sha;try{sha=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IOException(e);}
        Set<OpenOption> opts=new HashSet<>();opts.add(StandardOpenOption.READ);opts.add(LinkOption.NOFOLLOW_LINKS);long count=0;
        try(SeekableByteChannel in=stage.newByteChannel(Paths.get("received.zip"),opts)){
            ByteBuffer b=ByteBuffer.allocate(65536);int n;while((n=in.read(b))!=-1){checkInterrupted();if(n==0)continue;count+=n;if(count>limit)throw problem("已接收文件大小已变化");sha.update(b.array(),0,n);b.flip();if(output!=null)while(b.hasRemaining()){checkInterrupted();output.write(b);}b.clear();}
        }
        if(count!=limit)throw problem("已接收文件大小已变化");StringBuilder out=new StringBuilder();for(byte b:sha.digest())out.append(String.format(Locale.ROOT,"%02x",b));return out.toString();
    }
    Snapshot inspect(Entry entry)throws IOException{
        if(entry==null||!entry.selectable())throw problem("该暂存没有可安全导出的已接收数据");
        try(SecureDirectoryStream<Path> base=secure(root);SecureDirectoryStream<Path> stage=openStage(base,entry.name,entry.dirKey)){same(entry,base,stage);String sha=digest(stage,null,entry.bytes);same(entry,base,stage);return new Snapshot(entry,sha);}
    }
    private static String copyDigest(SeekableByteChannel source,long size)throws IOException{
        MessageDigest sha;try{sha=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IOException(e);}source.position(0);ByteBuffer b=ByteBuffer.allocate(65536);long count=0;int n;
        while((n=source.read(b))!=-1){checkInterrupted();if(n==0)continue;count+=n;if(count>size)throw problem("导出副本大小不符");sha.update(b.array(),0,n);b.clear();}
        if(count!=size)throw problem("导出副本大小不符");StringBuilder result=new StringBuilder();for(byte v:sha.digest())result.append(String.format(Locale.ROOT,"%02x",v));return result.toString();
    }
    File copy(Snapshot selected)throws IOException{
        if(selected==null)throw problem("没有选择暂存");Entry entry=selected.entry;
        try{Files.createDirectory(exports);}catch(FileAlreadyExistsException existing){/* Securely verify the existing directory below. */}
        try(SecureDirectoryStream<Path> base=secure(root);SecureDirectoryStream<Path> stage=openStage(base,entry.name,entry.dirKey);SecureDirectoryStream<Path> dest=secure(exports)){
            same(entry,base,stage);if(Files.getFileStore(exports).getUsableSpace()<entry.bytes+RESERVE)throw problem("剩余空间不足，无法创建安全副本");
            String name="received-fragment-"+UUID.randomUUID()+".bin";Set<OpenOption> options=new HashSet<>();options.add(StandardOpenOption.READ);options.add(StandardOpenOption.WRITE);options.add(StandardOpenOption.CREATE_NEW);options.add(LinkOption.NOFOLLOW_LINKS);
            try(SeekableByteChannel out=dest.newByteChannel(Paths.get(name),options)){
                String sha=digest(stage,out,entry.bytes);if(!sha.equals(selected.sha))throw problem("已接收文件内容已变化");same(entry,base,stage);if(!(out instanceof FileChannel))throw problem("无法确认副本已同步");((FileChannel)out).force(true);if(!selected.sha.equals(copyDigest(out,entry.bytes)))throw problem("导出副本校验不符");
            }catch(IOException|RuntimeException failure){throw new IOException("片段副本未完成，可能留在应用缓存中并占用空间；原暂存未删除",failure);}
            return exports.resolve(name).toFile();
        }
    }
}
