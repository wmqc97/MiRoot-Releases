/*
 * 日志工具类：所有级别（v/d/i/w/e）仅在 Debug 构建（BuildConfig.DEBUG）下输出。
 * 诊断类（d/i/w/v 及无 Throwable 的 e）按 tag+消息摘要节流，避免刷屏；带 Throwable 的 e 不节流。
 */
package com.wmqc.miroot.lyrics;

import com.wmqc.miroot.BuildConfig;

import android.util.Log;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LogHelper {
    /** 仅在 Debug 构建时输出诊断日志 */
    private static final boolean DEBUG = BuildConfig.DEBUG;

    /** 默认节流间隔（同一 tag 在此时间内最多输出一次） */
    private static final long DEFAULT_THROTTLE_MS = 2000;

    /** 按 tag 记录上次输出时间，用于节流 */
    private static final Map<String, Long> throttleMap = new ConcurrentHashMap<>();

    /**
     * 检查是否允许输出（节流：同一 key 在 intervalMs 内只允许一次；key = tag + 消息摘要，避免不同消息互相节流）
     */
    private static boolean shouldLog(String tag, String msg, long intervalMs) {
        if (!DEBUG) return false;
        if (msg != null) {
            // 屏蔽 InsetsSource/RenderInspector 这类无意义重复警告，避免影响 RenderInspector 渲染/耗时。
            if (msg.contains("InsetsSource") || msg.contains("RenderInspector")) {
                return false;
            }
        }
        int len = (msg != null) ? Math.min(100, msg.length()) : 0;
        String key = tag + "|" + (msg != null && len > 0 ? msg.substring(0, len) : "");
        long now = System.currentTimeMillis();
        Long last = throttleMap.get(key);
        if (last != null && (now - last) < intervalMs) return false;
        throttleMap.put(key, now);
        return true;
    }

    /** 诊断：仅 Debug + 节流（按 tag+消息摘要，同一摘要 2s 内只输出一次） */
    public static void d(String tag, String msg) {
        if (shouldLog(tag, msg, DEFAULT_THROTTLE_MS)) {
            Log.d(tag, msg);
        }
    }

    public static void d(String tag, String msg, Throwable tr) {
        if (shouldLog(tag, msg, DEFAULT_THROTTLE_MS)) {
            Log.d(tag, msg, tr);
        }
    }

    /** 诊断：仅 Debug + 自定义节流间隔（毫秒），用于高频路径 */
    public static void dThrottled(String tag, String msg, long intervalMs) {
        if (shouldLog(tag, msg, intervalMs)) {
            Log.d(tag, msg);
        }
    }

    public static void i(String tag, String msg) {
        if (shouldLog(tag, msg, DEFAULT_THROTTLE_MS)) {
            Log.i(tag, msg);
        }
    }

    public static void i(String tag, String msg, Throwable tr) {
        if (shouldLog(tag, msg, DEFAULT_THROTTLE_MS)) {
            Log.i(tag, msg, tr);
        }
    }

    public static void iThrottled(String tag, String msg, long intervalMs) {
        if (shouldLog(tag, msg, intervalMs)) {
            Log.i(tag, msg);
        }
    }

    public static void w(String tag, String msg) {
        if (shouldLog(tag, msg, DEFAULT_THROTTLE_MS)) {
            Log.w(tag, msg);
        }
    }

    public static void w(String tag, String msg, Throwable tr) {
        if (shouldLog(tag, msg, DEFAULT_THROTTLE_MS)) {
            Log.w(tag, msg, tr);
        }
    }

    public static void wThrottled(String tag, String msg, long intervalMs) {
        if (shouldLog(tag, msg, intervalMs)) {
            Log.w(tag, msg);
        }
    }

    /** 错误：仅 Debug + 节流；带 Throwable 时仅 Debug 不节流 */
    public static void e(String tag, String msg) {
        if (shouldLog(tag, msg, DEFAULT_THROTTLE_MS)) {
            Log.e(tag, msg);
        }
    }

    public static void e(String tag, String msg, Throwable tr) {
        if (DEBUG) {
            Log.e(tag, msg, tr);
        }
    }

    public static void v(String tag, String msg) {
        if (shouldLog(tag, msg, DEFAULT_THROTTLE_MS)) {
            Log.v(tag, msg);
        }
    }

    public static void v(String tag, String msg, Throwable tr) {
        if (shouldLog(tag, msg, DEFAULT_THROTTLE_MS)) {
            Log.v(tag, msg, tr);
        }
    }

    /**
     * 截断过长字符串，避免 logcat 刷屏与大字符串拼接。
     */
    public static String truncateForLog(String s, int maxLen) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        if (s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen) + "...(truncated,len=" + s.length() + ")";
    }

    /**
     * 仅 Debug：记录 HTTP/API 等大段响应为「长度 + 预览」，不做整包拼接进单条超长 log。
     */
    public static void dResponsePreview(String tag, String label, String body, int previewMax) {
        if (!DEBUG) {
            return;
        }
        int len = body == null ? 0 : body.length();
        String preview = truncateForLog(body, previewMax);
        Log.d(tag, label + " len=" + len + " preview=" + preview);
    }

    /**
     * 仅 Debug：不节流，用于需连贯多行的排查（例如 AI 目录列表封面手动绑定与换视频后的同步路径）。
     * Release 构建中不输出。
     */
    public static void dDebug(String tag, String msg) {
        if (!DEBUG) {
            return;
        }
        Log.d(tag, msg);
    }
}
