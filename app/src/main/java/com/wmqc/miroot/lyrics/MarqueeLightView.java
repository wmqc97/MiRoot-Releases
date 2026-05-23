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

import android.content.Context;
import android.os.SystemClock;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.BlurMaskFilter;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.animation.ValueAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import com.wmqc.miroot.BuildConfig;

import java.util.Random;

/**
 * 跑马灯视图
 * 在屏幕边缘显示一圈循环移动的灯光效果（适配背屏大R角）
 */
public class MarqueeLightView extends View {
    private static final String TAG = "MarqueeLightView";
    private final Random random = new Random();
    
    // 配置参数
    private static final int LIGHT_COUNT = 2;               // 光线数量（2条）
    private static final float DEFAULT_LIGHT_SIZE = 12f;     // 默认光线宽度（像素）
    private float lightSize = DEFAULT_LIGHT_SIZE;            // 当前光线宽度（可配置）
    private static final int LIGHT_COLOR_HEAD = 0xFFFFFFFF; // 头部颜色（完全不透明白色）
    private static final int LIGHT_COLOR_TAIL = 0x00FFFFFF; // 尾部颜色（完全透明）
    private static final long ANIMATION_DURATION = 5000;    // 动画时长（毫秒）
    private static final float EDGE_MARGIN = 0f;            // 边缘边距（像素）（V3.17: 紧贴屏幕边缘，完全占据外围）
    private static final float DEFAULT_CORNER_RADIUS = 100f;  // 默认圆角半径（像素）
    private float systemCornerRadius = DEFAULT_CORNER_RADIUS; // 系统圆角半径（从系统获取）
    
    /**
     * 固定圆角半径（px），非 null 时不检测系统圆角，直接使用此值（广播/锁屏等场景见 {@link ProjectionHelper#REAR_PROJECTION_FIXED_CORNER_RADIUS_PX}）
     */
    private Float fixedCornerRadiusPx = null;
    
    /**
     * 是否为主屏横屏模式（用于重新检测圆角半径）
     */
    private boolean isMainScreenLandscapeMode = false;
    private static final float SNAKE_LENGTH = 0.3f;          // 贪吃蛇长度（占路径总长度的比例，30%）
    private static final long COLOR_CHANGE_INTERVAL_BASE = 5000;  // 基础颜色变化间隔（毫秒）
    private static final long COLOR_CHANGE_INTERVAL_MIN_MS = 1000L;
    private static final long COLOR_CHANGE_INTERVAL_MAX_MS = 10000L;
    private static final long COLOR_SYNC_SAMPLE_INTERVAL_MS = 140L;
    private long currentColorChangeInterval = COLOR_CHANGE_INTERVAL_BASE;  // 当前颜色变化间隔（关联音谱）
    
    // 随机颜色相关
    private int[] lightColors = new int[LIGHT_COUNT];  // 每条光线的颜色
    private long lastColorChangeTime = 0;  // 上次颜色变化时间
    private long lastColorSyncSampleTime = 0L;
    private int lastSyncedColor = 0;
    private static final long DEBUG_COLOR_LOG_INTERVAL_MS = 5000L; // debug 下颜色日志最小间隔
    private long lastColorLogTime = 0L;
    private long perfWindowStartMs = 0L;
    private int perfFrameCount = 0;
    private long perfTotalDrawNs = 0L;
    private long perfMaxDrawNs = 0L;
    private static final long PERF_WINDOW_MS = 10_000L;
    private int consecutiveHeavyDrawCount = 0;
    private int consecutiveCoolDrawCount = 0;
    private static final long HEAVY_DRAW_NS = 8_000_000L;
    private static final int HEAVY_DRAW_TRIGGER_COUNT = 6;
    private static final int COOL_DRAW_RECOVER_COUNT = 160;
    
    // 动画
    private ValueAnimator animator;
    private float animationProgress = 0f;
    private long baseAnimationDuration = ANIMATION_DURATION;  // 基础动画时长
    private long currentAnimationDuration = ANIMATION_DURATION;  // 当前动画时长（根据节奏调整）

    /**
     * 某些机型/场景（息屏/锁屏、窗口短暂失焦、系统动画冻结、低内存）下，ValueAnimator 可能停止回调但不一定触发我们期望的生命周期。
     * 这里增加一个轻量 watchdog：若检测到“长时间没有 onAnimationUpdate”，则主动重建动画，避免跑马灯卡住不转。
     */
    private static final long WATCHDOG_TICK_MS = 1500L;
    private static final long WATCHDOG_STALE_MS = 2200L;
    private long lastAnimatorTickUptimeMs = 0L;
    private boolean watchdogRunning = false;
    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            watchdogRunning = false;
            if (!shouldAnimateNow()) {
                // 不需要动画时不循环，等待外部生命周期触发。
                return;
            }
            boolean running = (animator != null && animator.isRunning());
            long now = SystemClock.uptimeMillis();
            boolean stale = (lastAnimatorTickUptimeMs > 0L) && (now - lastAnimatorTickUptimeMs) >= WATCHDOG_STALE_MS;
            if (!running || stale) {
                if (BuildConfig.DEBUG) {
                    LogHelper.w(TAG, "⚠️ watchdog 检测到跑马灯可能卡住：running=" + running
                            + ", stale=" + stale + ", lastTick=" + lastAnimatorTickUptimeMs + ", now=" + now
                            + "，重启动画");
                }
                restartAnimationSafely();
            }
            scheduleWatchdog();
        }
    };
    
    
    // 绘图
    private Paint[] lightPaints;
    private Paint[] lightGlowPaints;  // 外散光效画笔（模糊外层）
    
    // 屏幕尺寸和路径
    private int screenWidth = 0;
    private int screenHeight = 0;
    private Path borderPath;
    private android.graphics.PathMeasure borderPathMeasure;  // 复用，避免 onDraw 内重复分配
    private float pathLength = 0f;
    /** 跑马灯轨迹单层外散光晕：复用 BlurMaskFilter */
    private BlurMaskFilter marqueeGlowBlur;
    /** 复用：getPositionAtPath 与 onDraw 内使用的数组，避免每帧分配 */
    private float[] pathPositionResult = new float[2];
    private float[] pathPositionTan = new float[2];
    /** 复用：跑马灯路径段，避免 onDraw 内每帧 new Path */
    private Path snakePathReuse;
    private Path segmentPath1;
    private Path segmentPath2;
    /** 复用：getPositionAtPath 的 head/tail 结果，避免 onDraw 内分配 */
    private float[] headPosResult = new float[2];
    private float[] tailPosResult = new float[2];
    
    /** 边框显示：是否绘制屏幕边缘边框路径（可与 {@link #neonEffectsEnabled} 独立）。 */
    private boolean borderFrameEnabled = false;
    /** 霓虹显示：为真时边框与跑马灯使用光晕/呼吸等霓虹效果；为假时边框为纯描边、跑马灯无光晕层。 */
    private boolean neonEffectsEnabled = true;
    private Paint neonBorderPaint;  // 霓虹灯边框画笔
    private Paint neonBorderGlowPaint1;  // 霓虹边框外散光效1（最外层）
    private Paint neonBorderGlowPaint2;  // 霓虹边框外散光效2（中间层）
    private Paint neonBorderGlowPaint3;  // 霓虹边框外散光效3（内层）
    private static final float NEON_BORDER_WIDTH = 3.5f;  // 霓虹灯边框宽度（增加一点）
    private static final int NEON_BORDER_COLOR = 0xFFFFB5C5;  // 霓虹灯边框默认颜色（粉色，当没有颜色回调时使用）
    private ValueAnimator neonBorderAnimator;  // 霓虹灯边框动画（呼吸效果）
    private float neonBorderAlpha = 0.5f;  // 当前边框透明度（0.0-1.0）
    
    // 颜色联动（跟随歌词颜色）
    public interface ColorSyncCallback {
        int getSyncColor();
    }
    private ColorSyncCallback colorSyncCallback = null;  // 颜色同步回调
    private boolean colorSyncEnabled = false;  // 是否启用颜色联动
    private boolean performanceGuardEnabled = false;
    private boolean lightweightModeEnabled = false;
    
    // 跑马灯控制
    private boolean marqueeLightEnabled = true;  // 是否启用跑马灯（可以独立控制）
    
    public MarqueeLightView(Context context) {
        this(context, null);
    }
    
    /**
     * 广播启动时使用：构造时即设固定圆角，避免 onSizeChanged 竞态导致跑马灯/霓虹灯圆角有时对有时错
     */
    public MarqueeLightView(Context context, boolean useFixedRearProjectionCornerRadius) {
        super(context);
        if (useFixedRearProjectionCornerRadius) {
            fixedCornerRadiusPx = ProjectionHelper.REAR_PROJECTION_FIXED_CORNER_RADIUS_PX;
            systemCornerRadius = ProjectionHelper.REAR_PROJECTION_FIXED_CORNER_RADIUS_PX;
            LogHelper.d(TAG, "✅ 广播启动：跑马灯/霓虹灯构造时即使用固定圆角 "
                    + ProjectionHelper.REAR_PROJECTION_FIXED_CORNER_RADIUS_PX + "px");
        }
        init();
    }
    
    public MarqueeLightView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }
    
    public MarqueeLightView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    
    private void init() {
        // 初始化画笔（V3.16: 简化为2条光线）
        lightPaints = new Paint[LIGHT_COUNT];
        
        // 创建两条光线的画笔（使用线条样式，渐变色在绘制时动态设置）
        // V3.17: 确保线条宽度在所有位置（包括圆角）都一致
        lightPaints[0] = new Paint(Paint.ANTI_ALIAS_FLAG);
        lightPaints[0].setStyle(Paint.Style.STROKE);
        lightPaints[0].setStrokeWidth(lightSize);
        lightPaints[0].setStrokeCap(Paint.Cap.ROUND);  // 使用ROUND保持端点圆润
        lightPaints[0].setStrokeJoin(Paint.Join.ROUND);  // 使用ROUND连接，在圆角处保持平滑
        
        lightPaints[1] = new Paint(Paint.ANTI_ALIAS_FLAG);
        lightPaints[1].setStyle(Paint.Style.STROKE);
        lightPaints[1].setStrokeWidth(lightSize);
        lightPaints[1].setStrokeCap(Paint.Cap.ROUND);  // 使用ROUND保持端点圆润
        lightPaints[1].setStrokeJoin(Paint.Join.ROUND);  // 使用ROUND连接，在圆角处保持平滑
        
        // 初始化外散光效画笔（模糊外层，用于霓虹光效）
        // 使用多层光效实现更好的外散效果
        lightGlowPaints = new Paint[LIGHT_COUNT];
        for (int i = 0; i < LIGHT_COUNT; i++) {
            lightGlowPaints[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
            lightGlowPaints[i].setStyle(Paint.Style.STROKE);
            lightGlowPaints[i].setStrokeWidth(lightSize * 4f);  // 外散光效宽度是光线的4倍
            lightGlowPaints[i].setStrokeCap(Paint.Cap.ROUND);
            lightGlowPaints[i].setStrokeJoin(Paint.Join.ROUND);
            // 使用 BlurMaskFilter 实现外散光效（OUTER模式实现向外扩散）
            lightGlowPaints[i].setMaskFilter(new BlurMaskFilter(15f, BlurMaskFilter.Blur.NORMAL));
        }

        // 单层光晕：半径取原 20/12/8 折中，一层模糊替代三层叠加
        marqueeGlowBlur = new BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL);
        
        // 初始化霓虹灯边框画笔（主边框）
        neonBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        neonBorderPaint.setStyle(Paint.Style.STROKE);
        neonBorderPaint.setStrokeWidth(NEON_BORDER_WIDTH);
        neonBorderPaint.setColor(NEON_BORDER_COLOR);
        neonBorderPaint.setStrokeCap(Paint.Cap.ROUND);
        neonBorderPaint.setStrokeJoin(Paint.Join.ROUND);
        
        // 初始化霓虹边框外散光效画笔（多层叠加实现真实霓虹效果）
        // 最外层光效（最淡、最宽、最模糊）
        neonBorderGlowPaint1 = new Paint(Paint.ANTI_ALIAS_FLAG);
        neonBorderGlowPaint1.setStyle(Paint.Style.STROKE);
        neonBorderGlowPaint1.setStrokeWidth(NEON_BORDER_WIDTH * 8f);
        neonBorderGlowPaint1.setColor(NEON_BORDER_COLOR);
        neonBorderGlowPaint1.setStrokeCap(Paint.Cap.ROUND);
        neonBorderGlowPaint1.setStrokeJoin(Paint.Join.ROUND);
        neonBorderGlowPaint1.setMaskFilter(new BlurMaskFilter(25f, BlurMaskFilter.Blur.NORMAL));
        
        // 中间层光效
        neonBorderGlowPaint2 = new Paint(Paint.ANTI_ALIAS_FLAG);
        neonBorderGlowPaint2.setStyle(Paint.Style.STROKE);
        neonBorderGlowPaint2.setStrokeWidth(NEON_BORDER_WIDTH * 5f);
        neonBorderGlowPaint2.setColor(NEON_BORDER_COLOR);
        neonBorderGlowPaint2.setStrokeCap(Paint.Cap.ROUND);
        neonBorderGlowPaint2.setStrokeJoin(Paint.Join.ROUND);
        neonBorderGlowPaint2.setMaskFilter(new BlurMaskFilter(15f, BlurMaskFilter.Blur.NORMAL));
        
        // 内层光效（较亮、较窄、较清晰）
        neonBorderGlowPaint3 = new Paint(Paint.ANTI_ALIAS_FLAG);
        neonBorderGlowPaint3.setStyle(Paint.Style.STROKE);
        neonBorderGlowPaint3.setStrokeWidth(NEON_BORDER_WIDTH * 3f);
        neonBorderGlowPaint3.setColor(NEON_BORDER_COLOR);
        neonBorderGlowPaint3.setStrokeCap(Paint.Cap.ROUND);
        neonBorderGlowPaint3.setStrokeJoin(Paint.Join.ROUND);
        neonBorderGlowPaint3.setMaskFilter(new BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL));
        
        // 设置背景透明
        setBackgroundColor(0x00000000);
        
        // V3.17: 设置不拦截触摸事件，让触摸事件穿透
        setClickable(false);
        setFocusable(false);
    }
    
    /**
     * 生成随机颜色（旧版风格：HSV 高饱和 + 高亮度连续随机）。
     */
    private void generateRandomColors() {
        for (int i = 0; i < LIGHT_COUNT; i++) {
            float hue = random.nextFloat() * 360f;
            float saturation = 0.85f + random.nextFloat() * 0.15f;
            float value = 0.85f + random.nextFloat() * 0.15f;
            lightColors[i] = Color.HSVToColor(new float[]{hue, saturation, value});
        }
        lastColorChangeTime = System.currentTimeMillis();
        if (BuildConfig.DEBUG) {
            LogHelper.d(
                    TAG,
                    "🎨 生成随机颜色: " +
                            String.format("#%06X", lightColors[0] & 0x00FFFFFF) + ", " +
                            String.format("#%06X", lightColors[1] & 0x00FFFFFF)
            );
        }
    }
    
    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        // V3.17: 不拦截触摸事件，让触摸事件穿透到底层
        return false;
    }
    
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        
        screenWidth = w;
        screenHeight = h;
        
        // 检测系统圆角半径
        detectSystemCornerRadius();
        
        // 计算屏幕边缘路径（带圆角）
        calculateBorderPath(w, h);
        
        // 启动动画
        startAnimation();
        
        LogHelper.d(TAG, "✅ onSizeChanged: " + w + "x" + h + ", pathLength=" + pathLength);
    }
    
    /**
     * 检测系统圆角半径（优化版：获取所有四个角，提高准确度）
     * 参考AbyssalMirrorLyricsViewGroup的实现
     */
    private void detectSystemCornerRadius() {
        try {
            // 广播启动时使用固定圆角（与深渊镜一致），不检测系统
            if (fixedCornerRadiusPx != null) {
                systemCornerRadius = fixedCornerRadiusPx;
                LogHelper.d(TAG, "✅ 广播启动：跑马灯/霓虹灯使用固定圆角半径: " + systemCornerRadius + "px");
                return;
            }
            // 尝试使用Android S (API 31+) 的WindowInsets获取圆角信息
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                // 方法1：通过 getRootWindowInsets() 获取
                android.view.WindowInsets rootWindowInsets = getRootWindowInsets();
                if (rootWindowInsets != null) {
                    float detectedRadius = getCornerRadiusFromInsets(rootWindowInsets, "getRootWindowInsets");
                    if (detectedRadius > 0) {
                        systemCornerRadius = detectedRadius;
                        LogHelper.d(TAG, "✅ 从getRootWindowInsets检测到系统圆角半径: " + systemCornerRadius + "px");
                        return;
                    }
                }
                
                // 方法2：通过 WindowManager.getCurrentWindowMetrics() 获取（需要Activity context）
                try {
                    android.content.Context context = getContext();
                    if (context instanceof android.app.Activity) {
                        android.app.Activity activity = (android.app.Activity) context;
                        android.view.WindowManager wm = activity.getWindowManager();
                        if (wm != null) {
                            android.view.WindowMetrics metrics = wm.getCurrentWindowMetrics();
                            if (metrics != null) {
                                android.view.WindowInsets insets = metrics.getWindowInsets();
                                if (insets != null) {
                                    float detectedRadius = getCornerRadiusFromInsets(insets, "WindowManager.getCurrentWindowMetrics");
                                    if (detectedRadius > 0) {
                                        systemCornerRadius = detectedRadius;
                                        LogHelper.d(TAG, "✅ 从WindowManager检测到系统圆角半径: " + systemCornerRadius + "px");
                                        return;
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    LogHelper.w(TAG, "⚠️ 从WindowManager获取圆角失败: " + e.getMessage());
                }
            }
            
            // 如果无法获取，尝试根据屏幕尺寸估算（作为fallback）
            float estimatedRadius = estimateCornerRadiusFromScreenSize();
            if (estimatedRadius > 0) {
                systemCornerRadius = estimatedRadius;
                LogHelper.d(TAG, "⚠️ 无法检测系统圆角，根据屏幕尺寸估算: " + systemCornerRadius + "px");
            } else {
                LogHelper.d(TAG, "⚠️ 无法检测系统圆角，使用默认值: " + systemCornerRadius + "px");
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "⚠️ 检测系统圆角失败，使用默认值: " + systemCornerRadius + "px, 错误: " + e.getMessage());
        }
    }
    
    /**
     * 从 WindowInsets 获取圆角半径（优化版：获取所有四个角）
     * @param insets WindowInsets 对象
     * @param source 来源描述（用于日志）
     * @return 圆角半径（px），失败返回 0
     */
    private float getCornerRadiusFromInsets(android.view.WindowInsets insets, String source) {
        if (insets == null) return 0;
        
        try {
            // 尝试使用直接API（如果targetSdk >= 31且编译时可用）
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                try {
                    // 尝试直接调用 getRoundedCorner(int position) 方法（如果可用）
                    java.lang.reflect.Method getRoundedCornerMethod = null;
                    try {
                        getRoundedCornerMethod = insets.getClass().getMethod("getRoundedCorner", int.class);
                    } catch (NoSuchMethodException e) {
                        // 方法不存在，继续使用反射的 getRoundedCorners()
                    }
                    
                    // 如果直接API可用，尝试获取四个角
                    if (getRoundedCornerMethod != null) {
                        // 定义四个角的位置常量（RoundedCorner.POSITION_*）
                        int[] positions = {1, 2, 4, 8}; // TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
                        java.util.List<Integer> radii = new java.util.ArrayList<>();
                        
                        for (int position : positions) {
                            try {
                                Object corner = getRoundedCornerMethod.invoke(insets, position);
                                if (corner != null) {
                                    java.lang.reflect.Method getRadiusMethod = corner.getClass().getMethod("getRadius");
                                    Object radiusObj = getRadiusMethod.invoke(corner);
                                    if (radiusObj instanceof Integer) {
                                        int radius = (Integer) radiusObj;
                                        if (radius > 0) {
                                            radii.add(radius);
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                // 某个角获取失败，继续尝试其他角
                            }
                        }
                        
                        if (!radii.isEmpty()) {
                            // 使用最大值（更保守，确保圆角足够大）
                            int maxRadius = 0;
                            int sum = 0;
                            for (int r : radii) {
                                if (r > maxRadius) maxRadius = r;
                                sum += r;
                            }
                            // 如果四个角都获取成功且值相近，使用平均值；否则使用最大值
                            float avgRadius = sum / (float) radii.size();
                            float finalRadius = (radii.size() == 4 && Math.abs(maxRadius - avgRadius) < 5) ? avgRadius : maxRadius;
                            LogHelper.d(TAG, "✅ 从" + source + "获取到" + radii.size() + "个角的圆角: " + radii + ", 使用: " + finalRadius + "px");
                            return finalRadius;
                        }
                    }
                } catch (Exception e) {
                    // 直接API不可用，继续使用反射方法
                }
            }
            
            // 使用反射调用 getRoundedCorners() 方法（兼容低targetSdk）
            java.lang.reflect.Method method = insets.getClass().getMethod("getRoundedCorners");
            Object result = method.invoke(insets);
            if (result != null && result instanceof java.util.List) {
                @SuppressWarnings("unchecked")
                java.util.List<Object> roundedCorners = (java.util.List<Object>) result;
                if (!roundedCorners.isEmpty()) {
                    // 获取所有圆角的半径值
                    java.util.List<Integer> radii = new java.util.ArrayList<>();
                    java.lang.reflect.Method getRadiusMethod = null;
                    
                    for (Object corner : roundedCorners) {
                        if (corner != null) {
                            try {
                                if (getRadiusMethod == null) {
                                    getRadiusMethod = corner.getClass().getMethod("getRadius");
                                }
                                Object radiusObj = getRadiusMethod.invoke(corner);
                                if (radiusObj instanceof Integer) {
                                    int radius = (Integer) radiusObj;
                                    if (radius > 0) {
                                        radii.add(radius);
                                    }
                                }
                            } catch (Exception e) {
                                // 某个角获取失败，继续
                            }
                        }
                    }
                    
                    if (!radii.isEmpty()) {
                        // 使用最大值（更保守，确保圆角足够大）
                        int maxRadius = 0;
                        int sum = 0;
                        for (int r : radii) {
                            if (r > maxRadius) maxRadius = r;
                            sum += r;
                        }
                        // 如果所有角都获取成功且值相近，使用平均值；否则使用最大值
                        float avgRadius = sum / (float) radii.size();
                        float finalRadius = (radii.size() == roundedCorners.size() && Math.abs(maxRadius - avgRadius) < 5) ? avgRadius : maxRadius;
                        LogHelper.d(TAG, "✅ 从" + source + "获取到" + radii.size() + "个角的圆角: " + radii + ", 使用: " + finalRadius + "px");
                        return finalRadius;
                    }
                }
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "⚠️ 从" + source + "反射获取圆角失败: " + e.getMessage());
        }
        
        return 0;
    }
    
    /**
     * 根据屏幕尺寸估算圆角半径（作为fallback）
     * 大多数现代Android设备的圆角半径约为屏幕短边的 2-4%
     */
    private float estimateCornerRadiusFromScreenSize() {
        try {
            android.content.res.Resources res = getContext().getResources();
            android.util.DisplayMetrics metrics = res.getDisplayMetrics();
            int screenWidth = metrics.widthPixels;
            int screenHeight = metrics.heightPixels;
            int minDimension = Math.min(screenWidth, screenHeight);
            
            // 根据屏幕尺寸估算：小屏设备（< 1080px）约 2-3%，大屏设备约 3-4%
            float estimatedRadius = minDimension < 1080 ? (minDimension * 0.025f) : (minDimension * 0.035f);
            
            // 限制在合理范围内（20px - 150px）
            estimatedRadius = Math.max(20f, Math.min(150f, estimatedRadius));
            
            LogHelper.d(TAG, "📐 根据屏幕尺寸估算圆角: 屏幕=" + screenWidth + "x" + screenHeight + ", 估算半径=" + estimatedRadius + "px");
            return estimatedRadius;
        } catch (Exception e) {
            LogHelper.w(TAG, "⚠️ 估算圆角失败: " + e.getMessage());
            return 0;
        }
    }
    
    /**
     * 计算屏幕边缘路径（带圆角适配，完整显示）
     * V3.17: 适配系统圆角，确保线条不超出屏幕范围，右边贴边显示
     */
    private void calculateBorderPath(int width, int height) {
        borderPath = new Path();
        
        // V3.17: 考虑线条宽度，让路径向内偏移线条宽度的一半，确保线条不超出屏幕
        // 线条宽度是lightSize，所以路径需要向内偏移lightSize/2
        // 微调：减小偏移量，让圆角更贴边
        float strokeOffset = lightSize / 2f - 1f;  // 减小1px，让圆角更贴边
        
        // V3.17: 所有边都向内偏移，确保线条不超出屏幕范围
        // 上边需要额外偏移，避免超出可显示范围
        float left = EDGE_MARGIN + strokeOffset;
        float top = EDGE_MARGIN + strokeOffset + 2f;  // 上边额外偏移2px，避免超出
        float right = width - EDGE_MARGIN - strokeOffset - 1f;  // 右边额外偏移1px，向内移动
        float bottom = height - EDGE_MARGIN - strokeOffset;
        
        // V3.17: 使用RectF和圆角矩形路径，适配系统圆角
        // 使用检测到的系统圆角半径，确保圆角半径不超过可用空间
        float maxRadius = Math.min(right - left, bottom - top) / 2f;
        float actualRadius = Math.min(systemCornerRadius, maxRadius);
        // 微调：如果实际半径接近最大半径，使用最大半径让圆角更贴边
        if (actualRadius > maxRadius * 0.9f) {
            actualRadius = maxRadius;
        }
        
        // 确保圆角半径不会导致路径无效
        if (actualRadius < 0) {
            actualRadius = 0;
        }
        
        RectF rect = new RectF(left, top, right, bottom);
        
        // 创建圆角矩形路径（顺时针方向）
        borderPath.addRoundRect(rect, actualRadius, actualRadius, Path.Direction.CW);
        
        // 使用 PathMeasure 计算准确的路径长度并缓存，供 getPositionAtPath/onDraw 复用
        borderPathMeasure = new android.graphics.PathMeasure(borderPath, false);
        pathLength = borderPathMeasure.getLength();
        if (snakePathReuse == null) snakePathReuse = new Path();
        if (segmentPath1 == null) segmentPath1 = new Path();
        if (segmentPath2 == null) segmentPath2 = new Path();
        
        LogHelper.d(TAG, "✅ 计算路径: " + width + "x" + height + ", pathLength=" + pathLength + 
                  ", actualRadius=" + actualRadius + ", strokeOffset=" + strokeOffset +
                  ", rect=[" + left + "," + top + "," + right + "," + bottom + "]");
    }
    
    /**
     * 根据路径位置获取坐标（使用缓存的 PathMeasure，避免重复分配）
     * 结果写入成员 pathPositionResult，调用方使用该数组避免分配。
     */
    private void getPositionAtPath(float pathPosition, float[] outResult) {
        float normalizedPos = (pathPosition % pathLength) / pathLength;
        if (borderPathMeasure == null) return;
        float len = borderPathMeasure.getLength();
        borderPathMeasure.getPosTan(len * normalizedPos, pathPositionResult, pathPositionTan);
        outResult[0] = pathPositionResult[0];
        outResult[1] = pathPositionResult[1];
    }
    
    /**
     * 启动动画
     * V3.17: 支持根据音乐节奏动态调整速度
     */
    private void startAnimation() {
        if (animator != null) {
            animator.cancel();
        }
        
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(currentAnimationDuration);
        animator.setInterpolator(new LinearInterpolator());
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.RESTART);
        
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                animationProgress = (Float) animation.getAnimatedValue();
                lastAnimatorTickUptimeMs = SystemClock.uptimeMillis();
                postInvalidateOnAnimation();
            }
        });
        
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                super.onAnimationStart(animation);
                LogHelper.d(TAG, "✅ 动画已启动，时长=" + currentAnimationDuration + "ms");
            }
            
            @Override
            public void onAnimationCancel(Animator animation) {
                super.onAnimationCancel(animation);
                LogHelper.d(TAG, "⏹ 动画已取消");
            }
        });
        
        animator.start();
        lastAnimatorTickUptimeMs = SystemClock.uptimeMillis();
        scheduleWatchdog();
        LogHelper.d(TAG, "🚀 启动跑马灯动画");
    }
    
    /**
     * 更新动画速度（根据音乐节奏）
     * V3.17: 动态调整动画时长以匹配音乐节奏
     */
    private void updateAnimationSpeed() {
        if (animator == null || !animator.isRunning()) {
            return;
        }
        
        // 获取当前动画进度
        float currentProgress = (Float) animator.getAnimatedValue();
        
        // 取消当前动画
        animator.cancel();
        
        // 创建新动画，从当前进度继续
        animator = ValueAnimator.ofFloat(currentProgress, currentProgress + 1f);
        animator.setDuration(currentAnimationDuration);
        animator.setInterpolator(new LinearInterpolator());
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.RESTART);
        
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float progress = (Float) animation.getAnimatedValue();
                animationProgress = progress % 1f;  // 归一化到0-1
                postInvalidateOnAnimation();
            }
        });
        
        animator.start();
    }

    private int resolveBorderBaseColor() {
        if (colorSyncEnabled && colorSyncCallback != null) {
            try {
                int c = colorSyncCallback.getSyncColor();
                return sanitizeSyncedNeutralColor(c) & 0x00FFFFFF;
            } catch (Exception e) {
                return NEON_BORDER_COLOR & 0x00FFFFFF;
            }
        }
        return NEON_BORDER_COLOR & 0x00FFFFFF;
    }

    /**
     * 当歌词侧关闭“随机颜色切换”时，回调通常会给出纯白/纯黑。
     * 这里把纯白略微压暗、纯黑略微抬亮，边框/跑马灯更柔和且仍保持同色系。
     */
    private int sanitizeSyncedNeutralColor(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        if (r >= 250 && g >= 250 && b >= 250) {
            return 0xFFE6E6E6;
        }
        if (r <= 5 && g <= 5 && b <= 5) {
            return 0xFF111111;
        }
        return argb;
    }

    private void noteDrawMetrics(long drawCostNs) {
        long now = SystemClock.uptimeMillis();
        if (perfWindowStartMs <= 0L) {
            perfWindowStartMs = now;
        }
        perfFrameCount++;
        perfTotalDrawNs += drawCostNs;
        if (drawCostNs > perfMaxDrawNs) {
            perfMaxDrawNs = drawCostNs;
        }
        if (drawCostNs >= HEAVY_DRAW_NS) {
            consecutiveHeavyDrawCount++;
            consecutiveCoolDrawCount = 0;
        } else {
            consecutiveCoolDrawCount++;
            consecutiveHeavyDrawCount = 0;
        }
        if (performanceGuardEnabled) {
            if (!lightweightModeEnabled && consecutiveHeavyDrawCount >= HEAVY_DRAW_TRIGGER_COUNT) {
                lightweightModeEnabled = true;
                LogHelper.w(TAG, "⚠️ 跑马灯进入轻量模式：连续重绘耗时偏高");
            } else if (lightweightModeEnabled && consecutiveCoolDrawCount >= COOL_DRAW_RECOVER_COUNT) {
                lightweightModeEnabled = false;
                LogHelper.d(TAG, "✅ 跑马灯退出轻量模式：重绘恢复稳定");
            }
        }
        if (BuildConfig.DEBUG && now - perfWindowStartMs >= PERF_WINDOW_MS) {
            float fps = (perfFrameCount * 1000f) / Math.max(1f, (now - perfWindowStartMs));
            float avgMs = perfTotalDrawNs / (float) Math.max(1, perfFrameCount) / 1_000_000f;
            float maxMs = perfMaxDrawNs / 1_000_000f;
            LogHelper.dThrottled(
                    TAG,
                    "📊 边框渲染窗口: fps=" + String.format(java.util.Locale.US, "%.1f", fps)
                            + ", avgMs=" + String.format(java.util.Locale.US, "%.2f", avgMs)
                            + ", maxMs=" + String.format(java.util.Locale.US, "%.2f", maxMs)
                            + ", lightweight=" + lightweightModeEnabled
                            + ", guard=" + performanceGuardEnabled,
                    PERF_WINDOW_MS
            );
            perfWindowStartMs = now;
            perfFrameCount = 0;
            perfTotalDrawNs = 0L;
            perfMaxDrawNs = 0L;
        }
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final long drawStartNs = System.nanoTime();
        
        if (pathLength == 0 || borderPath == null || screenWidth == 0 || screenHeight == 0) {
            return;
        }
        
        // 边框路径（边框显示开时始终绘制；霓虹效果由 neonEffectsEnabled 决定）
        if (borderFrameEnabled && borderPath != null) {
            int baseColor = resolveBorderBaseColor();
            if (neonEffectsEnabled) {
                int alphaValue = (int) (neonBorderAlpha * 255);
                if (!lightweightModeEnabled) {
                    int outerColor1 = (int)(alphaValue * 0.15f) << 24 | baseColor;
                    neonBorderGlowPaint1.setColor(outerColor1);
                    canvas.drawPath(borderPath, neonBorderGlowPaint1);
                    int outerColor2 = (int)(alphaValue * 0.30f) << 24 | baseColor;
                    neonBorderGlowPaint2.setColor(outerColor2);
                    canvas.drawPath(borderPath, neonBorderGlowPaint2);
                }
                int outerColor3 = (int)(alphaValue * 0.50f) << 24 | baseColor;
                neonBorderGlowPaint3.setColor(outerColor3);
                canvas.drawPath(borderPath, neonBorderGlowPaint3);
                int borderColor = alphaValue << 24 | baseColor;
                neonBorderPaint.setColor(borderColor);
                canvas.drawPath(borderPath, neonBorderPaint);
            } else {
                int borderColor = 0xDD000000 | baseColor;
                neonBorderPaint.setColor(borderColor);
                canvas.drawPath(borderPath, neonBorderPaint);
            }
        }
        
        // 更新跑马灯颜色：如果启用了颜色联动，从歌词获取颜色；否则使用随机颜色
        long currentTime = System.currentTimeMillis();
        if (colorSyncEnabled && colorSyncCallback != null) {
            try {
                // 颜色联动采样节流：避免每帧读取颜色回调导致额外负载。
                if (lastColorSyncSampleTime == 0L || currentTime - lastColorSyncSampleTime >= COLOR_SYNC_SAMPLE_INTERVAL_MS) {
                    lastColorSyncSampleTime = currentTime;
                    int lyricsColor = sanitizeSyncedNeutralColor(colorSyncCallback.getSyncColor());
                    if (lyricsColor != lastSyncedColor) {
                        for (int i = 0; i < LIGHT_COUNT; i++) {
                            lightColors[i] = lyricsColor;
                        }
                        lastSyncedColor = lyricsColor;
                        lastColorChangeTime = currentTime;
                        if (BuildConfig.DEBUG && (currentTime - lastColorLogTime) >= DEBUG_COLOR_LOG_INTERVAL_MS) {
                            lastColorLogTime = currentTime;
                            LogHelper.d(TAG, "🎨 跑马灯颜色已更新为歌词颜色: " + String.format("#%06X", lyricsColor & 0x00FFFFFF));
                        }
                    }
                }
            } catch (Exception e) {
                // 如果回调抛出异常（如lyricsView为null），使用随机颜色
                if (currentTime - lastColorChangeTime >= currentColorChangeInterval) {
                    generateRandomColors();
                }
            }
        } else if (currentTime - lastColorChangeTime >= currentColorChangeInterval) {
            // 使用随机颜色生成逻辑
            generateRandomColors();
        }
        
        // 只有在跑马灯启用时才绘制跑马灯光线
        if (!marqueeLightEnabled) {
            return;  // 如果跑马灯未启用，直接返回（但霓虹边框仍会绘制）
        }
        
        // V3.17: 贪吃蛇样式 - 每条光线由头部（高亮）和尾部（渐变消失）组成
        float snakeLength = pathLength * SNAKE_LENGTH; // 贪吃蛇的实际长度
        if (borderPathMeasure == null) return;
        float totalPathLength = borderPathMeasure.getLength();
        
        for (int lineIndex = 0; lineIndex < LIGHT_COUNT; lineIndex++) {
            Paint paint = lightPaints[lineIndex];
            
            // 计算这条光线的起始位置（两条光线间隔一半路径）
            float lineOffset = (lineIndex * pathLength) / LIGHT_COUNT;
            float animatedOffset = animationProgress * pathLength;
            float snakeHeadPosition = (lineOffset + animatedOffset) % pathLength;
            
            // 计算贪吃蛇的起始和结束位置
            float snakeTailPosition = (snakeHeadPosition - snakeLength + pathLength) % pathLength;
            
            // 计算渐变起点和终点（复用成员数组，避免分配）
            getPositionAtPath(snakeHeadPosition, headPosResult);
            getPositionAtPath(snakeTailPosition, tailPosResult);
            
            // 直接从 borderPath 提取路径段（复用 Path，避免每帧 new）
            snakePathReuse.reset();
            if (snakeTailPosition > snakeHeadPosition) {
                segmentPath1.reset();
                segmentPath2.reset();
                borderPathMeasure.getSegment(snakeTailPosition, totalPathLength, segmentPath1, true);
                snakePathReuse.addPath(segmentPath1);
                borderPathMeasure.getSegment(0, snakeHeadPosition, segmentPath2, true);
                snakePathReuse.addPath(segmentPath2);
            } else {
                borderPathMeasure.getSegment(snakeTailPosition, snakeHeadPosition, snakePathReuse, true);
            }
            
            // V3.17: 创建随机颜色渐变（从随机颜色到透明）
            int headColor = lightColors[lineIndex];  // 使用随机颜色作为头部颜色
            
            if (neonEffectsEnabled) {
                Paint glowPaint = lightGlowPaints[lineIndex];
                int glowHead = (headColor & 0x00FFFFFF) | 0x42000000;   // ~26% 头部
                int glowMid = (headColor & 0x00FFFFFF) | 0x1A000000;    // ~10% 中段
                LinearGradient glowGradient = new LinearGradient(
                    headPosResult[0], headPosResult[1],
                    tailPosResult[0], tailPosResult[1],
                    new int[]{glowHead, glowMid, 0x00000000},
                    new float[]{0f, 0.38f, 1f},
                    Shader.TileMode.CLAMP
                );
                glowPaint.setShader(glowGradient);
                glowPaint.setStrokeWidth(lightweightModeEnabled ? (lightSize * 2.4f) : (lightSize * 4f));
                glowPaint.setMaskFilter(marqueeGlowBlur);
                if (!lightweightModeEnabled || lineIndex == 0) {
                    canvas.drawPath(snakePathReuse, glowPaint);
                }
                glowPaint.setShader(null);
            }
            
            // V3.17: 确保线条粗细一致（在所有位置，包括圆角）
            LinearGradient gradient = new LinearGradient(
                headPosResult[0], headPosResult[1],
                tailPosResult[0], tailPosResult[1],
                new int[]{headColor, (headColor & 0x00FFFFFF) | 0x80000000, LIGHT_COLOR_TAIL},  // 从高亮到半透明再到完全透明
                new float[]{0f, 0.6f, 1f},  // 在60%位置开始变淡，与外散光效平滑过渡
                Shader.TileMode.CLAMP
            );
            // 确保使用当前的lightSize值设置线条宽度
            float currentLightSize = lightSize;  // 使用局部变量确保使用最新值
            paint.setStrokeWidth(currentLightSize);
            paint.setShader(gradient);
            
            // 绘制贪吃蛇路径（使用 Path 确保圆角处宽度一致，绘制在光效之上）
            canvas.drawPath(snakePathReuse, paint);
            
            // 清除shader，避免影响下一条光线
            paint.setShader(null);
        }
        noteDrawMetrics(System.nanoTime() - drawStartNs);
    }
    
    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != View.VISIBLE) {
            if (animator != null) animator.cancel();
            stopNeonBorderAnimation();
            stopWatchdog();
        } else if (screenWidth > 0 && screenHeight > 0) {
            startAnimation();
            if (borderFrameEnabled && neonEffectsEnabled) startNeonBorderAnimation();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) {
            // 恢复焦点时确保动画活着（部分机型会在失焦后“冻结”动画但 view 仍可见）
            if (shouldAnimateNow()) {
                restartAnimationSafely();
            }
        }
    }
    
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        LogHelper.d(TAG, "✅ onAttachedToWindow");
        if (getVisibility() == View.VISIBLE && screenWidth > 0 && screenHeight > 0) {
            if (animator != null && !animator.isRunning()) {
                animator.start();
            } else if (animator == null) {
                startAnimation();
            }
            if (borderFrameEnabled && neonEffectsEnabled) startNeonBorderAnimation();
            scheduleWatchdog();
        }
    }
    
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        LogHelper.d(TAG, "⏹ onDetachedFromWindow");
        if (animator != null) {
            animator.cancel();
        }
        stopNeonBorderAnimation();
        stopWatchdog();
    }

    private boolean shouldAnimateNow() {
        if (getVisibility() != View.VISIBLE) return false;
        if (getWindowVisibility() != View.VISIBLE) return false;
        if (screenWidth <= 0 || screenHeight <= 0) return false;
        if (borderPath == null || pathLength <= 0f) return false;
        // 只有跑马灯/边框有任意一个开，才有必要跑动画（否则只会耗电）
        return marqueeLightEnabled || (borderFrameEnabled && neonEffectsEnabled);
    }

    private void restartAnimationSafely() {
        // 避免在非主线程/布局阶段触发；post 到 UI 线程下一帧。
        post(() -> {
            if (!shouldAnimateNow()) return;
            // 统一走 startAnimation：内部会 cancel+recreate，避免 animator 处于异常状态（例如被冻结但 isRunning=true）。
            startAnimation();
            if (borderFrameEnabled && neonEffectsEnabled) startNeonBorderAnimation();
        });
    }

    private void scheduleWatchdog() {
        if (watchdogRunning) return;
        if (!shouldAnimateNow()) return;
        watchdogRunning = true;
        removeCallbacks(watchdogRunnable);
        postDelayed(watchdogRunnable, WATCHDOG_TICK_MS);
    }

    private void stopWatchdog() {
        watchdogRunning = false;
        removeCallbacks(watchdogRunnable);
    }
    
    /**
     * 设置灯光颜色
     */
    public void setLightColors(int color1, int color2) {
        if (lightPaints != null && lightPaints.length >= 2) {
            lightPaints[0].setColor(color1);
            lightPaints[1].setColor(color2);
            invalidate();
        }
    }
    
    /**
     * 设置动画速度（毫秒）
     */
    public void setAnimationDuration(long duration) {
        long safeDuration = Math.max(1200L, Math.min(12000L, duration));
        baseAnimationDuration = safeDuration;
        currentAnimationDuration = safeDuration;
        if (animator != null) {
            animator.setDuration(safeDuration);
        }
    }

    /**
     * 设置跑马灯颜色刷新节奏（毫秒），与歌词调试页保持同频。
     */
    public void setColorChangeIntervalMs(long intervalMs) {
        long safeInterval = Math.max(COLOR_CHANGE_INTERVAL_MIN_MS, Math.min(COLOR_CHANGE_INTERVAL_MAX_MS, intervalMs));
        if (currentColorChangeInterval == safeInterval) {
            return;
        }
        currentColorChangeInterval = safeInterval;
        // 参数更新后尽快切到新节奏，避免体感滞后。
        lastColorChangeTime = 0L;
        postInvalidateOnAnimation();
    }
    
    /**
     * 边框显示：是否绘制边缘边框路径（无霓虹时仅为描边）。
     */
    public void setBorderFrameEnabled(boolean enabled) {
        if (borderFrameEnabled != enabled) {
            borderFrameEnabled = enabled;
            if (enabled && neonEffectsEnabled) {
                startNeonBorderAnimation();
            } else {
                stopNeonBorderAnimation();
            }
            invalidate();
            LogHelper.d(TAG, "🖼 边框显示: " + (enabled ? "启用" : "禁用"));
        }
    }

    /**
     * 霓虹显示：控制边框与跑马灯的光晕/呼吸等霓虹效果（关时仍可显示边框与跑马灯线条）。
     */
    public void setNeonEffectsEnabled(boolean enabled) {
        if (neonEffectsEnabled != enabled) {
            neonEffectsEnabled = enabled;
            if (borderFrameEnabled && neonEffectsEnabled) {
                startNeonBorderAnimation();
            } else {
                stopNeonBorderAnimation();
            }
            invalidate();
            LogHelper.d(TAG, "✨ 霓虹效果: " + (enabled ? "启用" : "禁用"));
        }
    }

    /** @deprecated 使用 {@link #setBorderFrameEnabled(boolean)} 与 {@link #setNeonEffectsEnabled(boolean)} */
    @Deprecated
    public void setNeonBorderEnabled(boolean enabled) {
        setBorderFrameEnabled(enabled);
        setNeonEffectsEnabled(enabled);
    }
    
    /**
     * 启动霓虹灯边框呼吸动画
     */
    private void startNeonBorderAnimation() {
        if (!borderFrameEnabled || !neonEffectsEnabled) {
            return;
        }
        if (neonBorderAnimator != null) {
            neonBorderAnimator.cancel();
        }
        
        neonBorderAnimator = ValueAnimator.ofFloat(0.3f, 1.0f);
        neonBorderAnimator.setDuration(lightweightModeEnabled ? 2800 : 2000);
        neonBorderAnimator.setInterpolator(new LinearInterpolator());
        neonBorderAnimator.setRepeatCount(ValueAnimator.INFINITE);
        neonBorderAnimator.setRepeatMode(ValueAnimator.REVERSE);  // 往返动画
        
        neonBorderAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                neonBorderAlpha = (Float) animation.getAnimatedValue();
                postInvalidateOnAnimation();
            }
        });
        
        neonBorderAnimator.start();
    }
    
    /**
     * 停止霓虹灯边框呼吸动画
     */
    private void stopNeonBorderAnimation() {
        if (neonBorderAnimator != null) {
            neonBorderAnimator.cancel();
            neonBorderAnimator = null;
        }
        neonBorderAlpha = 0.5f;  // 重置为默认值
    }
    
    public boolean isBorderFrameEnabled() {
        return borderFrameEnabled;
    }

    public boolean isNeonEffectsEnabled() {
        return neonEffectsEnabled;
    }

    /** @deprecated 见 {@link #isBorderFrameEnabled()} */
    @Deprecated
    public boolean isNeonBorderEnabled() {
        return borderFrameEnabled;
    }
    
    /**
     * 设置跑马灯是否启用（可以独立控制，不影响霓虹边框）
     */
    public void setMarqueeLightEnabled(boolean enabled) {
        if (marqueeLightEnabled != enabled) {
            marqueeLightEnabled = enabled;
            invalidate();
            LogHelper.d(TAG, "💡 跑马灯: " + (enabled ? "启用" : "禁用"));
        }
    }
    
    /**
     * 获取跑马灯是否启用
     */
    public boolean isMarqueeLightEnabled() {
        return marqueeLightEnabled;
    }
    
    /**
     * 设置跑马灯线条宽度（像素）
     */
    public void setLightSize(float size) {
        if (size < 4f) size = 4f;  // 最小4px
        if (size > 30f) size = 30f;  // 最大30px
        
        if (Math.abs(lightSize - size) > 0.1f) {  // 使用浮点数比较，允许0.1px的误差
            lightSize = size;
            
            // 更新画笔的线条宽度
            if (lightPaints != null) {
                for (Paint paint : lightPaints) {
                    if (paint != null) {
                    paint.setStrokeWidth(lightSize);
                    }
                }
            }
            
            // 更新外散光效的线条宽度（虽然会在onDraw中动态设置，但这里也更新以确保一致性）
            if (lightGlowPaints != null) {
                for (Paint glowPaint : lightGlowPaints) {
                    if (glowPaint != null) {
                    // 外散光效宽度会根据lightSize动态计算，在onDraw中设置
                        // 这里不设置，因为onDraw中会根据lightSize动态计算
                    }
                }
            }
            
            // 重新计算路径（因为线条宽度变化会影响路径偏移）
            if (screenWidth > 0 && screenHeight > 0) {
                calculateBorderPath(screenWidth, screenHeight);
            }
            
            // 强制重绘
            postInvalidate();
            LogHelper.d(TAG, "📏 跑马灯线条宽度已设置为: " + lightSize + "px");
        }
    }
    
    /**
     * 获取跑马灯线条宽度（像素）
     */
    public float getLightSize() {
        return lightSize;
    }
    
    /**
     * 获取当前跑马灯颜色（用于与歌词颜色联动）
     * 返回第一条光线的颜色，如果有多条光线则返回第一条
     */
    public int getCurrentLightColor() {
        if (lightColors != null && lightColors.length > 0) {
            return lightColors[0];  // 返回第一条光线的颜色
        }
        return 0xFFFFFFFF;  // 默认白色
    }
    
    /**
     * 设置颜色同步回调（用于霓虹灯边框跟随歌词颜色）
     */
    public void setColorSyncCallback(ColorSyncCallback callback) {
        this.colorSyncCallback = callback;
    }
    
    /**
     * 设置是否启用颜色联动（霓虹灯边框跟随歌词颜色）
     */
    public void setColorSyncEnabled(boolean enabled) {
        this.colorSyncEnabled = enabled;
    }

    public void setPerformanceGuardEnabled(boolean enabled) {
        performanceGuardEnabled = enabled;
        if (!enabled) {
            consecutiveHeavyDrawCount = 0;
            consecutiveCoolDrawCount = 0;
        }
    }

    public void setLightweightModeEnabled(boolean enabled) {
        if (lightweightModeEnabled == enabled) {
            return;
        }
        lightweightModeEnabled = enabled;
        if (borderFrameEnabled && neonEffectsEnabled) {
            startNeonBorderAnimation();
        }
        invalidate();
        LogHelper.d(TAG, "🪶 跑马灯轻量模式: " + (enabled ? "启用" : "关闭"));
    }

    public boolean isLightweightModeEnabled() {
        return lightweightModeEnabled;
    }
    
    /**
     * 设置固定圆角半径（px）；默认广播场景可与 {@link ProjectionHelper#REAR_PROJECTION_FIXED_CORNER_RADIUS_PX} 对齐
     * @param px 圆角半径（像素）；传 0 或负数可恢复为检测系统圆角
     */
    public void setFixedCornerRadiusPx(float px) {
        fixedCornerRadiusPx = (px > 0) ? px : null;
        systemCornerRadius = (fixedCornerRadiusPx != null) ? fixedCornerRadiusPx : DEFAULT_CORNER_RADIUS;
        if (screenWidth > 0 && screenHeight > 0) {
            calculateBorderPath(screenWidth, screenHeight);
            invalidate();
        }
    }
    
    /**
     * 设置是否为主屏横屏模式（用于重新检测圆角半径）
     * @param isMainScreenLandscape 是否为主屏横屏模式
     */
    public void setMainScreenLandscapeMode(boolean isMainScreenLandscape) {
        if (this.isMainScreenLandscapeMode != isMainScreenLandscape) {
            this.isMainScreenLandscapeMode = isMainScreenLandscape;
            
            // 主屏和背屏的圆角可能不同，需要重新检测
            detectSystemCornerRadius();
            LogHelper.d(TAG, "🖥️ " + (isMainScreenLandscape ? "主屏横屏" : "背屏") + "模式：重新检测圆角半径: " + systemCornerRadius + "px");
            
            // 需要重新计算路径以应用新的圆角
            if (screenWidth > 0 && screenHeight > 0) {
                calculateBorderPath(screenWidth, screenHeight);
                invalidate();
            }
        }
    }
    
    /**
     * 获取所有光线颜色的平均值（用于与歌词颜色联动）
     */
    public int getAverageLightColor() {
        if (lightColors == null || lightColors.length == 0) {
            return 0xFFFFFFFF;  // 默认白色
        }
        
        // 计算所有颜色的平均值
        long totalR = 0, totalG = 0, totalB = 0;
        for (int color : lightColors) {
            totalR += (color >> 16) & 0xFF;
            totalG += (color >> 8) & 0xFF;
            totalB += color & 0xFF;
        }
        
        int avgR = (int)(totalR / lightColors.length);
        int avgG = (int)(totalG / lightColors.length);
        int avgB = (int)(totalB / lightColors.length);
        
        return 0xFF000000 | (avgR << 16) | (avgG << 8) | avgB;
    }
}
