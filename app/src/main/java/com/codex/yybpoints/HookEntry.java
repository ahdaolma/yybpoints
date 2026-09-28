package com.codex.yybpoints;

import android.app.Activity;
import android.os.SystemClock;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Observes only task API metadata while the user completes a real task. */
public final class HookEntry implements IXposedHookLoadPackage {
    private static final String TARGET = "com.tencent.android.qqdownloader";
    private static final String TAG = "YybPointsProbe";
    private static volatile long taskCaptureUntilElapsed;
    private static final Set<String> loggedHosts = new HashSet<>();
    private static final Set<Method> hookedRewardMethods = new HashSet<>();
    private static final Set<Class<?>> hookedPluginClasses = new HashSet<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if ("android".equals(param.packageName)) {
            SystemDisplayBridge.install(param.classLoader);
            return;
        }
        if (!TARGET.equals(param.packageName)) return;
        try {
            BackgroundAudioIsolation.install();
        } catch (Throwable error) {
            Log.w(TAG, "hidden audio isolation unavailable", error);
        }
        try {
            if (TARGET.equals(param.processName)) DynamicClaimHooks.install();
            if (TARGET.equals(param.processName)) installRewardHooks(param.classLoader);
            if (BuildConfig.DEBUG) {
            Class<?> callback = XposedHelpers.findClass(
                    "com.tencent.assistantv2.kuikly.engine.KuiklyMultiCmdEngine$KuiklyMultiCmdCallback",
                    param.classLoader);
            XposedHelpers.findAndHookMethod(
                    "com.tencent.assistantv2.kuikly.engine.KuiklyMultiCmdEngine",
                    param.classLoader, "a", JSONArray.class, callback, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam hook) {
                            try {
                                JSONArray requests = (JSONArray) hook.args[0];
                                record("KuiklyMultiCmd count=" + requests.length());
                                for (int i = 0; i < requests.length(); i++) {
                                    String line = ProbeSummary.summarize(requests.optJSONObject(i));
                                    if (line != null) record(line);
                                }
                            } catch (Throwable error) {
                                Log.w(TAG, "summary failed: " + error.getClass().getSimpleName());
                            }
                        }
                    });
            Class<?> function1 = XposedHelpers.findClass("kotlin.jvm.functions.Function1", param.classLoader);
            XposedHelpers.findAndHookMethod(
                    "com.tencent.assistantv2.kuikly.utils.KuiklyOkHttpRequestManager",
                    param.classLoader, "sendRequest", String.class, function1, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam hook) {
                            try {
                                String line = ProbeSummary.summarize(new JSONObject((String) hook.args[0]));
                                if (line != null) record("KuiklyOkHttp " + line);
                            } catch (Throwable error) {
                                record("KuiklyOkHttp summary failed: " + error.getClass().getSimpleName());
                            }
                        }
                    });
            Class<?> protocolEngine = XposedHelpers.findClass(
                    "com.tencent.assistant.protocol.kuikly.KRProtocolEngine", param.classLoader);
            XposedBridge.hookAllMethods(protocolEngine, "f", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    try {
                        Object multi = hook.args[1];
                        List<?> requests = (List<?>) XposedHelpers.getObjectField(multi, "multiCmds");
                        List<Integer> ids = new ArrayList<>();
                        if (requests != null) for (Object request : requests) {
                            ids.add(XposedHelpers.getIntField(request, "cmdId"));
                        }
                        record("KRProtocol cmdIds=" + ids + " functionId=" + hook.args[3]);
                    } catch (Throwable error) {
                        record("KRProtocol summary failed: " + error.getClass().getSimpleName());
                    }
                }
            });
            Class<?> photon = XposedHelpers.findClass(
                    "com.tencent.rapidview.server.PhotonCommonEngine", param.classLoader);
            XposedBridge.hookAllMethods(photon, "sendRequestForRawData", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    try {
                        int id = (Integer) hook.args[0];
                        Map<?, ?> params = (Map<?, ?>) hook.args[1];
                        record("Photon cmdId=" + id + " keys=" + params.keySet());
                    } catch (Throwable error) {
                        record("Photon summary failed: " + error.getClass().getSimpleName());
                    }
                }
            });
            Class<?> bridge = XposedHelpers.findClass(
                    "com.tencent.assistantv2.kuikly.module.KRBridgeModule", param.classLoader);
            XposedBridge.hookAllMethods(bridge, "call", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    if (hook.args.length == 0 || !(hook.args[0] instanceof String)) return;
                    String method = (String) hook.args[0];
                    if (method.contains("Request") || method.contains("request")) {
                        record("KRBridge method=" + method);
                    }
                }
            });
            Class<?> netService = XposedHelpers.findClass(
                    "com.tencent.assistant.netservice.xd", param.classLoader);
            XposedBridge.hookAllMethods(netService, "k", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    if (hook.args.length < 3) return;
                    try {
                        Object request = hook.args[2];
                        Object ids = XposedHelpers.getObjectField(request, "h");
                        String function = (String) XposedHelpers.getObjectField(request, "k");
                        record("NetService cmdIds=" + ids + " functionId=" + function);
                    } catch (Throwable error) {
                        record("NetService summary failed: " + error.getClass().getSimpleName());
                    }
                }
            });
            Class<?> baseEngine = XposedHelpers.findClass(
                    "com.tencent.assistant.module.BaseModuleEngine", param.classLoader);
            XposedBridge.hookAllMethods(baseEngine, "send", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    try {
                        String functionId = "";
                        String requestType = "";
                        for (Object arg : hook.args) {
                            if (arg instanceof String && ((String) arg).matches("540[0-4]")) {
                                functionId = (String) arg;
                            } else if (arg != null && arg.getClass().getName().contains("AdsPoint")) {
                                requestType = arg.getClass().getSimpleName();
                            }
                        }
                        if (!functionId.isEmpty() || !requestType.isEmpty()) {
                            record("BaseModuleEngine functionId=" + functionId + " request=" + requestType);
                        }
                    } catch (Throwable error) {
                        record("BaseModuleEngine summary failed: " + error.getClass().getSimpleName());
                    }
                }
            });
            }
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam hook) {
                    String name = hook.thisObject.getClass().getName();
                    if (name.equals("com.qq.e.tg.RewardvideoPortraitADActivity")) {
                        VideoBatchController.onAdActivityResumed((Activity) hook.thisObject);
                        return;
                    }
                    if (name.startsWith("com.tencent.assistant." )
                            || name.startsWith("com.tencent.assistantv2.")) {
                        record("Activity resumed=" + name);
                        if (name.contains("MainActivity") || name.contains("KRCommonActivity")) {
                            DynamicClaimHooks.onActivityResumed((Activity) hook.thisObject);
                            ControlBridge.onActivityResumed((Activity) hook.thisObject);
                        }
                        if (name.contains("KRCommonActivity")) {
                            openNetworkWindow("task page", 120_000L);
                        }
                    }
                }
            });
            XposedHelpers.findAndHookMethod(Activity.class, "onPause", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam hook) {
                    if (hook.thisObject.getClass().getName()
                            .equals("com.qq.e.tg.RewardvideoPortraitADActivity")) {
                        VideoBatchController.onAdActivityPaused((Activity) hook.thisObject);
                    }
                }
            });
            XposedHelpers.findAndHookMethod(Activity.class, "onDestroy", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam hook) {
                    if (hook.thisObject.getClass().getName()
                            .equals("com.qq.e.tg.RewardvideoPortraitADActivity")) {
                        VideoBatchController.onAdActivityDestroyed((Activity) hook.thisObject);
                    }
                }
            });
            if (BuildConfig.DEBUG) {
            Class<?> okHttp = XposedHelpers.findClass("okhttp3.OkHttpClient", param.classLoader);
            XposedBridge.hookAllMethods(okHttp, "newCall", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    if (hook.args.length != 1) return;
                    try {
                        Object url = XposedHelpers.callMethod(hook.args[0], "url");
                        String host = (String) XposedHelpers.callMethod(url, "host");
                        String path = (String) XposedHelpers.callMethod(url, "encodedPath");
                        recordNetworkRoute("OkHttp", host, path);
                    } catch (Throwable ignored) { }
                }
            });
            try {
                XposedBridge.hookAllMethods(URL.class, "openConnection", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam hook) {
                        if (!(hook.thisObject instanceof URL)) return;
                        try {
                            URL url = (URL) hook.thisObject;
                            recordNetworkRoute("URL", url.getHost(), url.getPath());
                        }
                        catch (Throwable ignored) { }
                    }
                });
            } catch (Throwable error) {
                record("URL network hook unavailable=" + error.getClass().getSimpleName());
            }
            }
            record("attached process=" + param.processName);
            XposedBridge.log(TAG + ": task API metadata probe attached");
        } catch (Throwable error) {
            Log.w(TAG, "hook unavailable: " + error.getClass().getSimpleName());
            XposedBridge.log(TAG + ": hook unavailable: " + error.getClass().getName());
        }
    }

    private static void installRewardHooks(ClassLoader loader) {
        try {
            Class<?> rewardWrapper = XposedHelpers.findClass("yyb9143413.y3.xg", loader);
            XposedBridge.hookAllConstructors(rewardWrapper, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam hook) {
                    record("Kuikly reward wrapper constructed");
                }
            });
            Class<?> rewardForwarder = XposedHelpers.findClass("yyb9143413.fu.xg", loader);
            XposedBridge.hookAllMethods(rewardForwarder, "onReward", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    record("Kuikly reward forwarder callback argCount=" + hook.args.length);
                    openNetworkWindow("Kuikly reward forwarder", 45_000L);
                }
                @Override protected void afterHookedMethod(MethodHookParam hook) {
                    if (!hook.hasThrowable() && hook.args.length == 0) {
                        VideoBatchController.onSdkReward();
                    }
                }
            });
            XC_MethodHook pluginLoadHook = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam hook) {
                    if (hook.args.length == 0 || !(hook.args[0] instanceof String)) return;
                    String name = (String) hook.args[0];
                    if (name.contains("rewardad") || name.contains("RewardAD")
                            || name.contains("RewardVideo")) {
                        record("AMS plugin class loaded=" + name
                                + " found=" + (hook.getResult() != null));
                    }
                    if (!name.equals("com.tencent.assistant.plugin.ams.main.rewardad.TangramRewardADPlugin")
                            || !(hook.getResult() instanceof Class)) return;
                    Class<?> pluginClass = (Class<?>) hook.getResult();
                    synchronized (HookEntry.class) {
                        if (!hookedPluginClasses.add(pluginClass)) return;
                    }
                    XposedBridge.hookAllConstructors(pluginClass, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam constructorHook) {
                            record("Tangram reward plugin constructed argCount="
                                    + constructorHook.args.length);
                            for (Object arg : constructorHook.args) {
                                if (arg == null) continue;
                                Class<?> argClass = arg.getClass();
                                for (Class<?> implemented : argClass.getInterfaces()) {
                                    if (implemented.getName().contains("ITangramRewardADListener")) {
                                        record("Tangram reward listener class=" + argClass.getName());
                                        hookRewardListenerMethods(argClass);
                                        break;
                                    }
                                }
                            }
                        }
                    });
                }
            };
            XposedBridge.hookAllMethods(
                    XposedHelpers.findClass("yyb9143413.u9.xe", loader),
                    "loadClass", pluginLoadHook);
            XposedBridge.hookAllMethods(
                    XposedHelpers.findClass("yyb9143413.u9.xc", loader),
                    "loadClass", pluginLoadHook);
            record("Kuikly reward bridge hooks ready");
        } catch (Throwable error) {
            record("Kuikly reward bridge hooks unavailable=" + error.getClass().getSimpleName());
        }
        try {
            Class<?> bonusReward = XposedHelpers.findClass("yyb9143413.bg.xm", loader);
            XposedBridge.hookAllMethods(bonusReward, "onReward", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    if (hook.args.length == 1 && hook.args[0] != null) {
                        try {
                            Object verified = XposedHelpers.callMethod(
                                    hook.args[0], "isS2SRewardSuccess");
                            Object errorCode = XposedHelpers.callMethod(
                                    hook.args[0], "getErrorCode");
                            record("Kuikly bonus reward callback s2s=" + verified
                                    + " errorCode=" + errorCode);
                        } catch (Throwable error) {
                            record("Kuikly bonus reward callback with result");
                        }
                    } else {
                        record("Kuikly bonus reward callback");
                    }
                    openNetworkWindow("Kuikly bonus reward", 45_000L);
                }
            });
            XposedBridge.hookAllMethods(bonusReward, "onADComplete", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    record("Kuikly bonus ad complete");
                }
            });
            XposedBridge.hookAllMethods(bonusReward, "onRewardDataPassthrough", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    record("Kuikly bonus reward passthrough callback");
                }
            });
            record("Kuikly bonus reward hooks ready");
        } catch (Throwable error) {
            record("Kuikly bonus reward hooks unavailable=" + error.getClass().getSimpleName());
        }
        try {
            Class<?> rewardAd = XposedHelpers.findClass(
                    "com.tencent.assistant.business.gdt.reward.RewardAdImpl", loader);
            XposedBridge.hookAllMethods(rewardAd, "setRewardAdListener", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam hook) {
                    if (hook.args.length == 0 || hook.args[0] == null) return;
                    hookRewardListenerMethods(hook.args[0].getClass());
                }
            });
            record("RewardAdImpl listener hook ready");
        } catch (Throwable error) {
            record("RewardAdImpl listener hook unavailable=" + error.getClass().getSimpleName());
        }
        try {
            Class<?> legacy = XposedHelpers.findClass(
                    "com.tencent.assistant.manager.webview.js.impl.CommonJsBridgeImpl$xu", loader);
            XposedBridge.hookAllMethods(legacy, "onReward", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam hook) {
                    record("legacy JS bridge reward callback observed");
                    openNetworkWindow("legacy reward", 30_000L);
                }
            });
            XposedBridge.hookAllMethods(legacy, "onADClose", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    record("legacy JS bridge ad close observed");
                    openNetworkWindow("legacy ad close", 30_000L);
                }
            });
            record("legacy JS bridge reward hooks ready");
        } catch (Throwable error) {
            record("legacy JS bridge hook unavailable=" + error.getClass().getSimpleName());
        }
    }

    private static void hookRewardListenerMethods(Class<?> listenerClass) {
        for (Method method : listenerClass.getMethods()) {
            if (!"onReward".equals(method.getName())) continue;
            synchronized (HookEntry.class) {
                if (!hookedRewardMethods.add(method)) continue;
            }
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam hook) {
                        if (hook.args.length == 1 && hook.args[0] != null) {
                            try {
                                Object verified = XposedHelpers.callMethod(
                                        hook.args[0], "isS2SRewardSuccess");
                                Object errorCode = XposedHelpers.callMethod(
                                        hook.args[0], "getErrorCode");
                                record("reward callback observed s2s=" + verified
                                        + " errorCode=" + errorCode);
                            } catch (Throwable error) {
                                record("reward callback observed resultType="
                                        + hook.args[0].getClass().getSimpleName());
                            }
                        } else {
                            record("reward callback observed without result");
                        }
                        openNetworkWindow("reward callback", 30_000L);
                    }
                });
                record("reward listener hooked class=" + listenerClass.getName()
                        + " argCount=" + method.getParameterTypes().length);
            } catch (Throwable error) {
                synchronized (HookEntry.class) { hookedRewardMethods.remove(method); }
                record("reward listener hook failed=" + error.getClass().getSimpleName());
            }
        }
    }

    static synchronized void record(String message) {
        Log.i(TAG, message);
    }

    private static void openNetworkWindow(String reason, long durationMs) {
        synchronized (HookEntry.class) {
            loggedHosts.clear();
            taskCaptureUntilElapsed = SystemClock.elapsedRealtime() + durationMs;
        }
        record(reason + " network observation window opened");
    }

    private static void recordNetworkRoute(String stack, String host, String path) {
        if (host == null || host.isEmpty()
                || SystemClock.elapsedRealtime() > taskCaptureUntilElapsed) return;
        String safePath = sanitizePath(path);
        synchronized (HookEntry.class) {
            String key = stack + ":" + host + ":" + safePath;
            if (!loggedHosts.add(key)) return;
        }
        record("Network stack=" + stack + " host=" + host + " path=" + safePath);
    }

    private static String sanitizePath(String path) {
        if (path == null || path.isEmpty()) return "/";
        String[] segments = path.split("/");
        StringBuilder result = new StringBuilder();
        for (String segment : segments) {
            result.append('/');
            if (segment.isEmpty()) continue;
            if (segment.length() > 40 || segment.matches("[0-9]{4,}")
                    || segment.matches("(?i)[0-9a-f-]{16,}")) {
                result.append("{id}");
            } else {
                result.append(segment);
            }
        }
        return result.length() == 0 ? "/" : result.toString();
    }
}
