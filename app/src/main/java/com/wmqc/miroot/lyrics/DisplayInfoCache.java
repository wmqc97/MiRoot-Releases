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

import android.graphics.Rect;
/**
 * 显示屏信息缓存
 * 在应用启动时获取一次，之后直接使用缓存数据
 */
public class DisplayInfoCache {
    private static final String TAG = "DisplayInfoCache";
    
    // 单例
    private static volatile DisplayInfoCache instance;
    
    // 缓存的背屏信息
    private RearDisplayHelper.RearDisplayInfo cachedInfo;
    private boolean initialized = false;
    
    private DisplayInfoCache() {}
    
    public static DisplayInfoCache getInstance() {
        if (instance == null) {
            synchronized (DisplayInfoCache.class) {
                if (instance == null) {
                    instance = new DisplayInfoCache();
                }
            }
        }
        return instance;
    }
    
    /**
     * 初始化缓存（在应用启动时调用一次）
     */
    public synchronized void initialize(ITaskService taskService) {
        if (initialized) {
            LogHelper.d(TAG, "ℹ️ 已初始化，跳过");
            return;
        }
        
        try {
            LogHelper.d(TAG, "🔄 开始获取背屏信息...");
            cachedInfo = RearDisplayHelper.getRearDisplayInfo(taskService);
            initialized = true;
            
            LogHelper.d(TAG, String.format("✅ 背屏信息已缓存: %dx%d, DPI=%d, Cutout=%s",
                cachedInfo.width, cachedInfo.height, cachedInfo.densityDpi,
                cachedInfo.hasCutout() ? cachedInfo.cutout.toString() : "无"));
                
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 初始化失败", e);
            // 设置默认值
            cachedInfo = new RearDisplayHelper.RearDisplayInfo();
            cachedInfo.width = 904;
            cachedInfo.height = 572;
            cachedInfo.densityDpi = 450;
            cachedInfo.cutout = new Rect(0, 0, 0, 0);
            initialized = true;
            LogHelper.w(TAG, "⚠️ 使用默认背屏信息");
        }
    }
    
    /**
     * 获取缓存的背屏信息
     */
    public RearDisplayHelper.RearDisplayInfo getCachedInfo() {
        if (!initialized) {
            LogHelper.w(TAG, "⚠️ 缓存未初始化，返回默认值");
            RearDisplayHelper.RearDisplayInfo defaultInfo = new RearDisplayHelper.RearDisplayInfo();
            defaultInfo.width = 904;
            defaultInfo.height = 572;
            defaultInfo.densityDpi = 450;
            defaultInfo.cutout = new Rect(0, 0, 0, 0);
            return defaultInfo;
        }
        return cachedInfo;
    }
    
    /**
     * 强制重新获取（用于刷新缓存）
     */
    public synchronized void refresh(ITaskService taskService) {
        initialized = false;
        initialize(taskService);
    }
    
    /**
     * 检查是否已初始化
     */
    public boolean isInitialized() {
        return initialized;
    }
    
    /**
     * 清除缓存（用于测试或重置）
     */
    public synchronized void clear() {
        cachedInfo = null;
        initialized = false;
        LogHelper.d(TAG, "🗑️ 缓存已清除");
    }
}

