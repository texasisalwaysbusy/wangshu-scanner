package org.wangshu.scan;

import android.graphics.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.wangshu.scan.core.StreamingPdf;

final class Exporter {
    interface Progress {void update(int done,int total);}
    static File pdf(Store store,Models.Document doc,File cache,boolean a4,int maxEdge,Progress progress) throws Exception {
        ArrayList<Models.Page> pages=doc.active();if(pages.isEmpty())throw new IOException("请先添加图片");
        cache.mkdirs();File temp=new File(cache,"scan-"+UUID.randomUUID()+".part"),result=new File(cache,"scan-"+UUID.randomUUID()+".pdf");
        try(StreamingPdf pdf=new StreamingPdf(temp,pages.size())) {
            for(int i=0;i<pages.size();i++){
                if(Thread.currentThread().isInterrupted())throw new IOException("已取消");
                Bitmap b=Renderer.render(store.original(doc,pages.get(i)),pages.get(i),maxEdge);
                try{
                    boolean landscape=b.getWidth()>b.getHeight();int pw=a4?(landscape?842:595):Math.max(72,b.getWidth()/3),ph=a4?(landscape?595:842):Math.max(72,b.getHeight()/3);
                    int channels=pages.get(i).mode.equals("COLOR")?3:1;File pixels=new File(cache,"pixels-"+UUID.randomUUID()+".part");
                    try{
                        try(DeflaterOutputStream encoded=new DeflaterOutputStream(new BufferedOutputStream(new FileOutputStream(pixels)))){
                            int[] row=new int[b.getWidth()];byte[] bytes=new byte[b.getWidth()*channels];
                            for(int y=0;y<b.getHeight();y++){
                                if(Thread.currentThread().isInterrupted())throw new IOException("已取消");b.getPixels(row,0,b.getWidth(),0,y,b.getWidth(),1);
                                for(int x=0;x<row.length;x++)if(channels==1)bytes[x]=(byte)(row[x]&255);else {bytes[3*x]=(byte)((row[x]>>16)&255);bytes[3*x+1]=(byte)((row[x]>>8)&255);bytes[3*x+2]=(byte)(row[x]&255);}encoded.write(bytes);
                            }
                        }
                        pdf.page(pixels,b.getWidth(),b.getHeight(),channels,pw,ph,a4?12:0);
                    }finally{pixels.delete();}
                } finally {b.recycle();}
                progress.update(i+1,pages.size());
            }
            pdf.finish();
            if(!temp.renameTo(result))throw new IOException("无法完成 PDF 写入");return result;
        } finally {if(temp.exists())temp.delete();}
    }
    /** User-requested local backup retains original bytes and edit parameters, no credentials. */
    static File backup(Store store,Models.Document doc,File cache) throws Exception {
        cache.mkdirs();File temp=new File(cache,"backup-"+UUID.randomUUID()+".part"),result=new File(cache,"backup-"+UUID.randomUUID()+".zip");
        try{
            try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(temp))){
                out.putNextEntry(new ZipEntry("manifest.json"));out.write(doc.json().toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));out.closeEntry();
                for(Models.Page p:doc.pages){
                    if(Thread.currentThread().isInterrupted())throw new IOException("已取消");
                    out.putNextEntry(new ZipEntry(p.file));try(InputStream in=new FileInputStream(store.original(doc,p))){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){if(Thread.currentThread().isInterrupted())throw new IOException("已取消");out.write(b,0,n);}}out.closeEntry();
                }
            }
            try(RandomAccessFile complete=new RandomAccessFile(temp,"rw")){complete.getFD().sync();}
            if(!temp.renameTo(result))throw new IOException("无法完成备份写入");return result;
        }finally{if(temp.exists())temp.delete();}
    }
    static void copy(File source,OutputStream out) throws IOException {
        if(out==null)throw new IOException("保存位置不可写");
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("复制已中断，目标可能不完整");
        try(InputStream in=new FileInputStream(source)){copy(in,out);}
    }
    /** Borrowed streams remain caller-owned, including on failure/interruption. */
    static void copy(InputStream in,OutputStream out) throws IOException {
        if(out==null)throw new IOException("保存位置不可写");if(in==null)throw new IOException("源文件无法读取");
        byte[] bytes=new byte[65536];
        for(;;){
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("复制已中断，目标可能不完整");
            int count=in.read(bytes);
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("复制已中断，目标可能不完整");
            if(count<0)break;if(count>0)out.write(bytes,0,count);
        }
        out.flush();
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("复制已中断，目标可能不完整");
    }
}
