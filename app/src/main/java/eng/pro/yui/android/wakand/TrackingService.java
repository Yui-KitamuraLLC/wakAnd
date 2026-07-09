package eng.pro.yui.android.wakand;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

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

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            String appName = getCurrentAppName();
            String url = ChromeUrlAccessibilityService.getLastUrl();
            String content = appName;
            if (url != null && !url.isEmpty()) {
                content += " | " + url;
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
        String category = isBrowsing ? "browsing" : "designing";
        String project = null;

        if (appSettingsManager.hasAnySettings()) {
            AppSettingsManager.AppSetting setting = appSettingsManager.getSettingFor(appName);
            if (setting == null || !setting.enabled) {
                return;
            }
            project = setting.projectName;
            if (!isBrowsing) {
                category = setting.category;
            }
        }

        wakaTimeClient.sendHeartbeat(entity, category, project);
    }

    private String getCurrentAppName() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                getPackageName());
        if (mode != AppOpsManager.MODE_ALLOWED) {
            return "(no permission)";
        }

        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis();
        List<UsageStats> stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, now - 60_000, now);

        if (stats == null || stats.isEmpty()) {
            return "—";
        }

        SortedMap<Long, UsageStats> sorted = new TreeMap<>();
        for (UsageStats s : stats) {
            sorted.put(s.getLastTimeUsed(), s);
        }
        String packageName = sorted.get(sorted.lastKey()).getPackageName();
        return AppUtil.getAppLabel(getPackageManager(), packageName);
    }
}
