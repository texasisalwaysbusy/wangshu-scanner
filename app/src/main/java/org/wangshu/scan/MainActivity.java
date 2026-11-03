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
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputType;
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
    private String originalPageBaseline;
    private Object editorSession;
    private final EditorSaveFlow editorSaves=new EditorSaveFlow();
    private EditorSaveFlow.Saved pendingEditorSave;
    private boolean startupEditorSaveSubmitted;
    private String editorDocumentVersion="",startupDocumentVersion="";
    private EditRecoveryState recovery;
    private EditorLifecycleState lifecycleState,startupState;
    private EditorLifecycleState.Snapshot lifecycleSnapshot;
    private Throwable lifecycleProblem;
    private RecoverySourceGate.Ticket recoveryLoadTicket;
    private boolean navigationCheckRunning;
    private long navigationCycle;
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
    private String startupOriginal;
    // Deliberately not restored after process death: old outputs lack crop-review provenance.
    private File approvedExportSource;
    private String approvedExportDigest;
    private long approvedExportBytes;
    private final HashSet<String> approvedSaveTokens=new HashSet<>();
    private PreparedText preparedText;
    private OcrCandidate.Result ocrCandidate;
    private final HashMap<String,PreparedText> textSavePermits=new HashMap<>();
    private String pendingKind="pdf";
    private boolean exportA4=true;
    private int exportEdge=2800;
    private TextView editorStatus,ocrStatus;
    private volatile Thread operationThread;
    private volatile boolean cancelOperation;

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        watchdog=MainThreadWatchdog.forActivity(this,handler);watchdog.start();watchdog.phase("create");
        palette();store=new Store(this);
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::handleBack);
        startupDocumentId=saved!=null?saved.getString("document"):getPreferences(0).getString("lastDocument",null);
        startupDraft=saved==null?null:saved.getString("draft");
        startupOriginal=saved==null?null:saved.getString("draftOriginal");
        startupEditorSaveSubmitted=saved!=null&&saved.getBoolean("editorSaveSubmitted",false);
        startupDocumentVersion=saved==null?"":saved.getString("draftDocumentVersion","");
        recovery=saved==null?null:EditRecoveryState.read(saved::getString); // read legacy envelopes
        try{startupState=saved==null?null:EditorLifecycleState.read(saved::getString);}catch(Exception problem){lifecycleProblem=problem;}
        if(saved!=null&&saved.getString("pendingExport")!=null){pendingExport=new File(saved.getString("pendingExport"));pendingKind=saved.getString("pendingKind","pdf");}
        if(saved!=null&&saved.getString("recentPdf")!=null){recentPdf=new File(saved.getString("recentPdf"));recentPdfRevision=boundedPdfRevision(saved.getString("recentPdfRevision",""));}
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
        final EditRecoveryState restoring=recovery;
        runIO("正在读取本地文稿…",()->{
            Throwable recoveryProblem=lifecycleProblem;EditorLifecycleState.Snapshot state=null;
            if(startupState!=null)try{state=startupState.load(getFilesDir());}catch(Exception|OutOfMemoryError|LinkageError problem){recoveryProblem=problem;}
            if(restoring!=null&&restoring.entry!=null)try{restoring.loaded(EditDraftStore.recovery(getFilesDir()).load(restoring.owner,restoring.entry));}
                catch(Exception|OutOfMemoryError|LinkageError problem){recoveryProblem=problem;}
            Object loaded;try{loaded=share?store.create():(id==null?null:store.load(id));}
                catch(Exception|OutOfMemoryError|LinkageError problem){loaded=problem;}
            return new StartupValue(loaded,recoveryProblem,state);
        },value->completeStartup(startupGeneration,value,share,uris));
    }
    private static final class StartupValue {
        final Object document;final Throwable recoveryProblem;final EditorLifecycleState.Snapshot state;
        StartupValue(Object document,Throwable problem,EditorLifecycleState.Snapshot state){this.document=document;recoveryProblem=problem;this.state=state;}
    }
    private void completeStartup(int expectedGeneration,Object value,boolean share,ArrayList<Uri> uris){
        if(generation!=expectedGeneration||document!=null)return;
        StartupValue loaded=(StartupValue)value;
        String encoded=startupDraft,baseline=startupOriginal,version=startupDocumentVersion,owner=startupDocumentId;
        boolean submitted=startupEditorSaveSubmitted;
        if(loaded.state!=null){
            EditDraftStore.Draft state=loaded.state.draft;
            if(loaded.state.recovery)recovery=new EditRecoveryState(state.document,state);
            else{encoded=state.edited;baseline=state.baseline;version=state.version;owner=state.document;submitted=state.submitted;}
            lifecycleState=startupState;lifecycleSnapshot=loaded.state;
        }
        if(loaded.state==null&&startupState!=null)lifecycleState=startupState; // keep a retryable receipt on transient load failure
        startupState=null;
        // Transfer every rejected editor snapshot to the recovery holder before clearing startup fields.
        if(loaded.document instanceof Models.Document)document=(Models.Document)loaded.document;
        if(recovery==null&&encoded!=null){
            try{
                if(document==null)throw new IOException("原文稿暂不可读取");
                restoreDraft(encoded,baseline,version,submitted);
            }catch(Exception problem){
                recovery=new EditRecoveryState(owner==null?"unknown":owner,
                        new EditDraftStore.Draft(owner==null?"unknown":owner,version,baseline==null?"":baseline,encoded,submitted,0));
            }
        }
        startupPending=false;startupDocumentId=null;startupDraft=null;startupOriginal=null;startupDocumentVersion="";startupEditorSaveSubmitted=false;
        if(recovery!=null){
            showRecovery("待核对编辑未自动应用。恢复记录不代表页面已保存；已保存页面不会被旧稿覆盖。");
            if(!recovery.verified&&recovery.data!=null)retainRecovery(recovery);
            else if(loaded.recoveryProblem!=null)error(loaded.recoveryProblem);
            return;
        }
        // restoreDraft may already have built the still-current editor.
        if(screen!=2){if(document!=null)showDocument();else showLibrary("");}
        if(loaded.recoveryProblem!=null)error(loaded.recoveryProblem);
        if(loaded.document instanceof Throwable)toast("上次文稿未能恢复，请查看文稿列表");
        if(share&&document!=null&&!uris.isEmpty())importImages(uris);
    }
    private void restoreDraft(String encoded,String baseline,String version,boolean submitted)throws Exception {
        Models.Page edited=strictCheckpointPage(encoded),old=strictCheckpointPage(baseline);
        EditRecoveryPolicy.Identity identity=identity(document.id,old);
        if(!EditRecoveryPolicy.sameSource(identity,identity(document.id,edited)))throw new IOException("草稿原图身份不一致");
        for(Models.Page current:document.active())if(current.id.equals(edited.id)){
            EditRecoveryPolicy.Decision result=EditRecoveryPolicy.decide(identity,identity(document.id,current),version,
                    document.savedSnapshot,baseline,encoded,current.json().toString(),submitted);
            if(result==EditRecoveryPolicy.Decision.BASELINE){openEditor(current,edited);return;}
            if(result==EditRecoveryPolicy.Decision.CONTENTS_SAVED){openEditor(current);toast("当前保存内容与记录相同；已打开当前版本，不自动跳页");return;}
        }
        throw new IOException("页面或文稿版本已变化，旧编辑仅保留为隔离记录");
    }
    @Override protected void onStart(){super.onStart();if(watchdog!=null){watchdog.start();watchdog.phase("start");}}
    @Override protected void onResume(){super.onResume();resumed=true;stateSaved=false;if(watchdog!=null)watchdog.phase("resume");SystemBars.apply(this,!dark());deliverPendingEditorSave();scheduleResultDrain();scheduleCaptureDrain();refreshInboxNotice();if(lifecycleProblem!=null){Throwable problem=lifecycleProblem;lifecycleProblem=null;error(problem);}}
    @Override protected void onPause(){resumed=false;navigationCycle++;super.onPause();}
    @Override protected void onStop(){if(watchdog!=null)watchdog.stop();super.onStop();}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(watchdog!=null)watchdog.phase(focused?"focus-gained":"focus-lost");}
    private boolean dark(){return (getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;}
    private void palette(){INK=getColor(R.color.ink);MUTED=getColor(R.color.muted);TEAL=getColor(R.color.accent);PAPER=getColor(R.color.paper);SURFACE=getColor(R.color.surface);ON_ACCENT=getColor(R.color.on_accent);PREVIEW=getColor(R.color.preview);}
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration){super.onConfigurationChanged(configuration);if(screen==2&&draft!=null&&!busy){if(cropping)reframeCrop();else buildEditor();}}
    @Override protected void onSaveInstanceState(Bundle out){
        navigationCycle++;
        savePendingResultState(out);if(captureRequest!=null&&captureRequest.phase<=CaptureRequest.LAUNCHED&&currentCapture(captureRequest))out.putString("singleCaptureToken",captureRequest.token);super.onSaveInstanceState(out);
        if(document!=null)out.putString("document",document.id);
        else if(startupPending&&startupDocumentId!=null)out.putString("document",startupDocumentId);
        saveEditorLifecycle(out::putString);
        if(pendingExport!=null){out.putString("pendingExport",pendingExport.getPath());out.putString("pendingKind",pendingKind);}
        if(recentPdf!=null){out.putString("recentPdf",recentPdf.getPath());out.putString("recentPdfRevision",boundedPdfRevision(recentPdfRevision));}
    }
    private void saveEditorLifecycle(EditorLifecycleState.Write out){
        if(startupPending&&startupState!=null){startupState.write(out);return;}
        if(recovery!=null&&recovery.entry!=null&&(recovery.verified||recovery.data==null)){recovery.writeReference(out::put);return;}
        EditorLifecycleState.Snapshot state;EditorLifecycleState previous=lifecycleState;
        try{
            if(recovery!=null&&recovery.data!=null)state=new EditorLifecycleState.Snapshot(recovery.data,true);
            else if(draft!=null){
                if(cropping&&cropView!=null)draft.quad=cropView.getQuad();
                state=new EditorLifecycleState.Snapshot(new EditDraftStore.Draft(document.id,editorDocumentVersion,originalPageBaseline,draft.json().toString(),editorSaves.hasPending()||pendingEditorSave!=null,0),false);
            }else if(startupPending&&startupDraft!=null)state=new EditorLifecycleState.Snapshot(new EditDraftStore.Draft(startupDocumentId,startupDocumentVersion,startupOriginal,startupDraft,startupEditorSaveSubmitted,0),false);
            else{if(lifecycleState!=null)lifecycleState.write(out);return;}
            if(!sameLifecycleSnapshot(state,lifecycleSnapshot)||lifecycleState==null||lifecycleState.memoryOnly){
                try{lifecycleState=EditorLifecycleState.capture(getFilesDir(),state);lifecycleProblem=null;}
                catch(Exception|OutOfMemoryError|LinkageError problem){
                    if(lifecycleState==null||!lifecycleState.memoryOnly||!sameLifecycleSnapshot(state,lifecycleSnapshot))lifecycleState=EditorLifecycleState.keepInProcess(state,lifecycleState);
                    lifecycleProblem=new IOException("草稿生命周期快照未能写入，当前输入仍在内存；进程结束后可能无法恢复。请返回后保存或暂存，不要清除应用数据。",problem);
                    toast("草稿快照未落盘，当前输入保留；请返回后处理");
                }
                lifecycleSnapshot=state;
            }
            lifecycleState.write(out);
            if(previous!=lifecycleState)EditorLifecycleState.release(previous);
        }catch(Exception problem){lifecycleProblem=problem;toast("草稿状态未能保留；当前输入仍在界面，请返回后处理");}
    }
    private void releaseLifecycleMemory(){EditorLifecycleState.release(lifecycleState);lifecycleState=null;lifecycleSnapshot=null;}
    private static boolean sameLifecycleSnapshot(EditorLifecycleState.Snapshot a,EditorLifecycleState.Snapshot b){
        if(a==null||b==null||a.recovery!=b.recovery)return false;
        EditDraftStore.Draft x=a.draft,y=b.draft;
        return x.document.equals(y.document)&&x.version.equals(y.version)&&Objects.equals(x.baseline,y.baseline)&&Objects.equals(x.edited,y.edited)&&x.submitted==y.submitted;
    }
    @Override protected void onDestroy(){
        editorSaves.abandon();pendingEditorSave=null;editorSession=null;navigationCheckRunning=false;
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
        generation++;previewGeneration++;cropping=false;cropView=null;preview=null;ocrStatus=null;releaseImages();
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
        cancelPendingCapture();screen=0;document=null;draft=null;originalPage=null;originalPageBaseline=null;editorSession=null;editorDocumentVersion="";getPreferences(0).edit().remove("lastDocument").apply();
        base("望舒扫描","离线保存 · 原图保留 · 无需账户",null);
        body.addView(button("找回编辑恢复记录（含冲突稿）",this::browseRecovery));
        if(getPreferences(0).getString("captureBatch",null)!=null)body.addView(button("找回未整理的连续扫描",this::recoverCaptureBatch));
        EditText search=new EditText(this);search.setSingleLine();search.setHint("搜索名称或已识别文字");search.setText(query);body.addView(search);
        LinearLayout actions=row();weighted(actions,primary("＋ 新建文稿",()->runIO("正在创建文稿…",store::create,value->{document=(Models.Document)value;showDocument();})));weighted(actions,button("恢复备份",this::pickBackup));body.addView(actions);
        LinearLayout utility=row();weighted(utility,button("隐藏的文稿",this::restoreDocuments));weighted(utility,button("拍照收件箱",this::cameraInbox));weighted(utility,button("关于与隐私",this::about));body.addView(utility);
        body.addView(button("查看备份恢复暂存",this::inspectRestoreIntake));
        body.addView(button("核对未完成的保存",this::inspectSavedJob));
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
        screen=1;draft=null;originalPage=null;originalPageBaseline=null;editorSession=null;editorDocumentVersion="";getPreferences(0).edit().putString("lastDocument",document.id).apply();
        base(document.title,"所有改动保留原图 · 点击页面进行编辑",()->showLibrary(""));
        if(document.manifestUncertain)body.addView(text("文稿保存状态未确认；请勿继续修改或导出。原图和记录候选保留，待核对恢复。",16,INK));
        LinearLayout top=row();weighted(top,button("重命名",this::rename));if(document.pages.stream().anyMatch(page->page.removed))weighted(top,button("恢复页面",this::restorePages));weighted(top,button("更多",this::documentMenu));body.addView(top);
        ScrollView scroll=new ScrollView(this);LinearLayout list=column();scroll.addView(list);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        Models.Document owner=document;ArrayList<Models.Page> pages=document.active();int viewGeneration=generation;
        // Global inbox is not tied to this document: keep a discoverable neutral
        // entry even before/after a delayed receipt arrives; user chooses the action.
        list.addView(button("逐页核对 / 纠错识别文字",this::showOcrReviewPages));
        list.addView(button("另存文字副本 · UTF-8",this::textExportOptions));
        list.addView(button("查找生命周期草稿快照",this::recoverLifecycleDrafts));
        inboxNotice=button("拍照收件箱 · 检查待核对文件",this::cameraInbox);list.addView(inboxNotice);refreshInboxNotice();
        if(pages.isEmpty())list.addView(text("这份文稿还没有页面\n\n拍照，或一次导入多张图片。之后可以裁切、增强、调整顺序。",18,MUTED));
        for(int i=0;i<pages.size();i++){
            Models.Page p=pages.get(i);int pos=i;
            LinearLayout card=row();card.setPadding(dp(8),dp(8),dp(4),dp(8));card.setBackground(rounded(SURFACE,12));
            ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setBackgroundColor(PREVIEW);image.setContentDescription("编辑第 "+(i+1)+" 页");card.addView(image,new LinearLayout.LayoutParams(dp(88),dp(112)));image.setOnClickListener(v->openEditor(p));
            LinearLayout info=column();info.setPadding(dp(8),0,0,0);info.addView(text("第 "+(i+1)+" 页 · "+modeName(p.mode)+(p.cropConfirmed()?" · 四角已核对":" · 四角待核对"),16,INK));info.addView(button("编辑 / 裁切",()->openEditor(p)));
            LinearLayout controls=row();Button up=pageAction("↑",()->move(p,-1));up.setContentDescription("第 "+(i+1)+" 页上移");up.setEnabled(i>0);weighted(controls,up);Button down=pageAction("↓",()->move(p,1));down.setContentDescription("第 "+(i+1)+" 页下移");down.setEnabled(i<pages.size()-1);weighted(controls,down);weighted(controls,pageAction("移除",()->{saveThenShow(owner,()->DocumentMutations.removed(owner,p,true,store::saveIfCurrent),()->toast("页面已移入恢复区，原图未删除"));}));info.addView(controls);card.addView(info,new LinearLayout.LayoutParams(0,-2,1));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(5),0,dp(5));list.addView(card,lp);
            Models.Document d=document;worker.execute(()->{try{Bitmap b=Renderer.thumbnail(store.original(d,p),p,220);runOnUiThread(()->{if(generation==viewGeneration&&!isFinishing())image.setImageBitmap(b);else b.recycle();});}catch(Exception e){runOnUiThread(()->{if(generation==viewGeneration)image.setContentDescription("第 "+(pos+1)+" 页无法预览："+e.getMessage());});}});
        }
        LinearLayout add=row();weighted(add,button("相册导入",this::pickImages));weighted(add,button("拍照添加",this::capture));weighted(add,button("连续扫描",this::continuousCapture));root.addView(add);
        Button export=primary("导出 PDF · "+pages.size()+" 页",this::exportOptions);export.setEnabled(!pages.isEmpty());export.setAlpha(1f);export.setMinHeight(dp(52));root.addView(export,new LinearLayout.LayoutParams(-1,-2));
        scheduleCaptureDrain();
    }
    private String modeName(String m){return m.equals("BW")?"黑白":m.equals("GRAY")?"灰度":"彩色";}
    private void inspectSavedJob(){
        runIO("正在核对保存记录…",()->saveJobs().read(),value->{
            SaveJobStore.Job job=(SaveJobStore.Job)value;
            if(job==null||job.phase==SaveJobStore.DONE||job.phase==SaveJobStore.CANCELLED){toast("没有待处理的保存请求");return;}
            String info="文件："+job.title.replaceAll("[\\p{Cntrl}]","?")+"\n大小："+job.bytes+" 字节\n源指纹："+job.sha.substring(0,12)+"\n\n导出源位于应用私有存储或缓存；缓存可能被系统清理。不会自动改写以前选择的位置。";
            if((job.phase==SaveJobStore.WRITING||job.phase==SaveJobStore.NEEDS_RESELECT)&&"text/plain".equals(job.mime)){new AlertDialog.Builder(this).setTitle("文字保存尚未确认").setMessage(info+"\n\n旧目标可能不完整。可核对保留的文字快照后，主动选择一个新文件；不会重写旧目标。").setPositiveButton("文字快照另存新文件",(d,w)->recoverTextSave(job.token)).setNeutralButton("结束旧文字请求",(d,w)->endTextSave(job.token)).setNegativeButton("保留记录",null).show();return;}
            if(job.phase==SaveJobStore.WRITING||job.phase==SaveJobStore.NEEDS_RESELECT){new AlertDialog.Builder(this).setTitle("保存结果尚未确认").setMessage(info+"\n\n目标可能已写入或只写入一部分。旧记录无法安全自动结束，新保存暂时受阻；请保留应用数据并等待进一步核对。").setPositiveButton("知道了",null).show();return;}
            new AlertDialog.Builder(this).setTitle("结束未完成的保存请求？").setMessage(info+"\n\n可结束此请求以允许新保存。源文件不删除；曾选目标可能已创建空文件，迟到结果不会自动写入。确认后如果状态已变化，将重新核对。").setPositiveButton("结束此请求",(d,w)->runIO("正在结束保存请求…",()->{SaveJobStore jobs=saveJobs();jobs.cancel(job.token);return jobs.read();},after->{SaveJobStore.Job end=(SaveJobStore.Job)after;if(end!=null&&job.token.equals(end.token)&&end.phase==SaveJobStore.CANCELLED){if(job.token.equals(activeSaveToken))activeSaveToken=null;handledSaveToken=job.token;textSavePermits.remove(job.token);approvedSaveTokens.remove(job.token);toast("已结束此请求；源文件未删除，可重新发起保存");}else toast("保存状态已变化或结果未知；记录保留，请再次核对");})).setNegativeButton("保留请求",null).show();
        });
    }
    private void inspectRestoreIntake(){
        if(activeSaveToken!=null){toast("已有保存请求尚未结束，请先处理；暂存和已有请求均保留");return;}
        RestoreIntake intake=new RestoreIntake(getFilesDir().toPath(),getCacheDir().toPath());
        runIO("正在只读检查恢复暂存…",intake::list,value->{
            @SuppressWarnings("unchecked") List<RestoreIntake.Entry> entries=(List<RestoreIntake.Entry>)value;
            if(entries.isEmpty()){toast("没有可列出的恢复暂存");return;}
            String[] labels=new String[entries.size()];for(int i=0;i<labels.length;i++){RestoreIntake.Entry e=entries.get(i);labels[i]=e.name.replaceAll("[\\p{Cntrl}]","?")+" · "+(e.selectable()?e.bytes+" 字节 · 状态未知":"不可导出 · "+(e.issue==null?"空文件":e.issue));}
            new AlertDialog.Builder(this).setTitle("恢复暂存 · 只读").setMessage("这里的已接收数据可能只是备份的一部分，不代表完整 ZIP。尚未读取的来源内容不在这里。不会自动删除任何暂存。").setItems(labels,(dialog,which)->{
                RestoreIntake.Entry chosen=entries.get(which);if(!chosen.selectable()){toast("此项无法安全导出；原暂存保留");return;}
                runIO("正在核对已接收的片段…",()->intake.inspect(chosen),result->{RestoreIntake.Snapshot snap=(RestoreIntake.Snapshot)result;
                    new AlertDialog.Builder(this).setTitle("导出已接收的片段？").setMessage("仅导出已收到的 "+chosen.bytes+" 字节；可能不完整，也无法找回来源中尚未读取的数据。副本以 .bin 标识，先在本地创建，需额外空间。原暂存不删除。\n校验指纹："+snap.sha.substring(0,12))
                        .setPositiveButton("创建副本并选择位置",(d,w)->{if(activeSaveToken!=null){toast("已有保存请求尚未结束；暂存保留");return;}runIO("正在复制已接收片段…",()->intake.copy(snap),copied->{if(activeSaveToken!=null){toast("已有保存请求；片段副本与原暂存均保留");return;}pendingExport=(File)copied;approvedExportSource=pendingExport;approvedExportDigest=snap.sha;approvedExportBytes=chosen.bytes;pendingKind="bin";saveExport();},true);}).setNegativeButton("取消",null).show();
                },true);
            }).setNegativeButton("关闭",null).show();
        },true);
    }
    private boolean matches(Models.Document d,String q){if(d.title.toLowerCase(Locale.ROOT).contains(q))return true;for(Models.Page p:d.active())if(p.cropConfirmed()&&p.ocrKey.equals(OcrEngine.key(p))&&p.ocrText.toLowerCase(Locale.ROOT).contains(q))return true;return false;}
    private interface SavedEdit {boolean save()throws Exception;}
    private void saveEdit(Models.Document owner,SavedEdit edit,Runnable after){
        if(owner==null||document!=owner||busy||recovery!=null){toast("请先处理当前编辑恢复记录或等待操作完成");return;}
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
    private void documentMenu(){new AlertDialog.Builder(this).setTitle("文稿操作").setItems(new String[]{"备份原图与编辑参数（ZIP）","隐藏文稿（保留文件）","查看原图保留说明","本地识别文字（中英）","查看 / 复制 / 逐页纠错识别文字","找回中断导入（追加到末尾）","分享已生成的 PDF","再次保存已生成的 PDF","拍照收件箱（找回未导入照片）","查找已结束批次的迟到拍照","找回暂存编辑","预览已生成的 PDF"},(dialog,which)->{
        if(which==0){Models.Document owner=document;runIO("正在打包原图…",()->{Models.Document copy=store.snapshotForExport(owner);File file=Exporter.backup(store,copy,new File(getCacheDir(),"exports"));verifyExportSnapshot(owner,copy);return file;},f->{if(document!=owner){toast("文稿已切换，备份仍保留在本地缓存");return;}pendingExport=(File)f;approvedExportSource=pendingExport;approvedExportDigest=null;approvedExportBytes=0;pendingKind="zip";saveExport();});}
        else if(which==1){Models.Document owner=document;new AlertDialog.Builder(this).setMessage("文稿将从列表隐藏，原始文件仍保留在应用私有目录。请先导出 ZIP 备份；可通过备份恢复。是否隐藏？").setPositiveButton("隐藏",(d,w)->{saveEdit(owner,()->DocumentMutations.archived(owner,true,store::saveIfCurrent),()->showLibrary(""));}).setNegativeButton("取消",null).show();}
        else if(which==2)about();else if(which==3)recognizeText();else if(which==4)showText();else if(which==5){Models.Document target=document;runIO("正在查找已保存的原图…",()->store.recoverOrphans(target),count->{showDocument();toast("已找回 "+count+" 页，追加到文稿末尾；已有页面不变");});}else if(which==8)cameraInbox();else if(which==9)recoverClosedCapture();else if(which==10)recoverEditCheckpoint();else if(which==11)previewPdf();else reusePdf(which==6);
    }).show();}
    private void recognizeText(){if(document.active().isEmpty()){toast("请先添加页面");return;}new AlertDialog.Builder(this).setTitle("本地文字识别").setMessage("图片不上传。识别结果可能有错，数学公式和手写字尤其需要核对；原图不会被替换。重新识别会逐页替换已保存的识别文字，如需保留请先在“查看 / 复制识别文字”中复制。识别文字后可在首页搜索内容。").setPositiveButton("仅英文",(d,w)->runRecognition("eng")).setNeutralButton("中英混合",(d,w)->runRecognition("chi_sim+eng")).setNegativeButton("取消",null).show();}
    private void runRecognition(String language){Models.Document target=document;runIO("本地识别中 · 可取消…",()->{requireReviewedCrops(target);requireReviewedCrops(store.snapshotForExport(target));OcrEngine.recognize(store,target,language,(done,total)->runOnUiThread(()->{if(progress!=null)progress.setMessage("本地识别 · "+done+" / "+total);}));return null;},r->{showDocument();showText();},true);}
    /** Stale text remains visible for manual recovery but never becomes a current OCR result. */
    private static String describeOcrPages(Models.Document doc){
        StringBuilder value=new StringBuilder();ArrayList<Models.Page> pages=doc.active();
        for(int i=0;i<pages.size();i++){Models.Page page=pages.get(i);if(page.ocrText.isEmpty())continue;
            boolean current=page.ocrKey.equals(OcrEngine.key(page));value.append("—— 第 ").append(i+1).append(" 页 · ");
            value.append(current?"当前识别":"旧识别，仅供参考（不参与搜索或 PDF 文字层）");if(!page.cropConfirmed())value.append(" · 四角待核对，文字可能缺失");value.append(" ——\n").append(page.ocrText).append("\n\n");
        }return value.toString();
    }
    private void showText(){String value=describeOcrPages(document);if(value.isEmpty()){showOcrReviewPages();return;}TextView t=text(value,16,INK);t.setTextIsSelectable(true);ScrollView scroll=new ScrollView(this);scroll.setPadding(dp(16),0,dp(16),0);scroll.addView(t);new AlertDialog.Builder(this).setTitle("已保存的识别文字 · 请核对原图").setView(scroll).setPositiveButton("复制全部",(d,w)->{((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("识别文字",value));toast("已复制已保存的文字");}).setNeutralButton("逐页纠错",(d,w)->showOcrReviewPages()).setNegativeButton("关闭",null).show();}
    private static String ocrPageStatus(Models.Page page){
        if(page.ocrKey.isEmpty())return "尚无识别基线 · 文字仅供查看 / 复制";
        if(!page.ocrKey.equals(OcrEngine.key(page)))return "旧识别 · 不参与搜索";
        if(!page.cropConfirmed())return "四角待核对 · 不参与搜索";
        return page.ocrText.isEmpty()?"当前识别文字为空":"当前图像对应的文字 · 请人工核对";
    }
    private void showOcrReviewPages(){
        Models.Document owner=document;if(owner==null)return;
        ArrayList<Models.Page> pages=owner.active();if(pages.isEmpty()){toast("请先添加页面");return;}
        String[] labels=new String[pages.size()];
        for(int i=0;i<pages.size();i++)labels[i]="第 "+(i+1)+" 页 · "+ocrPageStatus(pages.get(i));
        new AlertDialog.Builder(this).setTitle("选择页面 · 对照预览纠错").setItems(labels,(dialog,which)->{
            Models.Page page=pages.get(which);
            if(document!=owner||screen!=1||busy||!alive()||stateSaved||!resumed||owner.manifestUncertain||!owner.active().contains(page)){
                toast("文稿或页面状态已变化，请重新选择");return;
            }
            openEditor(page);
        }).setNegativeButton("关闭",null).show();
    }
    private static String ocrReviewStatus(Models.Page page,Models.Page saved){
        String state=saved!=null&&Objects.equals(page.ocrText,saved.ocrText)?"文字与已保存版本一致":"文字已修改 · 尚未保存";
        return state+" · "+(page.ocrOrigin.equals("manual")?"人工编辑过（不代表已校对无误）":page.ocrOrigin.equals("machine")?"自动识别":"来源未记录")+"\n"+ocrPageStatus(page);
    }
    /** Text callbacks belong to this exact draft and rendered editor, even for the same page ID. */
    private boolean updateOcrDraft(Object session,Models.Document owner,Models.Page page,int viewGeneration,String value){
        if(value==null||session==null||owner==null||page==null||document!=owner||draft!=page||editorSession!=session
                ||generation!=viewGeneration||screen!=2||cropping||busy||!alive()||stateSaved||!resumed
                ||owner.manifestUncertain||recovery!=null||editorSaves.hasPending())return false;
        if(!Objects.equals(page.ocrText,value))page.ocrOrigin="manual";page.ocrText=value; // Never refresh ocrKey/language: stale OCR stays stale after correction.
        return true;
    }
    private void addOcrEditor(LinearLayout controls){
        final Models.Document owner=document;final Models.Page page=draft;final Object session=editorSession;final int viewGeneration=generation;
        controls.addView(text("本页文字 · 对照上方预览核对 / 纠错",16,INK));
        TextView status=text(ocrReviewStatus(page,originalPage),13,MUTED);ocrStatus=status;controls.addView(status);
        EditText input=new EditText(this);input.setTextColor(INK);input.setHintTextColor(MUTED);input.setTextSize(16);
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);input.setSaveEnabled(false);
        input.setGravity(Gravity.TOP);input.setMinLines(4);input.setMaxLines(8);
        input.setHint("尚无文字，可手动抄录；不自动变为当前识别结果");input.setContentDescription("本页识别文字纠错，保存本页后生效");
        input.setText(page.ocrText); // Install restored text before attaching the listener.
        input.addTextChangedListener(new TextWatcher(){
            private boolean restoring;
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){}
            public void afterTextChanged(Editable value){
                if(restoring)return;
                if(updateOcrDraft(session,owner,page,viewGeneration,value.toString()))status.setText(ocrReviewStatus(page,originalPage));
                else{ // A rejected IME callback must not leave visible text that cannot be saved.
                    restoring=true;try{input.setText(page.ocrText);}finally{restoring=false;}
                }
            }
        });
        controls.addView(input,new LinearLayout.LayoutParams(-1,-2));
        controls.addView(button("单页重新识别 · 先比较，保留修订",this::singlePageOcrOptions));
        controls.addView(button("查看本次单页识别候选（未保存）",()->showOcrCandidate(ocrCandidate)));
        controls.addView(button("另存文字副本 · 选择已保存或草稿",this::textExportOptions));
        controls.addView(button("复制本页文字（含当前修改）",()->{
            if(document!=owner||draft!=page||editorSession!=session||generation!=viewGeneration||screen!=2)return;
            ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("本页文字",page.ocrText));
            toast("已复制本页文字；复制不会保存修改");
        }));
        controls.addView(text("页面需明确保存才会生效；切后台时可能自动保留私有草稿快照，放弃编辑也不删除快照。旧识别仍不参与搜索；整本重新识别会替换已保存文字，单页候选需确认采用。可搜索 PDF 会重新识别，不使用这里的纠错文字，公式和手写请以原图为准。",13,MUTED));
    }
    private void recoverLifecycleDrafts(){
        Models.Document owner=document;if(owner==null||busy)return;
        runIO("正在读取本地草稿快照…",()->EditDraftStore.lifecycle(getFilesDir()).list(owner.id),value->{
            if(document!=owner||screen!=1||stateSaved||!resumed)return;
            @SuppressWarnings("unchecked") List<EditDraftStore.Entry> entries=(List<EditDraftStore.Entry>)value;
            if(entries.isEmpty()){toast("没有可列出的生命周期草稿快照");return;}
            String[] labels=new String[entries.size()];for(int i=0;i<labels.length;i++){EditDraftStore.Entry e=entries.get(i);labels[i]=new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.CHINA).format(new Date(e.created))+(e.valid?" · 待核对原版本":" · 无法读取，文件保留");}
            new AlertDialog.Builder(this).setTitle("私有生命周期快照 · 不自动应用").setItems(labels,(dialog,index)->{
                EditDraftStore.Entry chosen=entries.get(index);if(!chosen.valid){toast("快照未通过核对，原文件保留");return;}
                runIO("正在核对草稿与已保存版本…",()->{EditDraftStore.Draft record=EditDraftStore.lifecycle(getFilesDir()).load(owner.id,chosen);Models.Document fresh=store.load(owner.id);Models.Page edited=recoveryEdited(fresh,record);recoveryOriginalVerified(fresh,edited);return new RecoveredLifecycle(record,fresh);},loaded->{
                    if(document!=owner||screen!=1||stateSaved||!resumed)return;
                    RecoveredLifecycle result=(RecoveredLifecycle)loaded;
                    try{openLifecycleDraft(result.document,result.draft);}catch(Exception problem){error(problem);}
                });
            }).setNegativeButton("关闭",null).show();
        });
    }
    private void openLifecycleDraft(Models.Document fresh,EditDraftStore.Draft record)throws Exception {
        // Refuse conflict while still on the document screen. The immutable lifecycle file remains discoverable.
        Models.Page edited=recoveryEdited(fresh,record);
        if(!fresh.savedSnapshot.equals(EditorSaveFlow.observedVersion(fresh.id)))throw new IOException("文稿在核对后已变化；快照保留，未载入");
        for(Models.Page page:fresh.active())if(page.id.equals(edited.id)){document=fresh;openEditor(page,edited);return;}
        throw new IOException("当前页面不可用；快照保留，未载入");
    }
    private boolean ocrCandidateReady(){return alive()&&resumed&&!stateSaved&&!busy&&screen==2&&!cropping&&recovery==null&&!editorSaves.hasPending()&&activeSaveToken==null;}
    private boolean ocrCandidateCurrent(OcrCandidate.Result result){return result!=null&&ocrCandidateReady()&&result.request.current(document,editorSession,originalPage,draft,originalPageBaseline,editorDocumentVersion,generation,navigationCycle);}
    private void singlePageOcrOptions(){
        if(!ocrCandidateReady()){toast("请先完成当前操作，原文字保留");return;}
        final OcrCandidate.Request request;
        try{request=new OcrCandidate.Request(document,editorSession,originalPage,draft,originalPageBaseline,editorDocumentVersion,generation,navigationCycle);}catch(Exception problem){error(problem);return;}
        new AlertDialog.Builder(this).setTitle("仅重识别第 "+request.number+" 页 · 先比较").setMessage("使用当前草稿的图像处理参数生成候选，不改已有文字或已保存页面。候选可能仍有错字，公式和手写以原图为准。您可保留当前修订、复制候选，或确认保留旧草稿后采用候选；采用后仍需保存本页。此操作不改变 PDF 文字层。候选仅在本次界面中保留，进程结束需重新识别。")
            .setPositiveButton("中英混合候选",(dialog,which)->runSinglePageOcr(request,"chi_sim+eng"))
            .setNeutralButton("仅英文候选",(dialog,which)->runSinglePageOcr(request,"eng")).setNegativeButton("保留当前文字",null).show();
    }
    private void runSinglePageOcr(OcrCandidate.Request request,String language){
        if(!ocrCandidateReady()||!request.current(document,editorSession,originalPage,draft,originalPageBaseline,editorDocumentVersion,generation,navigationCycle)){toast("页面或修订已变化，未开始重识别");return;}
        runIO("正在生成单页识别候选 · 原修订保留…",()->RecoverySourceGate.verifyAndRead(new File(getFilesDir(),"documents"),identity(request.owner.id,request.frozen),()->{
            store.assertCurrentReadable(request.owner);String text=OcrEngine.recognizePage(store,request.owner,request.frozen,language);store.assertCurrentReadable(request.owner);return request.result(text,language);
        }).value,value->{
            OcrCandidate.Result result=(OcrCandidate.Result)value;
            if(!ocrCandidateCurrent(result)){toast("页面、修订或界面状态已变化；迟到候选未应用，原文字保留");return;}
            ocrCandidate=result;showOcrCandidate(result);
        },true);
    }
    private void showOcrCandidate(OcrCandidate.Result result){
        if(result==null){toast("尚无单页识别候选，请先生成；已有文字保留");return;}
        if(!resumed||stateSaved||busy||!alive())return;
        boolean current=ocrCandidate==result&&ocrCandidateCurrent(result);OcrCandidate.Request request=result.request;
        ScrollView scroll=new ScrollView(this);LinearLayout panel=column();scroll.addView(panel);
        panel.addView(text("文稿："+request.owner.title+" · 第 "+request.number+" / "+request.order.size()+" 页\n"+(current?"可比较采用；两份文字都尚未自动保存":"已过期，仅查看或复制，不能采用到当前页面")+"\n采用前先保留完整旧草稿到独立恢复记录，保留失败则不替换。采用后仍需保存本页；PDF 文字层不变。",14,MUTED));
        panel.addView(text("原修订 · 识别启动时的草稿\n"+TextExport.origin(request.frozen.ocrOrigin)+"\n"+ocrPageStatus(request.frozen),14,INK));
        TextView previous=text(request.frozen.ocrText.isEmpty()?"（原文字为空）":request.frozen.ocrText,16,INK);previous.setTextIsSelectable(true);panel.addView(previous);
        panel.addView(text("新候选 · "+(result.language.equals("eng")?"仅英文":"中英混合")+" · 自动识别，未人工核对",14,INK));
        TextView proposed=text(result.text.isEmpty()?"（候选为空；采用会清空文字，旧草稿先保留）":result.text,16,INK);proposed.setTextIsSelectable(true);panel.addView(proposed);
        AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle("单页文字比较 · 未保存").setView(scroll).setNeutralButton("复制新候选",(d,w)->{((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("单页识别候选 · 未校对",result.text));toast("已复制候选；原修订未替换");}).setNegativeButton("保留原修订",null);
        if(current)dialog.setPositiveButton("保留旧稿并采用候选",(d,w)->adoptOcrCandidate(result));dialog.show();
    }
    private void adoptOcrCandidate(OcrCandidate.Result result){
        if(ocrCandidate!=result||!ocrCandidateCurrent(result)){toast("候选或当前修订已变化，未替换文字");return;}
        OcrCandidate.Request request=result.request;
        runIO("正在保留旧修订并核对候选…",()->RecoverySourceGate.verifyAndRead(new File(getFilesDir(),"documents"),identity(request.owner.id,request.frozen),()->{
            store.assertCurrentReadable(request.owner);
            OcrCandidate.Adoption adoption=result.retainPrevious(EditDraftStore.recovery(getFilesDir()),(next,record)->preflightEditorSave(request.owner,request.original,next,record));
            store.assertCurrentReadable(request.owner);return adoption;
        }).value,value->{
            if(ocrCandidate!=result||!ocrCandidateCurrent(result)||!result.apply((OcrCandidate.Adoption)value,document,editorSession,originalPage,draft,originalPageBaseline,editorDocumentVersion,generation,navigationCycle)){toast("状态已变化，候选未应用；旧修订记录保留");return;}
            ocrCandidate=null;buildEditor();toast("候选已放入草稿，尚未保存；旧修订在文库“找回编辑恢复记录”中保留；仅原版本一致时可载入");
        });
    }
    private static final class RecoveredLifecycle {final EditDraftStore.Draft draft;final Models.Document document;RecoveredLifecycle(EditDraftStore.Draft draft,Models.Document document){this.draft=draft;this.document=document;}}
    private static final class PreparedText {
        final TextExport.Request request;TextExport.Context context;final File file;final String digest,title,description;final long bytes;
        PreparedText(TextExport.Request request,File file,String digest,long bytes,String title,String description){this.request=request;this.file=file;this.digest=digest;this.bytes=bytes;this.title=title;this.description=description;}
        PreparedText historical(){return new PreparedText(null,file,digest,bytes,title,"先前生成的文字快照（未重新核对当前文稿）");}
    }
    private boolean textContextCurrent(PreparedText text){return !cropping&&text!=null&&(text.context==null||text.context.current(document,editorSession,draft,screen))&&(text.request==null||text.request.current(document,editorSession,draft,screen));}
    private void requireTextDiskCurrent(PreparedText text)throws Exception {
        if(text==null)throw new IOException("文字副本缺少来源凭据，请重新选择来源");
        if(text.request!=null){synchronized(Store.FILE_LOCK){store.assertCurrentReadable(text.request.owner);if(!text.request.version.equals(text.request.owner.savedSnapshot)||!text.request.order.equals(TextExport.order(text.request.owner)))throw new IOException("文稿或页序已变化，请重新生成文字快照");}}
    }
    private void textExportOptions(){
        if(document==null||!(screen==1||screen==2)||cropping||busy||recovery!=null||editorSaves.hasPending()||document.manifestUncertain||!resumed||stateSaved){toast("请先完成当前保存或恢复；草稿保留");return;}
        if(activeSaveToken!=null){toast("已有保存请求尚未结束，请先取消或处理保存记录");return;}
        Models.Document owner=document;Object session=editorSession;Models.Page page=draft;int view=generation,fromScreen=screen;
        ArrayList<String> labels=new ArrayList<>();ArrayList<TextExport.Source> sources=new ArrayList<>();
        labels.add("整本文稿 · 已保存文字");sources.add(TextExport.Source.SAVED_DOCUMENT);
        if(screen==2){labels.add("仅当前页 · 已保存文字");sources.add(TextExport.Source.SAVED_PAGE);labels.add("仅当前页 · 当前草稿副本（未保存）");sources.add(TextExport.Source.DRAFT_PAGE);}
        PreparedText previous=preparedText;boolean hasPrevious=previous!=null&&previous.file.isFile();if(hasPrevious)labels.add("上次生成的文字快照 · "+previous.title);
        labels.add("查找上次文字保存记录（缓存可能被清理）");
        new AlertDialog.Builder(this).setTitle("选择文字来源 · 导出不等于保存页面").setItems(labels.toArray(new String[0]),(dialog,index)->{
            if(document!=owner||screen!=fromScreen||generation!=view||editorSession!=session||draft!=page||busy||recovery!=null||!resumed||stateSaved){toast("页面状态已变化，请重新选择文字来源");return;}
            if(index<sources.size()){createTextExport(sources.get(index));return;}
            if(hasPrevious&&index==sources.size()){if(!previous.file.isFile()){toast("上次缓存已不可用，请重新导出");return;}preparedText=previous.historical();offerTextDestination(preparedText);return;}
            recoverTextSave(null);
        }).setNegativeButton("取消",null).show();
    }
    private void createTextExport(TextExport.Source source){
        final TextExport.Request request;
        try{request=new TextExport.Request(document,editorSession,draft,originalPage,originalPageBaseline,source,screen);}catch(Exception problem){error(problem);return;}
        runIO("正在生成带页码的 UTF-8 文字快照…",()->{
            Models.Document saved;TextExport.Snapshot snapshot;
            synchronized(Store.FILE_LOCK){if(!request.version.equals(request.owner.savedSnapshot))throw new IOException("文稿版本已变化，未导出旧快照");saved=store.snapshotForExport(request.owner);snapshot=request.snapshot(saved);}
            TextExport.Result result=TextExport.create(getCacheDir(),snapshot);verifyExportSnapshot(request.owner,saved);return result;
        },value->{
            if(!request.current(document,editorSession,draft,screen)){toast("文稿、页序或草稿已变化；未打开保存位置，源副本保留在本地缓存");return;}
            TextExport.Result result=(TextExport.Result)value;
            String title=TextExport.fileTitle(result.snapshot.title);
            preparedText=new PreparedText(request,result.file,result.digest,result.bytes,title,result.snapshot.description());
            if(resumed&&!stateSaved)offerTextDestination(preparedText);else toast("文字快照已生成；返回后从另存文字中明确选择，不自动打开位置");
        },true);
    }
    private void offerTextDestination(PreparedText text){
        if(!resumed||stateSaved||busy||text!=preparedText||!textContextCurrent(text)){toast("文字来源状态已变化，请重新选择；草稿保留");return;}
        Models.Document offeredOwner=document;Object offeredSession=editorSession;Models.Page offeredDraft=draft;int offeredScreen=screen;
        new AlertDialog.Builder(this).setTitle("另存 UTF-8 文字副本").setMessage(text.description+"\n文件："+text.title+"\n大小："+text.bytes+" 字节\n\n逐页标注来源、是否已保存及图像文字状态；人工编辑不代表已校对无误。此操作不会保存编辑页面，也不改变 PDF 文字层。由您选择保存位置；存储或同步取决于所选文档提供者。请求创建新文件；源在应用缓存，可能被清理。").setPositiveButton("选择新文件",(dialog,which)->{
            if(document!=offeredOwner||editorSession!=offeredSession||draft!=offeredDraft||screen!=offeredScreen){toast("页面已切换，请重新选择文字来源");return;}
            if(text!=preparedText||!textContextCurrent(text)||busy||!resumed||stateSaved){toast("来源状态已变化，未打开位置");return;}
            try{text.context=new TextExport.Context(document,editorSession,draft,screen);}catch(Exception problem){error(problem);return;}
            pendingExport=text.file;approvedExportSource=text.file;approvedExportDigest=text.digest;approvedExportBytes=text.bytes;pendingKind="txt";saveExport();
        }).setNegativeButton("取消",null).show();
    }
    private static final class RecoveredText {final SaveJobStore.Job job;final PreparedText text;RecoveredText(SaveJobStore.Job job,PreparedText text){this.job=job;this.text=text;}}
    private void recoverTextSave(String token){
        if(busy||activeSaveToken!=null){toast("请先结束当前保存请求");return;}
        runIO("正在核对上次文字快照…",()->{SaveJobStore jobs=saveJobs();SaveJobStore.Job job=jobs.read();if(job==null||!"text/plain".equals(job.mime)||token!=null&&!token.equals(job.token))throw new IOException("没有匹配的文字保存记录，请重新选择文字来源");File source;try{source=jobs.recoverSource(job.token,null);}catch(IOException unavailable){return new RecoveredText(job,null);}return new RecoveredText(job,new PreparedText(null,source,job.sha,job.bytes,job.title,"上次保存记录中的文字快照（未重新核对当前文稿）"));},value->{
            RecoveredText offered=(RecoveredText)value;PreparedText recovered=offered.text;
            if(!resumed||stateSaved)return;
            if(recovered==null){endTextSave(offered.job.token);return;}
            new AlertDialog.Builder(this).setTitle("文字快照另存新文件？").setMessage("来源："+recovered.title+"\n这可能包含旧识别或未保存草稿，状态以文件内生成时说明为准。旧目标可能不完整；不会再次打开旧目标。确认后结束旧文字请求并选择新文件。").setPositiveButton("核对快照并另选新文件",(dialog,which)->{
                runIO("正在结束旧文字请求并核对源…",()->{SaveJobStore jobs=saveJobs();SaveJobStore.Job job=jobs.read();if(job==null||!job.token.equals(offered.job.token)||!new File(job.path).equals(recovered.file)||!job.sha.equals(recovered.digest))throw new IOException("保存记录已变化，请重新查找");return jobs.reselectText(job.token);},result->{preparedText=recovered;offerTextDestination(recovered);});
            }).setNeutralButton("结束旧文字请求",(dialog,which)->endTextSave(offered.job.token)).setNegativeButton("保留记录",null).show();
        });
    }
    private void endTextSave(String token){
        if(busy||!resumed||stateSaved)return;
        new AlertDialog.Builder(this).setTitle("结束旧文字保存请求？").setMessage("缓存可能已不可用，旧目标可能不完整。只结束这条文字保存回执，源副本与旧目标均不删除，也不会再次打开旧位置。结束后请重新选择文字来源，并新建文件另存。").setPositiveButton("结束旧文字请求",(dialog,which)->{
            runIO("正在结束旧文字请求…",()->{saveJobs().closeTextRequest(token);return null;},value->{if(token.equals(activeSaveToken))activeSaveToken=null;handledSaveToken=token;approvedSaveTokens.remove(token);textSavePermits.remove(token);toast("旧文字请求已结束；原文稿、草稿和已有文件保留，可重新导出");});
        }).setNegativeButton("保留记录",null).show();
    }
    private TextDocumentSave.Handle openNewTextDestination(Uri uri,int flags)throws Exception {
        if(!android.provider.DocumentsContract.isDocumentUri(this,uri))throw new IOException("请选择系统文件选择器中的新文档；未打开目标");
        retainPickerPermission(uri,flags,true);
        ParcelFileDescriptor descriptor=getContentResolver().openFileDescriptor(uri,"rw");
        if(descriptor==null)throw new IOException("所选文档无法打开");
        try{
            android.system.StructStat stat=android.system.Os.fstat(descriptor.getFileDescriptor());
            boolean regular=(stat.st_mode&android.system.OsConstants.S_IFMT)==android.system.OsConstants.S_IFREG;
            TextDocumentSave.requireEmpty(regular,stat.st_size);
            android.system.Os.lseek(descriptor.getFileDescriptor(),0,android.system.OsConstants.SEEK_SET);
            OutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(descriptor);
            return new TextDocumentSave.Handle(){public boolean regular(){return regular;}public long bytes(){return stat.st_size;}public OutputStream output(){return out;}public void close()throws Exception{out.close();}};
        }catch(Exception problem){try{descriptor.close();}catch(Exception closing){problem.addSuppressed(closing);}throw problem;}
    }
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
    /** List old closed sessions separately: never replace the active capture pointer. */
    private void recoverClosedCapture(){
        Models.Document owner=document;if(owner==null)return;
        runIO("正在检查历史拍照记录…",()->CaptureBatch.closedPending(this,owner.id),value->{
            if(document!=owner){toast("文稿已切换，请重新查找历史拍照");return;}
            @SuppressWarnings("unchecked") List<CaptureBatch.ClosedCandidate> candidates=(List<CaptureBatch.ClosedCandidate>)value;
            if(candidates.isEmpty()){toast("当前未发现待核对的历史拍照");return;}
            String[] labels=new String[candidates.size()];java.text.DateFormat dates=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT);
            for(int i=0;i<labels.length;i++){CaptureBatch.ClosedCandidate c=candidates.get(i);labels[i]=dates.format(new Date(c.created))+" · "+c.id.substring(0,8)+" · 待整理 "+c.received+"，待核对 "+c.unverified;}
            new AlertDialog.Builder(this).setTitle("已结束批次 · 待核对不代表照片完整")
                .setItems(labels,(dialog,index)->{
                    if(document!=owner||busy){toast("文稿已变化或正在处理，请重新查找");return;}CaptureBatch.ClosedCandidate selected=candidates.get(index);
                    new AlertDialog.Builder(this).setTitle("核对所选批次？").setMessage("将检查所选批次的原文件，并整理已确认完整且尚未登记的照片；不会主动删除原文件。当前连续扫描与其他批次不会被切换。")
                        .setPositiveButton("检查并整理",(confirm,which)->{
                            if(document!=owner||busy){toast("文稿已变化或正在处理，请重新查找");return;}
                            Exporter.Progress progressUpdate=(done,total)->runOnUiThread(()->{if(progress!=null)progress.setMessage("正在整理历史拍照 · "+done+" / "+total);});
                            runIO("正在核对历史拍照，原图保留…",()->{CaptureBatch.prepareClosed(this,selected.id,owner.id,selected.revision);return CaptureBatch.importAll(store,selected.id,owner.id,progressUpdate);},result->{
                                if(document!=owner){toast("历史批次已处理；当前文稿未切换");return;}
                                document=(Models.Document)result;showDocument();toast("已处理可确认照片；未确认记录仍在，请检查页面及原文件");
                            },true);
                        }).setNegativeButton("取消",null).show();
                }).setNegativeButton("关闭",null).show();
        },true);
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
    private void pickBackup(){new AlertDialog.Builder(this).setTitle("恢复为新的独立副本").setMessage("每次都会新建一份独立文稿，即使选择的是同一个备份；这不是继续上次未完成的恢复。\n\n原始备份、解包暂存及新文稿都会占用空间，一次可能接近 1.5 GiB，另有文件系统开销。成功或失败均不会自动清理暂存，空间不足仍可能失败。\n\n文库的“查看备份恢复暂存”可核对并导出已收到的片段，但不代表来源完整，也不能清理暂存。如果上次结果未知，请先核对已有文稿与暂存，避免再次复制。")
        .setPositiveButton("另建副本并选择备份",(dialog,which)->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("application/zip");i.addCategory(Intent.CATEGORY_OPENABLE);try{startActivityForResult(i,BACKUP);}catch(ActivityNotFoundException e){error(new IOException("没有可用的文件选择器"));}}).setNegativeButton("取消",null).show();}
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
            }).setNeutralButton("保存拍照原文件",(d,w)->{pendingExport=photo;approvedExportSource=pendingExport;approvedExportDigest=null;approvedExportBytes=0;pendingKind="jpg";saveExport();}).setNegativeButton("稍后",null).create();
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
    private void openEditor(Models.Page p){openEditor(p,null);}
    private void openEditor(Models.Page p,Models.Page restoredDraft){
        if(recovery!=null){showRecovery("请先保留或处理现有恢复记录");return;}
        cancelPendingCapture();
        try{
            // Prepare both values before publishing editor state. A restored draft
            // must be installed before controls, crop status and preview are built.
            String baseline=p.json().toString();
            Models.Page initial=restoredDraft==null?p.copy():restoredDraft.copy();
            originalPageBaseline=baseline;originalPage=p;draft=initial;editorSession=new Object();editorDocumentVersion=document.savedSnapshot;
        }catch(Exception e){error(e);return;}
        screen=2;buildEditor();
    }
    private void buildEditor(){
        ArrayList<String> order=editorOrder(document);int position=order.indexOf(draft.id);
        base("编辑页面 · 第 "+(position+1)+" / "+order.size()+" 页",draft.cropConfirmed()?"四角已核对 · 原图始终保留":"四角待核对 · 请检查原图后确认",this::leaveEditor);
        if(document!=null&&document.manifestUncertain)body.addView(text("文稿保存状态未确认；请勿继续修改或导出。原图和记录候选保留，待核对恢复。",16,INK));
        editorStatus=text("正在生成预览…",12,MUTED);body.addView(editorStatus);
        boolean landscape=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout workspace=landscape?row():column();body.addView(workspace,new LinearLayout.LayoutParams(-1,0,1));
        FrameLayout frame=new FrameLayout(this);preview=new ImageView(this);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);preview.setBackgroundColor(PREVIEW);preview.setContentDescription("处理后页面预览");frame.addView(preview,new FrameLayout.LayoutParams(-1,-1));workspace.addView(frame,landscape?new LinearLayout.LayoutParams(0,-1,1.2f):new LinearLayout.LayoutParams(-1,0,1.3f));
        ScrollView controlsScroll=new ScrollView(this);controlsScroll.setContentDescription("页面增强工具，可上下滚动");controlsScroll.setFillViewport(false);controlsScroll.setScrollbarFadingEnabled(false);LinearLayout controls=column();controlsScroll.addView(controls);workspace.addView(controlsScroll,landscape?new LinearLayout.LayoutParams(0,-1,1):new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout modes=row();for(String m:new String[]{"COLOR","GRAY","BW"}){Button choice=button(modeName(m),()->{draft.mode=m;for(int i=0;i<modes.getChildCount();i++){Button item=(Button)modes.getChildAt(i);boolean selected=m.equals(item.getTag());item.setSelected(selected);styleButton(item,selected);}requestPreview();});choice.setTag(m);choice.setSelected(m.equals(draft.mode));styleButton(choice,m.equals(draft.mode));weighted(modes,choice);}controls.addView(modes);
        LinearLayout tools=row();weighted(tools,button("四角裁切",this::startCrop));weighted(tools,button("旋转 90°",()->{draft.rotation=(draft.rotation+1)%4;buildEditor();}));weighted(tools,button("重置",()->{draft.quad=Geometry.full();draft.rotation=0;draft.mode="COLOR";draft.shadows=false;draft.brightness=0;draft.bias=8;buildEditor();}));controls.addView(tools);
        LinearLayout adjust=row();weighted(adjust,button("亮度 / 黑白细节",this::adjustments));CheckBox shadow=new CheckBox(this);shadow.setText("减淡阴影");shadow.setTextColor(INK);shadow.setChecked(draft.shadows);shadow.setOnCheckedChangeListener((v,checked)->{draft.shadows=checked;requestPreview();});weighted(adjust,shadow);controls.addView(adjust);
        controls.addView(text("缺角或纸张超出画面时，自动边界可能不可靠。请在“四角裁切”中恢复全图并核对；画面外的内容需要重拍，裁切无法补回。",13,MUTED));
        addOcrEditor(controls);
        TextView capacity=text("正在核对恢复记录余量…",12,MUTED);controls.addView(capacity);fillRecoveryQuota(capacity,document.id,true);
        controls.addView(button("暂存编辑副本（不改已保存页面）",this::checkpointEditor));
        LinearLayout footer=row();weighted(footer,button("导出本页图片",this::exportPageImage));weighted(footer,button("保存本页并返回",this::saveEditor));root.addView(footer);
        Button advance=primary(position==order.size()-1?"保存并返回文稿（末页）":"保存并下一页",()->saveEditor(true));
        advance.setEnabled(position>=0&&draft.cropConfirmed()&&document!=null&&!document.manifestUncertain);
        advance.setContentDescription(draft.cropConfirmed()?advance.getText():"请先核对并确认四角，再保存并下一页");
        root.addView(advance,new LinearLayout.LayoutParams(-1,dp(52)));requestPreview();
    }
    private void refreshOcrStatus(){if(ocrStatus!=null&&draft!=null)ocrStatus.setText(ocrReviewStatus(draft,originalPage));}
    private void requestPreview(){
        refreshOcrStatus();
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
        Button apply=primary("确认四角并返回",()->{page.quad=cropView.getQuad();page.confirmCrop();draft=page;buildEditor();toast("四角已确认，请保存本页");});
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
    private static ArrayList<String> editorOrder(Models.Document owner){
        ArrayList<String> ids=new ArrayList<>();if(owner!=null)for(Models.Page p:owner.active())ids.add(p.id);return ids;
    }
    private void saveEditor(){saveEditor(false);}
    private void saveEditor(boolean advance){
        if(busy||!alive()||screen!=2||document==null||draft==null||recovery!=null){toast("请等待当前操作完成或处理恢复记录");return;}
        if(cropping){draft.quad=cropView.getQuad();buildEditor();return;}
        Models.Document owner=document;Models.Page before=originalPage;String baseline=originalPageBaseline;
        final Models.Page next;final EditorSaveFlow.Request request;
        try{
            if(before==null)throw new IOException("当前编辑页面缺失，草稿未保存");
            next=draft.copy(); // Historical OCR is retained; its old key still marks stale text.
            request=editorSaves.begin(editorSession,owner.id,before.id,baseline,next.json().toString(),editorOrder(owner),next.cropConfirmed(),advance);
        }catch(Exception e){error(e);return;}
        final EditRecoveryState held=new EditRecoveryState(owner.id,
                new EditDraftStore.Draft(owner.id,editorDocumentVersion,baseline,request.edited,true,0));
        // First validate exact serializer output and retain a verified immutable record.
        // The UI remains an editable draft if preflight or retention fails before any writer.
        // First retain a verified immutable edit record. Only then may the document transaction begin.
        runIO("正在保留恢复记录并保存当前页…",()->{
            try{preflightEditorSave(owner,before,next,held.data);return EditorSaveFlow.afterRecovery(
                ()->held.retained(EditDraftStore.recovery(getFilesDir()).retain(held.owner,held.data.version,held.data.baseline,held.data.edited,true)),
                ()->{synchronized(Store.FILE_LOCK){
                    if(!held.data.version.equals(owner.savedSnapshot))throw new IOException("编辑期间文稿版本已变化；恢复记录保留，未保存页面");
                    return editorSaves.commit(request,owner.id,before.json().toString(),editorOrder(owner),
                            ()->DocumentMutations.replacePage(owner,before,next,store::saveIfCurrent),()->owner.savedSnapshot);
                }});
            }catch(Exception|OutOfMemoryError|LinkageError failure){return failure;}
        },value->{
            if(value instanceof Throwable){
                editorSaves.fail(request);
                if(!held.verified&&document==owner&&originalPage==before&&editorSession==request.session&&screen==2){
                    error(new IOException("尚未开始保存页面；完整输入保留，可继续纠错、复制或返回。\n"+((Throwable)value).getMessage(),(Throwable)value));return;
                }
                recovery=held;showRecovery("页面未确认保存；当前编辑和已有记录保留。");error((Throwable)value);
                return;
            }
            recovery=held;
            pendingEditorSave=(EditorSaveFlow.Saved)value;deliverPendingEditorSave();
        });
    }
    private static void preflightEditorSave(Models.Document owner,Models.Page before,Models.Page next,EditDraftStore.Draft record)throws Exception {
        EditDraftStore.validate(record); // exact WED framing, UTF-8, all fields and checksum budget
        synchronized(Store.FILE_LOCK){
            Store.requireCertain(owner);int index=owner.pages.indexOf(before);
            if(index<0||!before.id.equals(next.id))throw new IOException("目标页面已变化；当前输入保留");
            org.json.JSONObject candidate=owner.json();
            candidate.getJSONArray("pages").put(index,next.json());
            // Reserve the longest possible nonnegative timestamp with the real serializer.
            // Store.save uses currentTimeMillis; no owner/page is changed by this check.
            candidate.put("updated",Long.MAX_VALUE);
            Store.manifestBytes(candidate);
        }
    }
    private static final class NavigationCheck {
        final Models.Document fresh;final EditorSaveFlow.Persisted persisted;
        NavigationCheck(Models.Document fresh,EditorSaveFlow.Persisted persisted){this.fresh=fresh;this.persisted=persisted;}
    }
    private void deliverPendingEditorSave(){
        final EditorSaveFlow.Saved saved=pendingEditorSave;
        if(saved==null||!resumed||stateSaved||!alive()||busy||navigationCheckRunning)return;
        final EditRecoveryState held=recovery;final EditorSaveFlow.Request request=saved.request;
        final long cycle=navigationCycle;
        navigationCheckRunning=true;
        runIO("正在核对磁盘上的已保存版本…",()->{
            try{
                final Models.Document[] loaded={null};
                EditorSaveFlow.Persisted check=EditorSaveFlow.checkPersisted(()->{
                    Models.Document fresh=store.load(request.documentId);loaded[0]=fresh;
                    return new EditorSaveFlow.Persisted(fresh.id,fresh.savedSnapshot,editorOrder(fresh));
                });
                return new NavigationCheck(loaded[0],check);
            }catch(Exception|OutOfMemoryError|LinkageError failure){return failure;}
        },value->{
            navigationCheckRunning=false;
            // A background/saved-state interval invalidates this disk observation: reread on resume.
            if(!EditorSaveFlow.observationCurrent(cycle,navigationCycle,resumed,stateSaved)){
                if(resumed&&!stateSaved&&pendingEditorSave==saved&&recovery==held)handler.post(this::deliverPendingEditorSave);
                return;
            }
            if(pendingEditorSave!=saved||recovery!=held)return;
            pendingEditorSave=null;
            try{
                if(!editorSaves.finish(saved,editorSession,document==null?null:document.id,originalPage==null?null:originalPage.id,
                        originalPageBaseline,draft==null?null:draft.json().toString())){
                    if(recovery==held&&held!=null&&held.verified)recovery=null; // immutable record remains discoverable from home
                    return;
                }
                if(value instanceof Throwable){showRecovery("保存动作已返回成功，但当前磁盘版本无法核对；不自动跳页。恢复记录保留。");error((Throwable)value);return;}
                NavigationCheck checked=(NavigationCheck)value;
                if(!EditorSaveFlow.permitsNavigation(saved,checked.persisted,EditorSaveFlow.observedVersion(request.documentId))){
                    showRecovery("保存后磁盘文稿版本或页序已变化；不自动跳页，不把旧编辑套到新版本。恢复记录保留。");return;
                }
                recovery=null;document=checked.fresh;releaseLifecycleMemory();
                if(!request.advance){showDocument();toast("本页与文字修改已保存");return;}
                if(request.nextPageId==null){showDocument();toast("本页与文字修改已保存，已到末页，返回文稿");return;}
                for(Models.Page page:document.active())if(page.id.equals(request.nextPageId)){openEditor(page);toast("上一页与文字修改已保存");return;}
                showDocument();toast("当前保存版本的下一页不可用，请核对文稿");
            }catch(Exception e){editorSaves.fail(request);showRecovery("保存结果需核对；恢复记录保留，未自动跳页。");error(e);}
        });
    }
    private void leaveEditor(){if(busy||editorSaves.hasPending()){toast("正在保存当前页，请等待结果后再返回");return;}if(cropping){buildEditor();return;}new AlertDialog.Builder(this).setTitle("离开编辑？").setMessage("当前页面和文字修改尚需保存。放弃后不应用本次修改，但已生成的私有生命周期快照和明确暂存副本仍保留，可分别从文稿草稿快照或“更多”找回。").setPositiveButton("保存",(d,w)->saveEditor()).setNegativeButton("放弃本次修改",(d,w)->{releaseLifecycleMemory();showDocument();}).setNeutralButton("继续编辑",null).show();}
    private static EditRecoveryPolicy.Identity identity(String owner,Models.Page page){
        return new EditRecoveryPolicy.Identity(owner,page.id,page.file,page.sha256);
    }
    private static Models.Page recoveryEdited(Models.Document current,EditDraftStore.Draft record)throws Exception{
        Models.Page old=strictCheckpointPage(record.baseline),edited=strictCheckpointPage(record.edited);
        EditRecoveryPolicy.Identity source=identity(record.document,old);
        if(!EditRecoveryPolicy.sameSource(source,identity(record.document,edited))||old.removed||edited.removed)
            throw new IOException("恢复记录的原图身份不一致，未应用");
        if(current==null||current.archived||current.manifestUncertain)throw new IOException("原文稿暂不可编辑，记录仅供查看");
        for(Models.Page page:current.active())if(page.id.equals(edited.id)){
            EditRecoveryPolicy.Decision result=EditRecoveryPolicy.decide(source,identity(current.id,page),record.version,
                    current.savedSnapshot,record.baseline,record.edited,page.json().toString(),record.submitted);
            if(result==EditRecoveryPolicy.Decision.BASELINE)return edited;
            if(result==EditRecoveryPolicy.Decision.CONTENTS_SAVED)throw new IOException("当前保存内容与此记录相同；请打开当前版本，不重放旧编辑");
            break;
        }
        throw new IOException("文稿版本、页面或原图已变化；只能查看或导出此记录，再人工编辑当前版本");
    }
    private static String excerpt(String value){if(value==null)return "(内容尚未读取)";return value.length()>32000?value.substring(0,32000)+"\n…界面仅显示前32000字，完整内容仍在记录中，可导出…":value;}
    private void showRecovery(String message){
        EditRecoveryState held=recovery;if(held==null){toast("没有待核对的编辑恢复记录");return;}
        screen=3;editorSession=null;base("编辑恢复记录",held.verified?"记录已独立写入核对 · 不代表页面已保存":"尚未核实落盘 · 不离开或丢弃当前记录",this::leaveRecovery);
        ScrollView scroll=new ScrollView(this);LinearLayout panel=column();scroll.addView(panel);
        panel.addView(text(message,16,INK));
        panel.addView(text("所属文稿："+held.owner+"\n记录："+(held.entry==null?"尚无文件回执":held.entry.token)+"\n不会自动覆盖已保存页面。记录含编辑参数和可能存在的旧识别文字，属于私密资料。普通ZIP备份不包含这些恢复记录；卸载或清除应用数据会丢失，请先单独导出。",13,MUTED));
        TextView capacity=text("正在核对恢复记录额度…",12,MUTED);panel.addView(capacity);fillRecoveryQuota(capacity,held.owner,false);
        if(!held.verified){panel.addView(button("重试保留 / 读取恢复记录",()->retainRecovery(held)));if(held.data!=null){panel.addView(button("原版本一致时返回草稿继续纠错",()->resumeUnverifiedEditor(held)));panel.addView(button("保留完整生命周期快照并返回",()->keepRecoverySnapshotAndLeave(held)));}}
        panel.addView(button("仅原版本一致时载入编辑预览",()->loadRecoveryEditor(held)));
        panel.addView(button("重新打开当前已保存版本（不套旧稿）",()->openCurrentRecovery(held)));
        panel.addView(button("导出这份恢复记录副本",()->exportRecovery(held)));
        panel.addView(button("返回文稿列表（记录保留）",this::leaveRecovery));
        EditDraftStore.Draft record=held.data;
        if(record!=null){
            TextView params=text("原版本："+record.version+"\n原页面基线：\n"+excerpt(record.baseline)+"\n\n待核对编辑：\n"+excerpt(record.edited),12,INK);
            params.setTextIsSelectable(true);panel.addView(params);
        }
        body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    }
    private void keepRecoverySnapshotAndLeave(EditRecoveryState held){
        if(recovery!=held||held.data==null||busy)return;
        runIO("正在保留完整私有草稿快照…",()->EditorLifecycleState.capture(getFilesDir(),new EditorLifecycleState.Snapshot(held.data,true)),value->{
            if(recovery!=held)return;
            EditorLifecycleState.release(lifecycleState);lifecycleState=(EditorLifecycleState)value;lifecycleSnapshot=new EditorLifecycleState.Snapshot(held.data,true);
            recovery=null;showDocument();toast("完整快照已写入并核对，可从文稿的生命周期草稿快照中找回；未覆盖页面");
        });
    }
    private void resumeUnverifiedEditor(EditRecoveryState held){
        if(recovery!=held||held.verified||held.data==null||busy||!resumed||stateSaved)return;
        EditDraftStore.Draft record=held.data;
        try{
            if(document==null||!held.owner.equals(document.id))throw new IOException("原文稿尚不可编辑；完整草稿保留");
            recovery=null;
            try{restoreDraft(record.edited,record.baseline,record.version,false);}
            catch(Exception problem){recovery=held;throw problem;}
            toast("完整输入已返回草稿；尚未保存页面，请缩短或复制文字后重试");
        }catch(Exception problem){error(problem);}
    }
    private void retainRecovery(EditRecoveryState held){
        if(recovery!=held||busy)return;
        runIO("正在保留或核对恢复记录…",()->{
            if(held.entry!=null&&held.data==null){held.loaded(EditDraftStore.recovery(getFilesDir()).load(held.owner,held.entry));return held.entry;}
            if(held.data==null)throw new IOException("恢复状态缺少内容和有效文件引用；请保留应用数据，未编造已保存状态");
            EditDraftStore.Draft record=held.data;
            EditDraftStore.Entry entry=EditDraftStore.recovery(getFilesDir()).retain(held.owner,record.version,record.baseline,record.edited,record.submitted);
            held.retained(entry);return entry;
        },value->{if(recovery==held)showRecovery("独立恢复记录已写入并核对。这不表示页面已保存；请查看或明确选择下一步。");});
    }
    private void leaveRecovery(){
        if(recovery!=null&&!recovery.verified){toast("恢复记录尚未写入或核对，请先重试保留；当前内容不会被丢弃");return;}
        recovery=null;releaseLifecycleMemory();showLibrary("");
    }
    private void fillRecoveryQuota(TextView target,String owner,boolean compact){
        worker.execute(()->{String message;
            try{EditDraftStore.Usage u=EditDraftStore.recovery(getFilesDir()).usage(owner);
                message=compact&&u.complete&&!u.near()?"恢复记录余量（本次核对）："+u.remainingFiles()+" 项 / "+u.remainingBytes()+" 字节；新写入仍需核对":EditRecoveryCatalog.quota(u);
            }catch(Exception e){message="恢复记录余量无法核对；已有记录保留。新写入仍须完整核对，不会跳过保护。";}
            final String shown=message;runOnUiThread(()->{if(alive()&&target.isAttachedToWindow())target.setText(shown);});
        });
    }
    private static final class RecoveryOwner {
        final String owner,label;final EditDraftStore.Usage usage;
        RecoveryOwner(String o,String l,EditDraftStore.Usage u){owner=o;label=l;usage=u;}
    }
    private static final class RecoveryRow {
        final EditDraftStore.Entry entry;final EditRecoveryCatalog.Label label;
        RecoveryRow(EditDraftStore.Entry e,EditRecoveryCatalog.Label l){entry=e;label=l;}
    }
    private static final class RecoveryListing {
        final EditDraftStore.Usage usage;final ArrayList<RecoveryRow> rows;
        RecoveryListing(EditDraftStore.Usage u,ArrayList<RecoveryRow> r){usage=u;rows=r;}
    }
    private static EditRecoveryCatalog.Current catalogCurrent(Models.Document d,java.util.Map<String,Boolean> proofs)throws Exception {
        if(d==null)return null;ArrayList<EditRecoveryCatalog.Page> pages=new ArrayList<>();
        for(Models.Page p:d.pages)pages.add(new EditRecoveryCatalog.Page(identity(d.id,p),p.json().toString(),p.removed,Boolean.TRUE.equals(proofs.get(p.id))));
        return new EditRecoveryCatalog.Current(d.id,d.title,d.savedSnapshot,!d.archived&&!d.manifestUncertain,pages.toArray(new EditRecoveryCatalog.Page[0]));
    }
    /** Same real verifier used for both list classification and explicit load. */
    private boolean recoveryOriginalVerified(Models.Document d,Models.Page page)throws Exception {
        RecoverySourceGate.verify(new File(getFilesDir(),"documents"),identity(d.id,page));return true;
    }
    private RecoveryListing recoveryListing(String owner)throws Exception {
        EditDraftStore journal=EditDraftStore.recovery(getFilesDir());EditDraftStore.Inventory inventory=journal.inventory(owner);
        Models.Document current=null;try{current=store.load(owner);}catch(java.io.InterruptedIOException cancelled){throw cancelled;}catch(Exception unavailable){/* current document status stays unknown */}
        java.util.HashMap<String,Boolean> proofs=new java.util.HashMap<>();EditRecoveryCatalog.Current snapshot=catalogCurrent(current,proofs);ArrayList<RecoveryRow> rows=new ArrayList<>();
        for(EditDraftStore.Entry entry:inventory.entries){
            EditRecoveryCatalog.Record record;
            try{EditDraftStore.Draft data=journal.load(owner,entry);Models.Page old=strictCheckpointPage(data.baseline),edited=strictCheckpointPage(data.edited);
                record=new EditRecoveryCatalog.Record(entry,data,identity(owner,old),identity(owner,edited),old.removed||edited.removed);
                if(current!=null)for(Models.Page page:current.pages)if(page.id.equals(edited.id)&&!proofs.containsKey(page.id)){
                    boolean verified=false;try{verified=recoveryOriginalVerified(current,page);}catch(java.io.InterruptedIOException cancelled){throw cancelled;}catch(Exception invalid){/* never claim source verified */}proofs.put(page.id,verified);
                    EditRecoveryCatalog.Page[] pages=snapshot.pages.clone();for(int i=0;i<pages.length;i++)if(pages[i].source.page.equals(page.id))pages[i]=new EditRecoveryCatalog.Page(pages[i].source,pages[i].json,pages[i].removed,verified);
                    snapshot=new EditRecoveryCatalog.Current(snapshot.owner,snapshot.title,snapshot.version,snapshot.editable,pages);
                }
            }catch(java.io.InterruptedIOException cancelled){throw cancelled;}catch(Exception invalid){record=new EditRecoveryCatalog.Record(new EditDraftStore.Entry(entry.token,entry.digest,entry.created,"记录未通过内容核对"),null,null,null,false);}
            rows.add(new RecoveryRow(record.entry,EditRecoveryCatalog.describe(owner,record,snapshot)));
        }
        return new RecoveryListing(inventory.usage,rows);
    }
    private void browseRecovery(){
        if(recovery!=null){showRecovery("请先查看当前待核对记录");return;}
        runIO("正在核对恢复记录文稿与额度…",()->{
            ArrayList<RecoveryOwner> owners=new ArrayList<>();EditDraftStore journal=EditDraftStore.recovery(getFilesDir());
            for(String owner:journal.owners()){Models.Document current=null;try{current=store.load(owner);}catch(java.io.InterruptedIOException cancelled){throw cancelled;}catch(Exception unavailable){}EditRecoveryCatalog.Current label=current==null?null:new EditRecoveryCatalog.Current(current.id,current.title,current.savedSnapshot,!current.archived,new EditRecoveryCatalog.Page[0]);owners.add(new RecoveryOwner(owner,EditRecoveryCatalog.document(owner,label),journal.usage(owner)));}return owners;
        },value->{
            @SuppressWarnings("unchecked") ArrayList<RecoveryOwner> owners=(ArrayList<RecoveryOwner>)value;
            if(owners.isEmpty()){toast("没有编辑恢复记录");return;}
            String[] labels=new String[owners.size()];for(int i=0;i<labels.length;i++){RecoveryOwner o=owners.get(i);labels[i]=o.label;
                labels[i]+="\n"+(o.usage.complete?o.usage.count+" 项 · 剩余 "+o.usage.remainingFiles()+" 项 / "+o.usage.remainingBytes()+" 字节":"余量未知 · 不跳过恢复保护");}
            new AlertDialog.Builder(this).setTitle("编辑恢复记录 · 当前文稿信息").setItems(labels,(dialog,index)->browseRecoveryEntries(owners.get(index).owner)).setNegativeButton("关闭",null).show();
        });
    }
    private void browseRecoveryEntries(String owner){
        runIO("正在校验记录、当前版本与原图…",()->recoveryListing(owner),value->{
            RecoveryListing listing=(RecoveryListing)value;ScrollView scroll=new ScrollView(this);LinearLayout panel=column();scroll.addView(panel);
            panel.addView(text(EditRecoveryCatalog.quota(listing.usage),14,INK));
            panel.addView(text("标题与页码来自当前文稿，不是历史标签；时间是首次保留时间，去重不更新时间。状态仅为本次只读核对，载入时仍会重新校验完整身份与原版本。记录不自动删除；普通ZIP不含恢复区。参数副本导出不含原图或整本文稿，也不释放额度；达到上限需要另行决定处置。",13,MUTED));
            final AlertDialog[] dialog=new AlertDialog[1];
            if(listing.rows.isEmpty())panel.addView(text("没有可列出的记录",14,INK));
            for(RecoveryRow row:listing.rows)panel.addView(button(row.label.text(),()->{
                EditDraftStore.Entry entry=row.entry;if(!entry.valid){toast("记录未通过校验，原文件保留："+entry.error);return;}
                dialog[0].dismiss();runIO("正在重新核对所选独立记录…",()->EditDraftStore.recovery(getFilesDir()).load(owner,entry),data->{
                    EditRecoveryState held=new EditRecoveryState(owner,(EditDraftStore.Draft)data);held.retained(entry);recovery=held;
                    showRecovery("这是独立编辑恢复记录；成功保存后的旧记录也可能在此，不会自动重放。");
                });
            }));
            dialog[0]=new AlertDialog.Builder(this).setTitle("只读查看 · 不自动应用").setView(scroll).setNegativeButton("关闭",null).create();dialog[0].show();
        });
    }
    private RecoverySourceGate.State recoveryLoadState(){
        EditRecoveryState current=recovery;EditDraftStore.Entry entry=current==null?null:current.entry;
        return new RecoverySourceGate.State(current,document,draft,editorSession,current==null?null:current.owner,
                entry==null?null:entry.token,entry==null?null:entry.digest,document==null?null:document.id,
                draft==null?null:draft.id,document==null?null:document.savedSnapshot,generation,screen,navigationCycle,
                alive()&&resumed&&!stateSaved);
    }
    private void loadRecoveryEditor(EditRecoveryState held){
        if(recovery!=held||!held.verified||held.entry==null){toast("请先保留并核对恢复记录");return;}
        new AlertDialog.Builder(this).setTitle("核对原版本后载入？").setMessage("会重新读取真实原图并核对哈希，只有原文稿版本、页面基线和原图身份全部一致才打开编辑预览。列表状态不作为载入凭据；页面或原图已变化时，记录继续保留。").setPositiveButton("核对并载入",(dialog,which)->{
            if(recovery!=held||!held.verified||held.entry==null||busy||!resumed||stateSaved)return;
            final String owner=held.owner;final EditDraftStore.Entry selected=held.entry;
            final RecoverySourceGate.Ticket ticket=new RecoverySourceGate.Ticket(recoveryLoadState());recoveryLoadTicket=ticket;
            runIO("正在重新核对真实原图与原版本…",()->{
                EditDraftStore.Draft record=EditDraftStore.recovery(getFilesDir()).load(owner,selected);
                Models.Document before=store.load(owner);Models.Page edited=recoveryEdited(before,record);
                EditRecoveryPolicy.Identity source=identity(owner,edited);
                return RecoverySourceGate.verifyAndRead(new File(getFilesDir(),"documents"),source,()->{
                    Models.Document fresh=store.load(owner);Models.Page checked=recoveryEdited(fresh,record);
                    if(!EditRecoveryPolicy.sameSource(source,identity(fresh.id,checked)))throw new IOException("原图身份在核验期间已变化，记录未应用");
                    return new RecoveredEdit(fresh,checked);
                });
            },value->{
                if(!ticket.accepts(recoveryLoadTicket,recoveryLoadState())){
                    if(recoveryLoadTicket==ticket)recoveryLoadTicket=null;
                    toast("界面、页面或恢复记录已变化，未载入旧结果；记录保留");return;
                }
                @SuppressWarnings("unchecked") RecoverySourceGate.Verified<RecoveredEdit> verified=(RecoverySourceGate.Verified<RecoveredEdit>)value;
                RecoveredEdit result=verified.value;recoveryLoadTicket=null;
                if(!EditRecoveryPolicy.sameSource(verified.proof.source,identity(result.document.id,result.edited))
                        ||!result.document.savedSnapshot.equals(EditorSaveFlow.observedVersion(owner))){toast("核对期间文稿或原图身份又有变化，记录未应用");return;}
                for(Models.Page page:result.document.active())if(EditRecoveryPolicy.sameSource(verified.proof.source,identity(result.document.id,page))){
                    recovery=null;document=result.document;openEditor(page,result.edited);toast("真实原图与原版本已核对；仍需审核并保存，记录未删除");return;
                }
                toast("当前页面不可用，恢复记录保留，未载入编辑");
            });
        }).setNegativeButton("继续查看",null).show();
    }
    private void openCurrentRecovery(EditRecoveryState held){
        if(recovery!=held||!held.verified){toast("请先保留并核对恢复记录");return;}
        runIO("正在重新读取当前已保存文稿…",()->store.load(held.owner),value->{
            if(recovery!=held||!resumed||stateSaved)return;Models.Document fresh=(Models.Document)value;
            if(!fresh.savedSnapshot.equals(EditorSaveFlow.observedVersion(held.owner))){toast("文稿又有变化，请重新核对");return;}
            recovery=null;document=fresh;showDocument();toast("已打开当前保存版本；恢复记录保留，未套用旧编辑");
        });
    }
    private void exportRecovery(EditRecoveryState held){
        if(recovery!=held||!held.verified||held.entry==null||activeSaveToken!=null){toast("请先核对恢复记录或处理已有保存请求");return;}
        new AlertDialog.Builder(this).setTitle("导出私密恢复记录？").setMessage("包含原页面基线、文稿版本、编辑参数和可能存在的识别文字，不含原图，不是可直接恢复的ZIP。请选择可信位置；不会自动分享或删除。").setPositiveButton("创建副本并选择位置",(dialog,which)->runIO("正在复制恢复记录…",()->EditDraftStore.recovery(getFilesDir()).export(held.owner,held.entry,getCacheDir()),value->{
            if(recovery!=held||!resumed||stateSaved||activeSaveToken!=null){toast("界面状态已变化，本地记录和副本保留，未发起外部保存");return;}
            EditDraftStore.Export exported=(EditDraftStore.Export)value;pendingExport=exported.file;approvedExportSource=exported.file;approvedExportDigest=exported.digest;approvedExportBytes=exported.bytes;pendingKind="bin";saveExport();
        })).setNegativeButton("取消",null).show();
    }
    /** Only the exact current serializer format is accepted; no silent parameter normalization. */
    private static Models.Page strictCheckpointPage(String encoded)throws Exception {
        Models.Page p=Models.Page.from(new org.json.JSONObject(encoded));
        if(!p.json().toString().equals(encoded))throw new IOException("草稿格式或参数不受支持，原记录保留，未自动修正");
        return p;
    }
    private static Models.Page matchCheckpoint(Models.Document current,EditDraftStore.Draft checkpoint)throws Exception {
        if(!current.id.equals(checkpoint.document)||current.archived||current.manifestUncertain)throw new IOException("草稿所属文稿不再可编辑");
        Models.Page p=strictCheckpointPage(checkpoint.edited),old=strictCheckpointPage(checkpoint.baseline);
        if(p.removed||old.removed||!p.id.equals(old.id)||!p.file.equals(old.file)||!p.sha256.equals(old.sha256))throw new IOException("草稿原图身份不一致");
        for(Models.Page saved:current.pages)if(saved.id.equals(p.id)){
            if(saved.removed||!saved.json().toString().equals(checkpoint.baseline))throw new IOException("已保存页面与草稿基线不同，未覆盖页面；暂存记录保留");
            return p;
        }
        throw new IOException("草稿对应页面不存在，暂存记录保留");
    }
    private void checkpointEditor(){
        if(draft==null||originalPageBaseline==null||document==null){toast("没有可暂存的编辑");return;}
        new AlertDialog.Builder(this).setTitle("暂存一份编辑副本？").setMessage("仅保存当前编辑参数和原页面基线，不修改已保存页面或原图。可在文稿“更多 → 找回暂存编辑”中找回。每份最多 4 MiB，每文稿最多 16 份（约 64 MiB）；旧副本和失败片段不会自动删除。副本含原页面和当前编辑的识别文字，保存在应用私有目录；放弃预览也不会清除它们，普通 ZIP 备份不包含这些副本；卸载、清除应用数据或换机会丢失，需另行导出草稿，或先恢复并保存本页再做 ZIP 备份。暂无清理入口。不是自动保存，后续修改需再次暂存。页面已另行保存或变化时，旧副本不会覆盖新页面。").setPositiveButton("暂存副本",(dialog,which)->{
            try{Models.Document owner=document;String baseline=originalPageBaseline;Models.Page copy=draft.copy();if(cropping&&cropView!=null)copy.quad=cropView.getQuad();String edited=copy.json().toString();
                runIO("正在核对并暂存编辑…",()->{Models.Document current=store.load(owner.id);matchCheckpoint(current,new EditDraftStore.Draft(owner.id,baseline,edited,0));return new EditDraftStore(getFilesDir()).save(owner.id,baseline,edited);},result->{toast("编辑副本已写入并核对；已保存页面未改变。后续修改需再次暂存");});
            }catch(Exception failure){error(failure);}
        }).setNegativeButton("取消",null).show();
    }
    private static final class RecoveredEdit {
        final Models.Document document;final Models.Page edited;
        RecoveredEdit(Models.Document d,Models.Page p){document=d;edited=p;}
    }
    private void recoverEditCheckpoint(){
        Models.Document owner=document;if(owner==null)return;
        runIO("正在读取暂存编辑…",()->new EditDraftStore(getFilesDir()).list(owner.id),value->{
            if(document!=owner){toast("文稿已切换，未打开暂存编辑");return;}
            @SuppressWarnings("unchecked") ArrayList<EditDraftStore.Entry> entries=(ArrayList<EditDraftStore.Entry>)value;
            if(entries.isEmpty()){toast("这份文稿没有明确暂存的编辑副本；未保存的预览不等于磁盘草稿");return;}
            String[] labels=new String[entries.size()];for(int i=0;i<entries.size();i++){EditDraftStore.Entry e=entries.get(i);labels[i]=e.valid?new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.getDefault()).format(new Date(e.created))+" · "+e.token.substring(0,8):"不可载入的记录 · "+e.token;}
            new AlertDialog.Builder(this).setTitle("选择暂存编辑（不自动覆盖）").setItems(labels,(dialog,index)->{
                EditDraftStore.Entry selected=entries.get(index);if(!selected.valid){new AlertDialog.Builder(this).setTitle("草稿未通过核对").setMessage(selected.error+"。原记录仍保留，未删除或自动修复。").setPositiveButton("知道了",null).show();return;}
                new AlertDialog.Builder(this).setTitle("载入编辑预览？").setMessage("将再次核对暂存内容和最新页面基线。仅打开编辑预览，需按“保存本页”才更改文稿；旧暂存副本仍保留。若页面已变化则拒绝覆盖。").setPositiveButton("核对并载入",(confirm,button)->runIO("正在核对草稿与页面…",()->{
                    EditDraftStore.Draft checkpoint=new EditDraftStore(getFilesDir()).load(owner.id,selected);Models.Document fresh=store.load(owner.id);return new RecoveredEdit(fresh,matchCheckpoint(fresh,checkpoint));
                },result->{if(document!=owner){toast("文稿已切换，暂存编辑未应用");return;}RecoveredEdit restored=(RecoveredEdit)result;document=restored.document;for(Models.Page page:document.pages)if(page.id.equals(restored.edited.id)){openEditor(page,restored.edited);toast("已载入暂存预览；请检查后保存本页");return;}})).setNeutralButton("导出草稿副本",(confirm,button)->exportEditCheckpoint(owner,selected)).setNegativeButton("取消",null).show();
            }).setNegativeButton("取消",null).show();
        });
    }
    private void exportEditCheckpoint(Models.Document owner,EditDraftStore.Entry selected){
        if(activeSaveToken!=null){toast("已有保存请求尚未结束，请先处理；草稿保留");return;}
        new AlertDialog.Builder(this).setTitle("导出私密编辑记录？").setMessage("副本包含原页面基线、编辑参数和可能存在的旧识别文字，可能含私密内容；副本以 .bin 标识，不含原图，也不是可直接导入的 ZIP 备份。不要求当前页面基线一致，可保留冲突草稿供人工核对。请选择可信的保存位置；不会自动分享或删除源草稿。").setPositiveButton("创建副本并选择位置",(dialog,which)->{
            if(activeSaveToken!=null){toast("已有保存请求尚未结束；草稿保留");return;}
            runIO("正在校验并复制草稿…",()->new EditDraftStore(getFilesDir()).export(owner.id,selected,getCacheDir()),result->{
                EditDraftStore.Export exported=(EditDraftStore.Export)result;
                if(document!=owner||activeSaveToken!=null){toast("文稿或保存状态已变化；草稿与本地副本保留，未发起外部保存");return;}
                pendingExport=exported.file;approvedExportSource=exported.file;approvedExportDigest=exported.digest;approvedExportBytes=exported.bytes;pendingKind="bin";saveExport();
            },true);
        }).setNegativeButton("取消",null).show();
    }
    // Worker-only: reject a result if the same document was edited while it was generated.
    private void verifyExportSnapshot(Models.Document owner,Models.Document frozen)throws Exception {Models.Document current=store.snapshotForExport(owner);if(!current.json().toString().equals(frozen.json().toString()))throw new IOException("文稿在导出期间已变化，请核对后重新生成；原图保留");}
    private static final class PdfExportResult {final File file;final Models.Document document;PdfExportResult(File f,Models.Document d){file=f;document=d;}}
    /** Unambiguous page/title framing prevents stale PDFs from matching a different document state. */
    private String pdfRevision(Models.Document d){StringBuilder key=new StringBuilder("pdf-revision-v3|");pdfRevisionPart(key,d.id);pdfRevisionPart(key,d.title);for(Models.Page p:d.active()){pdfRevisionPart(key,p.id);pdfRevisionPart(key,OcrEngine.key(p));pdfRevisionPart(key,p.cropReview);}return boundedPdfRevision(key.toString());}
    private static String boundedPdfRevision(String value){
        if(value==null||value.isEmpty()||value.matches("[a-f0-9]{64}"))return value==null?"":value;
        try{byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
        catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private static void pdfRevisionPart(StringBuilder out,String value){out.append(value.length()).append(':').append(value);}
    private void previewPdf(){Models.Document owner=document;File pdf=recentPdf;String revision=recentPdfRevision;runIO("正在核对 PDF 版本…",()->{Models.Document checked=store.snapshotForExport(owner);requireReviewedCrops(checked);if(pdf==null||!pdf.isFile()||!revision.equals(pdfRevision(checked)))throw new IOException("尚无当前版本的 PDF，请先生成；文稿改动后需要重新导出");return pdf;},value->{if(document!=owner||!revision.equals(pdfRevision(owner))){toast("文稿已改变，请重新生成 PDF");return;}Intent view=new Intent(this,PdfPreviewActivity.class);view.putExtra(PdfPreviewActivity.EXTRA_NAME,((File)value).getName());startActivity(view);});}
    private void reusePdf(boolean share){Models.Document owner=document;File pdf=recentPdf;String revision=recentPdfRevision;runIO("正在核对文稿版本…",()->{Models.Document checked=store.snapshotForExport(owner);requireReviewedCrops(checked);if(pdf==null||!pdf.isFile()||!revision.equals(pdfRevision(checked)))throw new IOException("尚无当前版本的 PDF，请先生成；文稿改动后需要重新导出");return pdf;},value->{if(document!=owner){toast("文稿已切换，请重新选择");return;}pendingExport=(File)value;approvedExportSource=pendingExport;approvedExportDigest=null;approvedExportBytes=0;pendingKind="pdf";if(share)shareExport();else saveExport();});}
    private static void requireReviewedCrops(Models.Document doc)throws IOException{int i=0;for(Models.Page page:doc.active()){i++;if(!page.cropConfirmed())throw new IOException("第 "+i+" 页四角尚未核对。请打开页面，检查四角或选择全图，确认后保存；原图和 ZIP 备份仍可导出。");}}
    private static void requireSavedPageReview(Models.Document doc,Models.Page draft)throws IOException{for(Models.Page saved:doc.active())if(saved.id.equals(draft.id)){if(saved.cropConfirmed()&&draft.cropConfirmed()&&saved.cropReview.equals(draft.cropReview))return;break;}throw new IOException("请先检查并确认四角，然后保存本页；原图副本仍可导出。");}
    private void exportPageImage(){
        new AlertDialog.Builder(this).setTitle("导出本页图片").setItems(new String[]{"高清 PNG（无损编码 · 长边≤4000像素）","高清 JPEG（长边≤4000像素）","原图副本（保留拍摄元数据）"},(dialog,which)->{
            try{Models.Document d=document;Models.Page page=draft.copy();runIO("正在准备图片…",()->{
                Models.Document frozen=which==2?null:store.snapshotForExport(d);if(frozen!=null)requireSavedPageReview(frozen,page);File folder=new File(getCacheDir(),"exports");folder.mkdirs();File original=store.original(d,page);String kind=which==0?"png":"jpg";
                if(which==2){BitmapFactory.Options info=new BitmapFactory.Options();info.inJustDecodeBounds=true;BitmapFactory.decodeFile(original.getPath(),info);String mime=info.outMimeType;kind="image/png".equals(mime)?"png":"image/webp".equals(mime)?"webp":"image/heif".equals(mime)||"image/heic".equals(mime)?"heic":"image/gif".equals(mime)?"gif":"image/jpeg".equals(mime)?"jpg":"bin";}
                File result=new File(folder,"page-"+UUID.randomUUID()+"."+kind);
                try(OutputStream out=new FileOutputStream(result)){
                    if(which==2)Exporter.copy(original,out);else {Bitmap b=Renderer.render(original,page,4000);try{if(!b.compress(which==0?Bitmap.CompressFormat.PNG:Bitmap.CompressFormat.JPEG,98,out))throw new IOException("图片编码未完成");}finally{b.recycle();}}
                }
                if(frozen!=null)verifyExportSnapshot(d,frozen);return result;
            },r->{if(document!=d){toast("文稿已切换，图片仍保留在本地缓存");return;}pendingExport=(File)r;approvedExportSource=pendingExport;approvedExportDigest=null;approvedExportBytes=0;pendingKind=pendingExport.getName().substring(pendingExport.getName().lastIndexOf('.')+1);saveExport();});}catch(Exception e){error(e);}
        }).setNegativeButton("取消",null).show();
    }
    private ArrayAdapter<String> optionAdapter(String[] values){return new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,values){
        private View label(View v){TextView t=(TextView)v;t.setSingleLine(false);t.setMaxLines(3);t.setEllipsize(null);t.setTextSize(14);t.setTextColor(INK);t.setPadding(dp(6),dp(8),dp(6),dp(8));t.setBackgroundColor(SURFACE);return t;}
        @Override public View getView(int position,View recycled,ViewGroup parent){return label(super.getView(position,recycled,parent));}
        @Override public View getDropDownView(int position,View recycled,ViewGroup parent){return label(super.getDropDownView(position,recycled,parent));}
    };}
    private void exportOptions(){
        try{requireReviewedCrops(document);}catch(IOException pending){error(pending);return;}
        LinearLayout panel=column();panel.setPadding(dp(20),dp(8),dp(20),dp(8));panel.setBackgroundColor(SURFACE);
        panel.addView(text("页面与清晰度",16,INK));
        CheckBox a4=new CheckBox(this);a4.setText("A4 页面（关闭则保持图片比例）");a4.setChecked(exportA4);a4.setMinHeight(dp(48));panel.addView(a4);
        TextView qualityLabel=text("图片清晰度",13,MUTED);panel.addView(qualityLabel);Spinner quality=new Spinner(this);quality.setContentDescription("PDF 图片清晰度");quality.setMinimumHeight(dp(48));quality.setAdapter(optionAdapter(new String[]{"标准 · 长边≤2800像素","省空间 · 长边≤1800像素","高清 · 长边≤4000像素"}));quality.setSelection(exportEdge==1800?1:exportEdge==4000?2:0);panel.addView(quality);
        panel.addView(text("文字提取与搜索",16,INK));
        CheckBox searchable=new CheckBox(this);searchable.setText("可搜索 PDF · 本地 OCR");searchable.setMinHeight(dp(48));panel.addView(searchable);
        TextView languageLabel=text("重新识别语言 · 不使用已纠错的文字",13,MUTED);panel.addView(languageLabel);Spinner language=new Spinner(this);language.setContentDescription("PDF 文字识别语言");language.setMinimumHeight(dp(48));language.setAdapter(optionAdapter(new String[]{"英文 · 教材与讲义","中英混合"}));language.setSelection(getPreferences(0).getString("pdfOcrLanguage","eng").equals("chi_sim+eng")?1:0);language.setEnabled(false);language.setAlpha(.55f);panel.addView(language);
        searchable.setOnCheckedChangeListener((v,checked)->{a4.setEnabled(!checked);language.setEnabled(checked);language.setAlpha(checked?1f:.55f);languageLabel.setText(checked?"重新识别语言 · 不使用已纠错的文字":"识别语言 · 启用可搜索 PDF 后可选");if(checked)a4.setChecked(false);});
        panel.addView(text("长边为上限，小图不放大。省空间档会缩小图像；高清档尽量保留裁切区域细节。原图始终保留。可搜索 PDF 按图片比例输出并重新识别，不使用逐页纠错文字；公式与手写以图像为准。",13,MUTED));
        ScrollView options=new ScrollView(this);options.setFillViewport(false);options.addView(panel);

        AlertDialog exportDialog=new AlertDialog.Builder(this).setTitle("导出 PDF").setView(options).setPositiveButton("生成",(d,w)->{exportA4=a4.isChecked();exportEdge=new int[]{2800,1800,4000}[quality.getSelectedItemPosition()];try{Models.Document owner=document;Exporter.Progress report=(done,total)->runOnUiThread(()->{if(progress!=null)progress.setMessage("正在生成 PDF · "+done+" / "+total);});boolean ocr=searchable.isChecked();String selectedLanguage=language.getSelectedItemPosition()==0?"eng":"chi_sim+eng";if(ocr)getPreferences(0).edit().putString("pdfOcrLanguage",selectedLanguage).apply();runIO("正在生成 PDF…",()->{Models.Document copy=store.snapshotForExport(owner);requireReviewedCrops(copy);File pdf=ocr?OcrEngine.searchablePdf(store,copy,new File(getCacheDir(),"exports"),selectedLanguage,exportEdge,report):Exporter.pdf(store,copy,new File(getCacheDir(),"exports"),exportA4,exportEdge,report);verifyExportSnapshot(owner,copy);return new PdfExportResult(pdf,copy);},result->{if(document!=owner){toast("文稿已切换，生成文件仍保留在本地缓存");return;}PdfExportResult completed=(PdfExportResult)result;Models.Document copy=completed.document;pendingExport=completed.file;approvedExportSource=pendingExport;approvedExportDigest=null;approvedExportBytes=0;pendingKind="pdf";recentPdf=pendingExport;recentPdfRevision=pdfRevision(copy);new AlertDialog.Builder(this).setTitle("PDF 已生成").setMessage("生成时的文稿快照 · "+copy.active().size()+" 页 · "+String.format(Locale.CHINA,"%.1f MB",pendingExport.length()/1048576.0)).setPositiveButton("保存到文件",(a,b)->saveExport()).setNeutralButton("分享",(a,b)->shareExport()).setNegativeButton("稍后",null).show();},true);}catch(Exception e){error(e);}}).setNegativeButton("取消",null).create();exportDialog.show();exportDialog.getWindow().setBackgroundDrawable(rounded(SURFACE,16));
    }
    private SaveJobStore saveJobs(){return new SaveJobStore(this);}
    private static boolean matchesApprovedSource(String digest,long bytes,SaveJobStore.Job job){return digest==null||(digest.equals(job.sha)&&bytes==job.bytes);}
    private void saveExport(){
        if(pendingExport==null||!pendingExport.isFile()){toast("请重新生成导出文件");return;}
        if(!pendingExport.equals(approvedExportSource)){toast("旧导出文件缺少四角核对凭据，源文件保留；请核对页面后重新生成，或重新导出原图 / ZIP");return;}
        if(!alive()||!resumed||stateSaved||busy||!pendingResults.isEmpty()||resultDrainScheduled){toast("请等待页面恢复或当前操作完成，导出源仍保留");return;}
        if(activeSaveToken!=null){
            String offered=activeSaveToken;
            new AlertDialog.Builder(this).setTitle("保存请求尚未确认").setMessage("可取消，或重新选择一个保存位置。不会自动改写之前的目标；导出源保留。")
                .setPositiveButton("重新选择",(d,w)->{if(!offered.equals(activeSaveToken)){toast("保存请求已变化，请重新操作");return;}runIO("正在结束旧保存请求…",()->{saveJobs().cancel(offered);return null;},value->{if(!offered.equals(activeSaveToken)){toast("保存请求已变化；导出源保留");return;}activeSaveToken=null;textSavePermits.remove(offered);approvedSaveTokens.remove(offered);saveExport();});})
                .setNegativeButton("取消此请求",(d,w)->{if(!offered.equals(activeSaveToken)){toast("保存请求已变化，请重新操作");return;}String token=offered;activeSaveToken=null;handledSaveToken=token;textSavePermits.remove(token);approvedSaveTokens.remove(token);runIO("正在取消保存请求…",()->{saveJobs().cancel(token);return null;},value->toast("已取消，导出源仍保留"));}).show();return;
        }
        PreparedText text="txt".equals(pendingKind)?preparedText:null;
        if("txt".equals(pendingKind)&&(!textContextCurrent(text)||text.context==null||!pendingExport.equals(text.file))){toast("文字副本来源已变化，请重新选择");return;}
        File source=pendingExport;String expectedDigest=approvedExportDigest;long expectedBytes=approvedExportBytes;String mime=exportMime(),title=text!=null?text.title:(document==null?"扫描文稿":document.title).replaceAll("[\\/:*?\"<>|]","_")+"."+pendingKind;
        runIO("正在核对导出源，准备另存为…",()->{if(text!=null)requireTextDiskCurrent(text);return saveJobs().begin(source,mime,title);},value->{
            SaveJobStore.Job job=(SaveJobStore.Job)value;
            if(!alive()||!resumed||stateSaved){toast("保存尚未启动；源与请求保留，请在文库核对未完成的保存");return;}
            if(!source.equals(approvedExportSource)||!Objects.equals(expectedDigest,approvedExportDigest)||expectedBytes!=approvedExportBytes){toast("导出源已变化，未打开保存位置；请在文库核对未完成的保存");return;}
            if(!matchesApprovedSource(expectedDigest,expectedBytes,job)){toast("片段副本与选中暂存不一致，未打开保存位置；请在文库核对未完成的保存");return;}
            if(text!=null&&!textContextCurrent(text)){runIO("正在结束过期文字请求…",()->{saveJobs().cancel(job.token);return null;},ignored->toast("文字来源已变化，未打开目标，请重新生成"));return;}
            activeSaveToken=job.token;approvedSaveTokens.add(job.token);if(text!=null)textSavePermits.put(job.token,text);
            try{startActivityForResult(new Intent(this,SavePickerActivity.class).setAction(SavePickerActivity.ACTION_START).putExtra(SavePickerActivity.EXTRA_TOKEN,job.token),SAVE);}
            catch(RuntimeException e){error(new IOException("保存选择器启动结果未确认；源与请求保留，请在文库核对未完成的保存",e));}
        });
    }
    private void recoverSaveResult(String token,String legacyPath){
        runIO("正在核对保留的导出源…",()->saveJobs().recoverSource(token,legacyPath).getPath(),value->{
            String path=(String)value;if(path.endsWith(".txt")){recoverTextSave(token);return;}new AlertDialog.Builder(this).setTitle("请重新选择保存位置")
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
            if(accepted&&token!=null){handledSaveToken=token;textSavePermits.remove(token);runIO("正在取消保存请求…",()->{saveJobs().cancel(token);return null;},value->toast("已取消或中断保存，导出源仍保留；需要时重新选择位置"));}
            else toast("保存已取消或未确认，未写入旧位置；导出源仍保留");return;
        }
        if(token==null||!accepted||!token.equals(activeSaveToken)){
            if(token!=null&&token.equals(handledSaveToken))return;
            if(activeSaveToken!=null){toast("较早保存结果已过期，未写入旧位置");return;}
            recoverSaveResult(token,event.getString("source"));return;
        }
        if(!approvedSaveTokens.remove(token)){toast("旧保存结果缺少四角核对凭据，未写入目标；导出源保留，请重新生成或重新导出原图 / ZIP");return;}
        PreparedText text=textSavePermits.remove(token);boolean textCurrent=textContextCurrent(text);
        activeSaveToken=null;handledSaveToken=token;Uri uri=event.getParcelable("uri");int flags=event.getInt("flags");
        if(!SavePickerActivity.validDestination(uri,getPackageName())){recoverSaveResult(token,null);return;}
        runIO("正在保存到新选择的位置…",()->{
            SaveJobStore jobs=saveJobs();SaveJobStore.Job request=jobs.read();
            if(request!=null&&token.equals(request.token)&&"text/plain".equals(request.mime)){
                if(!textCurrent){jobs.cancel(token);throw new IOException("文字来源、文稿或页序已变化，未打开目标；请重新选择来源");}
                try{requireTextDiskCurrent(text);}catch(Exception stale){jobs.cancel(token);throw stale;}
                try{TextDocumentSave.save(jobs,token,()->openNewTextDestination(uri,flags));return "text";}catch(Exception problem){throw new SaveWriteFailure(token,problem);}
            }
            try(SaveJobStore.Claim claim=jobs.claim(token)){
                retainPickerPermission(uri,flags,true);
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("保存已中断，未打开位置");
                try(OutputStream out=getContentResolver().openOutputStream(uri,"w")){if(out==null)throw new IOException("所选位置无法写入");Exporter.copy(claim.input,out);}
                jobs.done(token);return null;
            }catch(Exception e){try{jobs.uncertain(token);}catch(Exception state){e.addSuppressed(state);}throw new SaveWriteFailure(token,e);}
        },value->toast("text".equals(value)?"文字副本已保存；文稿草稿和 PDF 文字层未改变":"已保存，原图与导出源仍保留"));
    }
    private String exportMime(){switch(pendingKind){case "txt":return "text/plain";case "pdf":return "application/pdf";case "zip":return "application/zip";case "png":return "image/png";case "jpg":return "image/jpeg";case "webp":return "image/webp";case "heic":return "image/heic";case "gif":return "image/gif";default:return "application/octet-stream";}}
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
    private void handleBack(){if(busy){toast("正在保存，请稍候");return;}if(screen==3)leaveRecovery();else if(screen==2)leaveEditor();else if(screen==1)showLibrary("");else finish();}
    // API 33+ uses the platform callback above; this override handles API 26–32 only.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed(){handleBack();}
}
