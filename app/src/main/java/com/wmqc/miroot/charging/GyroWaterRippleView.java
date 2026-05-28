package com.wmqc.miroot.charging;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
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
    /** 高于此电量百分比用绿色液体，否则橙色 */
    private static final int TINT_GREEN_ABOVE_PERCENT = 20;

    // --- 气泡粒子常量 ---
    private static final int MAX_BUBBLES = 48;
    private static final long BUBBLE_SPAWN_INTERVAL_NS = 400_000_000L;
    private static final float BUBBLE_MIN_RADIUS_DP = 3f;
    private static final float BUBBLE_MAX_RADIUS_DP = 10f;

    private final Paint waterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path waterPath = new Path();
    private final Random random = new Random();

    private float fillLevel = 0.35f;
    /** 实际电量 0–100，决定绿/橙液体（与正在动画的液位无关） */
    private int batteryPercentForTint = 100;
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
    private long lastTickNs;
    private long lastTouchRippleMs;
    /** 用于涟漪生成与衰减的平滑陀螺仪合幅度 */
    private float smoothGyroMagForRipples;
    /** 是否处于高频自动生漪模式（滞回，与 {@link #smoothGyroMagForRipples} 配合） */
    private boolean rippleHighMotionMode;

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
        float speedY;
        float speedX;
        float alpha;
        float wobblePhase;
        float wobbleAmp;
    }

    private final List<Ripple> ripples = new ArrayList<>();
    private final List<Bubble> bubbles = new ArrayList<>();
    private long lastBubbleSpawnNs;

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
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 电量液位 0–1，决定液面基准高度（从下往上）。 */
    public void setFillLevel(float level) {
        float v = Math.max(0f, Math.min(1f, level));
        if (Math.abs(v - fillLevel) > 0.001f) {
            fillLevel = v;
            invalidate();
        }
    }

    public float getFillLevel() {
        return fillLevel;
    }

    /** 设置电量百分比，用于液体与涟漪配色（大于 20% 绿色，否则橙色）。 */
    public void setBatteryPercentForTint(int percent) {
        int p = Math.max(0, Math.min(100, percent));
        if (p != batteryPercentForTint) {
            batteryPercentForTint = p;
            invalidate();
        }
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
        cachedShader = null;
        cachedShaderHeight = 0;
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

        long spawnInterval = rippleHighMotionMode ? RIPPLE_SPAWN_NS_ACTIVE : RIPPLE_SPAWN_NS;
        if (frameTimeNanos - lastRippleNs >= spawnInterval) {
            lastRippleNs = frameTimeNanos;
            spawnAmbientRipple();
        }
        if (frameTimeNanos - lastBubbleSpawnNs >= BUBBLE_SPAWN_INTERVAL_NS) {
            lastBubbleSpawnNs = frameTimeNanos;
            spawnBubble();
        }

        if (w <= 0f || h <= 0f) {
            return;
        }

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

    private void spawnAmbientRipple() {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) {
            return;
        }
        float marginX = w * 0.18f;
        float marginY = h * 0.18f;
        float biasX = liquidShiftX * 0.35f + smoothGy * w * 0.04f;
        float biasY = smoothGy * h * 0.02f;
        float cx = marginX + random.nextFloat() * (w - 2f * marginX) + biasX;
        float cy = marginY + random.nextFloat() * (h - 2f * marginY) + biasY;
        cx = clamp(cx, marginX * 0.5f, w - marginX * 0.5f);
        cy = clamp(cy, marginY * 0.5f, h - marginY * 0.5f);
        spawnRippleAt(
            cx,
            cy,
            0.52f + Math.min(0.2f, smoothGyroMagForRipples * 0.07f),
            dp(8f)
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
        for (int i = 0; i < 8; i++) {
            spawnBubble();
        }
    }

    private void spawnBubble() {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f || bubbles.size() >= MAX_BUBBLES) {
            return;
        }
        float baseSurf = h * (1f - fillLevel);
        if (baseSurf >= h * 0.96f) {
            return;
        }
        Bubble b = new Bubble();
        float margin = w * 0.12f;
        b.x = margin + random.nextFloat() * (w - 2f * margin);
        b.y = baseSurf + random.nextFloat() * dp(10f) - dp(5f);
        float radiusDp = BUBBLE_MIN_RADIUS_DP + random.nextFloat()
                * (BUBBLE_MAX_RADIUS_DP - BUBBLE_MIN_RADIUS_DP);
        b.radius = dp(radiusDp);
        b.speedY = dp(12f + radiusDp * 3f);
        b.speedX = (random.nextFloat() - 0.5f) * dp(5f);
        b.alpha = 0f;
        b.wobblePhase = random.nextFloat() * 6.2832f;
        b.wobbleAmp = dp(2f + random.nextFloat() * 4f);
        bubbles.add(b);
    }

    private void updateBubbles(float dt, float frameScale, float w, float h) {
        if (w <= 0f || h <= 0f || bubbles.isEmpty()) {
            return;
        }
        float topFadeZone = h * 0.25f;
        float gLen = length3(gravAx, gravAy, gravAz);
        float gnx = gLen > 0.5f ? gravAx / gLen : 0f;
        float flowScale = Math.min(w, h) * 0.02f;
        float driftX = smoothGy * dt * 30f + gnx * dt * flowScale;
        Iterator<Bubble> it = bubbles.iterator();
        while (it.hasNext()) {
            Bubble b = it.next();
            b.y -= b.speedY * dt;
            b.x += b.speedX * dt + driftX
                    + (float) Math.sin(b.wobblePhase) * b.wobbleAmp * dt;
            b.wobblePhase += dt * (2.0f + random.nextFloat() * 1.5f);
            if (b.alpha < 1f) {
                b.alpha = Math.min(1f, b.alpha + dt * 2.5f);
            }
            if (b.y < topFadeZone) {
                b.alpha = Math.max(0f, b.alpha - dt * 1.5f);
            }
            if (b.y < -b.radius * 2f || b.alpha <= 0.01f) {
                it.remove();
            }
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

        canvas.drawColor(0xFF050810);

        float roll = smoothRollGnX;
        // 仅左右：横滚分量 + 陀螺仪 Y（绕竖直轴转动的左右晃），不跟俯仰/竖直朝向
        float tiltAlongX = roll * 1.25f + smoothGy * 0.58f;
        tiltAlongX = clamp(tiltAlongX, -1.65f, 1.65f);
        float slopeAmp = Math.max(dp(12f), h * 0.21f);

        float baseSurf = h * (1f - fillLevel);
        float amp = Math.max(dp(6f), h * 0.012f);
        float k = 0.011f + smoothGy * 0.0065f + liquidShiftX * 0.000015f;
        float phaseOff = wavePhase + smoothGz * 0.55f + liquidShiftX * 0.0042f;

        waterPath.reset();
        waterPath.moveTo(0, h);
        int steps = 56;
        for (int i = 0; i <= steps; i++) {
            float x = w * i / (float) steps;
            float tiltY = slopeAmp * tiltAlongX * (x / w - 0.5f) * 2f;
            double ang = x * k + phaseOff + smoothGx * 0.4f * Math.sin(x * 0.02f + wavePhase);
            float y = baseSurf + tiltY
                + amp * (float) Math.sin(ang)
                + amp * 0.32f * (float) Math.sin(ang * 1.65f + smoothGx * 1.8f)
                + amp * 0.28f * (float) Math.sin(ang * 1.7f + smoothGy * 2.2f);
            waterPath.lineTo(x, y);
        }
        waterPath.lineTo(w, h);
        waterPath.close();

        float gradTop = baseSurf;
        boolean greenLiquid = batteryPercentForTint > TINT_GREEN_ABOVE_PERCENT;
        int gradTopArgb = greenLiquid ? 0xF030FF8A : 0xF0FFCC80;
        int gradBotArgb = greenLiquid ? 0xFF008F52 : 0xFF9E4A0E;
        // 量化渐变起点，避免 fillLevel 微抖导致每帧重建 shader
        int gradTopQ = Math.round(gradTop / 4f) * 4; // 4px 阶梯
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

        // 气泡绘制：半透明白色上浮圆，高于液面
        if (!bubbles.isEmpty()) {
            for (Bubble b : bubbles) {
                int a = clamp255((int) (b.alpha * 160f));
                bubblePaint.setColor(Color.argb(a, 220, 240, 255));
                canvas.drawCircle(b.x, b.y, b.radius, bubblePaint);
                // 高光
                if (b.radius > dp(5f)) {
                    bubblePaint.setColor(Color.argb((int) (a * 0.35f), 255, 255, 255));
                    canvas.drawCircle(b.x - b.radius * 0.2f, b.y - b.radius * 0.25f,
                            b.radius * 0.32f, bubblePaint);
                }
            }
        }

        int rr = greenLiquid ? 55 : 255;
        int rg = greenLiquid ? 255 : 195;
        int rb = greenLiquid ? 140 : 115;
        ripplePaint.setStrokeWidth(dpStrokeThick);
        for (Ripple r : ripples) {
            int a = clamp255((int) (r.alpha * 255f));
            ripplePaint.setColor(Color.argb(a, rr, rg, rb));
            canvas.drawCircle(r.cx, r.cy, r.radius, ripplePaint);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cachedShader = null;
        cachedShaderHeight = 0;
    }
}
