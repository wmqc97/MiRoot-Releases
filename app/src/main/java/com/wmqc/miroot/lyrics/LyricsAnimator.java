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

import android.os.Handler;
import android.os.Looper;
import com.wmqc.miroot.BuildConfig;
/**
 * 歌词同步动画管理器
 * 
 * 负责:
 * - 歌词与音乐的精确同步
 * - 播放位置的准确计算
 * - 自动更新歌词显示
 * - 处理播放状态变化（播放/暂停/快进/快退）
 */
public class LyricsAnimator {
    private static final String TAG = "LyricsAnimator";
    
    // 更新频率：与 ModernLyricsView 逐字刷新节拍（约 30fps）对齐；过低会导致逐字高亮卡顿。
    private static final long UPDATE_INTERVAL_MS = 33; // ~30 FPS
    // 暂停时的更新频率：位置不变无需高帧率，250ms 仅维持心跳，降低 CPU 唤醒
    private static final long PAUSED_UPDATE_INTERVAL_MS = 250; // ~4 FPS
    
    // 回调接口
    public interface OnUpdateListener {
        /**
         * 当播放位置更新时调用
         * @param position 当前播放位置(毫秒)
         */
        void onPositionUpdate(long position);
        
        /**
         * 当歌词行变化时调用
         * @param lineIndex 新的歌词行索引
         */
        void onLineChanged(int lineIndex);
        
        /**
         * 当播放状态变化时调用
         * @param isPlaying 是否正在播放
         */
        void onPlaybackStateChanged(boolean isPlaying);
    }
    
    // 播放状态
    private boolean isPlaying = false;
    private long currentPosition = 0;       // 当前播放位置
    private long lastUpdateTime = 0;        // 上次更新时间
    private float playbackSpeed = 1.0f;     // 播放速度（1.0为正常速度）
    
    // 位置校准
    private long lastKnownPosition = 0;     // 上次已知的准确位置
    private long lastKnownPositionTime = 0; // 上次已知位置的时间戳
    private long lastCalibrateRealtimeMs = 0; // 上次执行校准的时间
    private static final long CALIBRATE_MIN_INTERVAL_MS = 1000L;
    private static final long FORCE_CALIBRATE_DEVIATION_MS = 1500L;
    private static final long NORMAL_CALIBRATE_DEVIATION_MS = 80L;
    
    // 回调
    private OnUpdateListener updateListener;
    
    // 更新任务
    private Handler handler;
    private Runnable updateRunnable;
    private boolean isRunning = false;
    
    // 当前行索引
    private int currentLineIndex = -1;
    private static final long DEBUG_POSITION_LOG_INTERVAL_MS = 15000L;
    private long lastPositionLogTime = 0L;
    
    public LyricsAnimator() {
        handler = new Handler(Looper.getMainLooper());
        
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                if (isRunning) {
                    update();
                    handler.postDelayed(this, getCurrentIntervalMs());
                }
            }
        };
    }
    
    /**
     * 获取当前帧更新间隔：播放时 30fps，暂停时降帧减少 CPU 唤醒
     */
    private long getCurrentIntervalMs() {
        return isPlaying ? UPDATE_INTERVAL_MS : PAUSED_UPDATE_INTERVAL_MS;
    }

    /**
     * 启动动画器
     */
    public void start() {
        if (!isRunning) {
            isRunning = true;
            lastUpdateTime = System.currentTimeMillis();
            handler.post(updateRunnable);
            if (BuildConfig.DEBUG) LogHelper.d(TAG, "歌词动画器已启动");
        }
    }

    /**
     * 停止动画器
     */
    public void stop() {
        if (isRunning) {
            isRunning = false;
            handler.removeCallbacks(updateRunnable);
            if (BuildConfig.DEBUG) LogHelper.d(TAG, "歌词动画器已停止");
        }
    }

    /**
     * 暂停（保持运行但不更新位置）
     */
    public void pause() {
        isPlaying = false;
        if (updateListener != null) {
            updateListener.onPlaybackStateChanged(false);
        }
        if (BuildConfig.DEBUG) LogHelper.d(TAG, "播放已暂停");
    }

    /**
     * 恢复播放
     */
    public void resume() {
        isPlaying = true;
        lastUpdateTime = System.currentTimeMillis();
        if (updateListener != null) {
            updateListener.onPlaybackStateChanged(true);
        }
        if (BuildConfig.DEBUG) LogHelper.d(TAG, "播放已恢复");
    }

    /**
     * 核心更新方法
     */
    private void update() {
        long currentTime = System.currentTimeMillis();

        if (isPlaying) {
            // 计算时间差
            long timeDelta = currentTime - lastUpdateTime;

            // 更新播放位置（考虑播放速度）
            currentPosition += (long)(timeDelta * playbackSpeed);

            // 确保位置不为负数
            if (currentPosition < 0) {
                currentPosition = 0;
            }

            // 通知监听器位置更新
            if (updateListener != null) {
                updateListener.onPositionUpdate(currentPosition);
            }

            // 调试日志（debug 下节流，避免位置更新日志刷屏）
            if (BuildConfig.DEBUG && (currentTime - lastPositionLogTime) >= DEBUG_POSITION_LOG_INTERVAL_MS) {
                lastPositionLogTime = currentTime;
                LogHelper.d(TAG, "📍 位置更新: " + currentPosition + "ms (isPlaying=" + isPlaying + ")");
            }
        }

        lastUpdateTime = currentTime;
    }
    
    /**
     * 设置播放位置（用于校准）
     * 
     * @param position 准确的播放位置(毫秒)
     */
    public void setPosition(long position) {
        this.currentPosition = position;
        this.lastKnownPosition = position;
        this.lastKnownPositionTime = System.currentTimeMillis();
        this.lastUpdateTime = lastKnownPositionTime;
        
        if (updateListener != null) {
            updateListener.onPositionUpdate(position);
        }
    }
    
    /**
     * 校准播放位置（从MediaController获取准确位置）
     * 
     * @param position 准确的播放位置
     * @param positionUpdateTime 位置更新的时间戳
     */
    public void calibratePosition(long position, long positionUpdateTime) {
        long currentTime = System.currentTimeMillis();
        
        // 首先检查position参数本身是否合理（不超过1小时，即3600000ms）
        // 如果position看起来像系统时间戳的一部分，直接拒绝
        if (position > 3600000) {
            if (BuildConfig.DEBUG) LogHelper.w(TAG, "位置参数异常: " + position + "ms，忽略此次校准");
            return; // 直接返回，不进行校准
        }
        
        // 检查positionUpdateTime是否有效
        // getLastPositionUpdateTime()可能返回0或系统时间戳
        // 如果值异常大（超过1小时），可能是系统时间戳，需要特殊处理
        long timeDiff;
        if (positionUpdateTime == 0) {
            // 如果为0，使用当前时间
            timeDiff = 0;
        } else if (positionUpdateTime > 1000000000000L) {
            // 如果值大于1000000000000（约31.7年），可能是系统时间戳
            // 计算时间差
            timeDiff = currentTime - positionUpdateTime;
            // 限制时间差在合理范围内（不超过5秒）
            if (timeDiff > 5000 || timeDiff < -5000) {
                timeDiff = 0; // 如果时间差异常，忽略
            }
        } else {
            // 正常情况：positionUpdateTime是相对时间戳
            timeDiff = currentTime - positionUpdateTime;
            // 限制时间差在合理范围内（不超过5秒）
            if (timeDiff > 5000 || timeDiff < -5000) {
                timeDiff = 0; // 如果时间差异常，忽略
            }
        }
        
        // 计算实际位置（加上时间差）
        long actualPosition = position + (isPlaying ? timeDiff : 0);
        
        // 确保位置值合理（不超过1小时，即3600000ms）
        if (actualPosition > 3600000) {
            if (BuildConfig.DEBUG) LogHelper.w(TAG, "计算出的位置值异常: " + actualPosition + "ms，使用原始位置: " + position);
            actualPosition = position;
            // 如果原始位置也异常，直接返回
            if (actualPosition > 3600000) {
                if (BuildConfig.DEBUG) LogHelper.w(TAG, "原始位置也异常，忽略此次校准");
                return;
            }
        }
        
        // 如果是第一次设置或偏差超过阈值，进行校准
        long deviation = Math.abs(actualPosition - currentPosition);
        boolean isFirstTime = (currentPosition == 0 && lastKnownPosition == 0);

        boolean shouldForceCalibrate = deviation >= FORCE_CALIBRATE_DEVIATION_MS;
        boolean hitCalibrateInterval = (currentTime - lastCalibrateRealtimeMs) >= CALIBRATE_MIN_INTERVAL_MS;
        boolean shouldNormalCalibrate = deviation > NORMAL_CALIBRATE_DEVIATION_MS && hitCalibrateInterval;

        if (isFirstTime || shouldForceCalibrate || shouldNormalCalibrate) {
            // 只在偏差较大（>500ms）或首次时输出详细日志，减少日志噪音
            if (isFirstTime || deviation > 500) {
                if (BuildConfig.DEBUG) LogHelper.d(TAG, "位置校准: " + currentPosition + " -> " + actualPosition + 
                          " (偏差: " + deviation + "ms, 首次: " + isFirstTime + 
                          ", timeDiff: " + timeDiff + "ms, intervalHit=" + hitCalibrateInterval + ")");
            }
            setPosition(actualPosition);
            lastCalibrateRealtimeMs = currentTime;
        }
        
        // 更新已知位置
        lastKnownPosition = position;
        lastKnownPositionTime = positionUpdateTime;
    }
    
    /**
     * 设置播放速度
     * 
     * @param speed 播放速度（1.0为正常，2.0为2倍速）
     */
    public void setPlaybackSpeed(float speed) {
        this.playbackSpeed = speed;
        if (BuildConfig.DEBUG) LogHelper.d(TAG, "播放速度已设置为: " + speed + "x");
    }
    
    /**
     * 快进
     * 
     * @param milliseconds 快进的毫秒数
     */
    public void seekForward(long milliseconds) {
        currentPosition += milliseconds;
        if (updateListener != null) {
            updateListener.onPositionUpdate(currentPosition);
        }
        if (BuildConfig.DEBUG) LogHelper.d(TAG, "快进 " + milliseconds + "ms 到位置: " + currentPosition);
    }
    
    /**
     * 快退
     * 
     * @param milliseconds 快退的毫秒数
     */
    public void seekBackward(long milliseconds) {
        currentPosition = Math.max(0, currentPosition - milliseconds);
        if (updateListener != null) {
            updateListener.onPositionUpdate(currentPosition);
        }
        if (BuildConfig.DEBUG) LogHelper.d(TAG, "快退 " + milliseconds + "ms 到位置: " + currentPosition);
    }
    
    /**
     * 跳转到指定位置
     * 
     * @param position 目标位置(毫秒)
     */
    public void seekTo(long position) {
        setPosition(Math.max(0, position));
        if (BuildConfig.DEBUG) LogHelper.d(TAG, "跳转到位置: " + position);
    }
    
    /**
     * 通知行变化
     * 
     * @param lineIndex 新的行索引
     */
    public void notifyLineChanged(int lineIndex) {
        if (lineIndex != currentLineIndex) {
            currentLineIndex = lineIndex;
            if (updateListener != null) {
                updateListener.onLineChanged(lineIndex);
            }
        }
    }
    
    /**
     * 重置状态
     */
    public void reset() {
        currentPosition = 0;
        lastUpdateTime = 0;
        lastKnownPosition = 0;
        lastKnownPositionTime = 0;
        lastCalibrateRealtimeMs = 0;
        currentLineIndex = -1;
        isPlaying = false;
        if (BuildConfig.DEBUG) LogHelper.d(TAG, "歌词动画器已重置");
    }
    
    // ========== Getter & Setter ==========
    
    public long getCurrentPosition() {
        return currentPosition;
    }
    
    public boolean isPlaying() {
        return isPlaying;
    }
    
    public boolean isRunning() {
        return isRunning;
    }
    
    public int getCurrentLineIndex() {
        return currentLineIndex;
    }
    
    public void setOnUpdateListener(OnUpdateListener listener) {
        this.updateListener = listener;
    }
    
    /**
     * 清理资源
     */
    public void release() {
        stop();
        updateListener = null;
        handler.removeCallbacksAndMessages(null);
        if (BuildConfig.DEBUG) LogHelper.d(TAG, "歌词动画器资源已释放");
    }
}

