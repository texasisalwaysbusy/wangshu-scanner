package org.wangshu.scan;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Explicit immutable edit checkpoints, separate from document manifests. No cleanup or auto-apply. */
final class EditDraftStore {
    private static final Object LOCK=new Object();
    private static final int MAGIC=0x57454431,MAX=4*1024*1024,MAX_FILES=16;
    private final Path root;
    EditDraftStore(File files){root=files.toPath().resolve("edit-drafts");}
    static final class Draft {
        final String document,baseline,edited;final long created;
        Draft(String d,String b,String e,long t){document=d;baseline=b;edited=e;created=t;}
    }
    static final class Entry {
        final String token,digest,error;final long created;final boolean valid;
        Entry(String t,String h,long c,String e){token=t;digest=h;created=c;error=e;valid=e==null;}
    }
    private static void interrupted()throws InterruptedIOException {if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("已取消草稿核对；已有记录保留");}
    private static void id(String v)throws IOException {if(v==null||!v.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new IOException("无效的草稿标识");}
    private static boolean present(Path p)throws IOException {try{Files.readAttributes(p,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);return true;}catch(NoSuchFileException absent){return false;}}
    private static void directory(Path p,boolean create)throws IOException {
        if(!present(p)&&create){try{Files.createDirectory(p);}catch(FileAlreadyExistsException concurrent){/* recheck below */}}
        if(!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))throw new IOException("草稿目录不可用或不是普通目录，未修改已有数据");
    }
    private Path folder(String owner,boolean create)throws IOException {
        id(owner);directory(root.getParent(),false);directory(root,create);Path p=root.resolve(owner);directory(p,create);return p;
    }
    private static byte[] sha(byte[] b)throws IOException {try{return MessageDigest.getInstance("SHA-256").digest(b);}catch(java.security.NoSuchAlgorithmException e){throw new IOException(e);}}
    private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(Locale.ROOT,"%02x",v&255));return s.toString();}
    private static void text(DataOutputStream out,String value)throws IOException {if(value==null)throw new IOException("草稿缺少字段");byte[] bytes=value.getBytes(StandardCharsets.UTF_8);if(!value.equals(new String(bytes,StandardCharsets.UTF_8)))throw new IOException("草稿包含无法无损保存的文字编码，未写入副本");if(bytes.length>MAX)throw new IOException("草稿字段过大");out.writeInt(bytes.length);out.write(bytes);}
    private static String text(DataInputStream in)throws IOException {int n=in.readInt();if(n<0||n>MAX||n>in.available())throw new IOException("草稿字段长度无效");byte[] bytes=new byte[n];in.readFully(bytes);String value=new String(bytes,StandardCharsets.UTF_8);if(!Arrays.equals(bytes,value.getBytes(StandardCharsets.UTF_8)))throw new IOException("草稿文字编码无效");return value;}
    private static byte[] encode(Draft d)throws IOException {
        if(d.baseline==null||d.edited==null||d.baseline.isEmpty()||d.edited.isEmpty())throw new IOException("草稿缺少基线或编辑参数，未写入副本");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream out=new DataOutputStream(bytes)){out.writeInt(MAGIC);out.writeLong(d.created);text(out,d.document);text(out,d.baseline);text(out,d.edited);}
        byte[] payload=bytes.toByteArray();if(payload.length>MAX-32)throw new IOException("编辑草稿超过 4 MiB，未写入副本");bytes.write(sha(payload));return bytes.toByteArray();
    }
    private static Draft decode(byte[] bytes,String owner)throws IOException {
        if(bytes.length<48||bytes.length>MAX)throw new IOException("草稿长度无效");byte[] payload=Arrays.copyOf(bytes,bytes.length-32);
        if(!MessageDigest.isEqual(sha(payload),Arrays.copyOfRange(bytes,bytes.length-32,bytes.length)))throw new IOException("草稿不完整或校验失败，文件保留");
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(payload))){if(in.readInt()!=MAGIC)throw new IOException("草稿版本不支持");long created=in.readLong();String doc=text(in),baseline=text(in),edited=text(in);if(!owner.equals(doc)||created<0||in.available()!=0||baseline.isEmpty()||edited.isEmpty())throw new IOException("草稿归属或字段无效");return new Draft(doc,baseline,edited,created);}
    }
    private static byte[] read(Path file)throws IOException {
        interrupted();if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("草稿不是普通文件");
        try(FileChannel in=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            long size=in.size();if(size<48||size>MAX)throw new IOException("草稿长度无效");ByteBuffer b=ByteBuffer.allocate((int)size);while(b.hasRemaining()){interrupted();int n=in.read(b);if(n<=0)throw new IOException("草稿未完整读取");}if(in.read(ByteBuffer.allocate(1))!=-1)throw new IOException("草稿读取期间长度变化");return b.array();
        }
    }
    Entry save(String owner,String baseline,String edited)throws IOException {
        synchronized(LOCK){interrupted();id(owner);byte[] data=encode(new Draft(owner,baseline,edited,System.currentTimeMillis()));Path dir=folder(owner,true);
            int count=0;try(DirectoryStream<Path> entries=Files.newDirectoryStream(dir)){for(Path ignored:entries){if(++count>=MAX_FILES)throw new IOException("该文稿已保留 16 份草稿或片段，未再写入；已有记录不删除，请先找回需要的内容");}}
            String token=UUID.randomUUID().toString();Path file=dir.resolve(token+".draft");
            // Unique CREATE_NEW: errors leave any received prefix, never retry or replace it.
            try(FileChannel out=FileChannel.open(file,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){ByteBuffer b=ByteBuffer.wrap(data);while(b.hasRemaining()){interrupted();if(out.write(b)==0)throw new IOException("草稿写入无进展；片段保留");}out.force(true);}
            byte[] confirmed=read(file);if(!Arrays.equals(data,confirmed))throw new IOException("草稿写后核对失败，候选保留");Draft d=decode(confirmed,owner);return new Entry(token,hex(sha(confirmed)),d.created,null);
        }
    }
    ArrayList<Entry> list(String owner)throws IOException {
        synchronized(LOCK){interrupted();id(owner);ArrayList<Entry> result=new ArrayList<>();directory(root.getParent(),false);if(!present(root))return result;directory(root,false);if(!present(root.resolve(owner)))return result;Path dir=folder(owner,false);
            try(DirectoryStream<Path> entries=Files.newDirectoryStream(dir)){for(Path p:entries){interrupted();if(result.size()>=MAX_FILES)throw new IOException("草稿目录项超出预期，未自动处理任何记录");String name=p.getFileName().toString();String token=name.endsWith(".draft")?name.substring(0,name.length()-6):name;
                try{id(token);if(!name.equals(token+".draft"))throw new IOException("未知的草稿文件");byte[] bytes=read(p);Draft d=decode(bytes,owner);result.add(new Entry(token,hex(sha(bytes)),d.created,null));}catch(InterruptedIOException cancelled){throw cancelled;}catch(IOException damaged){result.add(new Entry(token,"",0,damaged.getMessage()));}
            }}result.sort((a,b)->Long.compare(b.created,a.created));return result;
        }
    }
    static final class Export {
        final File file;final String digest;final long bytes;
        Export(File f,String h,long n){file=f;digest=h;bytes=n;}
    }
    /** Explicit metadata export, including historical OCR strings; not an image/document backup. */
    Export export(String owner,Entry entry,File cache)throws IOException {
        synchronized(LOCK){
            interrupted();if(entry==null||!entry.valid)throw new IOException("该草稿不可导出，原记录保留");id(entry.token);
            byte[] bytes=read(folder(owner,false).resolve(entry.token+".draft"));
            if(!hex(sha(bytes)).equals(entry.digest))throw new IOException("草稿在选择后已变化，未导出");decode(bytes,owner);
            Path parent=cache.toPath();directory(parent,false);Path dest=parent.resolve("exports");directory(dest,true);
            Path target=dest.resolve("edit-checkpoint-"+UUID.randomUUID()+".bin");
            try(FileChannel out=FileChannel.open(target,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining()){interrupted();if(out.write(buffer)==0)throw new IOException("草稿副本写入无进展，片段保留");}out.force(true);}
            byte[] confirmed=read(target);if(!Arrays.equals(bytes,confirmed))throw new IOException("草稿副本核对失败，源文件和候选均保留");
            return new Export(target.toFile(),entry.digest,confirmed.length);
        }
    }
    Draft load(String owner,Entry entry)throws IOException {
        synchronized(LOCK){interrupted();if(entry==null||!entry.valid)throw new IOException("该草稿不可载入，记录保留");id(entry.token);byte[] bytes=read(folder(owner,false).resolve(entry.token+".draft"));if(!hex(sha(bytes)).equals(entry.digest))throw new IOException("草稿在选择后已变化，未载入");return decode(bytes,owner);}
    }
}
