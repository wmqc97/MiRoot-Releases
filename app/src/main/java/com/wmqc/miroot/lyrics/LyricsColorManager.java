package com.wmqc.miroot.lyrics;

import android.graphics.Color;
import android.graphics.Paint;

/**
 * 歌词颜色管理器（全局单例）。
 *
 * 统一管理歌词高亮、边框描边、跑马灯的颜色：
 * - 固定色模式：用户通过色盘选择一个颜色，所有组件直接使用。
 * - 随机色模式：从高饱和可读色池中按时间间隔自然过渡切换。
 * - 所有组件通过 {@link #getColor()} 或 {@link #getColor(Paint)} 读取，保证同色。
 */
public final class LyricsColorManager {

    // ---- 高饱和可读色池（60 色，HSV 高饱和高亮度，保证深色/浅色背景均可读） ----
    private static final int[] COLOR_POOL = {
        0xFFFF3D00, // 炽橙红
        0xFFFF1744, // 艳红
        0xFFFF4081, // 玫红
        0xFFEE40B9, // 品红
        0xFFD500F9, // 亮紫
        0xFF651FFF, // 靛紫
        0xFF304FFE, // 宝蓝
        0xFF2979FF, // 亮蓝
        0xFF00B0FF, // 天蓝
        0xFF00E5FF, // 青蓝
        0xFF1DE9B6, // 翠绿
        0xFF00E676, // 荧光绿
        0xFF76FF03, // 酸橙
        0xFFC6FF00, // 亮黄绿
        0xFFFFEA00, // 金黄
        0xFFFFAB00, // 琥珀
        0xFFFF6D00, // 深橙
        0xFFF50057, // 洋红
        0xFFC51162, // 深品红
        0xFFAA00FF, // 紫
        0xFF6200EA, // 深紫
        0xFF3D5AFE, // 靛蓝
        0xFF2962FF, // 深蓝
        0xFF0091EA, // 靛青
        0xFF00BFA5, // 碧绿
        0xFF00C853, // 翠绿
        0xFF64DD17, // 亮绿
        0xFFFFD600, // 亮黄
        // ---- 以下为新增 30 色 ----
        0xFFFF5252, // 珊瑚红
        0xFFFF6E40, // 落日橙
        0xFFFF9100, // 暖橙
        0xFFFFCA28, // 向日葵黄
        0xFFFFD740, // 蜂蜜黄
        0xFFFDD835, // 柠檬黄
        0xFFD4E157, // 青柠绿
        0xFF66BB6A, // 薄荷绿
        0xFF26A69A, // 碧玉绿
        0xFF4DD0E1, // 水晶蓝
        0xFF42A5F5, // 天空蓝
        0xFF5C6BC0, // 静谧蓝
        0xFF7E57C2, // 薰衣草紫
        0xFFAB47BC, // 丁香紫
        0xFFEC407A, // 樱花粉
        0xFFEF5350, // 桃红
        0xFFFF7043, // 火焰橙
        0xFF8D6E63, // 可可棕
        0xFF78909C, // 雾蓝灰
        0xFF26C6DA, // 清澈青
        0xFF00E676, // 翡翠绿
        0xFF7C4DFF, // 幻彩紫
        0xFFB388FF, // 淡紫罗兰
        0xFFFF80AB, // 浅粉红
        0xFFFFAB91, // 蜜桃橙
        0xFFFFE082, // 暖金黄
        0xFFA5D6A7, // 薄荷浅绿
        0xFF80DEEA, // 冰蓝
        0xFF90CAF9, // 婴儿蓝
        0xFFCE93D8, // 淡紫丁香
    };

    private static final long COLOR_FADE_DURATION_MS = 800L;

    public static final LyricsColorManager INSTANCE = new LyricsColorManager();

    // 状态
    private boolean randomMode = true;
    private int fixedColor = 0xFFFFFFFF;
    private long colorChangeIntervalMs = 5000L;

    // 过渡状态
    private int currentColor = COLOR_POOL[0];
    private int targetColor = COLOR_POOL[0];
    private int transitionFrom = COLOR_POOL[0];
    private long lastChangeTime = 0;
    private boolean initialized = false;
    private int poolIndex = 0;

    private LyricsColorManager() {}

    /** 设置是否启用随机色模式。 */
    public synchronized void setRandomMode(boolean enabled) {
        if (this.randomMode == enabled) return;
        this.randomMode = enabled;
        if (!enabled) {
            // 切到固定色：平滑过渡
            transitionFrom = currentColor;
            targetColor = fixedColor;
            lastChangeTime = System.currentTimeMillis();
        } else {
            // 切到随机色：从当前色开始
            transitionFrom = currentColor;
            lastChangeTime = System.currentTimeMillis();
        }
    }

    public boolean isRandomMode() { return randomMode; }

    /** 设置固定色（色盘选择）。 */
    public synchronized void setFixedColor(int color) {
        this.fixedColor = color;
        if (!randomMode) {
            transitionFrom = currentColor;
            targetColor = color;
            lastChangeTime = System.currentTimeMillis();
        }
    }

    public int getFixedColor() { return fixedColor; }

    /** 设置随机色切换间隔（毫秒）。 */
    public synchronized void setColorChangeIntervalMs(long ms) {
        this.colorChangeIntervalMs = Math.max(1000L, Math.min(30000L, ms));
        // 重置计时，让新间隔立即生效
        lastChangeTime = System.currentTimeMillis();
    }

    public long getColorChangeIntervalMs() { return colorChangeIntervalMs; }

    /**
     * 获取当前平滑过渡后的颜色。
     * 随机模式下按时间间隔自动推进；固定模式下直接返回固定色。
     * @param paint 如果非 null，自动设置 paint 颜色。
     * @return 当前颜色
     */
    public synchronized int getColor(Paint paint) {
        if (!initialized) {
            initialized = true;
            lastChangeTime = System.currentTimeMillis();
            if (randomMode) {
                targetColor = nextPoolColor();
                transitionFrom = currentColor;
            } else {
                currentColor = fixedColor;
                targetColor = fixedColor;
                transitionFrom = fixedColor;
            }
        }

        long now = System.currentTimeMillis();

        if (randomMode) {
            // 检查是否需要切换到新颜色
            if (now - lastChangeTime >= colorChangeIntervalMs) {
                transitionFrom = currentColor;
                targetColor = nextPoolColor();
                lastChangeTime = now;
            }
            // 平滑过渡
            long fadeElapsed = now - lastChangeTime;
            float fadeT = Math.min(1f, (float) fadeElapsed / (float) Math.min(COLOR_FADE_DURATION_MS, colorChangeIntervalMs / 2));
            currentColor = interpolate(transitionFrom, targetColor, smoothStep(fadeT));
        } else {
            // 固定色模式：平滑过渡到固定色
            long elapsed = now - lastChangeTime;
            float t = Math.min(1f, (float) elapsed / (float) COLOR_FADE_DURATION_MS);
            currentColor = interpolate(transitionFrom, targetColor, smoothStep(t));
        }

        if (paint != null) {
            paint.setColor(currentColor);
        }
        return currentColor;
    }

    /** 获取当前颜色（不设置 paint）。 */
    public int getColor() { return getColor(null); }

    /** 获取目标颜色（边框等需要纯色的场景）。 */
    public synchronized int getTargetColor() {
        return targetColor;
    }

    /** 重置（切歌等场景）。 */
    public synchronized void reset() {
        initialized = false;
        lastChangeTime = System.currentTimeMillis();
    }

    // ---- 内部工具 ----

    private int nextPoolColor() {
        poolIndex = (poolIndex + 1) % COLOR_POOL.length;
        return COLOR_POOL[poolIndex];
    }

    /** 平滑阶梯函数，让过渡更自然（缓入缓出） */
    private static float smoothStep(float t) {
        return t * t * (3f - 2f * t);
    }

    static int interpolate(int from, int to, float t) {
        float[] hsvFrom = new float[3], hsvTo = new float[3];
        Color.colorToHSV(from, hsvFrom);
        Color.colorToHSV(to, hsvTo);

        // 色相取最短弧
        float dh = hsvTo[0] - hsvFrom[0];
        if (dh > 180f) dh -= 360f;
        if (dh < -180f) dh += 360f;
        float h = (hsvFrom[0] + dh * t + 360f) % 360f;
        float s = hsvFrom[1] + (hsvTo[1] - hsvFrom[1]) * t;
        float v = hsvFrom[2] + (hsvTo[2] - hsvFrom[2]) * t;

        int aFrom = Color.alpha(from), aTo = Color.alpha(to);
        int alpha = Math.round(aFrom + (aTo - aFrom) * t);
        return Color.HSVToColor(alpha, new float[]{h, s, v});
    }
}
