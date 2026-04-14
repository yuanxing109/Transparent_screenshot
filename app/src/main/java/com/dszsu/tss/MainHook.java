package com.dszsu.tss;

import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    // 使用 ConcurrentHashMap + WeakReference 包装
    private static final Map<WeakReference<View>, Boolean> protectedViews = new ConcurrentHashMap<>();
    private static final Map<Object, Object> vriToSurface = new ConcurrentHashMap<>();
    private static final Map<Object, Boolean> dimHandled = new ConcurrentHashMap<>();
    
    // 线程安全的Set
    private static final Set<String> loggedMessages = ConcurrentHashMap.newKeySet();
    private static final Set<String> initializedPackages = ConcurrentHashMap.newKeySet();
    
    private static final String TAG = "WisdomPass-Focus";

    private static final Set<String> TARGET_PACKAGES = new HashSet<String>() {{
        add("com.chaoxing.mobile");
        add("cn.ulearning.yxy");
    }};

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        if (pkg == null) return;
        if (pkg.startsWith("android") || pkg.startsWith("com.android.systemui")) return;

        // 画面隐藏功能（对所有应用生效）
        hookWindowManagerGlobal(lpparam);
        hookViewRootImpl(lpparam);
        cleanupWeakReferences();  // 定期清理失效引用

        // 焦点锁定功能（仅目标应用）
        if (TARGET_PACKAGES.contains(pkg)) {
            initFocusInterceptor(lpparam);
        }
    }

    // 清理失效的WeakReference
    private void cleanupWeakReferences() {
        protectedViews.keySet().removeIf(ref -> ref.get() == null);
    }

    private void initFocusInterceptor(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        
        if (!initializedPackages.contains(pkg)) {
            XposedBridge.log(TAG + " 初始化焦点拦截: " + pkg);
            initializedPackages.add(pkg);
        }

        try {
            // 只Hook最关键的层级，减少性能开销
            hookActivityFocusChanged(lpparam);
            // 移除其他冗余Hook，一个Activity层级就够了
        } catch (Throwable t) {
            XposedBridge.log(TAG + " 初始化失败: " + t.getMessage());
        }
    }

    private void hookActivityFocusChanged(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                android.app.Activity.class,
                "onWindowFocusChanged",
                boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        boolean hasFocus = (boolean) param.args[0];
                        
                        // 只在失去焦点时处理
                        if (hasFocus) return;
                        
                        android.app.Activity activity = (android.app.Activity) param.thisObject;
                        
                        // 精确包名验证
                        String activityPkg = activity.getPackageName();
                        if (!TARGET_PACKAGES.contains(activityPkg)) return;
                        
                        // 排除Dialog和PopupWindow
                        if (isTransientWindow(activity)) return;
                        
                        // 只拦截核心Activity（可选，根据需要开启）
                        if (shouldInterceptActivity(activity)) {
                            param.args[0] = true;
                            logOnce(activityPkg, "拦截焦点: " + activity.getClass().getSimpleName());
                        }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Hook Activity 失败: " + t.getMessage());
        }
    }

    // 判断是否是临时窗口（Dialog、Popup等）
    private boolean isTransientWindow(android.app.Activity activity) {
        try {
            // 检查Window类型
            Window window = activity.getWindow();
            if (window != null) {
                WindowManager.LayoutParams attrs = window.getAttributes();
                if (attrs != null && attrs.type != WindowManager.LayoutParams.TYPE_APPLICATION) {
                    return true;
                }
            }
            
            // 检查类名（Activity可能命名为DialogActivity，但不是真正的Dialog）
            String className = activity.getClass().getName();
            return className.contains("Popup") || 
                   className.contains("Toast") ||
                   className.contains("DialogActivity");
        } catch (Throwable ignored) {
            return false;
        }
    }

    // 判断是否应该拦截该Activity
    private boolean shouldInterceptActivity(android.app.Activity activity) {
        String className = activity.getClass().getName();
        // 可根据需要调整白名单/黑名单
        // 返回true表示拦截所有Activity，更激进
        // 或者只拦截特定Activity
        return true;  // 默认拦截所有
        
        // 更精细的控制示例：
        // return className.contains("Video") || 
        //        className.contains("Exam") ||
        //        className.contains("Course");
    }

    private void logOnce(String packageName, String message) {
        String key = packageName + ":" + message;
        if (!loggedMessages.contains(key)) {
            XposedBridge.log(TAG + " " + message);
            loggedMessages.add(key);
        }
    }

    // ==================== 画面隐藏功能（保持不变） ====================
    private void hookWindowManagerGlobal(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> wmg = XposedHelpers.findClass("android.view.WindowManagerGlobal", lpparam.classLoader);
            XposedBridge.hookAllMethods(wmg, "addView", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof View) {
                        View view = (View) param.args[0];
                        protectedViews.put(new WeakReference<>(view), true);
                    }
                }
            });
            
            // Hook removeView 清理
            XposedBridge.hookAllMethods(wmg, "removeView", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof View) {
                        View view = (View) param.args[0];
                        protectedViews.keySet().removeIf(ref -> {
                            View v = ref.get();
                            return v == null || v == view;
                        });
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    private void hookViewRootImpl(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> vriClass = XposedHelpers.findClass("android.view.ViewRootImpl", lpparam.classLoader);

            XposedBridge.hookAllMethods(vriClass, "relayoutWindow", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    handleDimming(param.thisObject);
                }
            });

            XposedBridge.hookAllMethods(vriClass, "performTraversals", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object vri = param.thisObject;
                    View view;
                    try {
                        view = (View) XposedHelpers.getObjectField(vri, "mView");
                    } catch (Throwable t) {
                        return;
                    }
                    if (view == null) return;
                    
                    // 检查View是否受保护
                    boolean isProtected = protectedViews.keySet().stream()
                        .anyMatch(ref -> ref.get() == view);
                    if (!isProtected) return;
                    
                    handleDimming(vri);
                    handleSecure(vri, lpparam.classLoader);
                }
            });
        } catch (Throwable ignored) {}
    }

    private void handleDimming(Object vri) {
        try {
            if (dimHandled.containsKey(vri)) return;
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) XposedHelpers.getObjectField(vri, "mWindowAttributes");
            if (lp != null && (lp.flags & WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0) {
                lp.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
                lp.dimAmount = 0f;
                dimHandled.put(vri, true);
            }
        } catch (Throwable ignored) {}
    }

    private void handleSecure(Object vri, ClassLoader cl) {
        try {
            Object sc = findValidSurface(vri);
            if (sc == null) return;

            synchronized (vriToSurface) {
                Object last = vriToSurface.get(vri);
                if (last == sc) return;
                vriToSurface.put(vri, sc);
            }

            Class<?> txnClass = XposedHelpers.findClass("android.view.SurfaceControl$Transaction", cl);
            Object txn = XposedHelpers.newInstance(txnClass);
            boolean applied = false;

            try {
                XposedHelpers.callMethod(txn, "setSkipScreenshot", sc, true);
                applied = true;
            } catch (Throwable ignored) {}

            if (!applied && Build.VERSION.SDK_INT < 33) {
                try {
                    XposedHelpers.callMethod(txn, "setSecure", sc, true);
                } catch (Throwable ignored) {}
            }

            XposedHelpers.callMethod(txn, "apply");
        } catch (Throwable ignored) {}
    }

    private Object findValidSurface(Object vri) {
        String[] fields = {
            "mSurfaceControl",
            "mLeash",
            "mSurfaceControlLocked",
            "mSurface"
        };
        for (String f : fields) {
            try {
                Object sc = XposedHelpers.getObjectField(vri, f);
                if (sc != null && (boolean) XposedHelpers.callMethod(sc, "isValid")) {
                    return sc;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }
}
