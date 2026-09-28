package com.codex.yybpoints;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.util.Calendar;

/** User-selected local time; each firing registers the following day's alarm. */
public final class DailySchedule extends BroadcastReceiver {
    private static final String TAG = "YYBSchedule";
    private static final String PREFS = "daily_schedule";
    private static final String RUN = "com.codex.yybpoints.DAILY_RUN";
    private static final int REQUEST_CODE = 81;

    static final class Settings {
        final boolean enabled;
        final int hour;
        final int minute;
        final long nextAt;
        final long lastRunAt;
        final String lastResult;

        Settings(SharedPreferences prefs) {
            enabled = prefs.getBoolean("enabled", false);
            hour = prefs.getInt("hour", 9);
            minute = prefs.getInt("minute", 0);
            nextAt = prefs.getLong("next_at", 0L);
            lastRunAt = prefs.getLong("last_run_at", 0L);
            lastResult = prefs.getString("last_result", "");
        }
    }

    static Settings read(Context context) {
        return new Settings(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    static void update(Context context, boolean enabled, int hour, int minute) {
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59)
            throw new IllegalArgumentException("Invalid local time");
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", enabled).putInt("hour", hour)
                .putInt("minute", minute).apply();
        reschedule(context);
    }

    static void recordResult(Context context, String result) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("last_run_at", System.currentTimeMillis())
                .putString("last_result", result).apply();
    }

    static boolean needsExactAlarmPermission(Context context) {
        if (Build.VERSION.SDK_INT < 31) return false;
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return manager == null || !manager.canScheduleExactAlarms();
    }

    static void reschedule(Context context) {
        Settings settings = read(context);
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;
        PendingIntent pending = PendingIntent.getBroadcast(context, REQUEST_CODE,
                new Intent(context, DailySchedule.class).setAction(RUN),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.cancel(pending);
        long nextAt = 0;
        if (settings.enabled) {
            Calendar next = Calendar.getInstance();
            next.set(Calendar.HOUR_OF_DAY, settings.hour);
            next.set(Calendar.MINUTE, settings.minute);
            next.set(Calendar.SECOND, 0);
            next.set(Calendar.MILLISECOND, 0);
            if (next.getTimeInMillis() <= System.currentTimeMillis() + 1_000L)
                next.add(Calendar.DAY_OF_YEAR, 1);
            nextAt = next.getTimeInMillis();
            try {
                if (needsExactAlarmPermission(context)) {
                    nextAt = 0;
                    Log.w(TAG, "daily schedule pending exact alarm permission");
                } else manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextAt, pending);
            } catch (SecurityException denied) {
                nextAt = 0;
                Log.w(TAG, "daily schedule pending exact alarm permission", denied);
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("next_at", nextAt).apply();
        Log.i(TAG, "scheduled enabled=" + settings.enabled + " nextAt=" + nextAt);
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (RUN.equals(intent.getAction())) {
            if (!read(context).enabled) return;
            reschedule(context);
            try {
                Intent service = new Intent(context, ScheduledBatchService.class);
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
                else context.startService(service);
            } catch (Throwable error) {
                Log.e(TAG, "scheduled service start failed", error);
            }
        } else {
            reschedule(context);
        }
    }
}
