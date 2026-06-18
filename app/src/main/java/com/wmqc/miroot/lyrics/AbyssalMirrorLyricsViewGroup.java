/*
 * 深渊镜效果歌词显示控件
 *
 * 层级约定：贴着屏幕外边框那层是第 1 层，x=0 的是第一层（layerIndex=0，margin=0 全屏贴边）。
 * 从第 1 层开始创建，第 2～4 层依次向内缩 (3dp 描边 + 3dp 间隔)。
 *
 * 规格：
 * 1. 第 1 层 = 贴屏幕外沿、x=0；第 2～4 层向内、从外到内渐透明
 * 2. 中央一行歌词三层叠影；第 2～4 层边框 + 歌词跟随陀螺仪 3D
 */

package com.wmqc.miroot.lyrics;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Camera;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.LightingColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.Layout;
import android.text.StaticLayout;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import com.wmqc.miroot.R;
import com.wmqc.miroot.BuildConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 深渊镜效果歌词显示控件
 * 使用ViewGroup实现多层边框的深渊镜效果，适配小米17 Pro Max窄长背屏
 */
public class AbyssalMirrorLyricsViewGroup extends FrameLayout {
    private final Random random = new Random();
    
    // ========== 配置常量 ==========
    
    /** 圆角矩形层数：4 层 */
    private static final int BORDER_LAYER_COUNT = 4;
    /** 第1层描边厚度（dp） */
    private static final float BORDER_STROKE_DP = 3f;
    /** 第4层描边厚度（dp），第1～4层线性过渡 */
    private static final float BORDER_STROKE_INNER_DP = 2f;
    /** 层与层之间间隔（dp），较密 */
    private static final float GAP_DP = 3f;
    /** 主屏幕模式下层与层之间间隔（dp），加大间距 */
    private static final float GAP_DP_MAIN_SCREEN = 4.5f;
    /** 每层向内缩进 = 描边 + 间隔，用于计算 margin */
    private static final float INSET_PER_LAYER_DP = BORDER_STROKE_DP + GAP_DP;

    /** 与 [RearScreenLyricsActivity] / [com.wmqc.miroot.ui.music.LyricsSettingsRepository] 键名一致 */
    private static final String PREFS_LYRICS_SETTINGS = "LyricsSettings";
    /** 与设置页「背屏歌词字体」一致：深渊镜与普通投屏共用 [projectionLyricsFont]。 */
    private static final String KEY_PROJECTION_LYRICS_FONT = "projectionLyricsFont";
    private static final String KEY_PROJECTION_LYRICS_CUSTOM_PATH = "projectionLyricsCustomPath";
    
    /**
     * 获取当前模式下的层间距（dp）
     */
    private float getGapDp() {
        return isMainScreenLandscapeMode ? GAP_DP_MAIN_SCREEN : GAP_DP;
    }
    
    /**
     * 获取当前模式下每层向内缩进（dp）
     */
    private float getInsetPerLayerDp() {
        return BORDER_STROKE_DP + getGapDp();
    }

    /** 边框颜色（白） */
    private static final int BORDER_COLOR = Color.WHITE;
    /** 第1层100%显示，到第4层最淡：线性过渡，中间无陡变，深渊感更自然 */
    private static final float[] BORDER_ALPHA_VALUES = {
        1.0f,   // 第1层：100% 不透明
        0.73f,
        0.47f,
        0.20f   // 第4层：最淡
    };
    
    /**
     * 默认歌词字体大小（px，从设置读取）
     */
    private static final float DEFAULT_TEXT_SIZE_PX = 78f;
    
    /**
     * 默认歌词颜色（白色）
     */
    private static final int DEFAULT_TEXT_COLOR = Color.WHITE;
    
    /**
     * 默认圆角半径（像素）
     */
    private static final float DEFAULT_CORNER_RADIUS = 100f;
    
    // ========== 成员变量 ==========
    
    /**
     * 深渊镜歌词 View（3 层文字 + 雾化 + 光照，参考深渊主题 abyssal_mirror_text）
     */
    private AbyssalLyricView abyssalLyricView;
    
    /**
     * 边框层数组（从外到内）
     */
    private FrameLayout[] borderLayers;
    
    /**
     * 系统圆角半径（从系统获取，或广播/锁屏等场景使用 {@link ProjectionHelper#REAR_PROJECTION_FIXED_CORNER_RADIUS_PX}）
     */
    private float systemCornerRadius = DEFAULT_CORNER_RADIUS;
    
    /**
     * 固定圆角半径（px），非 null 时不检测系统圆角（广播场景见 {@link ProjectionHelper#REAR_PROJECTION_FIXED_CORNER_RADIUS_PX}）
     */
    private Float fixedCornerRadiusPx = null;
    
    /**
     * 是否为主屏横屏模式（用于调整圆角半径）
     */
    private boolean isMainScreenLandscapeMode = false;
    
    /**
     * 传感器管理器（使用AbyssalMirrorSensorManager）
     */
    private AbyssalMirrorSensorManager sensorManager;
    
    /**
     * 旋转数据（欧拉角）
     */
    private float[] eulerDiff = new float[]{0, 0, 0};

    /**
     * 每帧重绘时的 euler 快照：doFrame 在 postInvalidate 前拷贝 eulerDiff，
     * 本帧内 4 层 BorderView + AbyssalLyricView 都只读此快照，避免绘制中途 euler 被 onRotationChanged 更新导致同一帧内各层/歌词不一致
     */
    private float[] eulerSnapshot = new float[]{0, 0, 0};
    
    /**
     * 3D投影相关
     */
    private Camera camera;
    private Matrix matrix;
    
    /**
     * 3D投影参数（参考深渊主题 abyssal_mirror_*.glsl）
     */
    private static final float EYE_Z = 3.0f;
    private static final float RENDER_PLANE_Z = 1.56f;
    /** 雾化系数，参考 text_frag：ScreenEdgeFogK */
    private static final float FOG_K = 1.5f;
    /** 陀螺仪静止阈值：|euler| 之和小于此值时视为未动，用 [0,0,0] 显示初始位置（层层递减同心） */
    private static final float GYRO_REST_THRESHOLD = 0.02f;
    /** 滞回：低于此值认为进入「静止」调度（30fps + Handler），避免在阈值附近与运动模式来回跳变导致卡顿 */
    private static final float GYRO_IDLE_ENTER_THRESHOLD = 0.018f;
    /** 滞回：高于此值认为进入「运动」调度（跟 Vsync、全速重绘） */
    private static final float GYRO_IDLE_EXIT_THRESHOLD = 0.048f;
    /** 每层陀螺仪 XY 移动叠加步长：第2层=1.0（轻微），第3层=1.0+step，…，最后一层与歌词移幅更大 */
    private static final float GYRO_SCALE_STEP = 0.22f;
    /** 歌词移幅在边框基础上的额外倍率（默认值，可被 setMovableRangeMultiplier 覆盖） */
    private static final float DEFAULT_MOVABLE_RANGE = 2.5f;
    /** 陀螺仪跟随倍数（可调节，默认 1.0） */
    private float gyroSensitivityMultiplier = 1.0f;
    /** 可移动范围倍率（可调节，默认 2.5） */
    private float movableRangeMultiplier = DEFAULT_MOVABLE_RANGE;
    /** 歌词超出时跑马灯：速度 px/ms，末端停留等效像素，首端停留等效像素 */
    private static final float LYRIC_SCROLL_SPEED_PX_PER_MS = 0.05f;
    private static final float LYRIC_SCROLL_PAUSE_AT_END_PX = 60f;
    private static final float LYRIC_SCROLL_PAUSE_AT_START_PX = 60f;
    /** 时空隧道：内层逐级缩小，每层相对外层缩放步长，使层层向内堆叠感更强 */
    private static final float TUNNEL_SCALE_PER_LAYER = 0.012f;
    /** 整体固定偏移（dp）：所有边框与歌词向同一方向平移，便于微调位置 */
    private static final float OFFSET_X_DP = 0f;
    private static final float OFFSET_Y_DP = 0f;
    /** 深渊镜主题色（var_config 默认 #8ED2CD），可选用于歌词/边框 */
    private static final int ABYSSAL_THEME_ACCENT = 0xFF8ED2CD;
    /** 渐变：上/浅色（白）→ 下/深色（主题），用于圆角框与歌词；呼吸+随机换色时由成员覆盖 */
    private static final int GRADIENT_START_COLOR = 0xFFFFFFFF;
    private static final int GRADIENT_END_COLOR = 0xFF8ED2CD;
    /** 换行触发颜色过渡时长（与 ModernLyricsView 一致） */
    private static final long COLOR_FADE_DURATION_MS = 800L;
    /** 呼吸周期（ms） */
    private static final long BREATH_PERIOD_MS = 2500;

    private static final float TEXT_BASE = 0.8f;
    private static final float TEXT_OFF = 0.06f;  // Z轴偏移（用于深度效果）
    /** 歌词 3 层深渊镜：Z 轴偏移（参考 abyssal_mirror_text instanceOffsets） */
    private static final float[] TEXT_LAYER_OFFSETS = {
        TEXT_BASE + TEXT_OFF * 2,  // 最远层（第一层）
        TEXT_BASE + TEXT_OFF * 1,  // 第二层
        TEXT_BASE + TEXT_OFF * 0   // 第三层（最近层）
    };
    /** 歌词各层的固定像素偏移（dp），用于产生重叠效果 */
    private static final float[] TEXT_LAYER_PIXEL_OFFSET_X_DP = {
        0f,     // 第一层：无偏移
        20f,    // 第二层：向右偏移20dp（约15-25像素，增大以增强摆动效果）
        40f     // 第三层：向右偏移40dp（约30-50像素，增大以增强摆动效果）
    };
    private static final float[] TEXT_LAYER_PIXEL_OFFSET_Y_DP = {
        0f,     // 第一层：无偏移
        15f,    // 第二层：向下偏移15dp（约10-20像素，增大以增强摆动效果）
        30f     // 第三层：向下偏移30dp（约20-40像素，增大以增强摆动效果）
    };
    /** 歌词 3 层透明度（远→近），参考 AbyssalMirrorLyricsView */
    private static final float[] TEXT_LAYER_ALPHA = {1.0f, 0.5f, 0.2f};
    private static final float TEXT_RENDER_PLANE_Z = 1.56f;
    private static final int TEXT_LAYER_COUNT = 3;
    
    /**
     * Choreographer 驱动刷新；静止时降频至约 30fps 以降低耗电与资源占用。
     */
    private Choreographer.FrameCallback frameCallback;
    private android.os.Handler frameHandler;
    private Runnable delayedFrameRunnable;
    private boolean needsRedraw = false;
    /** 用 Vsync 时间（纳秒）节流重绘，避免 System.currentTimeMillis() 在同一毫秒内丢帧导致转动卡顿 */
    private long lastRedrawFrameNanos = 0L;
    /** true = 低功耗静止调度；false = 跟屏刷新。滞回与 euler 阈值配合，减少 30fps/60fps 抖动 */
    private boolean gyroIdleLatched = true;
    /** 陀螺仪静止时约 30fps，降低 CPU/耗电（Handler 延迟） */
    private static final long MIN_REDRAW_INTERVAL_IDLE_MS = 33;
    /** 静止时 Choreographer 与重绘的最小间隔（纳秒），约 30fps */
    private static final long MIN_REDRAW_INTERVAL_IDLE_NS = 33_000_000L;
    private static final long MIN_REDRAW_INTERVAL_LIGHTWEIGHT_MS = 50;
    private static final long MIN_REDRAW_INTERVAL_LIGHTWEIGHT_NS = 50_000_000L;
    /** 静止时呼吸/换色更新节流间隔（ms） */
    private static final long BREATH_UPDATE_THROTTLE_MS = 50;
    private long lastBreathUpdateTime = 0;
    private float lastBreathFactor = 1f;
    private int lastGradientStart = GRADIENT_START_COLOR;
    private int lastGradientEnd = GRADIENT_END_COLOR;
    private long lastGyroLogTime = 0;
    private long lastSensorRedrawMarkTime = 0;
    private static final long SENSOR_REDRAW_MIN_INTERVAL_MS = 36L;
    private static final float SENSOR_REDRAW_DELTA_EPSILON = 0.006f;
    private boolean performanceGuardEnabled = false;
    private boolean lightweightModeEnabled = false;
    private long perfWindowStartMs = 0L;
    private int perfFrameCount = 0;
    private long perfTotalDrawNs = 0L;
    private long perfMaxDrawNs = 0L;
    private static final long PERF_WINDOW_MS = 10_000L;
    private int consecutiveHeavyFrameCount = 0;
    private int consecutiveCoolFrameCount = 0;
    private static final long HEAVY_FRAME_NS = 10_000_000L;
    private static final int HEAVY_FRAME_TRIGGER_COUNT = 4;
    private static final int COOL_FRAME_RECOVER_COUNT = 120;

    /** 呼吸渐变幻色（与投屏音乐一致）：渐变起/止色、过渡、呼吸系数 */
    private int currentGradientStart = GRADIENT_START_COLOR;
    private int currentGradientEnd = GRADIENT_END_COLOR;
    /** 方案1：上一首/下一首手势回调；手势开关，与 gestureControlEnabled 一致 */
    public interface OnPrevNextGestureListener {
        void onPrevious();
        void onNext();
    }
    private OnPrevNextGestureListener prevNextListener;
    private boolean enableGesture = true;
    private GestureDetector gestureDetector;
    private static final float FLING_VELOCITY_THRESHOLD = 400f;
    private int targetGradientStart = GRADIENT_END_COLOR;
    private int targetGradientEnd = GRADIENT_END_COLOR;
    private int transitionStartStart = GRADIENT_START_COLOR;
    private int transitionStartEnd = GRADIENT_END_COLOR;
    private long colorTransitionStartTime = 0;   // 本轮过渡开始时间戳
    private int lastSharedSyncColor = 0;         // 上次检测到的共享颜色（用于变化检测）
    private boolean randomColorSwitchEnabled = true;
    private float breathFactor = 1f;

    // ========== 构造函数 ==========
    
    /**
     * 是否在构造时使用固定背屏投屏圆角（广播启动），在 init() 最早处设置，避免与 onSizeChanged 竞态
     */
    private boolean useFixedRearProjectionCornerRadiusInInit = false;
    
    public AbyssalMirrorLyricsViewGroup(Context context) {
        super(context);
        init();
    }
    
    /**
     * 广播启动时使用：先设固定圆角再 init，保证首次检测和 onSizeChanged 数值一致，避免有时对有时错
     */
    public AbyssalMirrorLyricsViewGroup(Context context, boolean useFixedRearProjectionCornerRadius) {
        super(context);
        useFixedRearProjectionCornerRadiusInInit = useFixedRearProjectionCornerRadius;
        init();
    }
    
    public AbyssalMirrorLyricsViewGroup(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    public AbyssalMirrorLyricsViewGroup(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    
    // Android 16 (API 36) 兼容：添加defStyleRes参数
    public AbyssalMirrorLyricsViewGroup(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }
    
    // ========== 初始化方法 ==========
    
    /**
     * 初始化控件
     * 设置根布局属性，创建边框层和歌词TextView
     */
    private void init() {
        setPadding(0, 0, 0, 0);
        setBackgroundColor(Color.BLACK);
        setFitsSystemWindows(false); // 不消费窗口 insets，保证全屏贴边
        // 关闭裁剪：转动时 3D 偏移的路径可超出子 View 范围，避免被裁掉导致黑色
        setClipChildren(false);
        setClipToPadding(false);

        // 整控件一层 HARDWARE 即可；子层勿再开，否则离屏位图易不刷新（边框/换色卡住）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            setLayerType(LAYER_TYPE_HARDWARE, null);
        }
        
        // 广播启动：在首次 detectSystemCornerRadius 之前就设好固定圆角，避免与 onSizeChanged 竞态导致有时对有时错
        if (useFixedRearProjectionCornerRadiusInInit) {
            fixedCornerRadiusPx = ProjectionHelper.REAR_PROJECTION_FIXED_CORNER_RADIUS_PX;
            systemCornerRadius = ProjectionHelper.REAR_PROJECTION_FIXED_CORNER_RADIUS_PX;
            LogHelper.d("AbyssalMirrorLyrics", "✅ 广播启动：构造时即使用固定圆角 "
                    + ProjectionHelper.REAR_PROJECTION_FIXED_CORNER_RADIUS_PX + "px");
        }
        
        // 初始化传感器
        initSensor();
        
        // 检测系统圆角半径（若已设 fixedCornerRadiusPx 则直接使用，不再检测）
        detectSystemCornerRadius();
        
        // 创建边框层
        createBorderLayers();
        
        // 创建深渊镜歌词 View（3 层 + 雾化 + 光照）
        createAbyssalLyricView();

        // 方案1：手势上一首/下一首，onFling 中 |velocityX|>400 且 |velocityX|>|velocityY| 时：右滑(velocityX>0)→上一首，左滑(velocityX<0)→下一首
        gestureDetector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (!enableGesture || prevNextListener == null) return false;
                float absVx = Math.abs(velocityX);
                float absVy = Math.abs(velocityY);
                if (absVx <= FLING_VELOCITY_THRESHOLD || absVx <= absVy) return false;
                if (velocityX > 0) {
                    prevNextListener.onPrevious();
                } else {
                    prevNextListener.onNext();
                }
                return true;
            }
        });
        
        frameHandler = new Handler(Looper.getMainLooper());
        delayedFrameRunnable = () -> {
            if (frameCallback != null) {
                Choreographer.getInstance().postFrameCallback(frameCallback);
            }
        };
        // Choreographer 帧回调：运动时约 60fps，静止时约 30fps + 呼吸/换色节流，降低耗电与 CPU
        frameCallback = new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                final long frameStartNs = System.nanoTime();
                long currentTime = System.currentTimeMillis();
                float eulerMag = (eulerDiff != null && eulerDiff.length >= 3)
                    ? (Math.abs(eulerDiff[0]) + Math.abs(eulerDiff[1]) + Math.abs(eulerDiff[2])) : 0f;
                if (eulerMag < GYRO_IDLE_ENTER_THRESHOLD) {
                    gyroIdleLatched = true;
                } else if (eulerMag > GYRO_IDLE_EXIT_THRESHOLD) {
                    gyroIdleLatched = false;
                }
                boolean gyroAtRest = gyroIdleLatched;

                long minRedrawIntervalNs;
                if (lightweightModeEnabled) {
                    minRedrawIntervalNs = MIN_REDRAW_INTERVAL_LIGHTWEIGHT_NS;
                } else {
                    minRedrawIntervalNs = gyroAtRest ? MIN_REDRAW_INTERVAL_IDLE_NS : 0L;
                }

                // 静止：节流呼吸/换色，有可见变化才 needsRedraw。运动：每帧重绘跟手；呼吸/换色仍 50ms 节流减 CPU
                if (gyroAtRest) {
                    if (currentTime - lastBreathUpdateTime >= BREATH_UPDATE_THROTTLE_MS) {
                        lastBreathUpdateTime = currentTime;
                        boolean colorTransitioning = updateBreathingAndGradientColor();
                        float breathDelta = Math.abs(breathFactor - lastBreathFactor);
                        int startDelta = Math.abs(currentGradientStart - lastGradientStart) + Math.abs(currentGradientEnd - lastGradientEnd);
                        if (colorTransitioning || breathDelta > 0.008f || startDelta > 0) {
                            needsRedraw = true;
                            lastBreathFactor = breathFactor;
                            lastGradientStart = currentGradientStart;
                            lastGradientEnd = currentGradientEnd;
                        }
                    }
                } else {
                    frameHandler.removeCallbacks(delayedFrameRunnable);
                    if (currentTime - lastBreathUpdateTime >= BREATH_UPDATE_THROTTLE_MS) {
                        lastBreathUpdateTime = currentTime;
                        updateBreathingAndGradientColor();
                    }
                    needsRedraw = true;
                }

                if (needsRedraw && (frameTimeNanos - lastRedrawFrameNanos >= minRedrawIntervalNs)) {
                    if (eulerDiff != null && eulerDiff.length >= 3) {
                        eulerSnapshot[0] = eulerDiff[0];
                        eulerSnapshot[1] = eulerDiff[1];
                        eulerSnapshot[2] = eulerDiff[2];
                    } else {
                        eulerSnapshot[0] = 0f;
                        eulerSnapshot[1] = 0f;
                        eulerSnapshot[2] = 0f;
                    }
                    // 必须显式标记各 BorderView / 歌词 dirty：仅 postInvalidate 父级时，部分机型子 View 离屏层不刷新
                    invalidateAbyssalVisualSubtree();
                    postInvalidateOnAnimation();
                    needsRedraw = false;
                    lastRedrawFrameNanos = frameTimeNanos;
                }
                noteFrameMetrics(System.nanoTime() - frameStartNs);

                // 静止：Handler 约 30fps；运动：取消延迟任务并跟 Vsync，避免两种回调叠加重排
                if (gyroAtRest) {
                    frameHandler.removeCallbacks(delayedFrameRunnable);
                    frameHandler.postDelayed(
                        delayedFrameRunnable,
                        lightweightModeEnabled ? MIN_REDRAW_INTERVAL_LIGHTWEIGHT_MS : MIN_REDRAW_INTERVAL_IDLE_MS
                    );
                } else {
                    Choreographer.getInstance().postFrameCallback(this);
                }
            }
        };
    }
    
    /**
     * 初始化传感器
     */
    private void initSensor() {
        try {
            sensorManager = new AbyssalMirrorSensorManager(getContext());
            if (sensorManager != null) {
                sensorManager.setOnRotationChangedListener(new AbyssalMirrorSensorManager.OnRotationChangedListener() {
                    @Override
                    public void onRotationChanged(float[] euler) {
                        if (euler != null && euler.length >= 3) {
                            float delta = Math.abs(euler[0] - eulerDiff[0])
                                + Math.abs(euler[1] - eulerDiff[1])
                                + Math.abs(euler[2] - eulerDiff[2]);
                            eulerDiff[0] = euler[0];
                            eulerDiff[1] = euler[1];
                            eulerDiff[2] = euler[2];
                            float mag = Math.abs(euler[0]) + Math.abs(euler[1]) + Math.abs(euler[2]);
                            long now = System.currentTimeMillis();
                            boolean intervalReady = (now - lastSensorRedrawMarkTime) >= SENSOR_REDRAW_MIN_INTERVAL_MS;
                            if (delta >= SENSOR_REDRAW_DELTA_EPSILON || mag > 0.12f || intervalReady) {
                                needsRedraw = true;
                                lastSensorRedrawMarkTime = now;
                            }
                            if (BuildConfig.DEBUG && mag > 0.02f && System.currentTimeMillis() - lastGyroLogTime > 600) {
                                LogHelper.d("AbyssalMirrorLyrics", "euler received needsRedraw mag=" + mag);
                                lastGyroLogTime = System.currentTimeMillis();
                            }
                        }
                    }
                });
                LogHelper.d("AbyssalMirrorLyrics", "✅ 传感器管理器已初始化");
            }
        } catch (Exception e) {
            LogHelper.e("AbyssalMirrorLyrics", "❌ 传感器初始化失败", e);
            sensorManager = null;
        }
        
        // 初始化Camera和Matrix用于3D投影
        camera = new Camera();
        matrix = new Matrix();
    }
    
    /**
     * 检测系统圆角半径（优化版：获取所有四个角，提高准确度）
     * 参考MarqueeLightView的实现，但改进为获取所有角的圆角值
     */
    /** 深渊镜每一帧更新 euler/渐变前，显式失效边框与歌词，避免嵌套 View 显示列表不重建 */
    private void invalidateAbyssalVisualSubtree() {
        if (borderLayers != null) {
            for (FrameLayout fl : borderLayers) {
                if (fl == null) continue;
                for (int j = 0; j < fl.getChildCount(); j++) {
                    View c = fl.getChildAt(j);
                    if (c != null) {
                        c.invalidate();
                    }
                }
                fl.invalidate();
            }
        }
        if (abyssalLyricView != null) {
            abyssalLyricView.invalidate();
        }
    }

    private void noteFrameMetrics(long frameCostNs) {
        long now = System.currentTimeMillis();
        if (perfWindowStartMs <= 0L) {
            perfWindowStartMs = now;
        }
        perfFrameCount++;
        perfTotalDrawNs += frameCostNs;
        if (frameCostNs > perfMaxDrawNs) {
            perfMaxDrawNs = frameCostNs;
        }
        if (frameCostNs >= HEAVY_FRAME_NS) {
            consecutiveHeavyFrameCount++;
            consecutiveCoolFrameCount = 0;
        } else {
            consecutiveCoolFrameCount++;
            consecutiveHeavyFrameCount = 0;
        }
        if (performanceGuardEnabled) {
            if (!lightweightModeEnabled && consecutiveHeavyFrameCount >= HEAVY_FRAME_TRIGGER_COUNT) {
                lightweightModeEnabled = true;
                LogHelper.w("AbyssalMirrorLyrics", "⚠️ 深渊镜进入轻量模式：连续帧开销偏高");
            } else if (lightweightModeEnabled && consecutiveCoolFrameCount >= COOL_FRAME_RECOVER_COUNT) {
                lightweightModeEnabled = false;
                LogHelper.d("AbyssalMirrorLyrics", "✅ 深渊镜退出轻量模式：帧开销恢复");
            }
        }
        if (BuildConfig.DEBUG && now - perfWindowStartMs >= PERF_WINDOW_MS) {
            float fps = (perfFrameCount * 1000f) / Math.max(1f, (now - perfWindowStartMs));
            float avgMs = perfTotalDrawNs / (float) Math.max(1, perfFrameCount) / 1_000_000f;
            float maxMs = perfMaxDrawNs / 1_000_000f;
            LogHelper.dThrottled(
                "AbyssalMirrorLyrics",
                "📊 深渊镜渲染窗口: fps=" + String.format(java.util.Locale.US, "%.1f", fps)
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

    private void detectSystemCornerRadius() {
        try {
            // 广播启动时使用固定圆角，不检测系统
            if (fixedCornerRadiusPx != null) {
                systemCornerRadius = fixedCornerRadiusPx;
                LogHelper.d("AbyssalMirrorLyrics", "✅ 广播启动：使用固定圆角半径: " + systemCornerRadius + "px");
                return;
            }
            // 尝试使用Android S (API 31+) 的WindowInsets获取圆角信息
            if (Build.VERSION.SDK_INT >= 31) {
                // 方法1：通过 getRootWindowInsets() 获取
                android.view.WindowInsets rootWindowInsets = getRootWindowInsets();
                if (rootWindowInsets != null) {
                    float detectedRadius = getCornerRadiusFromInsets(rootWindowInsets, "getRootWindowInsets");
                    if (detectedRadius > 0) {
                        systemCornerRadius = detectedRadius;
                        LogHelper.d("AbyssalMirrorLyrics", "✅ 从getRootWindowInsets检测到系统圆角半径: " + systemCornerRadius + "px");
                        applyMainScreenCornerRadiusIfNeeded();
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
                                        LogHelper.d("AbyssalMirrorLyrics", "✅ 从WindowManager检测到系统圆角半径: " + systemCornerRadius + "px");
                                        applyMainScreenCornerRadiusIfNeeded();
                                        return;
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    LogHelper.w("AbyssalMirrorLyrics", "⚠️ 从WindowManager获取圆角失败: " + e.getMessage());
                }
            }
            
            // 如果无法获取，尝试根据屏幕尺寸估算（作为fallback）
            float estimatedRadius = estimateCornerRadiusFromScreenSize();
            if (estimatedRadius > 0) {
                systemCornerRadius = estimatedRadius;
                LogHelper.d("AbyssalMirrorLyrics", "⚠️ 无法检测系统圆角，根据屏幕尺寸估算: " + systemCornerRadius + "px");
            } else {
                LogHelper.d("AbyssalMirrorLyrics", "⚠️ 无法检测系统圆角，使用默认值: " + systemCornerRadius + "px");
            }
        } catch (Exception e) {
            LogHelper.w("AbyssalMirrorLyrics", "⚠️ 检测系统圆角失败，使用默认值: " + systemCornerRadius + "px, 错误: " + e.getMessage());
        }
        applyMainScreenCornerRadiusIfNeeded();
    }

    private void applyMainScreenCornerRadiusIfNeeded() {
        if (!isMainScreenLandscapeMode || fixedCornerRadiusPx != null) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        if (w > 0 && h > 0) {
            systemCornerRadius = ProjectionHelper.resolveMainScreenCornerRadiusPx(
                    systemCornerRadius, w, h);
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
            if (Build.VERSION.SDK_INT >= 31) {
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
                            LogHelper.d("AbyssalMirrorLyrics", "✅ 从" + source + "获取到" + radii.size() + "个角的圆角: " + radii + ", 使用: " + finalRadius + "px");
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
                        LogHelper.d("AbyssalMirrorLyrics", "✅ 从" + source + "获取到" + radii.size() + "个角的圆角: " + radii + ", 使用: " + finalRadius + "px");
                        return finalRadius;
                    }
                }
            }
        } catch (Exception e) {
            LogHelper.w("AbyssalMirrorLyrics", "⚠️ 从" + source + "反射获取圆角失败: " + e.getMessage());
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
            int w = getWidth() > 0 ? getWidth() : metrics.widthPixels;
            int h = getHeight() > 0 ? getHeight() : metrics.heightPixels;
            float estimatedRadius;
            if (isMainScreenLandscapeMode) {
                estimatedRadius = ProjectionHelper.estimateMainScreenCornerRadiusPx(w, h);
            } else {
                int minDimension = Math.min(w, h);
                estimatedRadius = minDimension < 1080 ? (minDimension * 0.025f) : (minDimension * 0.035f);
                estimatedRadius = Math.max(20f, Math.min(150f, estimatedRadius));
            }
            LogHelper.d("AbyssalMirrorLyrics", "📐 根据屏幕尺寸估算圆角: 屏幕=" + w + "x" + h + ", 估算半径=" + estimatedRadius + "px");
            return estimatedRadius;
        } catch (Exception e) {
            LogHelper.w("AbyssalMirrorLyrics", "⚠️ 估算圆角失败: " + e.getMessage());
            return 0;
        }
    }
    
    /**
     * 4 层边框全尺寸叠放（margin=0），层间不再嵌套遮挡；每层圆角矩形由 layerIndex 的 inset 决定大小，转动时所有框可见。
     * 公式：layerInsetDp = i * (BORDER_STROKE_DP + GAP_DP)，即第 i 层 rect 向里缩 i*6dp 每侧。
     */
    private void createBorderLayers() {
        // 先移除所有旧的边框层，避免重复创建
        if (borderLayers != null) {
            for (int i = 0; i < borderLayers.length; i++) {
                if (borderLayers[i] != null) {
                    removeView(borderLayers[i]);
                    borderLayers[i] = null;
                }
            }
        }
        
        borderLayers = new FrameLayout[BORDER_LAYER_COUNT];

        for (int i = 0; i < BORDER_LAYER_COUNT; i++) {
            FrameLayout borderLayer = new FrameLayout(getContext());
            // 全尺寸叠放，不设 margin，避免内层小 View 遮挡外层导致 3D 偏移时被裁出黑块
            LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
            params.setMargins(0, 0, 0, 0);
            borderLayer.setLayoutParams(params);
            borderLayer.setBackgroundColor(Color.TRANSPARENT);
            // 3D 边框路径可能略超出子 View 理论边界；勿裁剪，否则看起来「不跟陀螺仪」
            borderLayer.setClipChildren(false);

            BorderView borderView = new BorderView(getContext(), i);
            borderLayer.addView(borderView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

            // 勿对每层边框再开 HARDWARE：与根布局 HARDWARE 叠加时，子层纹理常不随父 invalidate 更新，
            // 表现为边框不跟陀螺仪、渐变/呼吸色卡住；由根统一合成即可。

            addView(borderLayer);
            borderLayers[i] = borderLayer;
            LogHelper.d("AbyssalLayout", "createBorderLayers: layer " + (i + 1) + " (i=" + i + ") fullSize overlay");
        }
    }
    
    /**
     * 边框绘制View
     * 在onDraw中绘制白色边框线条，应用3D投影效果
     */
    private class BorderView extends View {
        private int layerIndex;
        private Paint borderPaint;
        
        public BorderView(Context context, int layerIndex) {
            super(context);
            this.layerIndex = layerIndex;
            initPaint();
        }
        
        private void initPaint() {
            borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            borderPaint.setStyle(Paint.Style.STROKE);
                // 第1层3dp到第4层2dp线性过渡
            float strokeDp = BORDER_STROKE_DP - (BORDER_STROKE_DP - BORDER_STROKE_INNER_DP) * (layerIndex / (float) Math.max(1, BORDER_LAYER_COUNT - 1));
            borderPaint.setStrokeWidth(dpToPx(strokeDp));
            
            // 设置颜色和透明度
            float alpha = BORDER_ALPHA_VALUES[layerIndex];
            int alphaValue = (int) (alpha * 255);
            int borderColor = Color.argb(alphaValue, 
                Color.red(BORDER_COLOR), 
                Color.green(BORDER_COLOR), 
                Color.blue(BORDER_COLOR));
            borderPaint.setColor(borderColor);
        }
        
        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (getWidth() == 0 || getHeight() == 0) {
                return;
            }
            
            canvas.save();
            
            try {
                // 整体固定偏移：所有边框层向同一方向平移
                int offsetPxX = dpToPx(OFFSET_X_DP);
                int offsetPxY = dpToPx(OFFSET_Y_DP);
                canvas.translate(offsetPxX, offsetPxY);
                
                float viewWidth = getWidth();
                float viewHeight = getHeight();
                float viewCenterX = viewWidth / 2f;
                float viewCenterY = viewHeight / 2f;
                float aspectRatio = viewHeight / viewWidth;

                // 仅边框描边预留 2px；层内缩仍由 stroke 半宽决定
                float screenEdgeInset = ProjectionHelper.PROJECTION_EDGE_INSET_PX;
                
                // 第 2～4 层统一 worldZ 递进：外浅内深
                float worldZ = 0f - (TEXT_BASE + TEXT_OFF * (BORDER_LAYER_COUNT - 1 - layerIndex));
                
                // 与本层描边一致（第1层3dp～第4层2dp线性过渡），用于路径内缩
                int strokePx = (int) (borderPaint.getStrokeWidth() + 0.5f);
                float strokeHalfInset = strokePx / 2f;
                float edgeInset = strokeHalfInset + screenEdgeInset;
                // 每层 rect 向里缩 layerInset，全尺寸叠放后由 rect 体现层层递减（不再用 margin 嵌套）
                int layerInsetPx = dpToPx(layerIndex * AbyssalMirrorLyricsViewGroup.this.getInsetPerLayerDp());
                float cornerBase = Math.max(0f, AbyssalMirrorLyricsViewGroup.this.systemCornerRadius - edgeInset);
                float firstLayerRadius = Math.min(cornerBase, Math.max(0f, Math.min(viewWidth, viewHeight) / 2f - 1f));
                float rectW = viewWidth - 2f * (edgeInset + layerInsetPx);
                float rectH = viewHeight - 2f * (edgeInset + layerInsetPx);
                float maxRadius = Math.max(0f, Math.min(rectW, rectH) / 2f - 1f);
                float cornerRadius = Math.min(firstLayerRadius, maxRadius);

                RectF rect = new RectF(edgeInset + layerInsetPx, edgeInset + layerInsetPx, viewWidth - edgeInset - layerInsetPx, viewHeight - edgeInset - layerInsetPx);
                
                android.graphics.Path borderPath = new android.graphics.Path();
                borderPath.addRoundRect(rect, cornerRadius, cornerRadius, android.graphics.Path.Direction.CW);
                
                // 第 1 层不做 3D；第 2～4 层：euler 按层缩放，第二层轻微(1.0)、向内层层叠加；陀螺仪不动时 eulerForDraw→[0,0,0]
                float[] euler = AbyssalMirrorLyricsViewGroup.this.eulerSnapshot;
                boolean do3D = layerIndex > 0 && euler != null && euler.length >= 3;
                float[] eulerToUse = eulerForDraw(euler);
                eulerToUse = scaleEuler(eulerToUse, gyroSensitivityMultiplier);  // 可调节陀螺仪跟随倍数
                float layerScale = 1.0f + (layerIndex - 1) * GYRO_SCALE_STEP;  // 第2层=1.0，第3层=1.22，第4层=1.44
                float[] eulerScaled = scaleEuler(eulerToUse, layerScale);
                // 静止时：eulerToUse=[0,0,0]，不做 3D 路径变换、不雾化，显示默认的层层内缩
                float emag = Math.abs(eulerToUse[0]) + Math.abs(eulerToUse[1]) + Math.abs(eulerToUse[2]);
                boolean atRest = (emag < 1e-5f);
                float pathCenterX = viewCenterX, pathCenterY = viewCenterY;
                if (do3D && !atRest) {
                    // 参考位移（最内层）：用最内层 worldZ 与 scale 得到射线端点，倒推整条中心线
                    float refWorldZ = 0f - TEXT_BASE;
                    float refScale = 1.0f + (BORDER_LAYER_COUNT - 2) * GYRO_SCALE_STEP;
                    float[] refEuler = scaleEuler(eulerToUse, refScale);
                    float[] refPlane = AbyssalMirrorLyricsViewGroup.this.project3D(new float[]{0, 0, refWorldZ}, refEuler, aspectRatio);
                    // 反转方向，使边框层与歌词层移动方向一致
                    float refDx = -refPlane[0] * (viewWidth / 2f);
                    float refDy = refPlane[1] * (viewHeight / 2f);
                    // 第 2 层 fraction=1/3 即开始移动，第 4 层 fraction=1（中心在 viewCenter+ref），避免第 2 层不动造成断层
                    float fraction = layerIndex / (float) (BORDER_LAYER_COUNT - 1);
                    float targetX = viewCenterX + fraction * refDx;
                    float targetY = viewCenterY + fraction * refDy;
                    pathCenterX = targetX;
                    pathCenterY = targetY;
                    // 对路径上的点进行3D投影，用 eulerScaled 实现层层叠加的 XY 移动
                    android.graphics.PathMeasure pathMeasure = new android.graphics.PathMeasure(borderPath, false);
                    float pathLength = pathMeasure.getLength();
                    
                    if (pathLength > 0) {
                        // 采样点减少以降低 CPU/耗电，视觉仍平滑
                        int sampleCount = Math.max(
                            lightweightModeEnabled ? 24 : 40,
                            (int)(pathLength / (lightweightModeEnabled ? 18f : 12f))
                        );
                        android.graphics.Path transformedPath = new android.graphics.Path();
                        boolean isFirstPoint = true;
                        float sumX = 0f, sumY = 0f;
                        int ptCount = 0;
                        
                        for (int i = 0; i <= sampleCount; i++) {
                            float distance = (i / (float)sampleCount) * pathLength;
                            float[] pos = new float[2];
                            float[] tan = new float[2];
                            if (!pathMeasure.getPosTan(distance, pos, tan)) {
                                continue;
                            }
                            
                            float ndcX = (pos[0] - viewCenterX) / (viewWidth / 2.0f);
                            float ndcY = -(pos[1] - viewCenterY) / (viewHeight / 2.0f);
                            float[] worldP = new float[]{ndcX, ndcY, worldZ};
                            float[] planeP = AbyssalMirrorLyricsViewGroup.this.project3D(worldP, eulerScaled, aspectRatio);
                            // planeP[0],[1] 已是 NDC [-1,1]，直接乘 viewHalf 得 view 坐标
                            // 反转方向，使边框层与歌词层移动方向一致
                            float viewX = viewCenterX - planeP[0] * (viewWidth / 2.0f);
                            float viewY = viewCenterY + planeP[1] * (viewHeight / 2.0f);
                            sumX += viewX;
                            sumY += viewY;
                            ptCount++;
                            
                            if (isFirstPoint) {
                                transformedPath.moveTo(viewX, viewY);
                                isFirstPoint = false;
                            } else {
                                transformedPath.lineTo(viewX, viewY);
                            }
                        }
                        transformedPath.close();
                        // 几何中心对齐到「中心线」上的 (targetX,targetY)：由最内层参考位移倒推，各层中心共线
                        if (ptCount > 0) {
                            float cenX = sumX / ptCount;
                            float cenY = sumY / ptCount;
                            transformedPath.offset(targetX - cenX, targetY - cenY);
                        }
                        borderPath = transformedPath;
                    }
                }
                
                    // 时空隧道：第 2～4 层绕中心逐级缩小，始终层层向内堆叠，静止与陀螺仪移动时一致
                if (layerIndex >= 1) {
                    float scale = 1.0f - layerIndex * TUNNEL_SCALE_PER_LAYER;
                    scale = Math.max(0.5f, Math.min(1f, scale));
                    Matrix m = new Matrix();
                    m.setScale(scale, scale, pathCenterX, pathCenterY);
                    borderPath.transform(m);
                }
                
                // 呼吸渐变幻色：自上而下，起/止色随机平滑过渡；透明度按 BORDER_ALPHA_VALUES 与 breath 逐层减
                float alpha = BORDER_ALPHA_VALUES[layerIndex] * breathFactor;
                android.graphics.Shader gradient = new LinearGradient(0, 0, 0, viewHeight, currentGradientStart, currentGradientEnd, Shader.TileMode.CLAMP);
                borderPaint.setShader(gradient);
                borderPaint.setAlpha((int) (Math.max(0f, Math.min(1f, alpha)) * 255));

                // 绘制：第1层用填充圆环（避免 STROKE 圆角显粗、直线显细）；第2～4层用描边路径；圆角沿用第一层
                if (layerIndex == 0 && viewWidth > 2 * strokePx && viewHeight > 2 * strokePx && Build.VERSION.SDK_INT >= 19) {
                    float rOuter = firstLayerRadius;
                    float rInner = Math.max(0f, rOuter - strokePx);
                    android.graphics.Path outerP = new android.graphics.Path();
                    outerP.addRoundRect(new RectF(0, 0, viewWidth, viewHeight), rOuter, rOuter, android.graphics.Path.Direction.CW);
                    android.graphics.Path innerP = new android.graphics.Path();
                    innerP.addRoundRect(new RectF(strokePx, strokePx, viewWidth - strokePx, viewHeight - strokePx), rInner, rInner, android.graphics.Path.Direction.CW);
                    outerP.op(innerP, android.graphics.Path.Op.DIFFERENCE);
                    borderPaint.setStyle(Paint.Style.FILL);
                    canvas.drawPath(outerP, borderPaint);
                    borderPaint.setStyle(Paint.Style.STROKE);
                } else {
                    canvas.drawPath(borderPath, borderPaint);
                }
                
            } catch (Exception e) {
                LogHelper.e("AbyssalMirrorLyrics", "❌ 绘制边框失败: layer=" + layerIndex, e);
            } finally {
                canvas.restore();
            }
        }
    }
    
    /**
     * 深渊镜歌词绘制 View：3 层文字 + 雾化(applyFog) + 光照(NORMAL·-VRayDir)，
     * 参考深渊主题 abyssal_mirror_text_vert/frag.glsl
     */
    private class AbyssalLyricView extends View {
        private String lyric = "暂无歌词";
        private float textSizePx = DEFAULT_TEXT_SIZE_PX;
        private int textColor = DEFAULT_TEXT_COLOR;
        private android.graphics.Typeface typeface;
        private android.text.TextPaint[] layerPaints = new android.text.TextPaint[TEXT_LAYER_COUNT];
        private android.text.TextPaint[] blurPaints = new android.text.TextPaint[TEXT_LAYER_COUNT];
        private static final float LIGHT_INTENSITY = 1.5f;
        private long lyricChangeTime = 0;
        /** 渐变缓存，避免每帧 new LinearGradient 降低 GC 与 CPU */
        private android.graphics.Shader cachedLyricGradient;
        private int cachedGradientStart = 0;
        private int cachedGradientEnd = 0;
        private float cachedGradientVh = 0f;
        private final ArrayList<String> wrappedLyricLines = new ArrayList<>();
        private static final float WRAPPED_SUBLINE_GAP_PX = 8f;

        AbyssalLyricView(Context context) {
            super(context);
            typeface = LyricsFontHelper.resolveTypeface(context, LyricsFontHelper.DEFAULT_ID);
            for (int i = 0; i < TEXT_LAYER_COUNT; i++) {
                layerPaints[i] = new android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                layerPaints[i].setTextSize(textSizePx);
                layerPaints[i].setColor(textColor);
                layerPaints[i].setTextAlign(android.graphics.Paint.Align.CENTER);
                layerPaints[i].setTypeface(typeface);
                blurPaints[i] = new android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                blurPaints[i].setTextSize(textSizePx);
                blurPaints[i].setColor(textColor);
                blurPaints[i].setTextAlign(android.graphics.Paint.Align.CENTER);
                blurPaints[i].setTypeface(typeface);
                float r = (i == 2) ? 8f : (i == 1 ? 4f : 0f);
                if (r > 0) {
                    blurPaints[i].setMaskFilter(new android.graphics.BlurMaskFilter(r, android.graphics.BlurMaskFilter.Blur.NORMAL));
                }
            }
        }
        
        void setLyric(String s) { lyric = s != null ? s : "暂无歌词"; lyricChangeTime = System.currentTimeMillis(); invalidate(); }
        String getCurrentLyric() { return lyric; }
        void setTextSizePx(float px) { textSizePx = px; for (int i = 0; i < TEXT_LAYER_COUNT; i++) { layerPaints[i].setTextSize(px); blurPaints[i].setTextSize(px); } invalidate(); }
        float getTextSizePx() { return textSizePx; }
        void setTextColor(int c) { textColor = c; invalidate(); }
        int getTextColor() { return textColor; }

        void setLyricsTypeface(android.graphics.Typeface tf) {
            typeface = tf != null ? tf : android.graphics.Typeface.DEFAULT;
            for (int i = 0; i < TEXT_LAYER_COUNT; i++) {
                layerPaints[i].setTypeface(typeface);
                blurPaints[i].setTypeface(typeface);
            }
            invalidate();
        }
        
        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            if (getWidth() == 0 || getHeight() == 0) return;
            // 整体固定偏移：与边框同方向平移
            canvas.save();
            int offsetPxX = AbyssalMirrorLyricsViewGroup.this.dpToPx(OFFSET_X_DP);
            int offsetPxY = AbyssalMirrorLyricsViewGroup.this.dpToPx(OFFSET_Y_DP);
            canvas.translate(offsetPxX, offsetPxY);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float vw = getWidth();
            float vh = getHeight();
            // 裁剪到最后一层矩形框内：与第 4 层内边一致的圆角矩形，超宽句自动换行
            float clipR = Math.max(0, Math.min(AbyssalMirrorLyricsViewGroup.this.systemCornerRadius * 0.35f, Math.min(vw, vh) / 2f - 1f));
            if (clipR > 0.5f) {
                android.graphics.Path clipPath = new android.graphics.Path();
                clipPath.addRoundRect(new RectF(0, 0, vw, vh), clipR, clipR, android.graphics.Path.Direction.CW);
                canvas.clipPath(clipPath);
            } else {
                canvas.clipRect(0, 0, vw, vh);
            }
            int padPx = AbyssalMirrorLyricsViewGroup.this.dpToPx(6);
            float maxTextWidth = Math.max(1f, vw - 2f * padPx);
            List<String> displayLines = wrapLyricLines(layerPaints[0], lyric, maxTextWidth);
            canvas.clipRect(padPx, 0, vw - padPx, vh);
            // 呼吸渐变幻色：与圆角框一致；复用渐变对象以降低 GC/CPU
            if (cachedLyricGradient == null || cachedGradientStart != currentGradientStart
                || cachedGradientEnd != currentGradientEnd || cachedGradientVh != vh) {
                cachedLyricGradient = new LinearGradient(0, 0, 0, vh, currentGradientStart, currentGradientEnd, Shader.TileMode.CLAMP);
                cachedGradientStart = currentGradientStart;
                cachedGradientEnd = currentGradientEnd;
                cachedGradientVh = vh;
            }
            android.graphics.Shader lyricGradient = cachedLyricGradient;
            float aspect = vh / vw;
            float[] euler = AbyssalMirrorLyricsViewGroup.this.eulerSnapshot;
            float[] eulerToUse = AbyssalMirrorLyricsViewGroup.this.eulerForDraw(euler);
            eulerToUse = AbyssalMirrorLyricsViewGroup.this.scaleEuler(eulerToUse, AbyssalMirrorLyricsViewGroup.this.gyroSensitivityMultiplier);  // 可调节陀螺仪跟随倍数
            // 歌词基础移幅在最后一层边框基础上再乘可调节的可移动范围倍率
            float lyricsBaseScale = (1.0f + (BORDER_LAYER_COUNT - 1) * GYRO_SCALE_STEP) * AbyssalMirrorLyricsViewGroup.this.movableRangeMultiplier;
            float[] eye = new float[]{0, 0, EYE_Z};
            float emag = Math.abs(eulerToUse[0]) + Math.abs(eulerToUse[1]) + Math.abs(eulerToUse[2]);
            boolean atRest = (emag < 1e-5f);
            // 从远到近：i=2,1,0；三层歌词基本重叠，一起移动，每层只偏移10-20像素
            // 先计算第一层（最远层）的位置，作为基准
            float firstLayerMoveScale = 1.0f * lyricsBaseScale;
            float[] firstLayerEulerScaled = AbyssalMirrorLyricsViewGroup.this.scaleEuler(eulerToUse, firstLayerMoveScale);
            float firstLayerOffsetZ = TEXT_LAYER_OFFSETS[TEXT_LAYER_COUNT - 1];
            float firstLayerWorldZ = TEXT_RENDER_PLANE_Z - firstLayerOffsetZ;
            float[] firstLayerWorldP = new float[]{0, 0, firstLayerWorldZ};
            float[] firstLayerPlaneP = AbyssalMirrorLyricsViewGroup.this.project3D(firstLayerWorldP, firstLayerEulerScaled, aspect);
            float firstLayerScreenX = cx + firstLayerPlaneP[0] * (vw / 2f);
            float firstLayerScreenY = cy - firstLayerPlaneP[1] * (vh / 2f);
            
            for (int i = TEXT_LAYER_COUNT - 1; i >= 0; i--) {
                float screenX, screenY;
                float[] eulerScaled;
                float offsetZ = TEXT_LAYER_OFFSETS[i];
                float worldZ = TEXT_RENDER_PLANE_Z - offsetZ;
                float[] worldP = new float[]{0, 0, worldZ};
                float[] planeP;
                
                // 所有层都使用第一层的位置和eulerScaled，确保一起移动
                eulerScaled = firstLayerEulerScaled;
                planeP = AbyssalMirrorLyricsViewGroup.this.project3D(worldP, firstLayerEulerScaled, aspect);
                
                // 每层加上像素偏移，偏移方向跟随陀螺仪转动
                float baseOffsetX = AbyssalMirrorLyricsViewGroup.this.dpToPx(TEXT_LAYER_PIXEL_OFFSET_X_DP[i]);
                float baseOffsetY = AbyssalMirrorLyricsViewGroup.this.dpToPx(TEXT_LAYER_PIXEL_OFFSET_Y_DP[i]);
                
                // 根据陀螺仪的旋转角度计算偏移方向
                // 使用eulerToUse的角度来旋转偏移向量，让偏移方向跟随手机转动
                float eulerY = eulerToUse[1];  // Y轴旋转（左右摆动）
                float eulerX = eulerToUse[0];  // X轴旋转（上下摆动）
                
                // 使用2D旋转矩阵旋转偏移向量
                // 旋转角度需要适当缩放，让偏移方向更明显地跟随陀螺仪
                float rotationScale = 3.0f;  // 增大旋转效果，让偏移方向更明显
                float rotationAngle = eulerY * rotationScale;  // 主要使用Y轴旋转来控制偏移方向
                float cosAngle = (float) Math.cos(rotationAngle);
                float sinAngle = (float) Math.sin(rotationAngle);
                
                // 旋转偏移向量：根据陀螺仪的转动方向调整偏移方向
                float rotatedOffsetX = baseOffsetX * cosAngle - baseOffsetY * sinAngle;
                float rotatedOffsetY = baseOffsetX * sinAngle + baseOffsetY * cosAngle;
                
                // 同时根据X轴旋转调整Y方向偏移，让上下摆动也能影响偏移方向
                float eulerXFactor = eulerX * rotationScale * 0.5f;  // 增加X轴影响，让上下摆动更明显
                rotatedOffsetY += eulerXFactor * baseOffsetY;
                
                // 增加动态偏移，让第二层和第三层有更明显的摆动
                // 根据陀螺仪的旋转角度，增加额外的动态偏移
                if (i < TEXT_LAYER_COUNT - 1) {
                    float dynamicOffsetX = eulerY * baseOffsetX * 0.5f;  // 根据Y轴旋转增加动态偏移
                    float dynamicOffsetY = eulerX * baseOffsetY * 0.5f;  // 根据X轴旋转增加动态偏移
                    rotatedOffsetX += dynamicOffsetX;
                    rotatedOffsetY += dynamicOffsetY;
                }
                
                // 确保第二层和第三层完全跟随第一层移动，只加上旋转后的偏移
                screenX = firstLayerScreenX + rotatedOffsetX;
                screenY = firstLayerScreenY + rotatedOffsetY;
                float vDepth = planeP[2] - worldZ;
                // 光照：dot(NORMAL,-VRayDir)*LightIntensity+0.1，NORMAL=(0,0,1)
                float[] rotEye = AbyssalMirrorLyricsViewGroup.this.rotateEuler(eye, eulerScaled);
                float[] rayDir = new float[]{worldP[0] - rotEye[0], worldP[1] - rotEye[1], worldP[2] - rotEye[2]};
                float len = (float) Math.sqrt(rayDir[0]*rayDir[0] + rayDir[1]*rayDir[1] + rayDir[2]*rayDir[2]);
                if (len > 0.0001f) { rayDir[0]/=len; rayDir[1]/=len; rayDir[2]/=len; }
                float nd = -rayDir[2];
                float bright = Math.max(0.1f, Math.min(1f, nd * LIGHT_INTENSITY + 0.1f));
                // 雾化：静止时 fog=0 显默认；有转动时 fogAmount = 1 - exp(-(vDepth-(EYE_Z-1))*FOG_K)
                float fog = atRest ? 0f : (1.0f - (float) Math.exp(-(vDepth - (EYE_Z - 1.0f)) * FOG_K));
                fog = Math.max(0, Math.min(1, fog));
                // 渐变作底色，LightingColorFilter 乘 (1-fog)*bright 保留雾化与光照；呼吸系数乘到 alpha
                int mul = Math.max(0, Math.min(255, (int) ((1f - fog) * bright * 255)));
                int mulRGB = (mul << 16) | (mul << 8) | mul;
                android.graphics.LightingColorFilter colorFilter = new LightingColorFilter(mulRGB, 0);
                float ba = TEXT_LAYER_ALPHA[i];
                if (i > 0) ba *= (1f - fog * 0.5f);
                int a = (int) (ba * 255 * breathFactor);
                a = Math.max(0, Math.min(255, a));
                android.graphics.Paint.FontMetrics fm = layerPaints[i].getFontMetrics();
                float lineH = fm.descent - fm.ascent;
                float blockH = displayLines.size() * lineH
                    + Math.max(0, displayLines.size() - 1) * WRAPPED_SUBLINE_GAP_PX;
                float firstLineCenterY = screenY - blockH / 2f + lineH / 2f;
                for (int lineIdx = 0; lineIdx < displayLines.size(); lineIdx++) {
                    String lineText = displayLines.get(lineIdx);
                    float lineCenterY = firstLineCenterY + lineIdx * (lineH + WRAPPED_SUBLINE_GAP_PX);
                    float ty = lineCenterY - (fm.ascent + fm.descent) / 2f;
                    if (i > 0 && blurPaints[i].getMaskFilter() != null) {
                        blurPaints[i].setShader(lyricGradient);
                        blurPaints[i].setColorFilter(colorFilter);
                        blurPaints[i].setAlpha(a);
                        canvas.drawText(lineText, screenX, ty, blurPaints[i]);
                    } else {
                        layerPaints[i].setShader(lyricGradient);
                        layerPaints[i].setColorFilter(colorFilter);
                        layerPaints[i].setAlpha(a);
                        canvas.drawText(lineText, screenX, ty, layerPaints[i]);
                    }
                }
            }
            canvas.restore();
        }

        private List<String> wrapLyricLines(android.text.TextPaint paint, String text, float maxWidth) {
            wrappedLyricLines.clear();
            if (text == null || text.isEmpty()) {
                return wrappedLyricLines;
            }
            if (paint.measureText(text) <= maxWidth) {
                wrappedLyricLines.add(text);
                return wrappedLyricLines;
            }
            int layoutWidth = Math.max(1, Math.round(maxWidth));
            StaticLayout layout = StaticLayout.Builder
                .obtain(text, 0, text.length(), paint, layoutWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .build();
            for (int i = 0; i < layout.getLineCount(); i++) {
                int start = layout.getLineStart(i);
                int end = layout.getLineEnd(i);
                String line = text.substring(start, end);
                if (!line.isEmpty()) {
                    wrappedLyricLines.add(line);
                }
            }
            if (wrappedLyricLines.isEmpty()) {
                wrappedLyricLines.add(text);
            }
            return wrappedLyricLines;
        }
    }
    
    /**
     * 创建深渊镜歌词 View（3 层叠影 + 雾化 + 光照）
     * 歌词图层在最后一层：加入第 4 层（最内层）边框层，margin 与最内层里边对齐，显示在最后一层矩形框内，超宽自动换行
     */
    private void createAbyssalLyricView() {
        abyssalLyricView = new AbyssalLyricView(getContext());

        float textSizePx = getTextSizeFromSettings();
        abyssalLyricView.setTextSizePx(textSizePx);
        abyssalLyricView.setTextColor(DEFAULT_TEXT_COLOR);
        abyssalLyricView.setLyric("暂无歌词");

        // 与第 4 层（最内层）「里边」对齐：最内层 layerInset + 描边 = (BORDER_LAYER_COUNT-1)*getInsetPerLayerDp() + BORDER_STROKE_INNER_DP
        float innerInsetDp = (BORDER_LAYER_COUNT - 1) * getInsetPerLayerDp() + BORDER_STROKE_INNER_DP;
        int innerInsetPx = dpToPx(innerInsetDp);
        LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        params.setMargins(innerInsetPx, innerInsetPx, innerInsetPx, innerInsetPx);
        abyssalLyricView.setLayoutParams(params);

        // 与边框层一致：不在此子 View 上单独开 HARDWARE，避免渐变/陀螺仪与父级刷新不同步

        // 歌词置于最后一层（第 4 层）边框层内，作为其子 View，显示在最后一层矩形框内
        borderLayers[BORDER_LAYER_COUNT - 1].addView(abyssalLyricView);
        // 重建后从偏好读投屏歌词字体（与设置页、ModernLyricsView 一致）
        applyProjectionLyricsFontFromPrefs();
    }

    /**
     * 与 [RearScreenLyricsActivity.loadLyricsFontFieldsFromPrefs] 中投屏字体逻辑对齐。
     */
    private void applyProjectionLyricsFontFromPrefs() {
        if (abyssalLyricView == null) {
            return;
        }
        try {
            SharedPreferences p = getContext().getSharedPreferences(PREFS_LYRICS_SETTINGS, Context.MODE_PRIVATE);
            String fontId = LyricsFontHelper.normalizeFontId(p.getString(KEY_PROJECTION_LYRICS_FONT, null));
            String path = p.getString(KEY_PROJECTION_LYRICS_CUSTOM_PATH, null);
            if (path != null && path.isEmpty()) {
                path = null;
            }
            if (!LyricsFontHelper.ID_CUSTOM.equals(fontId)) {
                path = null;
            } else if (path == null || !(new java.io.File(path).isFile())) {
                fontId = LyricsFontHelper.DEFAULT_ID;
                path = null;
            }
            abyssalLyricView.setLyricsTypeface(LyricsFontHelper.resolveTypeface(getContext(), fontId, path));
        } catch (Exception e) {
            LogHelper.w("AbyssalMirrorLyrics", "读取投屏歌词字体偏好失败: " + e.getMessage());
        }
    }
    
    /**
     * 从SharedPreferences读取歌词字体大小设置
     * 
     * @return 字体大小（px）
     */
    private float getTextSizeFromSettings() {
        try {
            SharedPreferences prefs = getContext().getSharedPreferences(PREFS_LYRICS_SETTINGS, Context.MODE_PRIVATE);
            float textSize = prefs.getFloat("textSize", DEFAULT_TEXT_SIZE_PX);
            LogHelper.d("AbyssalMirrorLyrics", "✅ 从设置读取字体大小: " + textSize + "px");
            return textSize;
        } catch (Exception e) {
            LogHelper.w("AbyssalMirrorLyrics", "⚠️ 读取字体大小设置失败，使用默认值: " + DEFAULT_TEXT_SIZE_PX + "px", e);
            return DEFAULT_TEXT_SIZE_PX;
        }
    }
    
    // ========== 公共方法 ==========
    
    /**
     * 更新歌词文本
     *
     * @param lyric 歌词文本；为 null 或空白时，由 {@code emptyMeansNoLyrics} 决定显示「暂无歌词」还是留空。
     * @param emptyMeansNoLyrics true：空白视为无歌词（使用 {@code R.string.music_no_lyrics}）；false：允许界面留空
     */
    public void setLyric(String lyric, boolean emptyMeansNoLyrics) {
        if (abyssalLyricView == null) {
            return;
        }
        if (lyric == null || lyric.trim().isEmpty()) {
            if (emptyMeansNoLyrics) {
                abyssalLyricView.setLyric(getContext().getString(R.string.music_no_lyrics));
            } else {
                abyssalLyricView.setLyric("");
            }
        } else {
            abyssalLyricView.setLyric(lyric);
        }
    }

    /**
     * 与历史行为一致：空白时显示「暂无歌词」。
     */
    public void setLyric(String lyric) {
        setLyric(lyric, true);
    }
    
    /**
     * 设置歌词字体大小（px）
     */
    public void setTextSize(float sizePx) {
        if (abyssalLyricView != null) {
            abyssalLyricView.setTextSizePx(sizePx);
        }
    }

    /** 深渊镜歌词字体，由 {@link LyricsFontHelper} 解析后传入。 */
    public void setLyricsTypeface(android.graphics.Typeface typeface) {
        if (abyssalLyricView != null) {
            abyssalLyricView.setLyricsTypeface(typeface);
        }
    }
    
    /**
     * 设置歌词颜色（ARGB）
     */
    public void setTextColor(int color) {
        if (abyssalLyricView != null) {
            abyssalLyricView.setTextColor(color);
        }
    }
    
    /**
     * 获取歌词颜色
     */
    public int getTextColor() {
        return currentGradientStart;
    }

    /** 方案1：设置上一首/下一首手势回调 */
    public void setOnPrevNextGestureListener(OnPrevNextGestureListener l) {
        prevNextListener = l;
    }

    /** 方案1：开关手势上一首/下一首，与 gestureControlEnabled 一致 */
    public void setEnableGesture(boolean enabled) {
        enableGesture = enabled;
    }

    /** 设置陀螺仪跟随倍数（0.5～2.0 推荐，默认 1.0） */
    public void setGyroSensitivityMultiplier(float multiplier) {
        gyroSensitivityMultiplier = Math.max(0.3f, Math.min(3f, multiplier));
    }

    /** 设置可移动范围倍率（1.0～4.0 推荐，默认 2.5） */
    public void setMovableRangeMultiplier(float multiplier) {
        movableRangeMultiplier = Math.max(0.5f, Math.min(5f, multiplier));
    }

    /** 设置深渊镜颜色变化节奏 — 已改为换行触发，此方法保留兼容但不再生效。 */
    public void setColorChangeIntervalMs(long intervalMs) {
        // no-op: 换行触发模式下无需时间间隔
    }

    public void setRandomColorSwitchEnabled(boolean enabled) {
        if (randomColorSwitchEnabled == enabled) {
            return;
        }
        randomColorSwitchEnabled = enabled;
        LyricsColorManager.INSTANCE.setRandomMode(enabled);
        if (!enabled) {
            int fc = LyricsColorManager.INSTANCE.getColor();
            currentGradientStart = fc;
            currentGradientEnd = fc;
            if (abyssalLyricView != null) {
                abyssalLyricView.setTextColor(fc);
            }
            invalidateAbyssalVisualSubtree();
            postInvalidateOnAnimation();
        }
    }

    public void setPerformanceGuardEnabled(boolean enabled) {
        performanceGuardEnabled = enabled;
        if (!enabled) {
            consecutiveHeavyFrameCount = 0;
            consecutiveCoolFrameCount = 0;
        }
    }

    public void setLightweightModeEnabled(boolean enabled) {
        lightweightModeEnabled = enabled;
        invalidateAbyssalVisualSubtree();
        postInvalidateOnAnimation();
    }
    
    /**
     * 设置固定圆角半径（px），用于广播启动的音乐投屏，不检测系统圆角
     * @param px 圆角半径（像素）；传 0 或负数可恢复为检测系统圆角
     */
    public void setFixedCornerRadiusPx(float px) {
        fixedCornerRadiusPx = (px > 0) ? px : null;
        systemCornerRadius = (fixedCornerRadiusPx != null) ? fixedCornerRadiusPx : DEFAULT_CORNER_RADIUS;
        if (borderLayers != null) {
            for (FrameLayout layer : borderLayers) {
                if (layer != null) {
                    for (int i = 0; i < layer.getChildCount(); i++) {
                        View child = layer.getChildAt(i);
                        if (child != null) child.invalidate();
                    }
                }
            }
        }
        if (abyssalLyricView != null) abyssalLyricView.invalidate();
        invalidate();
    }
    
    /**
     * 设置是否为主屏横屏模式（用于重新检测圆角半径）
     * @param isMainScreenLandscape 是否为主屏横屏模式
     */
    public void setMainScreenLandscapeMode(boolean isMainScreenLandscape) {
        if (this.isMainScreenLandscapeMode != isMainScreenLandscape) {
            this.isMainScreenLandscapeMode = isMainScreenLandscape;
            
            // 保存当前歌词View的状态
            String currentLyric = null;
            float currentTextSize = 0f;
            int currentTextColor = DEFAULT_TEXT_COLOR;
            if (abyssalLyricView != null) {
                currentLyric = abyssalLyricView.getCurrentLyric();
                currentTextSize = abyssalLyricView.getTextSizePx();
                currentTextColor = abyssalLyricView.getTextColor();
            }
            
            // 主屏和背屏的圆角可能不同，需要重新检测
            detectSystemCornerRadius();
            LogHelper.d("AbyssalMirrorLyrics", "🖥️ " + (isMainScreenLandscape ? "主屏横屏" : "背屏") + "模式：重新检测圆角半径: " + systemCornerRadius + "px");
            
            // 需要重新创建边框层以应用新的圆角
            createBorderLayers();
            
            // 重新创建歌词View并恢复状态
            createAbyssalLyricView();
            if (currentLyric != null) {
                abyssalLyricView.setLyric(currentLyric);
            }
            if (currentTextSize > 0f) {
                abyssalLyricView.setTextSizePx(currentTextSize);
            }
            abyssalLyricView.setTextColor(currentTextColor);
        }
    }

    /**
     * 视图挂载后重新评估主屏圆角（启动时 insets 可能尚未就绪）。
     */
    public void reevaluateCornerRadius() {
        if (fixedCornerRadiusPx != null) {
            return;
        }
        detectSystemCornerRadius();
        if (borderLayers != null) {
            for (FrameLayout layer : borderLayers) {
                if (layer != null) {
                    for (int i = 0; i < layer.getChildCount(); i++) {
                        View child = layer.getChildAt(i);
                        if (child != null) {
                            child.invalidate();
                        }
                    }
                }
            }
        }
        if (abyssalLyricView != null) {
            abyssalLyricView.invalidate();
        }
        invalidate();
        LogHelper.d("AbyssalMirrorLyrics", "reevaluate corner radius: " + systemCornerRadius + "px");
    }
    
    // ========== 工具方法 ==========
    
    /**
     * 注册传感器监听器
     * 应在Activity的onResume中调用
     */
    public void registerSensorListener() {
        if (sensorManager != null) {
            sensorManager.start();
            LogHelper.d("AbyssalMirrorLyrics", "✅ 已启动传感器管理器");
        }
    }
    
    /**
     * 注销传感器监听器
     * 应在Activity的onPause中调用
     */
    public void unregisterSensorListener() {
        if (sensorManager != null) {
            sensorManager.stop();
            LogHelper.d("AbyssalMirrorLyrics", "✅ 已停止传感器管理器");
        }
    }
    
    /**
     * 陀螺仪静止时返回 [0,0,0]，使边框与歌词显示在初始位置（层层递减的同心布局）；有转动时返回 euler。
     */
    private float[] eulerForDraw(float[] euler) {
        if (euler == null || euler.length < 3) return new float[]{0, 0, 0};
        float mag = Math.abs(euler[0]) + Math.abs(euler[1]) + Math.abs(euler[2]);
        if (mag < GYRO_REST_THRESHOLD) return new float[]{0, 0, 0};
        
        // 在主屏幕横屏模式下，交换XY轴并反转X轴
        if (isMainScreenLandscapeMode) {
            // 交换X和Y轴，不反转方向
            // 向左摆动 → 歌词向左移动，向上摆动 → 歌词向上移动
            return new float[]{euler[1], euler[0], euler[2]};
        }
        
        // 背屏模式：反转X和Y轴
        // 向左摆动 → 歌词向右移动，向下摆动 → 歌词向上移动
        return new float[]{-euler[0], -euler[1], euler[2]};
    }
    
    /**
     * 按 scale 缩放 euler，用于层层叠加：第2层轻微(1.0)，内层与歌词逐层增大。
     */
    private float[] scaleEuler(float[] e, float scale) {
        if (e == null || e.length < 3) return new float[]{0, 0, 0};
        return new float[]{e[0] * scale, e[1] * scale, e[2] * scale};
    }
    
    /**
     * 欧拉角旋转函数（参考AbyssalMirrorLyricsView）
     */
    private float[] rotateEuler(float[] v, float[] euler) {
        float cx = (float) Math.cos(euler[0]);
        float sx = (float) Math.sin(euler[0]);
        float cy = (float) Math.cos(euler[1]);
        float sy = (float) Math.sin(euler[1]);
        float cz = (float) Math.cos(euler[2]);
        float sz = (float) Math.sin(euler[2]);
        
        // 旋转矩阵：R = Rz * Ry * Rx
        float x = v[0];
        float y = v[1];
        float z = v[2];
        
        // Rx旋转
        float y1 = y * cx - z * sx;
        float z1 = y * sx + z * cx;
        
        // Ry旋转
        float x2 = x * cy + z1 * sy;
        float z2 = -x * sy + z1 * cy;
        
        // Rz旋转
        float x3 = x2 * cz - y1 * sz;
        float y3 = x2 * sz + y1 * cz;
        
        return new float[]{x3, y3, z2};
    }
    
    /**
     * 3D 投影（参考深渊主题 abyssal_mirror_rect_vert.glsl / abyssal_mirror_text_vert.glsl）
     * 供 BorderView 与 AbyssalLyricView 共用。
     */
    private float[] project3D(float[] worldP, float[] eulerDiff, float aspectRatio) {
        float[] eyePos = new float[]{0, 0, EYE_Z};
        float[] rotatedEye = rotateEuler(eyePos, eulerDiff);
        
        float[] rayDir = new float[]{
            worldP[0] - rotatedEye[0],
            worldP[1] - rotatedEye[1],
            worldP[2] - rotatedEye[2]
        };
        float rayLength = (float) Math.sqrt(
            rayDir[0] * rayDir[0] + rayDir[1] * rayDir[1] + rayDir[2] * rayDir[2]
        );
        if (rayLength > 0.0001f) {
            rayDir[0] /= rayLength;
            rayDir[1] /= rayLength;
            rayDir[2] /= rayLength;
        } else {
            rayDir = new float[]{0, 0, 1};
        }
        
        float[] planeNormal = new float[]{0, 0, 1};
        float denom = planeNormal[0] * rayDir[0] + planeNormal[1] * rayDir[1] + planeNormal[2] * rayDir[2];
        
        float t = 0;
        if (Math.abs(denom) > 0.0001f) {
            float[] planePoint = new float[]{0, 0, 0.01f};
            float[] toPlane = new float[]{
                planePoint[0] - rotatedEye[0],
                planePoint[1] - rotatedEye[1],
                planePoint[2] - rotatedEye[2]
            };
            float numerator = toPlane[0] * planeNormal[0] + toPlane[1] * planeNormal[1] + toPlane[2] * planeNormal[2];
            t = numerator / denom;
        }
        
        // hit = eye + t*rayDir，rayDir = (worldP-eye)/L；故 hit_x*L/t = ndcX，直接可乘 viewWidth/2 得 viewX
        float hitX = rotatedEye[0] + t * rayDir[0];
        float hitY = rotatedEye[1] + t * rayDir[1];
        float hitZ = rotatedEye[2] + t * rayDir[2];
        float ndcX = (t > 1e-6f && rayLength > 1e-6f) ? (hitX * rayLength / t) : worldP[0];
        float ndcY = (t > 1e-6f && rayLength > 1e-6f) ? (hitY * rayLength / t) : worldP[1];
        float[] result = new float[4];
        result[0] = ndcX;
        result[1] = ndcY;
        result[2] = hitZ;
        result[3] = (t > 1e-6f) ? t : 1f;
        return result;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (gestureDetector != null && gestureDetector.onTouchEvent(ev)) return true;
        if (enableGesture) return true; // 保持接收完整序列以供 onFling
        return super.onTouchEvent(ev);
    }
    
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        registerSensorListener();
        needsRedraw = true;
        if (frameCallback != null) {
            Choreographer.getInstance().postFrameCallback(frameCallback);
        }
    }
    
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // 诊断：若 w<screenW 或 h<screenH 则在该层之上被缩小。背屏用 getDisplay().getRealSize
        int screenW = getContext().getResources().getDisplayMetrics().widthPixels;
        int screenH = getContext().getResources().getDisplayMetrics().heightPixels;
        if (Build.VERSION.SDK_INT >= 23) {
            android.view.Display d = getDisplay();
            if (d != null) {
                Point p = new Point();
                d.getRealSize(p);
                screenW = p.x;
                screenH = p.y;
            }
        }
        int pl = getPaddingLeft(), pr = getPaddingRight();
        int pw = (getParent() instanceof View) ? ((View) getParent()).getWidth() : 0;
        if (BuildConfig.DEBUG) {
            LogHelper.d("AbyssalLayout", String.format("AbyssalMirrorLyricsViewGroup onSizeChanged: w=%d h=%d | screenW=%d screenH=%d | 若w<screenW或h<screenH则在上层被缩小", w, h, screenW, screenH));
        }
        // 视图大小变化时，重新检测系统圆角半径
        detectSystemCornerRadius();
        // 通知所有BorderView更新圆角
        if (borderLayers != null) {
            for (FrameLayout layer : borderLayers) {
                if (layer != null) {
                    for (int i = 0; i < layer.getChildCount(); i++) {
                        View child = layer.getChildAt(i);
                        if (child instanceof BorderView) {
                            child.invalidate();
                        }
                    }
                }
            }
        }
    }
    
    @Override
    protected void onDetachedFromWindow() {
        if (frameHandler != null && delayedFrameRunnable != null) {
            frameHandler.removeCallbacks(delayedFrameRunnable);
        }
        if (frameCallback != null) {
            Choreographer.getInstance().removeFrameCallback(frameCallback);
        }
        unregisterSensorListener();
        super.onDetachedFromWindow();
    }
    
    /**
     * 将dp值转换为px值
     * 
     * @param dp dp值
     * @return px值
     */
    private int dpToPx(float dp) {
        float density = getContext().getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }

    /** 旧版风格：HSV 高饱和 + 高亮度连续随机。 */
    private int generateRandomColor() {
        float hue = random.nextFloat() * 360f;
        float saturation = 0.85f + random.nextFloat() * 0.15f;
        float value = 0.85f + random.nextFloat() * 0.15f;
        return Color.HSVToColor(new float[]{hue, saturation, value});
    }

    private int generateRandomColorDistinct(int primary) {
        int candidate = primary;
        float bestScore = -1f;
        int bestCandidate = primary;
        for (int i = 0; i < 12; i++) {
            candidate = generateRandomColor();
            float score = colorDifferenceScore(primary, candidate);
            if (score > bestScore) {
                bestScore = score;
                bestCandidate = candidate;
            }
            if (score >= 0.28f) {
                return candidate;
            }
        }
        return bestCandidate;
    }

    private float colorDifferenceScore(int a, int b) {
        float[] hsvA = new float[3];
        float[] hsvB = new float[3];
        Color.colorToHSV(a, hsvA);
        Color.colorToHSV(b, hsvB);
        float hueDelta = Math.abs(normalizeHueDelta(hsvB[0] - hsvA[0])) / 180f;
        float satDelta = Math.abs(hsvB[1] - hsvA[1]);
        float valueDelta = Math.abs(hsvB[2] - hsvA[2]);
        return hueDelta * 0.72f + satDelta * 0.14f + valueDelta * 0.14f;
    }

    private float normalizeHueDelta(float delta) {
        if (delta > 180f) return delta - 360f;
        if (delta < -180f) return delta + 360f;
        return delta;
    }

    /** 旧版对齐：使用 HSV 插值避免过渡期“发灰”。 */
    private int interpolateVividColor(int colorStart, int colorEnd, float ratio) {
        float t = Math.max(0f, Math.min(1f, ratio));
        float[] startHsv = new float[3];
        float[] endHsv = new float[3];
        Color.colorToHSV(colorStart, startHsv);
        Color.colorToHSV(colorEnd, endHsv);
        float hueDelta = normalizeHueDelta(endHsv[0] - startHsv[0]);
        float hue = (startHsv[0] + hueDelta * t + 360f) % 360f;
        float saturation = startHsv[1] + (endHsv[1] - startHsv[1]) * t;
        float value = startHsv[2] + (endHsv[2] - startHsv[2]) * t;
        saturation = Math.max(0.78f, Math.min(1f, saturation));
        value = Math.max(0.78f, Math.min(1f, value));
        int alpha = Math.round(Color.alpha(colorStart) + (Color.alpha(colorEnd) - Color.alpha(colorStart)) * t);
        return Color.HSVToColor(alpha, new float[]{hue, saturation, value});
    }

    /**
     * 更新呼吸系数与渐变随机换色（每帧调用，由 LyricsColorManager 驱动）。
     *
     * @return true 表示仍处于一轮颜色过渡期内，需要持续重绘。
     */
    private boolean updateBreathingAndGradientColor() {
        int color = LyricsColorManager.INSTANCE.getColor();
        currentGradientStart = color;
        currentGradientEnd = generateRandomColorDistinct(color);
        targetGradientStart = color;
        targetGradientEnd = currentGradientEnd;
        transitionStartStart = color;
        transitionStartEnd = currentGradientEnd;
        long now = System.currentTimeMillis();
        breathFactor = 0.95f + 0.05f * (float) Math.sin(2 * Math.PI * now / BREATH_PERIOD_MS);
        return false;
    }
}
