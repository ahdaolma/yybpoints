package com.codex.yybpoints;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Saves display-only account data and confirmed claims if the control UI is closed. */
public final class ClaimStatusReceiver extends BroadcastReceiver {
    private static final String TARGET = "com.tencent.android.qqdownloader";

    @Override public void onReceive(Context context, Intent intent) {
        if (!ControlBridge.ACTION_STATUS.equals(intent.getAction())) return;
        try {
            int expectedUid = context.getPackageManager().getPackageUid(TARGET, 0);
            if (getSentFromUid() != expectedUid || !TARGET.equals(getSentFromPackage())) return;
            String event = intent.getStringExtra("event");
            if ("claim".equals(event)) {
                new RewardHistory(context).record(intent.getIntExtra("sequence", -1),
                        intent.getStringExtra("claimTaskId"), intent.getIntExtra("points", -1));
            } else if ("account".equals(event)) {
                new AccountProfileStore(context).update(intent.getBooleanExtra("loggedIn", false),
                        intent.getStringExtra("nickname"), intent.getStringExtra("avatarUrl"));
            }
        } catch (Throwable ignored) { }
    }
}
