/*
 * Author: AntiOblivionis
 * Co-developed with AI assistants.
 */
package com.wmqc.miroot.car;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import com.wmqc.miroot.lyrics.LogHelper;

import java.io.IOException;
import java.io.InputStream;

/**
 * 车控投屏在 {@code assets/car/} 下的位图（由构建时从仓库 {@code assets/car} 同步）。
 * 缺省时回退 {@code res/drawable}。
 */
public final class CarControlAssets {
    private static final String TAG = "CarControlAssets";

    /** APK 内 assets 子目录，与 syncCarControlAssets 输出一致 */
    public static final String ASSET_DIR = "car";

    private CarControlAssets() {}

    public static String pngPath(String baseName) {
        return ASSET_DIR + "/" + baseName + ".png";
    }

    /** WebP 资源路径（部分大图已从 PNG 转为 WebP，见 {@code assets/car/*.webp}） */
    public static String webpPath(String baseName) {
        return ASSET_DIR + "/" + baseName + ".webp";
    }

    public static boolean exists(Context context, String assetPath) {
        if (context == null || assetPath == null || assetPath.isEmpty()) {
            return false;
        }
        AssetManager am = context.getApplicationContext().getAssets();
        try (InputStream ignored = am.open(assetPath)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 从 assets 解码位图；失败返回 null。
     */
    public static Bitmap decodeBitmap(Context context, String assetPath) {
        if (context == null || assetPath == null || assetPath.isEmpty()) {
            return null;
        }
        try (InputStream is = context.getAssets().open(assetPath)) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inScaled = false;
            return BitmapFactory.decodeStream(is, null, options);
        } catch (IOException e) {
            LogHelper.d(TAG, "无此 asset: " + assetPath);
            return null;
        }
    }

    /**
     * 车控按钮图标：{@code car/{resourceBaseName}.png}
     */
    public static Drawable loadCarIconDrawable(Context context, String resourceBaseName, int boundsPx) {
        if (resourceBaseName == null || resourceBaseName.isEmpty()) {
            return null;
        }
        Bitmap bmp = decodeBitmap(context, pngPath(resourceBaseName));
        if (bmp == null) {
            return null;
        }
        BitmapDrawable dr = new BitmapDrawable(context.getResources(), bmp);
        dr.setBounds(0, 0, boundsPx, boundsPx);
        return dr;
    }
}
