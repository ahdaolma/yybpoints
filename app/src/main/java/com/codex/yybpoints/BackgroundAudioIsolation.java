package com.codex.yybpoints;

import android.app.Application;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.util.Log;
import android.view.Display;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Keeps hidden-display ads from taking the physical screen's audio focus. */
final class BackgroundAudioIsolation {
    private static final String TAG = "YYBBackground";
    private static volatile Context appContext;
    private static boolean installed;
    private static int loggedFocusRequests;

    private BackgroundAudioIsolation() { }

    static synchronized void install() {
        if (installed) return;
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam hook) {
                        // Application.getApplicationContext() can still be null in attach().
                        appContext = (Context) hook.args[0];
                    }
                });
        XposedBridge.hookAllMethods(AudioManager.class, "requestAudioFocus", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam hook) {
                if (!isHiddenSession()) return;
                // The ad SDK may require a granted result to keep video playback running,
                // but the system must not pause or duck the user's physical-screen media.
                hook.setResult(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
                if (loggedFocusRequests++ < 3) {
                    Log.i(TAG, "hidden ad audio focus kept local");
                }
            }
        });
        XposedBridge.hookAllMethods(AudioTrack.class, "play", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam hook) {
                if (isHiddenSession()) ((AudioTrack) hook.thisObject).setVolume(0f);
            }
        });
        XposedBridge.hookAllMethods(AudioTrack.class, "setVolume", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam hook) {
                if (isHiddenSession() && hook.args.length == 1) hook.args[0] = 0f;
            }
        });
        XposedBridge.hookAllMethods(AudioTrack.class, "setStereoVolume", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam hook) {
                if (isHiddenSession() && hook.args.length == 2) {
                    hook.args[0] = 0f;
                    hook.args[1] = 0f;
                }
            }
        });
        XposedBridge.hookAllMethods(MediaPlayer.class, "start", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam hook) {
                if (isHiddenSession()) ((MediaPlayer) hook.thisObject).setVolume(0f, 0f);
            }
        });
        XposedBridge.hookAllMethods(MediaPlayer.class, "setVolume", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam hook) {
                if (isHiddenSession() && hook.args.length == 2) {
                    hook.args[0] = 0f;
                    hook.args[1] = 0f;
                }
            }
        });
        installed = true;
        Log.i(TAG, "hidden audio isolation hooks ready");
    }

    private static boolean isHiddenSession() {
        if (VideoBatchController.isRunning()) return true;
        Context context = appContext;
        if (context == null) return false;
        try {
            DisplayManager manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (manager != null) for (Display display : manager.getDisplays()) {
                if ("YYBPoints-Trusted".equals(display.getName())) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }
}
