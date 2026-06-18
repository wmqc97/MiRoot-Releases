
package com.wmqc.miroot.lyrics;

import android.content.Context;
import android.content.SharedPreferences;
/**
 * 设备型号判断工具类
 * 统一管理机型判断相关逻辑，避免代码重复
 *
 * <p>带壳底图资源文件名约定：
 * <ul>
 *   <li><b>ProMax</b>：{@code promax} + 颜色拼音首字母 + {@code .png}（聚焦/半屏底图），完整全壳在扩展名前加 {@code 2}，即 {@code promaxl2.png} 等。</li>
 *   <li>颜色与首字母映射：绿 lǜ→l，白 bái→b，灰 huī→h，紫 zǐ→z。</li>
 *   <li><b>Pro</b>：{@code pro} + 首字母（绿 {@code prol}、白 {@code prob}、灰 {@code proh}、紫 {@code proz}）；全壳在扩展名前加 {@code 2}（与 {@link com.wmqc.miroot.shell.DeviceGeometry#phoneBackFileName} 一致）。</li>
 * </ul>
 */
public class DeviceModelHelper {
    private static final String TAG = "DeviceModelHelper";

    /**
     * 带壳底图在 APK 内的唯一路径前缀（与 {@link com.wmqc.miroot.shell.DeviceGeometry} 一致）：
     * 仅使用 {@code app/src/main/assets/shell/*.png}，打包勿再包含 {@code flutter_assets/} 下重复底图。
     */
    public static final String SHELL_ASSETS_PREFIX = "shell/";

    /** @return 规范路径，例如 {@code shell/promaxl.png} */
    public static String getShellBundledAssetPath(String fileName) {
        return SHELL_ASSETS_PREFIX + fileName;
    }

    /**
     * 打开 APK 内带壳底图：仅从 {@code assets/shell/} 读取（与发版只打此目录一致）。
     */
    public static java.io.InputStream openPhoneBackAssetInputStream(
            android.content.res.AssetManager assets,
            String fileName) throws java.io.IOException {
        String shellPath = SHELL_ASSETS_PREFIX + fileName;
        java.io.InputStream in = assets.open(shellPath);
        LogHelper.d(TAG, "✅ 从 assets/shell 加载底图: " + shellPath);
        return in;
    }

    /** ProMax 聚焦底图（promax*.png，无后缀 2）：画布 1214×1817；叠放区左上角 (125,118) */
    public static final int PROMAX_HALF_CANVAS_W = 1214;
    public static final int PROMAX_HALF_CANVAS_H = 1817;
    public static final int PROMAX_HALF_SCREEN_X = 125;
    public static final int PROMAX_HALF_SCREEN_Y = 118;
    public static final int PROMAX_HALF_SCREEN_W = 976;
    public static final int PROMAX_HALF_SCREEN_H = 596;

    /**
     * ProMax 完整底图（promax*2.png）：截屏叠放区 976×596，左上角 (124,113)；画布 1217×2530。
     */
    public static final int PROMAX_FULL_SCREEN_X = 124;
    public static final int PROMAX_FULL_SCREEN_Y = 113;
    public static final int PROMAX_FULL_SCREEN_W = 976;
    public static final int PROMAX_FULL_SCREEN_H = 596;
    public static final int PROMAX_FULL_CANVAS_W = 1217;
    public static final int PROMAX_FULL_CANVAS_H = 2530;
    
    // 缓存机型判断结果（机型在应用运行期间不会改变）
    private static Boolean cachedIsProMax = null;
    
    /**
     * 判断机型是 Pro 还是 ProMax
     * @return true 表示 ProMax，false 表示 Pro
     */
    public static boolean isProMaxModel() {
        // 如果已缓存，直接返回
        if (cachedIsProMax != null) {
            return cachedIsProMax;
        }
        
        // 通过系统属性获取型号信息
        String roProductMarketname = getSystemProperty("ro.product.marketname");
        
        // 优先使用 ro.product.marketname 字段（最准确）
        if (roProductMarketname != null && !roProductMarketname.trim().isEmpty()) {
            String marketnameLower = roProductMarketname.toLowerCase();
            // 检查是否包含 "promax" 或 "pro max"（优先判断，避免误判）
            if (marketnameLower.contains("promax") || marketnameLower.contains("pro max")) {
                LogHelper.d(TAG, "✅ 通过 ro.product.marketname 检测到 ProMax: " + roProductMarketname);
                cachedIsProMax = true;
                return true;
            }
            // 检查是否包含 "17 pro" 但不包含 "max"（确保是 Pro 而不是 ProMax）
            if (marketnameLower.contains("17 pro") && !marketnameLower.contains("max")) {
                LogHelper.d(TAG, "✅ 通过 ro.product.marketname 检测到 Pro: " + roProductMarketname);
                cachedIsProMax = false;
                return false;
            }
        }
        
        // 如果 marketname 无法判断，使用合并信息
        String model = android.os.Build.MODEL;
        String device = android.os.Build.DEVICE;
        String product = android.os.Build.PRODUCT;
        String roProductModel = getSystemProperty("ro.product.model");
        String roProductName = getSystemProperty("ro.product.name");
        String roProductDevice = getSystemProperty("ro.product.device");
        String roProductBrand = getSystemProperty("ro.product.brand");
        String roMiuiProductName = getSystemProperty("ro.miui.product.name");
        String roProductDisplayId = getSystemProperty("ro.product.display_id");
        String roBuildDisplayId = getSystemProperty("ro.build.display.id");
        
        String allModelInfo = (model + " " + roProductModel + " " + roProductName + " " + device + " " + product + 
                              " " + roProductBrand + " " + roProductMarketname + " " + roMiuiProductName + 
                              " " + roProductDisplayId + " " + roBuildDisplayId).toLowerCase();
        
        // 优先判断 ProMax 或 Pro Max（不区分大小写）
        if (allModelInfo.contains("promax") || allModelInfo.contains("pro max")) {
            LogHelper.d(TAG, "✅ 通过合并信息检测到 ProMax");
            cachedIsProMax = true;
            return true;
        }
        
        // 判断 Pro（不区分大小写）
        if (allModelInfo.contains("pro")) {
            LogHelper.d(TAG, "✅ 通过合并信息检测到 Pro");
            cachedIsProMax = false;
            return false;
        }
        
        // 默认返回 ProMax
        LogHelper.w(TAG, "⚠️ 未检测到Pro或ProMax型号，默认使用 ProMax");
        cachedIsProMax = true;
        return true;
    }
    
    /**
     * 是否使用 ProMax 全壳底图（文件名 promax*2.png），否则为半屏 promax*.png。
     * SharedPreferences 键：{@code flutter.promax_shell_full}（Flutter 侧写入，默认 false=半屏）。
     */
    public static boolean isProMaxShellFull(Context context) {
        if (context == null || !isProMaxModel()) {
            return false;
        }
        try {
            SharedPreferences prefs = context.getSharedPreferences(
                    "FlutterSharedPreferences", Context.MODE_PRIVATE);
            return prefs.getBoolean("flutter.promax_shell_full", false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Pro 是否使用全壳底图（{@code pro*2.png}）。键 {@code flutter.pro_shell_full}，与 Kotlin {@link com.wmqc.miroot.shell.DeviceGeometry} 一致。
     */
    public static boolean isProShellFull(Context context) {
        if (context == null || isProMaxModel()) {
            return false;
        }
        try {
            SharedPreferences prefs = context.getSharedPreferences(
                    "FlutterSharedPreferences", Context.MODE_PRIVATE);
            return prefs.getBoolean("flutter.pro_shell_full", false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Flutter 可调合成坐标（键名与 Dart 一致，存储为 {@code flutter.<name>}）。
     */
    private static final String PREF_PROMAX_FOCUS_OVERLAY_X = "promax_focus_overlay_x";
    private static final String PREF_PROMAX_FOCUS_OVERLAY_Y = "promax_focus_overlay_y";
    private static final String PREF_PROMAX_FULL_OVERLAY_X = "promax_full_overlay_x";
    private static final String PREF_PROMAX_FULL_OVERLAY_Y = "promax_full_overlay_y";

    /**
     * 读取 Flutter {@code SharedPreferences} 中的整型（与 Dart {@code setInt} 写入一致）。
     * 兼容：{@code flutter.key} / 无前缀 {@code key}、{@code int}/{@code long}/{@code string} 存储。
     */
    private static int readFlutterIntPref(SharedPreferences prefs, String name, int defaultValue) {
        String fullKey = "flutter." + name;
        Integer v = tryReadIntPreference(prefs, fullKey);
        if (v != null) {
            return v;
        }
        v = tryReadIntPreference(prefs, name);
        return v != null ? v : defaultValue;
    }

    private static Integer tryReadIntPreference(SharedPreferences prefs, String key) {
        if (!prefs.contains(key)) {
            return null;
        }
        try {
            return prefs.getInt(key, 0);
        } catch (ClassCastException ignored) {
        }
        try {
            return (int) prefs.getLong(key, 0L);
        } catch (ClassCastException ignored) {
        }
        try {
            String s = prefs.getString(key, null);
            if (s != null && !s.isEmpty()) {
                return Integer.parseInt(s.trim());
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 根据机型获取合成截图的左上角坐标（ProMax 时依 {@link #isProMaxShellFull(Context)} 选聚焦或完整参数）。
     * @return [x, y]
     */
    public static int[] getCompositeScreenshotCoordinates() {
        return getCompositeScreenshotCoordinates(getApplicationContext());
    }

    /**
     * @param context 用于读取聚焦/完整偏好；可为 null（按聚焦 ProMax 内置默认）
     */
    public static int[] getCompositeScreenshotCoordinates(Context context) {
        if (!isProMaxModel()) {
            return new int[]{116, 933};
        }
        if (context == null) {
            return new int[]{PROMAX_HALF_SCREEN_X, PROMAX_HALF_SCREEN_Y};
        }
        try {
            SharedPreferences prefs = context.getSharedPreferences(
                    "FlutterSharedPreferences", Context.MODE_PRIVATE);
            if (isProMaxShellFull(context)) {
                int x = readFlutterIntPref(prefs, PREF_PROMAX_FULL_OVERLAY_X, PROMAX_FULL_SCREEN_X);
                int y = readFlutterIntPref(prefs, PREF_PROMAX_FULL_OVERLAY_Y, PROMAX_FULL_SCREEN_Y);
                return new int[]{x, y};
            }
            int x = readFlutterIntPref(prefs, PREF_PROMAX_FOCUS_OVERLAY_X, PROMAX_HALF_SCREEN_X);
            int y = readFlutterIntPref(prefs, PREF_PROMAX_FOCUS_OVERLAY_Y, PROMAX_HALF_SCREEN_Y);
            return new int[]{x, y};
        } catch (Exception e) {
            LogHelper.w(TAG, "读取合成坐标偏好失败: " + e.getMessage());
            if (isProMaxShellFull(context)) {
                return new int[]{PROMAX_FULL_SCREEN_X, PROMAX_FULL_SCREEN_Y};
            }
            return new int[]{PROMAX_HALF_SCREEN_X, PROMAX_HALF_SCREEN_Y};
        }
    }

    /**
     * 获取底图画布尺寸（带壳录屏 FFmpeg 黑底；应与当前模式对应 PNG 像素尺寸一致）
     */
    public static int[] getPhoneBackCanvasSize() {
        return getPhoneBackCanvasSize(getApplicationContext());
    }

    public static int[] getPhoneBackCanvasSize(Context context) {
        if (!isProMaxModel()) {
            return new int[]{1120, 1224};
        }
        if (context != null && isProMaxShellFull(context)) {
            return new int[]{PROMAX_FULL_CANVAS_W, PROMAX_FULL_CANVAS_H};
        }
        return new int[]{PROMAX_HALF_CANVAS_W, PROMAX_HALF_CANVAS_H};
    }

    /**
     * ProMax：将背屏截图/视频缩放到叠放区尺寸再合成（聚焦与完整均为 976×596，坐标不同）。
     */
    public static int[] getProMaxCompositeTargetSize(Context context) {
        if (!isProMaxModel()) {
            return new int[]{0, 0};
        }
        if (context != null && isProMaxShellFull(context)) {
            return new int[]{PROMAX_FULL_SCREEN_W, PROMAX_FULL_SCREEN_H};
        }
        return new int[]{PROMAX_HALF_SCREEN_W, PROMAX_HALF_SCREEN_H};
    }

    /**
     * 获取手机背面图片文件名（根据机型型号，带.webp后缀）
     * @return 文件名（promaxl.webp 或 prol.webp）
     */
    public static String getPhoneBackImageFileName() {
        return isProMaxModel() ? "promaxl.webp" : "prol.webp";
    }
    
    /**
     * 获取Application Context（用于在Service中访问SharedPreferences）
     */
    private static Context getApplicationContext() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = activityThreadClass.getMethod("currentApplication").invoke(null);
            if (activityThread instanceof Context) {
                return (Context) activityThread;
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "⚠️ 无法获取Application Context: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * 获取手机背面图片文件名（根据机型型号和用户选择，带.png后缀）
     * V3.7: 支持 ProMax 和 Pro 机型选择底图（绿色/白色/灰色/紫色）
     * @param context Context对象（用于访问SharedPreferences），如果为null则尝试获取Application Context
     * @return 文件名（含 ProMax / Pro 半屏与全壳 {@code *2.png}、Pro 紫色 {@code proz.png} 等）
     */
    public static String getPhoneBackImageFileName(Context context) {
        // 如果context为null，尝试获取Application Context
        if (context == null) {
            context = getApplicationContext();
        }
        
        // 如果仍然无法获取Context，使用默认文件名
        if (context == null) {
            LogHelper.w(TAG, "⚠️ 无法获取Context，使用默认底图文件名");
            return isProMaxModel() ? "promaxl.webp" : "prol.webp";
        }
        try {
            SharedPreferences prefs = context.getSharedPreferences(
                "FlutterSharedPreferences", Context.MODE_PRIVATE);
            
            if (isProMaxModel()) {
                // ProMax：颜色 + 半屏(promaxl.webp) / 全壳(promaxl2.webp)
                String backImageType = prefs.getString("flutter.promax_back_image_type", "green");
                String name;
                if ("white".equals(backImageType)) {
                    name = "promaxb.webp";
                    LogHelper.d(TAG, "✅ ProMax 使用白色底图: " + name);
                } else if ("gray".equals(backImageType)) {
                    name = "promaxh.webp";
                    LogHelper.d(TAG, "✅ ProMax 使用灰色底图: " + name);
                } else if ("purple".equals(backImageType)) {
                    name = "promaxz.webp";
                    LogHelper.d(TAG, "✅ ProMax 使用紫色底图: " + name);
                } else {
                    name = "promaxl.webp";
                    LogHelper.d(TAG, "✅ ProMax 使用绿色底图: " + name);
                }
                if (isProMaxShellFull(context) && name.endsWith(".webp")) {
                    name = name.substring(0, name.length() - 5) + "2.webp";
                    LogHelper.d(TAG, "✅ ProMax 全壳底图文件名: " + name);
                }
                return name;
            } else {
                String backImageType = prefs.getString("flutter.pro_back_image_type", "green");
                String name;
                if ("white".equals(backImageType)) {
                    name = "prob.webp";
                } else if ("gray".equals(backImageType)) {
                    name = "proh.webp";
                } else if ("purple".equals(backImageType)) {
                    name = "proz.webp";
                } else {
                    name = "prol.webp";
                }
                if (isProShellFull(context) && name.endsWith(".webp")) {
                    name = name.substring(0, name.length() - 5) + "2.webp";
                }
                LogHelper.d(TAG, "✅ Pro 底图文件名: " + name);
                return name;
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "⚠️ 读取底图选择失败，使用默认: " + e.getMessage());
            return isProMaxModel() ? "promaxl.webp" : "prol.webp";
        }
    }
    
    /**
     * 获取外部screenshots文件夹中的背屏底图文件名（根据机型型号，无后缀）
     * @return 外部文件名（promax 或 pro）
     */
    public static String getPhoneBackExternalFileName() {
        return isProMaxModel() ? "promax" : "pro";
    }
    
    /**
     * 获取外部screenshots文件夹中的背屏底图文件名（根据机型型号和用户选择，无后缀）
     * V3.7: 外部目录使用 promax 或 pro 文件名，根据用户选择从不同源文件复制
     * @param context Context对象（用于访问SharedPreferences）
     * @return 外部文件名（始终为 promax 或 pro）
     */
    public static String getPhoneBackExternalFileName(Context context) {
        // ⚠️ 修改：外部目录使用 promax 或 pro 文件名，根据用户选择从不同源文件复制
        return isProMaxModel() ? "promax" : "pro";
    }
    
    /**
     * 获取需要复制的源文件名（根据用户选择）
     * V3.7: 根据用户选择返回需要复制的源文件名，支持Pro和ProMax机型（绿色/白色/灰色/紫色）
     * @param context Context对象（用于访问SharedPreferences）
     * @return 源文件名（与 {@link #getPhoneBackImageFileName(Context)} 规则一致）
     */
    public static String getPhoneBackSourceFileName(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(
                "FlutterSharedPreferences", Context.MODE_PRIVATE);
            
            if (isProMaxModel()) {
                String backImageType = prefs.getString("flutter.promax_back_image_type", "green");
                String name;
                if ("white".equals(backImageType)) {
                    name = "promaxb.webp";
                } else if ("gray".equals(backImageType)) {
                    name = "promaxh.webp";
                } else if ("purple".equals(backImageType)) {
                    name = "promaxz.webp";
                } else {
                    name = "promaxl.webp";
                }
                if (isProMaxShellFull(context) && name.endsWith(".webp")) {
                    name = name.substring(0, name.length() - 5) + "2.webp";
                }
                LogHelper.d(TAG, "✅ ProMax 源文件: " + name);
                return name;
            } else {
                String backImageType = prefs.getString("flutter.pro_back_image_type", "green");
                String name;
                if ("white".equals(backImageType)) {
                    name = "prob.webp";
                } else if ("gray".equals(backImageType)) {
                    name = "proh.webp";
                } else if ("purple".equals(backImageType)) {
                    name = "proz.webp";
                } else {
                    name = "prol.webp";
                }
                if (isProShellFull(context) && name.endsWith(".webp")) {
                    name = name.substring(0, name.length() - 5) + "2.webp";
                }
                LogHelper.d(TAG, "✅ Pro 源文件: " + name);
                return name;
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "⚠️ 读取底图选择失败，使用默认: " + e.getMessage());
            return isProMaxModel() ? "promaxl.webp" : "prol.webp";
        }
    }
    
    /**
     * 通过系统属性获取型号信息
     * @param key 属性键
     * @return 属性值，失败返回空字符串
     */
    private static String getSystemProperty(String key) {
        try {
            Process process = Runtime.getRuntime().exec("getprop " + key);
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()));
            String result = reader.readLine();
            reader.close();
            process.waitFor();
            return result != null ? result.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }
}

