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
    private static final Object LOCK=new Object(),LIFECYCLE_LOCK=new Object();
    private static final int MAGIC=0x57454431,MAGIC_V2=0x57454432,MAX=4*1024*1024,SCAN_LIMIT=4096;
    private final Path root;
    private final int maxFiles,recordLimit;private final long maxBytes;private final Object lock;
    EditDraftStore(File files){this(files,"edit-drafts",16);}
    private EditDraftStore(File files,String folder,int count){this(files,folder,count,64L*1024*1024,MAX,LOCK);}
    private EditDraftStore(File files,String folder,int count,long bytes,int recordLimit,Object lock){root=files.toPath().resolve(folder);maxFiles=count;maxBytes=bytes;this.recordLimit=recordLimit;this.lock=lock;}
    // Separate immutable lifecycle snapshots can retain an edit that is too large to save.
    // App policy limits, unrelated to any Android Bundle/Binder limit. No automatic deletion.
    static EditDraftStore lifecycle(File files){return new EditDraftStore(files,"edit-lifecycle",256,128L*1024*1024,16*1024*1024,LIFECYCLE_LOCK);}
    static int validate(Draft draft)throws IOException{id(draft.document);return encode(draft,MAX).length;}
    static EditDraftStore recovery(File files){return new EditDraftStore(files,"edit-recovery",256);}
    static final class Draft {
        final String document,version,baseline,edited;final long created;final boolean submitted;
        Draft(String d,String b,String e,long t){this(d,"",b,e,false,t);}
        Draft(String d,String v,String b,String e,boolean submitted,long t){document=d;version=v==null?"":v;baseline=b;edited=e;this.submitted=submitted;created=t;}
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
    private static void text(DataOutputStream out,String value,int limit)throws IOException {if(value==null)throw new IOException("草稿缺少字段");byte[] bytes=value.getBytes(StandardCharsets.UTF_8);if(!value.equals(new String(bytes,StandardCharsets.UTF_8)))throw new IOException("草稿包含无法无损保存的文字编码，未写入副本");if(bytes.length>limit)throw new IOException("草稿字段过大");out.writeInt(bytes.length);out.write(bytes);}
    private static String text(DataInputStream in,int limit)throws IOException {int n=in.readInt();if(n<0||n>limit||n>in.available())throw new IOException("草稿字段长度无效");byte[] bytes=new byte[n];in.readFully(bytes);String value=new String(bytes,StandardCharsets.UTF_8);if(!Arrays.equals(bytes,value.getBytes(StandardCharsets.UTF_8)))throw new IOException("草稿文字编码无效");return value;}
    private static byte[] encode(Draft d,int limit)throws IOException {
        if(d.baseline==null||d.edited==null||d.baseline.isEmpty()||d.edited.isEmpty())throw new IOException("草稿缺少基线或编辑参数，未写入副本");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream out=new DataOutputStream(bytes)){boolean extended=!d.version.isEmpty()||d.submitted;out.writeInt(extended?MAGIC_V2:MAGIC);out.writeLong(d.created);text(out,d.document,limit);
            if(extended){text(out,d.version,limit);out.writeBoolean(d.submitted);}text(out,d.baseline,limit);text(out,d.edited,limit);}
        byte[] payload=bytes.toByteArray();if(payload.length>limit-32)throw new IOException("编辑记录超过 "+limit+" 字节上限，未写入副本");bytes.write(sha(payload));return bytes.toByteArray();
    }
    private Draft decode(byte[] bytes,String owner)throws IOException {
        if(bytes.length<48||bytes.length>recordLimit)throw new IOException("草稿长度无效");byte[] payload=Arrays.copyOf(bytes,bytes.length-32);
        if(!MessageDigest.isEqual(sha(payload),Arrays.copyOfRange(bytes,bytes.length-32,bytes.length)))throw new IOException("草稿不完整或校验失败，文件保留");
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(payload))){int magic=in.readInt();if(magic!=MAGIC&&magic!=MAGIC_V2)throw new IOException("草稿版本不支持");long created=in.readLong();String doc=text(in,recordLimit),version=magic==MAGIC_V2?text(in,recordLimit):"";boolean submitted=magic==MAGIC_V2&&in.readBoolean();String baseline=text(in,recordLimit),edited=text(in,recordLimit);if(!owner.equals(doc)||created<0||in.available()!=0||baseline.isEmpty()||edited.isEmpty())throw new IOException("草稿归属或字段无效");return new Draft(doc,version,baseline,edited,submitted,created);}
    }
    private byte[] read(Path file)throws IOException {
        interrupted();if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("草稿不是普通文件");
        try(FileChannel in=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            long size=in.size();if(size<48||size>recordLimit)throw new IOException("草稿长度无效");ByteBuffer b=ByteBuffer.allocate((int)size);while(b.hasRemaining()){interrupted();int n=in.read(b);if(n<=0)throw new IOException("草稿未完整读取");}if(in.read(ByteBuffer.allocate(1))!=-1)throw new IOException("草稿读取期间长度变化");return b.array();
        }
    }
    Entry snapshot(Draft d)throws IOException{return saveNew(new Draft(d.document,d.version,d.baseline,d.edited,d.submitted,System.currentTimeMillis()));}
    Entry save(String owner,String baseline,String edited)throws IOException {
        return saveNew(new Draft(owner,baseline,edited,System.currentTimeMillis()));
    }
    /** Immutable pre-commit journal. Exact retries reuse one verified record. Never auto-applied. */
    Entry retain(String owner,String version,String baseline,String edited,boolean submitted)throws IOException {
        synchronized(lock){
            if(version==null||(!version.isEmpty()&&!version.matches("[a-f0-9]{64}")))throw new IOException("恢复记录文稿版本无效");
            for(Entry entry:list(owner))if(entry.valid){
                Draft old=load(owner,entry);
                if(old.version.equals(version)&&old.baseline.equals(baseline)&&old.edited.equals(edited)&&old.submitted==submitted)return entry;
            }
            return saveNew(new Draft(owner,version,baseline,edited,submitted,System.currentTimeMillis()));
        }
    }
    /** Directory quota snapshot. Damaged/unknown names still consume count and regular bytes. */
    static final class Usage {
        final long count,bytes,byteLimit;final int fileLimit,recordLimit;final boolean complete;
        Usage(long c,long b,boolean known,int limit,long size){this(c,b,known,limit,size,MAX);}
        Usage(long c,long b,boolean known,int limit,long size,int record){count=c;bytes=b;complete=known;fileLimit=limit;byteLimit=size;recordLimit=record;}
        long remainingFiles(){return complete?Math.max(0,fileLimit-count):0;}
        long remainingBytes(){return complete?Math.max(0,byteLimit-bytes):0;}
        boolean canAdd(long size){return complete&&size>0&&size<=recordLimit&&count<fileLimit&&bytes<=byteLimit&&size<=byteLimit-bytes;}
        boolean full(){return !complete||count>=fileLimit||bytes>=byteLimit;}
        boolean near(){return full()||count*5>=fileLimit*4L||bytes>=byteLimit*4/5||remainingBytes()<recordLimit;}
    }
    private Usage scanUsage(Path dir)throws IOException {
        long count=0,bytes=0;boolean complete=true;
        try(DirectoryStream<Path> paths=Files.newDirectoryStream(dir)){for(Path file:paths){
            interrupted();if(count>=SCAN_LIMIT){complete=false;break;}count++;
            try{java.nio.file.attribute.BasicFileAttributes a=Files.readAttributes(file,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(!a.isRegularFile()||a.size()<0){complete=false;continue;}bytes=Math.addExact(bytes,a.size());
            }catch(IOException|ArithmeticException unknown){complete=false;}
        }}
        return new Usage(count,bytes,complete,maxFiles,maxBytes,recordLimit);
    }
    Usage usage(String owner)throws IOException {
        synchronized(lock){interrupted();id(owner);directory(root.getParent(),false);
            if(!present(root))return new Usage(0,0,true,maxFiles,maxBytes,recordLimit);directory(root,false);
            if(!present(root.resolve(owner)))return new Usage(0,0,true,maxFiles,maxBytes,recordLimit);
            return scanUsage(folder(owner,false));
        }
    }
    static final class Inventory {
        final Usage usage;final ArrayList<Entry> entries;
        Inventory(Usage u,ArrayList<Entry> e){usage=u;entries=e;}
    }
    Inventory inventory(String owner)throws IOException {synchronized(lock){return new Inventory(usage(owner),list(owner));}}
    private Entry saveNew(Draft draft)throws IOException {
        synchronized(lock){
            interrupted();String owner=draft.document;id(owner);byte[] data=encode(draft,recordLimit);Path dir=folder(owner,true);
            Usage usage=scanUsage(dir);
            if(!usage.complete)throw new IOException("恢复记录额度无法完整核对；未知条目保留，尚未保存页面");
            if(usage.count>=usage.fileLimit)throw new IOException("编辑记录数量已达上限（"+usage.fileLimit+"）；旧记录保留，尚未保存页面");
            if(!usage.canAdd(data.length))throw new IOException("该文稿记录达到 "+maxBytes+" 字节额度或不足以容纳本条记录；旧记录和当前编辑保留，尚未保存页面");
            String token=UUID.randomUUID().toString();Path file=dir.resolve(token+".draft");
            try(FileChannel out=FileChannel.open(file,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                ByteBuffer b=ByteBuffer.wrap(data);while(b.hasRemaining()){interrupted();if(out.write(b)==0)throw new IOException("草稿写入无进展；片段保留");}out.force(true);
            }
            byte[] confirmed=read(file);if(!Arrays.equals(data,confirmed))throw new IOException("草稿写后核对失败，候选保留");
            Draft checked=decode(confirmed,owner);return new Entry(token,hex(sha(confirmed)),checked.created,null);
        }
    }
    ArrayList<String> owners()throws IOException {
        synchronized(lock){
            ArrayList<String> ids=new ArrayList<>();directory(root.getParent(),false);if(!present(root))return ids;directory(root,false);
            try(DirectoryStream<Path> entries=Files.newDirectoryStream(root)){for(Path file:entries){
                if(ids.size()>=1024)throw new IOException("恢复记录文稿数超出预期，未删除任何内容");
                String owner=file.getFileName().toString();id(owner);directory(file,false);ids.add(owner);
            }}Collections.sort(ids);return ids;
        }
    }
    ArrayList<Entry> list(String owner)throws IOException {
        synchronized(lock){interrupted();id(owner);ArrayList<Entry> result=new ArrayList<>();directory(root.getParent(),false);if(!present(root))return result;directory(root,false);if(!present(root.resolve(owner)))return result;Path dir=folder(owner,false);
            try(DirectoryStream<Path> entries=Files.newDirectoryStream(dir)){for(Path p:entries){interrupted();if(result.size()>=SCAN_LIMIT)throw new IOException("草稿目录项超过4096，余量无法完整核对，未自动处理任何记录");String name=p.getFileName().toString();String token=name.endsWith(".draft")?name.substring(0,name.length()-6):name;
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
        synchronized(lock){
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
        synchronized(lock){interrupted();if(entry==null||!entry.valid)throw new IOException("该草稿不可载入，记录保留");id(entry.token);byte[] bytes=read(folder(owner,false).resolve(entry.token+".draft"));if(!hex(sha(bytes)).equals(entry.digest))throw new IOException("草稿在选择后已变化，未载入");return decode(bytes,owner);}
    }
}
