package com.dszsu.tss;

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
 * MainHook Xposed模块
 * 用于钩子Android系统中的视图和窗口管理功能，实现视图保护和窗口效果处理
 */
public class MainHook implements IXposedHookLoadPackage {

    /**
     * 存储受保护的视图
     * 使用IdentityHashMap基于对象引用而非equals()方法比较键
     */
    private static final Map<Object, Boolean> protectedViews =
            new IdentityHashMap<>();

    /**
     * 存储ViewRootImpl到Surface的映射
     * 用于跟踪视图对应的显示表面
     */
    private static final Map<Object, Object> vriToSurface =
            new IdentityHashMap<>();

    /**
     * 存储已处理过的dimming状态
     * 用于避免重复处理同一个窗口的变暗效果
     */
    private static final Map<Object, Boolean> dimHandled =
            new IdentityHashMap<>();

    /**
     * Xposed模块入口方法，当应用包加载时被调用
     * @param lpparam 加载包的参数，包含包名、类加载器等信息
     */
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // 获取当前加载的包名
        String pkg = lpparam.packageName;
        
        // 包名为空则直接返回
        if (pkg == null) return;
        
        // 钩子窗口管理全局类和视图根实现类（第三方应用）
        if (!pkg.startsWith("android") && !pkg.startsWith("com.android.systemui")) {
            hookWindowManagerGlobal(lpparam);
            hookViewRootImpl(lpparam);
        }
        
        // 钩子系统框架中的窗口焦点处理（对所有应用生效）
        // 注意：WindowState类是系统级的，只需在android包加载时钩子一次即可影响所有应用
        if (pkg.equals("android")) {
            hookHandleTapOutsideFocusInsideSelf(lpparam.classLoader);
        }
    }

    /**
     * 钩子窗口管理全局类
     * 用于跟踪添加到窗口管理器的视图
     * @param lpparam 加载包的参数
     */
    private void hookWindowManagerGlobal(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // 查找WindowManagerGlobal类
            Class<?> wmg =
                    XposedHelpers.findClass(
                            "android.view.WindowManagerGlobal",
                            lpparam.classLoader
                    );

            // 钩子WindowManagerGlobal类的所有addView方法
            XposedBridge.hookAllMethods(
                    wmg,
                    "addView",
                    new XC_MethodHook() {
                        /**
                         * 在addView方法执行前调用
                         * @param param 方法参数，包含thisObject、args等信息
                         */
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // 检查参数是否有效且第一个参数是View类型
                            if (param.args != null && param.args.length > 0 &&
                                    param.args[0] instanceof View) {
                                // 将视图添加到受保护列表
                                protectedViews.put(param.args[0], Boolean.TRUE);
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {
            // 忽略异常，避免影响系统运行
        }
    }

    /**
     * 钩子视图根实现类
     * 用于处理视图的变暗效果和安全属性
     * @param lpparam 加载包的参数
     */
    private void hookViewRootImpl(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // 查找ViewRootImpl类
            Class<?> vriClass =
                    XposedHelpers.findClass(
                            "android.view.ViewRootImpl",
                            lpparam.classLoader
                    );

            // 钩子relayoutWindow方法，用于处理窗口重布局
            XposedBridge.hookAllMethods(
                    vriClass,
                    "relayoutWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // 处理窗口变暗效果
                            handleDimming(param.thisObject);
                        }
                    }
            );

            // 钩子performTraversals方法，用于处理视图遍历
            XposedBridge.hookAllMethods(
                    vriClass,
                    "performTraversals",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // 获取当前ViewRootImpl对象
                            Object vri = param.thisObject;

                            // 获取视图对象
                            Object view;
                            try {
                                view = XposedHelpers.getObjectField(vri, "mView");
                            } catch (Throwable t) {
                                return;
                            }

                            // 仅对受保护视图执行完整处理
                            if (view == null || !protectedViews.containsKey(view)) return;

                            // 处理窗口变暗效果
                            handleDimming(vri);
                            
                            // 处理安全相关属性（防止截图）
                            handleSecure(vri, lpparam.classLoader);
                        }
                    }
            );
        } catch (Throwable ignored) {
            // 忽略异常，避免影响系统运行
        }
    }

    /**
     * 处理窗口变暗效果
     * 移除窗口的变暗背景
     * @param vri ViewRootImpl对象
     */
    private void handleDimming(Object vri) {
        try {
            // 检查是否已处理过该ViewRootImpl
            if (dimHandled.containsKey(vri)) return;

            // 获取窗口属性
            WindowManager.LayoutParams lp =
                    (WindowManager.LayoutParams)
                            XposedHelpers.getObjectField(vri, "mWindowAttributes");

            // 如果窗口设置了变暗效果，则移除该效果
            if (lp != null &&
                    (lp.flags & WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0) {
                // 清除变暗标志
                lp.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
                
                // 设置变暗量为0
                lp.dimAmount = 0f;
                
                // 标记为已处理
                dimHandled.put(vri, Boolean.TRUE);
            }
        } catch (Throwable ignored) {
            // 忽略异常，避免影响系统运行
        }
    }

    /**
     * 处理安全相关的Surface操作
     * 保护视图不被截图
     * @param vri ViewRootImpl对象
     * @param cl 类加载器
     */
    private void handleSecure(Object vri, ClassLoader cl) {
        try {
            // 查找有效的Surface对象
            Object sc = findValidSurface(vri);
            if (sc == null) return;

            // 同步更新ViewRootImpl到Surface的映射
            synchronized (vriToSurface) {
                Object last = vriToSurface.get(vri);
                
                // 如果Surface没有变化，则不需要重新处理
                if (last == sc) return;
                
                // 更新映射
                vriToSurface.put(vri, sc);
            }

            // 创建SurfaceControl.Transaction对象
            Class<?> txnClass =
                    XposedHelpers.findClass(
                            "android.view.SurfaceControl$Transaction",
                            cl
                    );
            Object txn = XposedHelpers.newInstance(txnClass);

            boolean applied = false;
            
            // 尝试使用setSkipScreenshot方法（Android 33+）
            try {
                XposedHelpers.callMethod(txn, "setSkipScreenshot", sc, true);
                applied = true;
            } catch (Throwable ignored) {
                // 忽略异常，尝试其他方法
            }

            // 如果setSkipScreenshot失败且Android版本低于33，则使用setSecure方法
            if (!applied && Build.VERSION.SDK_INT < 33) {
                try {
                    XposedHelpers.callMethod(txn, "setSecure", sc, true);
                } catch (Throwable ignored) {
                    // 忽略异常
                }
            }

            // 应用事务
            XposedHelpers.callMethod(txn, "apply");
        } catch (Throwable ignored) {
            // 忽略异常，避免影响系统运行
        }
    }

    /**
     * 查找有效的Surface对象
     * 在ViewRootImpl中按优先级查找多个可能的Surface字段
     * @param vri ViewRootImpl对象
     * @return 有效的Surface对象，或null
     */
    private Object findValidSurface(Object vri) {
        // 可能包含Surface的字段列表，按优先级排序
        String[] fields = {
                "mSurfaceControl",       // 主要Surface控制对象
                "mLeash",               //  leash Surface
                "mSurfaceControlLocked", // 锁定的Surface控制对象
                "mSurface"               // 基础Surface对象
        };

        // 遍历字段列表，查找有效的Surface
        for (String f : fields) {
            try {
                // 获取字段值
                Object sc = XposedHelpers.getObjectField(vri, f);
                
                // 检查Surface是否有效
                if (sc != null &&
                        (boolean) XposedHelpers.callMethod(sc, "isValid")) {
                    return sc;
                }
            } catch (Throwable ignored) {
                // 忽略异常，继续检查下一个字段
            }
        }
        
        // 没有找到有效的Surface
        return null;
    }

    /**
     * 钩子窗口焦点外点击事件处理方法
     * 用于拦截特定窗口在焦点外点击时的默认行为，并保护对应的视图不被截图
     * @param cl 系统类加载器
     */
    private void hookHandleTapOutsideFocusInsideSelf(final ClassLoader cl) {
        try {
            // 钩子com.android.server.wm.WindowState类的handleTapOutsideFocusInsideSelf方法
            XposedHelpers.findAndHookMethod(
                    "com.android.server.wm.WindowState", // 目标类名
                    cl, // 类加载器
                    "handleTapOutsideFocusInsideSelf", // 目标方法名
                    new XC_MethodHook() { // 方法钩子回调
                        /**
                         * 在被钩子方法执行前调用
                         * @param param 方法参数，包含thisObject、args等信息
                         * @throws Throwable 异常信息
                         */
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            // 获取当前WindowState对象
                            Object winState = param.thisObject;
                            
                            // 使用视图保护方法替代原来的焦点拦截逻辑
                            protectWindow(winState);
                            
                            // 仍然保留原有的焦点拦截逻辑，确保兼容性
                            if (checkWindowForHide(winState)) {
                                // 获取WindowManagerService对象
                                Object wmService = XposedHelpers.getObjectField(winState, "mWmService");
                                
                                // 获取当前获得焦点的窗口
                                Object currentFocusedWindow = XposedHelpers.callMethod(wmService, "getFocusedWindowLocked");
                                
                                // 如果当前有焦点窗口且该窗口不需要隐藏
                                if (currentFocusedWindow != null && !checkWindowForHide(currentFocusedWindow)) {
                                    // 获取显示ID
                                    int displayId = (int) XposedHelpers.callMethod(winState, "getDisplayId");
                                    
                                    // 将当前显示移动到顶部
                                    XposedHelpers.callMethod(wmService, "moveDisplayToTopInternal", displayId);
                                    
                                    // 设置方法结果为null，阻止原方法执行（核心拦截逻辑）
                                    param.setResult(null);
                                    return;
                                } else {
                                    // 没有有效的焦点窗口可维护，阻止所有操作
                                    param.setResult(null);
                                }
                            }
                        }
                    });
        } catch (Throwable ignored) {
            // 记录钩子失败日志
            XposedBridge.log("MainHook: Hook handleTapOutsideFocusInsideSelf failed.");
        }
    }

    /**
     * 检查窗口是否需要保护
     * @param winState WindowState对象
     * @return 是否需要保护窗口
     */
    private boolean checkWindowForHide(Object winState) {
        try {
            // 获取窗口标签
            String tag = String.valueOf(XposedHelpers.callMethod(winState, "getWindowTag"));
            
            // 获取窗口所属包名
            String pkg = (String) XposedHelpers.callMethod(winState, "getOwningPackage");
            
            // 检查特定窗口标签或包名
            if ("com.oplus.screenshot/LongshotCapture".equals(tag) // 长截图捕获窗口
                    || tag.contains("OplusOSZoomFloatHandleView") // OPPO缩放浮动手柄视图
                    || "InputMethod".equals(tag) // 输入法窗口
                    || "com.oplus.appplatform".equals(pkg) // OPPO应用平台
                    || "com.coloros.smartsidebar".equals(pkg)) { // ColorOS智能侧边栏
                return true;
            }
            
            // 对所有第三方应用窗口也进行焦点拦截
            if (pkg != null && !pkg.equals("android") && !pkg.startsWith("com.android.") && !pkg.startsWith("com.coloros.") && !pkg.startsWith("com.oplus.")) {
                return true;
            }
            
            // 检查窗口扩展是否为缩放模式
            try {
                // 获取WindowState的扩展对象
                Object ext = XposedHelpers.getObjectField(winState, "mWindowStateExt");
                if (ext != null) {
                    // 获取窗口模式
                    int mode = (int) XposedHelpers.callMethod(winState, "getWindowingMode");
                    
                    // 检查窗口模式是否为缩放模式
                    if ((boolean)XposedHelpers.callMethod(ext, "checkIfWindowingModeZoom", mode)) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
                // 忽略异常，继续检查其他条件
            }
            
            // 检查灵活任务相关状态
            Object task = XposedHelpers.callMethod(winState, "getTask");
            if (task == null) return false;
            
            // 获取根任务
            Object rootTask = XposedHelpers.callMethod(task, "getRootTask");
            if (rootTask == null) rootTask = task;
            
            // 检查是否为灵活任务且有标题
            if (!isFlexibleTaskAndHasCaption(rootTask)) return false;
            
            // 获取任务包装器
            Object wrapper = XposedHelpers.callMethod(rootTask, "getWrapper");
            if (wrapper == null) return false;
            
            // 获取扩展实现
            Object extImpl = XposedHelpers.callMethod(wrapper, "getExtImpl");
            if (extImpl == null) return false;
            
            // 获取灵活缩放状态
            int flexibleZoomState = (int) XposedHelpers.callMethod(extImpl, "getFlexibleZoomState");
            
            // 如果缩放状态不为0，则需要隐藏
            if (flexibleZoomState != 0) return true;
        } catch (Throwable ignored) {
            // 忽略所有异常，返回false
        }
        return false;
    }
    
    /**
     * 从WindowState对象获取对应的ViewRootImpl
     * @param winState WindowState对象
     * @return ViewRootImpl对象，或null
     */
    private Object getViewRootImplFromWindowState(Object winState) {
        try {
            // 尝试通过不同的路径获取ViewRootImpl
            
            // 方法1: 通过WindowState -> SurfaceControl -> ViewRootImpl
            Object surfaceControl = XposedHelpers.getObjectField(winState, "mSurfaceControl");
            if (surfaceControl != null) {
                // 尝试通过SurfaceControl找到对应的ViewRootImpl
                // 这部分可能需要更复杂的反射逻辑，取决于系统版本
                return surfaceControl;
            }
            
            // 方法2: 通过WindowState -> WindowToken -> IWindow
            Object windowToken = XposedHelpers.getObjectField(winState, "mToken");
            if (windowToken != null) {
                Object client = XposedHelpers.getObjectField(windowToken, "window");
                if (client != null) {
                    // IWindow的实现类通常是ViewRootImpl的内部类
                    return XposedHelpers.getObjectField(client, "this$0");
                }
            }
            
            // 方法3: 直接查找可能的ViewRootImpl引用字段
            String[] possibleFields = {"mViewRootImpl", "mView", "mClient"};
            for (String field : possibleFields) {
                try {
                    Object result = XposedHelpers.getObjectField(winState, field);
                    if (result != null) {
                        return result;
                    }
                } catch (Throwable ignored) {
                    // 忽略异常，继续尝试其他字段
                }
            }
        } catch (Throwable ignored) {
            // 忽略所有异常
        }
        return null;
    }
    
    /**
     * 保护WindowState对应的视图不被截图
     * @param winState WindowState对象
     */
    private void protectWindow(Object winState) {
        try {
            // 1. 检查窗口是否需要保护
            if (!checkWindowForHide(winState)) {
                return;
            }
            
            // 2. 获取ViewRootImpl对象
            Object vri = getViewRootImplFromWindowState(winState);
            if (vri == null) {
                // 如果无法获取ViewRootImpl，尝试使用WindowState的类加载器
                ClassLoader cl = winState.getClass().getClassLoader();
                
                // 3. 直接使用WindowState的SurfaceControl进行保护
                Object surfaceControl = XposedHelpers.getObjectField(winState, "mSurfaceControl");
                if (surfaceControl != null) {
                    // 创建SurfaceControl.Transaction对象
                    Class<?> txnClass = XposedHelpers.findClass("android.view.SurfaceControl$Transaction", cl);
                    Object txn = XposedHelpers.newInstance(txnClass);
                    
                    boolean applied = false;
                    
                    // 尝试使用setSkipScreenshot方法（Android 33+）
                    try {
                        XposedHelpers.callMethod(txn, "setSkipScreenshot", surfaceControl, true);
                        applied = true;
                    } catch (Throwable ignored) {
                        // 忽略异常，尝试其他方法
                    }
                    
                    // 如果setSkipScreenshot失败且Android版本低于33，则使用setSecure方法
                    if (!applied && Build.VERSION.SDK_INT < 33) {
                        try {
                            XposedHelpers.callMethod(txn, "setSecure", surfaceControl, true);
                        } catch (Throwable ignored) {
                            // 忽略异常
                        }
                    }
                    
                    // 应用事务
                    XposedHelpers.callMethod(txn, "apply");
                }
            } else {
                // 4. 使用标准的视图保护方法
                ClassLoader cl = vri.getClass().getClassLoader();
                handleSecure(vri, cl);
            }
        } catch (Throwable ignored) {
            // 忽略所有异常
        }
    }

    /**
     * 检查是否为灵活任务且有标题
     * @param rootTask 根任务对象
     * @return 是否为灵活任务且有标题
     */
    private static boolean isFlexibleTaskAndHasCaption(Object rootTask) {
        // 获取根任务的类加载器
        ClassLoader cl = rootTask.getClass().getClassLoader();

        boolean isFlexibleTask = false;
        try {
            // 找到灵活窗口工具类
            Class<?> flexUtilsClass = 
                    XposedHelpers.findClass("com.android.server.wm.FlexibleWindowUtils", cl);
            
            // 调用静态方法检查是否为灵活任务且有标题
            isFlexibleTask = (boolean) XposedHelpers.callStaticMethod(
                    flexUtilsClass,
                    "isFlexibleTaskAndHasCaption",
                    rootTask
            );
        } catch (Throwable ignored) {
            // 忽略异常，返回false
        }
        return isFlexibleTask;
    }
}
