package com.wmqc.miroot.charging;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import com.wmqc.miroot.car.CarControlIntents;
import com.wmqc.miroot.lyrics.LogHelper;
import com.wmqc.miroot.lyrics.LyricsIntents;

/**
 * 充电覆盖期的投屏恢复快照。
 * 保留现有充电 Activity 方案，仅补“进入前记忆 + 结束前恢复触发”。
 */
public final class ChargingProjectionRestoreState {

    private static final String TAG = "ChargingProjectionState";
    private static final long SNAPSHOT_TTL_MS = 60_000L;

    public enum ProjectionType {
        NONE,
        MUSIC,
        CAR
    }

    private static volatile ProjectionType projectionType = ProjectionType.NONE;
    private static volatile int rearDisplayId = 1;
    private static volatile int rearTaskId = -1;
    private static volatile boolean wasPlaying;
    private static volatile long capturedAtElapsed;
    private static volatile boolean restoreDispatched;

    private ChargingProjectionRestoreState() {
    }

    public static synchronized void capture(
        ProjectionType type,
        int displayId,
        int taskId,
        boolean playing
    ) {
        projectionType = type != null ? type : ProjectionType.NONE;
        rearDisplayId = displayId > 0 ? displayId : 1;
        rearTaskId = taskId;
        wasPlaying = playing;
        capturedAtElapsed = SystemClock.elapsedRealtime();
        restoreDispatched = false;
        LogHelper.d(
            TAG,
            "capture: type=" + projectionType
                + ", displayId=" + rearDisplayId
                + ", taskId=" + rearTaskId
                + ", playing=" + wasPlaying
        );
    }

    public static synchronized void clear() {
        projectionType = ProjectionType.NONE;
        rearDisplayId = 1;
        rearTaskId = -1;
        wasPlaying = false;
        capturedAtElapsed = 0L;
        restoreDispatched = false;
    }

    public static synchronized boolean hasActiveSnapshot() {
        if (projectionType == ProjectionType.NONE) {
            return false;
        }
        long captured = capturedAtElapsed;
        if (captured <= 0L) {
            return false;
        }
        return (SystemClock.elapsedRealtime() - captured) <= SNAPSHOT_TTL_MS;
    }

    /**
     * 仅当目标是“官方主题桌面”时返回 true。
     * 规则：
     * - 已识别为音乐/车控投屏：false
     * - 存在待恢复 taskId（含其它自定义背屏界面）：false
     * - 其余（无投屏快照且无 taskId）：true
     */
    public static synchronized boolean shouldRestoreOfficialGestureService() {
        if (projectionType != ProjectionType.NONE) {
            return false;
        }
        return rearTaskId <= 0;
    }

    public static synchronized void triggerRestoreIfNeeded(Context context, String source) {
        if (context == null) {
            return;
        }
        if (restoreDispatched) {
            return;
        }
        if (!hasActiveSnapshot()) {
            return;
        }
        if (projectionType == ProjectionType.NONE) {
            return;
        }
        Intent i;
        if (projectionType == ProjectionType.MUSIC) {
            i = new Intent(LyricsIntents.ACTION_OPEN_MUSIC_PROJECTION);
            i.putExtra(
                LyricsIntents.EXTRA_MUSIC_PROJECTION_OP,
                LyricsIntents.VALUE_MUSIC_PROJECTION_OP_START
            );
        } else if (projectionType == ProjectionType.CAR) {
            i = new Intent(CarControlIntents.ACTION_OPEN_CAR_CONTROL_PROJECTION);
            i.putExtra(
                CarControlIntents.EXTRA_CAR_PROJECTION_OP,
                CarControlIntents.VALUE_CAR_PROJECTION_OP_START
            );
        } else {
            return;
        }
        i.setPackage(context.getPackageName());
        context.sendBroadcast(i);
        restoreDispatched = true;
        LogHelper.d(
            TAG,
            "trigger restore from " + source
                + ": type=" + projectionType
                + ", displayId=" + rearDisplayId
                + ", taskId=" + rearTaskId
                + ", playing=" + wasPlaying
        );
    }
}
