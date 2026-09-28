package com.codex.yybpoints;

import android.app.Activity;
import android.os.SystemClock;

import de.robv.android.xposed.XposedHelpers;

/** Reads only the target app's own login flag, nickname and avatar URL. */
final class AccountInfoProbe {
    private static long lastReadAt;

    private AccountInfoProbe() { }

    static void publish(Activity activity) {
        if (SystemClock.uptimeMillis() - lastReadAt < 2_000L) return;
        lastReadAt = SystemClock.uptimeMillis();
        try {
            ClassLoader loader = activity.getClassLoader();
            Class<?> proxyType = XposedHelpers.findClass(
                    "com.tencent.nucleus.socialcontact.login.LoginProxy", loader);
            Object proxy = XposedHelpers.callStaticMethod(proxyType, "getInstance");
            boolean loggedIn = Boolean.TRUE.equals(XposedHelpers.callMethod(proxy, "isLogin"));
            if (!loggedIn) {
                ControlBridge.publishAccount(false, "", "");
                return;
            }
            Class<?> utils = XposedHelpers.findClass(
                    "com.tencent.nucleus.socialcontact.login.LoginUtils", loader);
            Object profile = XposedHelpers.callStaticMethod(utils, "e");
            String name = profile == null ? "" : (String) XposedHelpers.getObjectField(profile, "nickName");
            String avatar = profile == null ? "" : (String) XposedHelpers.getObjectField(profile, "iconUrl");
            if (name == null || name.isEmpty()) {
                name = (String) XposedHelpers.callMethod(proxy, "getNickName");
            }
            ControlBridge.publishAccount(true, name, avatar);
        } catch (Throwable error) {
            HookEntry.record("account display read unavailable=" + error.getClass().getSimpleName());
        }
    }

    static Boolean loggedIn(Activity activity) {
        try {
            Class<?> type = XposedHelpers.findClass(
                    "com.tencent.nucleus.socialcontact.login.LoginProxy",
                    activity.getClassLoader());
            Object proxy = XposedHelpers.callStaticMethod(type, "getInstance");
            return (Boolean) XposedHelpers.callMethod(proxy, "isLogin");
        } catch (Throwable ignored) { return null; }
    }
}
