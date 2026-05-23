package com.wmqc.miroot.theme;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.RemoteException;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import androidx.annotation.Nullable;

import com.wmqc.miroot.lyrics.ITaskService;
import com.wmqc.miroot.lyrics.LogHelper;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * AI 壁纸目录扫描、视频预览加载、主题包读取与 rearscreen 替换。
 * 依赖 {@link ITaskService} 执行 shell（Root / Shizuku）。
 */
public final class AiWallpaperThemeHelper {

    private static final String TAG = "AiWallpaperTheme";

    /**
     * 略缩图磁盘缓存：同一路径用 {@link #previewStripe} 串行化，不同路径可并行拉取；{@link
     * #clearAiWallpaperPreviewThumbnailCaches} 通过 {@link #runWithAllPreviewStripesLocked} 独占全部条，
     * 避免清理与读写竞态。单一路径上仍避免并发写入同一 {@code preview_*.png} 缓存文件。
     */
    private static final int PREVIEW_CACHE_STRIPE_COUNT = 32;

    private static final Object[] PREVIEW_CACHE_STRIPES = new Object[PREVIEW_CACHE_STRIPE_COUNT];

    static {
        for (int i = 0; i < PREVIEW_CACHE_STRIPE_COUNT; i++) {
            PREVIEW_CACHE_STRIPES[i] = new Object();
        }
    }

    private static Object previewStripe(String imagePath) {
        int h = imagePath != null ? imagePath.hashCode() : 0;
        return PREVIEW_CACHE_STRIPES[(h & Integer.MAX_VALUE) % PREVIEW_CACHE_STRIPE_COUNT];
    }

    private static void runWithAllPreviewStripesLocked(Runnable r) {
        runWithAllPreviewStripesLockedImpl(0, r);
    }

    private static void runWithAllPreviewStripesLockedImpl(int idx, Runnable r) {
        if (idx >= PREVIEW_CACHE_STRIPE_COUNT) {
            r.run();
            return;
        }
        synchronized (PREVIEW_CACHE_STRIPES[idx]) {
            runWithAllPreviewStripesLockedImpl(idx + 1, r);
        }
    }

    public static final String AI_MAML_BASE =
            "/sdcard/Android/data/com.android.thememanager/files/MIUI/.ai_wallpaper/maml/";

    /** 与 {@link #AI_MAML_BASE} 同目录，便于 root {@code test -f} 在不同挂载名下命中。 */
    private static final String[] AI_MAML_BASES =
            new String[] {
                AI_MAML_BASE,
                "/storage/emulated/0/Android/data/com.android.thememanager/files/MIUI/.ai_wallpaper/maml/",
                "/data/media/0/Android/data/com.android.thememanager/files/MIUI/.ai_wallpaper/maml/",
            };

    /**
     * 路径是否落在任意已知挂载名下的 {@code …/maml/{directory}/} 之下（含子目录中的文件）。
     */
    public static boolean isPathUnderAiMamlSubDirectory(String absolutePath, String directory) {
        if (absolutePath == null || directory == null || directory.isEmpty()) {
            return false;
        }
        for (String base : AI_MAML_BASES) {
            if (absolutePath.startsWith(base + directory + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * MiRoot 写入的背屏预览 {@code preview_rearscreen_0.png}。换视频后用它作为源，覆盖拷贝到
     * {@link DirectoryCoverBindingHelper} 里用户手动绑定的列表略缩图路径。
     */
    @Nullable
    public static String resolveExistingMamlPreviewRearscreen0Path(
            ITaskService taskService, String directory) {
        if (taskService == null || directory == null || directory.isEmpty()) {
            return null;
        }
        String tail = directory + "/preview/preview_rearscreen_0.png";
        for (String base : AI_MAML_BASES) {
            String path = base + tail;
            if (rootFileExists(taskService, path)) {
                LogHelper.dDebug(
                        "DirCoverBind",
                        "preview0 exists: " + LogHelper.truncateForLog(path, 220));
                return path;
            }
        }
        LogHelper.dDebug(
                "DirCoverBind",
                "preview0 missing for mamlDir="
                        + directory
                        + " triedTail="
                        + LogHelper.truncateForLog(tail, 160));
        return null;
    }

    private static final String COVER_BIND_AI_WALLPAPER_PREFIX =
            "/sdcard/Android/data/com.android.thememanager/files/MIUI/.ai_wallpaper";
    private static final String COVER_BIND_AI_WALLPAPER_PREFIX_ALT =
            "/storage/emulated/0/Android/data/com.android.thememanager/files/MIUI/.ai_wallpaper";
    /** 与主存 {@code /sdcard/…} 等价的 {@code /data/media/0/…} 前缀。 */
    private static final String COVER_BIND_AI_WALLPAPER_PREFIX_DATA_MEDIA =
            "/data/media/0/Android/data/com.android.thememanager/files/MIUI/.ai_wallpaper";

    private static final String[] COVER_BIND_AI_WALLPAPER_ROOTS =
            new String[] {
                COVER_BIND_AI_WALLPAPER_PREFIX,
                COVER_BIND_AI_WALLPAPER_PREFIX_ALT,
                COVER_BIND_AI_WALLPAPER_PREFIX_DATA_MEDIA,
            };

    private static boolean isUnderAiWallpaperBindTree(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        for (String root : COVER_BIND_AI_WALLPAPER_ROOTS) {
            if (path.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    /** 主题 zip 根目录效果图文件名（与主题商店约定一致） */
    public static final String THEME_EFFECT_PNG_ENTRY = "effect.png";
    /** 主题注入、SAF 解压等临时 zip 目录（可安全整体按策略清理） */
    public static final String THEME_TEMP_DIR = "/sdcard/MiRoot/theme_temp/";
    /** 视频替换解压与中间文件目录 */
    public static final String VIDEO_REPLACE_DIR = "/sdcard/MiRoot/video_replace/";

    /** @deprecated 使用 {@link #THEME_TEMP_DIR} */
    @Deprecated
    static final String THEME_METADATA_WORK_DIR = THEME_TEMP_DIR;
    private static final String VIDEO_REPLACE_WORK = VIDEO_REPLACE_DIR;

    private AiWallpaperThemeHelper() {}

    /**
     * 背屏主题包多为 zip，扩展名可能是 .zip / .mrc / .bin / .mtz 等；按路径解析可读文件。
     */
    @Nullable
    private static File resolveThemeArchiveFile(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        File f = new File(path);
        if (f.isFile() && f.exists()) {
            return f;
        }
        f = new File(path + ".zip");
        if (f.isFile() && f.exists()) {
            return f;
        }
        return null;
    }

    /**
     * 打开主题压缩包。API 24+ 优先 UTF-8 中央目录（部分 .mrc 使用中文文件名），失败再回退默认编码。
     */
    public static ZipFile openZipFileForTheme(File file) throws IOException {
        if (file == null || !file.exists() || !file.isFile()) {
            throw new IOException("theme file missing");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                return new ZipFile(file, StandardCharsets.UTF_8);
            } catch (ZipException e) {
                LogHelper.w(TAG, "openZipFileForTheme utf-8 fallback", e);
            }
        }
        return new ZipFile(file);
    }

    @Nullable
    private static String queryUriDisplayName(Context context, Uri uri) {
        try (android.database.Cursor c =
                context.getContentResolver()
                        .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    String n = c.getString(i);
                    if (n != null && !n.isEmpty()) {
                        return n;
                    }
                }
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "queryUriDisplayName", e);
        }
        return null;
    }

    /** 与 {@link ZipFile} 行为不一致时的兜底：顺序扫描条目读取 var_config.xml */
    @Nullable
    private static Map<String, String> readVarConfigFromZipInputStream(File file) {
        try (FileInputStream fis = new FileInputStream(file);
                BufferedInputStream bis = new BufferedInputStream(fis);
                ZipInputStream zis = new ZipInputStream(bis)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (!"var_config.xml".equals(entry.getName())) {
                    continue;
                }
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                try {
                    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                } catch (Exception ignored) {
                }
                DocumentBuilder builder = factory.newDocumentBuilder();
                Document doc = builder.parse(zis);
                Element root = doc.getDocumentElement();
                if (root == null || !"WidgetConfig".equalsIgnoreCase(root.getNodeName())) {
                    return null;
                }
                NamedNodeMap attributes = root.getAttributes();
                Map<String, String> config = new HashMap<>();
                putWidgetConfigFieldsIntoMap(config, attributes);
                return config;
            }
            return new HashMap<>();
        } catch (Exception e) {
            LogHelper.e(TAG, "readVarConfigFromZipInputStream", e);
            return null;
        }
    }

    public static boolean isUnderAiWallpaperPng(String path) {
        if (path == null) {
            return false;
        }
        if (!path.toLowerCase(Locale.ROOT).endsWith(".png")) {
            return false;
        }
        return isUnderAiWallpaperBindTree(path);
    }

    public static void openThemeManager(Context context) {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(
                    "com.android.thememanager",
                    "com.rearScreen.RearScreenNewListActivity"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            LogHelper.e(TAG, "openThemeManager failed", e);
        }
    }

    /** 优先 {@code su -c}（读他应用 /data/data），失败再回退 Shizuku/原 shell。 */
    private static String shellResultRootFirst(ITaskService ts, String cmd) {
        if (ts == null) {
            return "";
        }
        try {
            String r = ts.executeShellCommandWithResultAsRoot(cmd);
            if (r != null && !r.trim().isEmpty()) {
                return r;
            }
        } catch (RemoteException e) {
            LogHelper.w(TAG, "shellResultRootFirst asRoot", e);
        }
        try {
            String r2 = ts.executeShellCommandWithResult(cmd);
            return r2 != null ? r2 : "";
        } catch (RemoteException e) {
            LogHelper.w(TAG, "shellResultRootFirst fallback", e);
            return "";
        }
    }

    private static boolean shellCommandRootFirst(ITaskService ts, String cmd) {
        if (ts == null) {
            return false;
        }
        try {
            if (ts.executeShellCommandAsRoot(cmd)) {
                return true;
            }
        } catch (RemoteException e) {
            LogHelper.w(TAG, "shellCommandRootFirst asRoot", e);
        }
        try {
            return ts.executeShellCommand(cmd);
        } catch (RemoteException e) {
            LogHelper.w(TAG, "shellCommandRootFirst fallback", e);
            return false;
        }
    }

    private static boolean rootFileExists(ITaskService taskService, String path) {
        if (taskService == null || path == null || path.isEmpty() || path.contains("..")) {
            return false;
        }
        String r =
                shellResultRootFirst(
                        taskService, "test -f \"" + path + "\" && echo ok || echo no");
        return r.trim().equals("ok");
    }

    /**
     * 扫描 {@code maml/} 下 AI 壁纸目录；{@code coverBindPath} 恒为空（兼容旧 Map 结构），封面仅支持用户手动绑定。
     */
    public static List<Map<String, String>> scanAiWallpaperDirectories(ITaskService taskService) {
        List<Map<String, String>> directories = new ArrayList<>();
        if (taskService == null) {
            return directories;
        }
        try {
            String checkBaseCmd = "test -d \"" + AI_MAML_BASE + "\" && echo exists || echo not_exists";
            String baseCheck = shellResultRootFirst(taskService, checkBaseCmd);
            if (baseCheck == null || !baseCheck.trim().equals("exists")) {
                return directories;
            }
            String cmd = "find \"" + AI_MAML_BASE + "\" -maxdepth 2 -type f -name 'rearscreen' 2>/dev/null";
            String output = shellResultRootFirst(taskService, cmd);
            if (output == null || output.trim().isEmpty()) {
                return directories;
            }
            Set<String> processedDirs = new HashSet<>();
            for (String line : output.trim().split("\n")) {
                String fullPath = line.trim();
                if (fullPath.isEmpty()) {
                    continue;
                }
                String dirName = null;
                if (fullPath.contains("/maml/")) {
                    String[] parts = fullPath.split("/maml/");
                    if (parts.length > 1) {
                        String afterMaml = parts[1];
                        String[] dirParts = afterMaml.split("/");
                        if (dirParts.length > 0) {
                            dirName = dirParts[0];
                        }
                    }
                }
                if (dirName == null
                        || dirName.isEmpty()
                        || dirName.equals(".")
                        || dirName.equals("..")
                        || processedDirs.contains(dirName)) {
                    continue;
                }
                processedDirs.add(dirName);
                Map<String, String> dirInfo = new HashMap<>();
                dirInfo.put("name", dirName);
                String previewPath =
                        resolveExistingMamlPreviewRearscreen0Path(taskService, dirName);
                if (previewPath == null) {
                    previewPath = "";
                }
                dirInfo.put("previewPath", previewPath);
                dirInfo.put("coverBindPath", "");
                directories.add(dirInfo);
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "scanAiWallpaperDirectories", e);
        }
        return directories;
    }


    public static List<String> listAiWallpaperRootPngs(ITaskService taskService) {
        List<String> list = new ArrayList<>();
        if (taskService == null) {
            return list;
        }
        try {
            String cmd =
                    "find \""
                            + COVER_BIND_AI_WALLPAPER_PREFIX
                            + "\" -mindepth 1 -maxdepth 1 -type f -name \"*.png\" 2>/dev/null | sort";
            String out = shellResultRootFirst(taskService,cmd);
            if (out != null && !out.trim().isEmpty()) {
                for (String line : out.trim().split("\n")) {
                    String t = line.trim();
                    if (!t.isEmpty() && t.toLowerCase(Locale.ROOT).endsWith(".png")) {
                        list.add(t);
                    }
                }
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "listAiWallpaperRootPngs", e);
        }
        return list;
    }

    public static boolean deleteDirectory(ITaskService taskService, String directory) {
        if (taskService == null || directory == null || directory.isEmpty()) {
            return false;
        }
        try {
            String targetDir = AI_MAML_BASE + directory;
            String checkCmd = "test -d \"" + targetDir + "\" && echo 'exists' || echo 'not found'";
            String checkResult = shellResultRootFirst(taskService,checkCmd);
            if (checkResult == null || !checkResult.contains("exists")) {
                return false;
            }
            if (!shellCommandRootFirst(taskService,"rm -rf \"" + targetDir + "\"")) {
                return false;
            }
            String verifyCmd = "test -d \"" + targetDir + "\" && echo 'exists' || echo 'not found'";
            String verifyResult = shellResultRootFirst(taskService,verifyCmd);
            return verifyResult == null || !verifyResult.contains("exists");
        } catch (Exception e) {
            LogHelper.e(TAG, "deleteDirectory", e);
            return false;
        }
    }

    @Nullable
    public static byte[] loadPreviewImageBytes(Context context, ITaskService taskService, String imagePath) {
        if (context == null || taskService == null || imagePath == null || imagePath.isEmpty()) {
            return null;
        }
        synchronized (previewStripe(imagePath)) {
            return loadPreviewImageBytesLocked(context, taskService, imagePath);
        }
    }

    /** @return 长度为 2：{@code [0]} 字节大小、{@code [1]} 秒级 mtime；失败为 {@code null} */
    @Nullable
    private static long[] remoteFileSizeAndMtimeSeconds(ITaskService taskService, String imagePath) {
        String[] cmds =
                new String[] {
                    "stat -c '%s %Y' \"" + imagePath + "\" 2>/dev/null",
                    "stat -c \"%s %Y\" \"" + imagePath + "\" 2>/dev/null",
                    "busybox stat -c '%s %Y' \"" + imagePath + "\" 2>/dev/null",
                };
        for (String cmd : cmds) {
            String out = shellResultRootFirst(taskService, cmd);
            if (out == null) {
                continue;
            }
            String t = out.trim();
            if (t.isEmpty()) {
                continue;
            }
            String[] parts = t.split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            try {
                long size = Long.parseLong(parts[0].trim());
                long mtime = Long.parseLong(parts[1].trim());
                if (size > 0 && mtime > 0) {
                    return new long[] {size, mtime};
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    @Nullable
    private static byte[] loadPreviewImageBytesLocked(Context context, ITaskService taskService, String imagePath) {
        try {
            String checkFileCmd = "test -f \"" + imagePath + "\" && echo 'exists' || echo 'not found'";
            String checkResult = shellResultRootFirst(taskService, checkFileCmd);
            if (checkResult == null || !checkResult.contains("exists")) {
                return null;
            }
            long[] sm = remoteFileSizeAndMtimeSeconds(taskService, imagePath);
            long fileSize = 0;
            long mtimeSec = -1L;
            if (sm != null) {
                fileSize = sm[0];
                mtimeSec = sm[1];
            } else {
                String[] sizeCmds =
                        new String[] {
                            "stat -c %s \"" + imagePath + "\" 2>/dev/null",
                            "stat -c%s \"" + imagePath + "\" 2>/dev/null",
                            "busybox stat -c %s \"" + imagePath + "\" 2>/dev/null",
                        };
                for (String sizeCmd : sizeCmds) {
                    String sizeResult = shellResultRootFirst(taskService, sizeCmd);
                    if (sizeResult == null || sizeResult.trim().isEmpty()) {
                        continue;
                    }
                    String token = sizeResult.trim().split("\\s+")[0];
                    try {
                        fileSize = Long.parseLong(token);
                    } catch (NumberFormatException ignored) {
                        fileSize = 0;
                    }
                    if (fileSize > 0) {
                        break;
                    }
                }
            }
            boolean sizeKnown = fileSize > 0;
            String fileName = "preview_" + Math.abs(imagePath.hashCode()) + ".png";
            File cacheDir = context.getCacheDir();
            File cachedFile = new File(cacheDir, fileName);
            boolean mtimeKnown = mtimeSec > 0;
            boolean cacheMtimeMatches =
                    !mtimeKnown
                            || (cachedFile.exists()
                                    && Math.abs(cachedFile.lastModified() / 1000L - mtimeSec) <= 1L);
            boolean useCache =
                    sizeKnown
                            && cachedFile.exists()
                            && cachedFile.length() == fileSize
                            && cachedFile.length() > 0
                            && cacheMtimeMatches;
            if (!useCache) {
                String tempDir =
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                                .getAbsolutePath();
                String tempFileName =
                        "temp_preview_"
                                + System.currentTimeMillis()
                                + "_"
                                + Math.abs(imagePath.hashCode())
                                + ".png";
                String tempFilePath = tempDir + "/" + tempFileName;
                boolean copyOk =
                        shellCommandRootFirst(taskService, "cp \"" + imagePath + "\" \"" + tempFilePath + "\"");
                if (!copyOk) {
                    copyOk =
                            shellCommandRootFirst(taskService, "cat \"" + imagePath + "\" > \"" + tempFilePath + "\"");
                }
                if (!copyOk) {
                    return null;
                }
                File tempFile = new File(tempFilePath);
                if (!tempFile.exists()) {
                    return null;
                }
                long actualSize = tempFile.length();
                if (actualSize <= 0) {
                    //noinspection ResultOfMethodCallIgnored
                    tempFile.delete();
                    return null;
                }
                if (sizeKnown && actualSize != fileSize) {
                    //noinspection ResultOfMethodCallIgnored
                    tempFile.delete();
                    return null;
                }
                try (InputStream in = new FileInputStream(tempFile);
                        FileOutputStream out = new FileOutputStream(cachedFile)) {
                    byte[] buffer = new byte[4096];
                    int length;
                    while ((length = in.read(buffer)) > 0) {
                        out.write(buffer, 0, length);
                    }
                }
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
                if (cachedFile.length() <= 0
                        || (sizeKnown && cachedFile.length() != fileSize)) {
                    //noinspection ResultOfMethodCallIgnored
                    cachedFile.delete();
                    return null;
                }
                if (mtimeKnown) {
                    //noinspection ResultOfMethodCallIgnored
                    cachedFile.setLastModified(mtimeSec * 1000L);
                }
            }
            try (FileInputStream fis = new FileInputStream(cachedFile)) {
                int readSize = (int) cachedFile.length();
                byte[] imageBytes = new byte[readSize];
                int total = 0;
                int n;
                while (total < readSize && (n = fis.read(imageBytes, total, readSize - total)) != -1) {
                    total += n;
                }
                if (total == readSize && readSize > 0) {
                    return imageBytes;
                }
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "loadPreviewImageBytes", e);
        }
        return null;
    }

    /**
     * 清除 {@link #loadPreviewImageBytes} 在 app cacheDir 下按路径 hash 缓存的副本，
     * 在远端同路径文件被覆盖后应调用，否则会沿用旧缩略图（尤其文件大小未变时）。
     */
    public static void invalidatePreviewImageCache(@Nullable Context context, @Nullable String imagePath) {
        if (context == null || imagePath == null || imagePath.isEmpty()) {
            return;
        }
        synchronized (previewStripe(imagePath)) {
            try {
                String fileName = "preview_" + Math.abs(imagePath.hashCode()) + ".png";
                File cachedFile = new File(context.getCacheDir(), fileName);
                if (cachedFile.isFile()) {
                    //noinspection ResultOfMethodCallIgnored
                    cachedFile.delete();
                }
            } catch (Exception e) {
                LogHelper.w(TAG, "invalidatePreviewImageCache", e);
            }
        }
    }

    /**
     * 使某 AI maml 目录下 {@code preview/preview_rearscreen_0.png} 在所有已知挂载前缀上的略缩图磁盘缓存失效
     * （与 {@link #resolveExistingMamlPreviewRearscreen0Path} 可能返回的任一路径一致）。
     */
    public static void invalidateAiDirectoryRearscreenPreviewCaches(
            @Nullable Context context, @Nullable String directory) {
        if (context == null || directory == null || directory.isEmpty()) {
            return;
        }
        String tail = directory + "/preview/preview_rearscreen_0.png";
        for (String base : AI_MAML_BASES) {
            invalidatePreviewImageCache(context, base + tail);
        }
    }

    /**
     * 删除 {@link #loadPreviewImageBytes} 产生的本地缩略图缓存：cacheDir 下 {@code preview_*.png}，
     * 以及下载目录中可能残留的 {@code temp_preview_*.png} 中转文件。
     */
    public static void clearAiWallpaperPreviewThumbnailCaches(@Nullable Context context) {
        if (context == null) {
            return;
        }
        runWithAllPreviewStripesLocked(() -> clearAiWallpaperPreviewThumbnailCachesLocked(context));
    }

    private static void clearAiWallpaperPreviewThumbnailCachesLocked(@Nullable Context context) {
        try {
            File cacheDir = context.getCacheDir();
            if (cacheDir != null && cacheDir.isDirectory()) {
                File[] cacheFiles = cacheDir.listFiles();
                if (cacheFiles != null) {
                    for (File f : cacheFiles) {
                        if (!f.isFile()) {
                            continue;
                        }
                        String n = f.getName();
                        if (n.startsWith("preview_") && n.endsWith(".png")) {
                            //noinspection ResultOfMethodCallIgnored
                            f.delete();
                        }
                    }
                }
            }
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (downloads != null && downloads.isDirectory()) {
                File[] tempPreviews =
                        downloads.listFiles(
                                (dir, name) ->
                                        name != null
                                                && name.startsWith("temp_preview_")
                                                && name.endsWith(".png"));
                if (tempPreviews != null) {
                    for (File f : tempPreviews) {
                        if (f.isFile()) {
                            //noinspection ResultOfMethodCallIgnored
                            f.delete();
                        }
                    }
                }
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "clearAiWallpaperPreviewThumbnailCaches", e);
        }
    }

    @Nullable
    public static Map<String, String> readVarConfigFromZip(String zipFilePath) {
        File file = resolveThemeArchiveFile(zipFilePath);
        if (file == null) {
            return null;
        }
        ZipFile zipFile = null;
        InputStream xmlInputStream = null;
        try {
            zipFile = openZipFileForTheme(file);
            ZipEntry configEntry = zipFile.getEntry("var_config.xml");
            if (configEntry == null) {
                return new HashMap<>();
            }
            xmlInputStream = zipFile.getInputStream(configEntry);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            try {
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            } catch (Exception ignored) {
            }
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(xmlInputStream);
            Element root = doc.getDocumentElement();
            if (root == null || !"WidgetConfig".equalsIgnoreCase(root.getNodeName())) {
                return null;
            }
            NamedNodeMap attributes = root.getAttributes();
            Map<String, String> config = new HashMap<>();
            putWidgetConfigFieldsIntoMap(config, attributes);
            return config;
        } catch (IOException e) {
            LogHelper.w(TAG, "readVarConfigFromZip ZipFile, try ZipInputStream", e);
            return readVarConfigFromZipInputStream(file);
        } catch (Exception e) {
            LogHelper.e(TAG, "readVarConfigFromZip", e);
            return null;
        } finally {
            if (xmlInputStream != null) {
                try {
                    xmlInputStream.close();
                } catch (IOException ignored) {
                }
            }
            if (zipFile != null) {
                try {
                    zipFile.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String attr(NamedNodeMap attributes, String name, String def) {
        if (attributes == null) {
            return def;
        }
        org.w3c.dom.Node node = attributes.getNamedItem(name);
        return node != null ? node.getNodeValue() : def;
    }

    @Nullable
    private static String attrTrimmedOrNull(NamedNodeMap attributes, String name) {
        if (attributes == null) {
            return null;
        }
        org.w3c.dom.Node node = attributes.getNamedItem(name);
        if (node == null) {
            return null;
        }
        String v = node.getNodeValue();
        if (v == null) {
            return null;
        }
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    /**
     * 官方 var_config 常用 {@code des} 表示说明；部分包使用 {@code description}，编辑页统一映射到「主题信息」。
     */
    static String readWidgetConfigDescription(NamedNodeMap attributes) {
        String d = attrTrimmedOrNull(attributes, "description");
        if (d != null) {
            return d;
        }
        d = attrTrimmedOrNull(attributes, "des");
        if (d != null) {
            return d;
        }
        return "无描述";
    }

    private static void putWidgetConfigFieldsIntoMap(Map<String, String> config, NamedNodeMap attributes) {
        config.put("author", attr(attributes, "author", "未知"));
        config.put("name", attr(attributes, "name", "未知"));
        config.put("description", readWidgetConfigDescription(attributes));
    }

    @Nullable
    public static byte[] readThemePreviewImage(Context context, String themeFileRef) {
        try {
            File inputZipFile = resolveZipFileForRead(context, themeFileRef, "theme_preview_read_" + System.currentTimeMillis());
            if (inputZipFile == null || !inputZipFile.exists()) {
                return null;
            }
            ZipFile zipFile = null;
            try {
                zipFile = openZipFileForTheme(inputZipFile);
                ZipEntry entry = zipFile.getEntry(THEME_EFFECT_PNG_ENTRY);
                if (entry == null || entry.isDirectory()) {
                    return null;
                }
                try (InputStream is = zipFile.getInputStream(entry)) {
                    return loadBitmapAsJpegBytesFromStream(is);
                }
            } finally {
                if (zipFile != null) {
                    try {
                        zipFile.close();
                    } catch (IOException ignored) {
                    }
                }
                deleteThemeMetadataTempSourceZipQuietly(inputZipFile);
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "readThemePreviewImage", e);
            return null;
        }
    }

    /**
     * 读取主题包内 effect.png 原始字节（用于注入时保留原图，或写入背屏预览）。
     */
    @Nullable
    public static byte[] readEffectPngBytesFromThemeZip(Context context, String themeFileRef) {
        try {
            return readEffectPngRawBytesFromThemeZip(context, themeFileRef);
        } catch (Exception e) {
            LogHelper.w(TAG, "readEffectPngBytesFromThemeZip", e);
            return null;
        }
    }

    @Nullable
    private static byte[] readEffectPngRawBytesFromThemeZip(Context context, String themeFileRef) throws Exception {
        File inputZipFile = resolveZipFileForRead(context, themeFileRef, "theme_effect_raw_" + System.currentTimeMillis());
        if (inputZipFile == null || !inputZipFile.exists()) {
            return null;
        }
        ZipFile zipFile = null;
        try {
            zipFile = openZipFileForTheme(inputZipFile);
            ZipEntry entry = zipFile.getEntry(THEME_EFFECT_PNG_ENTRY);
            if (entry == null || entry.isDirectory()) {
                return null;
            }
            try (InputStream is = zipFile.getInputStream(entry)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    baos.write(buf, 0, n);
                }
                return baos.toByteArray();
            }
        } catch (IOException e) {
            LogHelper.w(TAG, "readEffectPngRawBytesFromThemeZip", e);
            return null;
        } finally {
            if (zipFile != null) {
                try {
                    zipFile.close();
                } catch (IOException ignored) {
                }
            }
            deleteThemeMetadataTempSourceZipQuietly(inputZipFile);
        }
    }

    public static void deleteThemeMetadataTempSourceZipQuietly(File f) {
        if (f == null || !f.exists() || !f.isFile()) {
            return;
        }
        try {
            String abs = f.getAbsolutePath();
            if (!abs.startsWith(THEME_METADATA_WORK_DIR)) {
                return;
            }
            String n = f.getName();
            if (n.startsWith("theme_preview_read_")
                    || n.startsWith("theme_effect_raw_")
                    || n.startsWith("theme_input_")
                    || n.startsWith("gesture_zip_in_")
                    || n.startsWith("gesture_zip_out_")) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Exception ignored) {
        }
    }

    /** 将主题 zip（路径或 content Uri）拷到 {@link #THEME_TEMP_DIR} 供本地处理；用毕可 {@link #deleteThemeMetadataTempSourceZipQuietly}。 */
    public static File resolveThemeZipForGestureRead(Context context, String themeFileRef)
            throws Exception {
        return resolveZipFileForRead(
                context, themeFileRef, "gesture_zip_in_" + System.currentTimeMillis());
    }

    @Nullable
    static File resolveZipFileForRead(Context context, String themeFileRef, String tempName)
            throws Exception {
        if (themeFileRef == null || themeFileRef.isEmpty()) {
            return null;
        }
        if (themeFileRef.startsWith("/")) {
            File f = new File(themeFileRef);
            if (!f.exists()) {
                f = new File(themeFileRef + ".zip");
            }
            if (f.exists() && f.isFile()) {
                return f;
            }
        }
        if (themeFileRef.startsWith("content://")) {
            Uri uri = Uri.parse(themeFileRef);
            if (uri == null) {
                return null;
            }
            File outFolder = new File(THEME_METADATA_WORK_DIR);
            if (!outFolder.exists()) {
                //noinspection ResultOfMethodCallIgnored
                outFolder.mkdirs();
            }
            File tmpZip = new File(THEME_METADATA_WORK_DIR + tempName + ".zip");
            try (InputStream in = context.getContentResolver().openInputStream(uri);
                    FileOutputStream fos = new FileOutputStream(tmpZip)) {
                if (in == null) {
                    return null;
                }
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) > 0) {
                    fos.write(buffer, 0, len);
                }
            }
            if (tmpZip.exists() && tmpZip.length() > 0) {
                return tmpZip;
            }
        }
        return null;
    }

    @Nullable
    private static byte[] loadBitmapAsJpegBytesFromStream(InputStream in) throws Exception {
        if (in == null) {
            return null;
        }
        // 先完整读入内存做两段 decode（bounds + sampled），避免 decode 原始超大图造成峰值内存与纹理上传压力
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            raw.write(buf, 0, n);
        }
        byte[] data = raw.toByteArray();
        if (data.length == 0) {
            return null;
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }

        int maxDim = 480;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.RGB_565; // 预览图：更省内存即可
        opts.inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxDim, maxDim);
        Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        if (bmp == null) {
            return null;
        }

        int width = bmp.getWidth();
        int height = bmp.getHeight();
        if (width <= 0 || height <= 0) {
            return null;
        }

        Bitmap scaled = bmp;
        if (width > maxDim || height > maxDim) {
            float scale = (float) maxDim / (float) Math.max(width, height);
            int targetW = Math.max(1, Math.round(width * scale));
            int targetH = Math.max(1, Math.round(height * scale));
            scaled = Bitmap.createScaledBitmap(bmp, targetW, targetH, true);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, baos);
        byte[] bytes = baos.toByteArray();
        return (bytes != null && bytes.length > 0) ? bytes : null;
    }

    private static int computeInSampleSize(
            int srcWidth, int srcHeight, int reqWidth, int reqHeight) {
        int inSampleSize = 1;
        if (srcHeight > reqHeight || srcWidth > reqWidth) {
            int halfHeight = srcHeight / 2;
            int halfWidth = srcWidth / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return Math.max(1, inSampleSize);
    }

    private static void applyThemeZipEffectToAiPreviews(
            Context context, ITaskService taskService, String directory, String themeFilePath) {
        if (taskService == null || directory == null || themeFilePath == null) {
            return;
        }
        try {
            byte[] raw = readEffectPngRawBytesFromThemeZip(context, themeFilePath);
            if (raw == null || raw.length == 0) {
                return;
            }
            shellCommandRootFirst(taskService,"mkdir -p \"" + VIDEO_REPLACE_WORK + "\"");
            String tmp = VIDEO_REPLACE_WORK + "theme_effect_apply_" + System.currentTimeMillis() + ".png";
            File out = new File(tmp);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(raw);
            }
            if (VideoReplacer.copySourcePngToRearscreenPreviews(taskService, directory, tmp)) {
                try {
                    boolean coverSyncOk =
                            DirectoryCoverBindingHelper.syncBoundCoverFromRearscreenPreview(
                                    context, taskService, directory);
                    LogHelper.dDebug(
                            "DirCoverBind",
                            "after theme effect preview write: dir="
                                    + directory
                                    + " boundCoverSyncOk="
                                    + coverSyncOk);
                } catch (Exception e) {
                    LogHelper.w(TAG, "syncBoundCover", e);
                }
            }
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        } catch (Exception e) {
            LogHelper.w(TAG, "applyThemeZipEffectToAiPreviews", e);
        }
    }

    public static boolean replaceThemeFile(
            Context context, ITaskService taskService, String directory, String themeFilePath) {
        if (taskService == null || directory == null || themeFilePath == null) {
            return false;
        }
        try {
            String targetDir = AI_MAML_BASE + directory + "/";
            String rearscreenFile = targetDir + "rearscreen";
            File themeFile = new File(themeFilePath);
            if (!themeFile.exists() || !themeFile.isFile() || themeFile.length() == 0) {
                return false;
            }
            boolean success =
                    replaceFileWithBackup(taskService, rearscreenFile, themeFilePath, true);
            if (success) {
                applyThemeZipEffectToAiPreviews(context, taskService, directory, themeFilePath);
            }
            return success;
        } catch (Exception e) {
            LogHelper.e(TAG, "replaceThemeFile", e);
            return false;
        }
    }

    /**
     * 替换任意可读写的绝对路径文件（如背屏 widget 已应用的 .mrc），逻辑同 AI 目录 rearscreen 替换。
     */
    public static boolean replaceRootOwnedFile(
            ITaskService taskService, String targetPath, String newSourcePath, boolean keepBackup) {
        if (taskService == null || targetPath == null || newSourcePath == null) {
            return false;
        }
        return replaceFileWithBackup(taskService, targetPath, newSourcePath, keepBackup);
    }

    /**
     * 将新主题包内的 effect.png 写入背屏条目对应的 snapshot（文件或目录内首张图），用于更新主题列表缩略图。
     */
    public static boolean updateRearSnapshotFromThemeZip(
            Context context, ITaskService taskService, String themeFilePath, String snapshotPath) {
        if (context == null || taskService == null || themeFilePath == null || snapshotPath == null
                || snapshotPath.isEmpty()) {
            return false;
        }
        try {
            byte[] raw = readEffectPngBytesFromThemeZip(context, themeFilePath);
            if (raw == null || raw.length == 0) {
                return false;
            }
            shellCommandRootFirst(taskService,"mkdir -p \"" + VIDEO_REPLACE_WORK + "\"");
            String tmp = VIDEO_REPLACE_WORK + "rear_snapshot_" + System.currentTimeMillis() + ".png";
            File out = new File(tmp);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(raw);
            }
            if (!out.exists() || out.length() == 0) {
                return false;
            }
            boolean ok = pushTempPngToRearSnapshot(taskService, tmp, snapshotPath);
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            return ok;
        } catch (Exception e) {
            LogHelper.w(TAG, "updateRearSnapshotFromThemeZip", e);
            return false;
        }
    }

    /**
     * 用所选视频的预览帧更新背屏 widget 缩略图（与 {@link VideoReplacer} 抽帧策略一致）。
     * 一般优先使用 {@link #updateRearSnapshotFromAiWallpaperPreviewRearscreen0}，与 AI 目录 preview 一致。
     */
    public static boolean updateRearSnapshotFromVideoFrame(
            Context context, ITaskService taskService, String videoPath, String snapshotPath) {
        if (context == null || taskService == null || videoPath == null || snapshotPath == null
                || videoPath.isEmpty() || snapshotPath.isEmpty()) {
            return false;
        }
        try {
            shellCommandRootFirst(taskService,"mkdir -p \"" + VIDEO_REPLACE_WORK + "\"");
            String tmp = VIDEO_REPLACE_WORK + "rear_vid_snap_" + System.currentTimeMillis() + ".png";
            if (!VideoReplacer.extractPreviewFrameToPng(videoPath, tmp)) {
                return false;
            }
            boolean ok = pushTempPngToRearSnapshot(taskService, tmp, snapshotPath);
            //noinspection ResultOfMethodCallIgnored
            new File(tmp).delete();
            return ok;
        } catch (Exception e) {
            LogHelper.w(TAG, "updateRearSnapshotFromVideoFrame", e);
            return false;
        }
    }

    /**
     * 使用 AI 目录下 {@code preview/preview_rearscreen_0.png} 更新背屏缩略图（与
     * {@link VideoReplacer#copySourcePngToRearscreenPreviews} 写入的预览为同一文件）。
     * 视频替换流程中该预览异步写入，会轮询等待；仍不可用时若提供 {@code fallbackVideoPath} 则再抽帧。
     */
    public static boolean updateRearSnapshotFromAiWallpaperPreviewRearscreen0(
            @Nullable Context context,
            ITaskService taskService,
            String aiDirectoryName,
            String snapshotPath,
            @Nullable String fallbackVideoPath) {
        if (taskService == null || aiDirectoryName == null || aiDirectoryName.isEmpty()
                || snapshotPath == null || snapshotPath.isEmpty()) {
            return false;
        }
        if (copyAiPreviewRearscreen0ToSnapshot(taskService, aiDirectoryName, snapshotPath)) {
            return true;
        }
        if (context != null && fallbackVideoPath != null && !fallbackVideoPath.isEmpty()) {
            return updateRearSnapshotFromVideoFrame(context, taskService, fallbackVideoPath, snapshotPath);
        }
        return false;
    }

    private static boolean copyAiPreviewRearscreen0ToSnapshot(
            ITaskService taskService, String aiDirectoryName, String snapshotPath) {
        String previewPngPath = AI_MAML_BASE + aiDirectoryName + "/preview/preview_rearscreen_0.png";
        try {
            for (int i = 0; i < 30; i++) {
                String check =
                        shellResultRootFirst(taskService,
                                "test -f \"" + previewPngPath + "\" && echo ok || echo no");
                if (check != null && check.trim().equals("ok")) {
                    break;
                }
                if (i == 29) {
                    return false;
                }
                Thread.sleep(100);
            }
            shellCommandRootFirst(taskService,"mkdir -p \"" + VIDEO_REPLACE_WORK + "\"");
            String tmp = VIDEO_REPLACE_WORK + "rear_prev_snap_" + System.currentTimeMillis() + ".png";
            if (!shellCommandRootFirst(taskService,"cp \"" + previewPngPath + "\" \"" + tmp + "\"")) {
                return false;
            }
            boolean ok = pushTempPngToRearSnapshot(taskService, tmp, snapshotPath);
            shellCommandRootFirst(taskService,"rm -f \"" + tmp + "\"");
            return ok;
        } catch (Exception e) {
            LogHelper.w(TAG, "copyAiPreviewRearscreen0ToSnapshot", e);
            return false;
        }
    }

    private static boolean pushTempPngToRearSnapshot(
            ITaskService taskService, String tmpPngPath, String snapshotPath) {
        if (taskService == null || tmpPngPath == null || snapshotPath == null || snapshotPath.isEmpty()) {
            return false;
        }
        try {
            File tmpFile = new File(tmpPngPath);
            if (!tmpFile.isFile() || tmpFile.length() <= 0) {
                return false;
            }
            String isDirRaw =
                    shellResultRootFirst(taskService,
                            "if [ -d \"" + snapshotPath + "\" ]; then echo dir; "
                                    + "elif [ -f \"" + snapshotPath + "\" ]; then echo file; "
                                    + "else echo miss; fi");
            String isDir = isDirRaw != null ? isDirRaw.trim() : "miss";
            boolean ok;
            if ("dir".equals(isDir)) {
                String first =
                        shellResultRootFirst(taskService,
                                "find \"" + snapshotPath + "\" -maxdepth 1 -type f "
                                        + "\\( -name '*.png' -o -name '*.jpg' -o -name '*.jpeg' "
                                        + "-o -name '*.webp' \\) 2>/dev/null | head -n 1");
                if (first == null || first.trim().isEmpty()) {
                    ok = shellCommandRootFirst(taskService,
                            "cp \"" + tmpPngPath + "\" \"" + snapshotPath + "/thumb.png\"");
                } else {
                    String dest = first.trim().split("\n")[0];
                    ok = shellCommandRootFirst(taskService,"cp \"" + tmpPngPath + "\" \"" + dest + "\"");
                }
            } else {
                String parent = new File(snapshotPath).getParent();
                if (parent != null && !parent.isEmpty()) {
                    shellCommandRootFirst(taskService,"mkdir -p \"" + parent + "\"");
                }
                ok = shellCommandRootFirst(taskService,"cp \"" + tmpPngPath + "\" \"" + snapshotPath + "\"");
            }
            return ok;
        } catch (Exception e) {
            LogHelper.w(TAG, "pushTempPngToRearSnapshot", e);
            return false;
        }
    }

    private static boolean replaceFileWithBackup(
            ITaskService taskService, String originalFile, String newFile, boolean keepBackup) {
        try {
            String checkNewFileCmd = "test -f \"" + newFile + "\" && stat -c%s \"" + newFile + "\" || echo '0'";
            String checkNewFileResult = shellResultRootFirst(taskService,checkNewFileCmd);
            if (checkNewFileResult == null || checkNewFileResult.trim().equals("0")) {
                return false;
            }
            String backupFile = originalFile + ".backup";
            String checkBackupCmd = "test -f \"" + backupFile + "\" && echo 'exists' || echo 'not found'";
            String checkBackupResult = shellResultRootFirst(taskService,checkBackupCmd);
            if (checkBackupResult == null || !checkBackupResult.contains("exists")) {
                shellCommandRootFirst(taskService,"cp \"" + originalFile + "\" \"" + backupFile + "\"");
            }
            boolean success = shellCommandRootFirst(taskService,"cp \"" + newFile + "\" \"" + originalFile + "\"");
            if (!success) {
                return false;
            }
            Thread.sleep(100);
            String verifyCmd = "test -f \"" + originalFile + "\" && stat -c%s \"" + originalFile + "\" || echo '0'";
            String verifyResult = shellResultRootFirst(taskService,verifyCmd);
            if (verifyResult == null || verifyResult.trim().equals("0")) {
                String backupExists =
                        shellResultRootFirst(taskService,
                                "test -f \"" + backupFile + "\" && echo exists || echo not_exists");
                if (backupExists != null && backupExists.trim().equals("exists")) {
                    shellCommandRootFirst(taskService,"cp \"" + backupFile + "\" \"" + originalFile + "\"");
                }
                return false;
            }
            long newFileSize = Long.parseLong(checkNewFileResult.trim());
            long replacedFileSize = Long.parseLong(verifyResult.trim());
            if (replacedFileSize != newFileSize) {
                shellCommandRootFirst(taskService,"cp \"" + newFile + "\" \"" + originalFile + "\"");
                Thread.sleep(100);
                verifyResult = shellResultRootFirst(taskService,verifyCmd);
                if (verifyResult == null || verifyResult.trim().equals("0")) {
                    return false;
                }
                replacedFileSize = Long.parseLong(verifyResult.trim());
                if (replacedFileSize != newFileSize) {
                    return false;
                }
            }
            if (!keepBackup) {
                shellCommandRootFirst(taskService,"rm -f \"" + backupFile + "\"");
            }
            return true;
        } catch (Exception e) {
            LogHelper.e(TAG, "replaceFileWithBackup", e);
            return false;
        }
    }

    /** content:// 或文档 URI 转为可读绝对路径（失败则返回原串）。 */
    public static String resolvePickedFilePath(Context context, String ref) {
        if (ref == null || ref.isEmpty()) {
            return null;
        }
        if (ref.startsWith("/")) {
            File file = new File(ref);
            if (file.exists() && file.isFile()) {
                return ref;
            }
        }
        if (!ref.startsWith("content://")) {
            return ref;
        }
        try {
            Uri uri = Uri.parse(ref);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && DocumentsContract.isDocumentUri(context, uri)) {
                String docId = DocumentsContract.getDocumentId(uri);
                String authority = uri.getAuthority();
                if ("com.android.externalstorage.documents".equals(authority)) {
                    String[] split = docId.split(":");
                    if (split.length >= 2 && "primary".equalsIgnoreCase(split[0])) {
                        String fullPath = Environment.getExternalStorageDirectory() + "/" + split[1];
                        File file = new File(fullPath);
                        if (file.exists() && file.isFile()) {
                            return fullPath;
                        }
                    }
                } else if ("com.android.providers.downloads.documents".equals(authority)) {
                    if (docId.startsWith("raw:")) {
                        String fullPath = docId.substring(4);
                        File file = new File(fullPath);
                        if (file.exists() && file.isFile()) {
                            return fullPath;
                        }
                    }
                } else if ("com.android.providers.media.documents".equals(authority)) {
                    String[] split = docId.split(":");
                    if (split.length >= 2) {
                        String type = split[0];
                        String id = split[1];
                        Uri contentUri = null;
                        if ("image".equals(type)) {
                            contentUri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                        } else if ("video".equals(type)) {
                            contentUri = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                        }
                        if (contentUri != null) {
                            contentUri = android.content.ContentUris.withAppendedId(contentUri, Long.parseLong(id));
                            String realPath = queryMediaDataPath(context, contentUri);
                            if (realPath != null) {
                                return realPath;
                            }
                        }
                    }
                }
            }
            String realPath = queryMediaDataPath(context, uri);
            if (realPath != null) {
                return realPath;
            }
            String tempPath = copyUriToCache(context, uri);
            if (tempPath != null) {
                return tempPath;
            }
            return ref;
        } catch (Exception e) {
            LogHelper.e(TAG, "resolvePickedFilePath", e);
            return ref;
        }
    }

    @Nullable
    private static String queryMediaDataPath(Context context, Uri uri) {
        try {
            String[] projection = {
                android.provider.MediaStore.Video.Media.DATA,
                android.provider.MediaStore.Images.Media.DATA,
                android.provider.MediaStore.Files.FileColumns.DATA
            };
            try (android.database.Cursor cursor =
                    context.getContentResolver().query(uri, projection, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    for (String columnName : projection) {
                        int columnIndex = cursor.getColumnIndex(columnName);
                        if (columnIndex >= 0) {
                            String realPath = cursor.getString(columnIndex);
                            if (realPath != null && !realPath.isEmpty()) {
                                File realFile = new File(realPath);
                                if (realFile.exists()) {
                                    return realPath;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "queryMediaDataPath", e);
        }
        return null;
    }

    @Nullable
    private static String copyUriToCache(Context context, Uri uri) {
        try (InputStream inputStream = context.getContentResolver().openInputStream(uri)) {
            if (inputStream == null) {
                return null;
            }
            String fileName = "theme_pick_" + System.currentTimeMillis();
            String mimeType = context.getContentResolver().getType(uri);
            String displayName = queryUriDisplayName(context, uri);
            String nameLower = displayName != null ? displayName.toLowerCase(Locale.ROOT) : "";
            boolean nameLooksLikeThemeArchive =
                    nameLower.endsWith(".mrc")
                            || nameLower.endsWith(".bin")
                            || nameLower.endsWith(".mtz")
                            || nameLower.endsWith(".mrm")
                            || nameLower.endsWith(".zip");
            boolean octetStream =
                    mimeType != null && "application/octet-stream".equalsIgnoreCase(mimeType.trim());
            if (mimeType != null) {
                if (mimeType.startsWith("video/")) {
                    fileName += ".mp4";
                } else if (mimeType.startsWith("image/")) {
                    fileName += ".jpg";
                } else if (mimeType.contains("zip")
                        || mimeType.contains("compressed")
                        || nameLooksLikeThemeArchive
                        || octetStream) {
                    // .mrc/.bin 等与 zip 同源，落盘为 .zip 便于后续统一按压缩包处理
                    fileName += ".zip";
                } else {
                    fileName += ".bin";
                }
            } else {
                fileName += nameLooksLikeThemeArchive ? ".zip" : ".tmp";
            }
            File tempFile = new File(context.getCacheDir(), fileName);
            try (FileOutputStream outputStream = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = inputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, length);
                }
            }
            if (tempFile.exists() && tempFile.length() > 0) {
                return tempFile.getAbsolutePath();
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "copyUriToCache", e);
        }
        return null;
    }
}
