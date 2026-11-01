package org.wangshu.scan;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/** In-process crash fence. Guard and manifest candidates are never deleted by this class. */
final class ManifestFence {
    static final class Uncertain extends IOException {
        private static final long serialVersionUID=1L;
        Uncertain(String detail){super("文稿保存结果未确认；原图和记录候选均保留，请停止修改并联系恢复。"+detail);}
        Uncertain(String detail,Throwable cause){this(detail);initCause(cause);}
    }
    private static final int LIMIT=2*1024*1024;
    private static File guard(File base){return new File(base.getParentFile(),"manifest.guard");}
    interface AttributeProbe {void inspect(File file)throws IOException;}
    private static final AttributeProbe FILE_ATTRIBUTES=file->Files.readAttributes(file.toPath(),java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
    static boolean present(File file,AttributeProbe probe)throws IOException{
        try{probe.inspect(file);return true;}
        catch(NoSuchFileException absent){return false;}
        catch(IOException|SecurityException unknown){throw new Uncertain("无法确认保护文件或候选文件的状态。",unknown);}
    }
    private static boolean exists(File file)throws IOException{return present(file,FILE_ATTRIBUTES);}
    private static void noSidecars(File base)throws IOException{
        if(exists(new File(base.getPath()+".new"))||exists(new File(base.getPath()+".bak")))throw new Uncertain("发现待核对的 .new/.bak 文件，未读取或更改它们。");
    }
    private static byte[] read(File f,int max)throws IOException{
        if(!Files.isRegularFile(f.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("文件不存在或不是普通文件");
        try(InputStream in=Files.newInputStream(f.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>max)throw new IOException("文件过大");out.write(b,0,n);}return out.toByteArray();
        }
    }
    private static String sha(byte[] data)throws IOException{
        try{byte[] value=MessageDigest.getInstance("SHA-256").digest(data);StringBuilder s=new StringBuilder();for(byte b:value)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}
        catch(NoSuchAlgorithmException impossible){throw new IOException(impossible);}
    }
    private static String guardText(File base)throws IOException{
        File f=guard(base);if(!exists(f))return null;
        try{return new String(read(f,256),StandardCharsets.US_ASCII);}catch(IOException e){throw new Uncertain("保护记录无法读取。",e);}
    }
    /** Read without AtomicFile.openRead: it can consume unresolved sidecars. */
    static byte[] readConfirmed(File base)throws IOException{
        noSidecars(base);String state=guardText(base);
        if(state!=null&&!state.matches("v1:C:[a-f0-9]{64}\\n"))throw new Uncertain("保护记录未完成或损坏。");
        byte[] bytes;try{bytes=read(base,LIMIT);}catch(IOException e){if(state!=null)throw new Uncertain("已保护的文稿无法读取。",e);throw e;}
        noSidecars(base);
        if(state!=null&&!state.equals("v1:C:"+sha(bytes)+"\n"))throw new Uncertain("保护记录与文稿内容不一致。");
        return bytes;
    }
    private static void writeGuard(File base,String value)throws IOException{
        File f=guard(base);byte[] bytes=value.getBytes(StandardCharsets.US_ASCII);
        try(FileChannel channel=FileChannel.open(f.toPath(),StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING,LinkOption.NOFOLLOW_LINKS)){
            ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining())channel.write(b);channel.force(true);
        }
    }
    /** Caller serializes every document operation with Store.FILE_LOCK. */
    static void begin(File base)throws IOException{
        noSidecars(base);String state=guardText(base);
        if(state!=null){byte[] old=readConfirmed(base);if(old.length==0)throw new Uncertain("当前文稿为空。");}
        try{String pending="v1:P:"+UUID.randomUUID()+"\n";writeGuard(base,pending);if(!pending.equals(guardText(base)))throw new IOException("写前保护记录未确认");}catch(IOException|RuntimeException e){throw new Uncertain("无法确认保护记录；未尝试发布文稿。",e);}
    }
    static void confirm(File base,byte[] expected)throws IOException{
        try{
            noSidecars(base);String state=guardText(base);
            if(state==null||!state.matches("v1:P:[a-f0-9-]{36}\\n"))throw new IOException("保护记录无效");
            byte[] actual=read(base,LIMIT);noSidecars(base);
            if(!java.util.Arrays.equals(actual,expected))throw new IOException("写后文稿不一致");
            writeGuard(base,"v1:C:"+sha(actual)+"\n");
            if(!("v1:C:"+sha(actual)+"\n").equals(guardText(base)))throw new IOException("保护记录未确认");
        }catch(IOException|RuntimeException e){if(e instanceof Uncertain)throw (Uncertain)e;throw new Uncertain("发布或核验失败，未自动重试。",e);}
    }
}
