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
        } finally {if(tmp.exists())tmp.delete();}
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
        File stage=new File(context.getCacheDir(),"restore-"+UUID.randomUUID());stage.mkdirs();
        try {
            HashSet<String> names=new HashSet<>();long total=0;int count=0;
            try(ZipInputStream zip=new ZipInputStream(input)){
                ZipEntry entry;byte[] buffer=new byte[65536];
                while((entry=zip.getNextEntry())!=null){
                    if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("备份恢复已中断，原有文稿未改变");
                    if(entry.isDirectory())continue;String name=entry.getName();
                    if(!name.equals("manifest.json")&&!name.matches("originals/[a-f0-9-]{36}\\.image"))throw new IOException("备份包含不支持的路径");
                    if(!names.add(name)||++count>1001)throw new IOException("备份包含重复或过多文件");
                    File outFile=new File(stage,name);outFile.getParentFile().mkdirs();long size=0;
                    try(OutputStream out=new FileOutputStream(outFile)){int n;while((n=zip.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("备份恢复已中断，原有文稿未改变");size+=n;total+=n;if(total>512L*1024*1024||size>100L*1024*1024||(name.equals("manifest.json")&&size>2*1024*1024))throw new IOException("备份超过安全尺寸限制");out.write(buffer,0,n);}}
                }
            }
            File manifest=new File(stage,"manifest.json");if(!manifest.isFile())throw new IOException("备份缺少文稿参数");
            Models.Document restored;
            try(InputStream in=new FileInputStream(manifest)){restored=Models.Document.from(new JSONObject(new String(readLimited(in,2*1024*1024),StandardCharsets.UTF_8)));}
            HashSet<String> pageNames=new HashSet<>();
            for(Models.Page p:restored.pages){
                safeId(p.id);if(!p.file.equals("originals/"+p.id+".image")||!pageNames.add(p.file))throw new IOException("备份页面路径无效");
                File original=new File(stage,p.file);if(!original.isFile())throw new IOException("备份缺少原图");MessageDigest sha=MessageDigest.getInstance("SHA-256");
                try(InputStream in=new FileInputStream(original)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)sha.update(b,0,n);}
                if(!hex(sha.digest()).equals(p.sha256))throw new IOException("备份原图校验失败");
                BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(original.getPath(),options);if(options.outWidth<2||options.outHeight<2||(long)options.outWidth*options.outHeight>150_000_000L)throw new IOException("备份图片无法安全读取");
            }
            restored.id=UUID.randomUUID().toString();restored.archived=false;restored.title+="（恢复）";
            File originals=new File(dir(restored),"originals");originals.mkdirs();
            for(Models.Page p:restored.pages)java.nio.file.Files.copy(new File(stage,p.file).toPath(),new File(dir(restored),p.file).toPath());
            save(restored);return restored;
        }finally{removeTemporary(stage);}
    }
    private static void removeTemporary(File f){File[] children=f.listFiles();if(children!=null)for(File c:children)removeTemporary(c);f.delete();}
    private static String snapshotDigest(JSONObject value)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8)));}
    static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b));return s.toString();}
}
