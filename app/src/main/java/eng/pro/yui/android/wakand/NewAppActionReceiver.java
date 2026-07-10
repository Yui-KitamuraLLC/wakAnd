package eng.pro.yui.android.wakand;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.Map;

public class NewAppActionReceiver extends BroadcastReceiver {

    static final String ACTION_LINK = "eng.pro.yui.android.wakand.ACTION_LINK_WAKATIME";
    static final String ACTION_IGNORE = "eng.pro.yui.android.wakand.ACTION_IGNORE_APP";
    static final String EXTRA_PACKAGE_NAME = "extra_package_name";
    static final String EXTRA_NOTIFICATION_ID = "extra_notification_id";

    @Override
    public void onReceive(Context context, Intent intent) {
        String packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME);
        int notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1);

        if (packageName == null) return;

        String action = intent.getAction();
        if (ACTION_LINK.equals(action)) {
            String appLabel = AppUtil.getAppLabel(context.getPackageManager(), packageName);
            AppSettingsManager manager = new AppSettingsManager(context);
            Map<String, AppSettingsManager.AppSetting> all = manager.loadAll();
            if (!all.containsKey(appLabel)) {
                all.put(appLabel, new AppSettingsManager.AppSetting(true, "AndroidUsage", "designing"));
                manager.saveAll(all);
            }
        }
        // ACTION_IGNORE: do nothing, just dismiss

        if (notificationId >= 0) {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            nm.cancel(notificationId);
        }
    }
}
