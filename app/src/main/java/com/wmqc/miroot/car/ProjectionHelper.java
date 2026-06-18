
package com.wmqc.miroot.car;

import com.wmqc.miroot.lyrics.LogHelper;
import com.wmqc.miroot.lyrics.ITaskService;
import com.wmqc.miroot.lyrics.RearScreenLyricsActivity;
import com.wmqc.miroot.rear.OfficialSubscreenMiRootProjectionSession;
import com.wmqc.miroot.rear.RearActivityLaunchSpec;
import com.wmqc.miroot.rear.RearProjectionLaunchSequence;
import android.content.Context;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * 投屏辅助工具类
 * 统一管理音乐投屏和车控投屏的启动流程，确保广播启动和按钮启动行为一致
 */
public class ProjectionHelper {
    private static final String TAG = "ProjectionHelper";

    private static final ExecutorService POST_LAUNCH_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MiRoot-CarProjPost");
        t.setDaemon(true);
        return t;
    });

    private static boolean isDeferredPostLaunchContext(Context context) {
        return context instanceof android.app.IntentService
            || context instanceof android.app.Service;
    }

    /**
     * 启动音乐投屏的完整流程（可复用）
     * @param taskService TaskService实例
     * @param context Context
     * @return 是否启动成功
     */
    public static boolean startMusicProjection(ITaskService taskService, Context context) {
        if (taskService == null) {
            LogHelper.e(TAG, "❌ TaskService为null，无法启动音乐投屏");
            return false;
        }

        try {
            LogHelper.d(TAG, "🚀 开始启动音乐投屏（统一流程）");

            // 检查是否已有音乐投屏在运行
            RearScreenLyricsActivity existingActivity = RearScreenLyricsActivity.getCurrentInstance();
            if (existingActivity != null) {
                try {
                    boolean isFinishing = existingActivity.isFinishing();
                    boolean isDestroyed = false;
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
                        isDestroyed = existingActivity.isDestroyed();
                    }
                    
                    int displayId = 0;
                    boolean hasUI = false;
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        try {
                            displayId = existingActivity.getDisplay().getDisplayId();
                        } catch (Exception e) {
                            LogHelper.w(TAG, "获取displayId失败", e);
                        }
                    }
                    
                    // 检查UI是否已创建（通过反射检查lyricsView是否存在）
                    try {
                        java.lang.reflect.Field lyricsViewField = RearScreenLyricsActivity.class.getDeclaredField("lyricsView");
                        lyricsViewField.setAccessible(true);
                        Object lyricsView = lyricsViewField.get(existingActivity);
                        hasUI = (lyricsView != null);
                    } catch (Exception e) {
                        LogHelper.w(TAG, "检查UI状态失败", e);
                        // 如果反射失败，假设UI未创建，继续启动流程
                        hasUI = false;
                    }
                    
                    // 只有在背屏、未销毁、且UI已创建时才认为正在运行
                    if (!isFinishing && !isDestroyed && displayId == 1 && hasUI) {
                        LogHelper.d(TAG, "⚠ 音乐投屏已在运行（在背屏且UI已创建），忽略请求");
                        return false;
                    } else {
                        LogHelper.w(TAG, String.format("⚠️ 检测到无效的Activity实例（isFinishing=%s, isDestroyed=%s, displayId=%d, hasUI=%s），清除并重新启动", 
                            isFinishing, isDestroyed, displayId, hasUI));
                        clearActivityInstance(RearScreenLyricsActivity.class, existingActivity);
                    }
                } catch (Exception e) {
                    LogHelper.e(TAG, "❌ 检查Activity状态失败", e);
                    clearActivityInstance(RearScreenLyricsActivity.class, existingActivity);
                }
            }

            String componentName = context.getPackageName() + "/" + RearScreenLyricsActivity.class.getName();
            boolean isBroadcast = (context instanceof android.app.IntentService) ||
                                  (context instanceof android.app.Service);
            RearActivityLaunchSpec launchSpec = RearProjectionLaunchSequence.mirootProjectionLaunchSpec(
                    "RearScreenLyricsActivity", componentName, isBroadcast);
            LogHelper.d(TAG, "步骤1: 背屏直启优先，失败则主屏占位迁屏(3.4)");
            if (!RearProjectionLaunchSequence.runPreferDirectRearLaunchWithPlaceholderFallback(
                    context, taskService, launchSpec, null)) {
                LogHelper.e(TAG, "❌ 音乐投屏启动失败（直启与占位迁屏均失败）");
                return false;
            }
            LogHelper.d(TAG, "✓ 音乐投屏已在背屏启动");

            LogHelper.d(TAG, "步骤2: 等待Activity在背屏创建并显示UI");
            Thread.sleep(800); // 初始等待时间
            
            // 检查UI是否已创建，如果未创建则继续等待（最多等待3秒）
            int uiCheckAttempts = 0;
            int maxUICheckAttempts = 11; // 最多检查11次，每次200ms，总共最多2.2秒
            boolean uiCreated = false;
            while (!uiCreated && uiCheckAttempts < maxUICheckAttempts) {
                RearScreenLyricsActivity activity = RearScreenLyricsActivity.getCurrentInstance();
                if (activity != null) {
                    try {
                        // 通过反射检查lyricsView是否已创建
                        java.lang.reflect.Field lyricsViewField = RearScreenLyricsActivity.class.getDeclaredField("lyricsView");
                        lyricsViewField.setAccessible(true);
                        Object lyricsView = lyricsViewField.get(activity);
                        if (lyricsView != null) {
                            uiCreated = true;
                            LogHelper.d(TAG, "✅ UI已创建（检查" + (uiCheckAttempts + 1) + "次）");
                            break;
                        }
                    } catch (Exception e) {
                        // 忽略反射异常，继续检查
                    }
                }
                
                if (!uiCreated) {
                    uiCheckAttempts++;
                    if (uiCheckAttempts < maxUICheckAttempts) {
                        Thread.sleep(200); // 每次等待200ms
                        LogHelper.d(TAG, "⏳ UI未创建，继续等待... (" + uiCheckAttempts + "/" + maxUICheckAttempts + ")");
                    }
                }
            }
            
            if (!uiCreated) {
                LogHelper.w(TAG, "⚠️ UI创建超时，但继续执行后续步骤");
            }

            LogHelper.d(TAG, "步骤3: 等待 TaskService 后触发屏蔽官方手势（对齐 3.4）");
            waitAndTriggerDisableGesture(RearScreenLyricsActivity.class, 3000);

            LogHelper.d(TAG, "✅ 音乐投屏启动流程完成");
            return true;

        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 启动音乐投屏失败", e);
            OfficialSubscreenMiRootProjectionSession.release(context.getApplicationContext(), taskService);
            return false;
        }
    }

    /**
     * 启动车控投屏：优先背屏直启，失败则主屏占位迁屏（3.4）。
     */
    public static boolean startCarControlProjection(ITaskService taskService, Context context) {
        if (taskService == null) {
            LogHelper.e(TAG, "❌ TaskService为null，无法启动车控投屏");
            return false;
        }

        try {
            LogHelper.d(TAG, "🚀 开始启动车控投屏（背屏直启优先，失败占位迁屏）");

            RearScreenCarControlActivity existingActivity = RearScreenCarControlActivity.getCurrentInstance();
            if (existingActivity != null) {
                try {
                    boolean isFinishing = existingActivity.isFinishing();
                    boolean isDestroyed = false;
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
                        isDestroyed = existingActivity.isDestroyed();
                    }

                    int displayId = 0;
                    boolean hasUI = false;
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        try {
                            displayId = existingActivity.getDisplay().getDisplayId();
                        } catch (Exception e) {
                            LogHelper.w(TAG, "获取displayId失败", e);
                        }
                    }

                    try {
                        java.lang.reflect.Field mainLayoutField =
                                RearScreenCarControlActivity.class.getDeclaredField("mainLayout");
                        mainLayoutField.setAccessible(true);
                        Object mainLayout = mainLayoutField.get(existingActivity);
                        hasUI = (mainLayout != null);
                    } catch (Exception e) {
                        LogHelper.w(TAG, "检查UI状态失败", e);
                        hasUI = false;
                    }

                    if (!isFinishing && !isDestroyed && displayId == 1 && hasUI) {
                        LogHelper.d(TAG, "⚠ 车控投屏已在运行（背屏且 UI 已创建），忽略重复请求");
                        return false;
                    } else {
                        LogHelper.w(TAG, String.format(
                                "⚠️ 清除无效车控实例（isFinishing=%s, isDestroyed=%s, displayId=%d, hasUI=%s）",
                                isFinishing, isDestroyed, displayId, hasUI));
                        clearActivityInstance(RearScreenCarControlActivity.class, existingActivity);
                    }
                } catch (Exception e) {
                    LogHelper.e(TAG, "❌ 检查Activity状态失败", e);
                    clearActivityInstance(RearScreenCarControlActivity.class, existingActivity);
                }
            }

            final String componentName = context.getPackageName() + "/" + RearScreenCarControlActivity.class.getName();

            RearActivityLaunchSpec launchSpec = RearProjectionLaunchSequence.mirootProjectionLaunchSpec(
                    "RearScreenCarControlActivity", componentName, false);
            LogHelper.d(TAG, "步骤1: 背屏直启优先，失败则主屏占位迁屏(3.4)");
            if (!RearProjectionLaunchSequence.runPreferDirectRearLaunchWithPlaceholderFallback(
                    context, taskService, launchSpec, null)) {
                LogHelper.e(TAG, "❌ 车控投屏启动失败（直启与占位迁屏均失败）");
                return false;
            }
            LogHelper.d(TAG, "✓ 车控投屏已在背屏启动");

            if (isDeferredPostLaunchContext(context)) {
                LogHelper.d(TAG, "快速路径：后台巩固 UI/手势（不阻塞广播/手势）");
                scheduleDeferredPostLaunchSteps(RearScreenCarControlActivity.class, "mainLayout", 700L);
                return true;
            }

            LogHelper.d(TAG, "步骤2: 等待Activity在背屏创建并显示UI");
            final long uiPollInitialMs = 200L;
            final long uiPollStepMs = 60L;
            final int maxUICheckAttempts = 18;
            Thread.sleep(uiPollInitialMs);

            int uiCheckAttempts = 0;
            boolean uiCreated = false;
            while (!uiCreated && uiCheckAttempts < maxUICheckAttempts) {
                RearScreenCarControlActivity activity = RearScreenCarControlActivity.getCurrentInstance();
                if (uiCreatedForField(activity, "mainLayout")) {
                    uiCreated = true;
                    LogHelper.d(TAG, "✅ UI已创建（检查" + (uiCheckAttempts + 1) + "次）");
                    break;
                }
                uiCheckAttempts++;
                if (uiCheckAttempts < maxUICheckAttempts) {
                    Thread.sleep(uiPollStepMs);
                    LogHelper.d(TAG, "⏳ UI未创建，继续等待... (" + uiCheckAttempts + "/" + maxUICheckAttempts + ")");
                }
            }
            if (!uiCreated) {
                LogHelper.w(TAG, "⚠️ UI创建超时，但继续执行后续步骤");
            }

            LogHelper.d(TAG, "步骤3: 等待 TaskService 后触发屏蔽官方手势（对齐 3.4）");
            waitAndTriggerDisableGesture(RearScreenCarControlActivity.class, 2000);

            LogHelper.d(TAG, "✅ 车控投屏启动流程完成");
            return true;

        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 启动车控投屏失败", e);
            OfficialSubscreenMiRootProjectionSession.release(context.getApplicationContext(), taskService);
            return false;
        }
    }

    private static void scheduleDeferredPostLaunchSteps(
            Class<?> activityClass,
            String uiFieldName,
            long maxGestureWaitMs) {
        POST_LAUNCH_EXECUTOR.execute(() -> {
            try {
                final long uiDeadline = System.currentTimeMillis() + 600L;
                while (System.currentTimeMillis() < uiDeadline) {
                    Object act = getCurrentActivityInstance(activityClass);
                    if (act != null && uiCreatedForField(act, uiFieldName)) {
                        break;
                    }
                    Thread.sleep(40L);
                }
                waitAndTriggerDisableGesture(activityClass, maxGestureWaitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LogHelper.w(TAG, "deferred post-launch: " + e.getMessage());
            }
        });
    }

    private static Object getCurrentActivityInstance(Class<?> activityClass) {
        try {
            java.lang.reflect.Field f = activityClass.getDeclaredField("currentInstance");
            f.setAccessible(true);
            return f.get(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean uiCreatedForField(Object activity, String fieldName) {
        if (activity == null) {
            return false;
        }
        try {
            java.lang.reflect.Field f = activity.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(activity) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static void waitAndTriggerDisableGesture(Class<?> activityClass, long maxWaitMs) {
        try {
            long startTime = System.currentTimeMillis();
            Object activity = null;
            long activityWaitMs = Math.min(500, maxWaitMs);
            while (System.currentTimeMillis() - startTime < activityWaitMs) {
                try {
                    java.lang.reflect.Field currentInstanceField = activityClass.getDeclaredField("currentInstance");
                    currentInstanceField.setAccessible(true);
                    activity = currentInstanceField.get(null);
                    if (activity != null) {
                        LogHelper.d(TAG, "✅ Activity已创建，开始等待TaskService连接");
                        break;
                    }
                } catch (Exception e) {
                    // ignore
                }
                Thread.sleep(100);
            }
            if (activity == null) {
                LogHelper.w(TAG, "⚠️ 等待超时，Activity未创建，跳过反射触发屏蔽手势");
                return;
            }
            long elapsed = System.currentTimeMillis() - startTime;
            long remainingTime = maxWaitMs - elapsed;
            long taskServiceWaitMs = Math.max(2000, Math.min(maxWaitMs - 500, remainingTime));
            LogHelper.d(TAG, "⏳ 等待TaskService连接（最多" + taskServiceWaitMs + "ms）");
            long taskServiceStartTime = System.currentTimeMillis();
            int checkCount = 0;
            while (System.currentTimeMillis() - taskServiceStartTime < taskServiceWaitMs) {
                try {
                    java.lang.reflect.Field taskServiceField = activityClass.getDeclaredField("taskService");
                    taskServiceField.setAccessible(true);
                    Object ts = taskServiceField.get(activity);
                    checkCount++;
                    if (checkCount % 5 == 0) {
                        LogHelper.d(TAG, "⏳ 等待TaskService连接中... (已检查 " + checkCount + " 次)");
                    }
                    if (ts != null) {
                        Method checkMethod = activityClass.getDeclaredMethod("checkAndDisableOfficialGesture");
                        checkMethod.setAccessible(true);
                        checkMethod.invoke(activity);
                        return;
                    }
                } catch (NoSuchFieldException e) {
                    try {
                        Method checkMethod = activityClass.getDeclaredMethod("checkAndDisableOfficialGesture");
                        checkMethod.setAccessible(true);
                        checkMethod.invoke(activity);
                        return;
                    } catch (Exception e2) {
                        LogHelper.w(TAG, "直接调用 checkAndDisableOfficialGesture 失败: " + e2.getMessage());
                    }
                } catch (Exception e) {
                    if (checkCount % 10 == 0) {
                        LogHelper.d(TAG, "⏳ 等待TaskService: " + e.getClass().getSimpleName());
                    }
                }
                Thread.sleep(100);
            }
            try {
                Method checkMethod = activityClass.getDeclaredMethod("checkAndDisableOfficialGesture");
                checkMethod.setAccessible(true);
                checkMethod.invoke(activity);
            } catch (Exception e) {
                LogHelper.w(TAG, "超时后调用 checkAndDisableOfficialGesture 失败: " + e.getMessage());
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "waitAndTriggerDisableGesture: " + e.getMessage());
        }
    }

    public static boolean isCarControlProjectionUiActive() {
        return isCarControlOnRearWithMainUi();
    }

    private static boolean isCarControlOnRearWithMainUi() {
        RearScreenCarControlActivity a = RearScreenCarControlActivity.getCurrentInstance();
        if (a == null) {
            return false;
        }
        if (a.isFinishing()) {
            return false;
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1 && a.isDestroyed()) {
            return false;
        }
        int displayId = 0;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                displayId = a.getDisplay().getDisplayId();
            } catch (Exception e) {
                return false;
            }
        }
        if (displayId != 1) {
            return false;
        }
        try {
            java.lang.reflect.Field f = RearScreenCarControlActivity.class.getDeclaredField("mainLayout");
            f.setAccessible(true);
            return f.get(a) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static String parseCarControlTaskIdFromStackList(String stackListOutput) {
        if (stackListOutput == null || stackListOutput.trim().isEmpty()) {
            return null;
        }
        String[] lines = stackListOutput.split("\n");
        Pattern taskIdPattern = Pattern.compile("taskId=(\\d+)");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.contains("taskId=")) {
                continue;
            }
            // 仅匹配背屏车控 Activity；不可使用宽泛的 "CarControl"（会误匹配 CarControlSettingsActivity，
            // 导致 moveTaskToDisplay 把整个设置任务迁到背屏）。
            if (!trimmed.contains("RearScreenCarControlActivity")) {
                continue;
            }
            Matcher matcher = taskIdPattern.matcher(trimmed);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    /**
     * 从 am stack list 完整输出中解析音乐投屏 Activity 的 taskId
     * 兼容不同 ROM 输出格式（有的只显示包名或类名简写）
     * @param stackListOutput am stack list 命令的完整输出
     * @return taskId 字符串，未找到返回 null
     */
    private static String parseMusicProjectionTaskIdFromStackList(String stackListOutput) {
        if (stackListOutput == null || stackListOutput.trim().isEmpty()) return null;
        String[] lines = stackListOutput.split("\n");
        Pattern taskIdPattern = Pattern.compile("taskId=(\\d+)");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.contains("taskId=")) continue;
            boolean isLyricsActivity = trimmed.contains("RearScreenLyricsActivity")
                || (trimmed.contains("com.wmqc.miroot") && trimmed.contains("Lyrics"));
            if (!isLyricsActivity) continue;
            Matcher matcher = taskIdPattern.matcher(trimmed);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    /**
     * 获取音乐投屏界面的stackId
     * @param taskService TaskService实例
     * @return stackId，失败返回-1
     */
    public static int getMusicProjectionStackId(ITaskService taskService) {
        if (taskService == null) {
            LogHelper.e(TAG, "❌ TaskService为null，无法获取stackId");
            return -1;
        }

        try {
            LogHelper.d(TAG, "🔍 开始获取音乐投屏的stackId");
            
            // 执行am stack list命令
            String result = taskService.executeShellCommandWithResult("am stack list");
            if (result == null || result.trim().isEmpty()) {
                LogHelper.e(TAG, "❌ am stack list返回为空");
                return -1;
            }
            
            // 解析输出，查找包含RearScreenLyricsActivity的stackId
            // 输出格式示例：
            // RootTask #0: displayId=0
            //   stackId=0: bounds=[0,0][1080,2400] userId=0
            //     taskId=1234: com.wmqc.miroot/com.wmqc.miroot.lyrics.RearScreenLyricsActivity
            
            String[] lines = result.split("\n");
            String currentStackId = null;
            boolean foundMusicProjection = false;
            
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                
                // 解析stackId行（在taskId行之前）
                if (line.startsWith("stackId=")) {
                    int start = line.indexOf("stackId=") + 8;
                    int end = line.indexOf(':', start);
                    if (end > start) {
                        currentStackId = line.substring(start, end).trim();
                        LogHelper.d(TAG, "📋 找到stackId: " + currentStackId);
                    }
                    continue;
                }
                
                // 检查taskId行是否包含RearScreenLyricsActivity
                if (line.contains("taskId=") && line.contains("RearScreenLyricsActivity")) {
                    foundMusicProjection = true;
                    LogHelper.d(TAG, "🎯 找到音乐投屏Activity: " + line);
                    // 如果找到了音乐投屏Activity，使用当前记录的stackId
                    break;
                }
                
                // 如果遇到新的RootTask或stackId行，重置currentStackId（但保留之前的值，因为taskId可能在后面）
                // 注意：这里不重置，因为taskId行应该在stackId行之后
            }
            
            if (foundMusicProjection && currentStackId != null) {
                try {
                    int stackId = Integer.parseInt(currentStackId);
                    LogHelper.d(TAG, "✅ 成功获取音乐投屏stackId=" + stackId);
                    return stackId;
                } catch (NumberFormatException e) {
                    LogHelper.e(TAG, "❌ stackId格式错误: " + currentStackId, e);
                    return -1;
                }
            } else {
                // 如果没找到，尝试反向查找：先找到taskId行，然后向上查找stackId行
                LogHelper.d(TAG, "⚠️ 正向查找失败，尝试反向查找");
                for (int i = lines.length - 1; i >= 0; i--) {
                    String line = lines[i].trim();
                    if (line.contains("taskId=") && line.contains("RearScreenLyricsActivity")) {
                        LogHelper.d(TAG, "🎯 反向找到音乐投屏Activity: " + line);
                        // 向上查找最近的stackId行
                        for (int j = i - 1; j >= 0; j--) {
                            String prevLine = lines[j].trim();
                            if (prevLine.startsWith("stackId=")) {
                                int start = prevLine.indexOf("stackId=") + 8;
                                int end = prevLine.indexOf(':', start);
                                if (end > start) {
                                    String stackIdStr = prevLine.substring(start, end).trim();
                                    try {
                                        int stackId = Integer.parseInt(stackIdStr);
                                        LogHelper.d(TAG, "✅ 反向查找成功获取音乐投屏stackId=" + stackId);
                                        return stackId;
                                    } catch (NumberFormatException e) {
                                        LogHelper.e(TAG, "❌ stackId格式错误: " + stackIdStr, e);
                                    }
                                }
                                break;
                            }
                            // 如果遇到RootTask行，停止查找
                            if (prevLine.startsWith("RootTask")) {
                                break;
                            }
                        }
                        break;
                    }
                }
                LogHelper.w(TAG, "⚠️ 未找到音乐投屏的stackId");
                return -1;
            }
            
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 获取音乐投屏stackId失败", e);
            return -1;
        }
    }

    /**
     * 获取车控投屏界面的stackId
     * @param taskService TaskService实例
     * @return stackId，失败返回-1
     */
    public static int getCarControlStackId(ITaskService taskService) {
        if (taskService == null) {
            LogHelper.e(TAG, "❌ TaskService为null，无法获取stackId");
            return -1;
        }

        try {
            LogHelper.d(TAG, "🔍 开始获取车控投屏的stackId");
            
            // 执行am stack list命令
            String result = taskService.executeShellCommandWithResult("am stack list");
            if (result == null || result.trim().isEmpty()) {
                LogHelper.e(TAG, "❌ am stack list返回为空");
                return -1;
            }
            
            // 解析输出，查找包含RearScreenCarControlActivity的stackId
            // 输出格式示例：
            // RootTask #0: displayId=0
            //   stackId=0: bounds=[0,0][1080,2400] userId=0
            //     taskId=1234: com.wmqc.miroot/com.wmqc.miroot.car.RearScreenCarControlActivity
            
            String[] lines = result.split("\n");
            String currentStackId = null;
            boolean foundCarControl = false;
            
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                
                // 解析stackId行（在taskId行之前）
                if (line.startsWith("stackId=")) {
                    int start = line.indexOf("stackId=") + 8;
                    int end = line.indexOf(':', start);
                    if (end > start) {
                        currentStackId = line.substring(start, end).trim();
                        LogHelper.d(TAG, "📋 找到stackId: " + currentStackId);
                    }
                    continue;
                }
                
                // 检查taskId行是否包含RearScreenCarControlActivity
                if (line.contains("taskId=") && line.contains("RearScreenCarControlActivity")) {
                    foundCarControl = true;
                    LogHelper.d(TAG, "🎯 找到车控Activity: " + line);
                    // 如果找到了车控Activity，使用当前记录的stackId
                    break;
                }
                
                // 如果遇到新的RootTask或stackId行，重置currentStackId（但保留之前的值，因为taskId可能在后面）
                // 注意：这里不重置，因为taskId行应该在stackId行之后
            }
            
            if (foundCarControl && currentStackId != null) {
                try {
                    int stackId = Integer.parseInt(currentStackId);
                    LogHelper.d(TAG, "✅ 成功获取车控投屏stackId=" + stackId);
                    return stackId;
                } catch (NumberFormatException e) {
                    LogHelper.e(TAG, "❌ stackId格式错误: " + currentStackId, e);
                    return -1;
                }
            } else {
                // 如果没找到，尝试反向查找：先找到taskId行，然后向上查找stackId行
                LogHelper.d(TAG, "⚠️ 正向查找失败，尝试反向查找");
                for (int i = lines.length - 1; i >= 0; i--) {
                    String line = lines[i].trim();
                    if (line.contains("taskId=") && line.contains("RearScreenCarControlActivity")) {
                        LogHelper.d(TAG, "🎯 反向找到车控Activity: " + line);
                        // 向上查找最近的stackId行
                        for (int j = i - 1; j >= 0; j--) {
                            String prevLine = lines[j].trim();
                            if (prevLine.startsWith("stackId=")) {
                                int start = prevLine.indexOf("stackId=") + 8;
                                int end = prevLine.indexOf(':', start);
                                if (end > start) {
                                    String stackIdStr = prevLine.substring(start, end).trim();
                                    try {
                                        int stackId = Integer.parseInt(stackIdStr);
                                        LogHelper.d(TAG, "✅ 反向查找成功获取车控投屏stackId=" + stackId);
                                        return stackId;
                                    } catch (NumberFormatException e) {
                                        LogHelper.e(TAG, "❌ stackId格式错误: " + stackIdStr, e);
                                    }
                                }
                                break;
                            }
                            // 如果遇到RootTask行，停止查找
                            if (prevLine.startsWith("RootTask")) {
                                break;
                            }
                        }
                        break;
                    }
                }
                LogHelper.w(TAG, "⚠️ 未找到车控投屏的stackId");
                return -1;
            }
            
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 获取车控投屏stackId失败", e);
            return -1;
        }
    }

    /**
     * 清除Activity的静态实例
     */
    private static void clearActivityInstance(Class<?> activityClass, Object existingActivity) {
        try {
            java.lang.reflect.Field currentInstanceField = activityClass.getDeclaredField("currentInstance");
            currentInstanceField.setAccessible(true);
            if (currentInstanceField.get(null) == existingActivity) {
                currentInstanceField.set(null, null);
                LogHelper.d(TAG, "✅ 已强制清除无效的静态实例");
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "清除静态实例失败", e);
        }
    }
}
