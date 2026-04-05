package com.wmqc.miroot.theme;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.RemoteException;

import com.wmqc.miroot.lyrics.ITaskService;
import com.wmqc.miroot.lyrics.LogHelper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

/**
 * AI 壁纸 maml 子目录名 → <b>主题壁纸列表略缩图</b>的绝对路径（用户手动选择；须落在 {@code .ai_wallpaper} 目录树内）。
 *
 * <p>{@code maml/…/preview/preview_rearscreen_0.png} <b>不是</b> 绑定目标，只是 MiRoot 换视频/换主题后生成的新预览；若已绑定列表略缩图，
 * 会把该预览 <b>覆盖拷贝到绑定路径</b>，以更新主题商店列表里的封面。
 */
public final class DirectoryCoverBindingHelper {

    private static final String PREFS_NAME = "miroot_directory_cover_bindings";
    private static final String KEY_JSON = "bindings_json";

    private DirectoryCoverBindingHelper() {}

    private static JSONObject loadJson(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = sp.getString(KEY_JSON, "{}");
        try {
            return new JSONObject(json);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static void saveJson(Context ctx, JSONObject o) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_JSON, o.toString()).apply();
    }

    public static String getBoundCoverPath(Context ctx, String directory) {
        if (ctx == null || directory == null || directory.isEmpty()) {
            return null;
        }
        JSONObject o = loadJson(ctx);
        if (!o.has(directory)) {
            return null;
        }
        String p = o.optString(directory, "");
        return p.isEmpty() ? null : p;
    }

    public static void setBinding(Context ctx, String directory, String pngAbsolutePath) {
        if (ctx == null || directory == null || pngAbsolutePath == null) {
            return;
        }
        JSONObject o = loadJson(ctx);
        try {
            o.put(directory, pngAbsolutePath);
        } catch (JSONException ignored) {
            return;
        }
        saveJson(ctx, o);
    }

    public static void removeBinding(Context ctx, String directory) {
        if (ctx == null || directory == null) {
            return;
        }
        JSONObject o = loadJson(ctx);
        o.remove(directory);
        saveJson(ctx, o);
    }

    /**
     * 将 MiRoot 已写入的 {@code preview_rearscreen_0.png} 覆盖到<b>已绑定的主题列表略缩图</b>（非绑定 preview_rearscreen_0 本身）。
     */
    public static boolean syncBoundCoverFromRearscreenPreview(
            Context ctx, ITaskService taskService, String directory) {
        if (ctx == null || taskService == null || directory == null || directory.isEmpty()) {
            return false;
        }
        String bound = getBoundCoverPath(ctx, directory);
        if (bound == null || bound.isEmpty()) {
            return false;
        }
        String src =
                AiWallpaperThemeHelper.resolveExistingMamlPreviewRearscreen0Path(
                        taskService, directory);
        if (src == null) {
            LogHelper.d(
                    "DirCoverBind",
                    "sync skip: preview missing for " + directory);
            return false;
        }
        try {
            File bf = new File(bound);
            File parent = bf.getParentFile();
            if (parent != null) {
                String p = parent.getAbsolutePath();
                taskService.executeShellCommand("mkdir -p \"" + p + "\"");
            }
            boolean cp =
                    taskService.executeShellCommand(
                            "cp -f \"" + src + "\" \"" + bound + "\"");
            if (!cp) {
                taskService.executeShellCommand(
                        "cat \"" + src + "\" > \"" + bound + "\"");
            }
            String verify =
                    taskService.executeShellCommandWithResult(
                            "test -f \"" + bound + "\" && echo ok || echo no");
            if (verify != null && verify.contains("ok")) {
                LogHelper.d(
                        "DirCoverBind",
                        "sync bound cover from preview: " + directory);
                return true;
            }
        } catch (RemoteException e) {
            LogHelper.w("DirCoverBind", "syncBoundCoverFromRearscreenPreview failed", e);
        }
        return false;
    }
}
