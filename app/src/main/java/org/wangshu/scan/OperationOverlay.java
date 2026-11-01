package org.wangshu.scan;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

/** Busy state in the Activity's existing window; never creates another HardwareRenderer. */
final class OperationOverlay {
    private final ViewGroup host,background;
    private final FrameLayout overlay;
    private final TextView message;
    private final int accessibilityBefore,focusabilityBefore;
    private final View focusBefore;
    private boolean dismissed;
    OperationOverlay(Activity activity,ViewGroup background,String label,Runnable cancel){
        this.background=background;host=activity.findViewById(android.R.id.content);
        float density=activity.getResources().getDisplayMetrics().density;int pad=Math.round(20*density);
        overlay=new FrameLayout(activity);overlay.setTag("scanner-operation-overlay");
        overlay.setBackgroundColor(0x880B0D10);overlay.setClickable(true);overlay.setFocusableInTouchMode(true);
        overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        if(android.os.Build.VERSION.SDK_INT>=28)overlay.setAccessibilityPaneTitle("正在处理文稿");overlay.setPadding(pad,pad,pad,pad);
        LinearLayout card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(pad,pad,pad,pad);
        GradientDrawable surface=new GradientDrawable();surface.setColor(activity.getColor(R.color.surface));surface.setCornerRadius(16*density);card.setBackground(surface);
        TextView title=new TextView(activity);title.setText("请稍候");title.setTextSize(20);title.setTextColor(activity.getColor(R.color.ink));card.addView(title);
        message=new TextView(activity);message.setTextSize(16);message.setTextColor(activity.getColor(R.color.ink));message.setPadding(0,pad,0,pad);message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);card.addView(message);setMessage(label);
        ProgressBar spinner=new ProgressBar(activity);card.addView(spinner,new LinearLayout.LayoutParams(Math.round(40*density),Math.round(40*density)));
        if(cancel!=null){Button button=new Button(activity);button.setText("取消");button.setMinHeight(Math.round(48*density));button.setTextColor(activity.getColor(R.color.accent));button.setOnClickListener(v->{button.setEnabled(false);cancel.run();});card.addView(button);}
        ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(false);scroll.addView(card,new ScrollView.LayoutParams(-1,-2));overlay.addView(scroll,new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER));
        accessibilityBefore=background.getImportantForAccessibility();focusabilityBefore=background.getDescendantFocusability();focusBefore=activity.getCurrentFocus();
        background.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);background.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        host.addView(overlay,new ViewGroup.LayoutParams(-1,-1));overlay.requestFocus();
    }
    void setMessage(String text){if(!dismissed)message.setText(text);}
    void dismiss(){
        if(dismissed)return;dismissed=true;
        if(overlay.getParent()==host)host.removeView(overlay);
        background.setImportantForAccessibility(accessibilityBefore);background.setDescendantFocusability(focusabilityBefore);
        if(focusBefore!=null&&focusBefore.isAttachedToWindow()&&focusBefore.isShown())focusBefore.requestFocus();
    }
}
