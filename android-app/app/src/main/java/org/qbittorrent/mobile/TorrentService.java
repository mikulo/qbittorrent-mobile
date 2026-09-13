package org.qbittorrent.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.List;

public final class TorrentService extends Service implements TorrentEngine.Listener {
    private static final String CHANNEL = "torrent_transfers";
    private static final int NOTIFICATION_ID = 5300;
    private TorrentEngine engine;

    private final UiRefresh update = new UiRefresh(500, this::updateNotification);

    @Override public void onCreate() {
        super.onCreate();
        AppLog.info("service_create");
        createChannel();
        engine = TorrentEngine.get(this);
        engine.addListener(this);
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.service_idle)));
        engine.startAsync();
        update.start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        engine.startAsync();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        AppLog.info("service_destroy");
        update.stop();
        engine.removeListener(this);
        engine.saveResumeDataAsync();
        super.onDestroy();
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        AppLog.info("service_task_removed");
        engine.saveResumeDataAsync();
        super.onTaskRemoved(rootIntent);
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onEngineChanged() { update.request(); }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL, getString(R.string.foreground_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.foreground_channel_description));
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void updateNotification() {
        List<TorrentSnapshot> snapshots = engine.snapshots();
        int active = 0;
        for (TorrentSnapshot item : snapshots) {
            if (item.group == TorrentSnapshot.Group.DOWNLOADING || item.group == TorrentSnapshot.Group.SEEDING) active++;
        }
        String text = active + " 个活动任务  ·  ↓ " + Formatters.speed(engine.downloadRate()) + "  ↑ " + Formatters.speed(engine.uploadRate());
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_qbittorrent)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(pending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .build();
    }
}
