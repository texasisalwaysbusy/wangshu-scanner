package org.wangshu.scan;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Read-only preview of this app's completed export. Not a PDF editor or text-search UI. */
public final class PdfPreviewActivity extends Activity {
    static final String EXTRA_NAME="exportName";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    // Renderer and its descriptor are exclusively owned by worker, including close.
    private PdfRenderer renderer;
    private volatile boolean closed;
    private boolean busy;
    private int index, count;
    private ImageView image;
    private TextView status;
    private Button previous, next, retry;
    private int retryPage;
    private Bitmap shown;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.WHITE);
        root.setPadding(16,16,16,16);
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(16+insets.getSystemWindowInsetLeft(),16+insets.getSystemWindowInsetTop(),16+insets.getSystemWindowInsetRight(),16+insets.getSystemWindowInsetBottom());return insets;});
        TextView title=new TextView(this);title.setText("已生成 PDF 快照 · 本地只读");title.setTextSize(20);root.addView(title);
        TextView note=new TextView(this);note.setText("用于核对页序与版面，不支持搜索或选词。缓存可能被系统清理，请仍使用“保存到文件”保留 PDF。");root.addView(note);
        status=new TextView(this);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);root.addView(status);
        image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);root.addView(image,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout controls=new LinearLayout(this);
        previous=new Button(this);previous.setText("上一页");previous.setOnClickListener(v->render(index-1));controls.addView(previous,new LinearLayout.LayoutParams(0,-2,1));
        next=new Button(this);next.setText("下一页");next.setOnClickListener(v->render(index+1));controls.addView(next,new LinearLayout.LayoutParams(0,-2,1));
        retry=new Button(this);retry.setText("重试当前页");retry.setOnClickListener(v->render(retryPage));root.addView(retry);retry.setVisibility(View.GONE);
        Button back=new Button(this);back.setText("返回");back.setOnClickListener(v->finish());controls.addView(back,new LinearLayout.LayoutParams(0,-2,1));root.addView(controls);
        setContentView(root);SystemBars.apply(this,true);
        int requested=state==null?0:state.getInt("page",0);
        busy=true;buttons();status.setText("正在打开本地 PDF…");
        String name=getIntent().getStringExtra(EXTRA_NAME);
        worker.execute(()->{
            ParcelFileDescriptor fd=null;
            try{
                if(closed)return;
                File file=resolveExport(getCacheDir(),name);
                fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
                renderer=new PdfRenderer(fd);fd=null; // successful constructor owns the descriptor
                int pages=renderer.getPageCount();if(pages<1)throw new IOException("PDF 没有页面");
                runOnUiThread(()->{if(closed)return;count=pages;busy=false;render(Math.max(0,Math.min(requested,pages-1)));});
            }catch(Exception|OutOfMemoryError e){failure("无法打开 PDF，请返回重新生成。",e);}
            finally{if(fd!=null)try{fd.close();}catch(IOException ignored){}}
        });
    }
    static File resolveExport(File cache,String name)throws IOException{
        if(name==null||!name.matches("(?:scan|searchable)-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.pdf")||name.contains(".."))throw new IOException("无效的 PDF 名称");
        File rawBase=new File(cache,"exports");File base=rawBase.getCanonicalFile();
        if(java.nio.file.Files.isSymbolicLink(rawBase.toPath())||!base.getParentFile().equals(cache.getCanonicalFile()))throw new IOException("PDF 缓存目录无效");
        File file=new File(base,name);
        if(!file.getCanonicalFile().getParentFile().equals(base)||java.nio.file.Files.isSymbolicLink(file.toPath())||!file.isFile())throw new IOException("PDF 缓存不可用");
        return file;
    }
    private void buttons(){previous.setEnabled(!busy&&index>0);next.setEnabled(!busy&&index+1<count);}
    private void render(int target){
        if(closed||busy||target<0||target>=count)return;
        busy=true;retryPage=target;retry.setVisibility(View.GONE);buttons();status.setText("正在显示第 "+(target+1)+" / "+count+" 页…");
        worker.execute(()->{
            Bitmap bitmap=null;
            try{
                if(closed)return;
                try(PdfRenderer.Page page=renderer.openPage(target)){
                    int w=page.getWidth(),h=page.getHeight();if(w<=0||h<=0)throw new IOException("无效页面大小");
                    double scale=1600.0/Math.max(w,h);
                    int width=Math.max(1,(int)Math.floor(w*scale)),height=Math.max(1,(int)Math.floor(h*scale));
                    bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);
                    page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                }
                Bitmap ready=bitmap;bitmap=null;
                runOnUiThread(()->{if(closed){ready.recycle();return;}shown=ready;image.setImageBitmap(ready);index=target;busy=false;status.setText("第 "+(index+1)+" / "+count+" 页");image.setContentDescription("PDF 第 "+(index+1)+" 页图像预览");buttons();});
            }catch(Exception|OutOfMemoryError e){failure("无法显示这一页，可重试翻页或返回；PDF 文件未改动。",e);}
            finally{if(bitmap!=null)bitmap.recycle();}
        });
    }
    private void failure(String message,Throwable ignored){runOnUiThread(()->{if(closed)return;busy=false;status.setText(message);retry.setVisibility(count>0?View.VISIBLE:View.GONE);buttons();});}
    @Override protected void onSaveInstanceState(Bundle out){out.putInt("page",index);super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){
        closed=true;
        if(image!=null)image.setImageDrawable(null);shown=null; // Framework may retain a display-list reference; let GC reclaim shown bitmaps.
        // Queue close after an active render. shutdownNow would abandon this close task.
        worker.execute(()->{if(renderer!=null){try{renderer.close();}finally{renderer=null;}}});worker.shutdown();
        super.onDestroy();
    }
}
