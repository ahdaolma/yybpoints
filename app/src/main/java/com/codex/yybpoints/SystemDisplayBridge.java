package com.codex.yybpoints;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Runs only in system_server when the user scopes this module to System Framework. */
final class SystemDisplayBridge {
    static final String ACTION_START = "com.codex.yybpoints.START_TRUSTED_DISPLAY";
    static final String ACTION_STOP = "com.codex.yybpoints.STOP_TRUSTED_DISPLAY";
    static final String ACTION_RESULT = "com.codex.yybpoints.TRUSTED_DISPLAY_RESULT";
    private static final String MODULE = BuildConfig.APPLICATION_ID;
    private static final String TARGET = "com.tencent.android.qqdownloader";
    private static final String TAG = "YYBBackground";
    private static final int TRUSTED_FLAG = 1 << 10;
    private static final int DESTROY_ON_REMOVAL_FLAG = 1 << 8;
    private static final int OWN_FOCUS_FLAG = 1 << 14;
    private static final int KEEP_MAIN_FOCUS_FLAG = 1 << 16;
    private static VirtualDisplay display;
    private static ImageReader reader;
    private static HandlerThread drainThread;
    private static Handler cleanup;
    private static final long MAX_DISPLAY_LIFETIME_MS = 45 * 60_000L;
    private static Runnable expiry;
    private static boolean installed;

    private SystemDisplayBridge() { }

    static void install(ClassLoader loader) {
        try {
            Class<?> systemServer = XposedHelpers.findClass("com.android.server.SystemServer", loader);
            XposedBridge.hookAllMethods(systemServer, "startOtherServices", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam hook) {
                    if (installed) return;
                    try {
                        Context context = (Context) XposedHelpers.getObjectField(
                                hook.thisObject, "mSystemContext");
                        if (context == null) return;
                        IntentFilter filter = new IntentFilter();
                        filter.addAction(ACTION_START);
                        filter.addAction(ACTION_STOP);
                        Commands commands = new Commands(context);
                        context.registerReceiver(commands, filter, Context.RECEIVER_EXPORTED);
                        IntentFilter removalFilter = new IntentFilter();
                        removalFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
                        removalFilter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
                        removalFilter.addDataScheme("package");
                        context.registerReceiver(new ModuleRemoval(context, commands),
                                removalFilter, Context.RECEIVER_EXPORTED);
                        installed = true;
                        XposedBridge.log("YYBBackground: trusted display bridge ready");
                    } catch (Throwable error) {
                        XposedBridge.log("YYBBackground: bridge registration failed "
                                + error.getClass().getSimpleName());
                    }
                }
            });
        } catch (Throwable error) {
            XposedBridge.log("YYBBackground: system hook unavailable "
                    + error.getClass().getSimpleName());
        }
    }

    /** Releases system resources when the module package is uninstalled directly. */
    private static final class ModuleRemoval extends BroadcastReceiver {
        private final Context context;
        private final Commands commands;

        ModuleRemoval(Context context, Commands commands) {
            this.context = context;
            this.commands = commands;
        }

        @Override public void onReceive(Context ignored, Intent intent) {
            Uri data = intent.getData();
            if (data == null || !MODULE.equals(data.getSchemeSpecificPart())
                    || intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            Log.i(TAG, "module uninstall detected; releasing hidden display and receivers");
            release();
            try {
                forceStopTarget(context);
            } catch (Throwable error) {
                Log.w(TAG, "target process cleanup failed", error);
            }
            try { context.unregisterReceiver(commands); }
            catch (Throwable error) { Log.w(TAG, "command receiver cleanup failed", error); }
            try { context.unregisterReceiver(this); }
            catch (Throwable error) { Log.w(TAG, "removal receiver cleanup failed", error); }
            installed = false;
        }
    }

    private static final class Commands extends BroadcastReceiver {
        private final Context context;

        Commands(Context context) { this.context = context; }

        @Override public void onReceive(Context ignored, Intent intent) {
            int requestId = intent.getIntExtra("requestId", -1);
            try {
                Log.i(TAG, "system command action=" + intent.getAction()
                        + " senderUid=" + getSentFromUid());
                ApplicationInfo info = context.getPackageManager().getApplicationInfo(MODULE, 0);
                if (getSentFromUid() != info.uid) {
                    Log.w(TAG, "system command rejected; expectedUid=" + info.uid);
                    return;
                }
                if (ACTION_STOP.equals(intent.getAction())) {
                    release();
                    forceStopTarget(context);
                    sendResult(context, "stop", true, -1, requestId,
                            "后台显示已移除，应用宝进程已结束");
                } else if (ACTION_START.equals(intent.getAction())) {
                    int displayId = create(context);
                    try {
                        int taskId = findTargetTask(context);
                        if (taskId > 0) {
                            try {
                                Class<?> manager = XposedHelpers.findClass("android.app.ActivityTaskManager", null);
                                Object service = XposedHelpers.callStaticMethod(manager, "getService");
                                XposedHelpers.callMethod(service, "moveRootTaskToDisplay", taskId, displayId);
                                XposedBridge.log("YYBBackground: moved task=" + taskId
                                        + " display=" + displayId);
                            } catch (Throwable migrationError) {
                                Log.w(TAG, "existing task migration failed; relaunching on hidden display",
                                        migrationError);
                                forceStopTarget(context);
                                launchTargetOnDisplay(context, displayId);
                            }
                        } else {
                            launchTargetOnDisplay(context, displayId);
                        }
                    } catch (Throwable error) {
                        release();
                        throw new IllegalStateException(error);
                    }
                    sendResult(context, "start", true, displayId, requestId,
                            "应用宝已在隐藏显示启动");
                }
            } catch (Throwable error) {
                XposedBridge.log("YYBBackground: command failed " + error.getClass().getSimpleName());
                Log.e(TAG, "system command failed", error);
                boolean stopping = ACTION_STOP.equals(intent.getAction());
                sendResult(context, stopping ? "stop" : "start", false, -1, requestId,
                        (stopping ? "后台停止失败：" : "系统显示创建失败：")
                                + error.getClass().getSimpleName());
            }
        }
    }

    private static void forceStopTarget(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(
                Context.ACTIVITY_SERVICE);
        if (manager == null) throw new IllegalStateException("ActivityManager unavailable");
        XposedHelpers.callMethod(manager, "forceStopPackage", TARGET);
        Log.i(TAG, "force-stopped target package=" + TARGET);
    }

    private static void launchTargetOnDisplay(Context context, int displayId) {
        Intent launch = new Intent(Intent.ACTION_MAIN)
                .setComponent(new ComponentName(TARGET,
                        "com.tencent.assistantv2.activity.MainActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId);
        context.startActivity(launch, options.toBundle());
        XposedBridge.log("YYBBackground: started app on display=" + displayId);
    }

    private static int findTargetTask(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningTaskInfo task : manager.getRunningTasks(100)) {
            if (task.id > 0 && task.baseActivity != null
                    && "com.tencent.android.qqdownloader".equals(
                    task.baseActivity.getPackageName())) return task.id;
        }
        return -1;
    }

    private static int create(Context context) {
        if (display != null && display.getDisplay() != null) {
            return display.getDisplay().getDisplayId();
        }
        release();
        drainThread = new HandlerThread("yybpoints-system-display");
        drainThread.start();
        reader = ImageReader.newInstance(180, 320, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(source -> {
            Image image = null;
            try { image = source.acquireLatestImage(); }
            catch (Throwable ignored) { }
            finally { if (image != null) image.close(); }
        }, new Handler(drainThread.getLooper()));
        DisplayManager manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        display = manager.createVirtualDisplay("YYBPoints-Trusted", 180, 320, 80,
                reader.getSurface(), DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                        | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                        | TRUSTED_FLAG | DESTROY_ON_REMOVAL_FLAG
                        | OWN_FOCUS_FLAG | KEEP_MAIN_FOCUS_FLAG);
        if (display == null || display.getDisplay() == null) {
            release();
            throw new IllegalStateException("display unavailable");
        }
        int displayId = display.getDisplay().getDisplayId();
        if (cleanup == null) cleanup = new Handler(Looper.getMainLooper());
        expiry = () -> {
            if (display == null || display.getDisplay() == null
                    || display.getDisplay().getDisplayId() != displayId) return;
            Log.w(TAG, "hidden display expired; forcing cleanup id=" + displayId);
            release();
            try { forceStopTarget(context); }
            catch (Throwable error) { Log.w(TAG, "expiry process cleanup failed", error); }
        };
        cleanup.postDelayed(expiry, MAX_DISPLAY_LIFETIME_MS);
        XposedBridge.log("YYBBackground: trusted display created id=" + displayId);
        Log.i(TAG, "trusted display created id=" + displayId);
        return displayId;
    }

    private static void sendResult(Context context, String operation, boolean success,
                                   int displayId, int requestId, String message) {
        Intent result = new Intent(ACTION_RESULT).setPackage(MODULE);
        result.putExtra("operation", operation);
        result.putExtra("success", success);
        result.putExtra("displayId", displayId);
        result.putExtra("requestId", requestId);
        result.putExtra("message", message);
        context.sendBroadcast(result, null, BroadcastOptions.makeBasic()
                .setShareIdentityEnabled(true).toBundle());
    }

    private static void release() {
        if (expiry != null && cleanup != null) {
            cleanup.removeCallbacks(expiry);
            expiry = null;
        }
        if (display != null) { display.release(); display = null; }
        if (reader != null) { reader.close(); reader = null; }
        if (drainThread != null) { drainThread.quitSafely(); drainThread = null; }
    }
}
