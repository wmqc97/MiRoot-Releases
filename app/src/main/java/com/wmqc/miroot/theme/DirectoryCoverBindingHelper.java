package com.wmqc.miroot.theme;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.RemoteException;

import com.wmqc.miroot.lyrics.ITaskService;
import com.wmqc.miroot.lyrics.LogHelper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.Iterator;

/**
 * AI 壁纸 maml 子目录名 → <b>主题壁纸列表略缩图</b>的绝对路径（用户手动选择；须落在 {@code .ai_wallpaper} 目录树内）。
 *
 * <p>{@code maml/…/preview/preview_rearscreen_0.png} <b>不是</b> 绑定目标，只是 MiRoot 换视频/换主题后生成的新预览；若已绑定列表略缩图，
 * 会把该预览 <b>覆盖拷贝到绑定路径</b>，以更新主题商店列表里的封面。
 */
public final class DirectoryCoverBindingHelper {

    private static final String TAG = "DirCoverBind";
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

    /** 是否存在任意 maml 目录 → 列表封面路径绑定。 */
    public static boolean hasAnyBinding(Context ctx) {
        if (ctx == null) {
            return false;
        }
        JSONObject o = loadJson(ctx);
        return o.length() > 0;
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
        LogHelper.dDebug(
                TAG,
                "setBinding dir="
                        + directory
                        + " cover="
                        + LogHelper.truncateForLog(pngAbsolutePath, 240));
    }

    public static void removeBinding(Context ctx, String directory) {
        if (ctx == null || directory == null) {
            return;
        }
        JSONObject o = loadJson(ctx);
        o.remove(directory);
        saveJson(ctx, o);
        LogHelper.dDebug(TAG, "removeBinding dir=" + directory);
    }

    /**
     * 绑定封面候选：排除已被其它 maml 目录占用的目标路径，以及位于「已绑定封面」的其它 maml 子目录下的 PNG，
     * 避免误选其它主题目录内素材；正在配置的 {@code configuringMamlDir} 不受「本目录下」限制。
     */
    public static boolean isUsableCoverCandidateForBinding(
            Context ctx, String configuringMamlDir, String candidatePngPath) {
        if (ctx == null || candidatePngPath == null || candidatePngPath.isEmpty()) {
            return false;
        }
        JSONObject o = loadJson(ctx);
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String dir = it.next();
            if (configuringMamlDir != null && configuringMamlDir.equals(dir)) {
                continue;
            }
            String bound = o.optString(dir, "");
            if (bound.isEmpty()) {
                continue;
            }
            if (bound.equals(candidatePngPath)) {
                return false;
            }
            if (AiWallpaperThemeHelper.isPathUnderAiMamlSubDirectory(candidatePngPath, dir)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 将 MiRoot 已写入的 {@code preview_rearscreen_0.png} 覆盖到<b>已绑定的主题列表略缩图</b>（非绑定 preview_rearscreen_0 本身）。
     */
    public static boolean syncBoundCoverFromRearscreenPreview(
            Context ctx, ITaskService taskService, String directory) {
        if (ctx == null || taskService == null || directory == null || directory.isEmpty()) {
            LogHelper.dDebug(
                    TAG,
                    "sync skip: badArgs ctx="
                            + (ctx != null)
                            + " taskService="
                            + (taskService != null)
                            + " directory="
                            + directory);
            return false;
        }
        String bound = getBoundCoverPath(ctx, directory);
        if (bound == null || bound.isEmpty()) {
            JSONObject o = loadJson(ctx);
            LogHelper.dDebug(
                    TAG,
                    "sync skip: no binding for mamlDir="
                            + directory
                            + " bindingsCount="
                            + o.length()
                            + " hasKey="
                            + o.has(directory));
            return false;
        }
        LogHelper.dDebug(
                TAG,
                "sync begin mamlDir="
                        + directory
                        + " bound="
                        + LogHelper.truncateForLog(bound, 240));
        String src =
                AiWallpaperThemeHelper.resolveExistingMamlPreviewRearscreen0Path(
                        taskService, directory);
        if (src == null) {
            LogHelper.d(
                    TAG,
                    "sync skip: preview missing for " + directory);
            LogHelper.dDebug(TAG, "sync abort: preview_rearscreen_0 not found under any base for " + directory);
            return false;
        }
        LogHelper.dDebug(TAG, "sync src preview=" + LogHelper.truncateForLog(src, 240));
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
            LogHelper.dDebug(TAG, "sync cp -f result=" + cp);
            if (!cp) {
                boolean catOk =
                        taskService.executeShellCommand(
                                "cat \"" + src + "\" > \"" + bound + "\"");
                LogHelper.dDebug(TAG, "sync cat fallback result=" + catOk);
            }
            String verify =
                    taskService.executeShellCommandWithResult(
                            "test -f \"" + bound + "\" && echo ok || echo no");
            LogHelper.dDebug(
                    TAG,
                    "sync verify test -f bound: " + (verify == null ? "(null)" : verify.trim()));
            if (verify != null && verify.contains("ok")) {
                LogHelper.d(
                        TAG,
                        "sync bound cover from preview: " + directory);
                return true;
            }
            LogHelper.dDebug(TAG, "sync fail: verify did not contain ok for bound path");
        } catch (RemoteException e) {
            LogHelper.w(TAG, "syncBoundCoverFromRearscreenPreview failed", e);
        }
        return false;
    }
}
