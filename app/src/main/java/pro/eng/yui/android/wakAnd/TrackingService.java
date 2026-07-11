package pro.eng.yui.android.wakAnd;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import android.net.Uri;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

public class TrackingService extends Service {

    private static final String CHANNEL_ID = "wakand_tracking";
    private static final int NOTIFICATION_ID = 1;
    private static final long POLL_INTERVAL_MS = 3000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private NotificationManager notificationManager;
    private WakaTimeClient wakaTimeClient;
    private AppSettingsManager appSettingsManager;
    private String lastNotificationText = "";

    private static final String CHROME_PACKAGE = "com.android.chrome";

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            String packageName = getCurrentPackageName();
            String appName = AppUtil.getAppLabel(getPackageManager(), packageName);
            boolean isBrowser = CHROME_PACKAGE.equals(packageName);
            String url = isBrowser ? ChromeUrlAccessibilityService.getLastUrl() : null;
            String content;
            if (isTrackedApp(appName)) {
                if (isBrowser && url != null && !url.isEmpty()) {
                    String host = Uri.parse(url).getHost();
                    content = appName + "(" + (host != null ? host : url) + ")";
                } else {
                    content = appName;
                }
            } else {
                content = getString(R.string.label_not_tracked);
            }
            if (!content.equals(lastNotificationText) || !isNotificationActive()) {
                updateNotification(content);
                lastNotificationText = content;
            }
            sendHeartbeat(appName, url);
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = getSystemService(NotificationManager.class);
        wakaTimeClient = new WakaTimeClient(this);
        appSettingsManager = new AppSettingsManager(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences prefs = getSharedPreferences("wakand_prefs", MODE_PRIVATE);
        if (!prefs.getBoolean(MainActivity.KEY_SILENT_NOTIFICATION, true)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification notification = buildNotification("Tracking...");
        startForeground(NOTIFICATION_ID, notification);
        handler.post(pollRunnable);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(pollRunnable);
        if (wakaTimeClient != null) {
            wakaTimeClient.shutdown();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Tracking", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows the currently active app");
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("wakAnd")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        startForeground(NOTIFICATION_ID, buildNotification(text));
    }

    private boolean isNotificationActive() {
        for (android.service.notification.StatusBarNotification sbn : notificationManager.getActiveNotifications()) {
            if (sbn.getId() == NOTIFICATION_ID) {
                return true;
            }
        }
        return false;
    }

    private void sendHeartbeat(String appName, String url) {
        if (wakaTimeClient == null) return;

        boolean isBrowsing = url != null && !url.isEmpty();
        String entity = isBrowsing ? url : appName;
        String project = null;

        if (appSettingsManager.hasAnySettings()) {
            AppSettingsManager.AppSetting setting = appSettingsManager.getSettingFor(appName);
            if (setting == null || !setting.enabled) {
                return;
            }
            project = setting.projectName;
        }

        wakaTimeClient.sendHeartbeat(entity, project, isBrowsing);
    }

    private boolean isTrackedApp(String appName) {
        if (!appSettingsManager.hasAnySettings()) {
            return true;
        }
        AppSettingsManager.AppSetting setting = appSettingsManager.getSettingFor(appName);
        return setting != null && setting.enabled;
    }

    private String getCurrentPackageName() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                getPackageName());
        if (mode != AppOpsManager.MODE_ALLOWED) {
            return "";
        }

        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis();
        List<UsageStats> stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, now - 60_000, now);

        if (stats == null || stats.isEmpty()) {
            return "";
        }

        SortedMap<Long, UsageStats> sorted = new TreeMap<>();
        for (UsageStats s : stats) {
            sorted.put(s.getLastTimeUsed(), s);
        }
        return sorted.get(sorted.lastKey()).getPackageName();
    }
}
