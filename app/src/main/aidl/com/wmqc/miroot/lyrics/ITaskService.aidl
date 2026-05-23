package com.wmqc.miroot.lyrics;

interface ITaskService {
    void destroy() = 16777114;

    String getCurrentForegroundApp() = 1;

    int getTaskIdByPackage(String packageName) = 2;

    boolean moveTaskToDisplay(int taskId, int displayId) = 3;

    boolean launchWakeActivity(int displayId) = 4;

    boolean forceStatusBarToMainDisplay() = 5;

    boolean disableSubScreenLauncher() = 6;

    boolean enableSubScreenLauncher() = 7;

    boolean isLauncherProcessRunning() = 8;

    boolean killLauncherProcess() = 9;

    boolean collapseStatusBar() = 11;

    int getCurrentRearDpi() = 12;

    boolean setRearDpi(int dpi) = 13;

    boolean resetRearDpi() = 14;

    boolean takeRearScreenshot() = 15;

    boolean takeRearScreenshotWithComposite(boolean compositeToPhoneBack) = 22;

    boolean isTaskOnDisplay(int taskId, int displayId) = 16;

    String getForegroundAppOnDisplay(int displayId) = 17;

    boolean setDisplayRotation(int displayId, int rotation) = 18;

    int getDisplayRotation(int displayId) = 19;

    boolean executeShellCommand(String cmd) = 20;

    String executeShellCommandWithResult(String cmd) = 21;

    /** 始终经 {@code su -c} 执行，用于读 /data/data 等他应用私有目录；无 su 时失败。 */
    boolean executeShellCommandAsRoot(String cmd) = 24;

    String executeShellCommandWithResultAsRoot(String cmd) = 25;

    /** 充电动画专用：无条件对 com.xiaomi.subscreencenter force-stop，不受设置页「禁用官方背屏服务」开关约束。 */
    boolean forceStopOfficialSubscreenForCharging() = 23;

    /**
     * 第三方应用投屏：总开关 [OfficialSubscreenServiceGate] + 应用页范围策略
     * [com.wmqc.miroot.rear.AppProjectionOfficialGesturePolicy] 均满足时才 force-stop
     * com.xiaomi.subscreencenter。
     */
    boolean disableSubScreenLauncherForAppProjection(String packageName) = 26;

    /** 与 {@link #disableSubScreenLauncherForAppProjection(String)} 同一策略，供 Keeper 初始杀进程使用。 */
    boolean killLauncherProcessForAppProjection(String packageName) = 27;

    /**
     * 充电动画等对官方背屏中心 force-stop 之后：仅 {@code pm enable} 包与组件，
     * 不执行 {@code am start} 拉起官方 Launcher，避免打断即将恢复的 MiRoot 背屏投屏；
     * 便于副屏边缘返回手势所需进程尽快恢复。
     */
    boolean enableOfficialSubscreenPackageOnly() = 28;

    /** 背屏栈顶 Activity 组件，格式 {@code pkg/cls:taskId}；解析失败返回 null。 */
    String getForegroundComponentOnDisplay(int displayId) = 29;
}
