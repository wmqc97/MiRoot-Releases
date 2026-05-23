package com.wmqc.miroot.lyrics;

import android.content.pm.PackageManager;
import rikka.shizuku.Shizuku;

/**
 * 特权模式标签（用于 UI）：检测时优先 Root（su），否则 Shizuku。
 * 实际执行 shell 见 {@link ShellCompat}：每次调用先 {@code su -c}，失败再 Shizuku，与此处缓存的 mode 可不一致。
 */
public final class PrivilegeBackend {

    public enum Mode {
        /** 尚未检测 */
        UNKNOWN,
        ROOT,
        SHIZUKU,
        NONE
    }

    private static volatile Mode mode = Mode.UNKNOWN;

    private PrivilegeBackend() {}

    public static Mode getMode() {
        return mode;
    }

    public static void setMode(Mode m) {
        mode = m != null ? m : Mode.UNKNOWN;
    }

    public static boolean isPrivileged() {
        Mode m = mode;
        return m == Mode.ROOT || m == Mode.SHIZUKU;
    }

    /**
     * 供 Flutter 展示：root / shizuku / 空字符串
     */
    public static String getModeLabel() {
        switch (mode) {
            case ROOT:
                return "Root";
            case SHIZUKU:
                return "Shizuku";
            default:
                return "";
        }
    }

    static boolean testRootSu() {
        try {
            Process process = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean testShizukuGranted() {
        try {
            if (!Shizuku.pingBinder()) {
                return false;
            }
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 同步检测并更新 {@link #mode}：优先 Root，否则 Shizuku。
     */
    public static synchronized void refreshSync() {
        if (testRootSu()) {
            mode = Mode.ROOT;
            return;
        }
        if (testShizukuGranted()) {
            mode = Mode.SHIZUKU;
            return;
        }
        mode = Mode.NONE;
    }

    /**
     * 在仍为 UNKNOWN 时检测一次（避免 TaskService 早于 Flutter 首次检查时执行命令）。
     */
    public static void refreshIfUnknown() {
        if (mode != Mode.UNKNOWN) {
            return;
        }
        refreshSync();
    }
}
