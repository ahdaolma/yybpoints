package com.codex.yybpoints;

import android.app.Activity;
import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.media.AudioManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.List;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XposedHelpers;

/** Only accepts control commands from this module's own UID. */
final class ControlBridge {
    static final String ACTION_QUERY = "com.codex.yybpoints.QUERY_TARGET_TASK";
    static final String ACTION_START = "com.codex.yybpoints.START_REAL_VIDEO_BATCH";
    static final String ACTION_STOP = "com.codex.yybpoints.STOP_REAL_VIDEO_BATCH";
    static final String ACTION_STATUS = "com.codex.yybpoints.REAL_VIDEO_BATCH_STATUS";
    private static final String MODULE = BuildConfig.APPLICATION_ID;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> current = new WeakReference<>(null);
    private static WeakReference<Activity> mainActivity = new WeakReference<>(null);
    private static boolean registered;
    private static boolean preparing;
    private static int prepareGeneration;
    private static String lastNavigationState = "尚未尝试";

    private ControlBridge() { }

    static void onActivityResumed(Activity activity) {
        current = new WeakReference<>(activity);
        AccountInfoProbe.publish(activity);
        if (activity.getClass().getName().endsWith(".MainActivity")) {
            mainActivity = new WeakReference<>(activity);
        }
        if (registered) return;
        try {
            Context context = activity.getApplicationContext();
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_QUERY);
            filter.addAction(ACTION_START);
            filter.addAction(ACTION_STOP);
            context.registerReceiver(new Commands(), filter, Context.RECEIVER_EXPORTED);
            registered = true;
            HookEntry.record("background control bridge ready");
        } catch (Throwable error) {
            HookEntry.record("background control bridge failed=" + error.getClass().getSimpleName());
        }
    }

    static void publish(String message) {
        Activity activity = current.get();
        if (activity == null) return;
        Intent status = new Intent(ACTION_STATUS).setPackage(MODULE);
        status.putExtra("message", message);
        sendStatus(activity, status);
    }

    static void publishClaim(int sequence, String taskId, int points) {
        Activity activity = current.get();
        if (activity == null) return;
        Intent status = new Intent(ACTION_STATUS).setPackage(MODULE);
        status.putExtra("event", "claim");
        status.putExtra("sequence", sequence);
        status.putExtra("claimTaskId", taskId);
        status.putExtra("points", points);
        status.putExtra("message", points > 0
                ? "任务 " + sequence + " 已到账 +" + points + " 积分"
                : "任务 " + sequence + " 已领奖，正在核对积分数");
        sendStatus(activity, status);
    }

    static void publishAccount(boolean loggedIn, String nickname, String avatarUrl) {
        Activity activity = current.get();
        if (activity == null) return;
        Intent status = new Intent(ACTION_STATUS).setPackage(MODULE);
        status.putExtra("event", "account");
        status.putExtra("loggedIn", loggedIn);
        status.putExtra("nickname", nickname == null ? "" : nickname);
        status.putExtra("avatarUrl", avatarUrl == null ? "" : avatarUrl);
        sendStatus(activity, status);
    }

    private static void sendStatus(Activity activity, Intent status) {
        status.putExtra("taskId", activity.getTaskId());
        status.putExtra("displayId", activity.getDisplay().getDisplayId());
        status.putExtra("running", VideoBatchController.isRunning());
        status.putExtra("pageReady", DynamicClaimHooks.currentPageModel() != null);
        status.putExtra("completed", VideoBatchController.completed());
        activity.sendBroadcast(status, null, BroadcastOptions.makeBasic()
                .setShareIdentityEnabled(true).toBundle());
    }

    static boolean loginPromptVisible() {
        Activity activity = current.get();
        return activity != null && !activity.isFinishing()
                && (Boolean.FALSE.equals(AccountInfoProbe.loggedIn(activity))
                || containsLoginPrompt(activity.getWindow().getDecorView()));
    }

    private static boolean containsLoginPrompt(View view) {
        if (!view.isShown()) return false;
        if (view instanceof TextView) {
            String text = ((TextView) view).getText().toString();
            if (text.contains("请先登录") || text.contains("立即登录")
                    || text.contains("登录后") || text.contains("登录/注册")) return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsLoginPrompt(group.getChildAt(i))) return true;
            }
        }
        return false;
    }

    private static void startWhenReady(int generation, int attempt) {
        if (!preparing || generation != prepareGeneration) return;
        Activity activity = current.get();
        if (activity == null || activity.isFinishing()
                || activity.getDisplay().getDisplayId() == 0) {
            preparing = false;
            publish("应用宝尚未在隐藏显示运行");
            return;
        }
        Object page = DynamicClaimHooks.currentPageModel();
        if (page != null) {
            AccountInfoProbe.publish(activity);
            preparing = false;
            if (!VideoBatchController.isRunning()) VideoBatchController.toggle(activity, page);
            publish("已通过应用宝接口开始真实视频任务");
            return;
        }
        if (activity.getClass().getName().endsWith(".MainActivity")
                && (attempt == 0 || attempt % 8 == 0)) openEarningsTab();
        if (attempt >= 100) {
            preparing = false;
            publish(loginPromptVisible() ? "应用宝未登录：请先打开应用宝登录账号"
                    : "应用宝赚钱任务页未能加载（" + lastNavigationState + "）");
            return;
        }
        MAIN.postDelayed(() -> startWhenReady(generation, attempt + 1), 750L);
    }

    private static void openEarningsTab() {
        Activity activity = mainActivity.get();
        if (activity == null || activity.isFinishing()) {
            lastNavigationState = "主页尚未就绪";
            return;
        }
        try {
            Object wrapper = XposedHelpers.getObjectField(activity, "E");
            Object tabs = XposedHelpers.getObjectField(wrapper, "i");
            Object widget = XposedHelpers.getObjectField(wrapper, "b");
            if (!(tabs instanceof List) || widget == null) {
                lastNavigationState = "主页标签尚未加载";
                return;
            }
            List<?> list = (List<?>) tabs;
            for (int index = 0; index < list.size(); index++) {
                Object tab = list.get(index);
                String name = (String) XposedHelpers.getObjectField(tab, "name");
                if (name == null || !name.contains("赚钱")) continue;
                Object listener = XposedHelpers.getObjectField(widget, "d");
                XposedHelpers.callMethod(listener, "onTabSelectionChanged", index, true);
                lastNavigationState = "已进入赚钱标签";
                HookEntry.record("background control selected native earnings tab index=" + index);
                return;
            }
            lastNavigationState = "赚钱标签未出现，标签数=" + list.size();
            HookEntry.record("background control earnings tab absent, count=" + list.size());
        } catch (Throwable error) {
            lastNavigationState = "导航失败：" + error.getClass().getSimpleName();
            HookEntry.record("background control earnings navigation failed="
                    + error.getClass().getSimpleName());
        }
    }

    private static final class Commands extends BroadcastReceiver {
        @Override public void onReceive(Context ignored, Intent intent) {
            try {
                int senderUid = getSentFromUid();
                HookEntry.record("background control incoming action=" + intent.getAction()
                        + " senderUid=" + senderUid + " senderPackage=" + getSentFromPackage());
                if (senderUid < 0 || !MODULE.equals(getSentFromPackage())) return;
                HookEntry.record("background control sender verified");
                HookEntry.record("background control received action=" + intent.getAction()
                        + " senderUid=" + senderUid);
                Activity activity = current.get();
                if (activity == null || activity.isFinishing()) return;
                if (ACTION_QUERY.equals(intent.getAction())) {
                    if (BuildConfig.DEBUG && intent.getBooleanExtra("probeAudioFocus", false)) {
                        AudioManager audio = (AudioManager) activity.getSystemService(
                                Context.AUDIO_SERVICE);
                        AudioManager.OnAudioFocusChangeListener listener = change -> { };
                        int result = audio.requestAudioFocus(listener, AudioManager.STREAM_MUSIC,
                                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
                        audio.abandonAudioFocus(listener);
                        HookEntry.record("hidden audio focus probe result=" + result);
                        publish("音频焦点诊断结果=" + result);
                        return;
                    }
                    publish(DynamicClaimHooks.currentPageModel() == null
                            ? "请先打开应用宝赚积分任务页" : "应用宝任务栈已连接");
                } else if (ACTION_START.equals(intent.getAction())) {
                    if (activity.getDisplay().getDisplayId() == 0) {
                        publish("后台应用宝尚未就绪");
                        return;
                    }
                    if (!VideoBatchController.isRunning() && !preparing) {
                        preparing = true;
                        lastNavigationState = "尚未尝试";
                        int generation = ++prepareGeneration;
                        startWhenReady(generation, 0);
                    }
                } else if (ACTION_STOP.equals(intent.getAction())) {
                    preparing = false;
                    prepareGeneration++;
                    VideoBatchController.stopFromController();
                    publish("已停止批量看视频");
                }
            } catch (Throwable error) {
                HookEntry.record("background control command failed="
                        + error.getClass().getSimpleName() + " message="
                        + String.valueOf(error.getMessage()));
                publish("后台命令失败：" + error.getClass().getSimpleName());
            }
        }
    }
}
