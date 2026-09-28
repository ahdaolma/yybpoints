package com.codex.yybpoints;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

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
    private LinearLayout history;
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

        page.addView(label("本轮到账明细", 18, TEXT, true), top(28));
        page.addView(label("仅记录已确认领奖；金额优先取应用宝领取成功回执", 12, MUTED, false), top(4));
        history = card();
        page.addView(history, top(12));

        Button uninstall = button("清理并卸载模块", 0xFF302535, 0xFFFFC7C7);
        uninstall.setOnClickListener(v -> {
            uninstall.setEnabled(false);
            coordinator.stop(success -> {
                uninstall.setEnabled(true);
                if (!success || isFinishing() || isDestroyed()) return;
                try {
                    startActivity(new Intent(Intent.ACTION_DELETE,
                            Uri.parse("package:" + getPackageName())));
                } catch (ActivityNotFoundException error) {
                    Log.e(TAG, "system uninstall screen unavailable", error);
                    status.setText("系统卸载界面不可用；可在系统设置中卸载本模块");
                }
            });
        });
        page.addView(uninstall, top(28));
        page.addView(label("卸载前会结束视频、移除隐藏显示和应用宝进程。",
                12, MUTED, false), top(8));

        setContentView(scroll);
        coordinator = BackgroundBatchCoordinator.get(this);
        coordinator.setListener(listener);
    }

    @Override protected void onResume() {
        super.onResume();
        if (coordinator != null) {
            renderHistory();
            renderAccount();
        }
    }

    @Override protected void onDestroy() {
        if (coordinator != null) coordinator.clearListener(listener);
        super.onDestroy();
    }

    private void renderHistory() {
        List<RewardHistory.Entry> entries = coordinator.rewards();
        int sum = 0;
        int unknown = 0;
        history.removeAllViews();
        if (entries.isEmpty()) {
            history.addView(label("开始后台观看后，已领奖的视频会显示在这里。",
                    13, MUTED, false));
        } else {
            for (int i = entries.size() - 1; i >= 0; i--) {
                RewardHistory.Entry entry = entries.get(i);
                if (entry.points > 0) sum += entry.points;
                else unknown++;
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                if (i != entries.size() - 1) row.setPadding(0, dp(12), 0, 0);
                LinearLayout names = new LinearLayout(this);
                names.setOrientation(LinearLayout.VERTICAL);
                names.addView(label("第 " + entry.sequence + " 条视频", 15, TEXT, true));
                names.addView(label("任务 ID " + entry.taskId, 11, MUTED, false), top(3));
                row.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
                row.addView(label(entry.points > 0 ? "+" + entry.points + " 积分"
                        : "金额待核对", 13, entry.points > 0 ? ACCENT : MUTED, true));
                history.addView(row);
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
