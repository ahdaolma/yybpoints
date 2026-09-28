package com.codex.yybpoints;

import android.app.Activity;
import android.animation.ValueAnimator;
import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.app.TimePickerDialog;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.SystemClock;
import android.content.res.Configuration;
import android.provider.Settings;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Physical-screen controls and confirmed reward history for hidden video playback. */
public final class ControlActivity extends Activity {
    private static final String TAG = "YYBBackground";
    private int BACKGROUND;
    private int CARD;
    private int TEXT;
    private int MUTED;
    private int ACCENT;
    private int ON_ACCENT;
    private int TINT;
    private int HERO;
    private int DIVIDER;
    private int RIPPLE;

    private TextView status;
    private TextView accountName;
    private TextView accountHint;
    private TextView avatarInitial;
    private ImageView avatar;
    private String loadedAvatarUrl = "";
    private TextView completed;
    private TextView total;
    private TextView unresolved;
    private Button startButton;
    private TextView stateTitle;
    private long actionLockedUntil;
    private boolean actionStarted;
    private Button scheduleTimeButton;
    private TextView scheduleNext;
    private TextView scheduleLastLabel;
    private TextView scheduleLastResult;
    private View scheduleDivider;
    private Switch scheduleEnabled;
    private LinearLayout summarySection;
    private LinearLayout history;
    private LinearLayout details;
    private Button detailsButton;
    private boolean detailsExpanded;
    private LinearLayout archiveSection;
    private LinearLayout archiveHistory;
    private Button archiveButton;
    private boolean archiveExpanded;
    private BackgroundBatchCoordinator coordinator;
    private final BackgroundBatchCoordinator.Listener listener = message -> {
        if (!message.contentEquals(status.getText())) {
            status.setText(message);
            animateChange(status);
        }
        renderAction();
        renderHistory();
        renderAccount();
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        BACKGROUND = getColor(R.color.page_background);
        CARD = getColor(R.color.surface);
        TEXT = getColor(R.color.text_primary);
        MUTED = getColor(R.color.text_secondary);
        ACCENT = getColor(R.color.accent);
        ON_ACCENT = getColor(R.color.on_accent);
        TINT = getColor(R.color.accent_tint);
        HERO = getColor(R.color.result_surface);
        DIVIDER = getColor(R.color.divider);
        RIPPLE = getColor(R.color.touch_ripple);
        if (getActionBar() != null) getActionBar().hide();
        ScheduledBatchService.ensureNotificationChannels(this);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(BACKGROUND);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setFocusableInTouchMode(true);
        page.requestFocus();
        page.setPadding(dp(24), dp(24), dp(24), dp(24));
        scroll.addView(page);

        page.addView(label("应用宝积分", 28, TEXT, true));
        page.addView(label("任务与积分记录", 14, MUTED, false), top(6));

        LinearLayout accountCard = new LinearLayout(this);
        accountCard.setOrientation(LinearLayout.HORIZONTAL);
        accountCard.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(accountCard, top(20));
        FrameLayout avatarBox = new FrameLayout(this);
        accountCard.addView(avatarBox, new LinearLayout.LayoutParams(dp(44), dp(44)));
        avatar = new ImageView(this);
        GradientDrawable avatarBackground = new GradientDrawable();
        avatarBackground.setShape(GradientDrawable.OVAL);
        avatarBackground.setColor(getColor(R.color.avatar_background));
        avatar.setBackground(avatarBackground);
        avatar.setClipToOutline(true);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatarBox.addView(avatar, new FrameLayout.LayoutParams(-1, -1));
        avatarInitial = label("?", 22, TEXT, true);
        avatarInitial.setGravity(Gravity.CENTER);
        avatarBox.addView(avatarInitial, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout accountText = new LinearLayout(this);
        accountText.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams accountTextParams = new LinearLayout.LayoutParams(0, -2, 1);
        accountTextParams.leftMargin = dp(11);
        accountCard.addView(accountText, accountTextParams);
        accountHint = label("当前账号", 12, MUTED, false);
        accountText.addView(accountHint);
        accountName = label("等待识别", 16, TEXT, true);
        accountText.addView(accountName, top(2));

        LinearLayout stats = card();
        stats.setBackground(shape(HERO, dp(28)));
        stats.setPadding(dp(22), dp(21), dp(22), dp(21));
        page.addView(stats, top(24));
        stateTitle = label("准备就绪", 20, TEXT, true);
        stats.addView(stateTitle);
        status = label("正在检查后台状态…", 13, MUTED, false);
        status.setLineSpacing(dp(3), 1f);
        stats.addView(status, top(8));
        View statsDivider = new View(this);
        statsDivider.setBackgroundColor(DIVIDER);
        LinearLayout.LayoutParams statsDividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        statsDividerParams.topMargin = dp(20);
        stats.addView(statsDivider, statsDividerParams);
        LinearLayout statsRow = new LinearLayout(this);
        statsRow.setOrientation(LinearLayout.HORIZONTAL);
        statsRow.setGravity(Gravity.CENTER_VERTICAL);
        stats.addView(statsRow, top(16));
        LinearLayout pointsBlock = new LinearLayout(this);
        pointsBlock.setOrientation(LinearLayout.VERTICAL);
        pointsBlock.addView(label("本轮已核对积分", 12, MUTED, false));
        total = label("0", 34, TEXT, true);
        pointsBlock.addView(total);
        statsRow.addView(pointsBlock, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout counts = new LinearLayout(this);
        counts.setOrientation(LinearLayout.VERTICAL);
        completed = label("已领奖 0 条", 14, TEXT, true);
        counts.addView(completed);
        unresolved = label("待核对 0 条", 12, MUTED, false);
        counts.addView(unresolved, top(7));
        statsRow.addView(counts);

        startButton = button("开始后台观看", ACCENT, ON_ACCENT);
        startButton.setOnClickListener(v -> {
            if (SystemClock.uptimeMillis() < actionLockedUntil) return;
            actionStarted = !coordinator.isSessionActive();
            actionLockedUntil = SystemClock.uptimeMillis() + 1500L;
            if (!actionStarted) coordinator.stop();
            else coordinator.start();
            renderAction();
            startButton.postDelayed(this::renderAction, 1550L);
        });
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(dp(24), dp(12), dp(24), dp(16));
        actions.setBackgroundColor(BACKGROUND);
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(-1, dp(56));
        actions.addView(startButton, startParams);
        root.addView(actions);

        LinearLayout scheduleCard = card();
        page.addView(label("自动化", 13, MUTED, true), top(28));
        page.addView(scheduleCard, top(10));
        LinearLayout scheduleHeader = new LinearLayout(this);
        scheduleHeader.setOrientation(LinearLayout.HORIZONTAL);
        scheduleHeader.setGravity(Gravity.CENTER_VERTICAL);
        scheduleCard.addView(scheduleHeader);
        LinearLayout scheduleTitle = new LinearLayout(this);
        scheduleTitle.setOrientation(LinearLayout.VERTICAL);
        scheduleTitle.addView(label("每日定时", 17, TEXT, true));
        scheduleTitle.addView(label("每天按设定时间运行", 12, MUTED, false), top(3));
        scheduleHeader.addView(scheduleTitle, new LinearLayout.LayoutParams(0, -2, 1));
        DailySchedule.Settings schedule = DailySchedule.read(this);
        scheduleEnabled = new Switch(this);
        scheduleEnabled.setContentDescription("启用每日定时任务");
        int[][] switchStates = {new int[] {android.R.attr.state_checked}, new int[] {}};
        scheduleEnabled.setThumbTintList(new ColorStateList(switchStates,
                new int[] {ACCENT, getColor(R.color.switch_off_thumb)}));
        scheduleEnabled.setTrackTintList(new ColorStateList(switchStates,
                new int[] {getColor(R.color.switch_on_track),
                        getColor(R.color.switch_off_track)}));
        scheduleEnabled.setChecked(schedule.enabled);
        scheduleHeader.addView(scheduleEnabled);
        scheduleTimeButton = button("", TINT, ACCENT);
        scheduleTimeButton.setOnClickListener(v -> {
            DailySchedule.Settings current = DailySchedule.read(this);
            new TimePickerDialog(this, (picker, hour, minute) -> {
                DailySchedule.update(this, scheduleEnabled.isChecked(), hour, minute);
                renderSchedule();
            }, current.hour, current.minute, true).show();
        });
        scheduleCard.addView(scheduleTimeButton, top(16));
        scheduleNext = label("", 12, MUTED, false);
        scheduleCard.addView(scheduleNext, top(8));
        scheduleDivider = new View(this);
        scheduleDivider.setBackgroundColor(DIVIDER);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        dividerParams.topMargin = dp(16);
        scheduleCard.addView(scheduleDivider, dividerParams);
        scheduleLastLabel = label("", 12, MUTED, false);
        scheduleCard.addView(scheduleLastLabel, top(14));
        scheduleLastResult = label("", 13, TEXT, false);
        scheduleLastResult.setLineSpacing(dp(3), 1f);
        scheduleCard.addView(scheduleLastResult, top(5));
        scheduleEnabled.setOnCheckedChangeListener((view, enabled) -> saveSchedule());
        renderSchedule();

        summarySection = new LinearLayout(this);
        summarySection.setOrientation(LinearLayout.VERTICAL);
        page.addView(summarySection, top(26));
        summarySection.addView(label("本轮任务总结", 18, TEXT, true));
        history = card();
        summarySection.addView(history, top(12));
        detailsButton = button("查看逐条已核对积分", CARD, ACCENT);
        detailsButton.setOnClickListener(v -> {
            if (motionEnabled()) TransitionManager.beginDelayedTransition(summarySection,
                    new AutoTransition().setDuration(200));
            detailsExpanded = !detailsExpanded;
            renderHistory();
        });
        summarySection.addView(detailsButton, top(10));
        details = card();
        summarySection.addView(details, top(10));

        archiveSection = new LinearLayout(this);
        archiveSection.setOrientation(LinearLayout.VERTICAL);
        page.addView(archiveSection, top(18));
        archiveButton = button("历史任务记录", CARD, ACCENT);
        archiveButton.setOnClickListener(v -> {
            if (motionEnabled()) TransitionManager.beginDelayedTransition(archiveSection,
                    new AutoTransition().setDuration(200));
            archiveExpanded = !archiveExpanded;
            renderHistory();
        });
        archiveSection.addView(archiveButton);
        archiveHistory = new LinearLayout(this);
        archiveHistory.setOrientation(LinearLayout.VERTICAL);
        archiveSection.addView(archiveHistory, top(10));

        Button uninstall = button("清理并卸载模块", BACKGROUND,
                getColor(R.color.destructive_text));
        uninstall.setOnClickListener(v -> {
            uninstall.setEnabled(false);
            if (!coordinator.isSessionActive() && !coordinator.hasHiddenDisplay()) {
                openSystemUninstall(uninstall);
            } else {
                coordinator.stop(success -> {
                    if (!success) status.setText("后台停止未确认；系统卸载时会再次清理，必要时重启手机");
                    openSystemUninstall(uninstall);
                });
            }
        });
        page.addView(uninstall, top(28));
        page.addView(label("卸载前会结束视频、移除隐藏显示和应用宝进程。",
                12, MUTED, false), top(8));

        setContentView(root);
        coordinator = BackgroundBatchCoordinator.get(this);
        coordinator.addListener(listener);
        scroll.post(() -> {
            if (!requestNotificationPermissionOnFirstLaunch()) requestExactAlarmAccess(false);
        });
    }

    private void renderAction() {
        boolean active = coordinator.isSessionActive();
        boolean locked = SystemClock.uptimeMillis() < actionLockedUntil;
        String title = active ? "任务进行中" : coordinator.rewardsFinished() ? "本轮已结束" : "准备就绪";
        if (!title.contentEquals(stateTitle.getText())) {
            stateTitle.setText(title);
            animateChange(stateTitle);
        }
        String action = locked ? actionStarted ? "正在启动…" : "正在清理…"
                : active ? "停止并还原" : "开始后台观看";
        startButton.setEnabled(!locked);
        if (!action.contentEquals(startButton.getText())) {
            startButton.setText(action);
            animateChange(startButton);
        }
    }

    private void openSystemUninstall(Button uninstall) {
        uninstall.setEnabled(true);
        if (isFinishing() || isDestroyed()) return;
        try {
            startActivity(new Intent(Intent.ACTION_DELETE,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException error) {
            Log.e(TAG, "system uninstall screen unavailable", error);
            status.setText("系统卸载界面不可用；可在系统设置中卸载本模块");
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (coordinator != null) {
            DailySchedule.Settings schedule = DailySchedule.read(this);
            if (schedule.enabled && schedule.nextAt == 0
                    && !DailySchedule.needsExactAlarmPermission(this)) DailySchedule.reschedule(this);
            renderHistory();
            renderAccount();
            renderSchedule();
            renderAction();
        }
    }

    @Override protected void onDestroy() {
        if (coordinator != null) coordinator.removeListener(listener);
        super.onDestroy();
    }

    private boolean requestNotificationPermissionOnFirstLaunch() {
        if (Build.VERSION.SDK_INT < 33 || isFinishing() || isDestroyed()
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED) return false;
        android.content.SharedPreferences prefs = getSharedPreferences("permission_prompt", MODE_PRIVATE);
        if (prefs.getBoolean("notification_requested", false)) return false;
        prefs.edit().putBoolean("notification_requested", true).apply();
        requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
        return true;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) requestExactAlarmAccess(false);
    }

    private void requestExactAlarmAccess(boolean userInitiated) {
        if (!DailySchedule.read(this).enabled || !DailySchedule.needsExactAlarmPermission(this)
                || isFinishing() || isDestroyed()) return;
        android.content.SharedPreferences prefs = getSharedPreferences("permission_prompt", MODE_PRIVATE);
        if (!userInitiated && prefs.getBoolean("exact_alarm_requested", false)) return;
        prefs.edit().putBoolean("exact_alarm_requested", true).apply();
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException error) {
            Log.e(TAG, "exact alarm settings unavailable", error);
            status.setText("系统精确闹钟授权界面不可用，定时任务尚未启动");
        }
    }

    private void saveSchedule() {
        DailySchedule.Settings current = DailySchedule.read(this);
        DailySchedule.update(this, scheduleEnabled.isChecked(), current.hour, current.minute);
        renderSchedule();
        if (scheduleEnabled.isChecked()) requestExactAlarmAccess(true);
    }

    private void renderSchedule() {
        DailySchedule.Settings settings = DailySchedule.read(this);
        scheduleTimeButton.setText(String.format(Locale.getDefault(), "开始时间  %02d:%02d",
                settings.hour, settings.minute));
        if (settings.enabled && settings.nextAt > 0) {
            scheduleNext.setText("下次运行：" + new SimpleDateFormat("yyyy-MM-dd HH:mm",
                    Locale.getDefault()).format(new Date(settings.nextAt)));
        } else if (settings.enabled) scheduleNext.setText("等待系统精确闹钟授权；定时任务尚未启动");
        else scheduleNext.setText("定时任务已关闭");
        boolean hasLastRun = settings.lastRunAt > 0;
        scheduleDivider.setVisibility(hasLastRun ? View.VISIBLE : View.GONE);
        scheduleLastLabel.setVisibility(hasLastRun ? View.VISIBLE : View.GONE);
        scheduleLastResult.setVisibility(hasLastRun ? View.VISIBLE : View.GONE);
        if (hasLastRun) {
            scheduleLastLabel.setText("上次运行  "
                    + new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                    .format(new Date(settings.lastRunAt)));
            scheduleLastResult.setText(settings.lastResult);
        }
    }

    private void renderHistory() {
        List<RewardHistory.Entry> entries = coordinator.rewards();
        int sum = 0;
        int unknown = 0;
        Map<Integer, Integer> amounts = new TreeMap<>();
        for (RewardHistory.Entry entry : entries) {
            if (entry.points > 0) {
                sum += entry.points;
                amounts.put(entry.points, amounts.getOrDefault(entry.points, 0) + 1);
            } else unknown++;
        }
        int known = entries.size() - unknown;
        boolean showSummary = !entries.isEmpty() && coordinator.rewardsFinished();
        summarySection.setVisibility(showSummary ? View.VISIBLE : View.GONE);
        history.removeAllViews();
        details.removeAllViews();
        if (showSummary) {
            long startedAt = coordinator.rewardsStartedAt();
            long finishedAt = coordinator.rewardsFinishedAt();
            if (startedAt > 0 && finishedAt >= startedAt) {
                SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                        Locale.getDefault());
                history.addView(label("时间：" + format.format(new Date(startedAt)) + " 至 "
                        + format.format(new Date(finishedAt)), 12, MUTED, false));
            }
            history.addView(label("完成 " + entries.size() + " 条 · 已核对 " + known
                    + " 条 · 共 " + sum + " 积分", 16, TEXT, true), top(8));
            if (!amounts.isEmpty()) {
                StringBuilder breakdown = new StringBuilder();
                for (Map.Entry<Integer, Integer> amount : amounts.entrySet()) {
                    if (breakdown.length() > 0) breakdown.append("  ·  ");
                    breakdown.append("+").append(amount.getKey()).append(" × ")
                            .append(amount.getValue());
                }
                history.addView(label("已核对：" + breakdown, 13, ACCENT, false), top(8));
            }
            if (unknown > 0) {
                history.addView(label(unknown + " 条已领奖，但回执缺少积分金额", 12,
                        MUTED, false), top(8));
            }
        }
        detailsButton.setVisibility(showSummary && known > 0 ? View.VISIBLE : View.GONE);
        detailsButton.setText(detailsExpanded ? "收起逐条已核对积分" : "查看逐条已核对积分");
        details.setVisibility(showSummary && known > 0 && detailsExpanded
                ? View.VISIBLE : View.GONE);
        if (showSummary && detailsExpanded) {
            for (int i = entries.size() - 1; i >= 0; i--) {
                RewardHistory.Entry entry = entries.get(i);
                if (entry.points <= 0) continue;
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                if (details.getChildCount() > 0) row.setPadding(0, dp(12), 0, 0);
                LinearLayout names = new LinearLayout(this);
                names.setOrientation(LinearLayout.VERTICAL);
                names.addView(label("第 " + entry.sequence + " 条视频", 15, TEXT, true));
                names.addView(label("任务 ID " + entry.taskId, 11, MUTED, false), top(3));
                row.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
                row.addView(label("+" + entry.points + " 积分", 13, ACCENT, true));
                details.addView(row);
            }
        }
        List<RewardHistory.Run> runs = new RewardHistory(this).archiveSnapshot();
        archiveSection.setVisibility(runs.isEmpty() ? View.GONE : View.VISIBLE);
        archiveButton.setText((archiveExpanded ? "收起" : "查看") + "历史任务记录（"
                + runs.size() + " 轮）");
        archiveHistory.setVisibility(archiveExpanded ? View.VISIBLE : View.GONE);
        archiveHistory.removeAllViews();
        if (archiveExpanded) {
            SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
            for (RewardHistory.Run run : runs) {
                LinearLayout item = card();
                archiveHistory.addView(item, top(8));
                int points = 0;
                int unknownCount = 0;
                StringBuilder perTask = new StringBuilder();
                for (RewardHistory.Entry entry : run.entries) {
                    if (entry.points > 0) {
                        points += entry.points;
                        if (perTask.length() > 0) perTask.append("  ·  ");
                        perTask.append("第").append(entry.sequence).append("条 +")
                                .append(entry.points);
                    } else unknownCount++;
                }
                item.addView(label(format.format(new Date(run.startedAt)) + " 至 "
                        + format.format(new Date(run.finishedAt)), 12, MUTED, false));
                item.addView(label(run.entries.size() + " 条 · 已核对 " + points
                        + " 积分 · 待核对 " + unknownCount + " 条", 14, TEXT, true), top(5));
                if (perTask.length() > 0) item.addView(label(perTask.toString(), 12,
                        ACCENT, false), top(6));
            }
        }
        completed.setText("已领奖 " + entries.size() + " 条");
        String sumText = String.valueOf(sum);
        if (!sumText.contentEquals(total.getText())) {
            total.setText(sumText);
            animateChange(total);
        }
        unresolved.setText("待核对 " + unknown + " 条");
    }

    private void renderAccount() {
        AccountProfileStore.Profile profile = coordinator.account();
        if (!profile.known) {
            accountHint.setText("当前账号");
            accountName.setText("启动后台任务后识别");
            avatarInitial.setText("?");
        } else if (!profile.loggedIn) {
            accountHint.setText("应用宝账号");
            accountName.setText("未登录 · 请先登录应用宝");
            avatarInitial.setText("?");
        } else {
            accountHint.setText("最近识别的账号");
            String name = profile.nickname.isEmpty() ? "已登录" : profile.nickname;
            accountName.setText(name);
            avatarInitial.setText(name.substring(0, 1));
        }
        String url = profile.loggedIn ? profile.avatarUrl : "";
        if (url.equals(loadedAvatarUrl)) return;
        loadedAvatarUrl = url;
        avatar.setImageDrawable(null);
        avatarInitial.setVisibility(View.VISIBLE);
        if (!url.isEmpty()) {
            AvatarLoader.load(this, url, avatar, () -> {
                if (url.equals(loadedAvatarUrl)) avatarInitial.setVisibility(View.GONE);
            });
        }
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(shape(CARD, dp(24)));
        return card;
    }

    private Button button(String text, int fill, int foreground) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(15);
        button.setTextColor(foreground);
        button.setBackground(ripple(shape(fill, dp(28))));
        button.setPadding(dp(16), dp(8), dp(16), dp(8));
        button.setMinHeight(dp(54));
        button.setElevation(0);
        button.setStateListAnimator(null);
        button.setOnTouchListener((view, event) -> {
            if (!motionEnabled()) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                view.animate().cancel();
                view.animate().scaleX(0.985f).scaleY(0.985f).setDuration(90).start();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                view.animate().cancel();
                view.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
            }
            return false;
        });
        return button;
    }

    private TextView label(String value, int sp, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(color);
        if (bold) text.setTypeface(null, 1);
        return text;
    }

    private GradientDrawable shape(int fill, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private GradientDrawable outline(int fill, int stroke, int radius) {
        GradientDrawable drawable = shape(fill, radius);
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private RippleDrawable ripple(GradientDrawable drawable) {
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), drawable,
                shape(0xFFFFFFFF, dp(28)));
    }

    private boolean motionEnabled() {
        return Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled();
    }

    private void animateChange(View view) {
        view.animate().cancel();
        if (!motionEnabled()) {
            view.setAlpha(1f);
            return;
        }
        view.setAlpha(0.55f);
        view.animate().alpha(1f).setDuration(180).start();
    }

    private LinearLayout.LayoutParams top(int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(margin);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
