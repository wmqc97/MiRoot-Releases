/*
 * 截图合成工具类 - 优化版本
 * 提取公共合成逻辑，减少代码重复，改进错误处理和资源管理
 */

package com.wmqc.miroot.lyrics;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 截图合成工具类
 * 提供统一的截图合成功能，支持在UI线程或后台线程中执行
 */
public class ScreenshotCompositeHelper {
    private static final String TAG = "ScreenshotComposite";
    
    /**
     * 合成截图到手机背面图片
     * @param phoneBackPath 手机背面图片路径
     * @param screenshotPath 截图文件路径
     * @param outputPath 输出文件路径
     * @return 是否成功
     */
    /**
     * @param appContext 用于读取 ProMax 半屏/全壳偏好；可为 null（按半屏新参数）
     */
    public static boolean compositeScreenshot(String phoneBackPath, String screenshotPath, String outputPath,
            Context appContext) {
        LogHelper.d(TAG, "═══════════════════════════════════════════════════════");
        LogHelper.d(TAG, "🎨 开始合成截图到手机背面图片");
        LogHelper.d(TAG, "═══════════════════════════════════════════════════════");
        LogHelper.d(TAG, "手机背面图片路径: " + phoneBackPath);
        LogHelper.d(TAG, "截图路径: " + screenshotPath);
        LogHelper.d(TAG, "输出路径: " + outputPath);
        
        Bitmap phoneBackBitmap = null;
        Bitmap screenshotBitmap = null;
        Bitmap compositeBitmap = null;
        
        try {
            // 验证文件存在
            if (!validateFiles(phoneBackPath, screenshotPath)) {
                return false;
            }
            
            // 加载图片
            phoneBackBitmap = loadBitmap(phoneBackPath, "手机背面图片");
            if (phoneBackBitmap == null) {
                return false;
            }
            
            screenshotBitmap = loadBitmap(screenshotPath, "截图");
            if (screenshotBitmap == null) {
                return false;
            }
            
            // 执行合成
            compositeBitmap = createCompositeBitmap(phoneBackBitmap, screenshotBitmap, appContext);
            if (compositeBitmap == null) {
                return false;
            }
            
            // 保存合成结果
            if (!saveCompositeBitmap(compositeBitmap, outputPath)) {
                return false;
            }
            
            LogHelper.d(TAG, "✅ 截图合成成功: " + outputPath);
            return true;
            
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 合成截图失败: " + e.getMessage(), e);
            return false;
        } finally {
            // 确保资源释放
            recycleBitmap(phoneBackBitmap);
            recycleBitmap(screenshotBitmap);
            recycleBitmap(compositeBitmap);
        }
    }

    public static boolean compositeScreenshot(String phoneBackPath, String screenshotPath, String outputPath) {
        return compositeScreenshot(phoneBackPath, screenshotPath, outputPath, null);
    }
    
    /**
     * 验证文件是否存在
     */
    private static boolean validateFiles(String phoneBackPath, String screenshotPath) {
        File phoneBackFile = new File(phoneBackPath);
        if (!phoneBackFile.exists() || phoneBackFile.length() == 0) {
            LogHelper.e(TAG, "❌ 手机背面图片不存在或为空: " + phoneBackPath);
            return false;
        }
        
        File screenshotFile = new File(screenshotPath);
        if (!screenshotFile.exists() || screenshotFile.length() == 0) {
            LogHelper.e(TAG, "❌ 截图文件不存在或为空: " + screenshotPath);
            return false;
        }
        
        LogHelper.d(TAG, "✅ 文件验证通过 - 手机背面: " + phoneBackFile.length() + " bytes, 截图: " + screenshotFile.length() + " bytes");
        return true;
    }
    
    /**
     * 加载Bitmap
     */
    private static Bitmap loadBitmap(String filePath, String description) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inJustDecodeBounds = false;
            options.inSampleSize = 1;
            
            Bitmap bitmap = BitmapFactory.decodeFile(filePath, options);
            if (bitmap != null) {
                LogHelper.d(TAG, "✅ " + description + "加载成功，尺寸: " + bitmap.getWidth() + "x" + bitmap.getHeight());
            } else {
                LogHelper.e(TAG, "❌ 无法加载" + description + ": " + filePath);
            }
            return bitmap;
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 加载" + description + "失败: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 创建合成后的Bitmap
     */
    private static Bitmap createCompositeBitmap(Bitmap phoneBackBitmap, Bitmap screenshotBitmap, Context appContext) {
        Bitmap toDraw = screenshotBitmap;
        boolean scaledCopy = false;
        try {
            int[] target = DeviceModelHelper.getProMaxCompositeTargetSize(appContext);
            if (DeviceModelHelper.isProMaxModel() && target[0] > 0 && target[1] > 0) {
                toDraw = Bitmap.createScaledBitmap(screenshotBitmap, target[0], target[1], true);
                scaledCopy = true;
                boolean full = appContext != null && DeviceModelHelper.isProMaxShellFull(appContext);
                LogHelper.d(TAG, "ProMax" + (full ? "（完整）" : "（聚焦）") + "：截图已缩放至 "
                        + target[0] + "x" + target[1]);
            }

            // 创建合成后的Bitmap（使用手机背面图片的大小）
            Bitmap compositeBitmap = Bitmap.createBitmap(
                phoneBackBitmap.getWidth(), 
                phoneBackBitmap.getHeight(), 
                Bitmap.Config.ARGB_8888
            );
            Canvas canvas = new Canvas(compositeBitmap);
            
            Paint paint = new Paint();
            paint.setFilterBitmap(true);
            paint.setAntiAlias(true);
            
            int[] coordinates = DeviceModelHelper.getCompositeScreenshotCoordinates(appContext);
            final int TARGET_X = coordinates[0];
            final int TARGET_Y = coordinates[1];
            LogHelper.d(TAG, "合成坐标: X=" + TARGET_X + ", Y=" + TARGET_Y);
            
            Rect screenshotSrcRect = new Rect(0, 0, toDraw.getWidth(), toDraw.getHeight());
            Rect screenshotDstRect = new Rect(
                TARGET_X, 
                TARGET_Y, 
                TARGET_X + toDraw.getWidth(), 
                TARGET_Y + toDraw.getHeight()
            );
            
            canvas.drawBitmap(toDraw, screenshotSrcRect, screenshotDstRect, paint);
            
            // 外框底图盖住截图区域；贴图必须在此之后绘制，才是视觉上的最后一层
            canvas.drawBitmap(phoneBackBitmap, 0, 0, paint);

            if (appContext != null) {
                com.wmqc.miroot.shell.ShellStickerOverlay.drawOnCanvasIfEnabled(canvas, appContext, paint);
            }
            
            LogHelper.d(TAG, "✅ 合成Bitmap创建成功: " + compositeBitmap.getWidth() + "x" + compositeBitmap.getHeight());
            return compositeBitmap;
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 创建合成Bitmap失败: " + e.getMessage(), e);
            return null;
        } finally {
            if (scaledCopy && toDraw != null && toDraw != screenshotBitmap) {
                recycleBitmap(toDraw);
            }
        }
    }
    
    /**
     * 保存合成后的Bitmap
     */
    private static boolean saveCompositeBitmap(Bitmap compositeBitmap, String outputPath) {
        try {
            File outputFile = new File(outputPath);
            File outputDir = outputFile.getParentFile();
            if (outputDir != null && !outputDir.exists()) {
                outputDir.mkdirs();
            }
            
            boolean compressSuccess;
            try (FileOutputStream outputStream = new FileOutputStream(outputPath)) {
                compressSuccess = compositeBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
                outputStream.flush();
            }
            
            if (!compressSuccess) {
                LogHelper.e(TAG, "❌ 图片压缩保存失败");
                return false;
            }
            
            // 验证输出文件
            if (!outputFile.exists() || outputFile.length() == 0) {
                LogHelper.e(TAG, "❌ 输出文件不存在或为空");
                return false;
            }
            
            LogHelper.d(TAG, "✅ 合成图片保存成功: " + outputPath + " (大小: " + outputFile.length() + " bytes)");
            return true;
        } catch (IOException e) {
            LogHelper.e(TAG, "❌ 保存合成图片失败: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 安全释放Bitmap资源
     */
    private static void recycleBitmap(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            try {
                bitmap.recycle();
            } catch (Exception e) {
                LogHelper.w(TAG, "释放Bitmap资源时出错: " + e.getMessage());
            }
        }
    }
    
    /**
     * 获取手机背面图片路径（优先顺序：内部目录 > 外部目录 > 从assets复制）
     * @param context Context对象（用于访问assets和files目录）
     * @return 手机背面图片路径，如果不存在则返回null
     */
    public static String getPhoneBackImagePath(android.content.Context context) {
        String phoneBackFileName = DeviceModelHelper.getPhoneBackImageFileName(context);
        String externalFileName = DeviceModelHelper.getPhoneBackExternalFileName();
        
        // 1. 检查内部私有目录
        File internalFile = new File(context.getFilesDir(), phoneBackFileName);
        if (internalFile.exists() && internalFile.length() > 0) {
            LogHelper.d(TAG, "✅ 找到内部手机背面图片: " + internalFile.getAbsolutePath());
            return internalFile.getAbsolutePath();
        }
        
        // 2. 检查外部目录
        String externalPath = "/sdcard/Android/data/com.wmqc.miroot.lyrics/files/screenshots/" + externalFileName;
        File externalFile = new File(externalPath);
        if (externalFile.exists() && externalFile.length() > 0) {
            LogHelper.d(TAG, "✅ 找到外部手机背面图片: " + externalPath);
            return externalPath;
        }
        
        // 3. 尝试从assets复制
        if (copyPhoneBackFromAssets(context, phoneBackFileName, internalFile)) {
            if (internalFile.exists() && internalFile.length() > 0) {
                LogHelper.d(TAG, "✅ 已从assets复制手机背面图片: " + internalFile.getAbsolutePath());
                return internalFile.getAbsolutePath();
            }
        }
        
        LogHelper.e(TAG, "❌ 无法找到或创建手机背面图片");
        return null;
    }
    
    /**
     * 从assets复制手机背面图片
     */
    private static boolean copyPhoneBackFromAssets(android.content.Context context, String fileName, File targetFile) {
        try {
            android.content.res.AssetManager assetManager = context.getAssets();
            java.io.InputStream inputStream;
            try {
                inputStream = DeviceModelHelper.openPhoneBackAssetInputStream(assetManager, fileName);
            } catch (Exception e) {
                LogHelper.e(TAG, "❌ 无法从 assets 加载背屏底图: " + fileName + " (" + e.getMessage() + ")");
                return false;
            }
            
            // 确保目标目录存在
            File parentDir = targetFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            
            // 复制文件
            try (FileOutputStream outputStream = new FileOutputStream(targetFile)) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = inputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, length);
                }
                outputStream.flush();
            }
            inputStream.close();
            
            return targetFile.exists() && targetFile.length() > 0;
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 从assets复制背屏底图失败: " + e.getMessage(), e);
            return false;
        }
    }
}

