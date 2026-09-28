package com.codex.yybpoints;

import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;

/** Debug-build-only ADB probe. The manifest requires android.permission.DUMP. */
public final class DebugBridgeReceiver extends BroadcastReceiver {
    private static final String TAG = "YYBBackground";
    private static final String ACTION_START = "com.codex.yybpoints.DEBUG_START_DISPLAY";
    private static final String ACTION_STOP = "com.codex.yybpoints.DEBUG_STOP_DISPLAY";
    private static final String ACTION_BATCH = "com.codex.yybpoints.DEBUG_START_BATCH";
    private static final String ACTION_DISPLAYS = "com.codex.yybpoints.DEBUG_QUERY_DISPLAYS";
    private static final String ACTION_ONE_CLICK = "com.codex.yybpoints.DEBUG_ONE_CLICK_BATCH";
    private static final String ACTION_ONE_CLICK_STOP = "com.codex.yybpoints.DEBUG_ONE_CLICK_STOP";
    private static final String ACTION_AUDIO_PROBE = "com.codex.yybpoints.DEBUG_AUDIO_FOCUS_PROBE";

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_ONE_CLICK.equals(action) || ACTION_ONE_CLICK_STOP.equals(action)) {
            PendingResult pending = goAsync();
            BackgroundBatchCoordinator coordinator = BackgroundBatchCoordinator.get(context);
            if (ACTION_ONE_CLICK.equals(action)) coordinator.start();
            else coordinator.stop();
            new Handler(Looper.getMainLooper()).postDelayed(pending::finish,
                    ACTION_ONE_CLICK.equals(action) ? 9_000L : 2_000L);
            return;
        }
        if (ACTION_DISPLAYS.equals(action)) {
            DisplayManager manager = (DisplayManager) context.getSystemService(
                    Context.DISPLAY_SERVICE);
            if (manager != null) {
                for (Display display : manager.getDisplays()) {
                    Log.i(TAG, "debug visible display id=" + display.getDisplayId()
                            + " name=" + display.getName());
                }
            }
            return;
        }
        if (!ACTION_START.equals(action) && !ACTION_STOP.equals(action)
                && !ACTION_BATCH.equals(action) && !ACTION_AUDIO_PROBE.equals(action)) return;
        String command = ACTION_START.equals(action) ? SystemDisplayBridge.ACTION_START
                : ACTION_STOP.equals(action) ? SystemDisplayBridge.ACTION_STOP
                : ACTION_AUDIO_PROBE.equals(action) ? ControlBridge.ACTION_QUERY
                : ControlBridge.ACTION_START;
        Log.i(TAG, "debug probe forwarding=" + command);
        Intent forward = new Intent(command).setPackage(ACTION_BATCH.equals(action)
                || ACTION_AUDIO_PROBE.equals(action)
                ? "com.tencent.android.qqdownloader" : "android");
        if (ACTION_AUDIO_PROBE.equals(action)) forward.putExtra("probeAudioFocus", true);
        context.sendBroadcast(forward, null,
                BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }
}
