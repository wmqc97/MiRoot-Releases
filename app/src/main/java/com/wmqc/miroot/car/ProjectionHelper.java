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

package com.wmqc.miroot.car;

import com.wmqc.miroot.lyrics.LogHelper;
import com.wmqc.miroot.lyrics.ITaskService;
import com.wmqc.miroot.lyrics.RearScreenLyricsActivity;
import com.wmqc.miroot.rear.OfficialSubscreenServiceGate;
import android.content.Context;
import android.app.KeyguardManager;
import android.os.RemoteException;
import java.lang.reflect.Method;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * 投屏辅助工具类
 * 统一管理音乐投屏和车控投屏的启动流程，确保广播启动和按钮启动行为一致
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
                LogHelper.d(TAG, "步骤1: 禁用官方Launcher");
                taskService.disableSubScreenLauncher();
                Thread.sleep(50);
            } else {
                LogHelper.d(TAG, "步骤1: 跳过禁用官方Launcher（开关未开启）");
            }

            // 步骤2: 不再区分锁屏/未锁屏，统一采用“主屏起 + service call 移动到背屏”的稳定策略（参考充电动画）
            String componentName = context.getPackageName() + "/" + RearScreenLyricsActivity.class.getName();
            boolean isBroadcast = (context instanceof android.app.IntentService) ||
                                  (context instanceof android.app.Service);

            LogHelper.d(TAG, "步骤2: 使用主屏占位+移动策略启动音乐Activity（与充电动画保持一致）");

            // 2.1 先在主屏启动Activity（占位符）
            String mainCmd;
            if (isBroadcast) {
                // 这里必须用 --ez 写入 boolean extra，否则 Activity 端 getBooleanExtra 会读不到（会回落为 false）
                mainCmd = String.format("am start -n %s --ez isBroadcast true", componentName);
                LogHelper.d(TAG, "🔵 在主屏启动音乐Activity（占位符，广播启动）");
            } else {
                mainCmd = String.format("am start -n %s", componentName);
                LogHelper.d(TAG, "🔵 在主屏启动音乐Activity（占位符）");
            }
            taskService.executeShellCommand(mainCmd);

            // 2.2 轮询获取taskId（完全对齐充电动画的节奏：60 次 × 30ms，20/40 次重发 mainCmd）
            String musicTaskId = null;
            int attempts = 0;
            int maxAttempts = 60; // 60 x 30ms = 1800ms
            while (musicTaskId == null && attempts < maxAttempts) {
                Thread.sleep(30);
                String result = taskService.executeShellCommandWithResult("am stack list | grep RearScreenLyricsActivity");
                if (result != null && !result.trim().isEmpty()) {
                    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("taskId=(\\d+)");
                    java.util.regex.Matcher matcher = pattern.matcher(result);
                    if (matcher.find()) {
                        musicTaskId = matcher.group(1);
                        LogHelper.d(TAG, "🎯 找到音乐taskId=" + musicTaskId + " (尝试" + (attempts + 1) + "次)");
                        break;
                    }
                }
                attempts++;
                if (attempts == 20 || attempts == 40) {
                    LogHelper.d(TAG, "重新发送主屏启动命令 (尝试" + attempts + ")");
                    taskService.executeShellCommand(mainCmd);
                }
            }

            if (musicTaskId == null) {
                LogHelper.e(TAG, "❌ 未能找到音乐Activity的taskId, 尝试了" + attempts + "次");
                return false;
            }

            // 2.3 通过 service call activity_task 50 将整个任务移动到背屏（displayId=1）
            String moveCmd = "service call activity_task 50 i32 " + musicTaskId + " i32 1";
            taskService.executeShellCommand(moveCmd);
            Thread.sleep(100);
            LogHelper.d(TAG, "✓ 已通过 service call 将音乐Activity移动到背屏 (taskId=" + musicTaskId + ")");

            // 步骤3: 等待Activity在背屏创建并显示UI（增加等待时间，确保onResume/onWindowFocusChanged被调用）
            LogHelper.d(TAG, "步骤3: 等待Activity在背屏创建并显示UI");
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
     * 启动车控投屏（与 {@link com.wmqc.miroot.lyrics.MusicProjectionService} 同类策略）：
     * <ol>
     *   <li>若栈里已有车控 Activity，优先 {@link ITaskService#moveTaskToDisplay} 迁到 display 1（内部顺序：
     *       {@code am task move-task-to-display} → {@code am display move-stack} → {@code service call}）</li>
     *   <li>否则 {@code am start --display 1} + 背屏唤醒（集成参考文档 3.1）</li>
     *   <li>仍失败则主屏占位 + 解析 {@code am stack list} 全文 + {@code moveTaskToDisplay}</li>
     * </ol>
     * 是否对官方背屏中心执行 force-stop 与音乐投屏一致：仅当 {@link OfficialSubscreenServiceGate}（功能页「禁用官方背屏服务」）开启。
     */
    public static boolean startCarControlProjection(ITaskService taskService, Context context) {
        if (taskService == null) {
            LogHelper.e(TAG, "❌ TaskService为null，无法启动车控投屏");
            return false;
        }

        try {
            LogHelper.d(TAG, "🚀 开始启动车控投屏（moveTaskToDisplay / --display 1 / 主屏迁屏）");

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
            final boolean disableOfficialSubscreen = OfficialSubscreenServiceGate.isDisableEnabled(context);

            if (disableOfficialSubscreen) {
                LogHelper.d(TAG, "步骤1: 「禁用官方背屏服务」已开启，禁用官方 Launcher");
                try {
                    taskService.disableSubScreenLauncher();
                } catch (RemoteException e) {
                    LogHelper.w(TAG, "disableSubScreenLauncher: " + e.getMessage());
                }
                Thread.sleep(50);
            } else {
                LogHelper.d(TAG, "步骤1: 「禁用官方背屏服务」未开启，跳过禁用官方 Launcher");
            }

            // A) 栈内已有车控 task（例如主屏透明占位）：直接迁屏
            String stackFull = taskService.executeShellCommandWithResult("am stack list");
            String existingTid = parseCarControlTaskIdFromStackList(stackFull);
            if (existingTid != null) {
                LogHelper.d(TAG, "步骤A: 发现已有车控 taskId=" + existingTid + "，尝试 moveTaskToDisplay → 1");
                if (moveTaskToDisplayOrLog(taskService, existingTid)) {
                    Thread.sleep(400);
                    if (waitCarControlRearUi(2800)) {
                        afterCarProjectionGestureStep(disableOfficialSubscreen);
                        LogHelper.d(TAG, "✅ 车控投屏完成（迁屏已有任务）");
                        return true;
                    }
                    LogHelper.w(TAG, "⚠️ 迁屏后仍未检测到背屏 UI，继续后续启动策略");
                }
            }

            // B) 唤醒背屏 + 直接在 display 1 启动
            LogHelper.d(TAG, "步骤B: input KEYCODE_WAKEUP + am start --display 1");
            taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
            Thread.sleep(50);
            taskService.executeShellCommand("am start --display 1 -n " + componentName);
            Thread.sleep(400);
            if (waitCarControlRearUi(3200)) {
                afterCarProjectionGestureStep(disableOfficialSubscreen);
                LogHelper.d(TAG, "✅ 车控投屏完成（--display 1）");
                return true;
            }
            LogHelper.w(TAG, "⚠️ --display 1 未在时限内就绪，尝试主屏占位 + moveTaskToDisplay");

            // C) 主屏 am start + 全文解析 taskId + moveTaskToDisplay（替代仅 service call 50，兼容 HyperOS）
            String mainCmd = "am start -n " + componentName;
            taskService.executeShellCommand(mainCmd);

            String carControlTaskId = null;
            int attempts = 0;
            final int maxAttempts = 60;
            while (carControlTaskId == null && attempts < maxAttempts) {
                Thread.sleep(30);
                stackFull = taskService.executeShellCommandWithResult("am stack list");
                carControlTaskId = parseCarControlTaskIdFromStackList(stackFull);
                if (carControlTaskId != null) {
                    LogHelper.d(TAG, "🎯 主屏策略解析到 taskId=" + carControlTaskId + " (第 " + (attempts + 1) + " 次)");
                    break;
                }
                attempts++;
                if (attempts == 20 || attempts == 40) {
                    taskService.executeShellCommand(mainCmd);
                }
            }

            if (carControlTaskId == null) {
                LogHelper.e(TAG, "❌ 主屏策略：未能从 am stack list 解析车控 taskId");
                return false;
            }

            if (!moveTaskToDisplayOrLog(taskService, carControlTaskId)) {
                LogHelper.w(TAG, "⚠️ moveTaskToDisplay 失败，尝试 legacy service call");
                taskService.executeShellCommand(
                        "service call activity_task 50 i32 " + carControlTaskId + " i32 1");
            }
            Thread.sleep(500);

            if (!waitCarControlRearUi(3500)) {
                LogHelper.e(TAG, "❌ 主屏迁屏后仍未检测到背屏车控 UI");
                return false;
            }

            afterCarProjectionGestureStep(disableOfficialSubscreen);
            LogHelper.d(TAG, "✅ 车控投屏完成（主屏占位+moveTaskToDisplay）");
            return true;

        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 启动车控投屏失败", e);
            return false;
        }
    }

    private static boolean moveTaskToDisplayOrLog(ITaskService taskService, String taskIdStr) {
        try {
            int tid = Integer.parseInt(taskIdStr);
            boolean ok = taskService.moveTaskToDisplay(tid, 1);
            LogHelper.d(TAG, "moveTaskToDisplay(" + tid + ",1) => " + ok);
            return ok;
        } catch (NumberFormatException e) {
            LogHelper.e(TAG, "taskId 非法: " + taskIdStr, e);
            return false;
        } catch (RemoteException e) {
            LogHelper.e(TAG, "moveTaskToDisplay RemoteException", e);
            return false;
        }
    }

    private static void afterCarProjectionGestureStep(boolean disableOfficialSubscreen) {
        if (!disableOfficialSubscreen) {
            LogHelper.d(TAG, "跳过 ProjectionHelper 内触发屏蔽手势（「禁用官方背屏服务」未开启）");
            return;
        }
        LogHelper.d(TAG, "等待 TaskService 连接并触发车控 Activity 屏蔽官方手势");
        waitAndTriggerDisableGesture(RearScreenCarControlActivity.class, 3000);
    }

    /** 背屏 displayId==1 且 mainLayout 已创建 */
    private static boolean waitCarControlRearUi(long maxWaitMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (System.currentTimeMillis() < deadline) {
            if (isCarControlOnRearWithMainUi()) {
                return true;
            }
            Thread.sleep(200);
        }
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
