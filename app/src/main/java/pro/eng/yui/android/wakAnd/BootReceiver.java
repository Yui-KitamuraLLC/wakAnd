package pro.eng.yui.android.wakAnd;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
                "android.intent.action.QUICKBOOT_POWERON".equals(action)) {

            SharedPreferences prefs = context.getSharedPreferences("wakand_prefs", Context.MODE_PRIVATE);
            // MainActivity.KEY_SILENT_NOTIFICATION の値は "silent_notification"
            boolean isEnabled = prefs.getBoolean("silent_notification", true);

            if (isEnabled) {
                Intent serviceIntent = new Intent(context, TrackingService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            }
        }
    }
}
