package org.wangshu.scan;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.util.*;

/** New camera originals are durable app files even if import fails. No automatic deletion. */
final class CameraInbox {
    static File directory(Context c){return new File(c.getFilesDir(),"camera");}
    static boolean captureName(String name){return name!=null&&name.matches("capture-[a-f0-9-]{36}\\.jpg");}
    static File file(Context c,String name){
        if(name==null||!name.matches("[a-zA-Z0-9_.-]+")||name.contains(".."))throw new IllegalArgumentException("Invalid capture name");
        File durable=new File(directory(c),name),legacy=new File(new File(c.getCacheDir(),"camera"),name);
        return durable.exists()||!legacy.exists()?durable:legacy;
    }
    static File create(Context c,String name)throws IOException{
        if(!captureName(name))throw new IOException("拍照文件名无效");File folder=directory(c);if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("无法准备拍照收件箱，请检查存储空间");
        File target=new File(folder,name);if(new File(new File(c.getCacheDir(),"camera"),name).exists()||!target.createNewFile())throw new IOException("拍照位置已存在，未覆盖任何文件");return target;
    }
    /** Reject structurally incomplete JPEG copies before manifest publication.
     * A .jpg filename is not proof of format: system camera providers can write
     * other formats, so their existing decode path remains unchanged. This check
     * does not prove that every JPEG scan decodes without corruption. */
    static void requireCompleteIfJpeg(File file)throws IOException{
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("照片检查已取消；原文件保留");
        try(InputStream in=new FileInputStream(file)){
            int first=in.read(),second=in.read();
            if(first==0xff&&second==0xd8&&!CaptureBatch.completeJpegInterruptibly(file))
                throw new IOException("拍照 JPEG 尚未完整保存；原文件保留，请稍后重试或先另存原图");
        }
    }
    static boolean ownCapture(Context c,Uri uri){return Objects.equals(uri.getAuthority(),c.getPackageName()+".files")&&uri.getPathSegments().size()==2&&uri.getPathSegments().get(0).equals("camera")&&captureName(uri.getLastPathSegment());}
    static boolean markImported(Context c,Uri uri){
        if(!ownCapture(c,uri))return true;
        return c.getSharedPreferences("camera-inbox",Context.MODE_PRIVATE).edit().putBoolean(uri.getLastPathSegment(),true).commit();
    }
    static File[] pending(Context c){
        LinkedHashMap<String,File> files=new LinkedHashMap<>();
        // Legacy cache is only read, never overwritten or deleted. New captures use files/.
        for(File directory:new File[]{directory(c),new File(c.getCacheDir(),"camera")}){
            File[] found=directory.listFiles();if(found==null)continue;
            for(File f:found)if(f.isFile()&&f.length()>0&&captureName(f.getName())&&!c.getSharedPreferences("camera-inbox",Context.MODE_PRIVATE).getBoolean(f.getName(),false))files.putIfAbsent(f.getName(),f);
        }
        File[] found=files.values().toArray(new File[0]);Arrays.sort(found,(a,b)->Long.compare(b.lastModified(),a.lastModified()));return found;
    }
}
