package org.wangshu.scan;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import com.googlecode.tesseract.android.TessBaseAPI;
import com.googlecode.tesseract.android.TessPdfRenderer;
import com.googlecode.leptonica.android.Pix;
import com.googlecode.leptonica.android.ReadFile;
import java.io.*;
import java.util.*;

/** Entirely offline Tesseract 5; text is optional and never replaces original raster content. */
final class OcrEngine {
    /** Versioned, length-framed inputs: adjacent numeric values cannot alias. */
    static String key(Models.Page p){
        StringBuilder out=new StringBuilder("ocr-key-v2|");
        keyPart(out,String.valueOf(p.sha256));keyPart(out,Arrays.toString(p.quad));
        keyPart(out,String.valueOf(p.rotation));keyPart(out,String.valueOf(p.mode));
        keyPart(out,String.valueOf(p.brightness));keyPart(out,String.valueOf(p.bias));keyPart(out,String.valueOf(p.shadows));
        return out.toString();
    }
    private static void keyPart(StringBuilder out,String value){out.append(value.length()).append(':').append(value);}
    private static TessBaseAPI open(Context context,String language) throws IOException {
        File data=OcrAssetIntegrity.prepareDirectory(context.getFilesDir()),root=data.getParentFile();
        for(String name:new String[]{"eng.traineddata","chi_sim.traineddata","pdf.ttf"}){
            OcrAssetIntegrity.prepare(data,name,asset->context.getAssets().open("tessdata/"+asset));
        }
        TessBaseAPI[] holder=new TessBaseAPI[1];TessBaseAPI api=new TessBaseAPI(values->{if(Thread.currentThread().isInterrupted()&&holder[0]!=null)holder[0].stop();});holder[0]=api;
        boolean ready=false;Throwable failure=null;
        try{
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("本地文字识别初始化已取消");
            if(!api.init(root.getPath(),language))throw new IOException("本地文字识别初始化失败");
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO);api.setVariable("user_defined_dpi","300");
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("本地文字识别初始化已取消");
            ready=true;return api;
        }catch(IOException|RuntimeException|Error problem){failure=problem;throw problem;}
        finally{if(!ready)try{api.recycle();}catch(RuntimeException|Error cleanup){if(failure!=null){if(cleanup!=failure)failure.addSuppressed(cleanup);}else throw cleanup;}}
    }
    static void recognize(Store store,Models.Document doc,String language,Exporter.Progress progress) throws Exception {
        TessBaseAPI api=open(store.context,language);
        try{
            ArrayList<Models.Page> pages=doc.active();for(int i=0;i<pages.size();i++){
                if(Thread.currentThread().isInterrupted())throw new IOException("已取消，已完成页面的文字仍保留");
                Models.Page p=pages.get(i);Bitmap b=Renderer.render(store.original(doc,p),p,2400);
                try{api.setImage(b);String recognized=api.getUTF8Text();if(Thread.currentThread().isInterrupted())throw new IOException("已取消，已完成页面的文字仍保留");DocumentMutations.ocr(doc,p,requireRecognizedText(recognized),key(p),language,store::saveIfCurrent);api.clear();}finally{b.recycle();}
                progress.update(i+1,pages.size());
            }
        }finally{api.recycle();}
    }
    /** A failed recognition must not replace an existing result with empty text. */
    static String requireRecognizedText(String text)throws IOException{
        if(text==null)throw new IOException("文字识别未返回结果；本页已有识别文字保留，请稍后重试。");
        return text;
    }
    static File searchablePdf(Store store,Models.Document doc,File folder,String language,int maxEdge,Exporter.Progress progress) throws Exception {
        ArrayList<Models.Page> pages=doc.active();if(pages.isEmpty())throw new IOException("请先添加页面");folder.mkdirs();String base="searchable-"+UUID.randomUUID();File output=new File(folder,base+".pdf");
        TessBaseAPI api=open(store.context,language);TessPdfRenderer renderer=null;boolean complete=false;
        try{
            renderer=new TessPdfRenderer(api,new File(folder,base).getPath());if(!api.beginDocument(renderer,doc.title))throw new IOException("无法创建可搜索 PDF");
            for(int i=0;i<pages.size();i++){
                if(Thread.currentThread().isInterrupted())throw new IOException("已取消");
                Models.Page page=pages.get(i);Bitmap bitmap=Renderer.render(store.original(doc,page),page,maxEdge);Pix pix=null;File temporary=new File(folder,"ocr-page-"+UUID.randomUUID()+".png");
                try{
                    try(OutputStream out=new FileOutputStream(temporary)){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("无法准备识别页面");}
                    pix=ReadFile.readBitmap(bitmap);bitmap.recycle();bitmap=null;
                    if(pix==null||!api.addPageToDocument(pix,temporary.getPath(),renderer))throw new IOException("文字识别页面未能写入");
                    if(Thread.currentThread().isInterrupted())throw new IOException("已取消，文稿和原图仍保留");
                }finally{if(bitmap!=null)bitmap.recycle();if(pix!=null)pix.recycle();temporary.delete();}
                progress.update(i+1,pages.size());
            }
            if(!api.endDocument(renderer))throw new IOException("可搜索 PDF 未能完成");
            try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(output,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer pdf=new PdfRenderer(fd)){if(pdf.getPageCount()!=pages.size())throw new IOException("可搜索 PDF 页数校验失败");}
            complete=true;return output;
        }finally{if(renderer!=null)renderer.recycle();api.recycle();if(!complete)output.delete();}
    }
}
