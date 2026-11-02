package org.wangshu.scan;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/** Durable capture intent and receipts. Never deletes an original or a session. */
final class CaptureBatch {
    private static final java.util.concurrent.locks.ReentrantLock LOCK=new java.util.concurrent.locks.ReentrantLock();
    /** Preserve original critical sections and reentrancy, but allow queued cancellation. */
    private static BatchLease acquireBatch()throws InterruptedIOException {
        BatchLease lease=new BatchLease(); // Allocate before acquisition: allocation failure must not strand the lock.
        try{LOCK.lockInterruptibly();return lease;}
        catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new InterruptedIOException("等待连续扫描记录时已取消；尚未进入本次操作，已有文件保留");}
    }
    private static final class BatchLease implements AutoCloseable {
        private final Thread owner=Thread.currentThread();private boolean closed;
        @Override public void close(){if(Thread.currentThread()!=owner)throw new IllegalStateException("批次锁必须由持有线程释放");if(!closed){closed=true;LOCK.unlock();}}
    }
    static final class Shot {
        String name,sha="",page="";long bytes=0;
        JSONObject json()throws JSONException{return new JSONObject().put("name",name).put("sha256",sha).put("bytes",bytes).put("page",page);}
    }
    static final class Batch {
        String id,document,revision;long created;boolean closed=false;ArrayList<Shot> shots=new ArrayList<>();
        int saved(){int n=0;for(Shot s:shots)if(!s.sha.isEmpty())n++;return n;}
        int imported(){int n=0;for(Shot s:shots)if(!s.page.isEmpty())n++;return n;}
        JSONObject json()throws JSONException{JSONArray a=new JSONArray();for(Shot s:shots)a.put(s.json());return new JSONObject().put("version",1).put("id",id).put("document",document).put("created",created).put("closed",closed).put("shots",a);}
    }
    static File folder(Context c){return new File(c.getFilesDir(),"capture-sessions");}
    private static final Set<String> UNCERTAIN=new HashSet<>();
    /** Directory is never followed if its final path component is a symbolic link. */
    private static java.nio.file.Path base(Context c,String id,boolean createDirectory)throws IOException{
        Store.safeId(id);java.nio.file.Path dir=folder(c).toPath();
        try{
            if(createDirectory){try{Files.createDirectory(dir);}catch(FileAlreadyExistsException existing){/* verify below */}}
            BasicFileAttributes a=Files.readAttributes(dir,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!a.isDirectory()||a.isSymbolicLink())throw new BatchFence.Uncertain("批次目录不可安全读取。");
            return dir.resolve(id+".json");
        }catch(BatchFence.Uncertain e){throw e;}
        catch(IOException|SecurityException e){throw new BatchFence.Uncertain("无法确认批次目录。",e);}
    }
    private static void write(Context c,Batch b,boolean fresh)throws Exception{
        byte[] bytes=b.json().toString(2).getBytes(StandardCharsets.UTF_8);if(bytes.length>512*1024)throw new IOException("本次拍摄记录过大，请整理后开始新批次");
        if(UNCERTAIN.contains(b.id))throw new BatchFence.Uncertain("本次批次记录仍待核对。");
        UNCERTAIN.add(b.id);
        try{
            java.nio.file.Path path=base(c,b.id,fresh);
            BatchFence.begin(path,fresh,BatchFence.NONE);
            requireNotInterrupted();
            AtomicFile f=new AtomicFile(path.toFile());
            // Never call failWrite: it can remove unresolved candidates.
            try(FileOutputStream out=f.startWrite()){out.write(bytes);f.finishWrite(out);}
            BatchFence.confirm(path,bytes,BatchFence.NONE);
            UNCERTAIN.remove(b.id);
        }catch(Exception e){
            if(e instanceof BatchFence.Uncertain)throw e;
            throw new BatchFence.Uncertain("批次发布或校验未确认，请勿自动重试。",e);
        }
    }
    static Batch create(Context c,String doc)throws Exception{try(BatchLease batchLease=acquireBatch()){Store.safeId(doc);Batch b=new Batch();b.id=UUID.randomUUID().toString();b.document=doc;b.created=System.currentTimeMillis();write(c,b,true);return b;}}
    static Batch load(Context c,String id)throws Exception{try(BatchLease batchLease=acquireBatch()){
        if(UNCERTAIN.contains(id))throw new BatchFence.Uncertain("本次批次记录仍待核对。");
        byte[] raw;try{raw=BatchFence.readConfirmed(base(c,id,false));}catch(BatchFence.Uncertain e){throw e;}catch(IOException e){throw new BatchFence.Uncertain("批次记录无法读取，原图及候选保留。",e);}JSONObject o;
        try{StrictJsonValidator.validate(raw);o=new JSONObject(new String(raw,StandardCharsets.UTF_8));Batch b=new Batch();b.revision=Store.hex(MessageDigest.getInstance("SHA-256").digest(raw));b.id=o.getString("id");b.document=o.getString("document");Store.safeId(b.document);if(!id.equals(b.id)||o.getInt("version")!=1)throw new IOException("连续扫描记录无效");b.created=o.getLong("created");b.closed=o.optBoolean("closed");JSONArray a=o.getJSONArray("shots");HashSet<String> names=new HashSet<>();for(int i=0;i<a.length();i++){JSONObject v=a.getJSONObject(i);Shot s=new Shot();s.name=v.getString("name");captureId(s.name);if(!names.add(s.name))throw new IOException("连续扫描记录包含重复拍摄编号");s.sha=v.optString("sha256");if(!s.sha.isEmpty()&&!s.sha.matches("[a-f0-9]{64}"))throw new IOException("拍摄校验值无效");s.bytes=v.optLong("bytes");s.page=v.optString("page");if(!s.page.isEmpty())Store.safeId(s.page);b.shots.add(s);}return b;
        }catch(Exception e){throw new BatchFence.Uncertain("批次记录损坏或归属未确认；原图及候选均保留。",e);}
    }}
    static String captureId(String name)throws IOException{
        if(!CameraInbox.captureName(name))throw new IOException("拍摄编号无效");String id=name.substring(8,name.length()-4);try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw new IOException("拍摄编号无效",e);}return id;
    }
    static Shot reserve(Context c,String id)throws Exception{try(BatchLease batchLease=acquireBatch()){Batch b=load(c,id);if(b.closed)throw new IOException("此批次已整理，请开始新批次");if(b.shots.size()>=200)throw new IOException("本次已记录200次拍摄，请先整理这些页面");Shot s=new Shot();s.name="capture-"+UUID.randomUUID()+".jpg";CameraInbox.create(c,s.name);b.shots.add(s);write(c,b,false);return s;}}
    private static Shot shot(Batch b,String name)throws IOException{for(Shot s:b.shots)if(s.name.equals(name))return s;throw new IOException("照片不属于此批次");}
    static String hash(File f)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new BufferedInputStream(new FileInputStream(f))){byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("已停止，拍照原文件保留");d.update(bytes,0,n);}}return Store.hex(d.digest());}
    /** Follow top-level JPEG markers; EXIF thumbnail EOI cannot validate a truncated main scan. */
    // Legacy callers retain the false-on-failure contract; interruption remains set.
    static boolean completeJpeg(File f){try{return completeJpegInterruptibly(f);}catch(IOException failure){return false;}}
    /** Bounded cooperative cancellation; never interpret cancellation as corruption. */
    static boolean completeJpegInterruptibly(File f)throws IOException{
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("JPEG 检查已取消；原文件保留");
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(f),65536))){
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("JPEG 检查已取消；原文件保留");
            if(in.readUnsignedShort()!=0xffd8)return false;boolean scan=false,seenScan=false;int checked=0;
            for(;;){int marker;
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("JPEG 检查已取消；原文件保留");
                if(scan){int x;do{x=in.readUnsignedByte();if((++checked&8191)==0&&Thread.currentThread().isInterrupted())throw new InterruptedIOException("JPEG 检查已取消；原文件保留");}while(x!=0xff);do{marker=in.readUnsignedByte();if((++checked&8191)==0&&Thread.currentThread().isInterrupted())throw new InterruptedIOException("JPEG 检查已取消；原文件保留");}while(marker==0xff);if(marker==0||marker>=0xd0&&marker<=0xd7)continue;scan=false;}
                else{if(in.readUnsignedByte()!=0xff)return false;do{marker=in.readUnsignedByte();if((++checked&8191)==0&&Thread.currentThread().isInterrupted())throw new InterruptedIOException("JPEG 检查已取消；原文件保留");}while(marker==0xff);}
                if(marker==0xd9)return seenScan;if(marker==0xd8||marker==0)return false;if(marker==1||marker>=0xd0&&marker<=0xd7)continue;
                int length=in.readUnsignedShort();if(length<2)return false;int left=length-2;while(left>0){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("JPEG 检查已取消；原文件保留");int skipped=in.skipBytes(left);if(skipped==0)throw new EOFException();left-=skipped;}
                if(marker==0xda){seenScan=true;scan=true;}
            }
        }catch(InterruptedIOException e){throw e;}catch(EOFException truncated){return false;}
    }
    private static void requireNotInterrupted()throws InterruptedIOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("连续扫描处理已取消；原文件和记录保留");}
    static Batch complete(Context c,String id,String name)throws Exception{
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("照片检查已取消；原文件保留");
        File f=CameraInbox.file(c,name);BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(f.getPath(),o);
        if(o.outWidth<2||o.outHeight<2||!completeJpegInterruptibly(f))throw new IOException("照片尚未完整保存；原文件保留，可稍后找回");String sha=hash(f);long bytes=f.length();
        try(BatchLease batchLease=acquireBatch()){requireNotInterrupted();Batch b=load(c,id);Shot s=shot(b,name);if(!s.sha.isEmpty()&&(!s.sha.equals(sha)||s.bytes!=bytes))throw new IOException("已保存照片发生变化，未覆盖原记录");s.sha=sha;s.bytes=bytes;requireNotInterrupted();write(c,b,false);return b;}
    }
    static Batch recover(Context c,String id)throws Exception{requireNotInterrupted();Batch b=load(c,id);for(Shot s:b.shots){requireNotInterrupted();if(s.sha.isEmpty()){
        File f=CameraInbox.file(c,s.name);BasicFileAttributes attrs=Files.readAttributes(f.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attrs.isRegularFile())throw new IOException("照片原文件无法确认；批次与候选均保留。");
        if(attrs.size()>0&&completeJpegInterruptibly(f))complete(c,id,s.name);
    }}requireNotInterrupted();Batch result=load(c,id);requireNotInterrupted();return result;}
    static Batch imported(Context c,String id,String name,String page)throws Exception{try(BatchLease batchLease=acquireBatch()){requireNotInterrupted();Batch b=load(c,id);Shot s=shot(b,name);Store.safeId(page);if(s.sha.isEmpty())throw new IOException("照片尚未保存完成");s.page=page;requireNotInterrupted();write(c,b,false);return b;}}
    static void close(Context c,String id)throws Exception{recover(c,id);try(BatchLease batchLease=acquireBatch()){requireNotInterrupted();Batch b=load(c,id);for(Shot s:b.shots)if(!s.sha.isEmpty()&&s.page.isEmpty())throw new IOException("还有拍照未导入，记录与原文件保留");b.closed=true;requireNotInterrupted();write(c,b,false);}}
    static Models.Document importAll(Store store,String id,Exporter.Progress progress)throws Exception{return importAll(store,id,null,progress);}
    /** The optional owner constrains an explicitly selected recovery to its displayed document. */
    static Models.Document importAll(Store store,String id,String expectedDocument,Exporter.Progress progress)throws Exception{
        if(expectedDocument!=null){Store.safeId(expectedDocument);Batch before=load(store.context,id);if(!expectedDocument.equals(before.document))throw new IOException("批次归属已变化，请重新查找并核对原文件");}
        Batch b=expectedDocument==null?recover(store.context,id):recoverClosed(store.context,id,expectedDocument);if(expectedDocument!=null&&!expectedDocument.equals(b.document))throw new IOException("批次归属已变化，请重新查找并核对原文件");int done=0,total=0;for(Shot s:b.shots)if(!s.sha.isEmpty()&&(expectedDocument==null||s.page.isEmpty()))total++;Models.Document doc=store.load(b.document);
        for(Shot shot:b.shots){
            if(shot.sha.isEmpty())continue;
            if(expectedDocument!=null&&!shot.page.isEmpty()){verifyRegisteredPage(store,doc,shot);continue;}
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("已停止整理；已保存页面和拍照原文件保留，可重试");
            if(expectedDocument!=null&&repairMissingInboxReceipt(store,id,expectedDocument,shot)){progress.update(++done,total);continue;}
            if(!hash(CameraInbox.file(store.context,shot.name)).equals(shot.sha))throw new IOException("拍照原文件校验不符，未覆盖文稿");
            Store.CaptureImport item=store.importCapture(b.document,shot.name,shot.sha);doc=item.document;
            if(item.added){try{item.page.quad=Renderer.detect(store.original(doc,item.page));if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("已停止整理");store.saveIfCurrent(doc);}catch(ManifestFence.Uncertain uncertain){throw uncertain;}catch(java.util.concurrent.CancellationException|IOException e){if(Thread.currentThread().isInterrupted())throw e;item.page.quad=org.wangshu.scan.core.Geometry.full();}}
            imported(store.context,id,shot.name,item.page.id);
            android.net.Uri uri=android.net.Uri.parse("content://"+store.context.getPackageName()+".files/camera/"+shot.name);
            if(!CameraInbox.markImported(store.context,uri))throw new IOException("文稿页面已保存，但拍照收件箱状态未保存；批次与原文件保留，请重试整理");
            progress.update(++done,total);
        }
        if(expectedDocument!=null){Batch end=load(store.context,id);if(!end.closed||!expectedDocument.equals(end.document))throw new IOException("批次状态已变化；已保存页面和原图保留，请重新核对");return store.load(expectedDocument);}
        close(store.context,id);return store.load(b.document);
    }
    /** A missing inbox file is not enough: both locations and the committed page must be verified. */
    private static boolean repairMissingInboxReceipt(Store store,String batchId,String document,Shot snapshot)throws Exception{
        synchronized(Store.FILE_LOCK){
            requireNotInterrupted();boolean exists=false;
            for(File dir:new File[]{CameraInbox.directory(store.context),new File(store.context.getCacheDir(),"camera")}){
                try{BasicFileAttributes attrs=Files.readAttributes(new File(dir,snapshot.name).toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                    if(!attrs.isRegularFile()||attrs.isSymbolicLink())throw new IOException("拍照原文件类型无法确认；请核对记录与原文件");exists=true;
                }catch(NoSuchFileException missing){/* Only confirmed absence is eligible for receipt repair. */}
            }
            if(exists)return false;
            String identity=captureId(snapshot.name);Shot probe=new Shot();probe.name=snapshot.name;probe.sha=snapshot.sha;probe.page=identity;
            verifyRegisteredPage(store,store.load(document),probe);
            try(BatchLease batchLease=acquireBatch()){
                requireNotInterrupted();Batch current=load(store.context,batchId);Shot actual=shot(current,snapshot.name);
                if(!current.closed||!document.equals(current.document)||!snapshot.sha.equals(actual.sha))throw new IOException("批次状态已变化；请重新核对");
                if(actual.page.isEmpty())imported(store.context,batchId,actual.name,identity);
                else if(!identity.equals(actual.page))throw new IOException("批次收据与已有页面不一致；请先核对");
            }
            return true;
        }
    }
    /** An existing receipt is skipped only after the document's own original is verified. */
    private static void verifyRegisteredPage(Store store,Models.Document doc,Shot shot)throws Exception{
        requireNotInterrupted();String identity=captureId(shot.name),relative="originals/"+identity+".image";Models.Page found=null;
        for(Models.Page page:doc.pages){requireNotInterrupted();if(page.id.equals(shot.page)||page.id.equals(identity)||page.file.equals(relative)){
            if(found!=null||!page.id.equals(shot.page)||!page.id.equals(identity)||!page.file.equals(relative)||!shot.sha.equals(page.sha256))throw new IOException("已有页面与批次记录不一致；原图及记录保留，请先核对");found=page;
        }}
        if(found==null)throw new IOException("批次标记的已有页面不存在；请核对文稿与拍照收件箱");
        File original=store.original(doc,found);verifyOriginalDirectories(original);BasicFileAttributes attrs=Files.readAttributes(original.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attrs.isRegularFile()||attrs.isSymbolicLink()||!shot.sha.equals(hash(original)))throw new IOException("已有页面原图无法核实；请核对文稿与拍照收件箱");
    }
    /** Narrow the recovery trust boundary; separate checks do not prevent later cross-process swaps. */
    private static void verifyOriginalDirectories(File original)throws IOException{
        File originals=original.getParentFile(),document=originals==null?null:originals.getParentFile(),root=document==null?null:document.getParentFile();
        if(originals==null||document==null||root==null||!"originals".equals(originals.getName()))throw new IOException("原图目录无法确认");
        for(File directory:new File[]{root,document,originals}){
            BasicFileAttributes attrs=Files.readAttributes(directory.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!attrs.isDirectory()||attrs.isSymbolicLink())throw new IOException("文稿原图目录无法确认；请核对存储位置");
        }
    }
    /** A closed session is never reopened as the active camera session. */
    static final class ClosedCandidate {
        final String id,document,revision;final long created;final int received,unverified;
        ClosedCandidate(Batch batch,int received,int unverified){this.id=batch.id;this.document=batch.document;this.revision=batch.revision;this.created=batch.created;this.received=received;this.unverified=unverified;}
    }
    /** Read only: unknown records block enumeration; no preference, raw file or journal is changed. */
    static List<ClosedCandidate> closedPending(Context c,String document)throws Exception{try(BatchLease batchLease=acquireBatch()){
        requireNotInterrupted();Store.safeId(document);BatchFence.Candidates candidates=BatchFence.enumerate(folder(c).toPath());
        if(!UNCERTAIN.isEmpty())throw new BatchFence.Uncertain("存在无法确认的批次，原图与记录保留。");
        ArrayList<ClosedCandidate> result=new ArrayList<>();
        for(String id:candidates.ids){
            requireNotInterrupted();Batch b=load(c,id);if(!b.closed||!document.equals(b.document))continue;int received=0,unverified=0;
            for(Shot shot:b.shots){requireNotInterrupted();if(!shot.sha.isEmpty()){if(shot.page.isEmpty())received++;continue;}
                Path raw=CameraInbox.file(c,shot.name).toPath();BasicFileAttributes attrs;
                try{attrs=Files.readAttributes(raw,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}
                catch(NoSuchFileException missing){unverified++;continue;}
                if(!attrs.isRegularFile()||attrs.isSymbolicLink())throw new IOException("拍照原文件类型无法确认；请核对记录与原文件");
                if(attrs.size()>0)unverified++;
            }
            if(received>0||unverified>0)result.add(new ClosedCandidate(b,received,unverified));
        }
        result.sort((a,b)->{int byTime=Long.compare(b.created,a.created);return byTime!=0?byTime:a.id.compareTo(b.id);});
        requireNotInterrupted();return result;
    }}
    /** Missing raw reservations remain recorded; only their exact absence can be skipped. */
    private static Batch recoverClosed(Context c,String id,String document)throws Exception{try(BatchLease batchLease=acquireBatch()){
        requireNotInterrupted();Batch b=load(c,id);if(!b.closed||!document.equals(b.document))throw new IOException("批次状态已变化，请重新查找并核对原文件");
        for(Shot shot:b.shots){requireNotInterrupted();if(!shot.sha.isEmpty())continue;File raw=CameraInbox.file(c,shot.name);BasicFileAttributes attrs;
            try{attrs=Files.readAttributes(raw.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}catch(NoSuchFileException absent){continue;}
            if(!attrs.isRegularFile()||attrs.isSymbolicLink())throw new IOException("拍照原文件类型无法确认；原图与记录保留");
            if(attrs.size()>0&&completeJpegInterruptibly(raw))complete(c,id,shot.name);
        }
        Batch end=load(c,id);if(!end.closed||!document.equals(end.document))throw new IOException("批次状态已变化，请重新查找并核对原文件");return end;
    }}
    /** Revalidate the selected closed journal before recovering any receipt. */
    static void prepareClosed(Context c,String id,String document,String revision)throws Exception{try(BatchLease batchLease=acquireBatch()){
        requireNotInterrupted();Store.safeId(document);if(revision==null||!revision.matches("[a-f0-9]{64}"))throw new IOException("批次校验信息无效，请重新查找");
        Batch b=load(c,id);if(!b.closed||!document.equals(b.document)||!revision.equals(b.revision))throw new IOException("批次已变化，请重新查找；原图与记录保留");
        b=recoverClosed(c,id,document);if(!b.closed||!document.equals(b.document))throw new IOException("批次归属已变化，请重新查找；原图与记录保留");
        for(Shot shot:b.shots){requireNotInterrupted();if(!shot.sha.isEmpty()&&shot.page.isEmpty())return;}
        throw new IOException("尚无可确认的新照片；批次记录未删除，请检查拍照收件箱及原来源");
    }}
    static Batch latestOpen(Context c,String document)throws Exception{try(BatchLease batchLease=acquireBatch()){
        requireNotInterrupted();Store.safeId(document);BatchFence.Candidates candidates=BatchFence.enumerate(folder(c).toPath());requireNotInterrupted();
        if(candidates.directoryMissing){if(!UNCERTAIN.isEmpty())throw new BatchFence.Uncertain("批次保护结果未知。");return null;}
        Batch best=null;
        for(String id:candidates.ids){
            requireNotInterrupted();Batch b;try{b=load(c,id);}catch(InterruptedIOException cancelled){throw cancelled;}catch(BatchFence.Uncertain e){throw e;}catch(Exception e){throw new BatchFence.Uncertain("存在无法验证或归属未知的批次。",e);}
            if(!b.closed&&b.document.equals(document)&&(best==null||b.created>best.created))best=b;
        }
        if(!UNCERTAIN.isEmpty())throw new BatchFence.Uncertain("存在无法确认的批次，原图与记录保留。");
        requireNotInterrupted();return best;
    }}
}
