package com.codex.yybpoints;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.util.List;

/** Keeps the module controller awake during manual and scheduled hidden-display runs. */
public final class ScheduledBatchService extends Service {
    static final String ACTION_MANUAL_START = "com.codex.yybpoints.MANUAL_START";
    private static final String TAG = "YYBSchedule";
    private static final String CHANNEL = "yyb_scheduled_batch";
    private static final String RESULT_CHANNEL = "yyb_scheduled_result";
    private static final long RUN_LIMIT_MS = 42 * 60_000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private BackgroundBatchCoordinator coordinator;
    private PowerManager.WakeLock runWakeLock;
    private boolean started;
    private boolean finished;
    private boolean recovering;
    private boolean stopRetried;
    private final BackgroundBatchCoordinator.Listener listener = message -> {
        if (!started || recovering || finished) return;
        if (coordinator.stopFailed()) {
            if (stopRetried) finish("后台停止两次未确认；系统超时保护将继续清理");
            else {
                stopRetried = true;
                coordinator.stop(success -> {
                    if (!success && !finished) finish("后台停止两次未确认；系统超时保护将继续清理");
                });
            }
        } else if (!coordinator.isSessionActive()) finish(message);
    };
    private final Runnable timeout = () -> {
        if (!finished) coordinator.stop(success -> {
            if (!finished) finish(success ? "运行超时，已清理" : "运行超时，停止未确认");
        });
    };

    @Override public void onCreate() {
        super.onCreate();
        coordinator = BackgroundBatchCoordinator.get(this);
        ensureNotificationChannels(this);
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null) {
            runWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "yybpoints:video-run");
            runWakeLock.acquire(RUN_LIMIT_MS + 60_000L);
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        startForeground(81, builder.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("应用宝后台任务")
                .setContentText("视频任务正在运行")
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true).build());
    }

    static void ensureNotificationChannels(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel running = new NotificationChannel(CHANNEL,
                    "应用宝运行状态", NotificationManager.IMPORTANCE_LOW);
            running.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            manager.createNotificationChannel(running);
            manager.createNotificationChannel(new NotificationChannel(RESULT_CHANNEL,
                    "应用宝任务结果", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (started) return START_NOT_STICKY;
        if (coordinator.isSessionActive()) {
            Log.i(TAG, "scheduled run skipped because another run is active");
            stopSelf();
            return START_NOT_STICKY;
        }
        started = true;
        if (coordinator.hasHiddenDisplay()) {
            recovering = true;
            coordinator.stop(success -> {
                recovering = false;
                if (success) startRun();
                else finish("旧后台显示清理失败");
            });
        } else {
            startRun();
        }
        return START_NOT_STICKY;
    }

    private void startRun() {
        if (finished) return;
        coordinator.start();
        coordinator.addListener(listener);
        if (coordinator.isSessionActive()) {
            main.postDelayed(timeout, RUN_LIMIT_MS);
        }
    }

    private void finish(String message) {
        if (finished) return;
        finished = true;
        main.removeCallbacks(timeout);
        coordinator.removeListener(listener);
        List<RewardHistory.Entry> entries = coordinator.rewards();
        String result;
        if (entries.isEmpty()) result = message.startsWith("后台视频已停止")
                ? "本轮 0 条任务，后台资源已清理" : "本轮 0 条：" + message;
        else {
            int points = 0;
            for (RewardHistory.Entry entry : entries) if (entry.points > 0) points += entry.points;
            result = "完成 " + entries.size() + " 条，已核对 " + points + " 积分";
        }
        DailySchedule.recordResult(this, result);
        postResultNotification(result);
        Log.i(TAG, "scheduled run finished claims=" + entries.size() + " status=" + message);
        stopSelf();
    }

    private void postResultNotification(String message) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 24 && !manager.areNotificationsEnabled()) {
            Log.w(TAG, "result notification suppressed: app notifications disabled");
            return;
        }
        PendingIntent open = PendingIntent.getActivity(this, 82,
                new Intent(this, ControlActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, RESULT_CHANNEL) : new Notification.Builder(this);
        manager.notify(82, builder.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("应用宝后台任务已结束")
                .setContentText(message)
                .setContentIntent(open)
                .setAutoCancel(true).build());
        Log.i(TAG, "result notification posted");
    }

    @Override public void onDestroy() {
        main.removeCallbacks(timeout);
        if (coordinator != null) {
            coordinator.removeListener(listener);
            if (started && !finished && coordinator.isSessionActive()) {
                coordinator.stop(success -> releaseWakeLock());
                main.postDelayed(this::releaseWakeLock, 12_000L);
            } else releaseWakeLock();
        } else {
            releaseWakeLock();
        }
        super.onDestroy();
    }

    private void releaseWakeLock() {
        if (runWakeLock != null && runWakeLock.isHeld()) runWakeLock.release();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
