/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪
 * 
 * Co-developed with AI assistants:
 * - Cursor
 * - Claude-4.5-Sonnet
 * - GPT-5
 * - Gemini-2.5-Pro
 */

package com.wmqc.miroot.lyrics;

import android.content.Context;
import com.wmqc.miroot.rear.OfficialSubscreenServiceGate;

import java.lang.reflect.Method;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * 投屏辅助工具类。
 * 音乐投屏：唤醒背屏 → {@code am start --display 1}（广播带 {@code --ez isBroadcast true}）；
 * 对 {@code com.xiaomi.subscreencenter} 的屏蔽仅当 {@link OfficialSubscreenServiceGate} 开启时执行。
 */
public class ProjectionHelper {
    private static final String TAG = "ProjectionHelper";

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

            if (OfficialSubscreenServiceGate.isDisableEnabled(context)) {
                try {
                    LogHelper.d(TAG, "步骤1: 禁用官方Launcher（开关已开启）");
                    taskService.disableSubScreenLauncher();
                } catch (Exception e) {
                    LogHelper.w(TAG, "disableSubScreenLauncher 异常（继续投屏）: " + e.getMessage());
                }
                Thread.sleep(100);
            } else {
                LogHelper.d(TAG, "步骤1: 跳过禁用官方Launcher（开关未开启）");
            }

            // 供歌词页 cutout / 媒体条避让尽早可用（MiRoot 歌词界面差异，不改变启动时序）
            try {
                if (!DisplayInfoCache.getInstance().isInitialized()) {
                    DisplayInfoCache.getInstance().initialize(taskService);
                }
            } catch (Exception e) {
                LogHelper.w(TAG, "DisplayInfoCache 预初始化失败（可忽略）: " + e.getMessage());
            }

            String componentName = context.getPackageName() + "/" + RearScreenLyricsActivity.class.getName();
            boolean isBroadcast = (context instanceof android.app.IntentService) ||
                                  (context instanceof android.app.Service);

            // 步骤2: 唤醒背屏（不区分锁屏/未锁屏，统一先发 KEYCODE_WAKEUP）
            LogHelper.d(TAG, "步骤2: 唤醒背屏");
            taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
            Thread.sleep(50);

            // 步骤3: 直接在背屏启动 Activity
            LogHelper.d(TAG, "步骤3: 直接在背屏启动 Activity（am start --display 1）");
            String rearCmd;
            if (isBroadcast) {
                rearCmd = String.format("am start --display 1 -n %s --ez isBroadcast true", componentName);
                LogHelper.d(TAG, "🔵 背屏启动音乐 Activity（广播 isBroadcast）");
            } else {
                rearCmd = String.format("am start --display 1 -n %s", componentName);
                LogHelper.d(TAG, "🔵 背屏启动音乐 Activity");
            }
            taskService.executeShellCommand(rearCmd);
            LogHelper.d(TAG, "✅ Activity 启动命令已执行: " + rearCmd);

            // 步骤4: 等待 Activity 创建（500ms）
            LogHelper.d(TAG, "步骤4: 等待 Activity 在背屏创建并显示 UI");
            Thread.sleep(500);
            
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

            if (OfficialSubscreenServiceGate.isDisableEnabled(context)) {
                LogHelper.d(TAG, "步骤5: 等待Activity的TaskService连接，然后触发屏蔽手势服务");
                waitAndTriggerDisableGesture(RearScreenLyricsActivity.class, 3000);
            }

            LogHelper.d(TAG, "✅ 音乐投屏启动流程完成");
            return true;

        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 启动音乐投屏失败", e);
            return false;
        }
    }

    /**
     * 启动车控投屏的完整流程（可复用）
     * @param taskService TaskService实例
     * @param context Context
     * @return 是否启动成功
     */
    /** 委托 {@link com.wmqc.miroot.car.ProjectionHelper}（车控实现仅在 car 包内维护一份）。 */
    public static boolean startCarControlProjection(ITaskService taskService, Context context) {
        return com.wmqc.miroot.car.ProjectionHelper.startCarControlProjection(taskService, context);
    }

    /**
     * 从 am stack list 完整输出中解析音乐投屏 Activity 的 taskId
     * 兼容不同 ROM 输出格式（有的只显示包名或类名简写）
     * @param stackListOutput am stack list 命令的完整输出
     * @return taskId 字符串，未找到返回 null
     */
    /**
     * 从 {@code am stack list} 解析当前音乐投屏 Activity 的 taskId（用于已在主屏时的迁屏）。
     *
     * @return taskId，失败返回 -1
     */
    public static int getMusicProjectionTaskId(ITaskService taskService) {
        if (taskService == null) {
            LogHelper.e(TAG, "❌ TaskService为null，无法获取taskId");
            return -1;
        }
        try {
            String result = taskService.executeShellCommandWithResult("am stack list");
            if (result == null || result.trim().isEmpty()) {
                return -1;
            }
            String tidStr = parseMusicProjectionTaskIdFromStackList(result);
            if (tidStr == null) {
                return -1;
            }
            return Integer.parseInt(tidStr);
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 获取音乐投屏taskId失败", e);
            return -1;
        }
    }

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
            //     taskId=1234: com.wmqc.miroot.lyrics/com.wmqc.miroot.lyrics.RearScreenLyricsActivity
            
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
            //     taskId=1234: com.wmqc.miroot.lyrics/com.wmqc.miroot.lyrics.RearScreenCarControlActivity
            
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

    /**
     * 主屏占位经 {@code am display move-stack} 移到背屏后的收尾（原：再次触发屏蔽官方背屏中心）。
     * 音乐投屏：已关闭，方法体为空。
     */
    public static void triggerLyricsGestureDisableAfterMove(long maxWaitMs) {
        // 音乐投屏：已注释屏蔽官方手势服务
        // waitAndTriggerDisableGesture(RearScreenLyricsActivity.class, maxWaitMs);
    }

    /**
     * 等待Activity的TaskService连接，然后主动触发屏蔽手势服务
     * @param activityClass Activity类
     * @param maxWaitMs 最大等待时间（毫秒）
     */
    private static void waitAndTriggerDisableGesture(Class<?> activityClass, long maxWaitMs) {
        try {
            long startTime = System.currentTimeMillis();
            Object activity = null;
            
            // 等待Activity创建（最多等待500ms）
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
                    // 忽略反射异常，继续等待
                }
                
                Thread.sleep(100);
            }
            
            if (activity == null) {
                LogHelper.w(TAG, "⚠️ 等待超时，Activity未创建，但会继续尝试触发屏蔽手势服务");
                // 即使Activity未创建，也尝试通过静态方法触发（如果Activity稍后创建，会在onCreate中自动触发）
                return;
            }
            
            // 等待TaskService连接（最多等待剩余时间，但至少2秒）
            long elapsed = System.currentTimeMillis() - startTime;
            long remainingTime = maxWaitMs - elapsed;
            long taskServiceWaitMs = Math.max(2000, Math.min(maxWaitMs - 500, remainingTime)); // 至少等待2秒
            
            LogHelper.d(TAG, "⏳ 等待TaskService连接（最多" + taskServiceWaitMs + "ms）");
            long taskServiceStartTime = System.currentTimeMillis();
            int checkCount = 0;
            
            while (System.currentTimeMillis() - taskServiceStartTime < taskServiceWaitMs) {
                try {
                    // 通过反射获取taskService字段
                    java.lang.reflect.Field taskServiceField = activityClass.getDeclaredField("taskService");
                    taskServiceField.setAccessible(true);
                    Object taskService = taskServiceField.get(activity);
                    
                    checkCount++;
                    if (checkCount % 5 == 0) {
                        LogHelper.d(TAG, "⏳ 等待TaskService连接中... (已检查 " + checkCount + " 次)");
                    }
                    
                    if (taskService != null) {
                        LogHelper.d(TAG, "✅ TaskService已连接，触发屏蔽手势服务 (检查 " + checkCount + " 次后成功)");
                        
                        // 通过反射调用checkAndDisableOfficialGesture方法
                        Method checkMethod = activityClass.getDeclaredMethod("checkAndDisableOfficialGesture");
                        checkMethod.setAccessible(true);
                        checkMethod.invoke(activity);
                        
                        LogHelper.d(TAG, "✅ 已触发屏蔽手势服务（通过ProjectionHelper）");
                        return;
                    }
                } catch (NoSuchFieldException e) {
                    // 字段不存在，尝试直接调用方法（不检查TaskService）
                    LogHelper.w(TAG, "⚠️ taskService字段不存在，尝试直接调用方法");
                    try {
                        Method checkMethod = activityClass.getDeclaredMethod("checkAndDisableOfficialGesture");
                        checkMethod.setAccessible(true);
                        checkMethod.invoke(activity);
                        LogHelper.d(TAG, "✅ 已触发屏蔽手势服务（直接调用，不检查TaskService）");
                        return;
                    } catch (Exception e2) {
                        LogHelper.w(TAG, "⚠️ 直接调用方法失败: " + e2.getMessage());
                    }
                } catch (Exception e) {
                    // 忽略其他异常，继续等待（但记录日志以便调试）
                    if (checkCount % 10 == 0) {
                        LogHelper.d(TAG, "⏳ 等待TaskService连接中（反射检查异常，继续等待）: " + e.getClass().getSimpleName());
                    }
                }
                
                Thread.sleep(100);
            }
            
            // 即使TaskService未连接，也尝试直接调用方法（Activity内部会处理TaskService未连接的情况）
            // 这样可以确保即使等待超时，也能触发屏蔽手势服务
            try {
                LogHelper.w(TAG, "⚠️ TaskService等待超时（" + taskServiceWaitMs + "ms），尝试直接调用方法（Activity内部会处理）");
                Method checkMethod = activityClass.getDeclaredMethod("checkAndDisableOfficialGesture");
                checkMethod.setAccessible(true);
                checkMethod.invoke(activity);
                LogHelper.d(TAG, "✅ 已触发屏蔽手势服务（超时后直接调用）");
            } catch (Exception e) {
                LogHelper.w(TAG, "⚠️ 超时后直接调用方法失败: " + e.getMessage() + "，Activity会在TaskService连接后自动触发");
                // 即使反射调用失败，Activity的onServiceConnected中也会自动触发，所以这里只是警告
            }
            
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 等待并触发屏蔽手势服务失败", e);
        }
    }
}
