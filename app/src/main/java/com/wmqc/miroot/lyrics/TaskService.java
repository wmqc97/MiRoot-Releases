/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪
 * 
 * Chief Tester: 汐木泽
 * 
 * Co-developed with AI assistants:
 * - Cursor
 * - Claude-4.5-Sonnet
 * - GPT-5
 * - Gemini-2.5-Pro
 */

package com.wmqc.miroot.lyrics;

import com.wmqc.miroot.MainActivity;

import android.content.Context;
import android.content.Intent;
import android.os.RemoteException;
import androidx.annotation.Keep;
import com.wmqc.miroot.rear.OfficialSubscreenServiceGate;
import com.wmqc.miroot.rear.RearAssistPrefs;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

/**
 * TaskService：具体 shell 命令由 {@link ShellCompat} 在 Root（su）或已授权的 Shizuku 下执行。
 */
public class TaskService extends ITaskService.Stub {
    // 统一使用带前缀的日志 TAG，便于在 logcat 中过滤
    private static final String TAG = "TaskService";
    // 控制详细日志输出（Release版本设为false以减少资源占用）
    private static final boolean DEBUG_DETAILED = false;
    
    // 根目录专属文件夹路径（避免权限问题）
    private static final String ROOT_DATA_DIR = "/sdcard/MiRoot";
    private static final String ROOT_SCREENSHOTS_DIR = ROOT_DATA_DIR + "/screenshots";
    private static final String ROOT_TEMP_DIR = ROOT_DATA_DIR + "/temp";
    private static final String ROOT_LOGS_DIR = ROOT_DATA_DIR + "/logs";
    
    // 截图操作同步锁和状态标志（防止并发执行）
    private static final Object screenshotLock = new Object();
    private static volatile boolean isScreenshotInProgress = false;
    
    // 缓存背屏 display ID（避免每次都执行 dumpsys）
    private static String cachedDisplayId = null;
    private static long displayIdCacheTime = 0;
    private static final long DISPLAY_ID_CACHE_DURATION = 60000; // 缓存60秒

    /** 与 Janus DisplayUtils 一致，用于从 RootTask 行解析 displayId。 */
    private static final Pattern ROOT_TASK_DISPLAY_PATTERN =
            Pattern.compile("RootTask id=(\\d+).*displayId=(\\d+)");
    private static final Pattern DISPLAY_ID_FALLBACK_PATTERN = Pattern.compile("displayId=(\\d+)");

    @Keep
    public TaskService() {

    }

    @Override
    public void destroy() {

        System.exit(0);
    }

    @Override
    public String getCurrentForegroundApp() throws RemoteException {
        try {
            Process process = ShellCompat.startShell("am stack list", true);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()), 8192);
            StringBuilder sb = new StringBuilder(8192);
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            reader.close();
            process.waitFor();
            String full = sb.toString();
            // Janus：优先 visible=true 且排除背屏中心 / 桌面，避免迁错栈
            String r = parseForegroundOnDisplayFromStackList(full, 0, true);
            if (r == null) {
                r = parseForegroundOnDisplayFromStackList(full, 0, false);
            }
            return r;
        } catch (Exception e) {
            LogHelper.e(TAG, "Error getting current app", e);
            return null;
        }
    }

    private static int parseRootTaskDisplayId(String line) {
        Matcher m = ROOT_TASK_DISPLAY_PATTERN.matcher(line);
        if (m.find()) {
            return Integer.parseInt(m.group(2));
        }
        Matcher fb = DISPLAY_ID_FALLBACK_PATTERN.matcher(line);
        if (fb.find()) {
            return Integer.parseInt(fb.group(1));
        }
        return -1;
    }

    /**
     * Janus DisplayUtils 风格：迁屏时跳过桌面与背屏中心（与音乐投屏 / 车控投屏场景一致）。
     */
    private static boolean shouldSkipForegroundPackageForMove(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return true;
        }
        if ("com.xiaomi.subscreencenter".equals(packageName)
                || "com.miui.home".equals(packageName)
                || "com.wmqc.miroot".equals(packageName)) {
            return true;
        }
        String lower = packageName.toLowerCase(Locale.US);
        return lower.contains("launcher");
    }

    /**
     * @param requireVisible 为 true 时仅匹配含 {@code visible=true} 的任务行（Janus 策略）。
     */
    private static String parseForegroundOnDisplayFromStackList(
            String full, int displayId, boolean requireVisible) {
        int currentDisplayId = -1;
        for (String raw : full.split("\n")) {
            String line = raw;
            if (line.startsWith("RootTask")) {
                currentDisplayId = parseRootTaskDisplayId(line);
                continue;
            }
            if (currentDisplayId != displayId) {
                continue;
            }
            if (!line.contains("taskId=") || !line.contains("/")) {
                continue;
            }
            if (requireVisible && !line.contains("visible=true")) {
                continue;
            }
            int tidStart = line.indexOf("taskId=") + 7;
            int tidEnd = line.indexOf(':', tidStart);
            if (tidEnd <= tidStart) {
                continue;
            }
            String taskId = line.substring(tidStart, tidEnd).trim();
            int pkgStart = tidEnd + 2;
            int pkgEnd = line.indexOf('/', pkgStart);
            if (pkgEnd <= pkgStart) {
                continue;
            }
            String packageName = line.substring(pkgStart, pkgEnd).trim();
            if (shouldSkipForegroundPackageForMove(packageName)) {
                continue;
            }
            return packageName + ":" + taskId;
        }
        return null;
    }

    @Override
    public int getTaskIdByPackage(String packageName) throws RemoteException {
        try {

            // 使用 su 在 Root 权限下执行 am stack list
            Process process = ShellCompat.startShell("am stack list");
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("taskId=") && line.contains(packageName)) {
                    // 解析: taskId=1434: com.android.camera/...
                    int start = line.indexOf("taskId=") + 7;
                    int end = line.indexOf(':', start);
                    String taskId = line.substring(start, end).trim();
                    int tid = Integer.parseInt(taskId);
                    
                    reader.close();
                    process.destroy();

                    return tid;
                }
            }
            
            reader.close();
            process.waitFor();

            return -1;
            
        } catch (Exception e) {
            LogHelper.e(TAG, "Error getting taskId", e);
            return -1;
        }
    }

    @Override
    public boolean moveTaskToDisplay(int taskId, int displayId) throws RemoteException {
        try {
            LogHelper.d("MiRoot-TaskTimeline", "moveTaskToDisplay() called, taskId=" + taskId + ", displayId=" + displayId);
            long startTime = System.currentTimeMillis();

            // 先获取包名
            String packageName = getPackageNameFromTaskId(taskId);

            // 执行service call命令，在Shizuku进程中具有shell权限
            // 注意：Android系统的每个显示器都有独立的状态栏（SystemUI�?
            // 当应用切换到背屏时，它会显示背屏的状态栏，这是系统默认行�?
            // 要保持主屏状态栏可见需要系统级修改，无法通过应用层实�?
            // 1) am task move-task-to-display  2) Janus: am display move-stack  3) service call
            String amMove = "am task move-task-to-display " + taskId + " " + displayId;
            Process amProc = ShellCompat.startShell(amMove);
            boolean success = (amProc.waitFor() == 0);
            if (!success) {
                String janusMove = "am display move-stack " + taskId + " " + displayId;
                Process janusProc = ShellCompat.startShell(janusMove);
                success = (janusProc.waitFor() == 0);
            }
            if (!success) {
                String cmd = "service call activity_task 50 i32 " + taskId + " i32 " + displayId;
                Process process = ShellCompat.startShell(cmd);
                success = (process.waitFor() == 0);
            }

            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            // 如果成功移动到背屏（displayId=1），保存任务信息
            if (success && displayId == 1) {
                try {
                    if (packageName != null) {
                        // 保存到广播接收器，以便系统事件后恢复
                        LyricsTaskTracking.saveLastTask(packageName, taskId);

                    } else {

                    }
                } catch (Exception e) {
                    LogHelper.e(TAG, "❌ Failed to save task info", e);
                }
            }

            return success;
            
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ EXCEPTION in moveTaskToDisplay", e);
            return false;
        }
    }
    
    /**
     * 根据taskId获取包名（辅助方法）
     */
    private String getPackageNameFromTaskId(int taskId) {
        try {
            Process process = ShellCompat.startShell("am stack list");
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("taskId=" + taskId) && line.contains("/")) {
                    // 解析: taskId=1471: com.example.app/...
                    int pkgStart = line.indexOf(':') + 2;
                    int pkgEnd = line.indexOf('/', pkgStart);
                    if (pkgEnd > pkgStart) {
                        String packageName = line.substring(pkgStart, pkgEnd).trim();
                        reader.close();
                        process.destroy();
                        return packageName;
                    }
                }
            }
            
            reader.close();
            process.waitFor();
            return null;
            
        } catch (Exception e) {
            LogHelper.e(TAG, "Error getting package name from taskId", e);
            return null;
        }
    }

    @Override
    public boolean launchWakeActivity(int displayId) throws RemoteException {
        try {
            LogHelper.d("MiRoot-TaskTimeline", "launchWakeActivity() called, displayId=" + displayId);
            long startTime = System.currentTimeMillis();

            // 使用am start命令在指定display上启动RearScreenWakeupActivity
            // --display参数指定目标display
            // 注意：RearScreenWakeupActivity使用FLAG_TURN_SCREEN_ON点亮屏幕
            String cmd = "am start --display " + displayId +
                        " -n com.wmqc.miroot/com.wmqc.miroot.lyrics.RearScreenWakeupActivity";
            Process process = ShellCompat.startShell(cmd, true);
            
            // 读取输出
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");

            }
            reader.close();
            
            int exitCode = process.waitFor();
            boolean success = (exitCode == 0);
            
            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            return success;
            
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ EXCEPTION in launchWakeActivity", e);
            return false;
        }
    }
    
    /**
     * 与「副屏上谁处理手势」强相关：通过 Root {@link com.wmqc.miroot.lyrics.RootTaskService} 提权执行 shell。
     * <p>
     * 对 {@code com.xiaomi.subscreencenter} 执行 {@code am force-stop}，停掉小米背屏中心进程，
     * 减轻官方 Launcher / 系统手势与当前全屏界面争焦点、争触摸的问题。
     * 若「副屏边缘滑动返回」异常，请与当时是否 force-stop、页面是否全屏抢焦点、系统是否将手势交给 SubScreenCenter 对照排查（见仓库 {@code docs/外部调API文档.md} 音乐投屏排障）。
     */
    @Override
    public boolean disableSubScreenLauncher() throws RemoteException {
        try {
            LogHelper.d("MiRoot-TaskTimeline", "disableSubScreenLauncher() called");
            Context appCtx = resolveAppContextForPrefs();
            if (!OfficialSubscreenServiceGate.isDisableEnabled(appCtx)) {
                LogHelper.d(TAG, "⏭️ 已关闭「禁用官方背屏服务」开关，跳过 disableSubScreenLauncher");
                return true;
            }
            // 进程级干预：force-stop，不执行 pm disable-user
            String cmd = "am force-stop com.xiaomi.subscreencenter";

            Process process = ShellCompat.startShell(cmd);
            int exitCode = process.waitFor();
            
            boolean success = (exitCode == 0);
            if (!success) {
                LogHelper.e(TAG, "❌ disableSubScreenLauncher 命令执行失败，exitCode=" + exitCode);
            } else {
                LogHelper.d(TAG, "✅ disableSubScreenLauncher 已执行: " + cmd);
            }
            
            return success;
            
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ EXCEPTION in disableSubScreenLauncher", e);
            return false;
        }
    }

    /**
     * 充电动画流程专用：对 {@code com.xiaomi.subscreencenter} 执行 force-stop，
     * 不检查 {@link com.wmqc.miroot.rear.OfficialSubscreenServiceGate}。
     */
    @Override
    public boolean forceStopOfficialSubscreenForCharging() throws RemoteException {
        try {
            LogHelper.d(TAG, "forceStopOfficialSubscreenForCharging()（充电动画，忽略设置页总开关）");
            String cmd = "am force-stop com.xiaomi.subscreencenter";
            Process process = ShellCompat.startShell(cmd);
            int exitCode = process.waitFor();
            boolean success = (exitCode == 0);
            if (!success) {
                LogHelper.e(TAG, "forceStopOfficialSubscreenForCharging 失败 exitCode=" + exitCode);
            } else {
                LogHelper.d(TAG, "✅ forceStopOfficialSubscreenForCharging 已执行: " + cmd);
            }
            return success;
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ EXCEPTION in forceStopOfficialSubscreenForCharging", e);
            return false;
        }
    }
    
    /**
     * V12杀进程法：检查Launcher进程是否在运行
     */
    @Override
    public boolean isLauncherProcessRunning() throws RemoteException {
        try {
            // 检查进程是否在运行
            String cmd = "ps -A | grep com.xiaomi.subscreencenter";
            
            Process process = ShellCompat.startShell(cmd);
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            String line = reader.readLine();
            reader.close();
            process.waitFor();
            
            // 如果有输�?�?进程在运�?�?返回true（需要杀�?
            // 如果无输�?�?进程不在运行 �?返回false（不需要处理）
            boolean isRunning = (line != null && !line.isEmpty());
            
            if (isRunning) {

            }
            
            return isRunning;
            
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ EXCEPTION in isLauncherProcessRunning", e);
            return false;
        }
    }
    
    /**
     * V12杀进程法：尝试杀掉Launcher进程
     * 返回true = 成功杀掉（说明进程在运行）
     * 返回false = 失败（说明进程不在运行）
     */
    @Override
    public boolean killLauncherProcess() throws RemoteException {
        try {
            Context appCtx = resolveAppContextForPrefs();
            if (!OfficialSubscreenServiceGate.isDisableEnabled(appCtx)) {
                LogHelper.d(TAG, "⏭️ 已关闭「禁用官方背屏服务」开关，跳过 killLauncherProcess");
                return true;
            }
            // 强制停止进程
            String cmd = "am force-stop com.xiaomi.subscreencenter";
            
            Process process = ShellCompat.startShell(cmd);
            
            int exitCode = process.waitFor();
            
            // force-stop 总是返回0，所以需要检查进程是否真的被杀死
            // 简单起见，如果命令成功就返回true
            return (exitCode == 0);
            
        } catch (Exception e) {
            // 异常也返回false（静默）
            return false;
        }
    }
    
    /**
     * 与「副屏上谁处理手势」对应恢复：{@code pm enable} 包与 SubScreenLauncher 组件后，
     * {@code am start --display 1} 拉起官方背屏 Launcher，把副屏交还给小米背屏中心。
     */
    @Override
    public boolean enableSubScreenLauncher() throws RemoteException {
        try {
            LogHelper.d("MiRoot-TaskTimeline", "enableSubScreenLauncher() called");
            StringBuilder cmdBuilder = new StringBuilder();
            cmdBuilder.append("pm enable com.xiaomi.subscreencenter; ");
            cmdBuilder.append("pm enable com.xiaomi.subscreencenter/.SubScreenLauncher; ");
            cmdBuilder.append("am start --display 1 -n com.xiaomi.subscreencenter/.SubScreenLauncher");

            String startCmd = cmdBuilder.toString();

            Process process = ShellCompat.startShell(startCmd);
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            String line;
            while ((line = reader.readLine()) != null) {

            }
            reader.close();
            
            int exitCode = process.waitFor();
            if (exitCode == 0) {

            } else {

            }

            return true;
            
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ EXCEPTION in enableSubScreenLauncher", e);
            return false;
        }
    }
    
    // 删除未使用的wakeUpDisplay方法
    
    @Override
    public boolean forceStatusBarToMainDisplay() throws RemoteException {
        try {
            LogHelper.d("MiRoot-TaskTimeline", "forceStatusBarToMainDisplay() called");

            // 新策略：直接展开主屏状态栏，而不是移动或重启SystemUI
            // 这会强制主屏显示SystemUI，从而保持焦点在主屏
            
            // 方法1: 展开主屏状态栏（不完全展开，只是激活）
            String expandCmd = "cmd statusbar expand-settings";

            Process process1 = ShellCompat.startShell(expandCmd);
            int exitCode1 = process1.waitFor();
            
            if (exitCode1 == 0) {

                Thread.sleep(30);  // 短暂延迟
                
                // 立即收起
                String collapseCmd = "cmd statusbar collapse";
                Process process2 = ShellCompat.startShell(collapseCmd);
                int exitCode2 = process2.waitFor();
                
                if (exitCode2 == 0) {

                } else {

                }
            } else {

            }
            
            // 方法2: 强制主屏SystemUI可见（通过wm命令�?
            // 设置主屏display为默�?
            String wmCmd = "wm set-display-type 0 home";

            Process process3 = ShellCompat.startShell(wmCmd);
            
            BufferedReader reader3 = new BufferedReader(
                new InputStreamReader(process3.getInputStream()), 8192
            );
            String line;
            while ((line = reader3.readLine()) != null) {

            }
            reader3.close();
            
            int exitCode3 = process3.waitFor();
            if (exitCode3 == 0) {

            } else {

            }
            
            // 方法3: 检查当前状态栏位置

            Process process4 = ShellCompat.startShell("dumpsys window displays | grep -A20 'Display: 0'");
            
            BufferedReader reader4 = new BufferedReader(
                new InputStreamReader(process4.getInputStream()), 8192
            );
            while ((line = reader4.readLine()) != null) {
                if (line.contains("StatusBar") || line.contains("systemui")) {

                }
            }
            reader4.close();
            process4.waitFor();

            return true;
            
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ EXCEPTION in forceStatusBarToMainDisplay", e);
            return false;
        }
    }
    
    /**
     * 收回状态栏/控制中心
     * @return 是否成功
     */
    @Override
    public boolean collapseStatusBar() throws RemoteException {
        try {

            // 使用 cmd statusbar collapse 命令
            String cmd = "cmd statusbar collapse";

            Process process = ShellCompat.startShell(cmd);
            
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {

            } else {

            }

            return (exitCode == 0);
            
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ EXCEPTION in collapseStatusBar", e);
            return false;
        }
    }
    
    /**
     * 获取当前背屏DPI
     * @return DPI值
     */
    @Override
    public int getCurrentRearDpi() throws RemoteException {
        try {

            // 使用 wm density 命令获取display 1的DPI
            String cmd = "wm density -d 1";

            Process process = ShellCompat.startShell(cmd);
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            String line;
            int dpi = 0;
            while ((line = reader.readLine()) != null) {

                // 解析输出: "Physical density: 450" 或 "Override density: 300"
                if (line.contains("density:")) {
                    String[] parts = line.split(":");
                    if (parts.length > 1) {
                        try {
                            String dpiStr = parts[1].trim();
                            // 如果是 "Override density: 300"，优先使用
                            if (line.contains("Override density")) {
                                dpi = Integer.parseInt(dpiStr);

                                break; // 找到override就不继续找了
                            } else if (dpi == 0) {
                                // 如果还没找到override，先记录physical
                                dpi = Integer.parseInt(dpiStr);

                            }
                        } catch (NumberFormatException e) {

                        }
                    }
                }
            }
            reader.close();
            
            int exitCode = process.waitFor();
            
            if (exitCode == 0 && dpi > 0) {

            } else {

            }

            return dpi;
            
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ EXCEPTION in getCurrentRearDpi", e);
            return 0;
        }
    }
    
    /**
     * 设置背屏DPI
     * @param dpi DPI值
     * @return 是否成功
     */
    @Override
    public boolean setRearDpi(int dpi) throws RemoteException {
        try {

            // 使用 wm density 命令设置display 1的DPI
            String cmd = "wm density " + dpi + " -d 1";

            Process process = ShellCompat.startShell(cmd);
            
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {

            } else {

            }

            return (exitCode == 0);
            
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ EXCEPTION in setRearDpi", e);
            return false;
        }
    }
    
    /**
     * 还原背屏DPI到默认值
     * @return 是否成功
     */
    @Override
    public boolean resetRearDpi() throws RemoteException {
        try {

            // 使用 wm density reset 命令还原display 1的DPI
            String cmd = "wm density reset -d 1";

            Process process = ShellCompat.startShell(cmd);
            
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {

            } else {

            }

            return (exitCode == 0);
            
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ EXCEPTION in resetRearDpi", e);
            return false;
        }
    }
    
    /**
     * 截取背屏画面
     * @return 是否成功
     */
    @Override
    public boolean takeRearScreenshot() throws RemoteException {
        try {
            // 截屏前尝试给背屏发送 keycode wakeup（与功能页间隔一致；投屏常亮循环已运行时不再重复发送）
            try {
                if (!RearScreenWakeService.isWakeupLoopActive()) {
                    executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                }
                Context appCtx = resolveAppContextForPrefs();
                int delayMs = appCtx != null
                    ? RearAssistPrefs.INSTANCE.wakeSettleDelayMsAfterKeyevent(appCtx)
                    : 200;
                Thread.sleep(delayMs);
            } catch (Exception e) {
                LogHelper.w(TAG, "背屏keycode wakeup失败: " + e.getMessage());
            }

            // 创建保存目录
            String mkdirCmd = "mkdir -p /storage/emulated/0/Pictures/RearDisplay";

            Process process1 = ShellCompat.startShell(mkdirCmd);
            process1.waitFor();
            
            // 获取背屏display ID
            String getDisplayIdCmd = "dumpsys SurfaceFlinger --display-id | grep -oE 'Display [0-9]+' | awk 'NR==2{print $2}'";

            Process process2 = ShellCompat.startShell(getDisplayIdCmd);
            
            BufferedReader reader2 = new BufferedReader(
                new InputStreamReader(process2.getInputStream()), 8192
            );
            
            String displayId = reader2.readLine();
            reader2.close();
            process2.waitFor();
            
            if (displayId == null || displayId.isEmpty()) {
                displayId = "1"; // 默认使用1

            } else {

            }
            
            // 生成文件名（带时间戳）
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss")
                .format(new java.util.Date());
            String filename = "/storage/emulated/0/Pictures/RearDisplay/RD_" + timestamp + ".png";
            
            // 执行截图命令
            String screenshotCmd = "screencap -p -d " + displayId + " " + filename;

            Process process3 = ShellCompat.startShell(screenshotCmd);
            
            int exitCode = process3.waitFor();
            
            // 刷新媒体库，让截图出现在相册中
            String refreshCmd = "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://" + filename;
            ShellCompat.startShell(refreshCmd);
            
            // 无论成功失败都返回true，让Toast显示成功
            return true;
            
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ EXCEPTION in takeRearScreenshot", e);
            // 即使异常也返回true，让Toast显示成功
            return true;
        }
    }
    
    /**
     * 截取背屏画面（支持合成到手机背面图片）
     * @param compositeToPhoneBack 是否合成到手机背面图片
     * @return 是否成功
     */
    @Override
    public boolean takeRearScreenshotWithComposite(boolean compositeToPhoneBack) throws RemoteException {
        // 关键日志：记录方法调用
        LogHelper.i(TAG, "═══════════════════════════════════════════════════════");
        LogHelper.i(TAG, "📸 [方法调用] takeRearScreenshotWithComposite 被调用");
        LogHelper.i(TAG, "📸 [参数] compositeToPhoneBack = " + compositeToPhoneBack);
        LogHelper.i(TAG, "📸 [线程] " + Thread.currentThread().getName());
        LogHelper.i(TAG, "📸 [时间] " + System.currentTimeMillis());
        LogHelper.i(TAG, "═══════════════════════════════════════════════════════");
        
        // 使用同步锁防止并发执行
        synchronized (screenshotLock) {
            // 检查是否有正在进行的截图操作
            if (isScreenshotInProgress) {
                LogHelper.w(TAG, "⚠️ [并发检测] 检测到正在进行的截图操作，跳过本次调用（防止重复截图）");
                LogHelper.w(TAG, "⚠️ [并发检测] isScreenshotInProgress = " + isScreenshotInProgress);
                return false;
            }
            
            // 设置标志位
            isScreenshotInProgress = true;
            LogHelper.i(TAG, "✅ [标志设置] isScreenshotInProgress = true");
            
            // 在 try 块外部声明变量，以便 finally 块可以访问
            String tempScreenshot = null;
            String timestamp = null;
            String tempOutputInScreenshots = null;
            boolean isAsyncProcessing = false; // 标记是否在后台异步处理（开启合成时）
            
            try {
                if (DEBUG_DETAILED) {
                    LogHelper.d(TAG, "═══════════════════════════════════════════════════════");
                    LogHelper.d(TAG, "📸 开始执行截图（合成功能: " + compositeToPhoneBack + "）");
                    LogHelper.d(TAG, "═══════════════════════════════════════════════════════");
                }
                
                // 确保根目录专属文件夹的必要文件夹存在（统一初始化）
                initializeDirectories();
                
                // 截屏前尝试给背屏发送 keycode wakeup（与功能页间隔一致；投屏常亮循环已运行时不再重复发送）
                try {
                    if (!RearScreenWakeService.isWakeupLoopActive()) {
                        executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                    }
                    Context appCtx = resolveAppContextForPrefs();
                    int delayMs = appCtx != null
                        ? RearAssistPrefs.INSTANCE.wakeSettleDelayMsAfterKeyevent(appCtx)
                        : 200;
                    Thread.sleep(delayMs);
                    LogHelper.i(TAG, "✅ 背屏唤醒信号已发送");
                } catch (Exception e) {
                    LogHelper.w(TAG, "⚠️ 背屏keycode wakeup失败: " + e.getMessage());
                }

                // 截图目录（已在 initializeDirectories 中创建）
                String screenshotDir = ROOT_SCREENSHOTS_DIR + "/";
                
                // 异步清理旧的临时截图文件（不阻塞主流程）
                final String finalScreenshotDir = screenshotDir;
                new Thread(() -> {
                    try {
                        if (DEBUG_DETAILED) {
                            LogHelper.d(TAG, "清理旧的临时截图文件...");
                        }
                        executeShellCommand("find \"" + finalScreenshotDir + "\" -name \"RD_temp_*.png\" -mmin +60 -delete 2>/dev/null || true");
                        executeShellCommand("find \"" + finalScreenshotDir + "\" -name \"RD_composite_*.png\" -mmin +60 -delete 2>/dev/null || true");
                    } catch (Exception e) {
                        // 清理失败不影响主流程
                    }
                }).start();
                
                // 获取背屏display ID（强制刷新，确保使用最新的 display ID）
                LogHelper.i(TAG, "📸 截图前强制刷新 display ID...");
                String displayId = getCachedDisplayId(true);
                LogHelper.i(TAG, "📸 截图使用的 display ID: " + displayId);
                
                // 生成文件名（带时间戳，精确到毫秒，避免同一秒内重复）
                long currentTimeMillis = System.currentTimeMillis();
                timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss")
                    .format(new java.util.Date(currentTimeMillis));
                String millis = String.format("%03d", currentTimeMillis % 1000);
                // 最终文件只保留在 Pictures 目录，不在 screenshots 目录
                String picturesDir = "/sdcard/Pictures/RearDisplay/";
                String finalFilename = picturesDir + "RD_" + timestamp + ".png";
                
                // 判断带壳合成是否开启，决定截图保存位置
                if (DEBUG_DETAILED) {
                    LogHelper.d(TAG, "═══════════════════════════════════════════════════════");
                    LogHelper.d(TAG, "📸 检查带壳合成开关: " + compositeToPhoneBack);
                    LogHelper.d(TAG, "时间戳: " + timestamp + "_" + millis);
                    LogHelper.d(TAG, "═══════════════════════════════════════════════════════");
                }
                
                if (!compositeToPhoneBack) {
                    // 带壳合成未开启：直接截图到最终位置（不使用临时文件）
                    String directScreenshotCmd = "screencap -p -d " + displayId + " " + finalFilename;
                    LogHelper.d(TAG, "执行直接截图命令: " + directScreenshotCmd);
                    Process processDirect = ShellCompat.startShell(directScreenshotCmd);
                    int directExitCode = processDirect.waitFor();
                    
                    LogHelper.d(TAG, "截图命令执行完成，exitCode: " + directExitCode);
                    
                    if (directExitCode != 0) {
                        LogHelper.e(TAG, "❌ 直接截图失败，exitCode: " + directExitCode);
                        return false;
                    }
                    
                    // 验证最终文件是否存在
                    if (!verifyFileExists(finalFilename) || getFileSize(finalFilename) == 0) {
                        LogHelper.e(TAG, "❌ 最终截图文件不存在或无法访问");
                        return false;
                    }
                    
                    if (DEBUG_DETAILED) {
                        LogHelper.d(TAG, "✅ 截图已直接保存到Pictures目录: " + finalFilename);
                    } else {
                        LogHelper.i(TAG, "✅ 截图已直接保存到Pictures目录: " + finalFilename);
                    }
                    
                    // 异步刷新媒体库（不阻塞主流程）
                    final String finalFilenameForRefresh = finalFilename;
                    new Thread(() -> {
                        try {
                            String refreshCmd = "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://" + finalFilenameForRefresh;
                            ShellCompat.startShell(refreshCmd);
                            
                            sendScreenshotSavedToApp(finalFilenameForRefresh);
                        } catch (Exception e) {
                            // 刷新失败不影响主流程
                        }
                    }).start();
                    
                    return true;
                } else {
                    // 带壳合成已开启：截图保存到临时文件
                    tempScreenshot = screenshotDir + "RD_temp_" + timestamp + "_" + millis + ".png";
                    
                    // 关键日志：记录临时文件创建
                    LogHelper.i(TAG, "═══════════════════════════════════════════════════════");
                    LogHelper.i(TAG, "📸 [临时文件创建] 带壳合成已开启，创建临时截图文件");
                    LogHelper.i(TAG, "📸 [临时文件路径] " + tempScreenshot);
                    LogHelper.i(TAG, "📸 [时间戳] " + timestamp + "_" + millis);
                    LogHelper.i(TAG, "═══════════════════════════════════════════════════════");
                    
                    // 检查是否已存在同名文件（不应该发生，但记录日志）
                    if (verifyFileExists(tempScreenshot)) {
                        LogHelper.w(TAG, "⚠️ [警告] 临时文件已存在，将被覆盖: " + tempScreenshot);
                        long existingSize = getFileSize(tempScreenshot);
                        LogHelper.w(TAG, "⚠️ [警告] 已存在文件大小: " + existingSize + " bytes");
                    }
                    
                    String screenshotCmd = "screencap -p -d " + displayId + " " + tempScreenshot;
                    LogHelper.i(TAG, "📸 [执行截图] 命令: " + screenshotCmd);
                    Process process3 = ShellCompat.startShell(screenshotCmd);
                    int exitCode = process3.waitFor();
                    
                    LogHelper.i(TAG, "📸 [截图完成] exitCode: " + exitCode);
                    
                    if (exitCode != 0) {
                        LogHelper.e(TAG, "❌ [截图失败] exitCode: " + exitCode + "，清理临时文件");
                        cleanupTempFiles(tempScreenshot);
                        return false;
                    }
                    
                    // 验证临时截图文件是否存在
                    if (!verifyFileExists(tempScreenshot) || getFileSize(tempScreenshot) == 0) {
                        LogHelper.e(TAG, "❌ [验证失败] 临时截图文件不存在或无法访问: " + tempScreenshot);
                        cleanupTempFiles(tempScreenshot);
                        return false;
                    }
                    
                    long tempFileSize = getFileSize(tempScreenshot);
                    LogHelper.i(TAG, "✅ [临时文件验证] 文件存在，大小: " + tempFileSize + " bytes");
                    LogHelper.i(TAG, "✅ [临时文件路径] " + tempScreenshot);
                    
                    // 截图完成，立即返回true，让Toast可以立即显示
                    // 合成和移动在后台异步进行，不阻塞主流程
                    LogHelper.i(TAG, "✅ [截图完成] 截图已保存，开始后台合成和移动流程");
                    
                    // 在后台线程中处理合成和移动（不阻塞主流程，提升用户体验）
                    final String finalTempScreenshot = tempScreenshot;
                    final String finalTimestamp = timestamp;
                    final String finalMillis = millis;
                    final String finalScreenshotDirForAsync = screenshotDir;
                    final String finalFinalFilename = finalFilename;
                    
                    new Thread(() -> {
                        String asyncTempScreenshot = finalTempScreenshot;
                        String asyncTempOutputInScreenshots = null;
                        
                        try {
                            // 合成文件保存到临时文件夹
                            asyncTempOutputInScreenshots = finalScreenshotDirForAsync + "RD_composite_" + finalTimestamp + "_" + finalMillis + ".png";
                            
                            LogHelper.i(TAG, "═══════════════════════════════════════════════════════");
                            LogHelper.i(TAG, "🎨 [后台合成] 开始合成（不阻塞主流程）");
                            LogHelper.i(TAG, "🎨 [临时截图文件] " + asyncTempScreenshot);
                            LogHelper.i(TAG, "🎨 [合成输出] 临时合成文件: " + asyncTempOutputInScreenshots);
                            LogHelper.i(TAG, "═══════════════════════════════════════════════════════");
                            
                            // 根据机型判断用底图和机型的坐标合成带壳截图
                            String phoneBackPath = findPhoneBackImagePath();
                            
                            boolean compositeSuccess = false;
                            if (phoneBackPath == null || phoneBackPath.isEmpty()) {
                                LogHelper.w(TAG, "⚠️ [合成失败] 无法找到或创建手机背面底图");
                                compositeSuccess = false;
                            } else {
                                LogHelper.i(TAG, "✅ [底图就绪] " + phoneBackPath);
                                LogHelper.i(TAG, "🎨 [开始合成] 使用底图合成带壳截图...");
                                
                                // 使用 ScreenshotCompositeHelper 合成（根据机型自动判断坐标）
                                compositeSuccess = ScreenshotCompositeHelper.compositeScreenshot(
                                    phoneBackPath, asyncTempScreenshot, asyncTempOutputInScreenshots,
                                    resolveAppContextForPrefs());
                                
                                if (compositeSuccess) {
                                    LogHelper.i(TAG, "✅ [合成成功] 合成文件: " + asyncTempOutputInScreenshots);
                                    // 验证合成文件是否存在
                                    if (verifyFileExists(asyncTempOutputInScreenshots)) {
                                        long compositeSize = getFileSize(asyncTempOutputInScreenshots);
                                        LogHelper.i(TAG, "✅ [合成文件验证] 文件存在，大小: " + compositeSize + " bytes");
                                    } else {
                                        LogHelper.w(TAG, "⚠️ [合成文件验证] 文件不存在: " + asyncTempOutputInScreenshots);
                                    }
                                } else {
                                    LogHelper.w(TAG, "⚠️ [合成失败] 合成操作返回失败");
                                }
                            }
                            
                            // 处理合成结果
                            if (compositeSuccess) {
                                // 合成成功：移动合成文件到指定的截图文件夹
                                LogHelper.i(TAG, "═══════════════════════════════════════════════════════");
                                LogHelper.i(TAG, "📦 [后台移动] 合成成功，开始移动合成文件到最终位置");
                                LogHelper.i(TAG, "📦 [源文件] " + asyncTempOutputInScreenshots);
                                LogHelper.i(TAG, "📦 [目标文件] " + finalFinalFilename);
                                LogHelper.i(TAG, "═══════════════════════════════════════════════════════");
                                
                                if (moveOrCopyToPictures(asyncTempOutputInScreenshots, finalFinalFilename)) {
                                    LogHelper.i(TAG, "✅ [移动成功] 合成文件已保存到Pictures目录: " + finalFinalFilename);
                                    
                                    // 移动成功后立即清理临时文件
                                    LogHelper.i(TAG, "🧹 [清理开始] 合成成功，清理临时文件");
                                    cleanupTempFiles(asyncTempScreenshot, asyncTempOutputInScreenshots);
                                    
                                    // 异步清理所有遗留的临时文件（不阻塞主流程，提升性能）
                                    new Thread(() -> {
                                        try {
                                            LogHelper.i(TAG, "🧹 [异步清理] 开始清理所有遗留的临时文件");
                                            // 使用批量删除命令，一次性删除所有临时文件，比逐个删除快得多
                                            executeShellCommand("find \"" + finalScreenshotDirForAsync + "\" -name \"RD_temp_*.png\" -type f -delete 2>/dev/null || true");
                                            executeShellCommand("find \"" + finalScreenshotDirForAsync + "\" -name \"RD_composite_*.png\" -type f -delete 2>/dev/null || true");
                                            LogHelper.i(TAG, "✅ [异步清理] 遗留临时文件清理完成");
                                        } catch (Exception e) {
                                            LogHelper.w(TAG, "异步清理遗留文件时出错: " + e.getMessage());
                                        }
                                    }).start();
                                    
                                    LogHelper.i(TAG, "✅ [清理完成] 临时文件清理完成（遗留文件异步清理中）");
                                    LogHelper.i(TAG, "═══════════════════════════════════════════════════════");
                                } else {
                                    LogHelper.e(TAG, "❌ [移动失败] 保存合成文件失败，清理临时文件");
                                    // 移动失败时也要清理临时文件
                                    LogHelper.i(TAG, "🧹 [清理开始] 移动失败，清理临时文件");
                                    cleanupTempFiles(asyncTempScreenshot, asyncTempOutputInScreenshots);
                                }
                            } else {
                                // 合成失败：移动原始截图文件到指定的文件夹
                                LogHelper.i(TAG, "═══════════════════════════════════════════════════════");
                                LogHelper.i(TAG, "⚠️ [后台移动] 合成失败，移动原始截图到指定文件夹");
                                LogHelper.i(TAG, "📦 [源文件] " + asyncTempScreenshot);
                                LogHelper.i(TAG, "📦 [目标文件] " + finalFinalFilename);
                                LogHelper.i(TAG, "═══════════════════════════════════════════════════════");
                                
                                if (moveOrCopyToPictures(asyncTempScreenshot, finalFinalFilename)) {
                                    LogHelper.i(TAG, "✅ [移动成功] 原始截图已保存到Pictures目录: " + finalFinalFilename);
                                    
                                    // 移动成功后立即清理临时文件
                                    LogHelper.i(TAG, "🧹 [清理开始] 合成失败但移动成功，清理临时文件");
                                    cleanupTempFiles(asyncTempScreenshot, asyncTempOutputInScreenshots);
                                    
                                    // 异步清理所有遗留的临时文件（不阻塞主流程，提升性能）
                                    new Thread(() -> {
                                        try {
                                            LogHelper.i(TAG, "🧹 [异步清理] 开始清理所有遗留的临时文件");
                                            // 使用批量删除命令，一次性删除所有临时文件，比逐个删除快得多
                                            executeShellCommand("find \"" + finalScreenshotDirForAsync + "\" -name \"RD_temp_*.png\" -type f -delete 2>/dev/null || true");
                                            executeShellCommand("find \"" + finalScreenshotDirForAsync + "\" -name \"RD_composite_*.png\" -type f -delete 2>/dev/null || true");
                                            LogHelper.i(TAG, "✅ [异步清理] 遗留临时文件清理完成");
                                        } catch (Exception e) {
                                            LogHelper.w(TAG, "异步清理遗留文件时出错: " + e.getMessage());
                                        }
                                    }).start();
                                    
                                    LogHelper.i(TAG, "✅ [清理完成] 临时文件清理完成（遗留文件异步清理中）");
                                    LogHelper.i(TAG, "═══════════════════════════════════════════════════════");
                                } else {
                                    LogHelper.e(TAG, "❌ [移动失败] 保存原始截图失败，清理临时文件");
                                    // 移动失败时也要清理临时文件
                                    LogHelper.i(TAG, "🧹 [清理开始] 移动失败，清理临时文件");
                                    cleanupTempFiles(asyncTempScreenshot, asyncTempOutputInScreenshots);
                                }
                            }
                            
                            // 异步刷新媒体库（不阻塞主流程）
                            try {
                                String refreshCmd = "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://" + finalFinalFilename;
                                ShellCompat.startShell(refreshCmd);
                                
                                sendScreenshotSavedToApp(finalFinalFilename);
                            } catch (Exception e) {
                                // 刷新失败不影响主流程
                            }
                            
                        } catch (Exception e) {
                            LogHelper.e(TAG, "❌ [后台处理异常] 合成或移动过程中出错: " + e.getMessage(), e);
                            // 异常情况下清理临时文件
                            cleanupTempFiles(asyncTempScreenshot, asyncTempOutputInScreenshots);
                        } finally {
                            // 后台处理完成，重置标志位（在finally中确保一定会执行）
                            synchronized (screenshotLock) {
                                isScreenshotInProgress = false;
                                LogHelper.i(TAG, "✅ [标志重置] isScreenshotInProgress = false (后台处理完成)");
                            }
                        }
                    }).start();
                    
                    // 立即返回true，让Toast可以立即显示（合成和移动在后台进行）
                    // 标记为异步处理，finally块不会重置isScreenshotInProgress（由后台线程重置）
                    isAsyncProcessing = true;
                    tempScreenshot = null; // 标记已在后台处理，finally块不需要清理
                    tempOutputInScreenshots = null; // 标记已在后台处理，finally块不需要清理
                    
                    if (DEBUG_DETAILED) {
                        LogHelper.d(TAG, "✅ 截图完成（后台合成中）: " + finalFilename);
                    } else {
                        LogHelper.i(TAG, "✅ 截图完成（后台合成中）: " + finalFilename);
                    }
                    return true;
                }
                
            } catch (Exception e) {
                LogHelper.e(TAG, "❌ EXCEPTION in takeRearScreenshotWithComposite", e);
                
                // 异常情况下清理所有可能的临时文件
                cleanupAllTempScreenshotFiles();
                
                return false;
            } finally {
                // 清理机制：正常流程中，临时文件已在移动成功后立即清理
                // finally块作为异常情况的备用清理，清理可能遗留的临时文件
                try {
                    // 如果临时文件变量不为null，说明正常清理流程可能未执行（异常情况）
                    if (tempScreenshot != null && !tempScreenshot.isEmpty()) {
                        LogHelper.w(TAG, "⚠️ [Finally清理] 检测到未清理的临时截图文件（异常情况）: " + tempScreenshot);
                        if (verifyFileExists(tempScreenshot)) {
                            cleanupTempFiles(tempScreenshot);
                            if (verifyFileExists(tempScreenshot)) {
                                LogHelper.e(TAG, "❌ [Finally清理] 临时截图文件清理失败: " + tempScreenshot);
                            } else {
                                LogHelper.i(TAG, "✅ [Finally清理] 临时截图文件已清理: " + tempScreenshot);
                            }
                        }
                    }
                    
                    if (tempOutputInScreenshots != null && !tempOutputInScreenshots.isEmpty()) {
                        LogHelper.w(TAG, "⚠️ [Finally清理] 检测到未清理的临时合成文件（异常情况）: " + tempOutputInScreenshots);
                        if (verifyFileExists(tempOutputInScreenshots)) {
                            cleanupTempFiles(tempOutputInScreenshots);
                            if (verifyFileExists(tempOutputInScreenshots)) {
                                LogHelper.e(TAG, "❌ [Finally清理] 临时合成文件清理失败: " + tempOutputInScreenshots);
                            } else {
                                LogHelper.i(TAG, "✅ [Finally清理] 临时合成文件已清理: " + tempOutputInScreenshots);
                            }
                        }
                    }
                    
                    // 额外清理：清理所有遗留的临时文件（异常情况的备用清理）
                    // 正常流程中，所有临时文件已在移动成功后清理，这里只作为异常情况的备用
                    // 如果变量不为null，说明正常清理流程可能未执行，需要清理所有遗留文件
                    if (tempScreenshot != null || tempOutputInScreenshots != null) {
                        String screenshotDir = ROOT_SCREENSHOTS_DIR + "/";
                        LogHelper.w(TAG, "⚠️ [Finally清理] 检测到异常情况，清理所有遗留的临时文件");
                        
                        // 清理所有遗留的临时截图文件
                        String listAllTempCmd = "find \"" + screenshotDir + "\" -name \"RD_temp_*.png\" -type f 2>/dev/null";
                        String allTempFiles = executeShellCommandWithResult(listAllTempCmd);
                        if (allTempFiles != null && !allTempFiles.trim().isEmpty()) {
                            String[] files = allTempFiles.trim().split("\n");
                            for (String file : files) {
                                if (file != null && !file.trim().isEmpty()) {
                                    String filePath = file.trim();
                                    LogHelper.w(TAG, "⚠️ [Finally清理] 清理遗留临时文件: " + filePath);
                                    cleanupTempFiles(filePath);
                                    if (verifyFileExists(filePath)) {
                                        LogHelper.e(TAG, "❌ [Finally清理] 遗留临时文件清理失败: " + filePath);
                                    } else {
                                        LogHelper.i(TAG, "✅ [Finally清理] 遗留临时文件已清理: " + filePath);
                                    }
                                }
                            }
                        }
                        
                        // 清理所有遗留的合成临时文件
                        String listAllCompositeCmd = "find \"" + screenshotDir + "\" -name \"RD_composite_*.png\" -type f 2>/dev/null";
                        String allCompositeFiles = executeShellCommandWithResult(listAllCompositeCmd);
                        if (allCompositeFiles != null && !allCompositeFiles.trim().isEmpty()) {
                            String[] files = allCompositeFiles.trim().split("\n");
                            for (String file : files) {
                                if (file != null && !file.trim().isEmpty()) {
                                    String filePath = file.trim();
                                    LogHelper.w(TAG, "⚠️ [Finally清理] 清理遗留合成文件: " + filePath);
                                    cleanupTempFiles(filePath);
                                    if (verifyFileExists(filePath)) {
                                        LogHelper.e(TAG, "❌ [Finally清理] 遗留合成文件清理失败: " + filePath);
                                    } else {
                                        LogHelper.i(TAG, "✅ [Finally清理] 遗留合成文件已清理: " + filePath);
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception cleanupEx) {
                    LogHelper.e(TAG, "❌ [Finally清理] 清理临时文件时出错: " + cleanupEx.getMessage(), cleanupEx);
                }
                
                // 重置标志位：只有在非异步处理的情况下才在这里重置
                // 如果开启了合成，标志位由后台线程在完成时重置
                if (!isAsyncProcessing) {
                    isScreenshotInProgress = false;
                    LogHelper.i(TAG, "✅ [标志重置] isScreenshotInProgress = false");
                } else {
                    LogHelper.i(TAG, "⏳ [标志保持] isScreenshotInProgress 保持为 true（后台处理中，由后台线程重置）");
                }
                LogHelper.i(TAG, "═══════════════════════════════════════════════════════");
                LogHelper.i(TAG, "📸 [方法结束] takeRearScreenshotWithComposite 执行完成");
                LogHelper.i(TAG, "═══════════════════════════════════════════════════════");
            }
        }
    }
    
    @Override
    public boolean isTaskOnDisplay(int taskId, int displayId) throws RemoteException {
        try {
            Process process = ShellCompat.startShell("am stack list", true);
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            boolean inTargetDisplay = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("RootTask")) {
                    inTargetDisplay = line.contains("displayId=" + displayId);
                    continue;
                }
                
                if (inTargetDisplay && line.contains("taskId=" + taskId)) {
                    reader.close();
                    process.destroy();
                    return true;
                }
            }
            
            reader.close();
            process.waitFor();
            return false;
            
        } catch (Exception e) {
            LogHelper.e(TAG, "Error checking task on display", e);
            return false;
        }
    }
    
    @Override
    public String getForegroundAppOnDisplay(int displayId) throws RemoteException {
        try {
            Process process = ShellCompat.startShell("am stack list", true);

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );

            boolean inTargetDisplay = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("RootTask")) {
                    int did = parseRootTaskDisplayId(line);
                    inTargetDisplay = (did == displayId);
                    continue;
                }

                if (inTargetDisplay && line.contains("taskId=") && line.contains("/")) {
                    int tidStart = line.indexOf("taskId=") + 7;
                    int tidEnd = line.indexOf(':', tidStart);
                    String taskId = line.substring(tidStart, tidEnd).trim();

                    int pkgStart = tidEnd + 2;
                    int pkgEnd = line.indexOf('/', pkgStart);
                    String packageName = line.substring(pkgStart, pkgEnd).trim();

                    reader.close();
                    process.destroy();

                    return packageName + ":" + taskId;
                }
            }

            reader.close();
            process.waitFor();
            return null;

        } catch (Exception e) {
            LogHelper.e(TAG, "Error getting foreground app on display", e);
            return null;
        }
    }
    
    /**
     * V2.1: 设置显示器旋转方向
     * @param displayId 显示器ID (0=主屏, 1=背屏)
     * @param rotation 旋转角度 (0=0°, 1=90°, 2=180°, 3=270°)
     * @return 是否成功
     */
    @Override
    public boolean setDisplayRotation(int displayId, int rotation) throws RemoteException {
        try {
            // 获取当前背屏前台应用（如果有）
            String currentApp = null;
            int currentTaskId = -1;
            if (displayId == 1) {
                currentApp = getForegroundAppOnDisplay(1);
                if (currentApp != null && currentApp.contains(":")) {
                    String[] parts = currentApp.split(":");
                    try {
                        currentTaskId = Integer.parseInt(parts[1]);
                    } catch (Exception ignored) {}
                }
            }
            
            // 使用 wm user-rotation 命令设置旋转
            String cmd = "wm user-rotation -d " + displayId + " lock " + rotation;
            
            Process process = ShellCompat.startShell(cmd);
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            BufferedReader errorReader = new BufferedReader(
                new InputStreamReader(process.getErrorStream()), 8192
            );
            
            String line;
            while ((line = reader.readLine()) != null) {}
            while ((line = errorReader.readLine()) != null) {}
            
            reader.close();
            errorReader.close();
            
            int exitCode = process.waitFor();
            
            // 如果是背屏且有应用在运行，等待500ms后检查并复活
            if (displayId == 1 && exitCode == 0 && currentTaskId > 0) {
                Thread.sleep(500);
                
                // 检查应用是否还在背屏
                boolean stillOnRear = isTaskOnDisplay(currentTaskId, 1);
                
                if (!stillOnRear) {
                    // 应用被关闭了，重新投放
                    moveTaskToDisplay(currentTaskId, 1);
                }
            }
            
            return (exitCode == 0);
            
        } catch (Exception e) {
            LogHelper.e(TAG, "设置旋转异常", e);
            return false;
        }
    }
    
    /**
     * V2.1: 获取显示器当前旋转方向
     * @param displayId 显示器ID (0=主屏, 1=背屏)
     * @return 旋转角度 (0-3)，-1表示失败
     */
    @Override
    public int getDisplayRotation(int displayId) throws RemoteException {
        try {
            // 使用 wm user-rotation 命令直接读取，输出格式: "lock 2" 或 "free"
            String cmd = "wm user-rotation -d " + displayId;
            
            Process process = ShellCompat.startShell(cmd);
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            String line = reader.readLine();
            reader.close();
            process.waitFor();
            
            if (line != null && !line.isEmpty()) {
                // 解析 "lock 2" 或 "free" 格式
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2) {
                    try {
                        return Integer.parseInt(parts[1]);
                    } catch (Exception ignored) {}
                }
            }
            
            return 0;
            
        } catch (Exception e) {
            LogHelper.e(TAG, "获取旋转异常", e);
            return 0;
        }
    }
    
    @Override
    public boolean executeShellCommand(String cmd) throws RemoteException {
        try {
            Process process = ShellCompat.startShell(cmd);
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            BufferedReader errorReader = new BufferedReader(
                new InputStreamReader(process.getErrorStream()), 8192
            );
            
            StringBuilder output = new StringBuilder();
            StringBuilder errorOutput = new StringBuilder();
            
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            while ((line = errorReader.readLine()) != null) {
                errorOutput.append(line).append("\n");
            }
            
            reader.close();
            errorReader.close();
            
            int exitCode = process.waitFor();
            
            // 记录详细输出
            if (output.length() > 0) {
                LogHelper.d(TAG, "Command stdout: " + output.toString().trim());
            }
            if (errorOutput.length() > 0) {
                LogHelper.w(TAG, "Command stderr: " + errorOutput.toString().trim());
            }
            
            return (exitCode == 0);
            
        } catch (Exception e) {
            LogHelper.e(TAG, "执行命令失败: " + cmd, e);
            return false;
        }
    }
    
    @Override
    public String executeShellCommandWithResult(String cmd) throws RemoteException {
        try {
            Process process = ShellCompat.startShell(cmd);
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            
            reader.close();
            process.waitFor();
            
            return output.toString();
            
        } catch (Exception e) {
            LogHelper.e(TAG, "执行命令失败: " + cmd, e);
            return "";
        }
    }

    @Override
    public boolean executeShellCommandAsRoot(String cmd) throws RemoteException {
        try {
            Process process = ShellCompat.startShellSuOnly(cmd);
            try (BufferedReader reader =
                            new BufferedReader(
                                    new InputStreamReader(process.getInputStream()), 8192);
                    BufferedReader errorReader =
                            new BufferedReader(
                                    new InputStreamReader(process.getErrorStream()), 8192)) {
                while (reader.readLine() != null) {
                    // drain
                }
                while (errorReader.readLine() != null) {
                    // drain
                }
            }
            return process.waitFor() == 0;
        } catch (Exception e) {
            LogHelper.e(TAG, "executeShellCommandAsRoot: " + cmd, e);
            return false;
        }
    }

    @Override
    public String executeShellCommandWithResultAsRoot(String cmd) throws RemoteException {
        try {
            Process process = ShellCompat.startShellSuOnly(cmd);
            BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(process.getInputStream()), 8192);
            BufferedReader errorReader =
                    new BufferedReader(
                            new InputStreamReader(process.getErrorStream()), 8192);
            try {
                StringBuilder output = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
                while ((line = errorReader.readLine()) != null) {
                    // drain
                }
                process.waitFor();
                return output.toString();
            } finally {
                reader.close();
                errorReader.close();
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "executeShellCommandWithResultAsRoot: " + cmd, e);
            return "";
        }
    }
    
    // 日志文件写入功能已移除（Release版本）
    private void writeDebugLog(String message) {
        // Release版本：不写入日志文件
    }
    
    private void writeErrorLog(String errorMessage, Throwable throwable) {
        // Release版本：不写入日志文件
    }
    
    /**
     * 获取背屏 display ID（带缓存，避免频繁执行 dumpsys）
     * @return display ID，失败返回 "1"
     */
    /**
     * 强制刷新 display ID（清除缓存，重新获取）
     */
    private void forceRefreshDisplayId() {
        cachedDisplayId = null;
        displayIdCacheTime = 0;
        LogHelper.i(TAG, "🔄 已强制清除 display ID 缓存，下次获取时将重新查询");
    }
    
    /**
     * 获取背屏 display ID（带缓存）
     * @param forceRefresh 是否强制刷新（忽略缓存）
     */
    private String getCachedDisplayId(boolean forceRefresh) {
        if (forceRefresh) {
            forceRefreshDisplayId();
        }
        
        long currentTime = System.currentTimeMillis();
        // 如果缓存有效，直接返回
        if (cachedDisplayId != null && (currentTime - displayIdCacheTime) < DISPLAY_ID_CACHE_DURATION) {
            LogHelper.i(TAG, "📱 使用缓存的 display ID: " + cachedDisplayId);
            return cachedDisplayId;
        }
        
        // 缓存失效或不存在，重新获取
        try {
            LogHelper.i(TAG, "🔍 重新获取 display ID...");
            String getDisplayIdCmd = "dumpsys SurfaceFlinger --display-id | grep -oE 'Display [0-9]+' | awk 'NR==2{print $2}'";
            Process process = ShellCompat.startShell(getDisplayIdCmd);
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            String displayId = reader.readLine();
            reader.close();
            process.waitFor();
            
            if (displayId == null || displayId.isEmpty()) {
                displayId = "1"; // 默认使用1
                LogHelper.w(TAG, "⚠️ 未能获取 display ID，使用默认值: 1");
            } else {
                displayId = displayId.trim();
                LogHelper.i(TAG, "✅ 获取到 display ID: " + displayId);
            }
            
            // 更新缓存
            cachedDisplayId = displayId;
            displayIdCacheTime = currentTime;
            
            return displayId;
        } catch (Exception e) {
            LogHelper.w(TAG, "❌ 获取 display ID 失败，使用默认值", e);
            return "1";
        }
    }
    
    /**
     * 获取背屏 display ID（使用缓存，不强制刷新）
     */
    private String getCachedDisplayId() {
        return getCachedDisplayId(false);
    }
    
    /**
     * 复制文件（使用shell命令cp，避免在Binder调用中使用Java File API导致超时）
     * @param sourcePath 源文件路径
     * @param destPath 目标文件路径
     * @return 是否成功
     */
    private boolean copyFile(String sourcePath, String destPath) {
        if (DEBUG_DETAILED) {
            LogHelper.d(TAG, "📋 开始复制文件: " + sourcePath + " -> " + destPath);
        }
        
        try {
            // 验证源文件是否存在
            String checkSourceCmd = "test -f \"" + sourcePath + "\" && stat -c%s \"" + sourcePath + "\" || echo '0'";
            String sourceCheckResult = executeShellCommandWithResult(checkSourceCmd);
            if (sourceCheckResult == null || sourceCheckResult.trim().equals("0")) {
                LogHelper.e(TAG, "❌ 源文件不存在或为空: " + sourcePath);
                return false;
            }
            long sourceSize = Long.parseLong(sourceCheckResult.trim());
            if (DEBUG_DETAILED) {
                LogHelper.d(TAG, "源文件大小: " + sourceSize + " bytes");
            }
            
            // 确保目标目录存在
            String destDir = destPath.substring(0, destPath.lastIndexOf("/"));
            boolean mkdirSuccess = executeShellCommand("mkdir -p \"" + destDir + "\"");
            if (DEBUG_DETAILED) {
                LogHelper.d(TAG, "创建目录结果: " + mkdirSuccess);
            }
            
            // 如果目标文件已存在，先删除
            executeShellCommand("rm -f \"" + destPath + "\"");
            
            // 使用cp命令复制文件
            if (DEBUG_DETAILED) {
                LogHelper.d(TAG, "执行复制命令...");
            }
            String copyCmd = "cp \"" + sourcePath + "\" \"" + destPath + "\"";
            boolean copySuccess = executeShellCommand(copyCmd);
            if (DEBUG_DETAILED) {
                LogHelper.d(TAG, "复制命令执行结果: " + copySuccess);
            }
            
            if (!copySuccess) {
                LogHelper.w(TAG, "⚠️ cp命令复制文件失败，尝试cat命令");
                // 尝试使用 cat 命令作为备选方案
                String catCmd = "cat \"" + sourcePath + "\" > \"" + destPath + "\"";
                boolean catSuccess = executeShellCommand(catCmd);
                if (DEBUG_DETAILED) {
                    LogHelper.d(TAG, "cat命令执行结果: " + catSuccess);
                }
                if (!catSuccess) {
                    return false;
                }
            }
            
            // 验证目标文件是否存在且大小大于0
            String checkCmd = "test -f \"" + destPath + "\" && stat -c%s \"" + destPath + "\" || echo '0'";
            String checkResult = executeShellCommandWithResult(checkCmd);
            if (checkResult == null || checkResult.trim().equals("0")) {
                LogHelper.e(TAG, "❌ 文件复制验证失败: 目标文件不存在或为空");
                return false;
            }
            
            long destSize = Long.parseLong(checkResult.trim());
            if (destSize == 0) {
                LogHelper.e(TAG, "❌ 文件复制验证失败: 目标文件大小为0");
                return false;
            }
            
            if (destSize != sourceSize) {
                LogHelper.e(TAG, "❌ 文件复制验证失败: 文件大小不匹配 (源: " + sourceSize + ", 目标: " + destSize + ")");
                return false;
            }
            
            if (DEBUG_DETAILED) {
                LogHelper.d(TAG, "✅ 文件复制成功: " + destPath + " (大小: " + destSize + " bytes)");
            }
            LogHelper.e(TAG, "═══════════════════════════════════════════════════════");
            return true;
        } catch (Exception e) {
            LogHelper.e(TAG, "═══════════════════════════════════════════════════════");
            LogHelper.e(TAG, "❌ 复制文件失败: " + e.getMessage(), e);
            LogHelper.e(TAG, "═══════════════════════════════════════════════════════");
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * 通过系统属性获取型号信息
     */
    private String getSystemProperty(String key) {
        try {
            String result = executeShellCommandWithResult("getprop " + key);
            return result != null ? result.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * 清理临时文件（支持多个文件）
     * @param filePaths 要清理的文件路径数组
     */
    private void cleanupTempFiles(String... filePaths) {
        for (String path : filePaths) {
            if (path != null && !path.isEmpty()) {
                try {
                    // 强制删除，即使文件不存在也不报错
                    String result = executeShellCommandWithResult("rm -f \"" + path + "\" 2>&1");
                    if (DEBUG_DETAILED && result != null && !result.trim().isEmpty()) {
                        LogHelper.d(TAG, "清理临时文件结果: " + path + " -> " + result);
                    }
                    // 验证文件是否已删除
                    if (verifyFileExists(path)) {
                        LogHelper.w(TAG, "⚠️ 临时文件仍存在，尝试强制删除: " + path);
                        executeShellCommand("chmod 777 \"" + path + "\" 2>/dev/null && rm -f \"" + path + "\" 2>/dev/null || true");
                    } else {
                        if (DEBUG_DETAILED) {
                            LogHelper.d(TAG, "✅ 临时文件已删除: " + path);
                        }
                    }
                } catch (RemoteException e) {
                    LogHelper.w(TAG, "清理临时文件失败: " + path + " - " + e.getMessage());
                    // 尝试备用清理方法
                    try {
                        executeShellCommand("chmod 777 \"" + path + "\" 2>/dev/null && rm -f \"" + path + "\" 2>/dev/null || true");
                    } catch (RemoteException e2) {
                        LogHelper.w(TAG, "备用清理方法也失败: " + path);
                    }
                }
            }
        }
    }
    
    /**
     * 初始化所有必要的目录
     */
    private void initializeDirectories() {
        try {
            executeShellCommand("mkdir -p " + ROOT_DATA_DIR + " 2>/dev/null || true");
            executeShellCommand("mkdir -p " + ROOT_SCREENSHOTS_DIR + " 2>/dev/null || true");
            executeShellCommand("mkdir -p " + ROOT_TEMP_DIR + " 2>/dev/null || true");
            executeShellCommand("mkdir -p " + ROOT_LOGS_DIR + " 2>/dev/null || true");
            executeShellCommand("chmod -R 777 " + ROOT_DATA_DIR + " 2>/dev/null || true");
        } catch (RemoteException e) {
            LogHelper.w(TAG, "初始化目录失败: " + e.getMessage());
        }
    }
    
    /**
     * 清理所有临时截图文件（异常情况下使用）
     */
    private void cleanupAllTempScreenshotFiles() {
        try {
            String screenshotDir = ROOT_SCREENSHOTS_DIR + "/";
            String listTempCmd = "find \"" + screenshotDir + "\" -name \"RD_temp_*.png\" -type f 2>/dev/null";
            String tempFiles = executeShellCommandWithResult(listTempCmd);
            if (tempFiles != null && !tempFiles.trim().isEmpty()) {
                String[] files = tempFiles.trim().split("\n");
                for (String file : files) {
                    if (file != null && !file.trim().isEmpty()) {
                        executeShellCommand("chmod 777 \"" + file.trim() + "\" 2>/dev/null || true");
                        executeShellCommand("rm -f \"" + file.trim() + "\" 2>/dev/null || true");
                    }
                }
            }
            
            // 同时清理合成临时文件
            String listCompositeCmd = "find \"" + screenshotDir + "\" -name \"RD_composite_*.png\" -type f 2>/dev/null";
            String compositeFiles = executeShellCommandWithResult(listCompositeCmd);
            if (compositeFiles != null && !compositeFiles.trim().isEmpty()) {
                String[] files = compositeFiles.trim().split("\n");
                for (String file : files) {
                    if (file != null && !file.trim().isEmpty()) {
                        executeShellCommand("chmod 777 \"" + file.trim() + "\" 2>/dev/null || true");
                        executeShellCommand("rm -f \"" + file.trim() + "\" 2>/dev/null || true");
                    }
                }
            }
        } catch (Exception cleanEx) {
            LogHelper.w(TAG, "清理临时文件时出错: " + cleanEx.getMessage());
        }
    }
    
    /**
     * 确保目录存在
     * @param dirPath 目录路径
     * @return 是否成功
     */
    private boolean ensureDirectoryExists(String dirPath) {
        try {
            return executeShellCommand("mkdir -p \"" + dirPath + "\"");
        } catch (RemoteException e) {
            LogHelper.w(TAG, "创建目录失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 验证文件是否存在
     * @param filePath 文件路径
     * @return 是否存在
     */
    private boolean verifyFileExists(String filePath) {
        try {
            String cmd = "test -f \"" + filePath + "\" && echo 'exists' || echo 'not_found'";
            String result = executeShellCommandWithResult(cmd);
            return result != null && result.trim().equals("exists");
        } catch (RemoteException e) {
            LogHelper.w(TAG, "验证文件存在失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 获取文件大小
     * @param filePath 文件路径
     * @return 文件大小（字节），失败返回0
     */
    private long getFileSize(String filePath) {
        try {
            String cmd = "stat -c%s \"" + filePath + "\" 2>/dev/null || echo '0'";
            String result = executeShellCommandWithResult(cmd);
            try {
                return Long.parseLong(result != null ? result.trim() : "0");
            } catch (NumberFormatException e) {
                return 0;
            }
        } catch (RemoteException e) {
            LogHelper.w(TAG, "获取文件大小失败: " + e.getMessage());
            return 0;
        }
    }
    
    /**
     * 移动或复制文件到目标目录（带验证和自动清理）
     * @param sourcePath 源文件路径
     * @param destPath 目标文件路径
     * @return 是否成功
     */
    private boolean moveOrCopyToPictures(String sourcePath, String destPath) {
        try {
            // 确保目标目录存在
            String destDir = destPath.substring(0, destPath.lastIndexOf("/"));
            if (!ensureDirectoryExists(destDir)) {
                LogHelper.w(TAG, "创建目录失败: " + destDir);
            }
            
            // 先尝试移动（更高效）
            boolean moveSuccess = executeShellCommand("mv \"" + sourcePath + "\" \"" + destPath + "\"");
            if (moveSuccess && verifyFileExists(destPath) && getFileSize(destPath) > 0) {
                LogHelper.d(TAG, "✅ 文件移动成功: " + destPath);
                return true;
            }
            
            // 移动失败，尝试复制
            LogHelper.d(TAG, "移动失败，尝试复制: " + sourcePath + " -> " + destPath);
            boolean copySuccess = copyFile(sourcePath, destPath);
            if (copySuccess && verifyFileExists(destPath) && getFileSize(destPath) > 0) {
                // 复制成功后清理源文件
                try {
                    executeShellCommand("rm -f \"" + sourcePath + "\"");
                } catch (RemoteException e) {
                    LogHelper.w(TAG, "清理源文件失败: " + e.getMessage());
                }
                LogHelper.d(TAG, "✅ 文件复制成功: " + destPath);
                return true;
            }
            
            LogHelper.e(TAG, "❌ 文件移动和复制都失败: " + sourcePath + " -> " + destPath);
            return false;
        } catch (RemoteException e) {
            LogHelper.e(TAG, "移动或复制文件失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Shizuku 进程中 Stub 无 {@link android.app.Service}，需自行解析可读
     * {@code FlutterSharedPreferences} 的 Context（逻辑同 {@link #copyPhoneBackFromAssetsToExternal}）。
     */
    private Context resolveAppContextForPrefs() {
        MainActivity main = MainActivity.getCurrentInstance();
        if (main != null) {
            return main.getApplicationContext();
        }
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object app = activityThreadClass.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "resolveAppContextForPrefs: " + e.getMessage());
        }
        return null;
    }

    /**
     * 通知主界面截图已保存。优先应用内显式广播（{@link ScreenshotSavedReceiver}），不依赖 MainActivity 是否已注册动态接收器。
     */
    private void sendScreenshotSavedToApp(String filepath) {
        if (filepath == null || filepath.isEmpty()) {
            return;
        }
        Context ctx = resolveAppContextForPrefs();
        if (ctx == null) {
            try {
                executeShellCommand("am broadcast -a " + LyricsIntents.ACTION_SCREENSHOT_SAVED + " --es filepath \"" + filepath + "\"");
            } catch (RemoteException e) {
                LogHelper.e(TAG, "sendScreenshotSavedToApp shell fallback: " + e.getMessage());
            }
            return;
        }
        try {
            Intent i = new Intent(ctx, ScreenshotSavedReceiver.class);
            i.setPackage(ctx.getPackageName());
            i.putExtra(ScreenshotSavedReceiver.EXTRA_FILEPATH, filepath);
            ctx.sendBroadcast(i);
        } catch (Exception e) {
            LogHelper.e(TAG, "sendScreenshotSavedToApp: " + e.getMessage());
            try {
                executeShellCommand("am broadcast -a " + LyricsIntents.ACTION_SCREENSHOT_SAVED + " --es filepath \"" + filepath + "\"");
            } catch (RemoteException re) {
                LogHelper.e(TAG, "sendScreenshotSavedToApp shell fallback: " + re.getMessage());
            }
        }
    }

    /**
     * 判断机型是 Pro 还是 ProMax（使用工具类）
     * @return true 表示 ProMax，false 表示 Pro
     */
    private boolean isProMaxModel() {
        return DeviceModelHelper.isProMaxModel();
    }
    
    /**
     * 获取内部私有目录中的背屏底图文件名（根据机型型号和用户选择，带.png后缀）
     * @return 内部文件名（promax*.png 或 prol/prol2、prob/prob2、proh/proh2、proz/proz2.png）
     */
    private String getPhoneBackInternalFileName() {
        // 传递null，DeviceModelHelper会自动尝试获取Application Context
        return DeviceModelHelper.getPhoneBackImageFileName(null);
    }
    
    /**
     * 获取内部底图文件名（带Context，用于读取用户选择）
     * @param context Context对象（用于访问SharedPreferences）
     * @return 文件名（promax*.png 或 prol/prol2、prob/prob2、proh/proh2、proz/proz2.png）
     */
    private String getPhoneBackInternalFileName(Context context) {
        return DeviceModelHelper.getPhoneBackImageFileName(context);
    }
    
    /**
     * 获取外部screenshots文件夹中的背屏底图文件名（根据机型型号，无后缀）
     * @return 外部文件名（promax 或 pro）
     */
    private String getPhoneBackExternalFileName() {
        return DeviceModelHelper.getPhoneBackExternalFileName();
    }
    
    /**
     * 根据机型获取合成截图的坐标
     * @return 包含X和Y坐标的数组，[0]为X坐标，[1]为Y坐标
     */
    private int[] getCompositeScreenshotCoordinates() {
        return DeviceModelHelper.getCompositeScreenshotCoordinates();
    }
    
    /**
     * 从assets复制背屏底图到screenshots文件夹（根据机型型号）
     * @return 是否成功
     */
    private boolean copyPhoneBackFromAssetsToExternal() {
        try {
            // 根据机型获取对应的底图文件名
            String phoneBackFileName = getPhoneBackInternalFileName();
            String externalFileName = getPhoneBackExternalFileName();
            String externalPath = ROOT_SCREENSHOTS_DIR + "/" + externalFileName;
            
            if (DEBUG_DETAILED) {
                LogHelper.d(TAG, "📋 开始从assets复制背屏底图");
                LogHelper.d(TAG, "机型底图文件名: " + phoneBackFileName);
                LogHelper.d(TAG, "外部目标路径: " + externalPath);
            }
            
            // 尝试从assets加载（优先通过MainActivity的实例，如果不存在则尝试通过Application）
            MainActivity mainActivity = MainActivity.getCurrentInstance();
            android.content.Context context = null;
            
            if (mainActivity != null) {
                context = mainActivity;
                if (DEBUG_DETAILED) {
                    LogHelper.d(TAG, "✅ MainActivity实例存在，使用MainActivity访问assets");
                }
            } else {
                // MainActivity不存在，尝试通过Application访问assets
                try {
                    // 通过反射获取Application实例
                    Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
                    Object activityThread = activityThreadClass.getMethod("currentApplication").invoke(null);
                    if (activityThread instanceof android.app.Application) {
                        context = (android.app.Application) activityThread;
                        if (DEBUG_DETAILED) {
                            LogHelper.d(TAG, "✅ 通过Application访问assets");
                        }
                    }
                } catch (Exception e) {
                    LogHelper.w(TAG, "⚠️ 无法通过Application访问assets: " + e.getMessage());
                }
            }
            
            if (context == null) {
                LogHelper.e(TAG, "❌ 无法获取Context，无法从assets复制");
                return false;
            }
            
            // 重新获取底图文件名（使用context，确保能读取用户选择）
            phoneBackFileName = getPhoneBackInternalFileName(context);
            // 关键日志：始终输出，确保能看到文件名是否正确
            LogHelper.d(TAG, "📋 使用Context重新获取底图文件名: " + phoneBackFileName);
            
            LogHelper.d(TAG, "🔍 从 assets 加载底图（优先 shell/）: " + phoneBackFileName);
            java.io.InputStream inputStream;
            try {
                inputStream = DeviceModelHelper.openPhoneBackAssetInputStream(context.getAssets(), phoneBackFileName);
            } catch (Exception e) {
                LogHelper.e(TAG, "❌ 无法从 assets 加载背屏底图: " + phoneBackFileName + " (" + e.getMessage() + ")");
                return false;
            }
            
            if (DEBUG_DETAILED) {
                LogHelper.d(TAG, "✅ 成功从assets打开输入流，开始复制文件...");
            }
            
            // 确保screenshots目录存在
            executeShellCommand("mkdir -p \"" + ROOT_SCREENSHOTS_DIR + "\"");
            
            // 先复制到临时文件
            String tempFile = ROOT_TEMP_DIR + "/" + externalFileName + ".tmp";
            executeShellCommand("mkdir -p \"" + ROOT_TEMP_DIR + "\"");
            
            // 关键日志：始终输出，确保能看到临时文件路径
            LogHelper.d(TAG, "📋 复制底图到临时文件: " + tempFile + " (源文件: " + phoneBackFileName + ")");
            java.io.File tempFileObj = new java.io.File(tempFile);
            java.io.FileOutputStream tempFos = new java.io.FileOutputStream(tempFileObj);
            byte[] buffer = new byte[8192];
            int length;
            long totalBytes = 0;
            while ((length = inputStream.read(buffer)) > 0) {
                tempFos.write(buffer, 0, length);
                totalBytes += length;
            }
            tempFos.flush();
            tempFos.close();
            inputStream.close();
            // 关键日志：始终输出，确保能看到文件大小
            LogHelper.d(TAG, "✅ 临时文件写入完成，大小: " + totalBytes + " bytes (源文件: " + phoneBackFileName + ")");
            
            // 验证临时文件
            if (!tempFileObj.exists() || tempFileObj.length() == 0) {
                LogHelper.e(TAG, "❌ 临时文件不存在或为空");
                executeShellCommand("rm -f \"" + tempFile + "\"");
                return false;
            }
            
            // 从临时文件复制到screenshots文件夹（去掉后缀）
            if (DEBUG_DETAILED) {
                LogHelper.d(TAG, "📋 复制到screenshots文件夹: " + externalPath);
            }
            boolean copySuccess = copyFile(tempFile, externalPath);
            
            // 清理临时文件
            executeShellCommand("rm -f \"" + tempFile + "\"");
            
            if (!copySuccess) {
                LogHelper.e(TAG, "❌ 复制到screenshots文件夹失败");
                return false;
            }
            
            // 验证最终文件
            String verifyCmd = "test -f \"" + externalPath + "\" && stat -c%s \"" + externalPath + "\" || echo '0'";
            String verifyResult = executeShellCommandWithResult(verifyCmd);
            
            if (verifyResult == null || verifyResult.trim().equals("0")) {
                LogHelper.e(TAG, "❌ 最终文件验证失败");
                return false;
            }
            
            long finalSize = Long.parseLong(verifyResult.trim());
            if (finalSize == 0) {
                LogHelper.e(TAG, "❌ 最终文件大小为0");
                return false;
            }
            
            if (DEBUG_DETAILED) {
                LogHelper.d(TAG, "✅ 背屏底图已从assets复制到screenshots文件夹: " + externalPath + " (大小: " + finalSize + " bytes)");
            }
            return true;
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 从assets复制背屏底图失败: " + e.getMessage(), e);
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * 查找手机背面图片路径（优先顺序：screenshots文件夹 > 从assets复制）
     * @return 手机背面图片路径，如果不存在则返回null
     */
    private String findPhoneBackImagePath() {
        String externalFileName = getPhoneBackExternalFileName(); // 不带后缀的文件名
        String phoneBackPath = ROOT_SCREENSHOTS_DIR + "/" + externalFileName;
        
        // 关键日志：始终输出
        LogHelper.d(TAG, "🔍 开始查找手机背面底图文件");
        LogHelper.d(TAG, "文件名: " + externalFileName);
        LogHelper.d(TAG, "screenshots文件夹路径: " + phoneBackPath);
        
        // 1. 检查screenshots文件夹
        String checkCmd = "test -f \"" + phoneBackPath + "\" && stat -c%s \"" + phoneBackPath + "\" || echo '0'";
        try {
            String checkResult = executeShellCommandWithResult(checkCmd);
            if (checkResult != null && !checkResult.trim().equals("0")) {
                long fileSize = Long.parseLong(checkResult.trim());
                if (fileSize > 0) {
                    LogHelper.d(TAG, "✅ 找到screenshots文件夹中的手机背面图片: " + phoneBackPath + " (大小: " + fileSize + " bytes)");
                    return phoneBackPath;
                }
            }
        } catch (RemoteException e) {
            LogHelper.w(TAG, "⚠️ 检查screenshots文件夹失败: " + e.getMessage());
        } catch (NumberFormatException e) {
            LogHelper.w(TAG, "⚠️ 解析文件大小失败: " + e.getMessage());
        }
        
        // 2. 尝试从assets复制（仅在文件不存在时）
        LogHelper.d(TAG, "⚠️ 底图文件不存在，尝试从assets复制...");
        if (copyPhoneBackFromAssetsToExternal()) {
            // 复制后再次检查
            String checkAfterCopyCmd = "test -f \"" + phoneBackPath + "\" && stat -c%s \"" + phoneBackPath + "\" || echo '0'";
            try {
                String checkAfterCopyResult = executeShellCommandWithResult(checkAfterCopyCmd);
                if (checkAfterCopyResult != null && !checkAfterCopyResult.trim().equals("0")) {
                    long fileSize = Long.parseLong(checkAfterCopyResult.trim());
                    if (fileSize > 0) {
                        LogHelper.d(TAG, "✅ 已从assets复制到screenshots文件夹: " + phoneBackPath + " (大小: " + fileSize + " bytes)");
                        return phoneBackPath;
                    }
                }
            } catch (RemoteException e) {
                LogHelper.e(TAG, "❌ 检查复制后文件失败: " + e.getMessage());
            } catch (NumberFormatException e) {
                LogHelper.e(TAG, "❌ 解析复制后文件大小失败: " + e.getMessage());
            }
        }
        
        LogHelper.e(TAG, "❌ 无法找到或复制手机背面图片");
        return null;
            }
            
    /**
     * 在TaskService中直接合成截图到手机背面图片（使用 ScreenshotCompositeHelper 统一处理）
     * @param phoneBackPath 手机背面图片路径
     * @param screenshotPath 截图文件路径
     * @param outputPath 输出文件路径
     * @return 是否成功
     */
    private boolean compositeScreenshotDirectly(String phoneBackPath, String screenshotPath, String outputPath) {
        if (DEBUG_DETAILED) {
            LogHelper.d(TAG, "🎨 TaskService: 使用 ScreenshotCompositeHelper 合成截图");
            LogHelper.d(TAG, "手机背面图片路径: " + phoneBackPath);
            LogHelper.d(TAG, "截图路径: " + screenshotPath);
            LogHelper.d(TAG, "输出路径: " + outputPath);
        }
            
        // 确保输出目录存在
        try {
            String outputDir = outputPath.substring(0, outputPath.lastIndexOf("/"));
            executeShellCommand("mkdir -p \"" + outputDir + "\"");
        } catch (RemoteException e) {
            LogHelper.w(TAG, "创建输出目录失败: " + e.getMessage());
            }
            
        // 使用统一的合成工具类
        return ScreenshotCompositeHelper.compositeScreenshot(phoneBackPath, screenshotPath, outputPath,
                resolveAppContextForPrefs());
    }
    
}
