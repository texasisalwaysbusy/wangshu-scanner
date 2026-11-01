package org.wangshu.scan;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.widget.*;
import java.io.IOException;
import java.util.concurrent.*;

/** Private token-preserving bridge; Android's document picker need not echo extras. */
public final class SavePickerActivity extends Activity {
    static final String ACTION_START="org.wangshu.scan.SAVE_PICKER",ACTION_RESULT="org.wangshu.scan.SAVE_RESULT",EXTRA_TOKEN="saveJobToken",EXTRA_RESELECT="saveNeedsReselect";
    private static final int PICKER_CODE=1;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(r,"save-picker-journal"));
    private String token;private SaveJobStore.Job prepared;private boolean resumed,saved,resultSent,pickerStarted;
    static boolean validRequest(String action,String token,String callerPackage,String callerClass,String ownPackage){
        return ACTION_START.equals(action)&&SaveJobStore.validToken(token)&&ownPackage!=null&&ownPackage.equals(callerPackage)&&MainActivity.class.getName().equals(callerClass);
    }
    static boolean validDestination(Uri uri,String ownPackage){return uri!=null&&"content".equals(uri.getScheme())&&uri.getAuthority()!=null&&!uri.getAuthority().isEmpty()&&!uri.getAuthority().equals(ownPackage+".files")&&uri.getFragment()==null;}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);Intent intent=getIntent();ComponentName caller=getCallingActivity();token=intent==null?null:intent.getStringExtra(EXTRA_TOKEN);
        if(!validRequest(intent==null?null:intent.getAction(),token,caller==null?null:caller.getPackageName(),caller==null?null:caller.getClassName(),getPackageName())){finishResult(RESULT_CANCELED,null,0,true);return;}
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);int pad=(int)(24*getResources().getDisplayMetrics().density);panel.setPadding(pad,pad,pad,pad);panel.setBackgroundColor(getColor(R.color.paper));
        TextView label=new TextView(this);label.setText("正在打开保存位置…\n\n导出源会保留。若系统中断此流程，请重新选择位置；不会自动重写之前的目标。");label.setTextSize(17);label.setTextColor(getColor(R.color.ink));panel.addView(label);
        Button cancel=new Button(this);cancel.setText("取消保存并返回");cancel.setMinHeight((int)(48*getResources().getDisplayMetrics().density));cancel.setOnClickListener(v->cancel());panel.addView(cancel);setContentView(panel);
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::cancel);
        if(state!=null){finishResult(RESULT_CANCELED,null,0,true);return;} // no automatic relaunch after reconstruction
        submit(()->{SaveJobStore.Job job=new SaveJobStore(this).picker(token);runOnUiThread(()->{prepared=job;launchIfReady();});});
    }
    @Override protected void onResume(){super.onResume();resumed=true;saved=false;SystemBars.apply(this,(getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)!=android.content.res.Configuration.UI_MODE_NIGHT_YES);launchIfReady();}
    @Override protected void onPause(){resumed=false;super.onPause();}
    @Override protected void onSaveInstanceState(Bundle out){saved=true;super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
    private interface Work {void run()throws Exception;}
    private void submit(Work work){try{worker.execute(()->{try{work.run();}catch(Exception|LinkageError e){runOnUiThread(()->finishResult(RESULT_CANCELED,null,0,true));}});}catch(RejectedExecutionException e){finishResult(RESULT_CANCELED,null,0,true);}}
    private void launchIfReady(){
        if(resultSent||pickerStarted||prepared==null||!resumed||saved||isFinishing()||isDestroyed())return;
        pickerStarted=true;Intent picker=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(prepared.mime).putExtra(Intent.EXTRA_TITLE,prepared.title);
        try{startActivityForResult(picker,PICKER_CODE);}catch(RuntimeException e){finishResult(RESULT_CANCELED,null,0,true);}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(resultSent||request!=PICKER_CODE||!pickerStarted)return;
        if(result!=RESULT_OK){cancel();return;}Uri uri=data==null?null:data.getData();if(!validDestination(uri,getPackageName())){finishResult(RESULT_CANCELED,null,0,true);return;}
        int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        submit(()->{new SaveJobStore(this).chosen(token);runOnUiThread(()->finishResult(RESULT_OK,uri,flags,false));});
    }
    private void cancel(){finishResult(RESULT_CANCELED,null,0,false);}
    private void finishResult(int result,Uri uri,int flags,boolean reselect){
        if(resultSent||isDestroyed())return;resultSent=true;Intent returned=new Intent(ACTION_RESULT).putExtra(EXTRA_TOKEN,token).putExtra(EXTRA_RESELECT,reselect);
        if(uri!=null){returned.setData(uri);returned.setClipData(ClipData.newRawUri("保存位置",uri));returned.addFlags(flags);}setResult(result,returned);finish();
    }
    @android.annotation.SuppressLint("GestureBackNavigation") @Override public void onBackPressed(){cancel();}
}
