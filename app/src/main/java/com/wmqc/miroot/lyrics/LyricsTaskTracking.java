package com.wmqc.miroot.lyrics;

import androidx.annotation.Nullable;

/**
 * 记录最后一次移动到背屏的任务（供后续扩展）。
 */
public final class LyricsTaskTracking {

    private static String lastMovedPackage;
    private static int lastTaskId = -1;
    private static boolean rearScreenActive;

    private LyricsTaskTracking() {}

    public static void saveLastTask(String packageName, int taskId) {
        lastMovedPackage = packageName;
        lastTaskId = taskId;
        rearScreenActive = true;
    }

    public static void clearLastTask() {
        lastMovedPackage = null;
        lastTaskId = -1;
        rearScreenActive = false;
    }

    public static boolean hasActiveTask() {
        return rearScreenActive && lastMovedPackage != null;
    }

    /** @return 最后一次移动到背屏的 taskId，无效时为 -1 */
    public static int getLastTaskId() {
        return lastTaskId;
    }

    @Nullable
    public static String getLastMovedPackage() {
        return lastMovedPackage;
    }

    /**
     * {@code package:taskId}，供充电动画恢复逻辑比对背屏前台。
     */
    @Nullable
    public static String getLastMovedLine() {
        if (lastMovedPackage == null || lastTaskId < 0) {
            return null;
        }
        return lastMovedPackage + ":" + lastTaskId;
    }
}
