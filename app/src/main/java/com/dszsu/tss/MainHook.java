package com.dszsu.tss;

import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import java.lang.ref.WeakReference;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    
    // 弱引用映射，防止内存泄漏
    private static class WeakIdentityMap<K, V> {
        private final Map<WeakReference<K>, V> map = new IdentityHashMap<>();
        
        public V get(K key) {
            cleanUp();
            for (Map.Entry<WeakReference<K>, V> entry : map.entrySet()) {
                K k = entry.getKey().get();
                if (k == key) {
                    return entry.getValue();
                }
            }
            return null;
        }
        
        public void put(K key, V value) {
            cleanUp();
            map.put(new WeakReference<>(key), value);
        }
        
        public boolean containsKey(K key) {
            cleanUp();
            for (Map.Entry<WeakReference<K>, V> entry : map.entrySet()) {
                K k = entry.getKey().get();
                if (k == key) {
                    return true;
                }
            }
            return false;
        }
        
        public void remove(K key) {
            cleanUp();
            Iterator<Map.Entry<WeakReference<K>, V>> iterator = map.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<WeakReference<K>, V> entry = iterator.next();
                K k = entry.getKey().get();
                if (k == key) {
                    iterator.remove();
                    break;
                }
            }
        }
        
        private void cleanUp() {
            Iterator<Map.Entry<WeakReference<K>, V>> iterator = map.entrySet().iterator();
            while (iterator.hasNext()) {
                WeakReference<K> ref = iterator.next().getKey();
                if (ref.get() == null) {
                    iterator.remove();
                }
            }
        }
    }

    // 数据存储
    private static final WeakIdentityMap<Object, Boolean> protectedViews = new WeakIdentityMap<>();
    private static final WeakIdentityMap<Object, Object> vriToSurface = new WeakIdentityMap<>();
    private static final WeakIdentityMap<Object, Boolean> dimHandled = new WeakIdentityMap<>();
    private static final WeakIdentityMap<Object, Boolean> zoomWindows = new WeakIdentityMap<>();
    private static String currentFocusedPackage = null;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        if (pkg == null) return;
        
        // 基础功能钩子
        hookWindowManagerGlobal(lpparam);
        hookViewRootImpl(lpparam);
        hookFocusProtection(lpparam.classLoader, pkg);
        hookAppInternalZoomWindows(lpparam.classLoader, pkg);
        
        // 系统级钩子（仅在Android系统包中生效）
        if (pkg.equals("android")) {
            XposedBridge.log("[透明截图][DEBUG] 初始化系统级钩子...");
            hookSystemZoomWindows(lpparam.classLoader);
            hookToastWindows(lpparam.classLoader);
            hookSystemSpecialWindows(lpparam.classLoader);
            hookSystemFocusMonitoring(lpparam.classLoader);
            XposedBridge.log("[透明截图][DEBUG] 系统钩子初始化完成.");
        }
    }

    // ========== 窗口管理钩子 ==========
    
    /**
     * 钩子WindowManagerGlobal，处理窗口创建事件
     */
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
                        if (param.args != null && param.args.length >= 2 &&
                                param.args[0] instanceof View &&
                                param.args[1] instanceof WindowManager.LayoutParams) {
                            
                            View view = (View) param.args[0];
                            WindowManager.LayoutParams params = (WindowManager.LayoutParams) param.args[1];
                            
                            // 只保护应用窗口
                            if (params.type >= WindowManager.LayoutParams.TYPE_APPLICATION && 
                                params.type <= WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG) {
                                protectedViews.put(view, Boolean.TRUE);
                            }
                        }
                    }
                }
            );
        } catch (Throwable t) {
            logError("WindowManagerGlobal钩子失败", t);
        }
    }

    // ========== 视图根节点钩子 ==========
    
    /**
     * 钩子ViewRootImpl，处理视图绘制事件
     */
    private void hookViewRootImpl(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> vriClass = XposedHelpers.findClass(
                "android.view.ViewRootImpl",
                lpparam.classLoader
            );

            // 钩子relayoutWindow方法
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

            // 钩子performTraversals方法
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
                            XposedBridge.log("[透明截图][DEBUG] 获取mView字段失败: " + t.getMessage());
                            return;
                        }

                        if (view == null || !protectedViews.containsKey(view)) return;

                        handleDimming(vri);
                        handleSecure(vri, lpparam.classLoader);
                    }
                }
            );
        } catch (Throwable t) {
            logError("ViewRootImpl钩子失败", t);
        }
    }

    /**
     * 处理窗口调光
     */
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
        } catch (Throwable t) {
            logError("处理窗口调光失败", t);
        }
    }

    /**
     * 处理防截屏
     */
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
            } catch (Throwable t) {
                XposedBridge.log("[透明截图][DEBUG] 设置跳过截屏失败: " + t.getMessage());
            }

            if (!applied && Build.VERSION.SDK_INT < 33) {
                try {
                    XposedHelpers.callMethod(txn, "setSecure", sc, true);
                } catch (Throwable t) {
                    XposedBridge.log("[透明截图][DEBUG] 设置安全模式失败: " + t.getMessage());
                }
            }

            XposedHelpers.callMethod(txn, "apply");
        } catch (Throwable t) {
            logError("处理防截屏失败", t);
        }
    }

    /**
     * 查找有效的Surface对象
     */
    private Object findValidSurface(Object vri) {
        String[] fields = {"mSurfaceControl", "mLeash", "mSurfaceControlLocked", "mSurface"};

        for (String f : fields) {
            try {
                Object sc = XposedHelpers.getObjectField(vri, f);
                if (sc != null) {
                    boolean isValid = (boolean) XposedHelpers.callMethod(sc, "isValid");
                    if (isValid) {
                        return sc;
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log("[透明截图][DEBUG] 获取Surface字段" + f + "失败: " + t.getMessage());
            }
        }
        return null;
    }

    // ========== 焦点保护钩子 ==========
    
    /**
     * 钩子焦点保护相关方法
     */
    private void hookFocusProtection(ClassLoader cl, final String packageName) {
        try {
            // 钩子View焦点变化
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
                            updateCurrentFocusedPackage(packageName, "获得焦点");
                        }
                    }
                }
            );
            
            // 钩子Activity焦点
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
                                updateCurrentFocusedPackage(packageName, "Activity获得焦点");
                            }
                        }
                    }
                );
            } catch (Throwable t) {
                XposedBridge.log("[透明截图][DEBUG] Activity焦点钩子失败: " + packageName + ": " + t.getMessage());
            }
            
        } catch (Throwable t) {
            XposedBridge.log("[透明截图][DEBUG] 焦点保护钩子失败: " + packageName + ": " + t.getMessage());
            XposedBridge.log("[透明截图][DEBUG] " + Log.getStackTraceString(t));
        }
    }

    /**
     * 更新当前焦点应用
     */
    private void updateCurrentFocusedPackage(String packageName, String event) {
        currentFocusedPackage = packageName;
        XposedBridge.log("[透明截图][DEBUG] " + event + ": " + packageName);
    }

    // ========== 应用内小窗钩子 ==========
    
    /**
     * 钩子应用内小窗
     */
    private void hookAppInternalZoomWindows(ClassLoader cl, final String packageName) {
        try {
            // 使用hookAllMethods代替findAndHookMethod，避免方法签名问题
            Class<?> windowManagerClass = XposedHelpers.findClass("android.view.WindowManager", cl);
            XposedBridge.hookAllMethods(
                windowManagerClass,
                "addView",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        // 检查参数是否符合预期
                        if (param.args != null && param.args.length >= 2 &&
                            param.args[0] instanceof View &&
                            param.args[1] instanceof WindowManager.LayoutParams) {
                            
                            WindowManager.LayoutParams params = (WindowManager.LayoutParams) param.args[1];
                            // 检测是否是悬浮窗口类型
                            if (params.type >= WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY ||
                                params.type == WindowManager.LayoutParams.TYPE_TOAST ||
                                params.type == WindowManager.LayoutParams.TYPE_SYSTEM_ALERT) {
                                
                                // 应用内悬浮窗口，应用防截屏
                                View view = (View) param.args[0];
                                protectedViews.put(view, Boolean.TRUE);
                                
                                XposedBridge.log("[透明截图][DEBUG] 检测到应用内悬浮窗口: " + packageName);
                            }
                        }
                    }
                }
            );
        } catch (Throwable t) {
            // 只记录简单的错误信息，避免日志过多
            XposedBridge.log("[透明截图][DEBUG] 应用内小窗钩子失败: " + packageName + ": " + t.getMessage());
        }
    }

    // ========== 系统级钩子 ==========
    
    /**
     * 钩子系统小窗
     */
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
                                setSurfaceSecure(transaction, surfaceControl);
                            }
                        }
                    }
                }
            );
        } catch (Throwable t) {
            logError("系统小窗钩子失败", t);
        }
    }

    /**
     * 设置Surface安全属性
     */
    private void setSurfaceSecure(Object transaction, Object surfaceControl) {
        try {
            XposedHelpers.callMethod(transaction, "setSkipScreenshot", surfaceControl, true);
        } catch (Throwable t) {
            XposedBridge.log("[透明截图][DEBUG] 系统窗口设置跳过截屏失败: " + t.getMessage());
            if (Build.VERSION.SDK_INT < 33) {
                try {
                    XposedHelpers.callMethod(transaction, "setSecure", surfaceControl, true);
                } catch (Throwable t2) {
                    XposedBridge.log("[透明截图][DEBUG] 系统窗口设置安全模式失败: " + t2.getMessage());
                }
            }
        }
    }

    /**
     * 钩子Toast窗口
     */
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
                                    XposedBridge.log("[透明截图][DEBUG] 隐藏Toast窗口");
                                }
                            }
                        } catch (Throwable t) {
                            XposedBridge.log("[透明截图][DEBUG] Toast窗口钩子失败: " + t.getMessage());
                        }
                    }
                }
            );
        } catch (Throwable t) {
            logError("Toast钩子失败", t);
        }
    }

    /**
     * 钩子系统特殊窗口（输入法等）
     */
    private void hookSystemSpecialWindows(final ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.wm.WindowState",
                cl,
                "handleTapOutsideFocusInsideSelf",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Object winState = param.thisObject;
                        
                        // 检测是否是需要处理的特殊窗口
                        if (checkWindowForHide(winState)) {
                            XposedBridge.log("[透明截图][DEBUG] 拦截系统特殊窗口焦点");
                            
                            // 获取WindowManagerService实例
                            Object wmService = XposedHelpers.getObjectField(winState, "mWmService");
                            // 获取当前焦点窗口
                            Object currentFocusedWindow = XposedHelpers.callMethod(wmService, "getFocusedWindowLocked");
                            
                            // 如果当前有有效焦点窗口且不是需要隐藏的窗口
                            if (currentFocusedWindow != null && !checkWindowForHide(currentFocusedWindow)) {
                                XposedBridge.log("[透明截图][DEBUG] 保持当前非特殊窗口焦点");
                                // 将当前显示移到最上层
                                int displayId = (int) XposedHelpers.callMethod(winState, "getDisplayId");
                                XposedHelpers.callMethod(wmService, "moveDisplayToTopInternal", displayId);
                                // 取消当前操作，阻止焦点切换
                                param.setResult(null);
                                return;
                            } else {
                                // 没有可保持的有效焦点，阻止所有操作
                                XposedBridge.log("[透明截图][DEBUG] 没有可保持的有效焦点，阻止所有操作");
                                param.setResult(null);
                            }
                        }
                    }
                }
            );
        } catch (Throwable t) {
            logError("系统特殊窗口钩子失败", t);
        }
    }

    /**
     * 钩子系统焦点监控
     */
    private void hookSystemFocusMonitoring(final ClassLoader cl) {
        try {
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
                                    XposedBridge.log("[透明截图][DEBUG] 系统焦点设置为: " + pkg);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[透明截图][DEBUG] 获取所属包名失败: " + t.getMessage());
                            }
                        }
                    }
                }
            );
        } catch (Throwable t) {
            logError("系统焦点监控钩子失败", t);
        }
    }

    // ========== 窗口检测工具方法 ==========
    
    /**
     * 检测是否是系统特殊窗口（保留用于兼容现有代码）
     */
    private boolean isSystemSpecialWindow(Object winState) {
        return checkWindowForHide(winState);
    }

    /**
     * 检测是否是需要处理的特殊窗口
     */
    private boolean checkWindowForHide(Object winState) {
        try {
            String tag = String.valueOf(XposedHelpers.callMethod(winState, "getWindowTag", new Object[0]));
            String pkg = (String) XposedHelpers.callMethod(winState, "getOwningPackage", new Object[0]);
            
            // 1. 特殊窗口标签检测
            if ("com.oplus.screenshot/LongshotCapture".equals(tag) ||
                (tag != null && tag.contains("OplusOSZoomFloatHandleView")) ||
                "InputMethod".equals(tag)) {
                return true;
            }
            
            // 2. 特殊包名检测
            if ("com.oplus.appplatform".equals(pkg) ||
                "com.coloros.smartsidebar".equals(pkg)) {
                return true;
            }
            
            // 3. OPPO小窗模式检测
            try {
                Object ext = XposedHelpers.getObjectField(winState, "mWindowStateExt");
                if (ext != null) {
                    int mode = (int) XposedHelpers.callMethod(winState, "getWindowingMode", new Object[0]);
                    if ((boolean)XposedHelpers.callMethod(ext, "checkIfWindowingModeZoom", new Object[]{mode})) {
                        return true;
                    }
                }
            } catch (Throwable t) {
                // 忽略异常，继续检测其他条件
            }
            
            // 4. 灵活任务窗口检测
            Object task = XposedHelpers.callMethod(winState, "getTask", new Object[0]);
            if (task == null) return false;
            
            Object rootTask = XposedHelpers.callMethod(task, "getRootTask", new Object[0]);
            if (rootTask == null) rootTask = task;
            
            if (!isFlexibleTaskAndHasCaption(rootTask)) return false;
            
            try {
                Object wrapper = XposedHelpers.callMethod(rootTask, "getWrapper", new Object[0]);
                if (wrapper == null) return false;
                
                Object extImpl = XposedHelpers.callMethod(wrapper, "getExtImpl", new Object[0]);
                if (extImpl == null) return false;
                
                int flexibleZoomState = (int) XposedHelpers.callMethod(extImpl, "getFlexibleZoomState", new Object[0]);
                if (flexibleZoomState != 0) return true;
            } catch (Throwable t) {
                // 忽略异常
            }
        } catch (Throwable t) {
            // 忽略所有异常，确保方法不会崩溃
        }
        return false;
    }

    /**
     * 检测是否是具有标题的灵活任务窗口
     */
    private static boolean isFlexibleTaskAndHasCaption(Object rootTask) {
        ClassLoader cl = rootTask.getClass().getClassLoader();
        
        boolean isFlexibleTask = false;
        try {
            // 尝试查找FlexibleWindowUtils类
            Class<?> flexUtilsClass = XposedHelpers.findClass("com.android.server.wm.FlexibleWindowUtils", cl);
            // 尝试调用isFlexibleTaskAndHasCaption方法
            isFlexibleTask = (boolean) XposedHelpers.callStaticMethod(
                    flexUtilsClass,
                    "isFlexibleTaskAndHasCaption",
                    rootTask
            );
        } catch (NoSuchMethodError e) {
        } catch (Throwable t) {
        }
        return isFlexibleTask;
    }

    // ========== 工具方法 ==========
    
    /**
     * 统一的错误日志记录
     */
    private void logError(String message, Throwable t) {
        XposedBridge.log("[透明截图][DEBUG] " + message + ": " + t.getMessage());
        XposedBridge.log("[透明截图][DEBUG] " + Log.getStackTraceString(t));
    }
}
