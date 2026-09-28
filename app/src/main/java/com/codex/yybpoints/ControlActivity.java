package com.codex.yybpoints;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.app.TimePickerDialog;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
    private static final int BACKGROUND = 0xFF0C1420;
    private static final int CARD = 0xFF172535;
    private static final int TEXT = 0xFFF2F6F5;
    private static final int MUTED = 0xFFA9BCC3;
    private static final int ACCENT = 0xFF63D7AD;

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
    private Button scheduleTimeButton;
    private TextView scheduleNext;
    private CheckBox scheduleEnabled;
    private CheckBox scheduleToast;
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
        status.setText(message);
        startButton.setEnabled(!coordinator.isSessionActive());
        renderHistory();
        renderAccount();
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(28), dp(20), dp(28));
        scroll.addView(page);

        page.addView(label("YYB  ·  LSPOSED", 12, ACCENT, true));
        page.addView(label("应用宝后台任务", 27, TEXT, true), top(4));
        page.addView(label("真实观看广告，领取结果以应用宝回执为准", 13, MUTED, false), top(7));

        LinearLayout accountCard = card();
        accountCard.setOrientation(LinearLayout.HORIZONTAL);
        accountCard.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(accountCard, top(24));
        FrameLayout avatarBox = new FrameLayout(this);
        accountCard.addView(avatarBox, new LinearLayout.LayoutParams(dp(56), dp(56)));
        avatar = new ImageView(this);
        GradientDrawable avatarBackground = new GradientDrawable();
        avatarBackground.setShape(GradientDrawable.OVAL);
        avatarBackground.setColor(0xFF2A5C61);
        avatar.setBackground(avatarBackground);
        avatarBox.addView(avatar, new FrameLayout.LayoutParams(-1, -1));
        avatarInitial = label("?", 22, TEXT, true);
        avatarInitial.setGravity(Gravity.CENTER);
        avatarBox.addView(avatarInitial, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout accountText = new LinearLayout(this);
        accountText.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams accountTextParams = new LinearLayout.LayoutParams(0, -2, 1);
        accountTextParams.leftMargin = dp(14);
        accountCard.addView(accountText, accountTextParams);
        accountHint = label("当前账号", 12, MUTED, false);
        accountText.addView(accountHint);
        accountName = label("等待识别", 17, TEXT, true);
        accountText.addView(accountName, top(5));

        LinearLayout statusCard = card();
        page.addView(statusCard, top(12));
        statusCard.addView(label("当前状态", 13, MUTED, true));
        status = label("正在检查后台状态…", 16, TEXT, false);
        status.setLineSpacing(dp(4), 1f);
        statusCard.addView(status, top(10));

        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(stats, top(12));
        completed = addStat(stats, "已领奖", "0 条", 0);
        total = addStat(stats, "已核对积分", "0", dp(8));
        unresolved = addStat(stats, "金额待核对", "0 条", dp(8));

        startButton = button("开始后台批量观看", ACCENT, BACKGROUND);
        startButton.setOnClickListener(v -> coordinator.start());
        page.addView(startButton, top(22));
        Button stop = button("停止后台播放并还原", 0xFF2A4052, TEXT);
        stop.setOnClickListener(v -> coordinator.stop());
        page.addView(stop, top(10));

        LinearLayout scheduleCard = card();
        page.addView(scheduleCard, top(20));
        scheduleCard.addView(label("每天定时后台观看", 18, TEXT, true));
        scheduleCard.addView(label("到点唤醒应用宝；完成后停止视频并清除隐藏显示",
                12, MUTED, false), top(6));
        DailySchedule.Settings schedule = DailySchedule.read(this);
        scheduleEnabled = new CheckBox(this);
        scheduleEnabled.setText("启用每日定时任务");
        scheduleEnabled.setTextColor(TEXT);
        scheduleEnabled.setChecked(schedule.enabled);
        scheduleCard.addView(scheduleEnabled, top(12));
        scheduleTimeButton = button("", 0xFF2A4052, TEXT);
        scheduleTimeButton.setOnClickListener(v -> {
            DailySchedule.Settings current = DailySchedule.read(this);
            new TimePickerDialog(this, (picker, hour, minute) -> {
                DailySchedule.update(this, scheduleEnabled.isChecked(), hour, minute,
                        scheduleToast.isChecked());
                renderSchedule();
            }, current.hour, current.minute, true).show();
        });
        scheduleCard.addView(scheduleTimeButton, top(8));
        scheduleToast = new CheckBox(this);
        scheduleToast.setText("在主屏幕显示开始和结束提示");
        scheduleToast.setTextColor(TEXT);
        scheduleToast.setChecked(schedule.toast);
        scheduleCard.addView(scheduleToast, top(8));
        scheduleNext = label("", 12, MUTED, false);
        scheduleCard.addView(scheduleNext, top(8));
        scheduleEnabled.setOnCheckedChangeListener((view, enabled) -> saveSchedule());
        scheduleToast.setOnCheckedChangeListener((view, enabled) -> saveSchedule());
        renderSchedule();

        summarySection = new LinearLayout(this);
        summarySection.setOrientation(LinearLayout.VERTICAL);
        page.addView(summarySection, top(28));
        summarySection.addView(label("本轮任务总结", 18, TEXT, true));
        history = card();
        summarySection.addView(history, top(12));
        detailsButton = button("查看逐条已核对积分", 0xFF2A4052, TEXT);
        detailsButton.setOnClickListener(v -> {
            detailsExpanded = !detailsExpanded;
            renderHistory();
        });
        summarySection.addView(detailsButton, top(10));
        details = card();
        summarySection.addView(details, top(10));

        archiveSection = new LinearLayout(this);
        archiveSection.setOrientation(LinearLayout.VERTICAL);
        page.addView(archiveSection, top(18));
        archiveButton = button("历史任务记录", 0xFF2A4052, TEXT);
        archiveButton.setOnClickListener(v -> {
            archiveExpanded = !archiveExpanded;
            renderHistory();
        });
        archiveSection.addView(archiveButton);
        archiveHistory = new LinearLayout(this);
        archiveHistory.setOrientation(LinearLayout.VERTICAL);
        archiveSection.addView(archiveHistory, top(10));

        Button uninstall = button("清理并卸载模块", 0xFF302535, 0xFFFFC7C7);
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

        setContentView(scroll);
        coordinator = BackgroundBatchCoordinator.get(this);
        coordinator.addListener(listener);
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
            renderHistory();
            renderAccount();
            renderSchedule();
        }
    }

    @Override protected void onDestroy() {
        if (coordinator != null) coordinator.removeListener(listener);
        super.onDestroy();
    }

    private void saveSchedule() {
        DailySchedule.Settings current = DailySchedule.read(this);
        DailySchedule.update(this, scheduleEnabled.isChecked(), current.hour, current.minute,
                scheduleToast.isChecked());
        renderSchedule();
    }

    private void renderSchedule() {
        DailySchedule.Settings settings = DailySchedule.read(this);
        scheduleTimeButton.setText(String.format(Locale.getDefault(), "开始时间  %02d:%02d",
                settings.hour, settings.minute));
        if (settings.enabled && settings.nextAt > 0) {
            scheduleNext.setText("下次运行：" + new SimpleDateFormat("yyyy-MM-dd HH:mm",
                    Locale.getDefault()).format(new Date(settings.nextAt)));
        } else scheduleNext.setText("定时任务已关闭");
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
        completed.setText(entries.size() + " 条");
        total.setText(String.valueOf(sum));
        unresolved.setText(unknown + " 条");
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
            accountHint.setText("最近识别的应用宝账号");
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

    private TextView addStat(LinearLayout parent, String name, String value, int leftMargin) {
        LinearLayout block = card();
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(88), 1);
        params.leftMargin = leftMargin;
        parent.addView(block, params);
        block.addView(label(name, 11, MUTED, false));
        TextView number = label(value, 18, TEXT, true);
        block.addView(number, top(10));
        return number;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(shape(CARD, dp(16)));
        return card;
    }

    private Button button(String text, int fill, int foreground) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(15);
        button.setTextColor(foreground);
        button.setBackground(shape(fill, dp(14)));
        button.setMinHeight(dp(54));
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

    private LinearLayout.LayoutParams top(int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(margin);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
