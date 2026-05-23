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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 背屏显示信息辅助类
 * 通过 dumpsys display 获取背屏分辨率、DPI、Cutout信息
 */
public class RearDisplayHelper {
    private static final String TAG = "RearDisplayHelper";
    
    /**
     * 背屏信息数据类
     */
    public static class RearDisplayInfo {
        public int width;           // 屏幕宽度（像素）
        public int height;          // 屏幕高度（像素）
        public int densityDpi;      // DPI
        public Rect cutout;         // Cutout区域（insets格式）        
        public RearDisplayInfo() {
            // 默认值（小米14 Ultra背屏）            width = 1200;
            height = 2200;
            densityDpi = 440;
            cutout = new Rect(0, 0, 0, 0);
        }
        
        @Override
        public String toString() {
            return String.format("RearDisplayInfo{width=%d, height=%d, dpi=%d, cutout=%s}",
                width, height, densityDpi, cutout.toString());
        }
        
        /**
         * 判断是否有cutout
         */
        public boolean hasCutout() {
            return cutout.left > 0 || cutout.top > 0 || cutout.right > 0 || cutout.bottom > 0;
        }
    }
    
    /**
     * 获取背屏信息（通过TaskService）
     */
    public static RearDisplayInfo getRearDisplayInfo(ITaskService taskService) {
        RearDisplayInfo info = new RearDisplayInfo();
        
        if (taskService == null) {
            LogHelper.w(TAG, "⚠️ TaskService为null，使用默认背屏信息");
            return info;
        }
        
        try {
            // 执行 dumpsys display 命令
            String result = taskService.executeShellCommandWithResult("dumpsys display");
            if (result == null || result.isEmpty()) {
                LogHelper.w(TAG, "⚠️ dumpsys display返回为空，使用默认背屏信息");
                return info;
            }
            
            // 🔍 详细日志：输出完整的dumpsys display结果（前2000字符）
            String preview = result.length() > 2000 ? result.substring(0, 2000) : result;
            LogHelper.d(TAG, "📋 dumpsys display 完整输出（前2000字符）：\n" + preview);
            LogHelper.d(TAG, "📏 dumpsys display 总长度: " + result.length() + " 字符");
            
            // 解析背屏信息（Display 1）
            parseRearDisplayInfo(result, info);
            
            LogHelper.d(TAG, "✓ 背屏信息: " + info.toString());
            
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 获取背屏信息失败，使用默认值", e);
        }
        
        return info;
    }
    
    /**
     * 解析 dumpsys display 输出
     */
    private static void parseRearDisplayInfo(String dumpsys, RearDisplayInfo info) {
        try {
            // 方法1: 从 mViewports 中解析（最准确）
            Pattern viewportPattern = Pattern.compile(
                "displayId=1[^}]*deviceWidth=(\\d+),\\s*deviceHeight=(\\d+)"
            );
            Matcher viewportMatcher = viewportPattern.matcher(dumpsys);
            if (viewportMatcher.find()) {
                info.width = Integer.parseInt(viewportMatcher.group(1));
                info.height = Integer.parseInt(viewportMatcher.group(2));
                LogHelper.d(TAG, String.format("✓ 从mViewports解析分辨率: %dx%d", info.width, info.height));
            }
            
            // 方法2: 查找Display 1的DisplayDeviceInfo区块（包含cutout）
            // 搜索 uniqueId="local:4630946949513469332" (Display 1的唯一标识)
            // 或者搜索包含 "904 x 572" 的 DisplayDeviceInfo
            int display1DeviceStart = -1;
            
            // 先尝试找到包含 displayId=1 的 DisplayViewport 来获取 uniqueId
            Pattern uniqueIdPattern = Pattern.compile("displayId=1[^}]*uniqueId='([^']+)'");
            Matcher uniqueIdMatcher = uniqueIdPattern.matcher(dumpsys);
            String display1UniqueId = null;
            if (uniqueIdMatcher.find()) {
                display1UniqueId = uniqueIdMatcher.group(1);
                LogHelper.d(TAG, "🔍 Display 1 uniqueId: " + display1UniqueId);
            }
            
            // 用uniqueId或分辨率来定位Display 1的DisplayDeviceInfo
            int searchPos = 0;
            while (true) {
                int idx = dumpsys.indexOf("DisplayDeviceInfo", searchPos);
                if (idx == -1) break;
                
                // 检查接下来2000字符内是否有匹配条件
                int checkEnd = Math.min(idx + 2000, dumpsys.length());
                String snippet = dumpsys.substring(idx, checkEnd);
                
                boolean isDisplay1 = false;
                if (display1UniqueId != null && snippet.contains(display1UniqueId)) {
                    isDisplay1 = true;
                } else if (snippet.contains(info.width + " x " + info.height)) {
                    // 用已解析的分辨率匹配（904 x 572）
                    isDisplay1 = true;
                }
                
                if (isDisplay1) {
                    display1DeviceStart = idx;
                    break;
                }
                searchPos = idx + 17; // "DisplayDeviceInfo".length()
            }
            
            String display1Block = "";
            if (display1DeviceStart != -1) {
                // 找到下一个 "DisplayDeviceInfo" 作为结束
                int nextBlockIdx = dumpsys.indexOf("DisplayDeviceInfo", display1DeviceStart + 17);
                
                display1Block = nextBlockIdx > 0 
                    ? dumpsys.substring(display1DeviceStart, nextBlockIdx)
                    : dumpsys.substring(display1DeviceStart, Math.min(display1DeviceStart + 3000, dumpsys.length()));
                
                LogHelper.d(TAG, "🔍 Display 1 DisplayDeviceInfo区块长度: " + display1Block.length() + " 字符");
                
                // 输出前600字符用于调试
                String preview = display1Block.length() > 600 
                    ? display1Block.substring(0, 600) 
                    : display1Block;
                LogHelper.d(TAG, "📋 Display 1 DisplayDeviceInfo区块（前600字符）：\n" + preview);
            } else {
                LogHelper.w(TAG, "⚠️ 未找到Display 1的DisplayDeviceInfo区块");
                display1Block = ""; // 不回退到全文，避免误匹配主屏数据
            }
            
            // 解析DPI（从DisplayDeviceInfo区块）
            // 格式: density 450
            if (!display1Block.isEmpty()) {
                Pattern dpiPattern = Pattern.compile("density\\s+(\\d+)");
                Matcher dpiMatcher = dpiPattern.matcher(display1Block);
                if (dpiMatcher.find()) {
                    info.densityDpi = Integer.parseInt(dpiMatcher.group(1));
                    LogHelper.d(TAG, "✓ 解析DPI: " + info.densityDpi);
                }
            }
            
            // 解析 Cutout（系统 dump 中常见的短横线分隔 Rect）
            // 格式: DisplayCutout{insets=Rect(296, 0 - 0, 0)
            // 注意：此处用 "top - right" 而不是 "top, right"
            info.cutout = parseCutoutFromDumpsys(display1Block);
            
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 解析背屏信息异常", e);
        }
    }
    
    /**
     * 解析 Cutout（短横线分隔的 Rect 行，与标准逗号分隔格式二选一）
     */
    private static Rect parseCutoutFromDumpsys(String display1Block) {
        Rect cutout = new Rect(0, 0, 0, 0);
        
        try {
            // 🔍 查找所有包含 "Cutout" 或 "cutout" 的行
            String[] lines = display1Block.split("\n");
            StringBuilder cutoutLines = new StringBuilder("📋 所有Cutout相关行：\n");
            boolean foundCutout = false;
            for (String line : lines) {
                if (line.toLowerCase().contains("cutout")) {
                    cutoutLines.append("  ").append(line.trim()).append("\n");
                    foundCutout = true;
                }
            }
            if (foundCutout) {
                LogHelper.d(TAG, cutoutLines.toString());
            } else {
                LogHelper.d(TAG, "ℹ️ Display 1区块中未找到任何包含'Cutout'的行");
            }
            
            // 短横线格式: Rect(296, 0 - 0, 0)
            // 标准格式: Rect(left, top, right, bottom)
            // 短横线格式: Rect(left, top - right, bottom)
            
            // 先尝试短横线格式
            Pattern hyphenInsetPattern = Pattern.compile("DisplayCutout\\{insets=Rect\\((\\d+),\\s*(\\d+)\\s*-\\s*(\\d+),\\s*(\\d+)\\)");
            Matcher hyphenInsetMatcher = hyphenInsetPattern.matcher(display1Block);
            
            if (hyphenInsetMatcher.find()) {
                cutout.left = Integer.parseInt(hyphenInsetMatcher.group(1));
                cutout.top = Integer.parseInt(hyphenInsetMatcher.group(2));
                cutout.right = Integer.parseInt(hyphenInsetMatcher.group(3));
                cutout.bottom = Integer.parseInt(hyphenInsetMatcher.group(4));
                LogHelper.d(TAG, String.format("✓ 解析Cutout(短横线格式): left=%d, top=%d, right=%d, bottom=%d",
                    cutout.left, cutout.top, cutout.right, cutout.bottom));
                return cutout;
            }
            
            // 再尝试标准格式（无短横线）
            Pattern standardPattern = Pattern.compile("DisplayCutout\\{insets=Rect\\((\\d+),\\s*(\\d+),\\s*(\\d+),\\s*(\\d+)\\)");
            Matcher standardMatcher = standardPattern.matcher(display1Block);
            
            if (standardMatcher.find()) {
                cutout.left = Integer.parseInt(standardMatcher.group(1));
                cutout.top = Integer.parseInt(standardMatcher.group(2));
                cutout.right = Integer.parseInt(standardMatcher.group(3));
                cutout.bottom = Integer.parseInt(standardMatcher.group(4));
                LogHelper.d(TAG, String.format("✓ 解析Cutout(标准格式): left=%d, top=%d, right=%d, bottom=%d",
                    cutout.left, cutout.top, cutout.right, cutout.bottom));
                return cutout;
            }
            
            // 尝试更宽松的模式（任何包含Rect的Cutout）
            Pattern loosePattern = Pattern.compile("cutout.*?Rect\\(([^)]+)\\)", Pattern.CASE_INSENSITIVE);
            Matcher looseMatcher = loosePattern.matcher(display1Block);
            if (looseMatcher.find()) {
                String rectContent = looseMatcher.group(1);
                LogHelper.d(TAG, "🔍 找到Cutout但格式未识别，Rect内容: " + rectContent);
            }
            
            LogHelper.d(TAG, "ℹ️ 未找到可识别的Cutout信息，使用默认值(0,0,0,0)");
            
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 解析Cutout异常", e);
        }
        
        return cutout;
    }
}

