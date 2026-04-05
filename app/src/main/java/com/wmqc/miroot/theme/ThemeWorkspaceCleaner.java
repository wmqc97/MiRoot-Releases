package com.wmqc.miroot.theme;

import android.os.RemoteException;

import com.wmqc.miroot.lyrics.ITaskService;

/**
 * 清理 {@link AiWallpaperThemeHelper#THEME_TEMP_DIR} 与 {@link AiWallpaperThemeHelper#VIDEO_REPLACE_DIR}
 * 下的注入临时 zip、视频替换解压目录与中间文件，避免长期占用存储。
 */
public final class ThemeWorkspaceCleaner {

    private ThemeWorkspaceCleaner() {}

    /**
     * 用户手动「清理缓存」：删除已知临时项（不删 AI maml 目录等其它路径）。
     *
     * @return 是否 shell 整体执行成功（部分文件不存在仍可能返回 true）
     */
    public static boolean cleanAllManual(ITaskService taskService) {
        if (taskService == null) {
            return false;
        }
        String theme = AiWallpaperThemeHelper.THEME_TEMP_DIR;
        String video = AiWallpaperThemeHelper.VIDEO_REPLACE_DIR;
        // glob 不可加引号，否则部分 shell 不展开
        String cmd =
                "rm -f "
                        + theme
                        + "theme_injected_*.zip "
                        + theme
                        + "theme_input_*.zip "
                        + theme
                        + "theme_preview_read_*.zip "
                        + theme
                        + "theme_effect_raw_*.zip 2>/dev/null; "
                        + "rm -rf \""
                        + video
                        + ".extracted\" \""
                        + video
                        + "format_check\" 2>/dev/null; "
                        + "rm -f \""
                        + video
                        + "rearscreen_temp\" \""
                        + video
                        + "rearscreen_new\" \""
                        + video
                        + "rearscreen_new.zip\" \""
                        + video
                        + "preview_frame.png\" 2>/dev/null; "
                        + "rm -f "
                        + video
                        + "temp_source_video_*.mp4 "
                        + video
                        + "theme_effect_apply_*.png 2>/dev/null; "
                        + "true";
        try {
            return taskService.executeShellCommand(cmd);
        } catch (RemoteException e) {
            return false;
        }
    }
}
