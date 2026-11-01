package org.wangshu.scan;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import org.wangshu.scan.core.Geometry;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Offline native document workspace. No service, account, tracking or network permission. */
public final class MainActivity extends Activity {
    private static final int IMPORT=101,CAMERA=102,SAVE=103,BACKUP=104,BATCH=105;
    private int INK,MUTED,TEAL,PAPER,SURFACE,ON_ACCENT,PREVIEW;
    private Store store;
    // Preview work cannot queue ahead of durable metadata work or startup recovery.
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(r,"scanner-previews"));
    private final ExecutorService operations=Executors.newSingleThreadExecutor(r->new Thread(r,"scanner-operations"));
    private final Handler handler=new Handler(Looper.getMainLooper());
    private LinearLayout root,body;
    private Models.Document document;
    private Models.Page originalPage,draft;
    private int screen=0,generation=0,previewGeneration=0;
    private boolean cropping=false,busy=false,cropLandscape=false;
    private CropView cropView;
    private ImageView preview;
    private Button inboxNotice;
    private int inboxCheckSerial;
    private Bitmap shownBitmap,cropBitmap;
    private String activeSaveToken,handledSaveToken; // intentionally not restored: uncertain saves require a new destination
    private File pendingExport;
    private File recentPdf;
    private String recentPdfRevision="";
    private OperationOverlay progress;
    private MainThreadWatchdog watchdog;
    private int operationEpoch=0;
    private boolean startupPending=false;
    private boolean resumed=false,stateSaved=false,resultDrainScheduled=false;
    private final ArrayDeque<Bundle> pendingResults=new ArrayDeque<>();
    private CaptureRequest captureRequest;
    private boolean captureDrainScheduled;
    private String startupDocumentId;
    private String startupDraft;
    private String pendingKind="pdf";
    private boolean exportA4=true;
    private int exportEdge=2800;
    private TextView editorStatus;
    private volatile Thread operationThread;
    private volatile boolean cancelOperation;

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        watchdog=MainThreadWatchdog.forActivity(this,handler);watchdog.start();watchdog.phase("create");
        palette();store=new Store(this);
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::handleBack);
        startupDocumentId=saved!=null?saved.getString("document"):getPreferences(0).getString("lastDocument",null);
        startupDraft=saved==null?null:saved.getString("draft");
        if(saved!=null&&saved.getString("pendingExport")!=null){pendingExport=new File(saved.getString("pendingExport"));pendingKind=saved.getString("pendingKind","pdf");}
        if(saved!=null&&saved.getString("recentPdf")!=null){recentPdf=new File(saved.getString("recentPdf"));recentPdfRevision=saved.getString("recentPdfRevision","");}
        restorePendingResultState(saved);restoreCaptureRequest(saved);
        Intent intent=getIntent();boolean share=saved==null&&(Intent.ACTION_SEND.equals(intent.getAction())||Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction()));
        ArrayList<Uri> uris=new ArrayList<>();
        if(share){
            if(Intent.ACTION_SEND.equals(intent.getAction())){Uri u=intent.getParcelableExtra(Intent.EXTRA_STREAM);if(u!=null)uris.add(u);}
            else {ArrayList<Uri> list=intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);if(list!=null)uris.addAll(list);}
        }
        // Present an Activity window before any manifest read/FILE_LOCK wait.
        base("望舒扫描","正在恢复本地工作区…",null);startupPending=true;
        final String id=startupDocumentId;final int startupGeneration=generation;
        runIO("正在读取本地文稿…",()->{
            try{if(share)return store.create();return id==null?null:store.load(id);}catch(Exception|OutOfMemoryError|LinkageError e){return e;}
        },value->completeStartup(startupGeneration,value,share,uris));
    }
    private void completeStartup(int expectedGeneration,Object value,boolean share,ArrayList<Uri> uris){
        String restoreDraft=startupDraft;startupPending=false;startupDocumentId=null;startupDraft=null;
        if(generation!=expectedGeneration||document!=null)return;
        if(value instanceof Models.Document)document=(Models.Document)value;
        else if(value instanceof Throwable)toast("上次文稿未能恢复，请查看文稿列表");
        if(document!=null)showDocument();else showLibrary("");
        if(restoreDraft!=null&&document!=null)restoreDraft(restoreDraft);
        if(share&&document!=null&&!uris.isEmpty())importImages(uris);
    }
    private void restoreDraft(String encoded){
        try{Models.Page p=Models.Page.from(new org.json.JSONObject(encoded));for(Models.Page x:document.pages)if(x.id.equals(p.id)){openEditor(x);draft=p;requestPreview();break;}}
        catch(Exception e){toast("编辑预览已重置，已保存的文稿未受影响");}
    }
    @Override protected void onStart(){super.onStart();if(watchdog!=null){watchdog.start();watchdog.phase("start");}}
    @Override protected void onResume(){super.onResume();resumed=true;stateSaved=false;if(watchdog!=null)watchdog.phase("resume");SystemBars.apply(this,!dark());scheduleResultDrain();scheduleCaptureDrain();refreshInboxNotice();}
    @Override protected void onPause(){resumed=false;super.onPause();}
    @Override protected void onStop(){if(watchdog!=null)watchdog.stop();super.onStop();}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(watchdog!=null)watchdog.phase(focused?"focus-gained":"focus-lost");}
    private boolean dark(){return (getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;}
    private void palette(){INK=getColor(R.color.ink);MUTED=getColor(R.color.muted);TEAL=getColor(R.color.accent);PAPER=getColor(R.color.paper);SURFACE=getColor(R.color.surface);ON_ACCENT=getColor(R.color.on_accent);PREVIEW=getColor(R.color.preview);}
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration){super.onConfigurationChanged(configuration);if(screen==2&&draft!=null&&!busy){if(cropping)reframeCrop();else buildEditor();}}
    @Override protected void onSaveInstanceState(Bundle out){
        savePendingResultState(out);if(captureRequest!=null&&captureRequest.phase<=CaptureRequest.LAUNCHED&&currentCapture(captureRequest))out.putString("singleCaptureToken",captureRequest.token);super.onSaveInstanceState(out);
        if(document!=null)out.putString("document",document.id);
        else if(startupPending&&startupDocumentId!=null)out.putString("document",startupDocumentId);
        if(startupPending&&startupDraft!=null)out.putString("draft",startupDraft);
        if(draft!=null)try{if(cropping&&cropView!=null)draft.quad=cropView.getQuad();out.putString("draft",draft.json().toString());}catch(Exception ignored){}
        if(pendingExport!=null){out.putString("pendingExport",pendingExport.getPath());out.putString("pendingKind",pendingKind);}
        if(recentPdf!=null){out.putString("recentPdf",recentPdf.getPath());out.putString("recentPdfRevision",recentPdfRevision);}
    }
    @Override protected void onDestroy(){
        generation++;previewGeneration++;operationEpoch++;cancelOperation=true;operations.shutdownNow();worker.shutdownNow();
        if(watchdog!=null)watchdog.close();handler.removeCallbacksAndMessages(null);
        if(progress!=null){progress.dismiss();progress=null;}if(preview!=null)preview.setImageDrawable(null);if(cropView!=null)cropView.setImage(null,Geometry.full());releaseImages();super.onDestroy();
    }
    private boolean alive(){return !isFinishing()&&!isDestroyed();}
    private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
    private GradientDrawable rounded(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(dp(4),dp(6),dp(4),dp(6));return t;}
    private void styleButton(Button b,boolean primary){int foreground=primary?ON_ACCENT:TEAL;int disabled=getColor(R.color.disabled_ink);b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{disabled,foreground}));b.setStateListAnimator(null);b.setElevation(0);b.setBackgroundTintList(null);android.graphics.drawable.StateListDrawable backgrounds=new android.graphics.drawable.StateListDrawable();backgrounds.addState(new int[]{-android.R.attr.state_enabled},rounded(getColor(R.color.disabled_surface),12));backgrounds.addState(new int[]{},rounded(primary?TEAL:SURFACE,12));b.setBackground(new RippleDrawable(ColorStateList.valueOf(getColor(R.color.control_ripple)),backgrounds,null));b.setPadding(dp(10),dp(10),dp(10),dp(10));}
    private Button button(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setTextSize(14);b.setAllCaps(false);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));styleButton(b,false);b.setOnClickListener(v->action.run());return b;}
    private Button pageAction(String label,Runnable action){
        Button control=button(label,action);
        // Unavailable arrows must not look like the selected/primary action.
        // Keep the native disabled state, readable label, and existing >=48dp target.
        android.graphics.drawable.StateListDrawable faces=new android.graphics.drawable.StateListDrawable();
        faces.addState(new int[]{-android.R.attr.state_enabled},rounded(Color.TRANSPARENT,12));
        GradientDrawable available=rounded(SURFACE,12);available.setStroke(Math.max(1,dp(1)),TEAL);faces.addState(new int[]{},available);
        control.setBackground(new RippleDrawable(ColorStateList.valueOf(getColor(R.color.control_ripple)),faces,null));return control;
    }
    private Button primary(String label,Runnable action){Button b=button(label,action);styleButton(b,true);return b;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private void weighted(LinearLayout row,View v){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1);lp.setMargins(dp(2),dp(3),dp(2),dp(3));row.addView(v,lp);}
    private void base(String title,String subtitle,Runnable back){
        generation++;previewGeneration++;cropping=false;cropView=null;preview=null;releaseImages();
        if(watchdog!=null)watchdog.phase("screen-build");
        root=column();root.setBackgroundColor(PAPER);root.setPadding(dp(16),dp(8),dp(16),dp(10));root.setFocusableInTouchMode(true);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            int l,r,t,b;
            if(Build.VERSION.SDK_INT>=30){Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());l=bars.left;r=bars.right;t=bars.top;b=bars.bottom;}
            else{l=insets.getSystemWindowInsetLeft();r=insets.getSystemWindowInsetRight();t=insets.getSystemWindowInsetTop();b=Math.min(insets.getSystemWindowInsetBottom(),insets.getStableInsetBottom());}
            v.setPadding(dp(16)+l,dp(8)+t,dp(16)+r,dp(10)+b);return insets;
        });
        LinearLayout header=row();if(back!=null){Button b=button("‹",back);b.setTextSize(26);b.setMaxLines(1);b.setContentDescription("返回上一页");b.setTooltipText("返回");header.addView(b,new LinearLayout.LayoutParams(dp(48),-2));}
        TextView name=text(title,24,INK);name.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));name.setMaxLines(2);header.addView(name,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));root.addView(header);
        if(subtitle!=null)root.addView(text(subtitle,13,MUTED));
        body=column();root.addView(body,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);root.requestFocus();root.requestApplyInsets();
    }
    private void releaseImages(){
        if(shownBitmap!=null){shownBitmap.recycle();shownBitmap=null;}if(cropBitmap!=null){cropBitmap.recycle();cropBitmap=null;}
    }
    private void showLibrary(String query){
        cancelPendingCapture();screen=0;document=null;draft=null;originalPage=null;getPreferences(0).edit().remove("lastDocument").apply();
        base("望舒扫描","离线保存 · 原图保留 · 无需账户",null);
        if(getPreferences(0).getString("captureBatch",null)!=null)body.addView(button("找回未整理的连续扫描",this::recoverCaptureBatch));
        EditText search=new EditText(this);search.setSingleLine();search.setHint("搜索名称或已识别文字");search.setText(query);body.addView(search);
        LinearLayout actions=row();weighted(actions,primary("＋ 新建文稿",()->runIO("正在创建文稿…",store::create,value->{document=(Models.Document)value;showDocument();})));weighted(actions,button("恢复备份",this::pickBackup));body.addView(actions);
        LinearLayout utility=row();weighted(utility,button("隐藏的文稿",this::restoreDocuments));weighted(utility,button("拍照收件箱",this::cameraInbox));weighted(utility,button("关于与隐私",this::about));body.addView(utility);
        ScrollView scroll=new ScrollView(this);LinearLayout list=column();scroll.addView(list);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        int libraryGeneration=generation;ArrayList<Models.Document> docs=new ArrayList<>();ArrayList<String> warnings=new ArrayList<>();boolean[] loaded={false};int[] limit={60};
        Runnable[] renderAgain=new Runnable[1];
        Runnable render=()->{
            if(libraryGeneration!=generation||screen!=0)return;
            list.removeAllViews();if(!loaded[0]){list.addView(text("正在读取本地文稿…",18,MUTED));return;}int shown=0,total=0;String q=search.getText().toString().trim().toLowerCase(Locale.ROOT);
            for(Models.Document d:docs)if(matches(d,q)){
                total++;if(shown>=limit[0])continue;
                shown++;LinearLayout card=column();card.setPadding(dp(14),dp(10),dp(14),dp(10));card.setBackground(rounded(SURFACE,14));
                card.addView(text(d.title,19,INK));card.addView(text(d.active().size()+" 页 · "+new java.text.SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(new Date(d.updated)),13,MUTED));
                card.setOnClickListener(v->{document=d;showDocument();});card.setContentDescription("打开文稿 "+d.title);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(8),0,dp(4));list.addView(card,lp);
            }
            if(shown==0)list.addView(text(q.isEmpty()&&!warnings.isEmpty()?"部分文稿暂时无法读取，可查看下方核对选项。原文件保留。":q.isEmpty()?"把纸张留在手边\n\n新建文稿，然后拍照或从相册导入。每张原图都会单独保留，可以随时重新裁切。":"没有匹配的文稿",18,MUTED));
            if(total>shown){int remaining=total-shown;list.addView(button("显示更多文稿 · 还有 "+remaining+" 份",()->{limit[0]+=60;renderAgain[0].run();}));}
            if(!warnings.isEmpty()){
                list.addView(button("有文稿无法读取 · 查看详情",()->new AlertDialog.Builder(this).setTitle("未删除任何文件").setMessage(String.join("\n",warnings)).setPositiveButton("知道了",null).show()));
                ArrayList<String> ids=recoveryIds(warnings);if(!ids.isEmpty())list.addView(button("核对记录并复制恢复",()->chooseRecoveryDocument(ids)));
            }
        };
        renderAgain[0]=render;render.run();search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){limit[0]=60;handler.removeCallbacks(render);handler.postDelayed(render,120);}public void afterTextChanged(android.text.Editable e){}});
        worker.execute(()->{ArrayList<Models.Document> snapshot=store.list();ArrayList<String> issues=new ArrayList<>(store.warnings);runOnUiThread(()->{if(libraryGeneration!=generation||screen!=0||!alive())return;docs.addAll(snapshot);warnings.addAll(issues);loaded[0]=true;render.run();});});
    }
    private static ArrayList<String> recoveryIds(List<String> warnings){
        ArrayList<String> result=new ArrayList<>();for(String warning:warnings){int separator=warning.indexOf(':');if(separator<=0)continue;String id=warning.substring(0,separator);try{if(UUID.fromString(id).toString().equals(id)&&!result.contains(id))result.add(id);}catch(IllegalArgumentException ignored){/* Unknown directory remains untouched. */}}return result;
    }
    private void chooseRecoveryDocument(ArrayList<String> ids){
        if(ids.isEmpty())return;String[] items=ids.toArray(new String[0]);new AlertDialog.Builder(this).setTitle("选择需要核对的文稿编号").setItems(items,(dialog,which)->inspectRecovery(items[which])).setNegativeButton("取消",null).show();
    }
    private void inspectRecovery(String id){
        runIO("正在只读核对记录…",()->ManifestRecovery.inspect(store,id),value->{
            @SuppressWarnings("unchecked") ArrayList<ManifestRecovery.Candidate> candidates=(ArrayList<ManifestRecovery.Candidate>)value;
            if(candidates.isEmpty()){new AlertDialog.Builder(this).setTitle("没有可读取的记录候选").setMessage("原文件仍保留。此恢复方式最多处理1000页、每张100 MiB、原图总计512 MiB；超限或损坏的记录需要进一步核对。请勿删除文稿目录。").setPositiveButton("知道了",null).show();return;}
            String[] labels=new String[candidates.size()];for(int i=0;i<labels.length;i++){ManifestRecovery.Candidate c=candidates.get(i);labels[i]=c.kind+" · "+(c.selectable?(c.title.replaceAll("[\\p{Cntrl}]"," ")+" · 总 "+c.pageCount+" 页（已移除 "+c.removedCount+"）· 记录指纹 "+c.token.substring(0,12)):("暂不可安全读取 · "+c.message.replaceAll("[\\p{Cntrl}]"," ")));}
            new AlertDialog.Builder(this).setTitle("选择候选（不会自动判断）").setItems(labels,(dialog,which)->{
                ManifestRecovery.Candidate choice=candidates.get(which);if(!choice.selectable){new AlertDialog.Builder(this).setTitle("该候选暂不可复制").setMessage(choice.message+"\n原文件仍保留，请勿删除。").setPositiveButton("知道了",null).show();return;}
                new AlertDialog.Builder(this).setTitle("复制为新文稿？").setMessage("这里只能按记录名称、页数和指纹区分候选，不能判断哪一份正确。此方式最多处理1000页、每张100 MiB、原图总计512 MiB。将校验记录和原图并复制为新文稿，旧文件保留；需要足够空间。若应用中断，结果可能未知；先查看文库，重复操作可能创建多个副本。").setPositiveButton("校验并复制",(confirm,button)->runIO("正在校验并复制原图…",()->ManifestRecovery.recover(store,id,choice),restored->{document=(Models.Document)restored;showDocument();toast("已创建恢复副本，请检查页面；原文稿和候选仍保留");},true)).setNegativeButton("取消",null).show();
            }).setNegativeButton("取消",null).show();
        });
    }
    private void showDocument(){
        if(document==null){showLibrary("");return;}
        if(captureRequest!=null&&!captureRequest.owner.equals(document.id))cancelPendingCapture();
        screen=1;draft=null;originalPage=null;getPreferences(0).edit().putString("lastDocument",document.id).apply();
        base(document.title,"所有改动保留原图 · 点击页面进行编辑",()->showLibrary(""));
        if(document.manifestUncertain)body.addView(text("文稿保存状态未确认；请勿继续修改或导出。原图和记录候选保留，待核对恢复。",16,INK));
        LinearLayout top=row();weighted(top,button("重命名",this::rename));if(document.pages.stream().anyMatch(page->page.removed))weighted(top,button("恢复页面",this::restorePages));weighted(top,button("更多",this::documentMenu));body.addView(top);
        ScrollView scroll=new ScrollView(this);LinearLayout list=column();scroll.addView(list);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        Models.Document owner=document;ArrayList<Models.Page> pages=document.active();int viewGeneration=generation;
        // Global inbox is not tied to this document: keep a discoverable neutral
        // entry even before/after a delayed receipt arrives; user chooses the action.
        inboxNotice=button("拍照收件箱 · 检查待核对文件",this::cameraInbox);list.addView(inboxNotice);refreshInboxNotice();
        if(pages.isEmpty())list.addView(text("这份文稿还没有页面\n\n拍照，或一次导入多张图片。之后可以裁切、增强、调整顺序。",18,MUTED));
        for(int i=0;i<pages.size();i++){
            Models.Page p=pages.get(i);int pos=i;
            LinearLayout card=row();card.setPadding(dp(8),dp(8),dp(4),dp(8));card.setBackground(rounded(SURFACE,12));
            ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setBackgroundColor(PREVIEW);image.setContentDescription("编辑第 "+(i+1)+" 页");card.addView(image,new LinearLayout.LayoutParams(dp(88),dp(112)));image.setOnClickListener(v->openEditor(p));
            LinearLayout info=column();info.setPadding(dp(8),0,0,0);info.addView(text("第 "+(i+1)+" 页 · "+modeName(p.mode),16,INK));info.addView(button("编辑 / 裁切",()->openEditor(p)));
            LinearLayout controls=row();Button up=pageAction("↑",()->move(p,-1));up.setContentDescription("第 "+(i+1)+" 页上移");up.setEnabled(i>0);weighted(controls,up);Button down=pageAction("↓",()->move(p,1));down.setContentDescription("第 "+(i+1)+" 页下移");down.setEnabled(i<pages.size()-1);weighted(controls,down);weighted(controls,pageAction("移除",()->{saveThenShow(owner,()->DocumentMutations.removed(owner,p,true,store::saveIfCurrent),()->toast("页面已移入恢复区，原图未删除"));}));info.addView(controls);card.addView(info,new LinearLayout.LayoutParams(0,-2,1));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(5),0,dp(5));list.addView(card,lp);
            Models.Document d=document;worker.execute(()->{try{Bitmap b=Renderer.thumbnail(store.original(d,p),p,220);runOnUiThread(()->{if(generation==viewGeneration&&!isFinishing())image.setImageBitmap(b);else b.recycle();});}catch(Exception e){runOnUiThread(()->{if(generation==viewGeneration)image.setContentDescription("第 "+(pos+1)+" 页无法预览："+e.getMessage());});}});
        }
        LinearLayout add=row();weighted(add,button("相册导入",this::pickImages));weighted(add,button("拍照添加",this::capture));weighted(add,button("连续扫描",this::continuousCapture));root.addView(add);
        Button export=primary("导出 PDF · "+pages.size()+" 页",this::exportOptions);export.setEnabled(!pages.isEmpty());export.setAlpha(1f);export.setMinHeight(dp(52));root.addView(export,new LinearLayout.LayoutParams(-1,-2));
        scheduleCaptureDrain();
    }
    private String modeName(String m){return m.equals("BW")?"黑白":m.equals("GRAY")?"灰度":"彩色";}
    private boolean matches(Models.Document d,String q){if(d.title.toLowerCase(Locale.ROOT).contains(q))return true;for(Models.Page p:d.active())if(p.ocrKey.equals(OcrEngine.key(p))&&p.ocrText.toLowerCase(Locale.ROOT).contains(q))return true;return false;}
    private interface SavedEdit {boolean save()throws Exception;}
    private void saveEdit(Models.Document owner,SavedEdit edit,Runnable after){
        if(owner==null||document!=owner||busy){toast("文稿已变化或正在处理，请稍后重试");return;}
        // DocumentMutations owns rollback and Store.FILE_LOCK on this background path.
        // Only the still-current owner can change the UI after a durable commit.
        runIO("正在保存文稿…",()->{try{return edit.save();}catch(Exception|OutOfMemoryError|LinkageError e){return e;}},value->{
            if(document!=owner)return;
            if(value instanceof Throwable)error((Throwable)value);else if(Boolean.TRUE.equals(value))after.run();
        });
    }
    private void saveThenShow(Models.Document owner,SavedEdit edit){saveThenShow(owner,edit,()->{});}
    private void saveThenShow(Models.Document owner,SavedEdit edit,Runnable after){saveEdit(owner,edit,()->{showDocument();after.run();});}
    private void move(Models.Page p,int direction){Models.Document owner=document;saveThenShow(owner,()->DocumentMutations.move(owner,p,direction,store::saveIfCurrent));}
    private void rename(){Models.Document owner=document;EditText name=new EditText(this);name.setSingleLine();name.setText(owner.title);name.selectAll();new AlertDialog.Builder(this).setTitle("文稿名称").setView(name).setPositiveButton("保存",(d,w)->{String value=name.getText().toString();saveThenShow(owner,()->DocumentMutations.rename(owner,value,store::saveIfCurrent));}).setNegativeButton("取消",null).show();}
    private void restorePages(){
        Models.Document owner=document;ArrayList<Models.Page> removed=new ArrayList<>();for(Models.Page p:owner.pages)if(p.removed)removed.add(p);
        if(removed.isEmpty()){toast("没有移除的页面");return;}
        String[] labels=new String[removed.size()];for(int i=0;i<labels.length;i++)labels[i]="恢复页面 "+(i+1)+" · "+modeName(removed.get(i).mode);
        new AlertDialog.Builder(this).setTitle("恢复页面（原图保留）").setItems(labels,(d,w)->saveThenShow(owner,()->DocumentMutations.removed(owner,removed.get(w),false,store::saveIfCurrent))).setNegativeButton("关闭",null).show();
    }
    private void documentMenu(){new AlertDialog.Builder(this).setTitle("文稿操作").setItems(new String[]{"备份原图与编辑参数（ZIP）","隐藏文稿（保留文件）","查看原图保留说明","本地识别文字（中英）","查看 / 复制识别文字","找回中断导入（追加到末尾）","分享已生成的 PDF","再次保存已生成的 PDF","拍照收件箱（找回未导入照片）"},(dialog,which)->{
        if(which==0){Models.Document owner=document;runIO("正在打包原图…",()->{Models.Document copy=store.snapshotForExport(owner);File file=Exporter.backup(store,copy,new File(getCacheDir(),"exports"));verifyExportSnapshot(owner,copy);return file;},f->{if(document!=owner){toast("文稿已切换，备份仍保留在本地缓存");return;}pendingExport=(File)f;pendingKind="zip";saveExport();});}
        else if(which==1){Models.Document owner=document;new AlertDialog.Builder(this).setMessage("文稿将从列表隐藏，原始文件仍保留在应用私有目录。请先导出 ZIP 备份；可通过备份恢复。是否隐藏？").setPositiveButton("隐藏",(d,w)->{saveEdit(owner,()->DocumentMutations.archived(owner,true,store::saveIfCurrent),()->showLibrary(""));}).setNegativeButton("取消",null).show();}
        else if(which==2)about();else if(which==3)recognizeText();else if(which==4)showText();else if(which==5){Models.Document target=document;runIO("正在查找已保存的原图…",()->store.recoverOrphans(target),count->{showDocument();toast("已找回 "+count+" 页，追加到文稿末尾；已有页面不变");});}else if(which==8)cameraInbox();else reusePdf(which==6);
    }).show();}
    private void recognizeText(){if(document.active().isEmpty()){toast("请先添加页面");return;}new AlertDialog.Builder(this).setTitle("本地文字识别").setMessage("图片不上传。识别结果可能有错，数学公式和手写字尤其需要核对；原图不会被替换。识别文字后可在首页搜索内容。").setPositiveButton("仅英文",(d,w)->runRecognition("eng")).setNeutralButton("中英混合",(d,w)->runRecognition("chi_sim+eng")).setNegativeButton("取消",null).show();}
    private void runRecognition(String language){Models.Document target=document;runIO("本地识别中 · 可取消…",()->{OcrEngine.recognize(store,target,language,(done,total)->runOnUiThread(()->{if(progress!=null)progress.setMessage("本地识别 · "+done+" / "+total);}));return null;},r->{showDocument();showText();},true);}
    private void showText(){StringBuilder value=new StringBuilder();for(Models.Page p:document.active())if(p.ocrKey.equals(OcrEngine.key(p))&&!p.ocrText.isEmpty())value.append("—— 第 ").append(document.active().indexOf(p)+1).append(" 页 ——\n").append(p.ocrText).append("\n\n");if(value.length()==0){toast("尚无有效识别文字，请先在“更多”中运行本地识别");return;}TextView t=text(value.toString(),16,INK);t.setTextIsSelectable(true);ScrollView scroll=new ScrollView(this);scroll.setPadding(dp(16),0,dp(16),0);scroll.addView(t);new AlertDialog.Builder(this).setTitle("识别文字 · 请核对原图").setView(scroll).setPositiveButton("复制全部",(d,w)->{((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("识别文字",value));toast("已复制");}).setNegativeButton("关闭",null).show();}
    private void restoreDocuments(){
        runIO("正在查找隐藏的文稿…",()->{
        ArrayList<Models.Document> hidden=new ArrayList<>();File[] folders=store.root.listFiles();if(folders!=null)for(File f:folders)if(f.isDirectory()&&!f.getName().startsWith("."))try{Models.Document d=store.load(f.getName());if(d.archived)hidden.add(d);}catch(Exception ignored){}
        return hidden.toArray(new Models.Document[0]);
        },value->{Models.Document[] hidden=(Models.Document[])value;if(hidden.length==0){toast("没有隐藏的文稿");return;}String[] titles=new String[hidden.length];for(int i=0;i<titles.length;i++)titles[i]=hidden[i].title;
        new AlertDialog.Builder(this).setTitle("恢复隐藏的文稿").setItems(titles,(d,w)->runIO("正在恢复文稿…",()->{Models.Document restored=store.load(hidden[w].id);DocumentMutations.archived(restored,false,store::saveIfCurrent);return restored;},restored->{document=(Models.Document)restored;showDocument();})).setNegativeButton("关闭",null).show();});
    }
    private void continuousCapture(){
        if(document==null)return;String owner=document.id;
        runIO("正在读取连续扫描记录…",()->CaptureBatch.latestOpen(this,owner),value->{
            CaptureBatch.Batch prior=(CaptureBatch.Batch)value;
            if(prior==null)runIO("正在准备连续扫描…",()->CaptureBatch.create(this,owner),created->openCaptureBatch((CaptureBatch.Batch)created));
            else showCaptureRecovery(prior);
        });
    }
    private void showCaptureRecovery(CaptureBatch.Batch batch){
        if(batch.closed){clearCaptureBatchIfCurrent(batch.id);toast("此批次已结束；原文稿和拍照收件箱仍保留");return;}
        new AlertDialog.Builder(this).setTitle("找回连续扫描 · 已保存 "+batch.saved()+" 张").setMessage("可以继续在同一取景流程拍摄，或统一整理已有照片。原文件保留；未完成的在途快门不会计作已保存。").setPositiveButton("继续拍摄",(d,w)->openCaptureBatch(batch)).setNeutralButton("整理页面",(d,w)->importCaptureBatch(batch.id)).setNegativeButton("稍后",null).show();
    }
    private void openCaptureBatch(CaptureBatch.Batch batch){
        try{if(!getPreferences(0).edit().putString("captureBatch",batch.id).commit())throw new IOException("无法记录连续扫描位置，尚未打开相机");startActivityForResult(new Intent(this,CaptureActivity.class).putExtra(CaptureActivity.EXTRA_SESSION,batch.id),BATCH);}catch(Exception e){error(e);}
    }
    private void clearCaptureBatchIfCurrent(String id){if(id!=null&&id.equals(getPreferences(0).getString("captureBatch",null)))getPreferences(0).edit().remove("captureBatch").apply();}
    private void recoverCaptureBatch(){String id=getPreferences(0).getString("captureBatch",null);if(id==null)return;runIO("正在找回已保存拍照…",()->CaptureBatch.recover(this,id),value->showCaptureRecovery((CaptureBatch.Batch)value));}
    private void importCaptureBatch(String id){
        Exporter.Progress update=(done,total)->runOnUiThread(()->{if(progress!=null)progress.setMessage("正在整理连续扫描 · "+done+" / "+total);});
        runIO("正在整理连续扫描，原图保留…",()->CaptureBatch.importAll(store,id,update),value->{document=(Models.Document)value;clearCaptureBatchIfCurrent(id);showDocument();toast("已整理，请检查每页四角；可继续调整、排序与导出");},true);
    }
    private void pickBackup(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("application/zip");i.addCategory(Intent.CATEGORY_OPENABLE);try{startActivityForResult(i,BACKUP);}catch(ActivityNotFoundException e){error(new IOException("没有可用的文件选择器"));}}
    private void pickImages(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("image/*");i.addCategory(Intent.CATEGORY_OPENABLE);i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);try{startActivityForResult(i,IMPORT);}catch(ActivityNotFoundException e){error(new IOException("没有可用的图片选择器"));}}
    private static final class ImageImportResult {
        final Models.Document document;final String message;
        ImageImportResult(Models.Document document,String message){this.document=document;this.message=message;}
    }
    private void importImages(ArrayList<Uri> uris){
        Models.Document target=document;
        if(target==null){toast("请先打开文稿再导入，拍照原文件仍保留");return;}
        runIO("正在保存原图（"+uris.size()+" 张）…",()->{
            Models.Document working=target;int added=0,existing=0,fullImages=0;ArrayList<String> failures=new ArrayList<>();
            for(int i=0;i<uris.size();i++)try{
                Uri uri=uris.get(i);Models.Page page;boolean newPage;
                if(CameraInbox.ownCapture(this,uri)){
                    // Acknowledgement can fail after the page is committed. Use
                    // the stable capture identity, including on inbox recovery;
                    // a retry must keep the user's edits/order/removed state.
                    Store.CaptureImport imported=store.importCapture(working.id,uri.getLastPathSegment());
                    working=imported.document;page=imported.page;newPage=imported.added;
                }else{page=store.importUri(working,uri);newPage=true;}
                if(newPage){
                    added++;
                    try{page.quad=Renderer.detect(store.original(working,page));if(!Thread.currentThread().isInterrupted())store.saveIfCurrent(working);}
                    catch(ManifestFence.Uncertain uncertain){throw uncertain;}
                    catch(Exception ignored){page.quad=Geometry.full();working=store.load(working.id);/* Keep the committed original and reload any newer edits. */}
                    if(Arrays.equals(page.quad,Geometry.full()))fullImages++;
                }else existing++;
                if(!CameraInbox.markImported(this,uri))failures.add("照片已存入文稿，收件箱状态未能更新，可稍后核对；重试会保留原页");
            }catch(ManifestFence.Uncertain uncertain){throw uncertain;}
            catch(Exception e){failures.add("第 "+(i+1)+" 张："+e.getMessage()+(CameraInbox.ownCapture(this,uris.get(i))?"\n拍照原文件仍在拍照收件箱，可先保存后重试。":""));}
            String message="已添加 "+added+" 张"+(existing==0?"":"\n已有 "+existing+" 张，保留原页的编辑、顺序与移除状态")+(added==0?"":"\n自动四角建议 "+(added-fullImages)+" 张；保留全图 "+fullImages+" 张。\n请检查页边，保留全图的页面可在编辑中拖动四角。")+(failures.isEmpty()?"":"\n"+String.join("\n",failures));
            // Another Activity may have edited this document while detection ran.
            // Never render the older in-memory snapshot after the import task.
            return new ImageImportResult(store.load(target.id),message);
        },value->{
            if(document!=target)return;ImageImportResult result=(ImageImportResult)value;document=result.document;
            showDocument();new AlertDialog.Builder(this).setTitle("导入完成").setMessage(result.message).setPositiveButton("继续",null).show();
        });
    }
    /** Read-only snapshot only; callbacks may not target a later screen/document. */
    private void refreshInboxNotice(){
        final Button target=inboxNotice;final Models.Document owner=document;
        if(target==null||owner==null||screen!=1||!alive())return;
        final int request=++inboxCheckSerial,ownerGeneration=generation;
        target.setText("拍照收件箱 · 检查待核对文件");
        try{worker.execute(()->{
            int count=-1;try{count=CameraInbox.pending(this).length;}catch(RuntimeException ignored){}final int found=count;
            handler.post(()->{if(!alive()||!resumed||screen!=1||document!=owner||generation!=ownerGeneration||target!=inboxNotice||request!=inboxCheckSerial)return;
                if(found>0)target.setText("拍照收件箱 · 上次发现 "+found+" 项未标记文件（不分文稿）");});
        });}catch(RejectedExecutionException ignored){/* Neutral entry stays visible. */}
    }
    private void cameraInbox(){
        runIO("正在查找未导入的拍照…",()->CameraInbox.pending(this),value->{
            File[] photos=(File[])value;if(photos.length==0){toast("没有待导入拍照；已保存原图可在文稿中查看");return;}
            String[] labels=new String[photos.length];for(int i=0;i<labels.length;i++)labels[i]="第 "+(i+1)+" 张 · "+new java.text.SimpleDateFormat("MM-dd HH:mm:ss",Locale.CHINA).format(new Date(photos[i].lastModified()))+" · "+String.format(Locale.CHINA,"%.1f MB",photos[i].length()/1048576.);
            new AlertDialog.Builder(this).setTitle("拍照收件箱 · 原文件保留").setItems(labels,(dialog,which)->showInboxPhoto(photos[which])).setNegativeButton("关闭",null).show();
        });
    }
    private void showInboxPhoto(File photo){
        runIO("正在预览拍照…",()->{try{return Renderer.upright(photo,800);}catch(Exception|OutOfMemoryError e){return null;}},value->{
            Bitmap bitmap=(Bitmap)value;ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setMinimumHeight(dp(180));if(bitmap!=null)image.setImageBitmap(bitmap);
            LinearLayout panel=column();panel.addView(image,new LinearLayout.LayoutParams(-1,dp(240)));
            String message=(bitmap==null?"预览无法读取，仍可保存拍照原文件。\n":"")+"收件箱不区分文稿；导入前请确认目标文稿。文件可能尚未写完，即使可以预览也不代表完整，请核对后再导入。可导入文稿，或先保存原文件。旧版缓存可能有已导入副本，请先核对；导入失败时文件仍保留。卸载前请自行保存。";
            Uri uri=Uri.parse("content://"+getPackageName()+".files/camera/"+photo.getName());
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle("找回拍照").setMessage(message).setView(panel).setPositiveButton(document==null?"导入新文稿":"导入当前文稿",(d,w)->{
                Runnable importPhoto=()->{ArrayList<Uri> images=new ArrayList<>();images.add(uri);importImages(images);};
                if(document==null)runIO("正在创建文稿…",store::create,created->{document=(Models.Document)created;showDocument();importPhoto.run();});else importPhoto.run();
            }).setNeutralButton("保存拍照原文件",(d,w)->{pendingExport=photo;pendingKind="jpg";saveExport();}).setNegativeButton("稍后",null).create();
            dialog.setOnDismissListener(d->{image.setImageDrawable(null);if(bitmap!=null)bitmap.recycle();});dialog.show();
        });
    }
    // A grant belongs to the document and request which asked for it, never to
    // whichever document happens to be visible when Android delivers a callback.
    // Persist before allocation; saved-state token + durable phase reject replay.
    private static final class CaptureRequest {
        static final int WAIT_PERMISSION=0,READY=1,PREPARED=2,LAUNCHED=3,CANCELLED=4,DONE=5;
        final String token,owner,name;final int code,phase;
        CaptureRequest(String token,String owner,int code,int phase){this.token=token;this.owner=owner;this.code=code;this.phase=phase;name="capture-"+token+".jpg";}
        CaptureRequest phase(int next){return new CaptureRequest(token,owner,code,next);}
        boolean pending(){return phase<=PREPARED;}
        boolean same(CaptureRequest other){return other!=null&&token.equals(other.token)&&owner.equals(other.owner)&&code==other.code;}
    }
    private CaptureRequest recordedCaptureRequest(){
        SharedPreferences p=getPreferences(0);String token=p.getString("singleCaptureToken",null),owner=p.getString("singleCaptureOwner",null);
        int code=p.getInt("singleCaptureCode",-1),phase=p.getInt("singleCapturePhase",-1);
        if(token==null||owner==null||owner.isEmpty()||!CameraInbox.captureName("capture-"+token+".jpg")||code<0x4000||code>0xffff||phase<0||phase>CaptureRequest.DONE)return null;
        return new CaptureRequest(token,owner,code,phase);
    }
    private boolean recordCaptureRequest(CaptureRequest value,boolean reserve){
        SharedPreferences.Editor edit=getPreferences(0).edit().putString("singleCaptureToken",value.token).putString("singleCaptureOwner",value.owner).putInt("singleCaptureCode",value.code).putInt("singleCapturePhase",value.phase);
        if(reserve)edit.putString("cameraName",value.name).putString("cameraDocument",value.owner);
        return edit.commit();
    }
    private boolean currentCapture(CaptureRequest value){CaptureRequest recorded=recordedCaptureRequest();return value.same(recorded)&&value.phase==recorded.phase;}
    private void restoreCaptureRequest(Bundle saved){
        CaptureRequest recorded=recordedCaptureRequest();String token=saved==null?null:saved.getString("singleCaptureToken");
        // A fresh launch (or older saved Bundle) cannot prove the old intent is
        // still wanted. Originals remain in the inbox; an explicit new tap may
        // start a new request, but cannot automatically replay this one.
        if(recorded!=null&&recorded.token.equals(token)&&recorded.phase<=CaptureRequest.LAUNCHED)captureRequest=recorded;
    }
    private void cancelPendingCapture(){
        CaptureRequest value=captureRequest;if(value==null||value.phase>CaptureRequest.LAUNCHED)return;
        CaptureRequest cancelled=value.phase(CaptureRequest.CANCELLED);captureRequest=cancelled;
        if(currentCapture(value)&&!recordCaptureRequest(cancelled,false))toast("拍照取消状态未能保存；未打开相机，重新进入后请再次点拍照");
    }
    private void capture(){
        if(!alive()||!resumed||stateSaved||busy||startupPending||document==null||document.archived||screen!=1){toast("文稿尚未就绪，请等待恢复完成后拍照");return;}
        CaptureRequest previous=captureRequest;
        if(previous!=null&&previous.owner.equals(document.id)&&currentCapture(previous)){
            if(previous.phase==CaptureRequest.WAIT_PERMISSION){toast("请先完成当前相机权限选择");return;}
            if(previous.phase==CaptureRequest.READY||previous.phase==CaptureRequest.PREPARED){scheduleCaptureDrain();return;}
            if(previous.phase==CaptureRequest.LAUNCHED){toast("正在等待拍照结果；若已中断，请返回文稿列表再重新打开。已写入照片可从拍照收件箱找回");return;}
        }
        cancelPendingCapture();
        // Never recycle a request code into an old delayed grant/result. The
        // bounded 16-bit Android request-code space is refused explicitly when
        // exhausted, instead of guessing an owner or replaying an old request.
        int sequence=getPreferences(0).getInt("singleCaptureSequence",0);
        if(sequence<0||sequence>0xbfff){toast("拍照请求编号已用尽；请使用连续扫描或相册导入，已有原图保留");return;}
        boolean granted=checkSelfPermission(android.Manifest.permission.CAMERA)==android.content.pm.PackageManager.PERMISSION_GRANTED;
        CaptureRequest next=new CaptureRequest(UUID.randomUUID().toString(),document.id,0x4000+sequence,granted?CaptureRequest.READY:CaptureRequest.WAIT_PERMISSION);
        if(!getPreferences(0).edit().putInt("singleCaptureSequence",sequence+1).putString("singleCaptureToken",next.token).putString("singleCaptureOwner",next.owner).putInt("singleCaptureCode",next.code).putInt("singleCapturePhase",next.phase).commit()){
            toast("无法保存拍照请求，尚未申请权限或创建图片；请检查存储空间");return;
        }
        captureRequest=next;
        if(granted)scheduleCaptureDrain();else try{requestPermissions(new String[]{android.Manifest.permission.CAMERA},next.code);}catch(RuntimeException e){cancelPendingCapture();error(e);}
    }
    private boolean captureReady(){return canDrainResult()&&screen==1&&document!=null&&pendingResults.isEmpty()&&!resultDrainScheduled;}
    private void scheduleCaptureDrain(){
        CaptureRequest value=captureRequest;
        if(document!=null&&document.archived){cancelPendingCapture();return;}
        if(captureDrainScheduled||value==null||!value.pending()||value.phase==CaptureRequest.WAIT_PERMISSION||!captureReady())return;
        if(!value.owner.equals(document.id)){cancelPendingCapture();return;}
        captureDrainScheduled=true;handler.post(()->{captureDrainScheduled=false;drainCaptureRequest();});
    }
    private void drainCaptureRequest(){
        CaptureRequest value=captureRequest;
        if(value==null||value.phase==CaptureRequest.WAIT_PERMISSION||!value.pending()||!captureReady())return;
        if(!value.owner.equals(document.id)||!currentCapture(value)){cancelPendingCapture();return;}
        if(checkSelfPermission(android.Manifest.permission.CAMERA)!=android.content.pm.PackageManager.PERMISSION_GRANTED){cancelPendingCapture();toast("相机权限已变更，未创建图片；可重新点拍照或从相册导入");return;}
        try{
            CaptureRequest prepared=value.phase(CaptureRequest.PREPARED);
            if(!recordCaptureRequest(prepared,true))throw new IOException("无法记录拍照位置，尚未创建图片或打开相机");
            captureRequest=prepared;
            File target=CameraInbox.file(this,prepared.name);
            if(target.exists()){
                if(value.phase!=CaptureRequest.PREPARED||!target.isFile()||target.length()!=0){cancelPendingCapture();toast("该拍照位置已有内容，未覆盖；请从拍照收件箱找回原图");return;}
            }else CameraInbox.create(this,prepared.name);
            Uri uri=Uri.parse("content://"+getPackageName()+".files/camera/"+prepared.name);
            Intent intent=new Intent(MediaStore.ACTION_IMAGE_CAPTURE);intent.putExtra(MediaStore.EXTRA_OUTPUT,uri);intent.setClipData(ClipData.newRawUri("capture",uri));intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_READ_URI_PERMISSION);
            CaptureRequest launched=prepared.phase(CaptureRequest.LAUNCHED);
            if(!recordCaptureRequest(launched,true)){
                // SharedPreferences may update memory even when commit reports
                // disk failure. Keep the same reservation for an explicit retry.
                captureRequest=prepared;recordCaptureRequest(prepared,true);
                throw new IOException("拍照位置已保留，但启动状态未能保存；未打开相机");
            }
            captureRequest=launched;
            try{startActivityForResult(intent,launched.code);}catch(RuntimeException e){
                // Retry only the same still-empty reservation. A written image
                // is always recovered via the inbox, never overwritten.
                captureRequest=prepared;if(currentCapture(launched)&&!recordCaptureRequest(prepared,true))cancelPendingCapture();throw e;
            }
        }catch(ActivityNotFoundException e){toast("此设备没有可用相机应用，拍照位置保留；可从相册导入");}catch(Exception e){error(e);}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);CaptureRequest value=captureRequest;
        if(value==null||request!=value.code||value.phase!=CaptureRequest.WAIT_PERMISSION||!currentCapture(value))return;
        boolean granted=false;if(permissions!=null&&grants!=null)for(int n=0;n<permissions.length&&n<grants.length;n++)if(android.Manifest.permission.CAMERA.equals(permissions[n]))granted=grants[n]==android.content.pm.PackageManager.PERMISSION_GRANTED;
        if(!granted){cancelPendingCapture();toast("相机权限未允许，仍可从相册导入图片");return;}
        CaptureRequest ready=value.phase(CaptureRequest.READY);
        if(!recordCaptureRequest(ready,false)){cancelPendingCapture();toast("无法保存授权后的拍照请求，尚未创建图片；请重新点拍照");return;}
        captureRequest=ready;scheduleCaptureDrain();
    }
    private void openEditor(Models.Page p){
        cancelPendingCapture();
        try{originalPage=p;draft=p.copy();}catch(Exception e){error(e);return;}screen=2;buildEditor();
    }
    private void buildEditor(){
        base("编辑页面","原图始终保留 · 修改满意后保存",this::leaveEditor);
        if(document!=null&&document.manifestUncertain)body.addView(text("文稿保存状态未确认；请勿继续修改或导出。原图和记录候选保留，待核对恢复。",16,INK));
        editorStatus=text("正在生成预览…",12,MUTED);body.addView(editorStatus);
        boolean landscape=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout workspace=landscape?row():column();body.addView(workspace,new LinearLayout.LayoutParams(-1,0,1));
        FrameLayout frame=new FrameLayout(this);preview=new ImageView(this);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);preview.setBackgroundColor(PREVIEW);preview.setContentDescription("处理后页面预览");frame.addView(preview,new FrameLayout.LayoutParams(-1,-1));workspace.addView(frame,landscape?new LinearLayout.LayoutParams(0,-1,1.2f):new LinearLayout.LayoutParams(-1,0,1.3f));
        ScrollView controlsScroll=new ScrollView(this);controlsScroll.setContentDescription("页面增强工具，可上下滚动");controlsScroll.setFillViewport(false);controlsScroll.setScrollbarFadingEnabled(false);LinearLayout controls=column();controlsScroll.addView(controls);workspace.addView(controlsScroll,landscape?new LinearLayout.LayoutParams(0,-1,1):new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout modes=row();for(String m:new String[]{"COLOR","GRAY","BW"}){Button choice=button(modeName(m),()->{draft.mode=m;for(int i=0;i<modes.getChildCount();i++){Button item=(Button)modes.getChildAt(i);boolean selected=m.equals(item.getTag());item.setSelected(selected);styleButton(item,selected);}requestPreview();});choice.setTag(m);choice.setSelected(m.equals(draft.mode));styleButton(choice,m.equals(draft.mode));weighted(modes,choice);}controls.addView(modes);
        LinearLayout tools=row();weighted(tools,button("四角裁切",this::startCrop));weighted(tools,button("旋转 90°",()->{draft.rotation=(draft.rotation+1)%4;requestPreview();}));weighted(tools,button("重置",()->{draft.quad=Geometry.full();draft.rotation=0;draft.mode="COLOR";draft.shadows=false;draft.brightness=0;draft.bias=8;buildEditor();}));controls.addView(tools);
        LinearLayout adjust=row();weighted(adjust,button("亮度 / 黑白细节",this::adjustments));CheckBox shadow=new CheckBox(this);shadow.setText("减淡阴影");shadow.setTextColor(INK);shadow.setChecked(draft.shadows);shadow.setOnCheckedChangeListener((v,checked)->{draft.shadows=checked;requestPreview();});weighted(adjust,shadow);controls.addView(adjust);
        LinearLayout footer=row();weighted(footer,button("导出本页图片",this::exportPageImage));weighted(footer,primary("保存本页",this::saveEditor));root.addView(footer);requestPreview();
    }
    private void requestPreview(){
        if(draft==null||preview==null||cropping)return;int ticket=++previewGeneration;ImageView target=preview;Models.Document d=document;Models.Page p;
        try{p=draft.copy();}catch(Exception e){error(e);return;}editorStatus.setText(modeName(p.mode)+" · 正在更新…");
        worker.execute(()->{
            if(ticket!=previewGeneration)return;
            try{Bitmap b=Renderer.render(store.original(d,p),p,1400);runOnUiThread(()->{if(ticket==previewGeneration&&target==preview&&alive()){target.setImageBitmap(b);if(shownBitmap!=null)shownBitmap.recycle();shownBitmap=b;editorStatus.setText(modeName(p.mode)+" · 可返回彩色保留浅色字迹");}else b.recycle();});}catch(Exception e){runOnUiThread(()->{if(ticket==previewGeneration&&alive()){editorStatus.setText("预览失败，原图未改变");error(e);}});}
        });
    }
    private void startCrop(){
        Models.Page p=draft;Models.Document d=document;runIO("正在载入原图…",()->Renderer.upright(store.original(d,p),1800),result->{
            showCrop((Bitmap)result,p,p.quad);
        });
    }
    private void reframeCrop(){
        boolean landscape=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        if(cropView==null||cropBitmap==null||cropLandscape==landscape)return;
        double[] quad=cropView.getQuad();Bitmap retained=cropBitmap;cropBitmap=null;
        // base() releases the previous frame. Transfer bitmap ownership before
        // rebuilding, and keep unsaved handles separate from the saved draft.
        showCrop(retained,draft,quad);
    }
    private void showCrop(Bitmap bitmap,Models.Page page,double[] quad){
        base("调整四角","拖动四角，放大镜辅助定位 · 可恢复全图",()->{draft=page;buildEditor();});cropping=true;cropBitmap=bitmap;cropLandscape=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        cropView=new CropView(this);cropView.setImage(bitmap,quad);
        Button full=button("恢复全图",()->cropView.setQuad(Geometry.full()));
        Button detect=button("自动边界",()->{Models.Document doc=document;runIO("正在识别纸张边界…",()->Renderer.detect(store.original(doc,page)),q->{double[] suggestion=(double[])q;cropView.setQuad(suggestion);reframeCrop();toast(Arrays.equals(suggestion,Geometry.full())?"未找到可靠边界，已保留全图；可拖动四角调整":"已建议四角，请检查后应用");});});
        Button apply=primary("应用裁切",()->{page.quad=cropView.getQuad();draft=page;buildEditor();});
        if(cropLandscape){
            LinearLayout stage=row();stage.setGravity(Gravity.TOP);stage.addView(cropView,new LinearLayout.LayoutParams(0,-1,3));
            LinearLayout tools=column();for(Button control:new Button[]{full,detect,apply}){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(dp(6),0,0,dp(6));tools.addView(control,p);}
            ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.setScrollbarFadingEnabled(false);scroll.addView(tools);stage.addView(scroll,new LinearLayout.LayoutParams(0,-1,2));body.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
        }else{body.addView(cropView,new LinearLayout.LayoutParams(-1,0,1));LinearLayout controls=row();weighted(controls,full);weighted(controls,detect);root.addView(controls);root.addView(apply,new LinearLayout.LayoutParams(-1,dp(52)));}
    }
    private void adjustments(){
        LinearLayout panel=column();panel.setPadding(dp(20),0,dp(20),0);TextView bright=text("亮度："+draft.brightness,15,INK);panel.addView(bright);SeekBar b=new SeekBar(this);b.setMax(80);b.setProgress(draft.brightness+40);panel.addView(b);
        TextView threshold=text("黑白保留细节："+draft.bias+"（小值保留浅笔迹）",14,INK);panel.addView(threshold);SeekBar t=new SeekBar(this);t.setMax(34);t.setProgress(draft.bias-1);panel.addView(t);panel.addView(text("黑白可能损失淡铅笔字；检查预览，必要时返回彩色或灰度。没有自动去除小符号。",13,MUTED));
        b.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean user){draft.brightness=p-40;bright.setText("亮度："+draft.brightness);}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){requestPreview();}});
        t.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean user){draft.bias=p+1;threshold.setText("黑白保留细节："+draft.bias+"（小值保留浅笔迹）");}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){requestPreview();}});
        new AlertDialog.Builder(this).setTitle("保守增强").setView(panel).setPositiveButton("完成",(d,w)->requestPreview()).show();
    }
    private void saveEditor(){
        if(cropping){draft.quad=cropView.getQuad();buildEditor();return;}
        Models.Document owner=document;Models.Page before=originalPage;
        try{Models.Page next=draft.copy();if(!next.ocrKey.equals(OcrEngine.key(next))){next.ocrText="";next.ocrKey="";}
            saveThenShow(owner,()->DocumentMutations.replacePage(owner,before,next,store::saveIfCurrent));
        }catch(Exception e){error(e);}
    }
    private void leaveEditor(){if(cropping){buildEditor();return;}new AlertDialog.Builder(this).setTitle("离开编辑？").setMessage("已保存的原图和页面不变。当前预览可保存后离开。").setPositiveButton("保存",(d,w)->saveEditor()).setNegativeButton("放弃本次修改",(d,w)->showDocument()).setNeutralButton("继续编辑",null).show();}
    // Worker-only: reject a result if the same document was edited while it was generated.
    private void verifyExportSnapshot(Models.Document owner,Models.Document frozen)throws Exception {Models.Document current=store.snapshotForExport(owner);if(!current.json().toString().equals(frozen.json().toString()))throw new IOException("文稿在导出期间已变化，请核对后重新生成；原图保留");}
    private static final class PdfExportResult {final File file;final Models.Document document;PdfExportResult(File f,Models.Document d){file=f;document=d;}}
    private String pdfRevision(Models.Document d){StringBuilder key=new StringBuilder(d.id).append(d.title);for(Models.Page p:d.active())key.append(p.id).append(OcrEngine.key(p));return key.toString();}
    private void reusePdf(boolean share){Models.Document owner=document;File pdf=recentPdf;String revision=recentPdfRevision;runIO("正在核对文稿版本…",()->{Models.Document checked=store.snapshotForExport(owner);if(pdf==null||!pdf.isFile()||!revision.equals(pdfRevision(checked)))throw new IOException("尚无当前版本的 PDF，请先生成；文稿改动后需要重新导出");return pdf;},value->{if(document!=owner){toast("文稿已切换，请重新选择");return;}pendingExport=(File)value;pendingKind="pdf";if(share)shareExport();else saveExport();});}
    private void exportPageImage(){
        new AlertDialog.Builder(this).setTitle("导出本页图片").setItems(new String[]{"高清 PNG（无损编码 · 长边≤4000像素）","高清 JPEG（长边≤4000像素）","原图副本（保留拍摄元数据）"},(dialog,which)->{
            try{Models.Document d=document;Models.Page page=draft.copy();runIO("正在准备图片…",()->{
                Models.Document frozen=which==2?null:store.snapshotForExport(d);File folder=new File(getCacheDir(),"exports");folder.mkdirs();File original=store.original(d,page);String kind=which==0?"png":"jpg";
                if(which==2){BitmapFactory.Options info=new BitmapFactory.Options();info.inJustDecodeBounds=true;BitmapFactory.decodeFile(original.getPath(),info);String mime=info.outMimeType;kind="image/png".equals(mime)?"png":"image/webp".equals(mime)?"webp":"image/heif".equals(mime)||"image/heic".equals(mime)?"heic":"image/gif".equals(mime)?"gif":"image/jpeg".equals(mime)?"jpg":"bin";}
                File result=new File(folder,"page-"+UUID.randomUUID()+"."+kind);
                try(OutputStream out=new FileOutputStream(result)){
                    if(which==2)Exporter.copy(original,out);else {Bitmap b=Renderer.render(original,page,4000);try{if(!b.compress(which==0?Bitmap.CompressFormat.PNG:Bitmap.CompressFormat.JPEG,98,out))throw new IOException("图片编码未完成");}finally{b.recycle();}}
                }
                if(frozen!=null)verifyExportSnapshot(d,frozen);return result;
            },r->{if(document!=d){toast("文稿已切换，图片仍保留在本地缓存");return;}pendingExport=(File)r;pendingKind=pendingExport.getName().substring(pendingExport.getName().lastIndexOf('.')+1);saveExport();});}catch(Exception e){error(e);}
        }).setNegativeButton("取消",null).show();
    }
    private ArrayAdapter<String> optionAdapter(String[] values){return new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,values){
        private View label(View v){TextView t=(TextView)v;t.setSingleLine(false);t.setMaxLines(3);t.setEllipsize(null);t.setTextSize(14);t.setTextColor(INK);t.setPadding(dp(6),dp(8),dp(6),dp(8));t.setBackgroundColor(SURFACE);return t;}
        @Override public View getView(int position,View recycled,ViewGroup parent){return label(super.getView(position,recycled,parent));}
        @Override public View getDropDownView(int position,View recycled,ViewGroup parent){return label(super.getDropDownView(position,recycled,parent));}
    };}
    private void exportOptions(){
        LinearLayout panel=column();panel.setPadding(dp(20),dp(8),dp(20),dp(8));panel.setBackgroundColor(SURFACE);
        panel.addView(text("页面与清晰度",16,INK));
        CheckBox a4=new CheckBox(this);a4.setText("A4 页面（关闭则保持图片比例）");a4.setChecked(exportA4);a4.setMinHeight(dp(48));panel.addView(a4);
        TextView qualityLabel=text("图片清晰度",13,MUTED);panel.addView(qualityLabel);Spinner quality=new Spinner(this);quality.setContentDescription("PDF 图片清晰度");quality.setMinimumHeight(dp(48));quality.setAdapter(optionAdapter(new String[]{"标准 · 长边≤2800像素","省空间 · 长边≤1800像素","高清 · 长边≤4000像素"}));quality.setSelection(exportEdge==1800?1:exportEdge==4000?2:0);panel.addView(quality);
        panel.addView(text("文字提取与搜索",16,INK));
        CheckBox searchable=new CheckBox(this);searchable.setText("可搜索 PDF · 本地 OCR");searchable.setMinHeight(dp(48));panel.addView(searchable);
        TextView languageLabel=text("识别语言 · 启用可搜索 PDF 后可选",13,MUTED);panel.addView(languageLabel);Spinner language=new Spinner(this);language.setContentDescription("PDF 文字识别语言");language.setMinimumHeight(dp(48));language.setAdapter(optionAdapter(new String[]{"英文 · 教材与讲义","中英混合"}));language.setSelection(getPreferences(0).getString("pdfOcrLanguage","eng").equals("chi_sim+eng")?1:0);language.setEnabled(false);language.setAlpha(.55f);panel.addView(language);
        searchable.setOnCheckedChangeListener((v,checked)->{a4.setEnabled(!checked);language.setEnabled(checked);language.setAlpha(checked?1f:.55f);languageLabel.setText(checked?"识别语言":"识别语言 · 启用可搜索 PDF 后可选");if(checked)a4.setChecked(false);});
        panel.addView(text("长边为上限，小图不放大。省空间档会缩小图像；高清档尽量保留裁切区域细节。原图始终保留。可搜索 PDF 按图片比例输出，文字可能有误，公式与手写以图像为准。",13,MUTED));
        ScrollView options=new ScrollView(this);options.setFillViewport(false);options.addView(panel);

        AlertDialog exportDialog=new AlertDialog.Builder(this).setTitle("导出 PDF").setView(options).setPositiveButton("生成",(d,w)->{exportA4=a4.isChecked();exportEdge=new int[]{2800,1800,4000}[quality.getSelectedItemPosition()];try{Models.Document owner=document;Exporter.Progress report=(done,total)->runOnUiThread(()->{if(progress!=null)progress.setMessage("正在生成 PDF · "+done+" / "+total);});boolean ocr=searchable.isChecked();String selectedLanguage=language.getSelectedItemPosition()==0?"eng":"chi_sim+eng";if(ocr)getPreferences(0).edit().putString("pdfOcrLanguage",selectedLanguage).apply();runIO("正在生成 PDF…",()->{Models.Document copy=store.snapshotForExport(owner);File pdf=ocr?OcrEngine.searchablePdf(store,copy,new File(getCacheDir(),"exports"),selectedLanguage,exportEdge,report):Exporter.pdf(store,copy,new File(getCacheDir(),"exports"),exportA4,exportEdge,report);verifyExportSnapshot(owner,copy);return new PdfExportResult(pdf,copy);},result->{if(document!=owner){toast("文稿已切换，生成文件仍保留在本地缓存");return;}PdfExportResult completed=(PdfExportResult)result;Models.Document copy=completed.document;pendingExport=completed.file;pendingKind="pdf";recentPdf=pendingExport;recentPdfRevision=pdfRevision(copy);new AlertDialog.Builder(this).setTitle("PDF 已生成").setMessage("生成时的文稿快照 · "+copy.active().size()+" 页 · "+String.format(Locale.CHINA,"%.1f MB",pendingExport.length()/1048576.0)).setPositiveButton("保存到文件",(a,b)->saveExport()).setNeutralButton("分享",(a,b)->shareExport()).setNegativeButton("稍后",null).show();},true);}catch(Exception e){error(e);}}).setNegativeButton("取消",null).create();exportDialog.show();exportDialog.getWindow().setBackgroundDrawable(rounded(SURFACE,16));
    }
    private SaveJobStore saveJobs(){return new SaveJobStore(this);}
    private void saveExport(){
        if(pendingExport==null||!pendingExport.isFile()){toast("请重新生成导出文件");return;}
        if(!alive()||!resumed||stateSaved||busy||!pendingResults.isEmpty()||resultDrainScheduled){toast("请等待页面恢复或当前操作完成，导出源仍保留");return;}
        if(activeSaveToken!=null){
            String offered=activeSaveToken;
            new AlertDialog.Builder(this).setTitle("保存请求尚未确认").setMessage("可取消，或重新选择一个保存位置。不会自动改写之前的目标；导出源保留。")
                .setPositiveButton("重新选择",(d,w)->{if(!offered.equals(activeSaveToken)){toast("保存请求已变化，请重新操作");return;}activeSaveToken=null;saveExport();})
                .setNegativeButton("取消此请求",(d,w)->{if(!offered.equals(activeSaveToken)){toast("保存请求已变化，请重新操作");return;}String token=offered;activeSaveToken=null;handledSaveToken=token;runIO("正在取消保存请求…",()->{saveJobs().cancel(token);return null;},value->toast("已取消，导出源仍保留"));}).show();return;
        }
        File source=pendingExport;String mime=exportMime(),title=(document==null?"扫描文稿":document.title).replaceAll("[\\/:*?\"<>|]","_")+"."+pendingKind;
        runIO("正在核对导出源，准备另存为…",()->saveJobs().begin(source,mime,title),value->{
            SaveJobStore.Job job=(SaveJobStore.Job)value;
            if(!alive()||!resumed||stateSaved){toast("保存流程已中断，导出源保留；请重新选择位置");return;}
            activeSaveToken=job.token;
            try{startActivityForResult(new Intent(this,SavePickerActivity.class).setAction(SavePickerActivity.ACTION_START).putExtra(SavePickerActivity.EXTRA_TOKEN,job.token),SAVE);}
            catch(RuntimeException e){activeSaveToken=null;error(new IOException("保存选择器未能打开，导出源保留；请重新选择位置",e));}
        });
    }
    private void recoverSaveResult(String token,String legacyPath){
        runIO("正在核对保留的导出源…",()->saveJobs().recoverSource(token,legacyPath).getPath(),value->{
            String path=(String)value;new AlertDialog.Builder(this).setTitle("请重新选择保存位置")
                .setMessage("这次保存的身份或完成状态无法确认，未自动重写旧位置。导出源仍保留；旧目标可能是半成品。")
                .setPositiveButton("重新选择",(d,w)->{pendingExport=new File(path);String name=pendingExport.getName();pendingKind=name.substring(name.lastIndexOf('.')+1);saveExport();}).setNegativeButton("稍后",null).show();
        });
    }
    private static final class SaveWriteFailure extends IOException {
        private static final long serialVersionUID=1L;final String token;
        SaveWriteFailure(String token,Exception cause){super("保存未确认，导出源保留；目标可能不完整，请重新选择位置",cause);this.token=token;}
    }
    private void processSaveResult(Bundle event){
        String token=event.getString(SavePickerActivity.EXTRA_TOKEN);boolean accepted=event.getInt("saveAccepted")==1;
        if(event.getInt("result")!=RESULT_OK){
            if(accepted&&token!=null){handledSaveToken=token;runIO("正在取消保存请求…",()->{saveJobs().cancel(token);return null;},value->toast("已取消或中断保存，导出源仍保留；需要时重新选择位置"));}
            else toast("保存已取消或未确认，未写入旧位置；导出源仍保留");return;
        }
        if(token==null||!accepted||!token.equals(activeSaveToken)){
            if(token!=null&&token.equals(handledSaveToken))return;
            if(activeSaveToken!=null){toast("较早保存结果已过期，未写入旧位置");return;}
            recoverSaveResult(token,event.getString("source"));return;
        }
        activeSaveToken=null;handledSaveToken=token;Uri uri=event.getParcelable("uri");int flags=event.getInt("flags");
        if(!SavePickerActivity.validDestination(uri,getPackageName())){recoverSaveResult(token,null);return;}
        runIO("正在保存到新选择的位置…",()->{
            SaveJobStore jobs=saveJobs();
            try(SaveJobStore.Claim claim=jobs.claim(token)){
                retainPickerPermission(uri,flags,true);
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("保存已中断，未打开位置");
                try(OutputStream out=getContentResolver().openOutputStream(uri,"w")){if(out==null)throw new IOException("所选位置无法写入");Exporter.copy(claim.input,out);}
                jobs.done(token);return null;
            }catch(Exception e){try{jobs.uncertain(token);}catch(Exception state){e.addSuppressed(state);}throw new SaveWriteFailure(token,e);}
        },value->toast("已保存，原图与导出源仍保留"));
    }
    private String exportMime(){switch(pendingKind){case "pdf":return "application/pdf";case "zip":return "application/zip";case "png":return "image/png";case "jpg":return "image/jpeg";case "webp":return "image/webp";case "heic":return "image/heic";case "gif":return "image/gif";default:return "application/octet-stream";}}
    private void shareExport(){if(pendingExport==null)return;Uri u=Uri.parse("content://"+getPackageName()+".files/exports/"+pendingExport.getName());Intent i=new Intent(Intent.ACTION_SEND);i.setType("application/pdf");i.putExtra(Intent.EXTRA_STREAM,u);i.setClipData(ClipData.newRawUri("PDF",u));i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(i,"分享 PDF"));}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        CaptureRequest receipt=null;
        if(request>=0x4000&&request<=0xffff){
            receipt=recordedCaptureRequest();if(receipt==null||receipt.code!=request||receipt.phase!=CaptureRequest.LAUNCHED)return;
            for(Bundle queued:pendingResults)if(receipt.token.equals(queued.getString("singleCaptureToken")))return;
            request=CAMERA;
        }
        if(request==CAMERA&&receipt==null){
            CaptureRequest active=recordedCaptureRequest();
            if(active!=null&&active.name.equals(getPreferences(0).getString("cameraName",null)))return; // stale legacy callback cannot claim a newer reservation
        }
        String saveToken=null;boolean saveAccepted=false;
        if(request==SAVE){
            if(data!=null&&SavePickerActivity.ACTION_RESULT.equals(data.getAction()))saveToken=data.getStringExtra(SavePickerActivity.EXTRA_TOKEN);
            if(saveToken!=null&&saveToken.equals(handledSaveToken))return;
            if(saveToken!=null){for(Bundle queued:pendingResults)if(saveToken.equals(queued.getString(SavePickerActivity.EXTRA_TOKEN)))return;}
            saveAccepted=SaveJobStore.validToken(saveToken)&&saveToken.equals(activeSaveToken);
            if(saveAccepted&&result!=RESULT_OK)activeSaveToken=null;
        }
        if(request!=BATCH&&request!=CAMERA&&request!=IMPORT&&request!=BACKUP&&request!=SAVE)return;
        // Only stable URI/context fields enter saved instance state. Never retain a
        // returned thumbnail Bitmap or arbitrary external Intent extras.
        Bundle event=new Bundle();event.putInt("request",request);event.putInt("result",result);
        event.putString("owner",document!=null?document.id:startupDocumentId);
        if(request==CAMERA){
            event.putString("cameraName",receipt!=null?receipt.name:getPreferences(0).getString("cameraName",null));event.putString("owner",receipt!=null?receipt.owner:getPreferences(0).getString("cameraDocument",null));
            if(receipt!=null)event.putString("singleCaptureToken",receipt.token);
        }
        if(request==BATCH){String id=data==null?null:data.getStringExtra(CaptureActivity.EXTRA_SESSION);event.putString("batch",id!=null?id:getPreferences(0).getString("captureBatch",null));}
        if(request==SAVE){if(saveToken!=null)event.putString(SavePickerActivity.EXTRA_TOKEN,saveToken);event.putInt("saveAccepted",saveAccepted?1:0);}
        // New SAVE callbacks never infer their source from mutable pendingExport.
        // Legacy queued Bundles retain their old source field for explicit recovery only.
        if(data!=null){
            event.putInt("flags",data.getFlags());event.putParcelable("uri",data.getData());
            if(request==IMPORT){ArrayList<Uri> images=new ArrayList<>();if(data.getClipData()!=null){for(int i=0;i<data.getClipData().getItemCount();i++){Uri uri=data.getClipData().getItemAt(i).getUri();if(uri!=null)images.add(uri);}}else if(data.getData()!=null)images.add(data.getData());event.putParcelableArrayList("images",images);}
        }
        pendingResults.addLast(event);scheduleResultDrain();
    }
    private void restorePendingResultState(Bundle saved){if(saved!=null){ArrayList<Bundle> previous=saved.getParcelableArrayList("pendingActivityResults");if(previous!=null)pendingResults.addAll(previous);}}
    private void savePendingResultState(Bundle out){stateSaved=true;if(!pendingResults.isEmpty())out.putParcelableArrayList("pendingActivityResults",new ArrayList<>(pendingResults));}
    private boolean canDrainResult(){return alive()&&resumed&&!stateSaved&&!busy&&!startupPending;}
    private void scheduleResultDrain(){
        if(pendingResults.isEmpty()){scheduleCaptureDrain();return;}
        if(resultDrainScheduled||pendingResults.isEmpty()||!canDrainResult())return;
        resultDrainScheduled=true;handler.post(()->{
            resultDrainScheduled=false;if(!canDrainResult())return;
            Bundle event=pendingResults.pollFirst();if(event==null)return;
            // Remove only at dispatch: an already-started import is not blindly
            // replayed after recreation, which would duplicate completed pages.
            try{processActivityResult(event);}finally{scheduleResultDrain();}
        });
    }
    private void retainPickerPermission(Uri uri,int flags,boolean writing){
        int needed=writing?Intent.FLAG_GRANT_WRITE_URI_PERMISSION:Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if(uri==null||(flags&Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)==0||(flags&needed)==0)return;
        try{getContentResolver().takePersistableUriPermission(uri,flags&needed);}catch(SecurityException ignored){/* Some providers only grant this Activity/task temporary access; the actual read/write still surfaces failures. */}
    }
    private void processActivityResult(Bundle event){
        int request=event.getInt("request"),result=event.getInt("result");Uri uri=event.getParcelable("uri");int flags=event.getInt("flags");
        if(request==BATCH){
            String id=event.getString("batch");if(id==null)return;
            if(result==RESULT_OK)importCaptureBatch(id);else{if(document!=null)showDocument();toast("连续扫描原文件已保留；点“连续扫描”可继续或整理");}return;
        }
        if(request==CAMERA){
            String name=event.getString("cameraName"),id=event.getString("owner");if(name==null)return;
            String token=event.getString("singleCaptureToken");
            if(token!=null){
                CaptureRequest receipt=recordedCaptureRequest();if(receipt==null||!token.equals(receipt.token)||receipt.phase!=CaptureRequest.LAUNCHED)return;
                CaptureRequest done=receipt.phase(CaptureRequest.DONE);
                if(!recordCaptureRequest(done,false)){toast("拍照结果暂未能记录，原图保留在拍照收件箱；请检查存储后找回");return;}
                if(captureRequest!=null&&captureRequest.same(receipt))captureRequest=done;
            }
            // Do not clear the pointer until the event can actually be handled,
            // and never clear a newer capture's ownership information.
            if(name.equals(getPreferences(0).getString("cameraName",null)))getPreferences(0).edit().remove("cameraName").remove("cameraDocument").apply();
            runIO("正在恢复拍照文稿…",()->{
                File image=CameraInbox.file(this,name);Uri capture=Uri.parse("content://"+getPackageName()+".files/camera/"+name);
                revokeUriPermission(capture,Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if(result!=RESULT_OK||image.length()==0)return image.length()>0?"文稿未改变；已写入的照片保留在拍照收件箱":"已取消拍照，文稿未改变";
                return store.load(id);
            },value->{
                if(value instanceof String){toast((String)value);return;}
                document=(Models.Document)value;showDocument();ArrayList<Uri> images=new ArrayList<>();images.add(Uri.parse("content://"+getPackageName()+".files/camera/"+name));importImages(images);
            });return;
        }
        if(request==SAVE){processSaveResult(event);return;}
        if(result!=RESULT_OK)return;
        if(request==BACKUP&&uri!=null){runIO("正在校验和恢复原图…",()->{retainPickerPermission(uri,flags,false);try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("备份无法读取");return store.restore(in);}},r->{document=(Models.Document)r;showDocument();toast("已恢复为独立文稿，原有文稿不变");});}
        else if(request==IMPORT){
            ArrayList<Uri> images=event.getParcelableArrayList("images");String owner=event.getString("owner");if(images==null||images.isEmpty())return;
            runIO("正在恢复导入文稿…",()->{for(Uri image:images)retainPickerPermission(image,flags,false);if(owner==null)throw new IOException("原文稿未能恢复，请重新打开文稿后选择图片；已有原图保留");return store.load(owner);},value->{document=(Models.Document)value;showDocument();importImages(images);});
        }

    }
    private interface Task{Object run() throws Exception;}private interface Done{void accept(Object value);}
    private void runIO(String label,Task task,Done done){
        runIO(label,task,done,false);
    }
    private void runIO(String label,Task task,Done done,boolean cancellable){
        if(busy||!alive()){toast("请等待当前操作完成");return;}
        busy=true;cancelOperation=false;int epoch=++operationEpoch;
        progress=new OperationOverlay(this,root,label,cancellable?()->{
            cancelOperation=true;Thread running=operationThread;if(running!=null)running.interrupt();
            if(progress!=null)progress.setMessage("正在取消，已保存页面仍保留…");
        }:null);
        if(watchdog!=null)watchdog.phase("operation-wait");
        try{operations.execute(()->{
            operationThread=Thread.currentThread();
            try{if(cancelOperation)throw new IOException("已取消，文稿仍保留");Object value=task.run();
                handler.post(()->{if(epoch!=operationEpoch||!alive())return;finishOperation();try{done.accept(value);}finally{scheduleResultDrain();}});
            }catch(Exception|OutOfMemoryError|LinkageError e){
                handler.post(()->{if(epoch!=operationEpoch||!alive())return;finishOperation();try{error(e);}finally{scheduleResultDrain();}});
            }finally{operationThread=null;Thread.interrupted();}
        });}catch(RejectedExecutionException e){finishOperation();if(alive())error(e);scheduleResultDrain();}
    }
    private void finishOperation(){busy=false;if(progress!=null){progress.dismiss();progress=null;}if(watchdog!=null)watchdog.phase("operation-done");}
    private void about(){new AlertDialog.Builder(this).setTitle("望舒扫描 · 本地优先").setMessage("拍照或导图后，原始字节单独保存在应用私有目录。新拍照先保存在文件收件箱，导入失败可从“拍照收件箱”找回或保存原文件；不会自动删除。所有裁切和增强都可重做，移除页面不会删除原图。\n\n应用没有网络权限，不上传图片，无账户、广告或分析统计。分享由您选择接收应用；系统相册/文件提供者可能包含云盘。\n\n卸载应用会删除私有文稿，请先导出 PDF，并用 ZIP 备份原图与参数。黑白可能损失浅色笔迹，可随时返回彩色。\n\n可选本地中英 OCR 和可搜索 PDF；识别文字可能有误，公式和手写以原图为准。自动裁切仅为建议，复杂背景请手调。真实手机兼容性仍需验收。").setPositiveButton("知道了",null).setNeutralButton("开源许可",(d,w)->notices()).show();}
    private void notices(){try{StringBuilder all=new StringBuilder();String[] files=getAssets().list("notices");if(files!=null)for(String name:files){all.append("\n—— ").append(name).append(" ——\n");try(InputStream in=getAssets().open("notices/"+name)){all.append(new String(Store.readLimited(in,1024*1024),java.nio.charset.StandardCharsets.UTF_8));}}TextView t=text(all.toString(),12,INK);t.setTextIsSelectable(true);ScrollView s=new ScrollView(this);s.addView(t);new AlertDialog.Builder(this).setTitle("第三方开源许可").setView(s).setPositiveButton("关闭",null).show();}catch(Exception e){error(e);}}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private void error(Throwable e){
        String detail=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
        boolean batchUncertain=e instanceof BatchFence.Uncertain;
        boolean uncertain=batchUncertain||e instanceof ManifestFence.Uncertain||(document!=null&&document.manifestUncertain);
        String guidance=batchUncertain?"\n\n批次记录结果未确认。请勿再次拍摄或整理该批次；原图和记录保留，待核对恢复。":uncertain?"\n\n请不要重试或继续修改该文稿；保留原图和所有文稿记录，待核对恢复。":e instanceof ManifestRecovery.Incomplete?"\n\n原文稿和部分副本保留在应用私有目录，部分副本可能暂时无法在应用内查看。请勿卸载或清理应用，不要重复恢复，待进一步核对。":"\n\n原图不会被覆盖。保存到外部位置失败时，目标可能留有不完整文件，可选择其他位置重试。";
        AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle("操作未完成").setMessage(detail+guidance).setPositiveButton("知道了",(ignoredDialog,which)->{if(uncertain&&document!=null&&document.manifestUncertain){if(screen==1)showDocument();else if(screen==2&&!cropping)buildEditor();}});
        if(!uncertain&&e instanceof SaveWriteFailure){String token=((SaveWriteFailure)e).token;dialog.setNeutralButton("重新选择位置",(d,w)->recoverSaveResult(token,null));}dialog.show();
    }
    private void handleBack(){if(busy){toast("正在保存，请稍候");return;}if(screen==2)leaveEditor();else if(screen==1)showLibrary("");else finish();}
    // API 33+ uses the platform callback above; this override handles API 26–32 only.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed(){handleBack();}
}
