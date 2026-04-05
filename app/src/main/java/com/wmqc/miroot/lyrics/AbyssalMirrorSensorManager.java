/*
 * 深渊镜效果传感器管理类
 * 负责陀螺仪数据的获取和处理
 */

package com.wmqc.miroot.lyrics;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
/**
 * 深渊镜效果传感器管理器
 * 处理陀螺仪数据，提供平滑的旋转数据
 */
public class AbyssalMirrorSensorManager implements SensorEventListener {
    private static final String TAG = "AbyssalMirrorSensor";
    
    private Context context;
    private SensorManager sensorManager;
    private Sensor gyroscope;
    private Handler handler; // 用于在主线程中处理传感器回调
    
    // 传感器数据
    private float[] gyroRaw = new float[]{0, 0, 0};
    private float[] gyroSmooth = new float[]{0, 0, 0};
    private float smoothFactor = 0.88f; // 平滑系数（优化：从0.99降低到0.88，提高响应速度）
    
    // 旋转状态
    private float[] basisWorld = new float[]{0, 0, 0}; // 欧拉角
    private float[] qWorld = new float[]{0, 0, 0, 1}; // 四元数
    private float[] qAnchor = new float[]{0, 0, 0, 1}; // 锚点四元数
    private float[] eulerDiff = new float[]{0, 0, 0}; // 相对旋转欧拉角
    
    // 传感器状态
    private boolean isRegistered = false; // 标记传感器是否已注册
    
    // 时间管理
    private long lastTime = 0;
    private static final float FIXED_DELTA = 1.0f / 60.0f; // 固定物理帧率 60fps
    private float physicsAccumulator = 0;
    private long lastEulerLogTime = 0; // 诊断：节流输出 euler
    
    // 回调接口
    public interface OnRotationChangedListener {
        void onRotationChanged(float[] eulerDiff);
    }
    
    private OnRotationChangedListener listener;
    
    public AbyssalMirrorSensorManager(Context context) {
        this.context = context;
        // 创建Handler，用于在主线程中处理传感器回调
        this.handler = new Handler(Looper.getMainLooper());
        try {
            sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager != null) {
                gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
                if (gyroscope == null) {
                    LogHelper.w(TAG, "⚠️ 设备不支持陀螺仪传感器");
                } else {
                    LogHelper.d(TAG, "✅ 陀螺仪传感器已找到");
                }
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 初始化传感器管理器失败", e);
            sensorManager = null;
            gyroscope = null;
        }
    }
    
    /**
     * 设置旋转变化监听器
     */
    public void setOnRotationChangedListener(OnRotationChangedListener listener) {
        this.listener = listener;
    }
    
    /**
     * 开始监听传感器
     */
    public void start() {
        try {
            if (sensorManager == null || gyroscope == null) {
                LogHelper.w(TAG, "⚠️ 传感器不可用");
                return;
            }
            
            // 如果已经注册，先注销
            if (isRegistered) {
                LogHelper.w(TAG, "⚠️ 传感器已注册，先注销");
                sensorManager.unregisterListener(this);
                isRegistered = false;
            }
        
            // 重置状态
            resetState();
            lastTime = System.currentTimeMillis();
            
            // 注册传感器监听器，使用Handler和游戏级延迟（优化：从FASTEST改为GAME以降低耗电，同时保持流畅响应）
            // SENSOR_DELAY_FASTEST 约 0ms（耗电高），SENSOR_DELAY_GAME 约 20ms（平衡性能和耗电）
            // 使用 GAME 延迟以平衡响应速度和耗电
            // 使用Handler确保回调在主线程中执行
            boolean registered = sensorManager.registerListener(
                this, 
                gyroscope, 
                SensorManager.SENSOR_DELAY_GAME,  // 约20ms，平衡性能和耗电
                handler  // 使用Handler确保回调在主线程中执行
            );
        
            if (registered) {
                isRegistered = true;
                LogHelper.d(TAG, "✅ 陀螺仪传感器已注册（使用Handler）");
            } else {
                LogHelper.w(TAG, "⚠️ 陀螺仪传感器注册失败");
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 启动传感器监听失败", e);
            isRegistered = false;
        }
    }
    
    /**
     * 停止监听传感器
     */
    public void stop() {
        try {
            if (sensorManager != null && isRegistered) {
                sensorManager.unregisterListener(this);
                isRegistered = false;
                LogHelper.d(TAG, "✅ 陀螺仪传感器已注销");
            } else if (!isRegistered) {
                LogHelper.d(TAG, "ℹ️ 传感器未注册，无需注销");
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 停止传感器监听失败", e);
            isRegistered = false;
        }
    }
    
    /**
     * 重置状态
     */
    public void resetState() {
        gyroRaw = new float[]{0, 0, 0};
        gyroSmooth = new float[]{0, 0, 0};
        basisWorld = new float[]{0, 0, 0};
        qWorld = new float[]{0, 0, 0, 1};
        qAnchor = new float[]{0, 0, 0, 1};
        eulerDiff = new float[]{0, 0, 0};
        physicsAccumulator = 0;
    }
    
    @Override
    public void onSensorChanged(SensorEvent event) {
        try {
            if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            // 获取原始数据（弧度/秒），不再量化到 0.1：量化会导致角速度阶梯感、转动卡顿
            gyroRaw[0] = event.values[0];
            gyroRaw[1] = event.values[1];
            gyroRaw[2] = event.values[2];

            // 平滑处理
            gyroSmooth[0] = smoothFactor * gyroSmooth[0] + (1 - smoothFactor) * gyroRaw[0];
            gyroSmooth[1] = smoothFactor * gyroSmooth[1] + (1 - smoothFactor) * gyroRaw[1];
            gyroSmooth[2] = smoothFactor * gyroSmooth[2] + (1 - smoothFactor) * gyroRaw[2];
            
                // 固定时间步长物理更新
                processPhysics(FIXED_DELTA);
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 处理传感器数据失败", e);
        }
    }
    
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 不需要处理精度变化
    }
    
    /**
     * 物理更新（固定时间步长）
     */
    private void processPhysics(float delta) {
        // 积分获得当前设备旋转角
        basisWorld = QuaternionCalculator.rotateByGyro(gyroSmooth, basisWorld, delta);
        
        // 把当前设备旋转角转为四元数
        qWorld = QuaternionCalculator.fromEuler(basisWorld);
        
        // 计算角度差值的球面距离
        float t = QuaternionCalculator.angle(qAnchor, qWorld);
        
        // 用弹簧将旋转锚点向设备旋转角度靠近；降低死区使小幅旋转也能驱动第2-4层跟随
        // 原 (t-0.38)/0.1 使 t<0.38 时 lerp=0；改为 (t-0.05)/0.25，t>0.05 即参与
        float lerpFactor = Math.max(0, Math.min(1, (t - 0.05f) / 0.25f)) * 0.18f;
        qAnchor = QuaternionCalculator.slerp(qAnchor, qWorld, lerpFactor);
        
        // 重新计算锚点更新后的旋转角度差值
        float[] qInvAnchor = QuaternionCalculator.inverse(qAnchor);
        float[] qDiff = QuaternionCalculator.multiply(qInvAnchor, qWorld);
        
        // 四元数转为欧拉角
        eulerDiff = QuaternionCalculator.toEuler(qDiff);
        
        // 参考项目：camera_y = quatToEuler_res[1] * -1
        eulerDiff[1] = -eulerDiff[1];
        
        // 回调通知
        if (listener != null) {
            float mag = Math.abs(eulerDiff[0]) + Math.abs(eulerDiff[1]) + Math.abs(eulerDiff[2]);
            if (mag > 0.02f && System.currentTimeMillis() - lastEulerLogTime > 600) {
                LogHelper.d(TAG, "euler->listener: [" + eulerDiff[0] + "," + eulerDiff[1] + "," + eulerDiff[2] + "]");
                lastEulerLogTime = System.currentTimeMillis();
            }
            listener.onRotationChanged(eulerDiff);
        }
    }
    
    /**
     * 获取当前旋转欧拉角
     */
    public float[] getEulerDiff() {
        return eulerDiff.clone();
    }
    
    /**
     * 设置平滑系数
     * @param factor 平滑系数 [0, 1]，越大越平滑但响应越慢
     */
    public void setSmoothFactor(float factor) {
        this.smoothFactor = Math.max(0, Math.min(1, factor));
    }
}
