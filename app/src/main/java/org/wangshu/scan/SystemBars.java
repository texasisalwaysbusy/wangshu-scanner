package org.wangshu.scan;

import android.app.Activity;
import android.os.Build;
import android.view.*;

/** Match icons to each Activity's actual edge-to-edge background. No system settings. */
final class SystemBars {
    static void apply(Activity activity,boolean lightBackground){
        Window window=activity.getWindow();View decor=window.getDecorView();
        if(Build.VERSION.SDK_INT<35){window.setStatusBarColor(activity.getColor(lightBackground?R.color.paper:R.color.capture_background));window.setNavigationBarColor(activity.getColor(lightBackground?R.color.paper:R.color.capture_background));}
        int mask=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if(Build.VERSION.SDK_INT>=26)mask|=View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility((decor.getSystemUiVisibility()&~mask)|(lightBackground?mask:0));
        if(Build.VERSION.SDK_INT>=30)decor.post(()->{WindowInsetsController controller=decor.getWindowInsetsController();if(controller!=null){int appearance=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;controller.setSystemBarsAppearance(lightBackground?appearance:0,appearance);}});
    }
}
