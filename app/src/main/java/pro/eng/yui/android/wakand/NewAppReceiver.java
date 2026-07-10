package pro.eng.yui.android.wakand;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class NewAppReceiver extends BroadcastReceiver {

    private static final String CHANNEL_ID = "wakand_new_app";
    private static final int NOTIFICATION_ID_BASE = 10000;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) return;
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
        if (intent.getData() == null) return;

        String packageName = intent.getData().getSchemeSpecificPart();
        if (packageName == null) return;

        String appLabel = AppUtil.getAppLabel(context.getPackageManager(), packageName);

        NotificationManager nm = context.getSystemService(NotificationManager.class);
        createChannelIfNeeded(nm);

        Intent linkIntent = new Intent(context, NewAppActionReceiver.class);
        linkIntent.setAction(NewAppActionReceiver.ACTION_LINK);
        linkIntent.putExtra(NewAppActionReceiver.EXTRA_PACKAGE_NAME, packageName);
        linkIntent.putExtra(NewAppActionReceiver.EXTRA_NOTIFICATION_ID, notificationId(packageName));
        PendingIntent linkPending = PendingIntent.getBroadcast(context,
                packageName.hashCode(), linkIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent ignoreIntent = new Intent(context, NewAppActionReceiver.class);
        ignoreIntent.setAction(NewAppActionReceiver.ACTION_IGNORE);
        ignoreIntent.putExtra(NewAppActionReceiver.EXTRA_PACKAGE_NAME, packageName);
        ignoreIntent.putExtra(NewAppActionReceiver.EXTRA_NOTIFICATION_ID, notificationId(packageName));
        PendingIntent ignorePending = PendingIntent.getBroadcast(context,
                packageName.hashCode() + 1, ignoreIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.new_app_installed))
                .setContentText(appLabel)
                .setSmallIcon(android.R.drawable.ic_menu_add)
                .setAutoCancel(true)
                .addAction(new Notification.Action.Builder(
                        null,
                        context.getString(R.string.action_link_wakatime),
                        linkPending).build())
                .addAction(new Notification.Action.Builder(
                        null,
                        context.getString(R.string.action_ignore),
                        ignorePending).build())
                .build();

        nm.notify(notificationId(packageName), notification);
    }

    private void createChannelIfNeeded(NotificationManager nm) {
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "New App Installed",
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Notifies when a new app is installed");
        nm.createNotificationChannel(channel);
    }

    private int notificationId(String packageName) {
        return NOTIFICATION_ID_BASE + (packageName.hashCode() & 0x7FFFFFFF) % 10000;
    }
}
