package org.wangshu.scan;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Originals are immutable; a durable manifest commit makes each edit visible. */
final class Store {
    // AtomicFile requires serialization across Activity-created Store instances.
    static final Object FILE_LOCK=new Object();
    private static final Object CAPTURE_IMPORT_LOCK=new Object();
    final File root;
    final Context context;
    final ArrayList<String> warnings=new ArrayList<>();
    Store(Context c) {context=c;root=new File(c.getFilesDir(),"documents");root.mkdirs();}
    static void safeId(String id) throws IOException {if(!id.matches("[a-f0-9-]{36}"))throw new IOException("Invalid document identifier");}
    File dir(Models.Document d) throws IOException {safeId(d.id);File f=new File(root,d.id);if(!f.isDirectory()&&!f.mkdirs())throw new IOException("无法创建文稿目录");return f;}
    File original(Models.Document d,Models.Page p) throws IOException {
        File base=dir(d),f=new File(base,p.file);
        if(!f.getCanonicalPath().startsWith(new File(base,"originals").getCanonicalPath()+File.separator))throw new IOException("Invalid image path");
        return f;
    }
    synchronized ArrayList<Models.Document> list() {
        ArrayList<Models.Document> docs=new ArrayList<>();warnings.clear();File[] dirs=root.listFiles();if(dirs==null)return docs;
        for(File f:dirs)if(f.isDirectory()&&!f.getName().startsWith(".")){
            try{Models.Document d=load(f.getName());if(!d.archived)docs.add(d);}catch(Exception e){warnings.add(f.getName()+": "+e.getMessage());}
        }
        docs.sort((a,b)->Long.compare(b.updated,a.updated));return docs;
    }
    Models.Document load(String id) throws Exception {
        synchronized(FILE_LOCK){
            safeId(id);File base=new File(new File(root,id),"manifest.json");
            byte[] bytes=ManifestFence.readConfirmed(base);
            Models.Document d=Models.Document.from(new JSONObject(new String(bytes,StandardCharsets.UTF_8)));
            if(!d.id.equals(id))throw new IOException("Manifest identifier mismatch");d.savedSnapshot=snapshotDigest(d.json());return d;
        }
    }
    void save(Models.Document d) throws Exception {
        synchronized(FILE_LOCK){
            if(d.manifestUncertain)throw new ManifestFence.Uncertain("该文稿已有未确认操作，未再次写入。");
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("保存已取消，尚未写入；原图保留。");
            long beforeUpdated=d.updated;d.updated=System.currentTimeMillis();
            byte[] bytes;String savedSnapshot;
            try{
                JSONObject snapshot=d.json();savedSnapshot=snapshotDigest(snapshot);
                bytes=snapshot.toString(2).getBytes(StandardCharsets.UTF_8);
                if(bytes.length>2*1024*1024)throw new IOException("文稿参数超过 2 MB，请拆分文稿后保存");
            }catch(Exception|Error preflight){d.updated=beforeUpdated;throw preflight;}
            File base;
            try{base=new File(dir(d),"manifest.json");}catch(Exception|Error preflight){d.updated=beforeUpdated;throw preflight;}
            // Once guard creation begins even its own outcome can be uncertain.
            // Never invoke AtomicFile.failWrite after that point: it can erase .new.
            d.manifestUncertain=true;
            try{
                ManifestFence.begin(base);
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("保存已中断，待核对的记录均保留。");
                AtomicFile atomic=new AtomicFile(base);FileOutputStream out=null;
                try{out=atomic.startWrite();out.write(bytes);out.getFD().sync();if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("保存已中断，待核对的记录均保留。");atomic.finishWrite(out);out=null;}
                finally{if(out!=null)try{out.close();}catch(IOException ignored){/* fence remains pending */}}
                ManifestFence.confirm(base,bytes);
                d.savedSnapshot=savedSnapshot;d.manifestUncertain=false;
            }catch(ManifestFence.Uncertain uncertain){throw uncertain;}
            catch(Exception failure){throw new ManifestFence.Uncertain("发布前后出现异常，候选文件未清理。",failure);}
        }
    }
    static void requireCertain(Models.Document d)throws ManifestFence.Uncertain{
        if(d==null||d.manifestUncertain)throw new ManifestFence.Uncertain("该文稿已有未确认操作，未继续修改。");
    }
    /** Off-main-thread preflight for operations that would otherwise present an uncertain draft. */
    void assertCurrentReadable(Models.Document d)throws Exception{
        synchronized(FILE_LOCK){requireCertain(d);Models.Document current=load(d.id);
            if(d.savedSnapshot.isEmpty()||!current.savedSnapshot.equals(d.savedSnapshot))throw new ManifestFence.Uncertain("该文稿的已保存版本已变化，请先重新核对。");}
    }
    /** Worker-only: pin an export copy after a non-destructive manifest/guard check. */
    Models.Document snapshotForExport(Models.Document d)throws Exception{
        synchronized(FILE_LOCK){assertCurrentReadable(d);return Models.Document.from(d.json());}
    }
    /** Fail closed if another Store committed after this document was loaded or saved. */
    void saveIfCurrent(Models.Document d)throws Exception{
        synchronized(FILE_LOCK){
            if(d.manifestUncertain)throw new ManifestFence.Uncertain("该文稿已有未确认操作，未再次写入。");
            Models.Document current=load(d.id);
            if(d.savedSnapshot.isEmpty()||!current.savedSnapshot.equals(d.savedSnapshot))throw new IOException("文稿在处理期间已变化；本次更改未保存，请重新打开文稿后重试，原图保留");
            save(d);
        }
    }
    Models.Document create() throws Exception {
        Models.Document d=new Models.Document();d.title="扫描 "+new java.text.SimpleDateFormat("MM-dd HH.mm",Locale.CHINA).format(new Date());save(d);return d;
    }
    Models.Page importUri(Models.Document d,Uri uri) throws Exception {
        requireCertain(d);
        try(InputStream in=context.getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("无法读取图片");return importStream(d,in);}
    }
    synchronized Models.Page importStream(Models.Document d,InputStream in) throws Exception {
        requireCertain(d);
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("图片导入已中断，已保存原图仍保留");
        Models.Page p=new Models.Page();File originals=new File(dir(d),"originals");originals.mkdirs();
        File tmp=new File(originals,p.id+".part"),dst=new File(originals,p.id+".image");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try {
            try(FileOutputStream out=new FileOutputStream(tmp)){
                byte[] buffer=new byte[65536];long size=0;int n;
                while((n=in.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("图片导入已中断，已保存原图仍保留");size+=n;if(size>100L*1024*1024)throw new IOException("单张图片超过 100 MB");out.write(buffer,0,n);digest.update(buffer,0,n);}
                out.getFD().sync();
            }
            // Bounds decoding alone can accept a JPEG cut off before its end marker.
            CameraInbox.requireCompleteIfJpeg(tmp);
            BitmapFactory.Options opts=new BitmapFactory.Options();opts.inJustDecodeBounds=true;BitmapFactory.decodeFile(tmp.getPath(),opts);
            if(opts.outWidth<2||opts.outHeight<2)throw new IOException("图片格式不支持或文件损坏");
            if((long)opts.outWidth*opts.outHeight>150_000_000L)throw new IOException("图片尺寸超过安全处理范围");
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("图片导入已中断，已保存原图仍保留");
            // Serialize publication of the final .image pathname with the
            // manifest commit. Orphan recovery may scan concurrently, but cannot
            // claim this image before its original import finishes the commit.
            // If a later edit won, the fully copied image remains recoverable.
            synchronized(FILE_LOCK){
                if(!tmp.renameTo(dst))throw new IOException("无法保存原图");
                Models.Document current=load(d.id);
                if(d.savedSnapshot.isEmpty()||!current.savedSnapshot.equals(d.savedSnapshot))throw new IOException("文稿在导入期间已变化；原图已保留，请重新打开文稿，再从“更多”找回原图并检查页面");
                p.file="originals/"+dst.getName();p.sha256=hex(digest.digest());d.pages.add(p);
                try{save(d);}catch(ManifestFence.Uncertain uncertain){throw uncertain;}catch(Exception e){d.pages.remove(p);throw e;}
            }
            return p;
        } catch (InterruptedIOException interrupted) {
            InterruptedIOException failure=new InterruptedIOException("图片导入中断；已接收的暂存保留在应用私有目录，可能不完整，目前不能在界面找回");failure.initCause(interrupted);throw failure;
        } catch (ManifestFence.Uncertain uncertain) {
            throw uncertain;
        } catch (Exception failure) {
            String detail=failure.getMessage()==null?"请检查原文件与存储空间":failure.getMessage();
            String retained=dst.exists()?"原图已保存，可重新打开文稿后从“更多”找回原图":"若已接收数据，其暂存保留在应用私有目录，可能不完整，目前不能在界面找回";
            throw new IOException("图片导入失败："+detail+"；"+retained,failure);
        }
    }
    static final class CaptureImport {
        final Models.Document document;final Models.Page page;final boolean added;
        CaptureImport(Models.Document d,Models.Page p,boolean a){document=d;page=p;added=a;}
    }
    CaptureImport importCapture(String document,String name)throws Exception{return importCapture(document,name,null);}
    CaptureImport importCapture(String document,String name,String expectedSha)throws Exception{
        if(expectedSha!=null&&!expectedSha.matches("[a-f0-9]{64}"))throw new IOException("拍摄记录校验值无效；原文件保留");
        String identity=CaptureBatch.captureId(name);File source=CameraInbox.file(context,name);
        synchronized(CAPTURE_IMPORT_LOCK){
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("已停止整理，拍照原文件保留");
            // Refuse a known-incomplete source before creating another partial copy;
            // recheck staged bytes below because the source can still change.
            CameraInbox.requireCompleteIfJpeg(source);
            String sha=CaptureBatch.hash(source);if(expectedSha!=null&&!expectedSha.equals(sha))throw new IOException("拍照原文件与批次收据不一致；未导入，原文件保留");Models.Document d=load(document);String relative="originals/"+identity+".image";
            // A manifest receipt alone cannot establish that its original still
            // exists and matches. Verify/rebuild the copy below before returning
            // an existing page; never overwrite a conflicting existing file.
            for(Models.Page p:d.pages)if(p.id.equals(identity)||p.file.equals(relative)){if(!sha.equals(p.sha256)||!identity.equals(p.id)||!relative.equals(p.file))throw new IOException("拍摄编号与已有原图记录不一致，未覆盖");}
            File originals=new File(dir(d),"originals");if(!originals.isDirectory()&&!originals.mkdirs())throw new IOException("无法准备原图目录");File dst=new File(originals,identity+".image");
            if(dst.exists()){if(!CaptureBatch.hash(dst).equals(sha))throw new IOException("已有不同原图，未覆盖");CameraInbox.requireCompleteIfJpeg(dst);}
            else{
                File temporary=new File(originals,identity+"-"+UUID.randomUUID()+".part");
                try{
                    try(InputStream in=new FileInputStream(source);FileOutputStream out=new FileOutputStream(temporary)){byte[] bytes=new byte[65536];long total=0;int n;while((n=in.read(bytes))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("已停止整理，原图保留");total+=n;if(total>100L*1024*1024)throw new IOException("单张照片超过100MB");out.write(bytes,0,n);}out.getFD().sync();}
                    if(!CaptureBatch.hash(temporary).equals(sha))throw new IOException("原图复制校验失败，拍照原文件保留");
                    CameraInbox.requireCompleteIfJpeg(temporary);
                    if(!temporary.renameTo(dst))throw new IOException("无法保存拍照副本");
                }finally{/* Preserve incomplete private staging bytes for recovery; never delete the source. */}
            }
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(dst.getPath(),bounds);if(bounds.outWidth<2||bounds.outHeight<2||(long)bounds.outWidth*bounds.outHeight>150_000_000L)throw new IOException("拍照尺寸无法处理，原文件保留");
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("已停止整理，原图和记录保留");
            synchronized(FILE_LOCK){
                d=load(document);for(Models.Page p:d.pages)if(p.id.equals(identity)||p.file.equals(relative)){if(!sha.equals(p.sha256)||!identity.equals(p.id)||!relative.equals(p.file))throw new IOException("已有不同原图，未覆盖");return new CaptureImport(d,p,false);}
                Models.Page p=new Models.Page();p.id=identity;p.file=relative;p.sha256=sha;d.pages.add(p);save(d);return new CaptureImport(d,p,true);
            }
        }
    }
    static byte[] readLimited(InputStream in,int limit) throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n,total=0;while((n=in.read(b))!=-1){total+=n;if(total>limit)throw new IOException("文件过大");out.write(b,0,n);}return out.toByteArray();}
    synchronized int recoverOrphans(Models.Document d)throws Exception{
        requireCertain(d);
        HashSet<String> known=new HashSet<>();for(Models.Page p:d.pages)known.add(p.file);File folder=new File(dir(d),"originals");File[] files=folder.listFiles();if(files==null)return 0;Arrays.sort(files,Comparator.comparing(File::getName));ArrayList<Models.Page> recovered=new ArrayList<>();
        for(File f:files)if(f.getName().matches("[a-f0-9-]{36}\\.image")&&!known.contains("originals/"+f.getName())){
            // Leave a suspect original untouched and report the problem instead
            // of promoting it to a page solely because its header has dimensions.
            CameraInbox.requireCompleteIfJpeg(f);
            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(f.getPath(),options);if(options.outWidth<2||options.outHeight<2||(long)options.outWidth*options.outHeight>150_000_000L)continue;
            MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)hash.update(buffer,0,n);}
            Models.Page p=new Models.Page();p.id=f.getName().substring(0,36);p.file="originals/"+f.getName();p.sha256=hex(hash.digest());recovered.add(p);
        }
        synchronized(FILE_LOCK){
            Models.Document current=load(d.id);
            if(d.savedSnapshot.isEmpty()||!current.savedSnapshot.equals(d.savedSnapshot))throw new IOException("文稿已在其他页面更改，请重新打开文稿再找回原图；所有原图保留");
            return DocumentMutations.appendRecovered(d,recovered,this::save);
        }
    }
    synchronized Models.Document restore(InputStream input) throws Exception {
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("备份恢复已中断，原有文稿未改变");
        // A cache directory can be evicted before a one-shot source can be read
        // again. Keep failed, possibly partial extraction in private files instead.
        // This is a conservative estimate, not a reservation: writes still may fail.
        // Fail before consuming a one-shot source when even the receive reserve is unavailable.
        requireRestoreSpace(context.getFilesDir(),1);
        File stage=new File(context.getFilesDir(),"restore-intake/restore-"+UUID.randomUUID());
        if(!stage.mkdirs())throw new IOException("无法创建备份恢复暂存目录");
        try {
            // Preserve the one-shot archive before extraction. This extra copy is
            // bounded independently of the uncompressed-entry limit below.
            File raw=new File(stage,"received.zip");
            if(!raw.createNewFile())throw new IOException("备份暂存位置已存在，未覆盖");
            try(FileOutputStream out=new FileOutputStream(raw)){
                try{
                    byte[] buffer=new byte[65536];long size=0;
                    while(true){
                        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("备份恢复已中断");
                        // Limit the read to current headroom; do not require a whole
                        // buffer just to discover EOF after a small complete archive.
                        int readLimit=(int)Math.min(buffer.length,requireRestoreSpace(stage,1));
                        int n=input.read(buffer,0,readLimit);if(n==-1)break;
                        if(n==0)throw new IOException("备份来源未返回数据，已停止接收以避免无进展循环");
                        requireRestoreSpace(stage,n);
                        if(size+n>512L*1024*1024)throw new IOException("备份压缩文件超过 512 MiB");
                        out.write(buffer,0,n);size+=n;
                    }
                    out.getFD().sync();
                }catch(IOException failure){try{out.getFD().sync();}catch(IOException syncFailure){failure.addSuppressed(syncFailure);}throw failure;}
            }
            // ZipInputStream alone accepts an archive without a central directory.
            // ZipFile requires one; compare its entry metadata with the local
            // stream and actual extracted data before publishing any pages.
            HashMap<String,ZipEntry> central=new HashMap<>();long declared=0;
            try(ZipFile file=new ZipFile(raw)){
                Enumeration<? extends ZipEntry> entries=file.entries();
                while(entries.hasMoreElements()){
                    ZipEntry item=entries.nextElement();String name=item.getName();
                    if(item.isDirectory()){
                        if(!name.equals("originals/"))throw new IOException("备份包含不支持的路径");
                    }else if(!name.equals("manifest.json")&&!name.matches("originals/[a-f0-9-]{36}\\.image"))throw new IOException("备份包含不支持的路径");
                    if(central.put(name,item)!=null||central.size()>1002)throw new IOException("备份包含重复或过多文件");
                    if(item.getSize()<0||item.getCrc()<0)throw new IOException("备份中央目录不完整");
                    if(!item.isDirectory()){
                        long limit=name.equals("manifest.json")?2L*1024*1024:100L*1024*1024;
                        if(item.getSize()>limit||item.getSize()>512L*1024*1024-declared)throw new IOException("备份超过安全尺寸限制");
                        declared+=item.getSize();
                    }
                }
            }
            // Keep raw + extracted intake. Budget another independent published copy;
            // counting the manifest twice is intentionally conservative.
            requireRestoreSpace(stage,declared*2);
            HashSet<String> names=new HashSet<>();long total=0;int count=0;
            try(ZipInputStream zip=new ZipInputStream(new FileInputStream(raw))){
                ZipEntry entry;byte[] buffer=new byte[65536];
                while((entry=zip.getNextEntry())!=null){
                    if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("备份恢复已中断，原有文稿未改变");
                    String name=entry.getName();ZipEntry listed=central.remove(name);
                    if(listed==null||listed.isDirectory()!=entry.isDirectory()||listed.getMethod()!=entry.getMethod())throw new IOException("备份本地记录与中央目录不一致");
                    if(entry.isDirectory()){
                        if(listed.getSize()!=0||listed.getCrc()!=0||zip.read()!=-1)throw new IOException("备份目录条目无效");
                        continue;
                    }
                    if(!name.equals("manifest.json")&&!name.matches("originals/[a-f0-9-]{36}\\.image"))throw new IOException("备份包含不支持的路径");
                    if(!names.add(name)||++count>1001)throw new IOException("备份包含重复或过多文件");
                    File outFile=new File(stage,name);outFile.getParentFile().mkdirs();long size=0;CRC32 crc=new CRC32();
                    try(OutputStream out=new FileOutputStream(outFile)){int n;while((n=zip.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("备份恢复已中断，原有文稿未改变");size+=n;total+=n;if(total>512L*1024*1024||size>100L*1024*1024||(name.equals("manifest.json")&&size>2*1024*1024))throw new IOException("备份超过安全尺寸限制");requireRestoreSpace(stage,n);out.write(buffer,0,n);crc.update(buffer,0,n);}}
                    if(size!=listed.getSize()||crc.getValue()!=listed.getCrc()||entry.getCompressedSize()!=listed.getCompressedSize())throw new IOException("备份本地记录与中央目录不一致");
                }
            }
            if(!central.isEmpty())throw new IOException("备份缺少中央目录所列文件");
            // Names, sizes and CRC alone do not prove that a malicious central
            // offset points at the same bytes as the sequential local record.
            // Compare SHA-256 of the central view with the extracted local view.
            try(ZipFile file=new ZipFile(raw)){
                Enumeration<? extends ZipEntry> entries=file.entries();long checked=0;
                while(entries.hasMoreElements()){
                    ZipEntry item=entries.nextElement();if(item.isDirectory())continue;
                    MessageDigest fromCentral=MessageDigest.getInstance("SHA-256"),fromLocal=MessageDigest.getInstance("SHA-256");
                    try(InputStream center=file.getInputStream(item);InputStream local=new FileInputStream(new File(stage,item.getName()))){
                        byte[] b=new byte[65536];int n;long size=0;
                        while((n=center.read(b))!=-1){
                            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("备份检查已中断");
                            size+=n;checked+=n;if(size>100L*1024*1024||checked>512L*1024*1024)throw new IOException("备份超过安全尺寸限制");
                            fromCentral.update(b,0,n);
                        }
                        while((n=local.read(b))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("备份检查已中断");fromLocal.update(b,0,n);}
                    }
                    if(!MessageDigest.isEqual(fromCentral.digest(),fromLocal.digest()))throw new IOException("备份本地记录与中央目录内容不一致");
                }
            }
            File manifest=new File(stage,"manifest.json");if(!manifest.isFile())throw new IOException("备份缺少文稿参数");
            Models.Document restored;
            try(InputStream in=new FileInputStream(manifest)){
                byte[] manifestBytes=readLimited(in,2*1024*1024);
                StrictJsonValidator.validate(manifestBytes);
                restored=Models.Document.from(new JSONObject(new String(manifestBytes,StandardCharsets.UTF_8)));
            }
            HashSet<String> pageNames=new HashSet<>();
            for(Models.Page p:restored.pages){
                safeId(p.id);if(!p.file.equals("originals/"+p.id+".image")||!pageNames.add(p.file))throw new IOException("备份页面路径无效");
                File original=new File(stage,p.file);if(!original.isFile())throw new IOException("备份缺少原图");MessageDigest sha=MessageDigest.getInstance("SHA-256");
                try(InputStream in=new FileInputStream(original)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)sha.update(b,0,n);}
                if(!hex(sha.digest()).equals(p.sha256))throw new IOException("备份原图校验失败");
                CameraInbox.requireCompleteIfJpeg(original);
                BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(original.getPath(),options);if(options.outWidth<2||options.outHeight<2||(long)options.outWidth*options.outHeight>150_000_000L)throw new IOException("备份图片无法安全读取");
            }
            // Never discard an unreferenced original when a one-shot archive is
            // accepted and its temporary files are removed after publication.
            HashSet<String> expectedFiles=new HashSet<>(pageNames);expectedFiles.add("manifest.json");
            if(!names.equals(expectedFiles))throw new IOException("备份含未列入文稿的文件，所有已接收数据保留");
            restored.id=UUID.randomUUID().toString();restored.archived=false;restored.title+="（恢复）";
            requireRestoreSpace(stage,declared);
            File originals=new File(dir(restored),"originals");
            if(!originals.isDirectory()&&!originals.mkdirs())throw new IOException("无法创建恢复原图目录");
            for(Models.Page p:restored.pages){
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("备份恢复已中断，原有文稿未改变");
                File source=new File(stage,p.file),destination=new File(dir(restored),p.file);
                long originalBytes=source.length();
                if(originalBytes<=0||originalBytes>100L*1024*1024)throw new IOException("恢复原图大小已变化");
                requireRestoreSpace(stage,originalBytes);
                // Write and force through the same CREATE_NEW handle; never overwrite.
                // Surface write/force errors before saving a manifest, retaining partial bytes.
                try(InputStream in=new FileInputStream(source);java.nio.channels.FileChannel channel=java.nio.channels.FileChannel.open(destination.toPath(),java.nio.file.StandardOpenOption.CREATE_NEW,java.nio.file.StandardOpenOption.WRITE)){
                    byte[] bytes=new byte[65536];int n;long written=0;
                    while((n=in.read(bytes))!=-1){
                        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("备份恢复已中断，原有文稿未改变");
                        written+=n;if(written>originalBytes)throw new IOException("恢复原图大小已变化");
                        requireRestoreSpace(stage,n);java.nio.ByteBuffer chunk=java.nio.ByteBuffer.wrap(bytes,0,n);
                        while(chunk.hasRemaining()){
                            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("备份恢复已中断，原有文稿未改变");
                            // A channel may legally return zero. Stop conservatively rather
                            // than spin/retry indefinitely; raw/extracted originals remain.
                            if(channel.write(chunk)==0)throw new IOException("恢复原图写入无进展，已停止并保留收到的数据");
                        }
                    }
                    channel.force(true);
                }
                // Re-read the destination instead of trusting only the source-side digest.
                MessageDigest copied=MessageDigest.getInstance("SHA-256");long copiedBytes=0;
                try(InputStream in=new FileInputStream(destination)){
                    byte[] bytes=new byte[65536];int n;
                    while((n=in.read(bytes))!=-1){
                        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("备份恢复已中断，原有文稿未改变");
                        copiedBytes+=n;if(copiedBytes>originalBytes)throw new IOException("恢复原图副本长度不符");copied.update(bytes,0,n);
                    }
                }
                if(copiedBytes!=originalBytes||!hex(copied.digest()).equals(p.sha256))throw new IOException("恢复原图副本校验失败");
            }
            // Retain the intake even on success until a symlink-safe,
            // user-visible cleanup path exists; never recursively follow an
            // unexpected link into other app data.
            save(restored);return restored;
        }catch(InterruptedIOException interrupted){
            InterruptedIOException failure=new InterruptedIOException("备份恢复中断；已接收的暂存保留在应用私有目录，可能不完整；可在“查看备份恢复暂存”中核对或导出已收到片段，不代表可以续传或完整恢复");failure.initCause(interrupted);throw failure;
        }catch(ManifestFence.Uncertain uncertain){throw uncertain;
        }catch(Exception failure){String detail=failure.getMessage()==null?"请检查备份与存储空间":failure.getMessage();throw new IOException("备份恢复失败："+detail+"；已接收的暂存保留在应用私有目录，可能不完整；可在“查看备份恢复暂存”中核对或导出已收到片段，不代表可以续传或完整恢复",failure);
        }
    }
    private static long requireRestoreSpace(File filesystem,long extra)throws IOException{
        // getUsableSpace is only a hint and may be zero when capacity is unknown.
        // Fail closed without deleting intake, user documents, or other app data.
        final long reserve=32L*1024*1024;
        long usable=filesystem.getUsableSpace();
        if(extra<0||usable<reserve||extra>usable-reserve)throw new IOException("剩余空间不足或无法核实；恢复还需要额外空间，未自动清理暂存");
        return usable-reserve;
    }
    private static void removeTemporary(File f){File[] children=f.listFiles();if(children!=null)for(File c:children)removeTemporary(c);f.delete();}
    private static String snapshotDigest(JSONObject value)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8)));}
    static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b));return s.toString();}
}
