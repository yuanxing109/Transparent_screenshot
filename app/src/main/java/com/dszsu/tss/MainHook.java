package com.dszsu.tss;

import android.os.Build;
import android.view.View;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    // 代码1原有数据结构
    private static final Map<Object, Boolean> protectedViews = new IdentityHashMap<>();
    private static final Map<Object, Object> vriToSurface = new IdentityHashMap<>();
    private static final Map<Object, Boolean> dimHandled = new IdentityHashMap<>();
    
    // 代码2新增：记录已处理的小窗窗口
    private static final Map<Object, Boolean> zoomWindows = new IdentityHashMap<>();
    
    // 代码2新增：当前焦点应用
    private static String currentFocusedPackage = null;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        if (pkg == null) return;
        
        // 1. 窗口全局钩子（防截屏基础）
        hookWindowManagerGlobal(lpparam);
        
        // 2. 视图根节点钩子（防截屏核心）
        hookViewRootImpl(lpparam);
        
        // 3. 焦点保护钩子
        hookFocusProtection(lpparam.classLoader, pkg);
        
        // 4. 应用内小窗检测（如果应用内有小窗功能）
        hookAppInternalZoomWindows(lpparam.classLoader, pkg);
        
        // 这些钩子只在Android系统包中生效，用于处理系统级窗口
        
        if (pkg.equals("android")) {
            XposedBridge.log("MainHook: Initializing system-level hooks...");
            
            // 5. 系统小窗检测（OPPO特有）
            hookSystemZoomWindows(lpparam.classLoader);
            
            // 6. Toast隐藏
            hookToastWindows(lpparam.classLoader);
            
            // 7. 输入法等特殊系统窗口
            hookSystemSpecialWindows(lpparam.classLoader);
            
            // 8. 系统级焦点监控
            hookSystemFocusMonitoring(lpparam.classLoader);
            
            XposedBridge.log("MainHook: System hooks initialized.");
        }
    }

    
    private void hookWindowManagerGlobal(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> wmg = XposedHelpers.findClass(
                "android.view.WindowManagerGlobal",
                lpparam.classLoader
            );

            XposedBridge.hookAllMethods(
                wmg,
                "addView",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args != null && param.args.length > 0 &&
                                param.args[0] instanceof View) {
                            protectedViews.put(param.args[0], Boolean.TRUE);
                        }
                    }
                }
            );
        } catch (Throwable ignored) {}
    }

    private void hookViewRootImpl(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> vriClass = XposedHelpers.findClass(
                "android.view.ViewRootImpl",
                lpparam.classLoader
            );

            XposedBridge.hookAllMethods(
                vriClass,
                "relayoutWindow",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        handleDimming(param.thisObject);
                    }
                }
            );

            XposedBridge.hookAllMethods(
                vriClass,
                "performTraversals",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object vri = param.thisObject;

                        Object view;
                        try {
                            view = XposedHelpers.getObjectField(vri, "mView");
                        } catch (Throwable t) {
                            return;
                        }

                        if (view == null || !protectedViews.containsKey(view)) return;

                        handleDimming(vri);
                        handleSecure(vri, lpparam.classLoader);
                    }
                }
            );
        } catch (Throwable ignored) {}
    }

    private void handleDimming(Object vri) {
        try {
            if (dimHandled.containsKey(vri)) return;

            WindowManager.LayoutParams lp = (WindowManager.LayoutParams)
                    XposedHelpers.getObjectField(vri, "mWindowAttributes");

            if (lp != null && (lp.flags & WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0) {
                lp.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
                lp.dimAmount = 0f;
                dimHandled.put(vri, Boolean.TRUE);
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

            Class<?> txnClass = XposedHelpers.findClass(
                "android.view.SurfaceControl$Transaction",
                cl
            );
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

    
    private void hookFocusProtection(ClassLoader cl, final String packageName) {
        try {
            // 1. 钩子窗口焦点变化
            XposedHelpers.findAndHookMethod(
                "android.view.View",
                cl,
                "onWindowFocusChanged",
                boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        boolean hasFocus = (boolean) param.args[0];
                        if (hasFocus) {
                            currentFocusedPackage = packageName;
                            XposedBridge.log("MainHook: Focus gained by " + packageName);
                        }
                    }
                }
            );
            
            // 2. 钩子Activity焦点
            try {
                Class<?> activityClass = XposedHelpers.findClass("android.app.Activity", cl);
                XposedHelpers.findAndHookMethod(
                    activityClass,
                    "onWindowFocusChanged",
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            boolean hasFocus = (boolean) param.args[0];
                            if (hasFocus) {
                                currentFocusedPackage = packageName;
                                XposedBridge.log("MainHook: Activity focus gained by " + packageName);
                            }
                        }
                    }
                );
            } catch (Throwable t) {
                // 忽略
            }
            
        } catch (Throwable t) {
            XposedBridge.log("MainHook: Focus protection hook failed for " + packageName + ": " + t);
        }
    }
    
    
    private void hookAppInternalZoomWindows(ClassLoader cl, final String packageName) {

        try {
            // 检测悬浮窗口
            XposedHelpers.findAndHookMethod(
                "android.view.WindowManager",
                cl,
                "addView",
                View.class,
                WindowManager.LayoutParams.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        WindowManager.LayoutParams params = (WindowManager.LayoutParams) param.args[1];
                        if (params != null) {
                            // 检测是否是悬浮窗口类型
                            if (params.type >= WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY ||
                                params.type == WindowManager.LayoutParams.TYPE_TOAST ||
                                params.type == WindowManager.LayoutParams.TYPE_SYSTEM_ALERT) {
                                
                                // 应用内悬浮窗口，应用防截屏
                                View view = (View) param.args[0];
                                protectedViews.put(view, Boolean.TRUE);
                                
                                XposedBridge.log("MainHook: Detected app internal overlay in " + packageName);
                            }
                        }
                    }
                }
            );
        } catch (Throwable t) {
            // 忽略
        }
    }
    
    private void hookSystemZoomWindows(final ClassLoader cl) {
        try {
            Class<?> transactionClass = XposedHelpers.findClass("android.view.SurfaceControl$Transaction", cl);
            XposedHelpers.findAndHookMethod(
                "com.android.server.wm.WindowState",
                cl,
                "updateSurfacePosition",
                transactionClass,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object winState = param.thisObject;
                        
                        // 检测是否是小窗或特殊窗口
                        if (isSystemSpecialWindow(winState)) {
                            zoomWindows.put(winState, Boolean.TRUE);
                            
                            Object transaction = param.args[0];
                            Object surfaceControl = XposedHelpers.getObjectField(winState, "mSurfaceControl");
                            
                            if (surfaceControl != null) {
                                try {
                                    XposedHelpers.callMethod(transaction, "setSkipScreenshot", surfaceControl, true);
                                } catch (Throwable t) {
                                    if (Build.VERSION.SDK_INT < 33) {
                                        XposedHelpers.callMethod(transaction, "setSecure", surfaceControl, true);
                                    }
                                }
                            }
                        }
                    }
                }
            );
        } catch (Throwable ignored) {
            XposedBridge.log("MainHook: System zoom window hook failed");
        }
    }
    
    private void hookToastWindows(final ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.wm.WindowState",
                cl,
                "isVisible",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object windowState = param.thisObject;
                            Object attrs = XposedHelpers.getObjectField(windowState, "mAttrs");
                            if (attrs != null) {
                                int type = XposedHelpers.getIntField(attrs, "type");
                                if (type == 2005) { // TYPE_TOAST
                                    param.setResult(false);
                                    XposedBridge.log("MainHook: Hiding Toast window");
                                }
                            }
                        } catch (Throwable t) {
                            // 忽略
                        }
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log("MainHook: Toast hook failed: " + t);
        }
    }
    
    private void hookSystemSpecialWindows(final ClassLoader cl) {
        try {
            // 输入法窗口处理
            XposedHelpers.findAndHookMethod(
                "com.android.server.wm.WindowState",
                cl,
                "handleTapOutsideFocusInsideSelf",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Object winState = param.thisObject;
                        
                        // 检测是否是特殊系统窗口（输入法、小窗等）
                        if (isSystemSpecialWindow(winState)) {
                            XposedBridge.log("MainHook: Intercepting focus for system special window");
                            
                            // 保持当前应用焦点
                            if (currentFocusedPackage != null) {
                                param.setResult(null);
                            }
                        }
                    }
                }
            );
        } catch (Throwable ignored) {
            XposedBridge.log("MainHook: System special window hook failed");
        }
    }
    
    private void hookSystemFocusMonitoring(final ClassLoader cl) {
        try {
            // 监控系统焦点变化
            XposedHelpers.findAndHookMethod(
                "com.android.server.wm.WindowManagerService",
                cl,
                "setFocusedWindow",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Object windowState = param.args[0];
                        if (windowState != null) {
                            try {
                                String pkg = (String) XposedHelpers.callMethod(
                                    windowState, "getOwningPackage", new Object[0]
                                );
                                if (pkg != null && !pkg.equals("android") && 
                                    !pkg.equals("com.android.systemui")) {
                                    currentFocusedPackage = pkg;
                                    XposedBridge.log("MainHook: System focus set to " + pkg);
                                }
                            } catch (Throwable t) {
                                // 忽略
                            }
                        }
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log("MainHook: System focus monitoring hook failed: " + t);
        }
    }
    
    // ========== 系统特殊窗口检测 ==========
    
    private boolean isSystemSpecialWindow(Object winState) {
        try {
            String tag = String.valueOf(XposedHelpers.callMethod(winState, "getWindowTag", new Object[0]));
            String pkg = (String) XposedHelpers.callMethod(winState, "getOwningPackage", new Object[0]);
            
            // 1. 输入法检测
            if ("InputMethod".equals(tag)) {
                return true;
            }
            
            // 2. OPPO特有检测
            if ("com.oplus.screenshot/LongshotCapture".equals(tag) ||
                (tag != null && tag.contains("OplusOSZoomFloatHandleView"))) {
                return true;
            }
            
            // 3. 系统UI特殊包名
            if ("com.oplus.appplatform".equals(pkg) ||
                "com.coloros.smartsidebar".equals(pkg)) {
                return true;
            }
            
            // 4. OPPO小窗模式检测
            try {
                Object ext = XposedHelpers.getObjectField(winState, "mWindowStateExt");
                if (ext != null) {
                    int mode = (int) XposedHelpers.callMethod(winState, "getWindowingMode", new Object[0]);
                    if ((boolean)XposedHelpers.callMethod(ext, "checkIfWindowingModeZoom", new Object[]{mode})) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {}
            
        } catch (Throwable ignored) {
        }
        return false;
    }
}
