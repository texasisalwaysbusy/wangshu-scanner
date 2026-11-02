package org.wangshu.scan;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;

/** Pinned packaged OCR assets: read-only verification and non-replacing first-use preparation. */
final class OcrAssetIntegrity {
    private OcrAssetIntegrity() {}
    private static final java.util.concurrent.locks.ReentrantLock PREPARE_LOCK=new java.util.concurrent.locks.ReentrantLock();
    interface Source {java.io.InputStream open(String name)throws IOException;}
    static File prepareDirectory(File files)throws IOException {
        interrupted();
        java.nio.file.Path parent=files.toPath();
        if(!Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS))throw new IOException("应用资源根目录不可用");
        for(String segment:new String[]{"ocr","tessdata"}){
            interrupted();
            parent=parent.resolve(segment);
            try{Files.createDirectory(parent);}catch(java.nio.file.FileAlreadyExistsException existing){/* verify, never replace */}
            if(!Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS))throw new IOException("识别资源目录异常，未替换已有内容");
        }
        return parent.toFile();
    }
    /** Existing files, including empty/damaged ones, are never repaired or overwritten. */
    static void prepare(File directory,String name,Source source)throws IOException {
        if(!java.util.Arrays.asList("eng.traineddata","chi_sim.traineddata","pdf.ttf").contains(name))throw new IOException("未知的本地识别资源，未准备文件");
        try{PREPARE_LOCK.lockInterruptibly();}catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new InterruptedIOException("等待识别资源准备时已取消");}
        try{
            interrupted();
            java.nio.file.Path folder=directory.toPath();
            if(!Files.isDirectory(folder,LinkOption.NOFOLLOW_LINKS))throw new IOException("识别资源目录不可用或不是普通目录");
            File target=new File(directory,name);
            boolean exists;
            try{Files.readAttributes(target.toPath(),java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);exists=true;}
            catch(java.nio.file.NoSuchFileException missing){exists=false;}
            if(exists){verify(target,name);return;}
            int fragments=0;
            try(java.nio.file.DirectoryStream<java.nio.file.Path> entries=Files.newDirectoryStream(folder)){
                for(java.nio.file.Path entry:entries){interrupted();String old=entry.getFileName().toString();if(old.equals(name+".part")||(old.startsWith(name+"-")&&old.endsWith(".part"))){if(++fragments>=4)throw new IOException("该识别资源已有 4 份待核对片段，未继续准备；已有内容保留，请先核对存储");}}
            }
            // Unique CREATE_NEW keeps prior interrupted candidates intact; no automatic cleanup.
            java.nio.file.Path part=folder.resolve(name+"-"+java.util.UUID.randomUUID()+".part");
            try(java.io.InputStream in=source.open(name);java.nio.channels.FileChannel out=java.nio.channels.FileChannel.open(part,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                byte[] bytes=new byte[65536];long count=0;
                for(;;){interrupted();int n=in.read(bytes);if(n==-1)break;if(n==0)throw new IOException("识别资源读取无进展，已接收片段保留");count+=n;if(count>5L*1024*1024)throw new IOException("识别资源超出准备上限，已接收片段保留");
                    ByteBuffer buffer=ByteBuffer.wrap(bytes,0,n);while(buffer.hasRemaining()){interrupted();if(out.write(buffer)==0)throw new IOException("识别资源写入无进展，片段保留");}
                }out.force(true);
            }
            verify(part.toFile(),name);interrupted();
            // No REPLACE_EXISTING or ATOMIC_MOVE: never intentionally replace an existing target.
            // Process-local serialization avoids our own concurrent publishers; this is not a
            // defense against a malicious same-UID directory swap.
            Files.move(part,target.toPath());
            verify(target,name);
        }finally{PREPARE_LOCK.unlock();}
    }
    static void verify(File file,String name)throws IOException {
        final long size;
        final String expected;
        // Keep aligned with docs/OCR-DEPENDENCIES.json and reviewed packaged assets.
        switch(name){
            case "eng.traineddata":size=4113088;expected="7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2";break;
            case "chi_sim.traineddata":size=2469156;expected="a5fcb6f0db1e1d6d8522f39db4e848f05984669172e584e8d76b6b3141e1f730";break;
            case "pdf.ttf":size=572;expected="c7845420925a23d88ed830a63957b8af85a66a8daf8d9fc90e843673b2ef1a59";break;
            default:throw new IOException("未知的本地识别资源，未启动识别");
        }
        interrupted();
        if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS))throw invalid(name);
        MessageDigest digest;
        try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IOException(e);}
        Set<OpenOption> options=new HashSet<>();options.add(StandardOpenOption.READ);options.add(LinkOption.NOFOLLOW_LINKS);
        long count=0;
        try(SeekableByteChannel input=Files.newByteChannel(file.toPath(),options)){
            if(input.size()!=size)throw invalid(name);
            ByteBuffer block=ByteBuffer.allocate(65536);
            while(true){
                interrupted();int n=input.read(block);if(n==-1)break;
                if(n==0)throw new IOException("本地识别资源读取无进展，未启动识别");
                count+=n;if(count>size)throw invalid(name);
                digest.update(block.array(),0,n);block.clear();
            }
        }
        interrupted();
        StringBuilder actual=new StringBuilder(64);
        for(byte b:digest.digest()){actual.append(Character.forDigit((b>>>4)&15,16));actual.append(Character.forDigit(b&15,16));}
        if(count!=size||!expected.contentEquals(actual))throw invalid(name);
    }
    private static IOException invalid(String name){
        return new IOException("本地识别资源校验失败（"+name+"），未启动识别。已有文稿和识别文字保留；不会自动覆盖或删除该资源，请先核对。");
    }
    private static void interrupted()throws InterruptedIOException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("已取消识别资源核对；已有文稿和识别文字保留");
    }
}
