package com.codex.yybpoints;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.app.BroadcastOptions;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.List;

/** Keeps the module controller alive while the scheduled hidden-display run is active. */
public final class ScheduledBatchService extends Service {
    private static final String TAG = "YYBSchedule";
    private static final String CHANNEL = "yyb_scheduled_batch";
    private static final long RUN_LIMIT_MS = 42 * 60_000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private BackgroundBatchCoordinator coordinator;
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
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                    "应用宝定时任务", NotificationManager.IMPORTANCE_LOW));
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        startForeground(81, builder.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("应用宝后台任务")
                .setContentText("定时观看进行中").setOngoing(true).build());
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
            showPhysicalToast("应用宝定时后台任务已开始");
            main.postDelayed(timeout, RUN_LIMIT_MS);
        }
    }

    private void finish(String message) {
        if (finished) return;
        finished = true;
        main.removeCallbacks(timeout);
        coordinator.removeListener(listener);
        List<RewardHistory.Entry> entries = coordinator.rewards();
        if (entries.isEmpty()) showPhysicalToast("应用宝定时任务结束：" + message);
        else {
            int points = 0;
            for (RewardHistory.Entry entry : entries) if (entry.points > 0) points += entry.points;
            showPhysicalToast("应用宝定时任务完成 " + entries.size() + " 条，已核对 "
                    + points + " 积分");
        }
        Log.i(TAG, "scheduled run finished claims=" + entries.size() + " status=" + message);
        stopSelf();
    }

    private void showPhysicalToast(String message) {
        if (!DailySchedule.read(this).toast) return;
        // The system bridge uses display 0 and still works when Android suppresses
        // background toasts from this app because its notifications are disabled.
        sendBroadcast(new Intent(SystemDisplayBridge.ACTION_TOAST).setPackage("android")
                .putExtra("message", message), null, BroadcastOptions.makeBasic()
                .setShareIdentityEnabled(true).toBundle());
    }

    @Override public void onDestroy() {
        main.removeCallbacks(timeout);
        if (coordinator != null) {
            coordinator.removeListener(listener);
            if (started && !finished && coordinator.isSessionActive()) coordinator.stop();
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
