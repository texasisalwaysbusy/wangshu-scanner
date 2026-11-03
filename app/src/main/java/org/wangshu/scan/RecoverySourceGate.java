package org.wangshu.scan;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Objects;

/** Fresh read-only source proof and UI delivery binding. A list label is never a proof. */
final class RecoverySourceGate {
    private static final long MAX_SOURCE=100L*1024*1024;
    private static void require(boolean value,String message)throws IOException {if(!value)throw new IOException(message);}
    private static void interrupted()throws InterruptedIOException {if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("已取消原图核验，记录保留");}
    private static boolean uuid(String value){return value!=null&&value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");}
    private static BasicFileAttributes attributes(Path root,Path owner,Path originals,Path file)throws IOException {
        interrupted();
        for(Path p:new Path[]{root,owner,originals})require(Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(p),"原图目录不可安全核验，未载入编辑");
        require(file.toFile().getCanonicalFile().getParentFile().equals(originals.toFile().getCanonicalFile()),"原图路径不在预期目录");
        BasicFileAttributes a=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        require(a.isRegularFile()&&!a.isSymbolicLink()&&a.size()>0&&a.size()<=MAX_SOURCE,"原图不是可核验的普通文件");
        return a;
    }
    private static boolean same(BasicFileAttributes a,BasicFileAttributes b){
        return a.size()==b.size()&&a.lastModifiedTime().equals(b.lastModifiedTime())&&Objects.equals(a.fileKey(),b.fileKey());
    }
    static final class Proof {
        final EditRecoveryPolicy.Identity source;
        private final Path root,owner,originals,file;
        private final BasicFileAttributes stamp;
        private Proof(EditRecoveryPolicy.Identity s,Path r,Path o,Path d,Path f,BasicFileAttributes a){source=s;root=r;owner=o;originals=d;file=f;stamp=a;}
        /** Worker-only final observation, after re-reading the document. No UI-thread I/O. */
        void recheck()throws IOException {require(same(stamp,attributes(root,owner,originals,file)),"原图在核验期间已变化，未载入编辑");}
    }
    static Proof verify(File documents,EditRecoveryPolicy.Identity source)throws Exception {
        require(source!=null&&uuid(source.document)&&uuid(source.page)
                &&("originals/"+source.page+".image").equals(source.file)
                &&source.sha!=null&&source.sha.matches("[a-f0-9]{64}"),"恢复记录的原图身份无效");
        Path root=documents.toPath(),owner=root.resolve(source.document),originals=owner.resolve("originals"),file=originals.resolve(source.page+".image");
        BasicFileAttributes before=attributes(root,owner,originals,file);
        MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0;
        try(FileChannel in=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            require(in.size()==before.size(),"原图在打开期间已变化");ByteBuffer buffer=ByteBuffer.allocate(65536);int n;
            while((n=in.read(buffer))!=-1){interrupted();require(n>0&&count+n<=MAX_SOURCE,"原图读取未完整完成");count+=n;buffer.flip();digest.update(buffer);buffer.clear();}
            require(count==before.size()&&in.size()==before.size(),"原图读取期间长度变化");
        }
        StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(Locale.ROOT,"%02x",b&255));
        require(hash.toString().equals(source.sha),"真实原图校验失败，记录保留，未载入编辑");
        BasicFileAttributes after=attributes(root,owner,originals,file);
        require(same(before,after),"原图核验期间已被替换或修改");
        return new Proof(source,root,owner,originals,file,after);
    }
    interface Read<T>{T run()throws Exception;}
    static final class Verified<T> {
        final T value;final Proof proof;
        private Verified(T v,Proof p){value=v;proof=p;}
    }
    /** Hash now, re-read current document, then observe the same source entry once more. Never writes. */
    static <T> Verified<T> verifyAndRead(File documents,EditRecoveryPolicy.Identity source,Read<T> current)throws Exception {
        Proof proof=verify(documents,source);
        T value=current.run();require(value!=null,"未取得当前版本，记录未应用");proof.recheck();
        return new Verified<>(value,proof);
    }
    static final class State {
        final Object recovery,document,page,session;
        final String owner,token,digest,documentId,pageId,documentVersion;
        final int generation,screen;final long cycle;final boolean live;
        State(Object r,Object d,Object p,Object s,String o,String t,String h,String di,String pi,String v,int g,int sc,long c,boolean l){
            recovery=r;document=d;page=p;session=s;owner=o;token=t;digest=h;documentId=di;pageId=pi;documentVersion=v;generation=g;screen=sc;cycle=c;live=l;
        }
    }
    static final class Ticket {
        private final State initial;
        Ticket(State s){initial=s;}
        boolean accepts(Ticket current,State now){
            State a=initial;return current==this&&a!=null&&now!=null&&uuid(a.owner)&&uuid(a.token)&&a.digest!=null&&a.digest.matches("[a-f0-9]{64}")&&a.live&&now.live&&a.screen==3&&now.screen==3
                    &&a.recovery==now.recovery&&a.document==now.document&&a.page==now.page&&a.session==now.session
                    &&Objects.equals(a.owner,now.owner)&&Objects.equals(a.token,now.token)&&Objects.equals(a.digest,now.digest)
                    &&Objects.equals(a.documentId,now.documentId)&&Objects.equals(a.pageId,now.pageId)&&Objects.equals(a.documentVersion,now.documentVersion)
                    &&a.generation==now.generation&&a.cycle==now.cycle;
        }
    }
    /*
     * App-owned originals are immutable. Hash and final stamp observation run on a worker.
     * The later UI callback rechecks its complete ticket and observed document version.
     * Java cannot make this atomic against an arbitrary external process replacing bytes
     * after the final observation (or restoring timestamps/file identity); no such claim.
     */
    private RecoverySourceGate(){}
}
