package org.wangshu.scan;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.*;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.*;
import android.os.*;
import android.util.Size;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.*;

/** One native preview for consecutive shots. Durable JPEG receipts precede the saved counter. */
public final class CaptureActivity extends Activity {
    static final String EXTRA_SESSION="captureSession";
    private TextureView texture;
    private final org.wangshu.scan.core.SurfaceLifetime<SurfaceTexture> surfaceLifetime=new org.wangshu.scan.core.SurfaceLifetime<>();
    private TextView status;
    private FrameLayout windowRoot,backOverlay;
    private LinearLayout captureRoot;
    private int captureAccessibilityBefore,captureFocusabilityBefore;
    private View captureFocusBefore;
    private Button shutter,done,retry;
    private String batchId;
    private volatile CameraRun camera;
    private volatile boolean visible=false,journalDisposed=false,batchUncertain=false;
    private boolean finishAfterSave=false,exitAfterSave=false,recovering=true,recoveryFailed=false,cameraPermissionRequested=false,checkingExit=false;
    private Integer checkedBackSaved;
    private Throwable checkedBackFailure;
    private final ExecutorService journal=Executors.newSingleThreadExecutor(r->new Thread(r,"capture-journal"));
    private MainThreadWatchdog watchdog;
    private int saved=0;
    private int pendingExit=Integer.MIN_VALUE;
    private boolean exitCompleted=false;
    private final Handler lifecycleUi=new Handler(Looper.getMainLooper());
    private int dp(int n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
    private android.graphics.drawable.GradientDrawable controlBackground(int fill,int border){android.graphics.drawable.GradientDrawable shape=new android.graphics.drawable.GradientDrawable();shape.setColor(fill);shape.setCornerRadius(dp(12));if(border!=0)shape.setStroke(dp(1),border);return shape;}
    private void styleControl(Button button,boolean primary){
        android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled},controlBackground(getColor(R.color.capture_disabled_surface),0));
        states.addState(new int[]{},controlBackground(primary?getColor(R.color.capture_primary):getColor(R.color.capture_secondary),primary?0:getColor(R.color.capture_stroke)));
        button.setBackgroundTintList(null);button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(getColor(R.color.capture_ripple)),states,null));button.setStateListAnimator(null);button.setElevation(0);
        button.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{getColor(R.color.capture_disabled_ink),primary?getColor(R.color.capture_on_primary):getColor(R.color.capture_text)}));
    }
    private Button button(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setTextSize(15);b.setAllCaps(false);b.setOnClickListener(v->action.run());b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setPadding(dp(10),dp(8),dp(10),dp(8));styleControl(b,false);return b;}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);watchdog=MainThreadWatchdog.forActivity(this,lifecycleUi);watchdog.start();watchdog.phase("capture-create");if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::handleBack);batchId=state==null?getIntent().getStringExtra(EXTRA_SESSION):state.getString(EXTRA_SESSION);cameraPermissionRequested=state!=null&&state.getBoolean("cameraPermissionRequested");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root=new LinearLayout(this);captureRoot=root;root.setTag("capture-body");root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(getColor(R.color.capture_background));root.setPadding(dp(8),dp(4),dp(8),dp(4));
        root.setOnApplyWindowInsetsListener((v,insets)->{if(Build.VERSION.SDK_INT>=30){Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(dp(8)+bars.left,dp(4)+bars.top,dp(8)+bars.right,dp(4)+bars.bottom);}else v.setPadding(dp(8)+insets.getSystemWindowInsetLeft(),dp(4)+insets.getSystemWindowInsetTop(),dp(8)+insets.getSystemWindowInsetRight(),dp(4)+insets.getSystemWindowInsetBottom());return insets;});
        TextView title=new TextView(this);title.setText("连续扫描");title.setTextSize(22);title.setTextColor(Color.WHITE);title.setPadding(dp(8),dp(8),dp(8),dp(4));root.addView(title);
        status=new TextView(this);status.setTextColor(getColor(R.color.capture_muted));status.setPadding(dp(8),dp(4),dp(8),dp(8));root.addView(status);
        FrameLayout preview=new FrameLayout(this);preview.setBackgroundColor(Color.BLACK);texture=new TextureView(this);texture.setContentDescription("连续扫描相机取景");preview.addView(texture,new FrameLayout.LayoutParams(-1,-1));root.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        TextView hint=new TextView(this);hint.setText("连续拍摄后统一裁切、矫正和排序。原图保留。");hint.setTextColor(getColor(R.color.capture_muted));hint.setPadding(dp(8),dp(8),dp(8),dp(4));root.addView(hint);
        LinearLayout controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER);shutter=button("拍摄下一页",this::takePicture);done=button("完成并整理",this::requestFinish);styleControl(shutter,true);LinearLayout.LayoutParams shutterLayout=new LinearLayout.LayoutParams(0,dp(58),1),doneLayout=new LinearLayout.LayoutParams(0,dp(58),1);shutterLayout.setMargins(dp(4),dp(4),dp(4),dp(4));doneLayout.setMargins(dp(4),dp(4),dp(4),dp(4));controls.addView(shutter,shutterLayout);controls.addView(done,doneLayout);root.addView(controls);
        retry=button("重新打开相机",()->{if(backOverlay!=null||checkingExit)return;if(recoveryFailed){recoverJournal();return;}cameraPermissionRequested=false;stopCamera();startCamera();});root.addView(retry);windowRoot=new FrameLayout(this);windowRoot.addView(root,new FrameLayout.LayoutParams(-1,-1));setContentView(windowRoot);root.requestApplyInsets();
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){public void onSurfaceTextureAvailable(SurfaceTexture s,int w,int h){startCamera();}public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h){transform();}public boolean onSurfaceTextureDestroyed(SurfaceTexture s){stopCamera();surfaceLifetime.retire(s,s::release);return false;}public void onSurfaceTextureUpdated(SurfaceTexture s){}});
        recoverJournal();
    }
    private void journalTask(Runnable work){if(journalDisposed)return;try{journal.execute(work);}catch(RejectedExecutionException ignored){/* A closing Activity cannot accept another journal read; durable camera receipt already exists. */}}
    private void recoverJournal(){
        if(journalDisposed)return;recovering=true;recoveryFailed=false;refresh("正在读取拍摄记录…");
        journalTask(()->{
            try{CaptureBatch.Batch initial=CaptureBatch.load(this,batchId);if(initial.closed)throw new IOException("此批次已经整理完成");}
            catch(Exception|OutOfMemoryError|LinkageError e){runOnUiThread(()->{if(journalDisposed||isDestroyed()||isFinishing())return;recovering=false;recoveryFailed=true;if(e instanceof BatchFence.Uncertain)uncertainBatch();else{Toast.makeText(this,"连续扫描记录无法打开；原文件保留",Toast.LENGTH_LONG).show();finish();}});return;}
            try{CaptureBatch.Batch batch=CaptureBatch.recover(this,batchId);runOnUiThread(()->{
                if(journalDisposed||isDestroyed()||isFinishing()||batchUncertain)return;
                saved=batch.saved();recovering=false;refresh(null);
                if(finishAfterSave){finishAfterSave=false;finishReview();}else if(exitAfterSave){exitAfterSave=false;backPrompt();}else if(visible)startCamera();
            });}catch(Exception|OutOfMemoryError|LinkageError e){runOnUiThread(()->{if(journalDisposed||isDestroyed()||isFinishing())return;recovering=false;recoveryFailed=true;message(e);});}
        });
    }
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString(EXTRA_SESSION,batchId);out.putBoolean("cameraPermissionRequested",cameraPermissionRequested);}
    @Override protected void onStart(){super.onStart();if(watchdog!=null)watchdog.start();}
    @Override protected void onResume(){super.onResume();if(watchdog!=null)watchdog.phase("capture-resume");SystemBars.apply(this,false);visible=true;if(pendingExit!=Integer.MIN_VALUE)finishIfCameraClosed();else if(checkedBackSaved!=null)applyBackCheck();else if(!checkingExit&&texture!=null&&texture.isAvailable())startCamera();}
    @Override protected void onPause(){visible=false;finishAfterSave=false;exitAfterSave=false;stopCamera();super.onPause();}
    @Override protected void onStop(){if(watchdog!=null)watchdog.stop();super.onStop();}
    @Override protected void onDestroy(){journalDisposed=true;journal.shutdownNow();if(watchdog!=null)watchdog.close();dismissBackPrompt();lifecycleUi.removeCallbacksAndMessages(null);super.onDestroy();}
    @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);if(texture!=null)texture.post(this::transform);}
    @android.annotation.SuppressLint("GestureBackNavigation") @Override public void onBackPressed(){handleBack();}
    private void handleBack(){if(batchUncertain){exitAfterCameraClosed(RESULT_CANCELED);return;}if(checkingExit){refresh("正在核对已保存照片，请稍候…");return;}if(backOverlay!=null){dismissBackPrompt();return;}if(pendingExit!=Integer.MIN_VALUE){refresh("正在结束取景；原图已保存…");return;}if(recovering){exitAfterSave=true;refresh("正在找回已保存照片，随后显示退出选项…");return;}CameraRun r=camera;if(r!=null&&r.busy.get()){exitAfterSave=true;refresh("正在保存这页，随后可选择整理或稍后继续…");return;}backPrompt();}
    private void backPrompt(){
        if(batchUncertain)return;
        if(journalDisposed||isFinishing()||isDestroyed()||backOverlay!=null||windowRoot==null||captureRoot==null||checkingExit)return;
        checkingExit=true;refresh("正在核对已保存照片…");boolean closeEmpty=!recoveryFailed;
        journalTask(()->{
            int count=-1;Throwable failure=null;
            try{
                // A completed receipt can precede the posted UI counter. All lock,
                // JSON, JPEG recovery/hash and AtomicFile work stays off main.
                count=CaptureBatch.load(this,batchId).saved();
                if(count==0&&closeEmpty)CaptureBatch.close(this,batchId);
            }catch(Exception|OutOfMemoryError|LinkageError e){failure=e;}
            final int found=count;final Throwable error=failure;
            runOnUiThread(()->{if(journalDisposed||isDestroyed()||isFinishing())return;checkedBackSaved=found;checkedBackFailure=error;applyBackCheck();});
        });
    }
    private void applyBackCheck(){
        // Home/rotation does not turn an in-progress Back decision into another
        // photo. Keep the result until resumed; never add a window in background.
        if(!visible||checkedBackSaved==null||journalDisposed||isDestroyed()||isFinishing())return;
        int count=checkedBackSaved;Throwable failure=checkedBackFailure;checkedBackSaved=null;checkedBackFailure=null;checkingExit=false;
        if(failure!=null){if(failure instanceof BatchFence.Uncertain)uncertainBatch();else{startCamera();message(failure);}return;}
        saved=count;if(saved==0){exitAfterCameraClosed(RESULT_CANCELED);return;}
        showBackPrompt();startCamera();
    }
    private void showBackPrompt(){
        if(journalDisposed||isFinishing()||isDestroyed()||backOverlay!=null||windowRoot==null||captureRoot==null)return;
        // Reuse this Activity's existing renderer/window. A second hardware-backed
        // Dialog window blocked in HardwareRenderer creation on the cloud API36 image.
        FrameLayout overlay=new FrameLayout(this);backOverlay=overlay;overlay.setTag("capture-back-prompt");overlay.setContentDescription("连续扫描退出选项");overlay.setBackgroundColor(getColor(R.color.capture_scrim));overlay.setClickable(true);overlay.setFocusable(true);overlay.setFocusableInTouchMode(true);overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        if(Build.VERSION.SDK_INT>=28)overlay.setAccessibilityPaneTitle("连续扫描退出选项");
        overlay.setPadding(dp(20),dp(20),dp(20),dp(20));
        overlay.setOnApplyWindowInsetsListener((view,insets)->{if(Build.VERSION.SDK_INT>=30){Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());view.setPadding(dp(20)+bars.left,dp(20)+bars.top,dp(20)+bars.right,dp(20)+bars.bottom);}else view.setPadding(dp(20)+insets.getSystemWindowInsetLeft(),dp(20)+insets.getSystemWindowInsetTop(),dp(20)+insets.getSystemWindowInsetRight(),dp(20)+insets.getSystemWindowInsetBottom());return insets;});
        ScrollView panel=new ScrollView(this);panel.setFillViewport(false);panel.setBackground(controlBackground(getColor(R.color.capture_surface),getColor(R.color.capture_stroke)));panel.setClipToOutline(true);panel.setPadding(dp(18),dp(18),dp(18),dp(18));panel.setClickable(true);
        LinearLayout choices=new LinearLayout(this);choices.setOrientation(LinearLayout.VERTICAL);
        TextView heading=new TextView(this);heading.setText("已保存 "+saved+" 张原图");heading.setTextSize(22);heading.setTextColor(getColor(R.color.capture_text));if(Build.VERSION.SDK_INT>=28)heading.setAccessibilityHeading(true);choices.addView(heading,new LinearLayout.LayoutParams(-1,-2));
        TextView explanation=new TextView(this);explanation.setText("可现在整理页面，或稍后恢复继续拍摄。已保存照片不会因返回而删除。");explanation.setTextSize(15);explanation.setTextColor(getColor(R.color.capture_muted));explanation.setPadding(0,dp(10),0,dp(12));choices.addView(explanation,new LinearLayout.LayoutParams(-1,-2));
        addBackChoice(choices,overlay,"整理页面",true,this::finishReview);
        addBackChoice(choices,overlay,"稍后继续",false,()->exitAfterCameraClosed(RESULT_CANCELED));
        Button continueButton=addBackChoice(choices,overlay,"继续拍摄",false,()->{});
        panel.addView(choices,new ScrollView.LayoutParams(-1,-2));overlay.addView(panel,new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER));
        captureAccessibilityBefore=captureRoot.getImportantForAccessibility();captureFocusabilityBefore=captureRoot.getDescendantFocusability();captureFocusBefore=getCurrentFocus();
        captureRoot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);captureRoot.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        windowRoot.addView(overlay,new FrameLayout.LayoutParams(-1,-1));overlay.requestApplyInsets();continueButton.requestFocus();
        overlay.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
    }
    private Button addBackChoice(LinearLayout choices,FrameLayout owner,String label,boolean primary,Runnable action){
        Button control=button(label,()->{if(backOverlay!=owner)return;dismissBackPrompt();action.run();});styleControl(control,primary);LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2);layout.setMargins(0,dp(6),0,0);choices.addView(control,layout);return control;
    }
    private void dismissBackPrompt(){
        FrameLayout previous=backOverlay;if(previous==null)return;backOverlay=null;
        if(previous.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)previous.getParent()).removeView(previous);
        if(captureRoot!=null){captureRoot.setImportantForAccessibility(captureAccessibilityBefore);captureRoot.setDescendantFocusability(captureFocusabilityBefore);}
        View focus=captureFocusBefore;captureFocusBefore=null;if(focus!=null&&focus.isAttachedToWindow()&&focus.isShown())focus.requestFocus();
    }

    private void requestFinish(){if(batchUncertain||checkingExit)return;if(backOverlay!=null)return;if(pendingExit!=Integer.MIN_VALUE)return;if(recovering){finishAfterSave=true;refresh("正在找回已保存照片…");return;}CameraRun r=camera;if(r!=null&&r.busy.get()){finishAfterSave=true;refresh("正在保存最后这页…");return;}finishReview();}
    private void finishReview(){if(!batchUncertain)exitAfterCameraClosed(RESULT_OK);}
    private void exitAfterCameraClosed(int result){
        if(pendingExit!=Integer.MIN_VALUE||isFinishing()||isDestroyed())return;
        pendingExit=result;stopCamera();refresh("正在结束取景，随后整理已保存页面…");
        lifecycleUi.postDelayed(()->{if(!exitCompleted&&!isDestroyed())refresh("相机关闭较慢，原图已保存；本次扫描可稍后找回");},30000);finishIfCameraClosed();
    }
    private void finishIfCameraClosed(){
        // Keep the live TextureView window until CameraDevice.onClosed and all
        // reader/producer resources have drained. Never finish and then race the
        // asynchronous camera close against window destruction.
        if(pendingExit==Integer.MIN_VALUE||exitCompleted||surfaceLifetime.hasOwners()||isDestroyed())return;
        exitCompleted=true;lifecycleUi.removeCallbacksAndMessages(null);setResult(batchUncertain?RESULT_CANCELED:pendingExit,new Intent().putExtra(EXTRA_SESSION,batchId));finish();
    }
    private void refresh(String message){if(status==null)return;CameraRun r=camera;boolean busy=r!=null&&r.busy.get();shutter.setEnabled(!batchUncertain&&r!=null&&r.ready&&!busy&&!recovering&&!checkingExit&&pendingExit==Integer.MIN_VALUE);done.setEnabled(!batchUncertain&&!isFinishing()&&!recovering&&!checkingExit&&pendingExit==Integer.MIN_VALUE);retry.setVisibility(!batchUncertain&&pendingExit==Integer.MIN_VALUE&&(r==null||r.failed)?View.VISIBLE:View.GONE);retry.setEnabled(!busy&&!recovering&&!checkingExit);if(batchUncertain)message="批次记录结果未确认；原图和记录保留。请返回，暂勿重试该批次。";status.setText("已保存 "+saved+" 张"+(message==null?" · 可继续拍摄或完成整理":"\n"+message));}
    private void uncertainBatch(){batchUncertain=true;runOnUiThread(()->{if(!journalDisposed&&!isFinishing()&&!isDestroyed()){finishAfterSave=false;exitAfterSave=false;if(pendingExit!=Integer.MIN_VALUE)pendingExit=RESULT_CANCELED;dismissBackPrompt();stopCamera();refresh("批次记录结果未确认；原图和记录保留。请返回，暂勿重试该批次。");}});}
    private void message(Throwable e){if(e instanceof BatchFence.Uncertain){uncertainBatch();return;}runOnUiThread(()->{if(!journalDisposed&&!isFinishing()&&!isDestroyed())refresh((e.getMessage()==null?"相机暂不可用":e.getMessage())+"；已保存原图保留");});}
    private void startCamera(){
        if(journalDisposed||batchUncertain||recovering||recoveryFailed||checkingExit||pendingExit!=Integer.MIN_VALUE||!visible||texture==null||!texture.isAvailable()||camera!=null||isFinishing())return;
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){refresh("连续取景需要相机权限；可重试授权，或返回从相册导入");if(!cameraPermissionRequested){cameraPermissionRequested=true;requestPermissions(new String[]{Manifest.permission.CAMERA},31);}return;}
        SurfaceTexture output=texture.getSurfaceTexture();if(output==null||surfaceLifetime.isRetired(output))return;
        if(surfaceLifetime.hasOwners()){refresh("正在等待前一相机关闭；已保存原图保留…");return;}
        CameraRun r=new CameraRun(output);if(!surfaceLifetime.acquire(output,r))return;camera=r;r.thread.start();r.handler=new Handler(r.thread.getLooper());r.handler.post(r::open);r.handler.postDelayed(()->{if(!r.ready&&!r.closed)r.fail(new IOException("相机启动较慢，请重试或使用系统拍照"));},20000);refresh("正在打开相机…");
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==31){if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)startCamera();else refresh("相机权限未允许；已保存照片可整理，或返回从相册导入");}}
    private void stopCamera(){CameraRun r=camera;camera=null;if(r!=null)r.close();}
    private void takePicture(){if(batchUncertain||checkingExit)return;if(backOverlay!=null)return;CameraRun r=camera;if(r==null||!r.ready||!r.busy.compareAndSet(false,true))return;refresh("正在保存这页…");final int rotation=getWindowManager().getDefaultDisplay().getRotation()*90;r.handler.post(()->r.capture(rotation));}
    private void saved(CameraRun r){journalTask(()->{
        try{int count=CaptureBatch.load(this,batchId).saved();runOnUiThread(()->{if(journalDisposed||isDestroyed()||isFinishing()||batchUncertain)return;saved=count;if(camera==r)refresh(null);if(finishAfterSave){finishAfterSave=false;finishReview();}else if(exitAfterSave){exitAfterSave=false;backPrompt();}});}
        catch(Exception|OutOfMemoryError|LinkageError e){message(e);}
    });}
    /** TextureView already applies sensor rotation. Correct display rotation and fit without cropping. */
    private void transform(){
        CameraRun r=camera;if(r==null||r.previewSize==null||texture.getWidth()==0||texture.getHeight()==0)return;
        float w=texture.getWidth(),h=texture.getHeight();boolean swap=r.sensor%180!=0;float bw=swap?r.previewSize.getHeight():r.previewSize.getWidth(),bh=swap?r.previewSize.getWidth():r.previewSize.getHeight();
        float[] dst={0,0,bw,0,bw,bh,0,bh};Matrix rotation=new Matrix();rotation.setRotate(-90*getWindowManager().getDefaultDisplay().getRotation());rotation.mapPoints(dst);RectF bounds=new RectF();bounds.set(dst[0],dst[1],dst[0],dst[1]);for(int i=2;i<8;i+=2){bounds.left=Math.min(bounds.left,dst[i]);bounds.right=Math.max(bounds.right,dst[i]);bounds.top=Math.min(bounds.top,dst[i+1]);bounds.bottom=Math.max(bounds.bottom,dst[i+1]);}float scale=Math.min(w/bounds.width(),h/bounds.height());for(int i=0;i<8;i+=2){dst[i]=(dst[i]-bounds.left)*scale+(w-bounds.width()*scale)/2;dst[i+1]=(dst[i+1]-bounds.top)*scale+(h-bounds.height()*scale)/2;}Matrix matrix=new Matrix();matrix.setPolyToPoly(new float[]{0,0,w,0,w,h,0,h},0,dst,0,4);texture.setTransform(matrix);
    }
    private final class CameraRun {
        final SurfaceTexture outputTexture;
        CameraRun(SurfaceTexture output){outputTexture=output;}
        final HandlerThread thread=new HandlerThread("continuous-camera");Handler handler;
        final AtomicBoolean busy=new AtomicBoolean();volatile boolean ready=false,closed=false,failed=false;boolean opening=false,closingDevice=false,released=false;CameraDevice device;CameraCaptureSession session;ImageReader reader;Surface previewSurface;Size previewSize,jpegSize;int sensor=90,af=CaptureRequest.CONTROL_AF_MODE_OFF;Token active;
        final class Token{final String name;long timestamp=0;boolean complete=false;Token(String n){name=n;}}
        boolean current(){return !closed&&camera==this&&visible;}
        @android.annotation.SuppressLint("MissingPermission") void open(){try{
            CameraManager manager=(CameraManager)getSystemService(CAMERA_SERVICE);String id=null;CameraCharacteristics characteristics=null;
            for(String candidate:manager.getCameraIdList()){CameraCharacteristics c=manager.getCameraCharacteristics(candidate);Integer facing=c.get(CameraCharacteristics.LENS_FACING);if(facing!=null&&facing==CameraCharacteristics.LENS_FACING_BACK){id=candidate;characteristics=c;break;}}
            if(id==null)throw new IOException("未找到可用后置相机，可返回使用系统拍照或相册");Integer s=characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);sensor=s==null?90:s;int[] modes=characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);if(modes!=null)for(int mode:modes)if(mode==CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)af=mode;
            StreamConfigurationMap map=characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);if(map==null)throw new IOException("相机没有可用照片输出");Size[] jpeg=map.getOutputSizes(ImageFormat.JPEG);if(jpeg==null||jpeg.length==0)throw new IOException("相机不支持JPEG拍照");jpegSize=Arrays.stream(jpeg).filter(v->(long)v.getWidth()*v.getHeight()<=16_000_000L).max(Comparator.comparingLong(v->(long)v.getWidth()*v.getHeight())).orElse(jpeg[0]);
            Size[] previews=map.getOutputSizes(SurfaceTexture.class);if(previews==null||previews.length==0)throw new IOException("相机预览不可用");double ratio=(double)jpegSize.getWidth()/jpegSize.getHeight();previewSize=Arrays.stream(previews).filter(v->Math.max(v.getWidth(),v.getHeight())<=1920).min(Comparator.comparingDouble(v->Math.abs((double)v.getWidth()/v.getHeight()-ratio)*100+(Math.abs((long)v.getWidth()*v.getHeight()-640L*480)/(640.*480)))).orElse(previews[0]);
            reader=ImageReader.newInstance(jpegSize.getWidth(),jpegSize.getHeight(),ImageFormat.JPEG,2);reader.setOnImageAvailableListener(this::image,handler);
            runOnUiThread(()->{if(current())transform();});if(!current()){close();return;}
            opening=true;
            manager.openCamera(id,new CameraDevice.StateCallback(){
                public void onOpened(CameraDevice d){opening=false;device=d;if(!current()){release();return;}configure();}
                public void onDisconnected(CameraDevice d){opening=false;if(!closingDevice)device=d;if(closed)release();else fail(new IOException("相机已断开，请重试"));}
                public void onError(CameraDevice d,int error){opening=false;if(!closingDevice)device=d;if(closed)release();else fail(new IOException("相机暂不可用（"+error+"）"));}
                public void onClosed(CameraDevice d){opening=false;closingDevice=false;if(device==d)device=null;if(!closed)fail(new IOException("相机已关闭，请重试"));release();}
            },handler);
        }catch(Exception e){opening=false;fail(e);}}
        void configure(){try{SurfaceTexture surface=outputTexture;if(surface==null)throw new IOException("取景窗口尚未准备");surface.setDefaultBufferSize(previewSize.getWidth(),previewSize.getHeight());previewSurface=new Surface(surface);device.createCaptureSession(Arrays.asList(previewSurface,reader.getSurface()),new CameraCaptureSession.StateCallback(){public void onConfigured(CameraCaptureSession s){if(!current()){s.close();return;}session=s;try{CaptureRequest.Builder preview=device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);preview.addTarget(previewSurface);preview.set(CaptureRequest.CONTROL_AF_MODE,af);session.setRepeatingRequest(preview.build(),null,handler);ready=true;runOnUiThread(()->{if(current()){transform();refresh(null);}});}catch(Exception e){fail(e);}}public void onConfigureFailed(CameraCaptureSession s){s.close();fail(new IOException("相机无法同时取景与拍照，可使用系统拍照"));}},handler);}catch(Exception e){fail(e);}}
        void capture(int displayDegrees){if(!current()||session==null){busy.set(false);return;}try{
            CaptureBatch.Shot shot=CaptureBatch.reserve(CaptureActivity.this,batchId);Token token=new Token(shot.name);active=token;CaptureRequest.Builder request=device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);request.addTarget(reader.getSurface());request.set(CaptureRequest.CONTROL_AF_MODE,af);request.set(CaptureRequest.JPEG_QUALITY,(byte)95);request.set(CaptureRequest.JPEG_ORIENTATION,(sensor-displayDegrees+360)%360);request.setTag(token);
            session.capture(request.build(),new CameraCaptureSession.CaptureCallback(){@Override public void onCaptureStarted(CameraCaptureSession s,CaptureRequest r,long timestamp,long frame){token.timestamp=timestamp;}@Override public void onCaptureFailed(CameraCaptureSession s,CaptureRequest r,CaptureFailure failure){if(active==token&&!token.complete)fail(new IOException("这张照片未完成，请重试"));}},handler);
            handler.postDelayed(()->{if(active==token&&busy.get()&&!token.complete)fail(new IOException("这张照片尚未完成，请重新打开相机；已保存页面保留"));},20000);
        }catch(Exception e){busy.set(false);message(e);if(!(e instanceof BatchFence.Uncertain))saved(this);}}
        long lastTimestamp=0;
        void image(ImageReader source){Image image=null;Token token=active;try{
            image=source.acquireNextImage();if(image==null)return;long stamp=image.getTimestamp();if(token==null||token.complete||!busy.get()||stamp>0&&stamp<=lastTimestamp||stamp>0&&token.timestamp>0&&stamp!=token.timestamp)return;
            File target=CameraInbox.file(CaptureActivity.this,token.name);if(target.length()!=0)throw new IOException("拍照位置已有内容，未覆盖任何原文件");ByteBuffer bytes=image.getPlanes()[0].getBuffer();try(FileOutputStream out=new FileOutputStream(target)){while(bytes.hasRemaining())out.getChannel().write(bytes);out.getFD().sync();}
            CaptureBatch.complete(CaptureActivity.this,batchId,token.name);token.complete=true;lastTimestamp=stamp;busy.set(false);saved(this);
        }catch(Exception e){if(e instanceof BatchFence.Uncertain)uncertainBatch();fail(e);}finally{if(image!=null)image.close();}}
        void fail(Throwable error){failed=true;ready=false;busy.set(false);close();runOnUiThread(()->{if(camera==this&&visible&&!isDestroyed()){finishAfterSave=false;exitAfterSave=false;refresh((error.getMessage()==null?"相机暂不可用":error.getMessage())+"；已保存原图保留");}});}
        void close(){if(closed)return;closed=true;ready=false;if(handler!=null)handler.post(this::release);}
        void release(){
            if(released)return;
            if(session!=null){session.close();session=null;}
            if(device!=null){CameraDevice closing=device;device=null;closingDevice=true;closing.close();}
            // close() is asynchronous. Keep its callback thread, ImageReader and
            // TextureView producer surface alive until the final onClosed event.
            if(opening||closingDevice)return;
            if(reader!=null){
                // Only drain already queued buffers for this run's reserved capture.
                // CameraDevice.close can discard unfinished requests; never invent a saved page.
                // The reader holds at most two JPEG buffers and acquireNextImage never waits.
                for(int i=0;i<2&&active!=null&&!active.complete&&busy.get();i++)image(reader);
                reader.setOnImageAvailableListener(null,null);reader.close();reader=null;
            }
            if(previewSurface!=null){previewSurface.release();previewSurface=null;}
            released=true;surfaceLifetime.release(outputTexture,this);thread.quitSafely();
            runOnUiThread(()->{if(pendingExit!=Integer.MIN_VALUE)finishIfCameraClosed();else if(visible&&camera==null&&!isFinishing()&&!isDestroyed()&&texture.isAvailable())startCamera();});
        }
    }
}
