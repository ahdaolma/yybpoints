package com.codex.yybpoints;

import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;

/** One start/stop path for the physical controller and the headless ADB probe. */
final class BackgroundBatchCoordinator {
    interface Listener { void onStatus(String message); }
    interface StopCallback { void onComplete(boolean success); }

    private static final String TAG = "YYBBackground";
    private static final String TARGET = "com.tencent.android.qqdownloader";
    private static BackgroundBatchCoordinator instance;

    static synchronized BackgroundBatchCoordinator get(Context context) {
        if (instance == null) instance = new BackgroundBatchCoordinator(
                context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private String status = "准备就绪，点击下方按钮开始观看任务";
    private boolean sessionActive;
    private boolean waiting;
    private boolean stopping;
    private boolean stopFailed;
    private final CopyOnWriteArrayList<StopCallback> stopCallbacks =
            new CopyOnWriteArrayList<>();
    private int generation;

    private final BroadcastReceiver resultReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            String action = intent.getAction();
            int senderUid = getSentFromUid();
            String senderPackage = getSentFromPackage();
            Log.i(TAG, "result action=" + action + " senderUid=" + senderUid
                    + " senderPackage=" + senderPackage);
            if (ControlBridge.ACTION_STATUS.equals(action)) {
                if (senderUid < 0 || !TARGET.equals(senderPackage)) return;
                if ("claim".equals(intent.getStringExtra("event"))) {
                    new RewardHistory(context).record(intent.getIntExtra("sequence", -1),
                            intent.getStringExtra("claimTaskId"),
                            intent.getIntExtra("points", -1));
                } else if ("account".equals(intent.getStringExtra("event"))) {
                    new AccountProfileStore(context).update(
                            intent.getBooleanExtra("loggedIn", false),
                            intent.getStringExtra("nickname"),
                            intent.getStringExtra("avatarUrl"));
                }
                String message = intent.getStringExtra("message");
                if (message != null && !stopping) {
                    report(message);
                    if (message.startsWith("批量任务停止：")) {
                        stop(success -> {
                            if (success) report(message + "；后台显示和应用宝进程已清理");
                        });
                    }
                }
                else notifyListeners(status);
                return;
            }
            if (!SystemDisplayBridge.ACTION_RESULT.equals(action)
                    || senderUid != 1000) return;
            if (intent.getIntExtra("requestId", -1) != generation) {
                Log.i(TAG, "ignored stale display result operation="
                        + intent.getStringExtra("operation"));
                return;
            }
            String operation = intent.getStringExtra("operation");
            if ("stop".equals(operation) && stopping) {
                if (!intent.getBooleanExtra("success", false)) {
                    finishStop(false, intent.getStringExtra("message"));
                } else {
                    pollStopped(generation, 0);
                }
                return;
            }
            if (!"start".equals(operation) || !waiting) return;
            int displayId = intent.getIntExtra("displayId", -1);
            if (displayId < 0) {
                String message = intent.getStringExtra("message");
                stop(success -> report(message == null ? "后台显示未启动" : message));
            } else {
                startBatchOnDisplay(displayId);
            }
        }
    };

    private BackgroundBatchCoordinator(Context context) {
        this.context = context;
        IntentFilter filter = new IntentFilter();
        filter.addAction(SystemDisplayBridge.ACTION_RESULT);
        filter.addAction(ControlBridge.ACTION_STATUS);
        context.registerReceiver(resultReceiver, filter, Context.RECEIVER_EXPORTED);
    }

    void addListener(Listener value) {
        listeners.addIfAbsent(value);
        value.onStatus(status);
    }

    void removeListener(Listener value) { listeners.remove(value); }

    List<RewardHistory.Entry> rewards() { return new RewardHistory(context).snapshot(); }

    boolean rewardsFinished() { return new RewardHistory(context).isFinished(); }

    long rewardsStartedAt() { return new RewardHistory(context).startedAt(); }

    long rewardsFinishedAt() { return new RewardHistory(context).finishedAt(); }

    AccountProfileStore.Profile account() { return new AccountProfileStore(context).read(); }

    boolean isSessionActive() { return sessionActive || waiting || stopping; }

    boolean stopFailed() { return stopFailed; }

    boolean hasHiddenDisplay() {
        DisplayManager manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (manager == null) return false;
        for (Display display : manager.getDisplays()) {
            if ("YYBPoints-Trusted".equals(display.getName())) return true;
        }
        return false;
    }

    void start() {
        if (isSessionActive()) return;
        DisplayManager manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (manager != null) for (Display display : manager.getDisplays()) {
            if ("YYBPoints-Trusted".equals(display.getName())) {
                report("已有后台显示；请先停止并还原，再开始新一轮");
                return;
            }
        }
        generation++;
        stopFailed = false;
        sessionActive = true;
        waiting = true;
        new RewardHistory(context).reset();
        report("正在创建后台显示并迁移应用宝任务栈…");
        sendVerifiedBroadcast(new Intent(SystemDisplayBridge.ACTION_START)
                .setPackage("android").putExtra("requestId", generation));
        int token = generation;
        main.postDelayed(() -> pollHiddenDisplay(token, 0), 500L);
        main.postDelayed(() -> {
            if (waiting && token == generation) {
                stop(success -> report("未收到系统框架响应；检查 LSPosed 系统框架作用域"));
            }
        }, 8_000L);
    }

    void stop() {
        stop(null);
    }

    void stop(StopCallback callback) {
        if (stopping) {
            if (callback != null) stopCallbacks.add(callback);
            return;
        }
        if (callback != null) stopCallbacks.add(callback);
        generation++;
        waiting = false;
        stopping = true;
        stopFailed = false;
        int token = generation;
        report("正在停止视频、移除后台显示并结束应用宝进程…");
        sendVerifiedBroadcast(new Intent(ControlBridge.ACTION_STOP).setPackage(TARGET));
        main.postDelayed(() -> {
            if (stopping && token == generation) sendVerifiedBroadcast(
                    new Intent(SystemDisplayBridge.ACTION_STOP).setPackage("android")
                            .putExtra("requestId", token));
        }, 500L);
        main.postDelayed(() -> {
            if (stopping && token == generation) finishStop(false,
                    "未收到系统停止确认；请检查 LSPosed 系统框架作用域");
        }, 10_000L);
    }

    private void pollStopped(int token, int attempt) {
        if (!stopping || token != generation) return;
        DisplayManager manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        boolean found = false;
        if (manager != null) for (Display item : manager.getDisplays()) {
            if ("YYBPoints-Trusted".equals(item.getName())) { found = true; break; }
        }
        if (!found && manager != null) {
            finishStop(true, "后台视频已停止；隐藏显示和应用宝进程已清理");
        } else if (attempt < 20) {
            main.postDelayed(() -> pollStopped(token, attempt + 1), 250L);
        } else {
            finishStop(false, "系统已响应停止，但隐藏显示仍在；请重试");
        }
    }

    private void finishStop(boolean success, String message) {
        if (!stopping) return;
        stopping = false;
        stopFailed = !success;
        if (success) sessionActive = false;
        new RewardHistory(context).finish();
        report(message == null ? "后台停止未确认" : message);
        List<StopCallback> completedCallbacks = new ArrayList<>(stopCallbacks);
        stopCallbacks.clear();
        for (StopCallback callback : completedCallbacks) callback.onComplete(success);
    }

    private void pollHiddenDisplay(int token, int attempt) {
        if (!waiting || token != generation) return;
        DisplayManager manager = (DisplayManager) context.getSystemService(
                Context.DISPLAY_SERVICE);
        if (manager != null) {
            for (Display display : manager.getDisplays()) {
                if ("YYBPoints-Trusted".equals(display.getName())) {
                    Log.i(TAG, "controller found hidden display=" + display.getDisplayId());
                    startBatchOnDisplay(display.getDisplayId());
                    return;
                }
            }
        }
        if (attempt < 14) main.postDelayed(() -> pollHiddenDisplay(token, attempt + 1), 500L);
    }

    private void startBatchOnDisplay(int displayId) {
        if (!waiting) return;
        waiting = false;
        report("应用宝已移到无画面屏幕 " + displayId + "，正在开始任务");
        int token = generation;
        for (int delay : new int[]{1_000, 3_000, 6_000}) {
            main.postDelayed(() -> {
                if (token == generation) sendVerifiedBroadcast(
                        new Intent(ControlBridge.ACTION_START).setPackage(TARGET));
            }, delay);
        }
    }

    private void sendVerifiedBroadcast(Intent intent) {
        context.sendBroadcast(intent, null, BroadcastOptions.makeBasic()
                .setShareIdentityEnabled(true).toBundle());
    }

    private void report(String message) {
        status = message;
        Log.i(TAG, "controller status=" + message);
        notifyListeners(message);
    }

    private void notifyListeners(String message) {
        for (Listener listener : listeners) listener.onStatus(message);
    }
}
