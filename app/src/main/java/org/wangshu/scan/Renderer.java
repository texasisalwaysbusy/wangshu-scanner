package org.wangshu.scan;

import android.graphics.*;
import androidx.exifinterface.media.ExifInterface;
import android.os.CancellationSignal;
import java.io.*;
import org.wangshu.scan.core.*;

final class Renderer {
    static Bitmap upright(File file,int maxEdge) throws IOException {
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),options);
        if(options.outWidth<2||options.outHeight<2)throw new IOException("无法解码图片");
        options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>maxEdge*1.5)options.inSampleSize*=2;
        options.inJustDecodeBounds=false;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
        Bitmap source=BitmapFactory.decodeFile(file.getPath(),options);if(source==null)throw new IOException("无法解码图片");
        try {
        int orientation=1;try{orientation=new ExifInterface(file.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);}catch(IOException ignored){}
        Matrix matrix=new Matrix();
        switch(orientation){
            case 2:matrix.setScale(-1,1);break;case 3:matrix.setRotate(180);break;case 4:matrix.setScale(1,-1);break;
            case 5:matrix.setRotate(90);matrix.postScale(-1,1);break;case 6:matrix.setRotate(90);break;
            case 7:matrix.setRotate(-90);matrix.postScale(-1,1);break;case 8:matrix.setRotate(-90);break;
        }
        if(!matrix.isIdentity()){Bitmap b=Bitmap.createBitmap(source,0,0,source.getWidth(),source.getHeight(),matrix,true);if(b!=source)source.recycle();source=b;}
        if(Math.max(source.getWidth(),source.getHeight())>maxEdge){double f=maxEdge/(double)Math.max(source.getWidth(),source.getHeight());Bitmap b=Bitmap.createScaledBitmap(source,Math.max(2,(int)(source.getWidth()*f)),Math.max(2,(int)(source.getHeight()*f)),true);if(b!=source)source.recycle();source=b;}
        return source;
        }catch(RuntimeException|OutOfMemoryError e){source.recycle();throw e;}
    }
    /** Render from raw source regions, not a globally reduced upright bitmap. Never modifies the file. */
    static Bitmap render(File file,Models.Page page,int maxEdge) throws IOException {
        Bitmap output=null;BitmapRegionDecoder decoder=null;Bitmap whole=null;boolean completed=false;
        try{
            checkCancelled();
            BitmapFactory.Options header=new BitmapFactory.Options();header.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),header);
            if(header.outWidth<2||header.outHeight<2)throw new IOException("无法读取原图尺寸；原图保留");
            int exif=1;try{exif=new ExifInterface(file.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);}catch(IOException ignored){/* Missing orientation does not rewrite the original. */}
            RegionRenderPlan plan=RegionRenderPlan.create(header.outWidth,header.outHeight,exif,page.quad,page.rotation,maxEdge);
            boolean enhance=!page.mode.equals("COLOR")||page.brightness!=0||page.shadows;
            requireMemory(plan,enhance,file.length(),false);
            boolean fallback=false;
            try{decoder=BitmapRegionDecoder.newInstance(file.getPath(),false);if(decoder==null)throw new IOException("此图片格式无法分区解码");}
            catch(IOException|IllegalArgumentException unsupported){
                if((long)plan.rawWidth*plan.rawHeight>RegionRenderPlan.SMALL_FALLBACK_PIXELS)throw new IOException("此格式无法安全分区处理大图；请保留或导出原图，或换用JPEG/PNG",unsupported);
                fallback=true;requireMemory(plan,enhance,file.length(),true);
            }
            if(decoder!=null&&(decoder.getWidth()!=plan.rawWidth||decoder.getHeight()!=plan.rawHeight))throw new IOException("原图尺寸在处理期间发生变化，未继续输出");
            double[] raw=plan.rawQuad(),destination=plan.destinationQuad();float[] from=new float[8],to=new float[8];for(int i=0;i<8;i++){from[i]=(float)raw[i];to[i]=(float)destination[i];}
            Matrix transform=new Matrix();if(!transform.setPolyToPoly(from,0,to,0,4))throw new IOException("裁切四角无法透视映射");
            output=Bitmap.createBitmap(plan.width,plan.height,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(output);canvas.drawColor(Color.WHITE);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
            BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;options.inScaled=false;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
            if(fallback){
                checkCancelled();whole=BitmapFactory.decodeFile(file.getPath(),options);if(whole==null||whole.getWidth()!=plan.rawWidth||whole.getHeight()!=plan.rawHeight)throw new IOException("图片格式无法按原始像素读取；原图保留");
                canvas.drawBitmap(whole,transform,paint);whole.recycle();whole=null;
            }else{
                for(int index=0;index<plan.tileCount;index++){
                    checkCancelled();RegionRenderPlan.Tile tile=plan.tile(index);Rect region=new Rect(tile.decodeLeft,tile.decodeTop,tile.decodeRight,tile.decodeBottom);Bitmap pixels=null;
                    try{
                        pixels=decoder.decodeRegion(region,options);
                        if(pixels==null||pixels.getWidth()!=region.width()||pixels.getHeight()!=region.height())throw new IOException("分区解码未保留请求的原始像素，未降低清晰度输出");
                        if(pixels.getAllocationByteCount()>tile.decodedPixels()*4+65536)throw new IOException("分区位图超过预计内存预算；原图保留");
                        checkCancelled();int save=canvas.save();
                        try{canvas.concat(transform);canvas.clipRect(tile.left,tile.top,tile.right,tile.bottom);canvas.drawBitmap(pixels,null,new RectF(region),paint);}finally{canvas.restoreToCount(save);}
                    }finally{if(pixels!=null)pixels.recycle();}
                }
                decoder.recycle();decoder=null; // No encoded source/region decoder retained beside the full enhancement array.
            }
            checkCancelled();
            if(enhance){int[] pixels=new int[plan.width*plan.height];output.getPixels(pixels,0,plan.width,0,0,plan.width,plan.height);Enhance.apply(pixels,plan.width,plan.height,page.mode,page.brightness,page.bias,page.shadows);output.setPixels(pixels,0,plan.width,0,0,plan.width,plan.height);}
            checkCancelled();completed=true;return output;
        }catch(OutOfMemoryError exhausted){throw new IOException("当前内存不足以按所选清晰度处理，未降低分辨率；原图保留，请选较小输出或稍后重试",exhausted);}
        catch(IllegalArgumentException invalid){throw new IOException(invalid.getMessage()+"；原图保留",invalid);}
        finally{if(whole!=null)whole.recycle();if(decoder!=null)decoder.recycle();if(!completed&&output!=null)output.recycle();}
    }
    private static void requireMemory(RegionRenderPlan plan,boolean enhance,long encodedBytes,boolean fallback)throws IOException{
        Runtime runtime=Runtime.getRuntime();long budget=RegionRenderPlan.workingBudget(runtime.maxMemory(),runtime.totalMemory()-runtime.freeMemory()),needed=plan.estimatedWorkingBytes(enhance,encodedBytes,fallback);
        if(needed>budget)throw new IOException("所选"+plan.width+"×"+plan.height+"输出预计需要约"+((needed+1048575)/1048576)+"MB处理空间，当前预算约"+(budget/1048576)+"MB；未降低分辨率，原图保留。请选较小输出或彩色模式");
    }
    private static void checkCancelled()throws InterruptedIOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("已取消图像处理，原图保留");}
    static Bitmap thumbnail(File file,Models.Page page,int maxEdge) throws IOException {
        if(maxEdge<2||maxEdge>360)throw new IOException("此路径仅用于低分辨率缩略图");
        Bitmap source=upright(file,maxEdge);Bitmap output=null;
        try{
            int[] size=Geometry.outputSize(page.quad,source.getWidth(),source.getHeight(),maxEdge);
            output=Bitmap.createBitmap(size[0],size[1],Bitmap.Config.ARGB_8888);
            float[] src=new float[8];for(int i=0;i<4;i++){src[2*i]=(float)(page.quad[2*i]*source.getWidth());src[2*i+1]=(float)(page.quad[2*i+1]*source.getHeight());}
            float[] dst={0,0,size[0],0,size[0],size[1],0,size[1]};Matrix m=new Matrix();if(!m.setPolyToPoly(src,0,dst,0,4))throw new IOException("裁切四角无效");
            Canvas c=new Canvas(output);c.drawColor(Color.WHITE);c.drawBitmap(source,m,new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG));
            source.recycle();source=null; // Perspective sampling is complete; release before enhancement buffers.
            if(!page.mode.equals("COLOR")||page.brightness!=0||page.shadows){int[] pixels=new int[size[0]*size[1]];output.getPixels(pixels,0,size[0],0,0,size[0],size[1]);Enhance.apply(pixels,size[0],size[1],page.mode,page.brightness,page.bias,page.shadows);output.setPixels(pixels,0,size[0],0,0,size[0],size[1]);}
            if(page.rotation!=0){Matrix rot=new Matrix();rot.setRotate(page.rotation*90);Bitmap b=Bitmap.createBitmap(output,0,0,output.getWidth(),output.getHeight(),rot,true);if(b!=output)output.recycle();output=b;}
            return output;
        }catch(RuntimeException|OutOfMemoryError e){if(output!=null)output.recycle();throw e;}finally{if(source!=null)source.recycle();}
    }
    static double[] detect(File file) throws IOException {
        Bitmap b=upright(file,360);try{int[] p=new int[b.getWidth()*b.getHeight()];b.getPixels(p,0,b.getWidth(),0,0,b.getWidth(),b.getHeight());return PageDetector.detect(p,b.getWidth(),b.getHeight());}finally{b.recycle();}
    }
}
