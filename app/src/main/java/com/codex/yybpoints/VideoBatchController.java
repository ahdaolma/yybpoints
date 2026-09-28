package com.codex.yybpoints;

import android.app.Activity;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.XposedHelpers;

/** Starts one real rewarded video at a time through the app's own ad API. */
final class VideoBatchController {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<String> STARTED = new HashSet<>();
    private static final Pattern AWARD_TEXT = Pattern.compile("(\\d{1,5})\\s*积分已成功发放");
    private static WeakReference<Object> model = new WeakReference<>(null);
    private static WeakReference<Activity> adActivity = new WeakReference<>(null);
    private static WeakReference<Activity> pageActivity = new WeakReference<>(null);
    private static boolean running;
    private static String activeTaskId;
    private static int completed;
    private static long generation;
    private static boolean rewardReceived;
    private static int claimSequence;
    private static long continuationScheduledGeneration = -1;
    private static boolean waitingForNativeContinuation;
    private static String lastClaimedTaskId;
    private static int lastClaimedPoints;
    private static int nextSeries = 1;
    private static int emptyProbeCount;

    private VideoBatchController() { }

    static void toggle(Activity activity, Object pageModel) {
        if (running) return;
        model = new WeakReference<>(pageModel);
        pageActivity = new WeakReference<>(activity);
        claimSequence = 0;
        STARTED.clear();
        completed = 0;
        activeTaskId = null;
        rewardReceived = false;
        waitingForNativeContinuation = false;
        lastClaimedTaskId = null;
        lastClaimedPoints = -1;
        nextSeries = 1;
        emptyProbeCount = 0;
        running = true;
        generation++;
        HookEntry.record("video batch started");
        next(activity);
    }

    static boolean isRunning() { return running; }

    static int completed() { return completed; }

    static void stopFromController() { stop("控制界面停止"); }

    static boolean isActiveTask(String taskId) {
        return running && taskId != null && taskId.equals(activeTaskId);
    }

    static void onClaimResult(String taskId, int outcome) {
        onClaimResult(taskId, outcome, -1);
    }

    static void onClaimResult(String taskId, int outcome, int receivedPoints) {
        if (!running) return;
        if (activeTaskId == null || !activeTaskId.equals(taskId)) {
            if (outcome == 0 && receivedPoints > 0 && taskId != null
                    && taskId.equals(lastClaimedTaskId)
                    && receivedPoints != lastClaimedPoints) {
                lastClaimedPoints = receivedPoints;
                ControlBridge.publishClaim(claimSequence, taskId, receivedPoints);
            }
            return;
        }
        if (outcome != 0) {
            stop("领奖失败或结果不确定");
            return;
        }
        completed++;
        int sequence = ++claimSequence;
        activeTaskId = null;
        lastClaimedTaskId = taskId;
        lastClaimedPoints = receivedPoints > 0 ? receivedPoints : -1;
        ControlBridge.publishClaim(sequence, taskId, lastClaimedPoints);
        if (lastClaimedPoints <= 0) schedulePointScan(sequence, taskId, 0);
        boolean hasContinuation = hasNativeContinuation();
        waitingForNativeContinuation = hasContinuation;
        HookEntry.record("video batch claim confirmed taskId=" + taskId
                + " nativeContinuation=" + hasContinuation);
        ControlBridge.publish("真实任务已领奖，累计 " + completed + " 条");
        long token = generation;
        if (hasContinuation) {
            MAIN.postDelayed(() -> {
                if (running && generation == token && waitingForNativeContinuation) {
                    stop("领奖后未检测到原生续播");
                }
            }, 15_000L);
        } else {
            MAIN.postDelayed(() -> {
                if (!running || generation != token || activeTaskId != null
                        || waitingForNativeContinuation) return;
                if (hasNativeContinuation()) {
                    waitingForNativeContinuation = true;
                    HookEntry.record("video batch next deferred: continuation appeared after claim");
                    MAIN.postDelayed(() -> {
                        if (running && generation == token && waitingForNativeContinuation) {
                            stop("领奖后未检测到原生续播");
                        }
                    }, 15_000L);
                    return;
                }
                if (adActivity.get() != null) {
                    stop("上一条广告尚未退出");
                    return;
                }
                next(pageActivity.get());
            }, 6_000L);
        }
        DynamicClaimHooks.refreshUi();
    }

    private static boolean hasNativeContinuation() {
        Object page = model.get();
        if (page == null) return true;
        try {
            return Boolean.TRUE.equals(XposedHelpers.callMethod(page, "B"));
        } catch (Throwable error) {
            HookEntry.record("video batch continuation state unknown="
                    + error.getClass().getSimpleName());
            return true;
        }
    }

    static void onVideoStartFromApp(Object pageModel, String taskId) {
        if (taskId == null || taskId.isEmpty()) return;
        if (!running) return;
        if (taskId.equals(activeTaskId) || activeTaskId != null) return;
        if (adActivity.get() != null && !waitingForNativeContinuation) {
            HookEntry.record("video batch ignored start while prior ad is active taskId=" + taskId);
            return;
        }
        followNativeVideo(taskId);
    }

    static void onShowResult(String taskId, boolean started) {
        if (running && !started && taskId != null && taskId.equals(activeTaskId)) {
            stop("广告未能启动");
        }
    }

    static void onAdActivityResumed(Activity activity) {
        adActivity = new WeakReference<>(activity);
        HookEntry.record("video batch ad activity resumed=" + activity.getClass().getName());
        if (running && waitingForNativeContinuation && activeTaskId == null
                && lastClaimedTaskId != null) {
            followNativeVideo(lastClaimedTaskId);
        }
    }

    static void onAdActivityPaused(Activity activity) {
        HookEntry.record("video batch ad activity paused display="
                + activity.getDisplay().getDisplayId());
    }

    static void onAdActivityDestroyed(Activity activity) {
        if (adActivity.get() == activity) adActivity = new WeakReference<>(null);
    }

    static void onPageActivityResumed(Activity activity) {
        pageActivity = new WeakReference<>(activity);
    }

    private static void followNativeVideo(String taskId) {
        STARTED.add(taskId);
        activeTaskId = taskId;
        waitingForNativeContinuation = false;
        rewardReceived = false;
        generation++;
        long token = generation;
        HookEntry.record("video batch follows native continuation taskId=" + taskId);
        MAIN.postDelayed(() -> {
            if (running && generation == token && taskId.equals(activeTaskId)) {
                stop("原生续播等待超时");
            }
        }, 180_000L);
        DynamicClaimHooks.refreshUi();
    }

    static void onSdkReward() {
        if (activeTaskId != null) onRealReward(activeTaskId);
    }

    static void onRealReward(String taskId) {
        if (!running || activeTaskId == null || !activeTaskId.equals(taskId)
                || rewardReceived) return;
        rewardReceived = true;
        HookEntry.record("video batch real reward received taskId=" + taskId);
        long token = generation;
        MAIN.postDelayed(() -> tapRewardedClose(token, taskId, 0), 500L);
    }

    private static void tapRewardedClose(long token, String taskId, int attempt) {
        Activity activity = adActivity.get();
        if (!running || generation != token || !taskId.equals(activeTaskId)
                || activity == null || activity.isFinishing()) return;
        if (attempt == 0) {
            try {
                // PluginProxyActivity forwards this to the ad plugin's own
                // onPluginBackPressed path, independently of screen position.
                activity.onBackPressed();
                HookEntry.record("video batch invoked native ad back taskId=" + taskId);
                MAIN.postDelayed(() -> tapRewardedClose(token, taskId, 1), 800L);
                return;
            } catch (Throwable error) {
                HookEntry.record("video batch native ad back failed="
                        + error.getClass().getSimpleName());
            }
        }
        if (attempt == 1) {
            try {
                Object plugin = XposedHelpers.callMethod(activity, "getPluginActivity");
                if (plugin != null) {
                    XposedHelpers.callMethod(plugin, "finish");
                    HookEntry.record("video batch invoked ad plugin finish taskId=" + taskId
                            + " plugin=" + plugin.getClass().getName());
                    MAIN.postDelayed(() -> tapRewardedClose(token, taskId, 2), 800L);
                    return;
                }
            } catch (Throwable error) {
                HookEntry.record("video batch ad plugin finish failed="
                        + error.getClass().getSimpleName());
            }
        }
        HookEntry.record("video batch tapping rewarded ad close taskId=" + taskId
                + " attempt=" + attempt);
        if (!tapTopWindow(activity, 0.075f, 0.09f)) {
            if (attempt < 12) {
                MAIN.postDelayed(() -> tapRewardedClose(token, taskId, attempt + 1), 250L);
            } else {
                stop("奖励已达标但关闭按钮未响应");
            }
            return;
        }
        MAIN.postDelayed(() -> {
            if (!running || generation != token || adActivity.get() != activity
                    || activity.isFinishing()) return;
            if (!clickCompactCloseView(activity)) {
                stop("奖励已达标但未找到可点击的关闭控件");
            }
        }, 1_500L);
    }

    static void onContinuationDialog() {
        if (!running || continuationScheduledGeneration == generation) return;
        continuationScheduledGeneration = generation;
        if (activeTaskId != null) {
            // The app only opens this dialog after its own successful claim response.
            onClaimResult(activeTaskId, 0);
        }
        if (lastClaimedTaskId != null && lastClaimedPoints <= 0) {
            schedulePointScan(claimSequence, lastClaimedTaskId, 0);
        }
        // The dialog itself is authoritative even if the low-level claim callback
        // observed a stale value of B() before the page processed its response.
        waitingForNativeContinuation = true;
        long token = generation;
        MAIN.postDelayed(() -> tapContinuation(token, 0), 300L);
    }

    private static void tapContinuation(long token, int attempt) {
        if (!running || generation != token || !waitingForNativeContinuation
                || activeTaskId != null || adActivity.get() != null) return;
        if (DynamicClaimHooks.activateContinueButton()) return;
        Activity activity = pageActivity.get();
        if (activity != null && !activity.isFinishing()) {
            HookEntry.record("video batch activating confirmed continue dialog attempt=" + attempt);
            if (!clickContinueView(activity)) tapTopWindow(activity, 0.5f, 0.60f);
        }
        if (attempt < 1) MAIN.postDelayed(() -> tapContinuation(token, attempt + 1), 3_000L);
        else HookEntry.record("video batch awaiting native continuation or countdown");
    }

    private static boolean clickContinueView(Activity activity) {
        try {
            Class<?> managerType = Class.forName("android.view.WindowManagerGlobal");
            Object manager = XposedHelpers.callStaticMethod(managerType, "getInstance");
            Object roots = XposedHelpers.getObjectField(manager, "mViews");
            if (!(roots instanceof List)) return false;
            List<?> views = (List<?>) roots;
            for (int i = views.size() - 1; i >= 0; i--) {
                if (!(views.get(i) instanceof View)) continue;
                View target = findContinueView((View) views.get(i));
                if (target == null) continue;
                HookEntry.record("video batch native continue view=" + target.getClass().getName());
                return target.performClick();
            }
        } catch (Throwable error) {
            HookEntry.record("video batch continue view lookup failed="
                    + error.getClass().getSimpleName());
        }
        return false;
    }

    private static View findContinueView(View view) {
        if (!view.isShown()) return null;
        if (view instanceof TextView && ((TextView) view).getText().toString().contains("再看一条")) {
            for (View target = view; target != null; target = target.getParent() instanceof View
                    ? (View) target.getParent() : null) {
                if (target.isClickable()) return target;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View target = findContinueView(group.getChildAt(i));
                if (target != null) return target;
            }
        }
        return null;
    }

    private static boolean tap(Activity activity, float xFraction, float yFraction) {
        View decor = activity.getWindow().getDecorView();
        int width = decor.getWidth();
        int height = decor.getHeight();
        if (width <= 0 || height <= 0 || !activity.hasWindowFocus()) {
            HookEntry.record("video batch tap unavailable: activity not focused");
            return false;
        }
        float x = width * xFraction;
        float y = height * yFraction;
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x, y, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            boolean pressed = activity.dispatchTouchEvent(down);
            boolean released = activity.dispatchTouchEvent(up);
            HookEntry.record("video batch tap handled=" + (pressed && released)
                    + " activity=" + activity.getClass().getSimpleName());
            return pressed && released;
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    private static boolean tapTopWindow(Activity activity, float xFraction, float yFraction) {
        Point target = windowTarget(activity, xFraction, yFraction);
        try {
            Class<?> managerType = Class.forName("android.view.WindowManagerGlobal");
            Object manager = XposedHelpers.callStaticMethod(managerType, "getInstance");
            Object roots = XposedHelpers.getObjectField(manager, "mViews");
            if (roots instanceof List) {
                List<?> views = (List<?>) roots;
                for (int i = views.size() - 1; i >= 0; i--) {
                    if (!(views.get(i) instanceof View)) continue;
                    View root = (View) views.get(i);
                    int[] location = new int[2];
                    root.getLocationOnScreen(location);
                    if (!root.isShown() || target.x < location[0] || target.y < location[1]
                            || target.x >= location[0] + root.getWidth()
                            || target.y >= location[1] + root.getHeight()) continue;
                    HookEntry.record("video batch close window=" + root.getClass().getSimpleName()
                            + " roots=" + views.size() + " size="
                            + root.getWidth() + "x" + root.getHeight());
                    return dispatchTap(root, target.x - location[0], target.y - location[1]);
                }
            }
        } catch (Throwable error) {
            HookEntry.record("video batch top window lookup failed="
                    + error.getClass().getSimpleName());
        }
        return tap(activity, xFraction, yFraction);
    }

    private static Point windowTarget(Activity activity, float xFraction, float yFraction) {
        Point display = new Point();
        activity.getWindowManager().getDefaultDisplay().getRealSize(display);
        View decor = activity.getWindow().getDecorView();
        int width = decor.getWidth();
        int height = decor.getHeight();
        if (width > 0 && height > 0
                && (width < display.x * 0.8f || height < display.y * 0.6f)) {
            int[] location = new int[2];
            decor.getLocationOnScreen(location);
            // The floating window removes the fullscreen top bar around the X.
            float localX = xFraction < 0.2f ? 0.09f : xFraction;
            float localY = yFraction < 0.2f ? 0.05f : yFraction;
            HookEntry.record("video batch floating window=" + width + "x" + height
                    + " at=" + location[0] + "," + location[1]);
            return new Point(location[0] + Math.round(width * localX),
                    location[1] + Math.round(height * localY));
        }
        return new Point(Math.round(display.x * xFraction),
                Math.round(display.y * yFraction));
    }

    private static boolean clickCompactCloseView(Activity activity) {
        Point size = new Point();
        activity.getWindowManager().getDefaultDisplay().getRealSize(size);
        Point target = windowTarget(activity, 0.075f, 0.09f);
        int x = target.x;
        int y = target.y;
        View candidate = null;
        try {
            Class<?> managerType = Class.forName("android.view.WindowManagerGlobal");
            Object manager = XposedHelpers.callStaticMethod(managerType, "getInstance");
            Object roots = XposedHelpers.getObjectField(manager, "mViews");
            if (roots instanceof List) {
                for (Object root : (List<?>) roots) {
                    if (root instanceof View) {
                        candidate = findCompactClickTarget((View) root, x, y,
                                size.x, size.y, candidate);
                    }
                }
            }
        } catch (Throwable error) {
            HookEntry.record("video batch close view lookup failed="
                    + error.getClass().getSimpleName());
        }
        if (candidate == null) {
            candidate = findCompactClickTarget(activity.getWindow().getDecorView(), x, y,
                    size.x, size.y, null);
        }
        if (candidate == null) return false;
        HookEntry.record("video batch close view=" + candidate.getClass().getName());
        return candidate.performClick();
    }

    private static View findCompactClickTarget(View view, int x, int y, int screenWidth,
                                               int screenHeight, View current) {
        if (!view.isShown()) return current;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                current = findCompactClickTarget(group.getChildAt(i), x, y,
                        screenWidth, screenHeight, current);
            }
        }
        Rect bounds = new Rect();
        if (view.isClickable() && view.getGlobalVisibleRect(bounds)
                && bounds.contains(x, y) && bounds.width() < screenWidth / 3
                && bounds.height() < screenHeight / 5
                && (current == null || bounds.width() * bounds.height()
                < current.getWidth() * current.getHeight())) return view;
        return current;
    }

    private static boolean dispatchTap(View root, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x, y, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            boolean pressed = root.dispatchTouchEvent(down);
            boolean released = root.dispatchTouchEvent(up);
            HookEntry.record("video batch top window tap handled=" + (pressed && released));
            return pressed && released;
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    private static void next(Activity activity) {
        if (!running) return;
        Object page = DynamicClaimHooks.currentPageModel();
        if (page == null) page = model.get();
        else model = new WeakReference<>(page);
        if (page == null) {
            if (++emptyProbeCount <= 15) {
                long token = generation;
                HookEntry.record("video batch waiting for page model attempt=" + emptyProbeCount);
                MAIN.postDelayed(() -> {
                    if (running && generation == token && activeTaskId == null) next(activity);
                }, 2_000L);
            } else {
                stop(ControlBridge.loginPromptVisible()
                        ? "应用宝未登录：请先打开应用宝登录账号" : "任务页未能加载");
            }
            return;
        }
        if (activeTaskId != null || waitingForNativeContinuation || adActivity.get() != null) {
            HookEntry.record("video batch next deferred: previous ad or continuation active");
            return;
        }
        if (completed >= 100) {
            stop("达到单次上限");
            return;
        }
        try {
            TaskChoice choice = findNext(page);
            Object candidate = choice == null ? null : choice.task;
            if (candidate == null) {
                if (++emptyProbeCount <= 15) {
                    int attempt = emptyProbeCount;
                    long token = generation;
                    HookEntry.record("video batch waiting for task data attempt=" + attempt);
                    MAIN.postDelayed(() -> {
                        if (running && generation == token && activeTaskId == null) next(activity);
                    }, 2_000L);
                    return;
                }
                boolean loginRequired = ControlBridge.loginPromptVisible();
                stop(loginRequired ? "应用宝未登录：请先打开应用宝登录账号"
                        : "没有更多可看的视频任务");
                return;
            }
            String id = (String) XposedHelpers.callMethod(candidate, "j");
            emptyProbeCount = 0;
            nextSeries = choice.series == 1 ? 2 : 1;
            STARTED.add(id);
            activeTaskId = id;
            rewardReceived = false;
            long token = generation;
            HookEntry.record("video batch showing series=" + choice.series + " taskId=" + id);
            if (choice.series == 1) {
                // Mirror the app's own slide-card entry, na.j(1).
                try {
                    XposedHelpers.setObjectField(page, "D", candidate);
                    XposedHelpers.setIntField(page, "H", 2);
                } catch (Throwable error) {
                    HookEntry.record("video batch slide context unavailable="
                            + error.getClass().getSimpleName());
                }
            }
            XposedHelpers.callMethod(page, "E0", candidate);
            MAIN.postDelayed(() -> {
                if (running && generation == token && id.equals(activeTaskId)) {
                    stop("等待视频或领奖超时");
                }
            }, 180_000L);
            DynamicClaimHooks.refreshUi();
        } catch (Throwable error) {
            HookEntry.record("video batch start failed=" + error.getClass().getSimpleName());
            stop("调用视频接口失败");
        }
    }

    private static TaskChoice findNext(Object page) {
        Object slideCard = XposedHelpers.callMethod(page, "M");
        Object whiteCard = XposedHelpers.callMethod(page, "R");
        Object slide = XposedHelpers.callMethod(slideCard, "a");
        Object white = whiteTasks(whiteCard);
        Object slideTask = fromIterable(slide, false);
        Object whiteTask = fromIterable(white, true);
        HookEntry.record("video batch series candidates slide=" + (slideTask != null)
                + " white=" + (whiteTask != null));
        if (slideTask == null && whiteTask == null
                && (emptyProbeCount == 0 || emptyProbeCount == 14)) {
            HookEntry.record("video batch inventory slide=" + inventory(slide)
                    + " white=" + inventory(white));
        }
        if (nextSeries == 1) {
            if (slideTask != null) return new TaskChoice(1, slideTask);
            if (whiteTask != null) return new TaskChoice(2, whiteTask);
        } else {
            if (whiteTask != null) return new TaskChoice(2, whiteTask);
            if (slideTask != null) return new TaskChoice(1, slideTask);
        }
        return null;
    }

    private static String inventory(Object source) {
        if (!(source instanceof Iterable)) return "unavailable";
        int total = 0;
        int watchAd = 0;
        int canReceive = 0;
        for (Object task : (Iterable<?>) source) {
            if (task == null) continue;
            total++;
            if (!"WATCH_AD".equals(String.valueOf(XposedHelpers.callMethod(task, "v")))) continue;
            watchAd++;
            if ("can_receive".equals(String.valueOf(XposedHelpers.callMethod(task, "t")))) {
                canReceive++;
            }
        }
        return "total:" + total + ",watchAd:" + watchAd + ",canReceive:" + canReceive;
    }

    private static Object whiteTasks(Object whiteCard) {
        try {
            return XposedHelpers.getObjectField(whiteCard, "f1987b");
        } catch (Throwable namedFieldError) {
            Object emptyList = null;
            for (Field field : whiteCard.getClass().getDeclaredFields()) {
                if (!List.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(whiteCard);
                    if (value instanceof List) {
                        List<?> list = (List<?>) value;
                        if (list.isEmpty()) {
                            emptyList = value;
                        } else if (list.get(0) != null
                                && "g0.l".equals(list.get(0).getClass().getName())) {
                            HookEntry.record("video batch white series found by field type");
                            return value;
                        }
                    }
                } catch (Throwable ignored) { }
            }
            if (emptyList != null) return emptyList;
            HookEntry.record("video batch white series unavailable="
                    + namedFieldError.getClass().getSimpleName());
            return null;
        }
    }

    private static final class TaskChoice {
        final int series;
        final Object task;

        TaskChoice(int series, Object task) {
            this.series = series;
            this.task = task;
        }
    }

    private static Object fromIterable(Object source, boolean allowRepeat) {
        if (!(source instanceof Iterable)) return null;
        for (Object task : (Iterable<?>) source) {
            if (task == null) continue;
            Object type = XposedHelpers.callMethod(task, "v");
            Object status = XposedHelpers.callMethod(task, "t");
            if (!"WATCH_AD".equals(String.valueOf(type))
                    || !"can_receive".equals(String.valueOf(status))) continue;
            String id = (String) XposedHelpers.callMethod(task, "j");
            if (id != null && !id.isEmpty()
                    && (allowRepeat || !STARTED.contains(id))) return task;
        }
        return null;
    }

    private static void stop(String reason) {
        if (!running) return;
        running = false;
        activeTaskId = null;
        waitingForNativeContinuation = false;
        generation++;
        HookEntry.record("video batch stopped reason=" + reason + " completed=" + completed);
        ControlBridge.publish("批量任务停止：" + reason);
        DynamicClaimHooks.refreshUi();
    }

    private static void schedulePointScan(int sequence, String taskId, int attempt) {
        MAIN.postDelayed(() -> {
            if (sequence != claimSequence || !taskId.equals(lastClaimedTaskId)
                    || lastClaimedPoints > 0) return;
            int points = readVisibleAward();
            if (points > 0) {
                lastClaimedPoints = points;
                HookEntry.record("video batch award text confirmed taskId=" + taskId
                        + " points=" + points);
                ControlBridge.publishClaim(sequence, taskId, points);
            } else if (attempt < 3) {
                schedulePointScan(sequence, taskId, attempt + 1);
            }
        }, attempt == 0 ? 150L : 500L);
    }

    private static int readVisibleAward() {
        try {
            Class<?> type = Class.forName("android.view.WindowManagerGlobal");
            Object manager = XposedHelpers.callStaticMethod(type, "getInstance");
            Object roots = XposedHelpers.getObjectField(manager, "mViews");
            if (!(roots instanceof List)) return -1;
            for (Object root : (List<?>) roots) {
                if (root instanceof View) {
                    int value = findAward((View) root);
                    if (value > 0) return value;
                }
            }
        } catch (Throwable error) {
            HookEntry.record("video award text read failed=" + error.getClass().getSimpleName());
        }
        return -1;
    }

    private static int findAward(View view) {
        if (!view.isShown()) return -1;
        if (view instanceof TextView) {
            Matcher match = AWARD_TEXT.matcher(((TextView) view).getText());
            if (match.find()) return Integer.parseInt(match.group(1));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                int value = findAward(group.getChildAt(i));
                if (value > 0) return value;
            }
        }
        return -1;
    }
}
