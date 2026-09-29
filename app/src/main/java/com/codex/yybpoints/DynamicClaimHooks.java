package com.codex.yybpoints;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import dalvik.system.BaseDexClassLoader;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Uses only request attempts made by the app after its own task completion checks. */
final class DynamicClaimHooks {
    private static final String BUTTON_TAG = "yybpoints.retry.button";
    private static final long RETAIN_MS = 15 * 60_000L;
    private static final long RETRY_COOLDOWN_MS = 30_000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final IdentityHashMap<Object, Attempt> BY_CALLBACK = new IdentityHashMap<>();
    private static final LinkedHashMap<String, Attempt> BY_TASK = new LinkedHashMap<>();
    private static final Set<Class<?>> HOOKED_ENGINES = new HashSet<>();
    private static final Set<Class<?>> HOOKED_RESPONSES = new HashSet<>();
    private static final Set<Class<?>> HOOKED_PAGES = new HashSet<>();
    private static final Set<Class<?>> HOOKED_REWARDS = new HashSet<>();
    private static final Set<Class<?>> HOOKED_VIEWMODEL_RESPONSES = new HashSet<>();
    private static final Set<Class<?>> HOOKED_REWARD_EVENTS = new HashSet<>();
    private static final Set<Class<?>> HOOKED_CONTINUATION_DIALOGS = new HashSet<>();
    private static volatile WeakReference<Activity> currentActivity = new WeakReference<>(null);
    private static volatile WeakReference<Object> pageModel = new WeakReference<>(null);
    private static volatile WeakReference<Object> continueAction = new WeakReference<>(null);
    private static volatile long continueActionCreatedAt;
    private static final Set<Class<?>> HOOKED_CONTINUE_ACTIONS = new HashSet<>();

    private DynamicClaimHooks() { }

    static void install() {
        XposedBridge.hookAllMethods(BaseDexClassLoader.class, "findClass", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam hook) {
                if (hook.hasThrowable() || hook.args.length == 0 || !(hook.args[0] instanceof String)) return;
                String name = (String) hook.args[0];
                Class<?> found = (Class<?>) hook.getResult();
                if (found == null) return;
                if ("g1.b".equals(name)) hookEngine(found);
                else if ("g1.a".equals(name)) hookResponse(found);
                else if ("h0.na".equals(name)) hookPage(found);
                else if ("h0.na$x".equals(name)) hookReward(found);
                else if ("h0.ib".equals(name)) hookViewModelResponse(found);
                else if ("a0.o$a".equals(name)) hookRewardEvent(found);
                else if ("h0.aa".equals(name)) hookContinuationDialog(found);
                else if ("i0.a$i0".equals(name)) hookContinueAction(found);
            }
        });
        HookEntry.record("dynamic claim loader hook ready");
    }

    private static synchronized void hookEngine(Class<?> type) {
        if (!HOOKED_ENGINES.add(type)) return;
        XposedBridge.hookAllMethods(type, "a", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam hook) {
                if (hook.args.length != 15 || !(hook.args[0] instanceof String)
                        || hook.args[14] == null || !(hook.method instanceof Method)) return;
                String taskId = (String) hook.args[0];
                if (taskId.isEmpty()) return;
                Attempt attempt = new Attempt(taskId, hook.thisObject, (Method) hook.method,
                        hook.args.clone(), hook.args[14], System.currentTimeMillis());
                synchronized (BY_CALLBACK) {
                    prune();
                    Attempt prior = BY_TASK.put(taskId, attempt);
                    if (prior != null) BY_CALLBACK.remove(prior.callback);
                    BY_CALLBACK.put(attempt.callback, attempt);
                }
                HookEntry.record("task claim attempted type=" + hook.args[1] + " taskId=" + taskId);
                refreshButton();
            }
        });
        HookEntry.record("dynamic AdsPointTaskCallback request hook ready");
    }

    private static synchronized void hookResponse(Class<?> type) {
        if (!HOOKED_RESPONSES.add(type)) return;
        XposedBridge.hookAllMethods(type, "j", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam hook) {
                if (hook.args.length != 2) return;
                Object callback;
                try { callback = XposedHelpers.getObjectField(hook.thisObject, "f2007b"); }
                catch (Throwable ignored) { return; }
                Attempt attempt;
                int outcome;
                int receivedPoints = -1;
                synchronized (BY_CALLBACK) {
                    attempt = BY_CALLBACK.remove(callback);
                    if (attempt == null) {
                        HookEntry.record("task claim response callback unmatched");
                        return;
                    }
                    if (BY_TASK.get(attempt.taskId) != attempt) return;
                    int outerCode = hook.args[0] instanceof Integer ? (Integer) hook.args[0] : -1;
                    Integer innerCode = null;
                    if (hook.args[1] instanceof List && !((List<?>) hook.args[1]).isEmpty()) {
                        Object response = ((List<?>) hook.args[1]).get(0);
                        if (response != null && "y.l0".equals(response.getClass().getName())) {
                            int parsed = responseCode(response);
                            if (parsed >= 0) innerCode = parsed;
                            if (parsed == 0) {
                                receivedPoints = ClaimPointReader.fromSuccessResponse(response);
                                if (receivedPoints <= 0) HookEntry.record(
                                        "claim amount unavailable shape="
                                                + ClaimPointReader.fieldShape(response));
                            }
                        }
                    }
                    if (outerCode == 0 && innerCode != null && innerCode == 0) {
                        BY_TASK.remove(attempt.taskId);
                        HookEntry.record("task claim confirmed taskId=" + attempt.taskId);
                        outcome = 0;
                    } else if (outerCode != 0 || (innerCode != null && innerCode != 0)) {
                        attempt.failed = true;
                        HookEntry.record("task claim failed taskId=" + attempt.taskId
                                + " outerCode=" + outerCode + " innerCode=" + innerCode);
                        outcome = 1;
                    } else {
                        BY_TASK.remove(attempt.taskId);
                        HookEntry.record("task claim result uncertain taskId=" + attempt.taskId);
                        outcome = 2;
                    }
                }
                VideoBatchController.onClaimResult(attempt.taskId, outcome, receivedPoints);
                refreshButton();
            }
        });
        HookEntry.record("dynamic AdsPointTaskCallback response hook ready");
    }

    private static synchronized void hookPage(Class<?> type) {
        if (!HOOKED_PAGES.add(type)) return;
        XposedBridge.hookAllMethods(type, "E0", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam hook) {
                if (hook.args.length != 1 || hook.args[0] == null) return;
                try {
                    String taskId = (String) XposedHelpers.callMethod(hook.args[0], "j");
                    VideoBatchController.onVideoStartFromApp(hook.thisObject, taskId);
                } catch (Throwable ignored) { }
            }
        });
        XposedBridge.hookAllConstructors(type, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam hook) {
                pageModel = new WeakReference<>(hook.thisObject);
                HookEntry.record("dynamic points page model ready");
                refreshButton();
            }
        });
        XposedBridge.hookAllMethods(type, "F0", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam hook) {
                if (hook.args.length > 1 && hook.args[1] instanceof String
                        && hook.getResult() instanceof Boolean) {
                    VideoBatchController.onShowResult((String) hook.args[1], (Boolean) hook.getResult());
                }
            }
        });
        HookEntry.record("dynamic points page hook ready");
    }

    private static synchronized void hookReward(Class<?> type) {
        if (!HOOKED_REWARDS.add(type)) return;
        XposedBridge.hookAllMethods(type, "b", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam hook) {
                if (hook.hasThrowable()) return;
                // This callback itself is the app's genuine eligibility signal.
                // The obfuscated task field changes between dynamic bundle versions.
                VideoBatchController.onSdkReward();
                try {
                    String taskId = (String) XposedHelpers.getObjectField(hook.thisObject, "f2557b");
                    VideoBatchController.onRealReward(taskId);
                } catch (Throwable error) {
                    HookEntry.record("video reward observation failed=" + error.getClass().getSimpleName());
                }
            }
        });
        HookEntry.record("dynamic video reward callback hook ready");
    }

    private static synchronized void hookViewModelResponse(Class<?> type) {
        if (!HOOKED_VIEWMODEL_RESPONSES.add(type)) return;
        XposedBridge.hookAllMethods(type, "j", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam hook) {
                if (hook.args.length != 2 || !(hook.args[0] instanceof Integer)) return;
                try {
                    String taskId = claimTaskId(hook.thisObject);
                    int code = (Integer) hook.args[0];
                    int inner = responseCode(hook.args[1]);
                    int receivedPoints = code == 0 && inner == 0
                            ? ClaimPointReader.fromSuccessResponse(hook.args[1]) : -1;
                    if (code == 0 && inner == 0 && receivedPoints <= 0)
                        HookEntry.record("view model amount unavailable shape="
                                + ClaimPointReader.fieldShape(hook.args[1]));
                    HookEntry.record("view model claim result taskId=" + taskId
                            + " outerCode=" + code + " innerCode=" + inner
                            + " receivedPoints=" + receivedPoints);
                    if (taskId != null) {
                        VideoBatchController.onClaimResult(taskId,
                                code == 0 && inner == 0 ? 0 : (code != 0 || inner > 0 ? 1 : 2),
                                receivedPoints);
                    }
                } catch (Throwable error) {
                    HookEntry.record("view model claim result probe failed="
                            + error.getClass().getSimpleName());
                }
            }
        });
        HookEntry.record("dynamic video claim result hook ready");
    }

    private static String claimTaskId(Object callback) {
        for (Field field : callback.getClass().getDeclaredFields()) {
            if (field.getType() != String.class
                    || java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            try {
                field.setAccessible(true);
                String value = (String) field.get(callback);
                if (VideoBatchController.isActiveTask(value)) return value;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static int responseCode(Object response) {
        if (response instanceof List) {
            List<?> items = (List<?>) response;
            response = items.isEmpty() ? null : items.get(0);
        }
        if (response == null) return -1;
        try {
            java.lang.reflect.Field field = response.getClass().getDeclaredField("f8147a");
            field.setAccessible(true);
            return field.getInt(response);
        } catch (Throwable ignored) {
            for (java.lang.reflect.Field field : response.getClass().getDeclaredFields()) {
                if (field.getType() != int.class
                        || java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    return field.getInt(response);
                } catch (Throwable ignoredAgain) { }
            }
            return -1;
        }
    }

    private static synchronized void hookRewardEvent(Class<?> type) {
        if (!HOOKED_REWARD_EVENTS.add(type)) return;
        XposedBridge.hookAllMethods(type, "e", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam hook) {
                if (hook.args.length != 1 || hook.args[0] == null) return;
                try {
                    String method = (String) XposedHelpers.callMethod(hook.args[0], "m", "method", "");
                    if (!"onReward".equals(method)) return;
                    VideoBatchController.onSdkReward();
                    Object params = XposedHelpers.getObjectField(hook.thisObject, "f98b");
                    String taskId = (String) XposedHelpers.getObjectField(params, "f77b");
                    VideoBatchController.onRealReward(taskId);
                } catch (Throwable error) {
                    HookEntry.record("reward event observation failed=" + error.getClass().getSimpleName());
                }
            }
        });
        HookEntry.record("dynamic reward event hook ready");
    }

    private static synchronized void hookContinuationDialog(Class<?> type) {
        if (!HOOKED_CONTINUATION_DIALOGS.add(type)) return;
        try {
            hookContinueAction(XposedHelpers.findClass("i0.a$i0", type.getClassLoader()));
        } catch (Throwable error) {
            HookEntry.record("dynamic continuation action hook unavailable="
                    + error.getClass().getSimpleName());
        }
        XposedBridge.hookAllMethods(type, "e", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam hook) {
                if (hook.hasThrowable() || hook.args.length != 2
                        || !"WATCH_AD".equals(String.valueOf(hook.args[1]))) return;
                HookEntry.record("video continuation dialog shown after successful claim");
                VideoBatchController.onContinuationDialog();
            }
        });
        HookEntry.record("dynamic continuation dialog hook ready");
    }

    private static synchronized void hookContinueAction(Class<?> type) {
        if (!HOOKED_CONTINUE_ACTIONS.add(type)) return;
        XposedBridge.hookAllConstructors(type, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam hook) {
                continueAction = new WeakReference<>(hook.thisObject);
                continueActionCreatedAt = SystemClock.uptimeMillis();
                HookEntry.record("video continuation native action captured");
            }
        });
        HookEntry.record("dynamic continuation button action hook ready");
    }

    static boolean activateContinueButton() {
        Object action = continueAction.get();
        if (action != null && SystemClock.uptimeMillis() - continueActionCreatedAt <= 5_000L) {
            try {
                XposedHelpers.callMethod(action, "b");
                continueAction = new WeakReference<>(null);
                HookEntry.record("video continuation native button action invoked");
                return true;
            } catch (Throwable error) {
                HookEntry.record("video continuation native button action failed="
                        + error.getClass().getSimpleName());
            }
        }
        return false;
    }

    private static Object findSingleton(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    || !type.isAssignableFrom(field.getType())) continue;
            try {
                field.setAccessible(true);
                Object value = field.get(null);
                if (value != null) return value;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    static void onActivityResumed(Activity activity) {
        currentActivity = new WeakReference<>(activity);
        VideoBatchController.onPageActivityResumed(activity);
        refreshButton();
    }

    static void refreshUi() { refreshButton(); }

    static Object currentPageModel() { return pageModel.get(); }

    private static void refreshButton() {
        MAIN.post(() -> {
            Activity activity = currentActivity.get();
            if (activity == null || activity.isFinishing() || pageModel.get() == null) return;
            String activityName = activity.getClass().getName();
            if (!activityName.contains("KRCommonActivity") && !activityName.contains("MainActivity")) return;
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            View existing = decor.findViewWithTag(BUTTON_TAG);
            TextView button;
            if (existing instanceof TextView) {
                button = (TextView) existing;
            } else {
                button = new TextView(activity);
                button.setTag(BUTTON_TAG);
                button.setTextColor(Color.WHITE);
                button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                button.setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 8));
                button.setBackgroundColor(0xCC6B3C16);
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.RIGHT | Gravity.BOTTOM);
                params.setMargins(0, 0, dp(activity, 12), dp(activity, 80));
                decor.addView(button, params);
                button.setOnClickListener(v -> retryFailed(activity));
            }
            int count = 0;
            synchronized (BY_CALLBACK) {
                prune();
                for (Attempt attempt : BY_TASK.values()) if (attempt.failed) count++;
            }
            button.setText("补领失败任务 " + count);
            button.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
        });
    }

    private static void retryFailed(Activity activity) {
        List<Attempt> retries = new ArrayList<>();
        long now = System.currentTimeMillis();
        synchronized (BY_CALLBACK) {
            prune();
            for (Attempt attempt : BY_TASK.values()) {
                if (attempt.failed && now - attempt.lastRetryAt >= RETRY_COOLDOWN_MS) {
                    attempt.failed = false;
                    attempt.lastRetryAt = now;
                    retries.add(attempt);
                }
            }
        }
        if (retries.isEmpty()) {
            Toast.makeText(activity, "没有确认失败且可补领的任务", Toast.LENGTH_SHORT).show();
            return;
        }
        for (Attempt attempt : retries) {
            try {
                attempt.method.invoke(attempt.engine, attempt.args.clone());
                HookEntry.record("task claim retry sent taskId=" + attempt.taskId);
            } catch (Throwable error) {
                synchronized (BY_CALLBACK) { attempt.failed = true; }
                HookEntry.record("task claim retry failed locally=" + error.getClass().getSimpleName());
            }
        }
        Toast.makeText(activity, "已重发 " + retries.size() + " 个领奖请求，等待服务器确认", Toast.LENGTH_SHORT).show();
        refreshButton();
    }

    private static void prune() {
        long now = System.currentTimeMillis();
        List<String> expired = new ArrayList<>();
        for (Attempt attempt : BY_TASK.values()) {
            if (now - attempt.createdAt > RETAIN_MS) expired.add(attempt.taskId);
        }
        for (String id : expired) {
            Attempt attempt = BY_TASK.remove(id);
            if (attempt != null) BY_CALLBACK.remove(attempt.callback);
        }
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static final class Attempt {
        final String taskId;
        final Object engine;
        final Method method;
        final Object[] args;
        final Object callback;
        final long createdAt;
        long lastRetryAt;
        boolean failed;

        Attempt(String taskId, Object engine, Method method, Object[] args, Object callback, long createdAt) {
            this.taskId = taskId;
            this.engine = engine;
            this.method = method;
            this.args = args;
            this.callback = callback;
            this.createdAt = createdAt;
        }
    }
}
