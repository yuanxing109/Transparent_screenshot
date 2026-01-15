package com.dszsu.tss;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;

import android.view.View;
import android.os.Build;
import android.view.WindowManager;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * MainHook Xposed模块 - 透明截图保护
 * 整合Entry和MainHook功能，默认保护前台应用焦点
 */
public class MainHook implements IXposedHookLoadPackage {

    /**
     * 存储受保护的视图
     */
    private static final Map<Object, Boolean> protectedViews =
            new IdentityHashMap<>();

    /**
     * 存储ViewRootImpl到Surface的映射
     */
    private static final Map<Object, Object> vriToSurface =
            new IdentityHashMap<>();

    /**
     * 存储已处理过的dimming状态
     */
    private static final Map<Object, Boolean> dimHandled =
            new IdentityHashMap<>();

    /**
     * Xposed模块入口方法
     */
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        if (pkg == null) return;
        
        // 第一层：第三方应用保护（应用层面）
        if (!pkg.startsWith("android") && !pkg.startsWith("com.android.systemui")) {
            hookWindowManagerGlobal(lpparam);
            hookViewRootImpl(lpparam);
        }
        
        // 第二层：系统框架保护（系统层面，只执行一次）
        if (pkg.equals("android")) {
            XposedBridge.log("透明截图: 初始化系统钩子...");
            hookHandleTapOutsideFocusInsideSelf(lpparam.classLoader);
            hookOplusZoomWindow(lpparam.classLoader);
            XposedBridge.log("透明截图: 系统钩子初始化完成.");
        }
    }

    /**
     * 钩子窗口管理全局类
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
                            if (param.args != null && param.args.length > 0 &&
                                    param.args[0] instanceof View) {
                                protectedViews.put(param.args[0], Boolean.TRUE);
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {
        }
    }

    /**
     * 钩子视图根实现类
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
                                return;
                            }

                            if (view == null || !protectedViews.containsKey(view)) return;

                            handleDimming(vri);
                            handleSecure(vri, lpparam.classLoader);
                        }
                    }
            );
        } catch (Throwable ignored) {
        }
    }

    /**
     * 处理窗口变暗效果
     */
    private void handleDimming(Object vri) {
        try {
            if (dimHandled.containsKey(vri)) return;

            WindowManager.LayoutParams lp =
                    (WindowManager.LayoutParams)
                            XposedHelpers.getObjectField(vri, "mWindowAttributes");

            if (lp != null &&
                    (lp.flags & WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0) {
                lp.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
                lp.dimAmount = 0f;
                dimHandled.put(vri, Boolean.TRUE);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 处理安全相关的Surface操作
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
            } catch (Throwable ignored) {
            }

            if (!applied && Build.VERSION.SDK_INT < 33) {
                try {
                    XposedHelpers.callMethod(txn, "setSecure", sc, true);
                } catch (Throwable ignored) {
                }
            }

            XposedHelpers.callMethod(txn, "apply");
        } catch (Throwable ignored) {
        }
    }

    /**
     * 查找有效的Surface对象
     */
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
                if (sc != null &&
                        (boolean) XposedHelpers.callMethod(sc, "isValid")) {
                    return sc;
                }
            } catch (Throwable ignored) {
            }
        }
        
        return null;
    }

    /**
     * 钩子窗口焦点外点击事件 - 保护前台应用焦点
     * 默认保护所有前台应用的焦点，防止被其他窗口抢走
     */
    private void hookHandleTapOutsideFocusInsideSelf(final ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.server.wm.WindowState",
                    cl,
                    "handleTapOutsideFocusInsideSelf",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object clickedWindow = param.thisObject;  // 被点击的窗口
                            
                            // 1. 先保护被点击窗口的截图
                            protectWindow(clickedWindow);
                            
                            // 2. 获取窗口管理器服务
                            Object wmService = XposedHelpers.getObjectField(clickedWindow, "mWmService");
                            
                            // 3. 获取当前获得焦点的窗口（前台应用）
                            Object currentFocusedWindow = XposedHelpers.callMethod(wmService, "getFocusedWindowLocked");
                            
                            // 4. 关键：默认保护所有前台应用的焦点
                            if (currentFocusedWindow != null && isForegroundAppWindow(currentFocusedWindow)) {
                                String focusedPkg = (String) XposedHelpers.callMethod(currentFocusedWindow, "getOwningPackage");
                                String clickedPkg = (String) XposedHelpers.callMethod(clickedWindow, "getOwningPackage");
                                
                                XposedBridge.log(String.format(
                                    "透明截图: 保护前台应用 - 当前焦点: %s, 点击窗口: %s", 
                                    focusedPkg, clickedPkg
                                ));
                                
                                // 5. 判断点击的窗口是否允许获取焦点
                                if (!isAllowedToTakeFocus(clickedWindow, currentFocusedWindow)) {
                                    XposedBridge.log("透明截图: 拦截焦点转移，保护前台应用: " + focusedPkg);
                                    
                                    // 6. 获取当前焦点窗口的显示ID
                                    int displayId = (int) XposedHelpers.callMethod(currentFocusedWindow, "getDisplayId");
                                    
                                    // 7. 将当前显示移动到顶部
                                    XposedHelpers.callMethod(wmService, "moveDisplayToTopInternal", displayId);
                                    
                                    // 8. 拦截系统处理，保持前台应用焦点
                                    param.setResult(null);
                                    return;
                                }
                            }
                            // 9. 如果当前不是前台应用窗口，或者允许焦点转移，就不拦截
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("透明截图: 钩子 handleTapOutsideFocusInsideSelf 失败: " + t.getMessage());
        }
    }

    /**
     * 判断窗口是否是前台应用窗口
     * 默认保护所有前台应用
     */
    private boolean isForegroundAppWindow(Object window) {
        try {
            // 获取窗口包名
            String pkg = (String) XposedHelpers.callMethod(window, "getOwningPackage");
            if (pkg == null) return false;
            
            // 排除系统窗口（输入法、通知栏等）
            if (isSystemWindow(window)) {
                return false;
            }
            
            // 获取窗口属性
            WindowManager.LayoutParams attrs = (WindowManager.LayoutParams) 
                XposedHelpers.callMethod(window, "getAttrs");
            
            if (attrs != null) {
                // 检查窗口类型
                int type = attrs.type;
                
                // 排除系统级窗口类型
                if (type == WindowManager.LayoutParams.TYPE_INPUT_METHOD ||
                    type == WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE ||
                    type == WindowManager.LayoutParams.TYPE_STATUS_BAR ||
                    type == WindowManager.LayoutParams.TYPE_NAVIGATION_BAR ||
                    type == WindowManager.LayoutParams.TYPE_SYSTEM_ALERT ||
                    type == WindowManager.LayoutParams.TYPE_TOAST ||
                    type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) {
                    return false;
                }
                
                // 检查窗口标志
                int flags = attrs.flags;
                if ((flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0 ||
                    (flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0) {
                    return false;
                }
            }
            
            // 默认情况下，所有非系统应用窗口都视为前台应用窗口
            return true;
            
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 判断是否是系统窗口
     */
    private boolean isSystemWindow(Object window) {
        try {
            String pkg = (String) XposedHelpers.callMethod(window, "getOwningPackage");
            if (pkg == null) return false;
            
            // 系统应用包名判断
            if (pkg.equals("android") || 
                pkg.startsWith("com.android.") ||
                pkg.startsWith("com.coloros.") || 
                pkg.startsWith("com.oplus.") ||
                pkg.startsWith("android.systemui")) {
                return true;
            }
            
            // 检查窗口标签
            String tag = String.valueOf(XposedHelpers.callMethod(window, "getWindowTag"));
            if (tag != null && (
                tag.contains("SystemUI") ||
                tag.contains("Keyguard") ||
                tag.contains("NavigationBar") ||
                tag.contains("StatusBar")
            )) {
                return true;
            }
            
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 判断窗口是否允许从当前焦点窗口获取焦点
     */
    private boolean isAllowedToTakeFocus(Object clickedWindow, Object currentFocusedWindow) {
        try {
            String clickedPkg = (String) XposedHelpers.callMethod(clickedWindow, "getOwningPackage");
            String focusedPkg = (String) XposedHelpers.callMethod(currentFocusedWindow, "getOwningPackage");
            
            // 1. 同一个应用允许焦点转移
            if (focusedPkg != null && clickedPkg != null && focusedPkg.equals(clickedPkg)) {
                return true;
            }
            
            // 2. 系统窗口允许获取焦点
            if (isSystemWindow(clickedWindow)) {
                return true;
            }
            
            // 3. 检查窗口属性
            WindowManager.LayoutParams attrs = (WindowManager.LayoutParams) 
                XposedHelpers.callMethod(clickedWindow, "getAttrs");
            
            if (attrs != null) {
                // 小窗口允许（工具类应用）
                if (attrs.width < 400 && attrs.height < 400) {
                    return true;
                }
                
                // 特定类型的窗口允许
                int type = attrs.type;
                if (type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY ||
                    type == WindowManager.LayoutParams.TYPE_TOAST ||
                    type == WindowManager.LayoutParams.TYPE_SYSTEM_ALERT) {
                    // 但如果是全屏覆盖层，不允许
                    if (attrs.width >= 1000 || attrs.height >= 1000) {
                        return false;
                    }
                    return true;
                }
            }
            
            // 4. 检查窗口标签
            String tag = String.valueOf(XposedHelpers.callMethod(clickedWindow, "getWindowTag"));
            if (tag != null) {
                // 允许特定工具类窗口
                if (tag.contains("Float") ||
                    tag.contains("Toast") ||
                    tag.contains("Popup") ||
                    tag.contains("Bubble") ||
                    tag.contains("Assistant")) {
                    return true;
                }
            }
            
            // 5. 默认不允许：保护前台应用焦点
            return false;
            
        } catch (Throwable ignored) {
        }
        return false;  // 出错时默认保护
    }

    /**
     * 钩子OPPO缩放窗口更新表面位置方法
     */
    private void hookOplusZoomWindow(final ClassLoader cl) {
        try {
            Class<?> transactionClass = XposedHelpers.findClass("android.view.SurfaceControl$Transaction", cl);
            XposedHelpers.findAndHookMethod(
                    "com.android.server.wm.WindowState",
                    cl,
                    "updateSurfacePosition",
                    transactionClass,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object winState = param.thisObject;
                            boolean isNeedHide = checkOplusWindowForHide(winState);
                            Object transaction = param.args[0];
                            Object surfaceControl = XposedHelpers.getObjectField(winState, "mSurfaceControl");
                            if (surfaceControl != null) {
                                XposedHelpers.callMethod(transaction, "setSkipScreenshot", surfaceControl, isNeedHide);
                            }

                            Object task = XposedHelpers.callMethod(winState, "getTask");
                            if (task != null) {
                                Object taskSurface = XposedHelpers.callMethod(task, "getSurfaceControl");
                                if (taskSurface != null) {
                                    XposedHelpers.callMethod(transaction, "setSkipScreenshot", taskSurface, isNeedHide);
                                }
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    /**
     * 检查窗口是否需要隐藏（OPPO特定逻辑）
     */
    private boolean checkOplusWindowForHide(Object winState) {
        try {
            String tag = String.valueOf(XposedHelpers.callMethod(winState, "getWindowTag"));
            String pkg = (String) XposedHelpers.callMethod(winState, "getOwningPackage");
            if ("com.oplus.screenshot/LongshotCapture".equals(tag)
                    || tag.contains("OplusOSZoomFloatHandleView")
                    || "InputMethod".equals(tag)
                    || "com.oplus.appplatform".equals(pkg)
                    || "com.coloros.smartsidebar".equals(pkg)) {
                return true;
            }
            try {
                Object ext = XposedHelpers.getObjectField(winState, "mWindowStateExt");
                if (ext != null) {
                    int mode = (int) XposedHelpers.callMethod(winState, "getWindowingMode");
                    if ((boolean)XposedHelpers.callMethod(ext, "checkIfWindowingModeZoom", mode)) return true;
                }
            } catch (Throwable ignored) {
            }
            Object task = XposedHelpers.callMethod(winState, "getTask");
            if (task == null) return false;
            Object rootTask = XposedHelpers.callMethod(task, "getRootTask");
            if (rootTask == null) rootTask = task;
            if (!isFlexibleTaskAndHasCaption(rootTask)) return false;
            Object wrapper = XposedHelpers.callMethod(rootTask, "getWrapper");
            if (wrapper == null) return false;
            Object extImpl = XposedHelpers.callMethod(wrapper, "getExtImpl");
            if (extImpl == null) return false;
            int flexibleZoomState = (int) XposedHelpers.callMethod(extImpl, "getFlexibleZoomState");
            if (flexibleZoomState != 0) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 检查窗口是否需要保护（截图保护）
     */
    private boolean checkWindowForHide(Object winState) {
        try {
            // 首先检查OPPO特定窗口
            if (checkOplusWindowForHide(winState)) {
                return true;
            }
            
            // 获取窗口所属包名
            String pkg = (String) XposedHelpers.callMethod(winState, "getOwningPackage");
            
            // 对所有第三方应用窗口进行截图保护
            if (pkg != null && !pkg.equals("android") && !pkg.startsWith("com.android.") 
                && !pkg.startsWith("com.coloros.") && !pkg.startsWith("com.oplus.")) {
                return true;
            }
            
        } catch (Throwable ignored) {
        }
        return false;
    }
    
    /**
     * 保护WindowState对应的视图不被截图
     */
    private void protectWindow(Object winState) {
        try {
            if (!checkWindowForHide(winState)) {
                return;
            }
            
            ClassLoader cl = winState.getClass().getClassLoader();
            
            // 优先使用ViewRootImpl进行保护
            Object vri = getViewRootImplFromWindowState(winState);
            if (vri != null) {
                handleDimming(vri);
                handleSecure(vri, cl);
            } 
            
            // 直接保护WindowState的SurfaceControl
            Object surfaceControl = getSurfaceControlFromWindowState(winState);
            if (surfaceControl != null) {
                Class<?> txnClass = XposedHelpers.findClass("android.view.SurfaceControl$Transaction", cl);
                Object txn = XposedHelpers.newInstance(txnClass);
                
                boolean applied = false;
                
                try {
                    XposedHelpers.callMethod(txn, "setSkipScreenshot", surfaceControl, true);
                    applied = true;
                } catch (Throwable ignored) {
                }
                
                if (!applied && Build.VERSION.SDK_INT < 33) {
                    try {
                        XposedHelpers.callMethod(txn, "setSecure", surfaceControl, true);
                    } catch (Throwable ignored) {
                    }
                }
                
                XposedHelpers.callMethod(txn, "apply");
            }
        } catch (Throwable ignored) {
        }
    }
    
    /**
     * 从WindowState获取SurfaceControl
     */
    private Object getSurfaceControlFromWindowState(Object winState) {
        try {
            String[] possibleFields = {
                "mSurfaceControl",
                "mLeash",
                "mSurfaceControlLocked"
            };
            
            for (String field : possibleFields) {
                try {
                    Object surfaceControl = XposedHelpers.getObjectField(winState, field);
                    if (surfaceControl != null && 
                        (boolean) XposedHelpers.callMethod(surfaceControl, "isValid")) {
                        return surfaceControl;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
    
    /**
     * 从WindowState获取ViewRootImpl
     */
    private Object getViewRootImplFromWindowState(Object winState) {
        try {
            // 方法1: 通过WindowToken
            Object windowToken = XposedHelpers.getObjectField(winState, "mToken");
            if (windowToken != null) {
                Object client = XposedHelpers.getObjectField(windowToken, "window");
                if (client != null) {
                    return XposedHelpers.getObjectField(client, "this$0");
                }
            }
            
            // 方法2: 通过Session
            try {
                Object session = XposedHelpers.getObjectField(winState, "mSession");
                if (session != null) {
                    Object viewRootImpl = XposedHelpers.getObjectField(session, "mViewRootImpl");
                    if (viewRootImpl != null) {
                        return viewRootImpl;
                    }
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 检查是否为灵活任务且有标题
     */
    private static boolean isFlexibleTaskAndHasCaption(Object rootTask) {
        ClassLoader cl = rootTask.getClass().getClassLoader();

        boolean isFlexibleTask = false;
        try {
            Class<?> flexUtilsClass = 
                    XposedHelpers.findClass("com.android.server.wm.FlexibleWindowUtils", cl);
            
            isFlexibleTask = (boolean) XposedHelpers.callStaticMethod(
                    flexUtilsClass,
                    "isFlexibleTaskAndHasCaption",
                    rootTask
            );
        } catch (Throwable ignored) {
        }
        return isFlexibleTask;
    }
}
