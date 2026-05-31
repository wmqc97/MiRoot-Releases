package com.wmqc.miroot.charging;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * 充电动画：Canvas 水波液面 + 涟漪（触摸触发、自动扩散、衰减）。
 * 融合 {@link Sensor#TYPE_GYROSCOPE} 与 {@link Sensor#TYPE_ACCELEROMETER}：倾斜时液面坡度、重心偏移与波纹漂移近似物理水面。
 * 传感器与帧回调在 {@link #onAttachedToWindow()} / {@link #onDetachedFromWindow()} 与
 * {@link #onWindowVisibilityChanged(int, int)} 中成对注册、注销，便于生命周期管理。
 */
public class GyroWaterRippleView extends View implements SensorEventListener {

    private static final long RIPPLE_SPAWN_NS = 1_800_000_000L;
    /** 激烈晃动时略拉长间隔，配合平滑后的运动幅度，减轻波纹刷屏感 */
    private static final long RIPPLE_SPAWN_NS_ACTIVE = 720_000_000L;
    /** 帧率节流：背屏充电动画无需满 60fps，降低主线程压力与掉帧风险 */
    private static final long FRAME_INTERVAL_NS = 33_333_333L; // ~30fps
    /** 涟漪用陀螺仪合幅度低通（与液面 wave 解耦，避免传感器毛刺导致波纹抖动） */
    private static final float GYRO_MAG_RIPPLE_LPF = 0.82f;
    /** 滞回：进入「高频生漪」略高、退出略低，避免在阈值附近来回切换 */
    private static final float RIPPLE_HIGH_MOTION_ENTER = 1.95f;
    private static final float RIPPLE_HIGH_MOTION_EXIT = 1.35f;
    private static final float GYRO_LPF = 0.72f;
    private static final float ACCEL_LPF = 0.88f;
    private static final float LIQUID_DECAY = 0.988f;
    /** 物理步长基准：以 60fps（16.667ms）为一帧做时间归一化，避免帧率波动引起观感抖动 */
    private static final float BASE_DT_SEC = 1f / 60f;
    /** 重力估计对液面目标偏移的跟随强度 */
    private static final float GRAVITY_SETTLE = 0.06f;
    private static final int MAX_RIPPLES = 96;
    /** 1D 液面晃荡网格（简化 slosh） */
    private static final int SLOSH_CELLS = 12;
    private static final int RIPPLE_DRAW_RINGS = 3;
    private static final int MAX_FOAM = 36;
    /** 液位明显上升时判定为「涨水中」，抑制液面顶部的炸波与泡沫 */
    private static final float FILL_RISE_DETECT_STEP = 0.0008f;
    private static final long FILL_RISE_ACTIVE_NS = 450_000_000L;
    private static final float FILL_SPLASH_LEVEL_STEP = 0.035f;
    private static final long FILL_SPLASH_MIN_INTERVAL_NS = 550_000_000L;
    private static final long FOAM_SPAWN_NS = 280_000_000L;
    private static final long FOAM_SPAWN_NS_WHILE_RISING = 620_000_000L;
    /** 漂浮物（图片 / 电量）相对屏幕中心的默认右移（px）。 */
    private static final float FLOATING_DEFAULT_OFFSET_X_PX = 180f;
    /** 底部信息 Canvas 绘制时的左右安全区内边距（与 [RearScreenChargingActivity] safe_area_wrapper 一致）。 */
    private float floatingInfoSafeLeftPx;
    private float floatingInfoSafeRightPx;
    /** 底部信息漂浮可活动区域（相对屏高，绘制于水面之上）。 */
    private static final float FLOATING_INFO_ZONE_TOP_FRAC = 0.70f;
    private static final float FLOATING_INFO_ZONE_BOTTOM_FRAC = 0.94f;
    /** 水上大数字：字心高于液面（相对半字高），负值=露出水面。 */
    private static final float FLOATING_BATTERY_SURFACE_DIP_FRAC = -0.01f;
    /** 顶屏浸没时露出水面以上的半字高比例（相对半字高）。 */
    private static final float FLOATING_BATTERY_TOP_EXPOSE_FRAC = 0.50f;
    /** 锚点处液面波峰参与贴面追随的权重（其余随 [frameBaseSurf] 整体液位）。 */
    private static final float FLOATING_BATTERY_LOCAL_WAVE_FOLLOW = 0.40f;
    /** 大数字电量距屏幕顶部的最小留白（px）。 */
    private static final float FLOATING_BATTERY_TOP_SAFE_DP = 1f;
    /** 低液位时底部单行信息区（相对屏高）。 */
    private static final float FLOATING_INFO_ROW_TOP_FRAC = 0.875f;
    private static final int FLOATING_INFO_LAYOUT_NONE = 0;
    private static final int FLOATING_INFO_LAYOUT_ROW = 1;
    private static final int FLOATING_INFO_LAYOUT_STACK = 2;

    public enum FloatingDisplay {
        NONE,
        IMAGE,
        BATTERY
    }

    // --- 气泡粒子常量 ---
    private static final int MAX_BUBBLES = 32;
    private static final long BUBBLE_SPAWN_INTERVAL_NS = 620_000_000L;
    private static final float BUBBLE_MIN_RADIUS_DP = 3f;
    private static final float BUBBLE_MAX_RADIUS_DP = 10f;

    private final Paint waterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint waterDepthPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint surfaceBandFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint surfaceMeniscusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint surfaceSheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backWavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint foamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleShadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mascotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint floatingBatteryPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint floatingInfoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backgroundPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Matrix backgroundMatrix = new Matrix();
    private final Path waterPath = new Path();
    private final Path backWavePath = new Path();
    private final Path surfaceLinePath = new Path();
    private final Path surfaceBandPath = new Path();
    private final float[] sloshH = new float[SLOSH_CELLS];
    private final float[] sloshV = new float[SLOSH_CELLS];
    private final Random random = new Random();

    private float fillLevel = 0.35f;
    /** 实际电量 0–100（漂浮电量文字等；液体颜色与电量无关） */
    private int batteryPercentForTint = 100;
    private FloatingDisplay floatingDisplay = FloatingDisplay.NONE;
    private int waterColorCustom = ChargingWaterColor.DEFAULT_ARGB;
    private int waterOpacityPercent = 100;
    private Bitmap mascotBitmap;
    private boolean mascotBitmapOwned;
    private Bitmap backgroundBitmap;
    private boolean backgroundBitmapOwned;
    /** 底层由 TextureView 播放视频时，本 View 不再铺黑底/图片，仅绘制水体与 UI 层内容。 */
    private boolean sceneVideoBackgroundActive;
    private float mascotOffsetX;
    private float mascotOffsetY;
    /** 液位过高时大数字改在水体下层绘制。 */
    private boolean floatingBatteryDrawUnderWater;
    private float mascotVelX;
    private float mascotVelY;
    private float mascotRotDeg;
    /** 由倾斜重力驱动的锚点横向偏移（像素），慢跟随液面重心。 */
    private float mascotAnchorX;
    private float wavePhase;
    private float gyroSpinImpulse;
    private float smoothGx;
    private float smoothGy;
    private float smoothGz;
    /** 低通加速度（m/s²），近似重力方向 */
    private float gravAx;
    private float gravAy;
    private float gravAz;
    private float liquidShiftX;
    private float liquidShiftY;
    /** 由重力驱动的目标液面偏移（像素），用于倾斜时「水往低处流」 */
    private float gravityTargetShiftX;
    /** 重力 X 低通：仅左右倾（横滚），液面只跟此项 + 陀螺仪 Y 摆动，不跟俯仰/竖直度 */
    private float smoothRollGnX;
    private long lastRippleNs;
    private long lastFillSplashNs;
    private long fillLevelRisingUntilNs;
    private long lastTickNs;
    private long lastTouchRippleMs;
    /** 用于涟漪生成与衰减的平滑陀螺仪合幅度 */
    private float smoothGyroMagForRipples;
    /** 是否处于高频自动生漪模式（滞回，与 {@link #smoothGyroMagForRipples} 配合） */
    private boolean rippleHighMotionMode;

    /** 本帧液面几何（tick/onDraw 共用） */
    private float frameBaseSurf;
    private float frameTiltAlongX;
    private float frameSlopeAmp;
    private float frameAmp;
    private float frameK;
    private float framePhaseOff;

    private SensorManager sensorManager;
    private Sensor gyroscope;
    private Sensor accelerometer;
    private boolean choreographerRunning;
    private boolean sensorsRegistered;

    private int dpStrokeThin;
    private int dpStrokeThick;

    private long lastFrameRenderNs;

    // 渐变 shader 缓存：仅在关键参数变化（尺寸/配色/渐变起点量化后）时重建，避免每帧分配对象导致 GC 抖动
    private LinearGradient cachedShader;
    private int cachedShaderHeight;
    private int cachedShaderTopQ;
    private int cachedShaderTopArgb;
    private int cachedShaderBotArgb;

    /** 底部信息布局：低液位单行 / 高液位逐项竖排。 */
    private int floatingInfoLayoutMode = FLOATING_INFO_LAYOUT_NONE;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!choreographerRunning) {
                return;
            }
            // 节流：减少无谓的 draw 调度；tick 仍用真实帧时间推进，保持动画一致性
            if (lastFrameRenderNs == 0L || frameTimeNanos - lastFrameRenderNs >= FRAME_INTERVAL_NS) {
                lastFrameRenderNs = frameTimeNanos;
                tick(frameTimeNanos);
                invalidate();
            } else {
                tick(frameTimeNanos);
            }
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private static final class Ripple {
        float cx;
        float cy;
        float radius;
        float alpha;
    }

    private static final class Bubble {
        float x;
        float y;
        float radius;
        float baseRadius;
        float speedY;
        float speedX;
        float alpha;
        float wobblePhase;
        float wobblePhase2;
        float wobbleAmp;
        float wobbleFreq2;
        float age;
        float squashPhase;
        boolean popping;
        float popProgress;
        final float[] popDropX = new float[3];
        final float[] popDropY = new float[3];
        final float[] popDropVy = new float[3];
    }

    private static final class Foam {
        float x;
        float y;
        float radius;
        float life;
        float maxLife;
        float driftPhase;
    }

    /** 电量漂浮模式下，底部各参数独立漂浮的标签。 */
    private static final class FloatingInfoLabel {
        String text = "";
        float homeX;
        float homeY;
        float offsetX;
        float offsetY;
        float velX;
        float velY;
        float rotDeg;
    }

    private final List<Ripple> ripples = new ArrayList<>();
    private final List<Bubble> bubbles = new ArrayList<>();
    private final List<Foam> foams = new ArrayList<>();
    private final List<FloatingInfoLabel> floatingInfoLabels = new ArrayList<>();
    private long lastBubbleSpawnNs;
    private long lastFoamSpawnNs;

    public GyroWaterRippleView(Context context) {
        super(context);
        init();
    }

    public GyroWaterRippleView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public GyroWaterRippleView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // 无背景时系统可能标记为不绘制，onDraw 不执行 → 整屏黑
        setWillNotDraw(false);
        ripplePaint.setStyle(Paint.Style.STROKE);
        dpStrokeThin = dp(2.5f);
        dpStrokeThick = dp(3f);
        ripplePaint.setStrokeWidth(dpStrokeThin);
        ripplePaint.setStrokeCap(Paint.Cap.ROUND);
        // 背屏/部分机型上 HARDWARE + Path/Shader 可能整块不合成；软件层更稳
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        bubblePaint.setStyle(Paint.Style.FILL);
        bubbleRimPaint.setStyle(Paint.Style.STROKE);
        bubbleRimPaint.setStrokeCap(Paint.Cap.ROUND);
        bubbleShadePaint.setStyle(Paint.Style.FILL);
        bubbleShadePaint.setAntiAlias(true);
        surfaceBandFillPaint.setStyle(Paint.Style.FILL);
        surfaceBandFillPaint.setAntiAlias(true);
        surfaceMeniscusPaint.setStyle(Paint.Style.STROKE);
        surfaceMeniscusPaint.setStrokeCap(Paint.Cap.ROUND);
        surfaceMeniscusPaint.setStrokeJoin(Paint.Join.ROUND);
        surfaceSheenPaint.setStyle(Paint.Style.STROKE);
        surfaceSheenPaint.setStrokeCap(Paint.Cap.ROUND);
        surfaceSheenPaint.setStrokeJoin(Paint.Join.ROUND);
        backWavePaint.setStyle(Paint.Style.FILL);
        backWavePaint.setAntiAlias(true);
        foamPaint.setStyle(Paint.Style.FILL);
        foamPaint.setAntiAlias(true);
        waterDepthPaint.setStyle(Paint.Style.FILL);
        mascotPaint.setFilterBitmap(true);
        mascotPaint.setAntiAlias(true);
        floatingBatteryPaint.setColor(Color.WHITE);
        floatingBatteryPaint.setTextAlign(Paint.Align.CENTER);
        floatingBatteryPaint.setFakeBoldText(true);
        floatingBatteryPaint.setAntiAlias(true);
        floatingBatteryPaint.setShadowLayer(dp(3f), 0f, dp(2f), 0x80000000);
        applyFloatingBatteryTextColor();
        floatingInfoPaint.setColor(0xDDFFFFFF);
        floatingInfoPaint.setTextAlign(Paint.Align.CENTER);
        floatingInfoPaint.setAntiAlias(true);
        floatingInfoPaint.setShadowLayer(dp(2f), 0f, dp(1f), 0x80000000);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 电量液位 0–1，决定液面基准高度（从下往上）。 */
    public void setFillLevel(float level) {
        float v = Math.max(0f, Math.min(1f, level));
        if (Math.abs(v - fillLevel) > 0.001f) {
            if (v > fillLevel + FILL_SPLASH_LEVEL_STEP) {
                long now = System.nanoTime();
                if (now - lastFillSplashNs >= FILL_SPLASH_MIN_INTERVAL_NS) {
                    lastFillSplashNs = now;
                    post(this::spawnFillSplash);
                }
            }
            if (v > fillLevel + FILL_RISE_DETECT_STEP) {
                fillLevelRisingUntilNs = System.nanoTime() + FILL_RISE_ACTIVE_NS;
            }
            fillLevel = v;
            invalidate();
        }
    }

    private boolean isFillLevelRising() {
        return System.nanoTime() < fillLevelRisingUntilNs;
    }

    public float getFillLevel() {
        return fillLevel;
    }

    /** 设置电量百分比（漂浮电量显示等）。 */
    public void setBatteryPercentForTint(int percent) {
        int p = Math.max(0, Math.min(100, percent));
        if (p != batteryPercentForTint) {
            batteryPercentForTint = p;
            applyFloatingBatteryTextColor();
            invalidate();
        }
    }

    private void applyFloatingBatteryTextColor() {
        floatingBatteryPaint.setColor(
            ChargingBatteryLevel.largePercentTextColorArgb(batteryPercentForTint));
    }

    public void setFloatingDisplay(FloatingDisplay display) {
        if (display == null) {
            display = FloatingDisplay.NONE;
        }
        if (floatingDisplay != display) {
            floatingDisplay = display;
            mascotOffsetX = 0f;
            mascotOffsetY = 0f;
            mascotVelX = 0f;
            mascotVelY = 0f;
            mascotRotDeg = 0f;
            mascotAnchorX = 0f;
            cachedShader = null;
            invalidate();
        }
    }

    /**
     * 底部充电信息：每项独立一行漂浮（Activity 隐藏 XML 信息栏后传入）。
     * 布局在安全区（左摄像头避让 + 右内边距）内垂直排列。
     */
    public void setFloatingInfoLabels(@Nullable List<String> labels) {
        List<String> next = labels != null ? labels : Collections.emptyList();
        while (floatingInfoLabels.size() < next.size()) {
            floatingInfoLabels.add(new FloatingInfoLabel());
        }
        while (floatingInfoLabels.size() > next.size()) {
            floatingInfoLabels.remove(floatingInfoLabels.size() - 1);
        }
        for (int i = 0; i < next.size(); i++) {
            String t = next.get(i);
            floatingInfoLabels.get(i).text = t != null ? t : "--";
        }
        floatingInfoLayoutMode = FLOATING_INFO_LAYOUT_NONE;
        ensureFloatingInfoLayout(getWidth(), getHeight());
        invalidate();
    }

    public void setWaterColorCustom(int argb) {
        int rgb = argb | 0xFF000000;
        if (waterColorCustom != rgb) {
            waterColorCustom = rgb;
            cachedShader = null;
            invalidate();
        }
    }

    /** 自定义水体颜色的透明度（10–100%）。 */
    public void setWaterOpacityPercent(int percent) {
        int p = Math.max(0, Math.min(100, percent));
        if (p != waterOpacityPercent) {
            waterOpacityPercent = p;
            cachedShader = null;
            invalidate();
        }
    }

    /** 与背屏歌词共用字体（MFGeHei / 系统 / 自定义）。 */
    public void setLyricsTypeface(@Nullable android.graphics.Typeface typeface) {
        if (typeface != null) {
            floatingBatteryPaint.setTypeface(typeface);
            floatingBatteryPaint.setFakeBoldText(false);
            floatingInfoPaint.setTypeface(typeface);
        }
        invalidate();
    }

    /** 传入卡通 Bitmap；View 会在 detach 或替换时 recycle（若此前由 View 持有）。 */
    public void setMascotBitmap(@Nullable Bitmap bitmap) {
        releaseMascotBitmap();
        mascotBitmap = bitmap;
        mascotBitmapOwned = bitmap != null;
        invalidate();
    }

    /** 传入场景背景；View 会在 detach 或替换时 recycle（若此前由 View 持有）。 */
    public void setBackgroundBitmap(@Nullable Bitmap bitmap) {
        releaseBackgroundBitmap();
        backgroundBitmap = bitmap;
        backgroundBitmapOwned = bitmap != null;
        invalidate();
    }

    public void setSceneVideoBackgroundActive(boolean active) {
        if (sceneVideoBackgroundActive != active) {
            sceneVideoBackgroundActive = active;
            invalidate();
        }
    }

    private void releaseMascotBitmap() {
        if (mascotBitmapOwned && mascotBitmap != null && !mascotBitmap.isRecycled()) {
            mascotBitmap.recycle();
        }
        mascotBitmap = null;
        mascotBitmapOwned = false;
    }

    private void releaseBackgroundBitmap() {
        if (backgroundBitmapOwned && backgroundBitmap != null && !backgroundBitmap.isRecycled()) {
            backgroundBitmap.recycle();
        }
        backgroundBitmap = null;
        backgroundBitmapOwned = false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        sensorManager = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
        // 首帧时 isShown() 常为 false，post 后再启动，避免水波与涟漪不刷新看起来像黑屏
        post(() -> {
            if (!isAttachedToWindow()) {
                return;
            }
            if (getWindowVisibility() == VISIBLE) {
                registerSensors();
                startChoreographer();
            }
            // 不等首帧 Choreographer 也先画一帧，避免背屏 Surface 就绪慢时长时间纯黑
            invalidate();
        });
    }

    @Override
    protected void onDetachedFromWindow() {
        stopChoreographer();
        unregisterSensors();
        sensorManager = null;
        lastTickNs = 0L;
        lastFrameRenderNs = 0L;
        smoothGyroMagForRipples = 0f;
        rippleHighMotionMode = false;
        bubbles.clear();
        foams.clear();
        resetSlosh();
        cachedShader = null;
        cachedShaderHeight = 0;
        releaseMascotBitmap();
        releaseBackgroundBitmap();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) {
            stopChoreographer();
            unregisterSensors();
            return;
        }
        if (isAttachedToWindow()) {
            registerSensors();
            startChoreographer();
        }
    }

    private void registerSensors() {
        if (sensorsRegistered || sensorManager == null) {
            return;
        }
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (gyroscope != null) {
            sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME);
        }
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        }
        sensorsRegistered = gyroscope != null || accelerometer != null;
        lastRippleNs = System.nanoTime();
        lastBubbleSpawnNs = System.nanoTime();
        lastFoamSpawnNs = System.nanoTime();
    }

    private void unregisterSensors() {
        if (sensorManager != null && sensorsRegistered) {
            sensorManager.unregisterListener(this);
        }
        sensorsRegistered = false;
        gyroscope = null;
        accelerometer = null;
    }

    private void startChoreographer() {
        if (choreographerRunning) {
            return;
        }
        choreographerRunning = true;
        lastRippleNs = System.nanoTime();
        lastFrameRenderNs = 0L;
        // 界面加载后立即冒出一批气泡
        spawnInitialBubbles();
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private void stopChoreographer() {
        choreographerRunning = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            long now = System.currentTimeMillis();
            if (action == MotionEvent.ACTION_DOWN || now - lastTouchRippleMs > 45L) {
                lastTouchRippleMs = now;
                spawnRippleAt(event.getX(), event.getY(), 0.78f, dp(6f));
            }
        }
        return true;
    }

    private void tick(long frameTimeNanos) {
        float w = getWidth();
        float h = getHeight();
        float dt = lastTickNs > 0L
            ? (frameTimeNanos - lastTickNs) / 1_000_000_000f
            : 0.016f;
        lastTickNs = frameTimeNanos;
        dt = Math.min(Math.max(dt, 0.005f), 0.048f);
        float frameScale = dt / BASE_DT_SEC;

        float gyroMag = Math.abs(smoothGx) + Math.abs(smoothGy) + Math.abs(smoothGz);
        smoothGyroMagForRipples =
            smoothGyroMagForRipples * GYRO_MAG_RIPPLE_LPF + gyroMag * (1f - GYRO_MAG_RIPPLE_LPF);
        if (smoothGyroMagForRipples > RIPPLE_HIGH_MOTION_ENTER) {
            rippleHighMotionMode = true;
        } else if (smoothGyroMagForRipples < RIPPLE_HIGH_MOTION_EXIT) {
            rippleHighMotionMode = false;
        }
        wavePhase += (0.055f + gyroSpinImpulse * 0.06f + gyroMag * 0.14f) * frameScale;
        gyroSpinImpulse *= (float) Math.pow(0.9f, frameScale);
        gyroSpinImpulse += smoothGz * 0.22f * frameScale;
        if (gyroSpinImpulse > 3.5f) {
            gyroSpinImpulse = 3.5f;
        } else if (gyroSpinImpulse < -3.5f) {
            gyroSpinImpulse = -3.5f;
        }

        updateGravityTargets(w, h);

        float gLenTick = length3(gravAx, gravAy, gravAz);
        if (gLenTick > 0.5f) {
            float nx = gravAx / gLenTick;
            float a = Math.min(1f, dt * 18f);
            smoothRollGnX += (nx - smoothRollGnX) * a;
        }

        if (w > 0f && h > 0f) {
            liquidShiftX += smoothGy * dt * 420f;
            float settle = 1f - (float) Math.pow(1f - GRAVITY_SETTLE, frameScale);
            liquidShiftX += (gravityTargetShiftX - liquidShiftX) * settle;
            liquidShiftY += (0f - liquidShiftY) * settle;
            float liquidDecay = (float) Math.pow(LIQUID_DECAY, frameScale);
            liquidShiftX *= liquidDecay;
            liquidShiftY *= liquidDecay;
            float maxX = w * 0.22f;
            liquidShiftX = clamp(liquidShiftX, -maxX, maxX);
        }

        long spawnInterval = isFillLevelRising()
            ? RIPPLE_SPAWN_NS * 2L
            : (rippleHighMotionMode ? RIPPLE_SPAWN_NS_ACTIVE : RIPPLE_SPAWN_NS);
        if (frameTimeNanos - lastRippleNs >= spawnInterval) {
            lastRippleNs = frameTimeNanos;
            spawnAmbientRipple();
        }
        if (frameTimeNanos - lastBubbleSpawnNs >= BUBBLE_SPAWN_INTERVAL_NS) {
            lastBubbleSpawnNs = frameTimeNanos;
            if (random.nextFloat() < 0.12f) {
                spawnBubbleCluster();
            } else {
                spawnBubble();
            }
        }
        if (frameTimeNanos - lastFoamSpawnNs >= (isFillLevelRising() ? FOAM_SPAWN_NS_WHILE_RISING : FOAM_SPAWN_NS)) {
            lastFoamSpawnNs = frameTimeNanos;
            if (!isFillLevelRising() || random.nextFloat() < 0.28f) {
                spawnFoamParticle();
            }
        }

        if (w <= 0f || h <= 0f) {
            return;
        }

        updateSurfaceFrame(w, h);
        updateSlosh(dt, frameScale, w, h);

        float gLen = length3(gravAx, gravAy, gravAz);
        float gnx = gLen > 0.5f ? gravAx / gLen : 0f;
        float flowScale = Math.min(w, h) * 0.045f;
        float driftX = smoothGy * dt * 95f + gnx * dt * flowScale;
        float driftY = 0f;

        Iterator<Ripple> it = ripples.iterator();
        while (it.hasNext()) {
            Ripple r = it.next();
            r.cx += driftX;
            r.cy += driftY;
            r.radius += Math.max(w, h) * (0.0042f + smoothGyroMagForRipples * 0.00035f) * frameScale;
            r.alpha -= (0.016f + smoothGyroMagForRipples * 0.0025f) * frameScale;
            if (r.alpha <= 0.02f) {
                it.remove();
            }
        }
        updateBubbles(dt, frameScale, w, h);
        updateFoams(dt, w, h);
        if (floatingDisplay != FloatingDisplay.NONE || !floatingInfoLabels.isEmpty()) {
            updateMascotPhysics(dt, frameScale, w, h);
        }
        if (hasFloatingInfoContent()) {
            ensureFloatingInfoLayout(w, h);
            if (floatingInfoLayoutMode != FLOATING_INFO_LAYOUT_NONE) {
                updateFloatingInfoPhysics(dt, frameScale, w, h);
            }
        }
    }

    private void resetSlosh() {
        for (int i = 0; i < SLOSH_CELLS; i++) {
            sloshH[i] = 0f;
            sloshV[i] = 0f;
        }
    }

    private void updateSurfaceFrame(float w, float h) {
        frameBaseSurf = h * (1f - fillLevel);
        float roll = smoothRollGnX;
        frameTiltAlongX = roll * 1.25f + smoothGy * 0.58f;
        frameTiltAlongX = clamp(frameTiltAlongX, -1.65f, 1.65f);
        frameSlopeAmp = Math.max(dp(12f), h * 0.21f);
        frameAmp = Math.max(dp(6f), h * 0.012f);
        frameK = 0.011f + smoothGy * 0.0065f + liquidShiftX * 0.000015f;
        framePhaseOff = wavePhase + smoothGz * 0.55f + liquidShiftX * 0.0042f;
    }

    private void updateSlosh(float dt, float frameScale, float w, float h) {
        float drive = (-smoothRollGnX * 0.9f + smoothGy * 0.42f) * h * 0.022f;
        sloshV[SLOSH_CELLS / 2] += drive * dt * 28f;
        if (smoothGyroMagForRipples > 1.6f) {
            int cell = (int) clamp(
                SLOSH_CELLS / 2f + smoothGy * 2.5f,
                1f,
                SLOSH_CELLS - 2f
            );
            sloshV[cell] += smoothGyroMagForRipples * h * 0.0016f * dt * 40f;
        }
        sloshV[0] += drive * dt * 10f;
        sloshV[SLOSH_CELLS - 1] += drive * dt * 10f;

        float stiffness = 9.5f;
        float damp = (float) Math.pow(0.91f, frameScale);
        float maxSlosh = h * 0.028f;
        for (int i = 0; i < SLOSH_CELLS; i++) {
            float left = i > 0 ? sloshH[i - 1] : sloshH[i];
            float right = i < SLOSH_CELLS - 1 ? sloshH[i + 1] : sloshH[i];
            float force = (left + right) * 0.5f - sloshH[i];
            sloshV[i] += force * stiffness * dt;
            sloshV[i] *= damp;
            sloshH[i] += sloshV[i] * dt;
            sloshH[i] = clamp(sloshH[i], -maxSlosh, maxSlosh);
        }
    }

    private float sloshOffsetAt(float x, float w) {
        if (w <= 0f) {
            return 0f;
        }
        float t = clamp(x / w, 0f, 1f) * (SLOSH_CELLS - 1);
        int i0 = (int) t;
        int i1 = Math.min(i0 + 1, SLOSH_CELLS - 1);
        float f = t - i0;
        return sloshH[i0] * (1f - f) + sloshH[i1] * f;
    }

    private float rippleBumpAt(float x, float w, float h) {
        if (ripples.isEmpty()) {
            return 0f;
        }
        float sigma = Math.max(w * 0.07f, dp(24f));
        float bumpScale = isFillLevelRising() ? 0.42f : 1f;
        float bump = 0f;
        for (Ripple r : ripples) {
            float dx = x - r.cx;
            float g = (float) Math.exp(-(dx * dx) / (2f * sigma * sigma));
            float wave = (float) Math.sin(r.radius * 0.095f + wavePhase * 0.4f);
            bump -= g * wave * r.alpha * h * 0.011f * bumpScale;
        }
        return bump;
    }

    private float surfaceYAt(float x, float w, float h) {
        return computeSurfaceY(
            x, w, h, frameBaseSurf, frameTiltAlongX, frameSlopeAmp,
            frameAmp, frameK, framePhaseOff, true
        );
    }

    private void updateMascotPhysics(float dt, float frameScale, float w, float h) {
        if (floatingDisplay == FloatingDisplay.IMAGE
            && (mascotBitmap == null || mascotBitmap.isRecycled())) {
            return;
        }
        // 锚点随横滚/液面重心慢移，模拟「水往低处流」时卡通跟着漂。
        float targetAnchorX = gravityTargetShiftX * 0.92f + liquidShiftX * 0.38f;
        float anchorSettle = 1f - (float) Math.pow(1f - GRAVITY_SETTLE * 1.15f, frameScale);
        mascotAnchorX += (targetAnchorX - mascotAnchorX) * anchorSettle;
        float maxAnchorX = w * 0.26f;
        mascotAnchorX = clamp(mascotAnchorX, -maxAnchorX, maxAnchorX);

        mascotVelX += smoothGy * dt * 140f + (-smoothRollGnX) * dt * 90f;
        mascotVelY += smoothGx * dt * 45f;
        float mascotDecay = (float) Math.pow(0.985f, frameScale);
        mascotVelX *= mascotDecay;
        mascotVelY *= mascotDecay;

        mascotOffsetX += mascotVelX * dt;
        mascotOffsetY += mascotVelY * dt;

        float spring = 1f - (float) Math.pow(1f - 0.10f, frameScale);
        mascotOffsetX += (0f - mascotOffsetX) * spring;
        mascotOffsetY += (0f - mascotOffsetY) * spring * 0.55f;

        float maxOffX = w * 0.06f;
        float maxOffY = h * 0.05f;
        mascotOffsetX = clamp(mascotOffsetX, -maxOffX, maxOffX);
        mascotOffsetY = clamp(mascotOffsetY, -maxOffY, maxOffY);

        float targetRot = smoothGy * 9f + smoothGx * 3f + smoothRollGnX * 5f;
        targetRot = clamp(targetRot, -14f, 14f);
        float rotSpring = 1f - (float) Math.pow(1f - 0.12f, frameScale);
        mascotRotDeg += (targetRot - mascotRotDeg) * rotSpring;
    }

    private float computeSurfaceY(
        float x,
        float w,
        float h,
        float baseSurf,
        float tiltAlongX,
        float slopeAmp,
        float amp,
        float k,
        float phaseOff,
        boolean withRippleBump
    ) {
        float tiltY = slopeAmp * tiltAlongX * (x / w - 0.5f) * 2f;
        double ang = x * k + phaseOff + smoothGx * 0.4f * Math.sin(x * 0.02f + wavePhase);
        float y = baseSurf + tiltY
            + amp * (float) Math.sin(ang)
            + amp * 0.32f * (float) Math.sin(ang * 1.65f + smoothGx * 1.8f)
            + amp * 0.28f * (float) Math.sin(ang * 1.7f + smoothGy * 2.2f)
            + sloshOffsetAt(x, w);
        if (withRippleBump) {
            y += rippleBumpAt(x, w, h);
        }
        return y;
    }

    private void buildWaterPaths(
        float w,
        float h,
        float baseSurf,
        float tiltAlongX,
        float slopeAmp,
        float amp,
        float k,
        float phaseOff
    ) {
        int steps = 56;
        waterPath.reset();
        backWavePath.reset();
        waterPath.moveTo(0, h);
        backWavePath.moveTo(0, h);
        surfaceLinePath.reset();
        surfaceBandPath.reset();
        float backAmp = amp * 0.48f;
        float backK = k * 1.35f;
        float backPhase = phaseOff + 1.35f;
        float bandDepth = dp(5f);
        for (int i = 0; i <= steps; i++) {
            float x = w * i / (float) steps;
            float y = computeSurfaceY(x, w, h, baseSurf, tiltAlongX, slopeAmp, amp, k, phaseOff, true);
            float yBack = computeSurfaceY(
                x, w, h, baseSurf, tiltAlongX * 0.92f, slopeAmp * 0.85f,
                backAmp, backK, backPhase, false
            );
            waterPath.lineTo(x, y);
            backWavePath.lineTo(x, yBack);
            if (i == 0) {
                surfaceLinePath.moveTo(x, y);
                surfaceBandPath.moveTo(x, y);
            } else {
                surfaceLinePath.lineTo(x, y);
                surfaceBandPath.lineTo(x, y);
            }
        }
        for (int i = steps; i >= 0; i--) {
            float x = w * i / (float) steps;
            float y = computeSurfaceY(x, w, h, baseSurf, tiltAlongX, slopeAmp, amp, k, phaseOff, true)
                + bandDepth;
            surfaceBandPath.lineTo(x, y);
        }
        surfaceBandPath.close();
        waterPath.lineTo(w, h);
        waterPath.close();
        backWavePath.lineTo(w, h);
        backWavePath.close();
    }

    private void drawMascot(Canvas canvas, float w, float h, float baseSurf) {
        if (mascotBitmap == null || mascotBitmap.isRecycled()) {
            return;
        }
        int bmpW = mascotBitmap.getWidth();
        int bmpH = mascotBitmap.getHeight();
        if (bmpW <= 0 || bmpH <= 0) {
            return;
        }
        float scale = Math.min(w * 0.35f / bmpW, h * 0.28f / bmpH);
        float bob = (float) Math.sin(wavePhase * 0.7f) * dp(4f);
        float cx = w * 0.5f + FLOATING_DEFAULT_OFFSET_X_PX + mascotAnchorX + mascotOffsetX;
        float cy = baseSurf + h * 0.08f + mascotOffsetY + bob;
        canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate(mascotRotDeg);
        canvas.scale(scale, scale);
        canvas.drawBitmap(mascotBitmap, -bmpW * 0.5f, -bmpH * 0.5f, mascotPaint);
        canvas.restore();
    }

    /**
     * 与 XML 底部信息栏相同的安全区：左避让摄像头、右留边距，内容在可见区域内居中/排列。
     */
    public void setFloatingInfoSafeHorizontalInsetsPx(float leftPx, float rightPx) {
        float l = Math.max(0f, leftPx);
        float r = Math.max(0f, rightPx);
        if (l == floatingInfoSafeLeftPx && r == floatingInfoSafeRightPx) {
            return;
        }
        floatingInfoSafeLeftPx = l;
        floatingInfoSafeRightPx = r;
        floatingInfoLayoutMode = FLOATING_INFO_LAYOUT_NONE;
        ensureFloatingInfoLayout(getWidth(), getHeight());
        invalidate();
    }

    private float floatingInfoContentLeft(float w) {
        return floatingInfoSafeLeftPx;
    }

    private float floatingInfoContentRight(float w) {
        return Math.max(floatingInfoSafeLeftPx, w - floatingInfoSafeRightPx);
    }

    private float floatingInfoSafeCenterX(float w) {
        float left = floatingInfoContentLeft(w);
        float right = floatingInfoContentRight(w);
        return left + (right - left) * 0.5f;
    }

    private boolean hasFloatingInfoContent() {
        return floatingDisplay == FloatingDisplay.BATTERY && !floatingInfoLabels.isEmpty();
    }

    /**
     * 高液位、大数字已离开底部信息区：底部多行；否则单行（均绘制于水面之上）。
     */
    private boolean shouldUseFloatingInfoStackLayout(float w, float h) {
        if (!hasFloatingInfoContent()) {
            return false;
        }
        float zoneTop = h * FLOATING_INFO_ROW_TOP_FRAC;
        return computeFloatingBatteryBottom(w, h) + dp(14f) <= zoneTop;
    }

    private float floatingBatteryTextSize(float w, float h) {
        return Math.min(w * 0.23f, h * 0.15f);
    }

    /**
     * 贴液面追随 → 触顶钳位 → 液面没过字顶时随水面动态下压（约半字露出）。
     * 没入深度随液位连续变化；下半入水时改水体下层绘制。
     */
    private float computeFloatingBatteryCenterY(float w, float h) {
        float textSize = floatingBatteryTextSize(w, h);
        float textHalfH = textSize * 0.55f;
        float bob = (float) Math.sin(wavePhase * 0.7f) * dp(4f);
        float cx = w * 0.5f + FLOATING_DEFAULT_OFFSET_X_PX + mascotAnchorX + mascotOffsetX;
        float surfaceAtX = surfaceYAt(cx, w, h);
        float infoGuardTop = h * FLOATING_INFO_ROW_TOP_FRAC - textHalfH - dp(10f);
        float minTop = dp(FLOATING_BATTERY_TOP_SAFE_DP);
        float minCenterY = minTop + textHalfH;

        float localWave = surfaceAtX - frameBaseSurf;
        float idealCy = frameBaseSurf + textHalfH * FLOATING_BATTERY_SURFACE_DIP_FRAC
            + localWave * FLOATING_BATTERY_LOCAL_WAVE_FOLLOW
            + mascotOffsetY + bob;

        // 触顶：字顶不低于安全边距，不再随液面继续上移
        float cy = Math.max(idealCy, minCenterY);

        // 液面高过字顶：随水面动态下压，保留约半字露出；100% 满水时整段没入
        float textTop = cy - textHalfH;
        if (surfaceAtX < textTop - dp(1f)) {
            float exposeAboveSurface = textHalfH * FLOATING_BATTERY_TOP_EXPOSE_FRAC;
            float targetTop = Math.max(minTop, surfaceAtX + exposeAboveSurface);
            cy = targetTop + textHalfH;
        }

        cy = Math.min(cy, infoGuardTop);
        floatingBatteryDrawUnderWater = cy + textHalfH > surfaceAtX + dp(2f);
        return cy;
    }

    private float computeFloatingBatteryBottom(float w, float h) {
        return computeFloatingBatteryCenterY(w, h) + floatingBatteryTextSize(w, h) * 0.55f;
    }

    private int resolveFloatingInfoLayoutMode(float w, float h) {
        if (!hasFloatingInfoContent() || w <= 0f || h <= 0f) {
            return FLOATING_INFO_LAYOUT_NONE;
        }
        return shouldUseFloatingInfoStackLayout(w, h)
            ? FLOATING_INFO_LAYOUT_STACK
            : FLOATING_INFO_LAYOUT_ROW;
    }

    private void resetFloatingInfoMotion() {
        for (FloatingInfoLabel lb : floatingInfoLabels) {
            lb.offsetX = 0f;
            lb.offsetY = 0f;
            lb.velX = 0f;
            lb.velY = 0f;
            lb.rotDeg = 0f;
        }
    }

    private void ensureFloatingInfoLayout(float w, float h) {
        int mode = resolveFloatingInfoLayoutMode(w, h);
        if (mode == floatingInfoLayoutMode) {
            return;
        }
        floatingInfoLayoutMode = mode;
        if (mode == FLOATING_INFO_LAYOUT_STACK) {
            layoutFloatingInfoHomesStack(w, h);
        } else if (mode == FLOATING_INFO_LAYOUT_ROW) {
            layoutFloatingInfoHomesRow(w, h);
        }
        resetFloatingInfoMotion();
    }

    private void layoutFloatingInfoHomesStack(float w, float h) {
        int n = floatingInfoLabels.size();
        if (n <= 0) {
            return;
        }
        float cx = floatingInfoSafeCenterX(w);
        float lineStep = Math.max(dp(20f), h * 0.048f);
        float bottomY = h * FLOATING_INFO_ZONE_BOTTOM_FRAC;
        float startY = bottomY - (n - 1) * lineStep;
        for (int i = 0; i < n; i++) {
            FloatingInfoLabel lb = floatingInfoLabels.get(i);
            lb.homeX = cx;
            lb.homeY = startY + i * lineStep;
        }
    }

    /** 低液位：底部安全区内单行均分，与 XML 信息栏类似但 Canvas 绘制。 */
    private void layoutFloatingInfoHomesRow(float w, float h) {
        int n = floatingInfoLabels.size();
        if (n <= 0) {
            return;
        }
        float zoneLeft = floatingInfoContentLeft(w) + dp(8f);
        float zoneRight = floatingInfoContentRight(w) - dp(10f);
        float homeY = h * FLOATING_INFO_ZONE_BOTTOM_FRAC - dp(8f);
        if (n == 1) {
            floatingInfoLabels.get(0).homeX = (zoneLeft + zoneRight) * 0.5f;
            floatingInfoLabels.get(0).homeY = homeY;
            return;
        }
        float spread = zoneRight - zoneLeft;
        for (int i = 0; i < n; i++) {
            float t = i / (float) (n - 1);
            FloatingInfoLabel lb = floatingInfoLabels.get(i);
            lb.homeX = zoneLeft + spread * t;
            lb.homeY = homeY;
        }
    }

    private void updateFloatingInfoPhysics(float dt, float frameScale, float w, float h) {
        boolean rowLayout = floatingInfoLayoutMode == FLOATING_INFO_LAYOUT_ROW;
        float zoneTop = h * (rowLayout ? FLOATING_INFO_ROW_TOP_FRAC : FLOATING_INFO_ZONE_TOP_FRAC);
        float zoneBottom = h * FLOATING_INFO_ZONE_BOTTOM_FRAC;
        float zoneLeft = floatingInfoContentLeft(w) + dp(6f);
        float zoneRight = floatingInfoContentRight(w) - dp(10f);
        float textSize = rowLayout
            ? Math.max(dp(10f), Math.min(w * 0.028f, h * 0.024f))
            : Math.max(dp(11f), Math.min(w * 0.032f, h * 0.028f));
        floatingInfoPaint.setTextSize(textSize);

        float decay = (float) Math.pow(0.988f, frameScale);
        float spring = 1f - (float) Math.pow(1f - 0.11f, frameScale);
        float maxOffX = w * (rowLayout ? 0.03f : 0.04f);
        float maxOffY = h * (rowLayout ? 0.012f : 0.028f);

        float anchorX = mascotAnchorX + mascotOffsetX;

        for (FloatingInfoLabel lb : floatingInfoLabels) {
            lb.velX += smoothGy * dt * 115f + (-smoothRollGnX) * dt * 72f;
            lb.velY += smoothGx * dt * (rowLayout ? 22f : 38f);
            lb.velX *= decay;
            lb.velY *= decay;
            lb.offsetX += lb.velX * dt;
            lb.offsetY += lb.velY * dt;
            lb.offsetX += (0f - lb.offsetX) * spring;
            lb.offsetY += (0f - lb.offsetY) * spring * 0.6f;
            lb.offsetX = clamp(lb.offsetX, -maxOffX, maxOffX);
            lb.offsetY = clamp(lb.offsetY, -maxOffY, maxOffY);

            float drawX = lb.homeX + anchorX + lb.offsetX;
            float drawY = lb.homeY + lb.offsetY;
            float halfTextW = lb.text != null && !lb.text.isEmpty()
                    ? floatingInfoPaint.measureText(lb.text) * 0.5f
                    : dp(24f);
            float minCenterX = zoneLeft + halfTextW;
            if (drawX < minCenterX) {
                lb.offsetX += (minCenterX - drawX) * 0.35f;
            } else if (drawX > zoneRight - halfTextW) {
                lb.offsetX -= (drawX - (zoneRight - halfTextW)) * 0.35f;
            }
            if (drawY < zoneTop) {
                lb.offsetY += (zoneTop - drawY) * 0.35f;
            } else if (drawY > zoneBottom) {
                lb.offsetY -= (drawY - zoneBottom) * 0.35f;
            }

            float targetRot = smoothGy * 5f + smoothGx * 2f + smoothRollGnX * 3f;
            float rotMax = rowLayout ? 4f : 8f;
            targetRot = clamp(targetRot, -rotMax, rotMax);
            float rotSpring = 1f - (float) Math.pow(1f - 0.14f, frameScale);
            lb.rotDeg += (targetRot - lb.rotDeg) * rotSpring;
        }
    }

    private void drawFloatingInfoLabels(Canvas canvas, float w, float h) {
        if (!hasFloatingInfoContent()) {
            return;
        }
        ensureFloatingInfoLayout(w, h);
        if (floatingInfoLayoutMode == FLOATING_INFO_LAYOUT_NONE) {
            return;
        }
        boolean rowLayout = floatingInfoLayoutMode == FLOATING_INFO_LAYOUT_ROW;
        float textSize = rowLayout
            ? Math.max(dp(10f), Math.min(w * 0.028f, h * 0.024f))
            : Math.max(dp(11f), Math.min(w * 0.032f, h * 0.028f));
        floatingInfoPaint.setTextSize(textSize);
        Paint.FontMetrics fm = floatingInfoPaint.getFontMetrics();
        float textOffsetY = -(fm.ascent + fm.descent) * 0.5f;
        float anchorX = mascotAnchorX + mascotOffsetX;
        int labelIndex = 0;
        Float prevDrawX = null;
        for (FloatingInfoLabel lb : floatingInfoLabels) {
            if (lb.text == null || lb.text.isEmpty()) {
                continue;
            }
            float bob = (float) Math.sin(wavePhase * 0.55f + labelIndex * 0.65f) * dp(rowLayout ? 1.2f : 2f);
            float cx = lb.homeX + anchorX + lb.offsetX;
            float cy = lb.homeY + lb.offsetY + bob;
            if (rowLayout && prevDrawX != null) {
                float midX = (prevDrawX + cx) * 0.5f;
                floatingInfoPaint.setStyle(Paint.Style.STROKE);
                floatingInfoPaint.setStrokeWidth(dp(1f));
                floatingInfoPaint.setAlpha(0x55);
                canvas.drawLine(midX, cy - dp(6f), midX, cy + dp(6f), floatingInfoPaint);
                floatingInfoPaint.setStyle(Paint.Style.FILL);
                floatingInfoPaint.setAlpha(0xDD);
            }
            prevDrawX = cx;
            labelIndex++;
            canvas.save();
            canvas.translate(cx, cy);
            if (!rowLayout) {
                canvas.rotate(lb.rotDeg);
            }
            canvas.drawText(lb.text, 0f, textOffsetY, floatingInfoPaint);
            canvas.restore();
        }
    }

    private void drawFloatingBattery(Canvas canvas, float w, float h, float baseSurf) {
        String text = batteryPercentForTint + "%";
        float textSize = floatingBatteryTextSize(w, h);
        floatingBatteryPaint.setTextSize(textSize);
        float cx = w * 0.5f + FLOATING_DEFAULT_OFFSET_X_PX + mascotAnchorX + mascotOffsetX;
        float cy = computeFloatingBatteryCenterY(w, h);
        Paint.FontMetrics fm = floatingBatteryPaint.getFontMetrics();
        float textOffsetY = -(fm.ascent + fm.descent) * 0.5f;
        canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate(mascotRotDeg);
        canvas.drawText(text, 0f, textOffsetY, floatingBatteryPaint);
        canvas.restore();
    }

    private void drawSceneBackground(Canvas canvas, int w, int h) {
        if (sceneVideoBackgroundActive) {
            canvas.drawColor(isSolidWater() ? 0x55000000 : 0x33000000);
            return;
        }
        canvas.drawColor(0xFF000000);
        if (backgroundBitmap == null || backgroundBitmap.isRecycled()) {
            return;
        }
        int bmpW = backgroundBitmap.getWidth();
        int bmpH = backgroundBitmap.getHeight();
        if (bmpW <= 0 || bmpH <= 0) {
            return;
        }
        float scale = Math.max((float) w / bmpW, (float) h / bmpH);
        float dx = (w - bmpW * scale) * 0.5f;
        float dy = (h - bmpH * scale) * 0.5f;
        backgroundMatrix.reset();
        backgroundMatrix.postScale(scale, scale);
        backgroundMatrix.postTranslate(dx, dy);
        canvas.drawBitmap(backgroundBitmap, backgroundMatrix, backgroundPaint);
        int scrim = isSolidWater() ? 0xA8000000 : 0x55000000;
        canvas.drawColor(scrim);
    }

    private boolean isSolidWater() {
        return ChargingWaterColor.isSolidOpacity(waterOpacityPercent);
    }

    private int[] resolveWaterGradientColors() {
        return ChargingWaterColor.gradientColors(waterColorCustom, waterOpacityPercent);
    }

    private int resolveWaterBaseArgb() {
        return waterColorCustom;
    }

    private void applySurfacePaints() {
        int base = resolveWaterBaseArgb();
        surfaceBandFillPaint.setColor(
            ChargingWaterColor.surfaceBandFillArgb(base, waterOpacityPercent)
        );
        surfaceMeniscusPaint.setColor(
            ChargingWaterColor.surfaceMeniscusStrokeArgb(base, waterOpacityPercent)
        );
        surfaceMeniscusPaint.setStrokeWidth(dp(1.6f));
        surfaceSheenPaint.setColor(
            ChargingWaterColor.surfaceSheenStrokeArgb(base, waterOpacityPercent)
        );
        surfaceSheenPaint.setStrokeWidth(dp(0.9f));
    }

    private int[] resolveRippleRgb() {
        return ChargingWaterColor.rippleRgb(waterColorCustom);
    }

    private void updateGravityTargets(float w, float h) {
        if (w <= 0f || h <= 0f) {
            return;
        }
        float gLen = length3(gravAx, gravAy, gravAz);
        if (gLen < 0.5f) {
            return;
        }
        float nx = gravAx / gLen;
        gravityTargetShiftX = -nx * w * 0.34f;
    }

    private static float length3(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (Math.min(v, hi));
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (Math.min(v, 255));
    }

    private void spawnFillSplash() {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) {
            return;
        }
        boolean rising = isFillLevelRising();
        float baseSurf = h * (1f - fillLevel);
        int rippleCount = rising ? 1 : 2;
        for (int i = 0; i < rippleCount; i++) {
            float cx = w * (0.22f + random.nextFloat() * 0.56f);
            float cy = baseSurf + random.nextFloat() * dp(6f) - dp(2f);
            float alpha = rising ? 0.38f + random.nextFloat() * 0.08f : 0.48f + random.nextFloat() * 0.10f;
            spawnRippleAt(cx, cy, alpha, dp(3f + random.nextFloat() * 2.5f));
        }
        if (!rising) {
            spawnFoamAt(w * (0.2f + random.nextFloat() * 0.6f), baseSurf, true);
        }
        sloshV[SLOSH_CELLS / 2] += h * (rising ? 0.0025f : 0.004f);
    }

    private void spawnFoamParticle() {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f || foams.size() >= MAX_FOAM) {
            return;
        }
        float x = w * (0.12f + random.nextFloat() * 0.76f);
        spawnFoamAt(x, frameBaseSurf, false);
    }

    private void spawnFoamAt(float x, float baseSurf, boolean burst) {
        if (foams.size() >= MAX_FOAM) {
            return;
        }
        Foam f = new Foam();
        f.x = x;
        f.y = baseSurf + (float) Math.sin(x * 0.03f + wavePhase) * dp(2f) + dp(1f);
        f.radius = dp(burst ? 2f + random.nextFloat() * 3f : 1.5f + random.nextFloat() * 2.5f);
        f.maxLife = burst ? 0.55f + random.nextFloat() * 0.35f : 0.8f + random.nextFloat() * 0.9f;
        f.life = f.maxLife;
        f.driftPhase = random.nextFloat() * 6.2832f;
        foams.add(f);
    }

    private void updateFoams(float dt, float w, float h) {
        if (foams.isEmpty()) {
            return;
        }
        float gLen = length3(gravAx, gravAy, gravAz);
        float gnx = gLen > 0.5f ? gravAx / gLen : 0f;
        float driftX = smoothGy * dt * 18f + gnx * dt * w * 0.012f;
        Iterator<Foam> it = foams.iterator();
        while (it.hasNext()) {
            Foam f = it.next();
            f.life -= dt;
            f.x += driftX + (float) Math.sin(f.driftPhase + wavePhase) * dt * dp(3f);
            f.y = surfaceYAt(f.x, w, h) + dp(1f);
            f.driftPhase += dt * 2.2f;
            if (f.life <= 0f) {
                it.remove();
            }
        }
    }

    private void drawFoams(Canvas canvas) {
        for (Foam f : foams) {
            float t = f.life / f.maxLife;
            int a = clamp255((int) (t * 140f));
            foamPaint.setColor(Color.argb(a, 240, 250, 255));
            canvas.drawCircle(f.x, f.y, f.radius, foamPaint);
        }
    }

    private void spawnAmbientRipple() {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) {
            return;
        }
        float marginX = w * 0.18f;
        float biasX = liquidShiftX * 0.35f + smoothGy * w * 0.04f;
        float cx = marginX + random.nextFloat() * (w - 2f * marginX) + biasX;
        cx = clamp(cx, marginX * 0.5f, w - marginX * 0.5f);

        // 环境涟漪生成在液面以下，避免涨水时顶部叠满同心波
        float surf = h * (1f - fillLevel) + dp(isFillLevelRising() ? 36f : 24f);
        float bottom = h * 0.9f;
        if (surf >= bottom) {
            return;
        }
        float cy = surf + random.nextFloat() * (bottom - surf);
        spawnRippleAt(
            cx,
            cy,
            0.46f + Math.min(0.16f, smoothGyroMagForRipples * 0.06f),
            dp(7f)
        );
    }

    private void spawnRippleAt(float cx, float cy, float alpha, float startRadius) {
        while (ripples.size() >= MAX_RIPPLES) {
            ripples.remove(0);
        }
        Ripple r = new Ripple();
        r.cx = cx;
        r.cy = cy;
        r.radius = startRadius;
        r.alpha = alpha;
        ripples.add(r);
    }

    private void spawnInitialBubbles() {
        // 布局还未测量时先标记，等 onSizeChanged 或 tick 自动补
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        for (int i = 0; i < 3; i++) {
            spawnBubble();
        }
    }

    private void spawnBubbleCluster() {
        int count = 2 + random.nextInt(1);
        for (int i = 0; i < count; i++) {
            spawnBubble(true);
        }
    }

    private void spawnBubble() {
        spawnBubble(false);
    }

    private void spawnBubble(boolean cluster) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f || bubbles.size() >= MAX_BUBBLES) {
            return;
        }
        float baseSurf = h * (1f - fillLevel);
        float waterDepth = h - baseSurf;
        if (waterDepth <= dp(24f)) {
            return;
        }
        Bubble b = new Bubble();
        float margin = w * 0.12f;
        float clusterJitter = cluster ? dp(8f) : 0f;
        b.x = margin + random.nextFloat() * (w - 2f * margin)
            + (random.nextFloat() - 0.5f) * clusterJitter;
        // 偏向水体中下段生成，小泡更多
        float depthT = (float) Math.pow(random.nextFloat(), 1.35f);
        b.y = baseSurf + dp(12f) + depthT * Math.max(dp(24f), waterDepth * 0.78f);
        float sizeT = (float) Math.pow(random.nextFloat(), 1.55f);
        float radiusDp = BUBBLE_MIN_RADIUS_DP
            + sizeT * (BUBBLE_MAX_RADIUS_DP - BUBBLE_MIN_RADIUS_DP);
        b.baseRadius = dp(radiusDp);
        b.radius = b.baseRadius;
        // 大气泡升速更慢（近似 Stokes）
        b.speedY = dp(16f / (0.55f + radiusDp * 0.42f));
        b.speedX = (random.nextFloat() - 0.5f) * dp(3.5f);
        b.alpha = 0f;
        b.age = random.nextFloat() * 0.6f;
        b.wobblePhase = random.nextFloat() * 6.2832f;
        b.wobblePhase2 = random.nextFloat() * 6.2832f;
        b.wobbleAmp = dp(2.5f + random.nextFloat() * 5f);
        b.wobbleFreq2 = 1.4f + random.nextFloat() * 2.2f;
        b.squashPhase = random.nextFloat() * 6.2832f;
        b.popping = false;
        b.popProgress = 0f;
        bubbles.add(b);
    }

    private void beginBubblePop(Bubble b, float surfaceY, float w, float h) {
        b.popping = true;
        b.popProgress = 0f;
        b.y = surfaceY + dp(0.5f);
        for (int i = 0; i < b.popDropX.length; i++) {
            b.popDropX[i] = b.x + (random.nextFloat() - 0.5f) * b.radius * 0.6f;
            b.popDropY[i] = b.y - b.radius * 0.2f;
            b.popDropVy[i] = dp(28f + random.nextFloat() * 36f);
        }
        float cx = clamp(b.x, b.radius, w - b.radius);
        spawnRippleAt(cx, surfaceY, 0.42f, Math.max(dp(3f), b.radius * 0.35f));
    }

    private void updateBubbles(float dt, float frameScale, float w, float h) {
        if (w <= 0f || h <= 0f || bubbles.isEmpty()) {
            return;
        }
        float gLen = length3(gravAx, gravAy, gravAz);
        float gnx = gLen > 0.5f ? gravAx / gLen : 0f;
        float flowScale = Math.min(w, h) * 0.02f;
        float driftX = smoothGy * dt * 30f + gnx * dt * flowScale + liquidShiftX * dt * 0.08f;
        Iterator<Bubble> it = bubbles.iterator();
        while (it.hasNext()) {
            Bubble b = it.next();
            if (b.popping) {
                b.popProgress += dt * 4.2f;
                float expand = 1f + b.popProgress * 0.85f;
                b.radius = b.baseRadius * expand;
                b.alpha = Math.max(0f, 1f - b.popProgress * 1.15f);
                for (int i = 0; i < b.popDropX.length; i++) {
                    b.popDropY[i] -= b.popDropVy[i] * dt;
                    b.popDropVy[i] *= (float) Math.pow(0.92f, frameScale);
                }
                if (b.popProgress >= 1f) {
                    it.remove();
                }
                continue;
            }
            b.age += dt;
            float surfaceY = surfaceYAt(b.x, w, h);
            float distToSurf = b.y - b.radius - surfaceY;
            // 近液面减速 + 轻微脉动
            float surfaceDrag = clamp(distToSurf / dp(24f), 0.28f, 1f);
            float riseEase = Math.min(1f, b.age * 1.6f);
            b.y -= b.speedY * dt * surfaceDrag * riseEase;
            b.wobblePhase += dt * (1.8f + b.baseRadius * 0.004f);
            b.wobblePhase2 += dt * b.wobbleFreq2;
            b.squashPhase += dt * 2.6f;
            b.x += b.speedX * dt + driftX
                + (float) Math.sin(b.wobblePhase) * b.wobbleAmp * dt
                + (float) Math.sin(b.wobblePhase2 + 0.8f) * b.wobbleAmp * 0.42f * dt;
            float pulse = 1f + 0.05f * (float) Math.sin(b.age * 5.2f + b.wobblePhase);
            b.radius = b.baseRadius * pulse;
            float waterDepth = Math.max(h - frameBaseSurf, dp(1f));
            float depthNorm = clamp((b.y - frameBaseSurf) / waterDepth, 0f, 1f);
            float targetAlpha = 0.42f + depthNorm * 0.48f;
            if (b.alpha < targetAlpha) {
                b.alpha = Math.min(targetAlpha, b.alpha + dt * 2.2f);
            } else {
                b.alpha += (targetAlpha - b.alpha) * Math.min(1f, dt * 3f);
            }
            if (distToSurf <= dp(3f)) {
                beginBubblePop(b, surfaceY, w, h);
                continue;
            }
            if (b.y < h * 0.04f || b.alpha <= 0.02f) {
                it.remove();
            }
        }
    }

    private void drawBubbles(Canvas canvas) {
        if (bubbles.isEmpty()) {
            return;
        }
        int[] rippleRgb = resolveRippleRgb();
        for (Bubble b : bubbles) {
            if (b.popping) {
                drawBubblePop(canvas, b, rippleRgb);
                continue;
            }
            drawBubbleBody(canvas, b);
        }
    }

    private void drawBubbleBody(Canvas canvas, Bubble b) {
        int a = clamp255((int) (b.alpha * 255f));
        if (a <= 4) {
            return;
        }
        float squash = 1f + 0.08f * (float) Math.sin(b.squashPhase);
        float rx = b.radius * (1f + squash * 0.06f);
        float ry = b.radius * (1f - squash * 0.05f);
        float cx = b.x;
        float cy = b.y;

        bubblePaint.setStyle(Paint.Style.FILL);
        bubblePaint.setColor(Color.argb((int) (a * 0.28f), 150, 205, 230));
        canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, bubblePaint);
        bubblePaint.setColor(Color.argb((int) (a * 0.42f), 195, 230, 245));
        canvas.drawOval(cx - rx * 0.78f, cy - ry * 0.78f, cx + rx * 0.78f, cy + ry * 0.78f, bubblePaint);
        bubblePaint.setColor(Color.argb((int) (a * 0.22f), 230, 248, 255));
        canvas.drawOval(cx - rx * 0.42f, cy - ry * 0.42f, cx + rx * 0.42f, cy + ry * 0.42f, bubblePaint);

        bubbleRimPaint.setStrokeWidth(Math.max(1f, b.radius * 0.09f));
        bubbleRimPaint.setColor(Color.argb((int) (a * 0.92f), 245, 252, 255));
        canvas.drawOval(cx - rx * 0.94f, cy - ry * 0.94f, cx + rx * 0.94f, cy + ry * 0.94f, bubbleRimPaint);

        bubbleShadePaint.setColor(Color.argb((int) (a * 0.18f), 40, 90, 120));
        canvas.drawArc(
            cx - rx * 0.72f,
            cy - ry * 0.2f,
            cx + rx * 0.72f,
            cy + ry * 0.95f,
            10f,
            160f,
            true,
            bubbleShadePaint
        );

        if (b.radius > dp(3.5f)) {
            bubblePaint.setColor(Color.argb((int) (a * 0.75f), 255, 255, 255));
            canvas.drawOval(
                cx - rx * 0.38f,
                cy - ry * 0.62f,
                cx - rx * 0.04f,
                cy - ry * 0.18f,
                bubblePaint
            );
            if (b.radius > dp(6f)) {
                bubblePaint.setColor(Color.argb((int) (a * 0.35f), 255, 255, 255));
                canvas.drawCircle(cx + rx * 0.18f, cy - ry * 0.08f, b.radius * 0.11f, bubblePaint);
            }
        }
    }

    private void drawBubblePop(Canvas canvas, Bubble b, int[] rippleRgb) {
        float t = b.popProgress;
        int rr = rippleRgb[0];
        int rg = rippleRgb[1];
        int rb = rippleRgb[2];
        ripplePaint.setStyle(Paint.Style.STROKE);
        for (int ring = 0; ring < 2; ring++) {
            float ringT = clamp(t - ring * 0.18f, 0f, 1f);
            if (ringT <= 0f) {
                continue;
            }
            int ringA = clamp255((int) ((1f - ringT) * 100f));
            float rad = b.baseRadius * (1f + ringT * (1.1f + ring * 0.35f));
            ripplePaint.setStrokeWidth(ring == 0 ? dpStrokeThin : dp(1.5f));
            ripplePaint.setColor(Color.argb(ringA, rr, rg, rb));
            canvas.drawCircle(b.x, b.y, rad, ripplePaint);
        }
        int bodyA = clamp255((int) ((1f - t * 1.2f) * 140f));
        if (bodyA > 0) {
            bubblePaint.setStyle(Paint.Style.FILL);
            bubblePaint.setColor(Color.argb(bodyA, 235, 248, 255));
            canvas.drawCircle(b.x, b.y, b.baseRadius * (1f - t * 0.35f), bubblePaint);
        }
        for (int i = 0; i < b.popDropX.length; i++) {
            int dropA = clamp255((int) ((1f - t) * 160f));
            bubblePaint.setColor(Color.argb(dropA, 230, 245, 255));
            canvas.drawCircle(b.popDropX[i], b.popDropY[i], Math.max(dp(1f), b.baseRadius * 0.14f), bubblePaint);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        int type = event.sensor.getType();
        if (type == Sensor.TYPE_GYROSCOPE) {
            float nx = event.values[0];
            float ny = event.values[1];
            float nz = event.values[2];
            smoothGx = smoothGx * GYRO_LPF + nx * (1f - GYRO_LPF);
            smoothGy = smoothGy * GYRO_LPF + ny * (1f - GYRO_LPF);
            smoothGz = smoothGz * GYRO_LPF + nz * (1f - GYRO_LPF);
        } else if (type == Sensor.TYPE_ACCELEROMETER) {
            float ax = event.values[0];
            float ay = event.values[1];
            float az = event.values[2];
            gravAx = gravAx * ACCEL_LPF + ax * (1f - ACCEL_LPF);
            gravAy = gravAy * ACCEL_LPF + ay * (1f - ACCEL_LPF);
            gravAz = gravAz * ACCEL_LPF + az * (1f - ACCEL_LPF);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        drawSceneBackground(canvas, w, h);

        updateSurfaceFrame(w, h);

        float baseSurf = frameBaseSurf;
        float tiltAlongX = frameTiltAlongX;
        float slopeAmp = frameSlopeAmp;
        float amp = frameAmp;
        float k = frameK;
        float phaseOff = framePhaseOff;

        buildWaterPaths(w, h, baseSurf, tiltAlongX, slopeAmp, amp, k, phaseOff);

        if (floatingDisplay == FloatingDisplay.IMAGE) {
            drawMascot(canvas, w, h, baseSurf);
        }
        if (floatingDisplay == FloatingDisplay.BATTERY) {
            computeFloatingBatteryCenterY(w, h);
            if (floatingBatteryDrawUnderWater) {
                drawFloatingBattery(canvas, w, h, baseSurf);
            }
        }

        int[] gradColors = resolveWaterGradientColors();
        int gradTopArgb = gradColors[0];
        int gradBotArgb = gradColors[1];

        int backWaveColor = (gradTopArgb & 0x00FFFFFF) | 0x44000000;
        backWavePaint.setColor(backWaveColor);
        canvas.drawPath(backWavePath, backWavePaint);

        float gradTop = baseSurf;
        int gradTopQ = Math.round(gradTop / 4f) * 4;
        if (cachedShader == null
            || cachedShaderHeight != h
            || cachedShaderTopQ != gradTopQ
            || cachedShaderTopArgb != gradTopArgb
            || cachedShaderBotArgb != gradBotArgb) {
            cachedShaderHeight = h;
            cachedShaderTopQ = gradTopQ;
            cachedShaderTopArgb = gradTopArgb;
            cachedShaderBotArgb = gradBotArgb;
            cachedShader = new LinearGradient(
                0, gradTopQ, 0, h,
                gradTopArgb,
                gradBotArgb,
                Shader.TileMode.CLAMP
            );
        }
        waterPaint.setShader(cachedShader);
        canvas.drawPath(waterPath, waterPaint);
        waterPaint.setShader(null);

        if (isSolidWater()) {
            waterDepthPaint.setColor(
                ChargingWaterColor.depthOverlayArgb(waterColorCustom, waterOpacityPercent)
            );
            canvas.drawPath(waterPath, waterDepthPaint);
        }

        if (floatingDisplay == FloatingDisplay.BATTERY && !floatingBatteryDrawUnderWater) {
            drawFloatingBattery(canvas, w, h, baseSurf);
        }

        applySurfacePaints();
        canvas.drawPath(surfaceBandPath, surfaceBandFillPaint);
        canvas.drawPath(surfaceLinePath, surfaceMeniscusPaint);
        canvas.save();
        canvas.translate(0f, -dp(1f));
        canvas.drawPath(surfaceLinePath, surfaceSheenPaint);
        canvas.restore();

        drawFoams(canvas);
        drawBubbles(canvas);

        int[] rippleRgb = resolveRippleRgb();
        int rr = rippleRgb[0];
        int rg = rippleRgb[1];
        int rb = rippleRgb[2];
        ripplePaint.setStyle(Paint.Style.STROKE);
        for (Ripple r : ripples) {
            for (int ring = 0; ring < RIPPLE_DRAW_RINGS; ring++) {
                float ringScale = 1f - ring * 0.22f;
                float ringAlpha = r.alpha * ringScale;
                if (ringAlpha <= 0.03f) {
                    continue;
                }
                float rad = r.radius - ring * dp(5f);
                if (rad <= dp(2f)) {
                    continue;
                }
                int a = clamp255((int) (ringAlpha * 255f));
                ripplePaint.setStrokeWidth(ring == 0 ? dpStrokeThick : dpStrokeThin);
                ripplePaint.setColor(Color.argb(a, rr, rg, rb));
                canvas.drawCircle(r.cx, r.cy, rad, ripplePaint);
            }
        }

        if (!floatingInfoLabels.isEmpty()) {
            drawFloatingInfoLabels(canvas, w, h);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        floatingInfoLayoutMode = FLOATING_INFO_LAYOUT_NONE;
        ensureFloatingInfoLayout(w, h);
        cachedShader = null;
        cachedShaderHeight = 0;
        resetSlosh();
    }
}
