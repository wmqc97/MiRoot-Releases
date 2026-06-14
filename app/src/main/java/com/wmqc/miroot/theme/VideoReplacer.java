/*
 * 视频替换工具类 - 优化版本
 * 提取公共方法，减少重复代码，改进错误处理
 */

package com.wmqc.miroot.theme;

import com.wmqc.miroot.lyrics.ITaskService;
import com.wmqc.miroot.lyrics.LogHelper;
import android.media.MediaMetadataRetriever;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class VideoReplacer {
    private static final String TAG = "VideoReplacer";
    
    // 路径常量
    private static final String BASE_DIR = "/sdcard/Android/data/com.android.thememanager/files/MIUI/.ai_wallpaper/maml/";

    /**
     * 预览封面取第 N 帧（从 0 开始）
     * 之前固定取第一帧(时间戳 0)，这里改为可调整帧号
     */
    private static final int PREVIEW_COVER_FRAME_INDEX = 20;
    private static final float PREVIEW_COVER_FPS_FALLBACK = 30f;
    
    /**
     * 获取视频替换工作目录路径。
     * @return 工作目录路径，以 / 结尾
     */
    private String getWorkDir() {
        return "/sdcard/MiRoot/video_replace/";
    }
    
    /**
     * 获取解压临时目录路径（.extracted）
     * @return 解压目录路径，以 / 结尾
     */
    private String getExtractDir() {
        return getWorkDir() + ".extracted/";
    }
    
    // 文件大小常量
    private static final int BUFFER_SIZE = 8192;  // 8KB 缓冲区（用于文件复制）
    private static final int ZIP_BUFFER_SIZE = 4096;  // 4KB 缓冲区（用于ZIP压缩）
    private static final int LARGE_FILE_BUFFER_SIZE = 65536;  // 64KB 缓冲区（用于大文件）
    private static final long LARGE_FILE_THRESHOLD = 10 * 1024 * 1024;  // 10MB（大文件阈值）
    private static final long MIN_VIDEO_SIZE = 1024;  // 最小视频文件大小（1KB）
    
    // 等待时间常量（毫秒）- 优化：减少等待时间
    private static final int FILE_WRITE_WAIT_MS = 200;
    private static final int FILE_VERIFY_WAIT_MS = 100;
    private static final int PREVIEW_REPLACE_WAIT_MS = 50;
    private static final int PREVIEW_DELETE_WAIT_MS = 30;
    
    // 文件扩展名
    private static final String VIDEO_EXT = ".mp4";
    private static final String PNG_EXT = ".png";
    private static final String ZIP_EXT = ".zip";
    
    private final ITaskService taskService;
    private final ProgressCallback progressCallback;
    private final Context context;
    
    public interface ProgressCallback {
        void onProgress(String message);
    }
    
    public VideoReplacer(ITaskService taskService, ProgressCallback callback, Context context) {
        this.taskService = taskService;
        this.progressCallback = callback;
        this.context = context;
    }

    private boolean shellCmd(String cmd) {
        return AiWallpaperThemeHelper.themeShellCommand(taskService, cmd);
    }

    private String shellResult(String cmd) {
        return AiWallpaperThemeHelper.themeShellResult(taskService, cmd);
    }

    private void afterAppWrite(String path) {
        AiWallpaperThemeHelper.ensureShellReadable(taskService, path);
    }

    private static boolean shellCmd(ITaskService ts, String cmd) {
        return AiWallpaperThemeHelper.themeShellCommand(ts, cmd);
    }

    private static String shellResult(ITaskService ts, String cmd) {
        return AiWallpaperThemeHelper.themeShellResult(ts, cmd);
    }
    
    /**
     * 准备解压临时文件（在用户选择目录时调用）
     * @param directory 目录名称
     * @return true表示准备成功或已存在，false表示失败
     */
    public boolean prepareExtractedFiles(String directory) {
        try {
            LogHelper.d(TAG, "准备临时文件 - 目录: " + directory);
            
            // 构建路径
            String targetDir = BASE_DIR + directory + "/";
            String rearscreenFile = targetDir + "rearscreen";
            
            // 检查临时目录是否已有解压文件
            if (checkExistingExtractedFiles()) {
                LogHelper.d(TAG, "临时文件已存在，跳过准备");
                return true;
            }
            
            // 准备工作目录
            if (!prepareWorkDirectory()) {
                LogHelper.e(TAG, "创建工作目录失败");
                return false;
            }
            
            // 准备底包并解压：优先 rearscreen.bak，否则从含 assets/ai/ 的 rearscreen 创建 .bak
            String tempRearscreen = getWorkDir() + "rearscreen_temp";
            String baseRearscreen =
                    AiWallpaperThemeHelper.resolveVideoReplaceBaseRearscreen(taskService, rearscreenFile);
            if (baseRearscreen == null) {
                LogHelper.e(TAG, "无可用视频底包（需 rearscreen.bak 或含 assets/ai/ 的 rearscreen）");
                return false;
            }
            LogHelper.d(TAG, "使用视频底包: " + baseRearscreen);
            if (!AiWallpaperThemeHelper.themeShellCommand(taskService,
                    "cp \"" + baseRearscreen + "\" \"" + tempRearscreen + "\"")) {
                LogHelper.e(TAG, "复制视频底包到工作目录失败");
                return false;
            }
            
            // 解压文件
            if (!extractRearscreenFile(tempRearscreen, tempRearscreen)) {
                LogHelper.e(TAG, "解压rearscreen文件失败");
                return false;
            }
            
            LogHelper.d(TAG, "临时文件准备完成");
            return true;
            
        } catch (Exception e) {
            LogHelper.e(TAG, "准备临时文件失败", e);
            return false;
        }
    }
    
    /**
     * 执行视频替换流程（简化版：假设临时文件已准备好）
     * @param directory 目录名称
     * @param videoPath 视频文件路径（应该已经是实际文件路径，不是 content:// URI）
     * @param loopEnabled 是否循环播放
     * @param soundEnabled 是否启用声音
     * @param volume 音量（0-100）
     */
    public boolean replaceVideo(String directory, String videoPath, boolean loopEnabled, boolean soundEnabled, int volume) {
        try {
            // 验证参数
            if (directory == null || videoPath == null || directory.isEmpty() || videoPath.isEmpty()) {
                throw new Exception("目录和视频路径不能为空");
            }
            
            LogHelper.d(TAG, "开始视频替换 - 目录: " + directory + ", 视频路径: " + videoPath);
            
            // 构建路径
            String targetDir = BASE_DIR + directory + "/";
            String rearscreenFile = targetDir + "rearscreen";
            
            // 确保临时文件已准备好
            if (!checkExistingExtractedFiles()) {
                throw new Exception("临时文件未准备，请先选择目录");
            }
            
            // 复制视频到临时文件夹
            String tempVideoPath = getExtractDir() + "assets/ai/ai.mp4";
            File videoFile = new File(videoPath);
            boolean javaApiExists = videoFile.exists() && videoFile.isFile() && videoFile.length() > 0;
            
            // 先删除目标文件（如果存在）
            try {
                File targetFile = new File(tempVideoPath);
                if (targetFile.exists()) {
                    targetFile.delete();
                }
            } catch (Exception e) {
                LogHelper.w(TAG, "删除旧视频文件失败", e);
            }
            
            String actualVideoPath;
            if (javaApiExists) {
                // 使用Java API复制
                if (copyFileWithJava(videoPath, tempVideoPath)) {
                    LogHelper.d(TAG, "视频已复制到临时文件夹");
                    actualVideoPath = tempVideoPath;
                } else {
                    // 如果Java复制失败，使用Shell命令
                    if (!replaceVideoFile(videoPath, tempVideoPath)) {
                        throw new Exception("复制视频失败");
                    }
                    actualVideoPath = tempVideoPath;
                }
            } else {
                // 使用Shell命令复制
                if (!replaceVideoFile(videoPath, tempVideoPath)) {
                    throw new Exception("复制视频失败");
                }
                actualVideoPath = tempVideoPath;
            }
            
            // 验证视频文件
            if (!validateVideoFile(actualVideoPath)) {
                throw new Exception("视频文件验证失败");
            }
            
            // 修改循环设置和声音设置
            String manifestFile = getExtractDir() + "manifest.xml";
            if (!updateManifestLoop(manifestFile, loopEnabled)) {
                LogHelper.w(TAG, "修改循环设置失败，继续执行");
            }
            // 修改声音设置
            if (!updateManifestSound(manifestFile, soundEnabled, volume)) {
                LogHelper.w(TAG, "修改声音设置失败，继续执行");
            }
            
            // 提取并替换封面图片
            new Thread(() -> {
                try {
                    updatePreviewImage(directory, actualVideoPath);
                } catch (Exception e) {
                    LogHelper.w(TAG, "更新预览图失败（不影响替换结果）", e);
                }
            }).start();
            
            // 压缩并替换
            String newRearscreen = getWorkDir() + "rearscreen_new";
            if (!compressDirectory(getExtractDir(), newRearscreen)) {
                throw new Exception("压缩失败");
            }
            
            if (!replaceOriginalFile(rearscreenFile, newRearscreen)) {
                throw new Exception("替换失败");
            }
            
            // 不再清理临时文件，保留在工作目录（见 getWorkDir）中
            // 注释掉清理操作，保留临时文件以便后续使用
            // new Thread(() -> {
            //     try {
            //         Thread.sleep(2000);
            //         cleanup();
            //     } catch (InterruptedException e) {
            //         Thread.currentThread().interrupt();
            //     }
            // }).start();
            
            updateProgress("替换成功");
            return true;
            
        } catch (Exception e) {
            LogHelper.e(TAG, "═══════════════════════════════════════");
            LogHelper.e(TAG, "❌ 视频替换失败", e);
            LogHelper.e(TAG, "失败原因: " + e.getMessage());
            if (e.getCause() != null) {
                LogHelper.e(TAG, "根本原因: " + e.getCause().getMessage());
            }
            LogHelper.e(TAG, "堆栈跟踪:");
            e.printStackTrace();
            LogHelper.e(TAG, "═══════════════════════════════════════");
            updateProgress("视频替换失败: " + e.getMessage());
            // 不再清理临时文件，保留以便调试
            // cleanup();
            return false;
        }
    }
    
    /**
     * 准备工作目录
     */
    private boolean prepareWorkDirectory() {
        return AiWallpaperThemeHelper.prepareThemeShellWorkDirs(taskService);
    }
    
    /**
     * 验证视频文件是否存在
     */
    private boolean validateVideoFile(String videoPath) {
        try {
            // 验证视频文件（此时应该已经是可访问的路径）
            boolean videoExists = false;
            // 先尝试使用 Java File API 检查
            File videoFile = new File(videoPath);
            boolean javaApiExists = videoFile.exists() && videoFile.isFile() && videoFile.length() > 0;
            LogHelper.d(TAG, "使用Java API检查视频文件: " + videoPath + ", 存在: " + javaApiExists + ", 大小: " + (javaApiExists ? videoFile.length() : 0));
            
            if (javaApiExists) {
                // Java API 可以访问，验证通过
                videoExists = true;
            } else {
                // Java API 无法访问，尝试使用 Shell 命令（仅用于外部路径）
                if (videoPath.startsWith("/data/user/") || videoPath.startsWith("/data/data/") || videoPath.startsWith("/data/cache/")) {
                    // 应用内部路径，Shell 无法访问
                    LogHelper.e(TAG, "应用内部路径且Java API无法访问: " + videoPath);
                    videoExists = false;
                } else {
                    // 外部路径，使用 Shell 命令
                    String checkVideoCmd = "test -f \"" + videoPath + "\" && echo 'exists' || echo 'not found'";
                    String checkVideoResult = AiWallpaperThemeHelper.themeShellResult(taskService,checkVideoCmd);
                    videoExists = (checkVideoResult != null && checkVideoResult.contains("exists"));
                    LogHelper.d(TAG, "使用Shell命令检查视频文件: " + videoPath + ", 结果: " + checkVideoResult);
                }
            }
            
            if (!videoExists) {
                LogHelper.e(TAG, "源视频文件不存在: " + videoPath);
                return false;
            }
            
            return true;
        } catch (Exception e) {
            LogHelper.e(TAG, "验证视频文件失败", e);
            return false;
        }
    }
    
    /**
     * 从assets复制rearscreen底包到工作目录
     */
    private boolean copyRearscreenFromAssets(String targetPath) {
        if (context == null) {
            LogHelper.e(TAG, "Context为空，无法从assets读取文件");
            return false;
        }
        
        InputStream inputStream = null;
        FileOutputStream outputStream = null;
        
        try {
            LogHelper.d(TAG, "开始从assets复制rearscreen底包");
            
            // 尝试多个可能的路径
            String[] possiblePaths = {
                "flutter_assets/assets/rearscreen",
                "assets/rearscreen",
                "rearscreen"
            };
            
            // 尝试加载文件
            for (String path : possiblePaths) {
                try {
                    inputStream = context.getAssets().open(path);
                    LogHelper.d(TAG, "✅ 成功从路径加载: " + path);
                    break;
                } catch (Exception e) {
                    LogHelper.d(TAG, "路径不存在: " + path);
                }
            }
            
            if (inputStream == null) {
                LogHelper.e(TAG, "无法从assets加载rearscreen文件");
                return false;
            }
            
            // 确保目标目录存在
            File targetFile = new File(targetPath);
            File parentDir = targetFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            
            // 复制文件
            outputStream = new FileOutputStream(targetFile);
            byte[] buffer = new byte[BUFFER_SIZE];
            int length;
            long totalBytes = 0;
            
            while ((length = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, length);
                totalBytes += length;
            }
            
            outputStream.flush();
            
            // 验证文件
            if (targetFile.exists() && targetFile.length() > 0) {
                afterAppWrite(targetPath);
                LogHelper.d(TAG, "✅ rearscreen底包已从assets复制: " + targetPath + " (大小: " + targetFile.length() + " bytes)");
                return true;
            } else {
                LogHelper.e(TAG, "复制rearscreen底包失败：文件不存在或为空");
                return false;
            }
            
        } catch (Exception e) {
            LogHelper.e(TAG, "从assets复制rearscreen底包失败", e);
            return false;
        } finally {
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException e) {
                    LogHelper.w(TAG, "关闭输出流失败", e);
                }
            }
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    LogHelper.w(TAG, "关闭输入流失败", e);
                }
            }
        }
    }
    
    /**
     * 解压 rearscreen 文件（增强版：包含详细的验证）
     */
    private boolean extractRearscreenFile(String sourceFile, String tempFile) {
        try {
            LogHelper.d(TAG, "═══════════════════════════════════════");
            LogHelper.d(TAG, "开始解压rearscreen文件");
            LogHelper.d(TAG, "源文件: " + sourceFile);
            LogHelper.d(TAG, "临时文件: " + tempFile);
            
            // 如果源文件和临时文件是同一个，不需要复制
            boolean needCopy = !sourceFile.equals(tempFile);
            
            if (needCopy) {
                // 复制文件到工作目录
                LogHelper.d(TAG, "复制rearscreen文件到工作目录...");
                if (!AiWallpaperThemeHelper.themeShellCommand(taskService,"cp \"" + sourceFile + "\" \"" + tempFile + "\"")) {
                    LogHelper.e(TAG, "❌ 复制rearscreen文件到工作目录失败");
                    return false;
                }
                
                // 验证复制成功
                String verifyCmd = "test -f \"" + tempFile + "\" && stat -c%s \"" + tempFile + "\" || echo '0'";
                String verifyResult = AiWallpaperThemeHelper.themeShellResult(taskService,verifyCmd);
                LogHelper.d(TAG, "临时文件验证结果: " + verifyResult);
                
                if (verifyResult == null || verifyResult.trim().equals("0")) {
                    LogHelper.e(TAG, "❌ 临时rearscreen文件复制失败或文件为空");
                    return false;
                }
                LogHelper.d(TAG, "✅ 文件复制成功，大小: " + verifyResult.trim() + " bytes");
            } else {
                // 验证源文件是否存在
                String verifyCmd = "test -f \"" + sourceFile + "\" && stat -c%s \"" + sourceFile + "\" || echo '0'";
                String verifyResult = AiWallpaperThemeHelper.themeShellResult(taskService,verifyCmd);
                LogHelper.d(TAG, "源文件验证结果: " + verifyResult);
                
                if (verifyResult == null || verifyResult.trim().equals("0")) {
                    LogHelper.e(TAG, "❌ 源rearscreen文件不存在或为空");
                    return false;
                }
                LogHelper.d(TAG, "✅ 源文件存在，大小: " + verifyResult.trim() + " bytes");
            }
            
            // 创建解压目录
            String extractDir = getExtractDir();
            LogHelper.d(TAG, "创建解压目录: " + extractDir);
            if (!AiWallpaperThemeHelper.themeShellCommand(taskService,"mkdir -p \"" + extractDir + "\"")) {
                LogHelper.e(TAG, "❌ 创建解压目录失败");
                return false;
            }
            
            // 清理解压目录（如果已存在）
            AiWallpaperThemeHelper.themeShellCommand(taskService,"rm -rf \"" + extractDir + "*\"");
            
            // 解压 ZIP 文件
            LogHelper.d(TAG, "开始解压ZIP文件...");
            String unzipCmd = "cd \"" + extractDir + "\" && unzip -q -o \"" + tempFile + "\"";
            LogHelper.d(TAG, "解压命令: " + unzipCmd);
            boolean unzipSuccess = AiWallpaperThemeHelper.themeShellCommand(taskService,unzipCmd);
            LogHelper.d(TAG, "解压命令执行结果: " + unzipSuccess);
            
            if (!unzipSuccess) {
                LogHelper.e(TAG, "❌ 解压rearscreen文件失败");
                // 尝试获取解压错误信息
                String unzipError = AiWallpaperThemeHelper.themeShellResult(taskService,"cd \"" + extractDir + "\" && unzip -o \"" + tempFile + "\" 2>&1");
                LogHelper.e(TAG, "解压错误信息: " + (unzipError != null ? unzipError : "无错误信息"));
                return false;
            }
            
            // 等待解压完成（优化：减少等待时间）
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // 验证解压结果：检查关键文件是否存在
            String manifestCheck = "test -f \"" + extractDir + "manifest.xml\" && echo 'exists' || echo 'not found'";
            String manifestResult = AiWallpaperThemeHelper.themeShellResult(taskService,manifestCheck);
            LogHelper.d(TAG, "manifest.xml验证结果: " + manifestResult);
            
            if (manifestResult == null || !manifestResult.contains("exists")) {
                LogHelper.e(TAG, "❌ 解压后manifest.xml文件不存在，解压可能失败");
                // 列出解压目录内容以便调试
                String listCmd = "ls -la \"" + extractDir + "\"";
                String listResult = AiWallpaperThemeHelper.themeShellResult(taskService,listCmd);
                LogHelper.e(TAG, "解压目录内容: " + (listResult != null ? listResult : "无法列出"));
                return false;
            }
            
            // 检查assets目录是否存在
            String assetsCheck = "test -d \"" + extractDir + "assets\" && echo 'exists' || echo 'not found'";
            String assetsResult = AiWallpaperThemeHelper.themeShellResult(taskService,assetsCheck);
            LogHelper.d(TAG, "assets目录验证结果: " + assetsResult);
            
            LogHelper.d(TAG, "✅ rearscreen文件解压成功");
            LogHelper.d(TAG, "═══════════════════════════════════════");
            return true;
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 解压rearscreen文件失败", e);
            return false;
        }
    }
    
    /**
     * 检查临时目录是否已有解压文件
     * @return true表示已有解压文件且完整，false表示需要重新解压
     */
    private boolean checkExistingExtractedFiles() {
        try {
            String extractDir = getExtractDir();
            LogHelper.d(TAG, "检查临时目录是否已有解压文件: " + extractDir);
            
            // 检查解压目录是否存在
            String checkDirCmd = "test -d \"" + extractDir + "\" && echo 'exists' || echo 'not found'";
            String dirResult = AiWallpaperThemeHelper.themeShellResult(taskService,checkDirCmd);
            if (dirResult == null || !dirResult.contains("exists")) {
                LogHelper.d(TAG, "解压目录不存在，需要重新解压");
                return false;
            }
            
            // 检查关键文件 manifest.xml 是否存在
            String manifestCheck = "test -f \"" + extractDir + "manifest.xml\" && echo 'exists' || echo 'not found'";
            String manifestResult = AiWallpaperThemeHelper.themeShellResult(taskService,manifestCheck);
            if (manifestResult == null || !manifestResult.contains("exists")) {
                LogHelper.d(TAG, "manifest.xml不存在，需要重新解压");
                return false;
            }
            
            // 检查 assets 目录是否存在
            String assetsCheck = "test -d \"" + extractDir + "assets\" && echo 'exists' || echo 'not found'";
            String assetsResult = AiWallpaperThemeHelper.themeShellResult(taskService,assetsCheck);
            if (assetsResult == null || !assetsResult.contains("exists")) {
                LogHelper.d(TAG, "assets目录不存在，需要重新解压");
                return false;
            }
            
            // 检查 assets/ai 目录是否存在（视频替换需要此目录）
            String aiCheck = "test -d \"" + extractDir + "assets/ai\" && echo 'exists' || echo 'not found'";
            String aiResult = AiWallpaperThemeHelper.themeShellResult(taskService,aiCheck);
            if (aiResult == null || !aiResult.contains("exists")) {
                LogHelper.d(TAG, "assets/ai目录不存在，需要重新解压");
                return false;
            }
            
            LogHelper.d(TAG, "✅ 临时目录已有完整的解压文件，可以直接使用");
            return true;
        } catch (Exception e) {
            LogHelper.w(TAG, "检查临时目录失败，需要重新解压", e);
            return false;
        }
    }
    
    /**
     * 替换视频文件（增强版：包含详细的错误处理和重试机制）
     */
    private boolean replaceVideoFile(String sourceVideoPath, String targetVideoPath) {
        try {
            LogHelper.d(TAG, "═══════════════════════════════════════");
            LogHelper.d(TAG, "开始替换视频文件");
            LogHelper.d(TAG, "源文件: " + sourceVideoPath);
            LogHelper.d(TAG, "目标文件: " + targetVideoPath);
            
            // 创建视频目录
            String aiVideoDir = getExtractDir() + "assets/ai/";
            LogHelper.d(TAG, "创建视频目录: " + aiVideoDir);
            if (!AiWallpaperThemeHelper.themeShellCommand(taskService,"mkdir -p \"" + aiVideoDir + "\"")) {
                LogHelper.e(TAG, "创建视频目录失败");
                return false;
            }
            
            // 验证目录是否创建成功
            String verifyDirCmd = "test -d \"" + aiVideoDir + "\" && echo 'exists' || echo 'not found'";
            String verifyDirResult = AiWallpaperThemeHelper.themeShellResult(taskService,verifyDirCmd);
            LogHelper.d(TAG, "视频目录验证结果: " + verifyDirResult);
            
            if (verifyDirResult == null || !verifyDirResult.contains("exists")) {
                LogHelper.w(TAG, "目录创建失败，尝试逐级创建并设置权限");
                // 逐级创建目录
                String extractDir = getExtractDir();
                String assetsDir = extractDir + "assets";
                AiWallpaperThemeHelper.themeShellCommand(taskService,"mkdir -p \"" + assetsDir + "\"");
                AiWallpaperThemeHelper.themeShellCommand(taskService,"mkdir -p \"" + aiVideoDir + "\"");
                // 设置目录权限
                AiWallpaperThemeHelper.themeShellCommand(taskService,"chmod -R 777 \"" + extractDir + "\"");
                
                // 再次验证
                verifyDirResult = AiWallpaperThemeHelper.themeShellResult(taskService,verifyDirCmd);
                if (verifyDirResult == null || !verifyDirResult.contains("exists")) {
                    LogHelper.e(TAG, "无法创建视频目录: " + aiVideoDir);
                    return false;
                }
            }
            
            // 处理应用内部路径
            String actualSourcePath = sourceVideoPath;
            if (sourceVideoPath.startsWith("/data/user/") || sourceVideoPath.startsWith("/data/data/")) {
                // 先复制到临时位置
                String tempVideoPath = getWorkDir() + "temp_source_video_" + System.currentTimeMillis() + VIDEO_EXT;
                LogHelper.d(TAG, "应用内部路径，复制到临时位置: " + tempVideoPath);
                if (!copyFileWithJava(sourceVideoPath, tempVideoPath)) {
                    LogHelper.e(TAG, "复制到临时位置失败");
                    return false;
                }
                actualSourcePath = tempVideoPath;
            }
            
            // 验证源文件是否存在
            String verifySrcCmd = "test -f \"" + actualSourcePath + "\" && stat -c%s \"" + actualSourcePath + "\" || echo '0'";
            String verifySrcResult = AiWallpaperThemeHelper.themeShellResult(taskService,verifySrcCmd);
            LogHelper.d(TAG, "源文件验证结果: " + verifySrcResult);
            
            if (verifySrcResult == null || verifySrcResult.trim().equals("0")) {
                LogHelper.e(TAG, "源视频文件不存在或为空: " + actualSourcePath);
                return false;
            }
            
            // 验证目标目录是否可写
            String testWriteCmd = "touch \"" + aiVideoDir + "test.tmp\" && rm \"" + aiVideoDir + "test.tmp\" && echo 'writable' || echo 'not writable'";
            String testWriteResult = AiWallpaperThemeHelper.themeShellResult(taskService,testWriteCmd);
            LogHelper.d(TAG, "目标目录可写性测试: " + testWriteResult);
            
            if (testWriteResult == null || !testWriteResult.contains("writable")) {
                LogHelper.w(TAG, "目标目录不可写，尝试设置权限");
                AiWallpaperThemeHelper.themeShellCommand(taskService,"chmod -R 777 \"" + getExtractDir() + "\"");
            }
            
            // 使用多种方法尝试复制文件（降级重试）
            boolean cpVideoSuccess = false;
            
            // 方法1: 使用 cp 命令
            String copyCmd = "cp \"" + actualSourcePath + "\" \"" + targetVideoPath + "\"";
            LogHelper.d(TAG, "执行复制命令 (cp): " + copyCmd);
            cpVideoSuccess = AiWallpaperThemeHelper.themeShellCommand(taskService,copyCmd);
            LogHelper.d(TAG, "cp命令结果: " + cpVideoSuccess);
            
            if (!cpVideoSuccess) {
                // 方法2: 使用 cat 命令
                LogHelper.w(TAG, "cp命令失败，尝试使用cat命令");
                String catCmd = "cat \"" + actualSourcePath + "\" > \"" + targetVideoPath + "\"";
                LogHelper.d(TAG, "执行cat命令: " + catCmd);
                cpVideoSuccess = AiWallpaperThemeHelper.themeShellCommand(taskService,catCmd);
                LogHelper.d(TAG, "cat命令结果: " + cpVideoSuccess);
                
                if (!cpVideoSuccess) {
                    // 方法3: 使用 dd 命令
                    LogHelper.w(TAG, "cat命令失败，尝试使用dd命令");
                    String ddCmd = "dd if=\"" + actualSourcePath + "\" of=\"" + targetVideoPath + "\" bs=4096 2>&1";
                    LogHelper.d(TAG, "执行dd命令: " + ddCmd);
                    String ddResult = AiWallpaperThemeHelper.themeShellResult(taskService,ddCmd);
                    LogHelper.d(TAG, "dd命令输出: " + ddResult);
                    // dd命令总是返回true，需要检查输出文件
                    cpVideoSuccess = true;
                }
            }
            
            LogHelper.d(TAG, "═══════════════════════════════════════");
            
            // 等待文件写入完成
            try {
                Thread.sleep(FILE_WRITE_WAIT_MS);
            } catch (InterruptedException e) {
                // 忽略
            }
            
            // 验证目标文件是否存在
            String verifyVideoCmd = "test -f \"" + targetVideoPath + "\" && echo 'exists' || echo 'not found'";
            String verifyVideoResult = AiWallpaperThemeHelper.themeShellResult(taskService,verifyVideoCmd);
            LogHelper.d(TAG, "目标文件存在性验证: " + verifyVideoResult);
            
            if (verifyVideoResult == null || !verifyVideoResult.contains("exists")) {
                // 文件不存在，尝试再次复制
                LogHelper.w(TAG, "目标文件不存在，尝试再次复制");
                String retryCopyCmd = "cp \"" + actualSourcePath + "\" \"" + targetVideoPath + "\"";
                AiWallpaperThemeHelper.themeShellCommand(taskService,retryCopyCmd);
                
                // 再次验证
                try {
                    Thread.sleep(FILE_WRITE_WAIT_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                verifyVideoResult = AiWallpaperThemeHelper.themeShellResult(taskService,verifyVideoCmd);
                if (verifyVideoResult == null || !verifyVideoResult.contains("exists")) {
                    LogHelper.e(TAG, "视频文件复制失败，目标文件不存在: " + targetVideoPath);
                    return false;
                }
            }
            
            // 验证文件大小
            String sizeCmd = "stat -c%s \"" + targetVideoPath + "\" 2>/dev/null || echo '0'";
            String sizeResult = AiWallpaperThemeHelper.themeShellResult(taskService,sizeCmd);
            LogHelper.d(TAG, "目标文件大小验证结果: " + sizeResult);
            
            if (sizeResult != null && !sizeResult.trim().isEmpty() && !sizeResult.trim().equals("0")) {
                try {
                    long fileSize = Long.parseLong(sizeResult.trim());
                    LogHelper.d(TAG, "目标文件大小: " + fileSize + " bytes");
                    
                    if (fileSize == 0) {
                        // 文件大小为0，尝试使用dd命令重新复制
                        LogHelper.w(TAG, "文件大小为0，尝试使用dd命令重新复制");
                        String ddRetryCmd = "dd if=\"" + actualSourcePath + "\" of=\"" + targetVideoPath + "\" bs=4096 2>/dev/null";
                        AiWallpaperThemeHelper.themeShellCommand(taskService,ddRetryCmd);
                        
                        // 再次验证文件大小
                        try {
                            Thread.sleep(FILE_WRITE_WAIT_MS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                        String retrySizeResult = AiWallpaperThemeHelper.themeShellResult(taskService,sizeCmd);
                        if (retrySizeResult != null && !retrySizeResult.trim().isEmpty() && !retrySizeResult.trim().equals("0")) {
                            long retryFileSize = Long.parseLong(retrySizeResult.trim());
                            if (retryFileSize == 0) {
                                LogHelper.e(TAG, "视频文件大小为0，复制失败");
                                return false;
                            } else {
                                LogHelper.d(TAG, "dd命令重新复制成功: " + targetVideoPath + " (大小: " + retryFileSize + " bytes)");
                                return true;
                            }
                        } else {
                            LogHelper.e(TAG, "视频文件大小为0，复制失败");
                            return false;
                        }
                    } else {
                        LogHelper.d(TAG, "✅ 视频文件复制成功: " + targetVideoPath + " (大小: " + fileSize + " bytes)");
                        return true;
                    }
                } catch (NumberFormatException e) {
                    LogHelper.w(TAG, "无法解析文件大小: " + sizeResult);
                    return false;
                }
            } else {
                LogHelper.e(TAG, "视频文件大小为0或无法获取文件大小，复制失败");
                return false;
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "替换视频文件失败", e);
            return false;
        }
    }
    
    /**
     * 使用 Java File API 复制文件（优化版：根据文件大小动态调整缓冲区）
     */
    private boolean copyFileWithJava(String sourcePath, String targetPath) {
        FileInputStream fis = null;
        FileOutputStream fos = null;
        try {
            File sourceFile = new File(sourcePath);
            if (!sourceFile.exists() || sourceFile.length() == 0) {
                LogHelper.e(TAG, "源文件不存在或为空: " + sourcePath);
                return false;
            }
            
            File targetFile = new File(targetPath);
            File parentDir = targetFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            
            // 根据文件大小选择缓冲区大小
            long fileSize = sourceFile.length();
            int bufferSize = fileSize > LARGE_FILE_THRESHOLD ? LARGE_FILE_BUFFER_SIZE : BUFFER_SIZE;
            
            fis = new FileInputStream(sourceFile);
            fos = new FileOutputStream(targetFile);
            byte[] buffer = new byte[bufferSize];
            int length;
            long totalBytes = 0;
            
            while ((length = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, length);
                totalBytes += length;
                
                // 对于大文件，可选：报告进度（每5MB报告一次）
                if (fileSize > LARGE_FILE_THRESHOLD && totalBytes % (5 * 1024 * 1024) == 0) {
                    int progress = (int) (totalBytes * 100 / fileSize);
                    LogHelper.d(TAG, "复制进度: " + progress + "% (" + (totalBytes / 1024 / 1024) + "MB/" + (fileSize / 1024 / 1024) + "MB)");
                }
            }
            
            fos.flush();
            
            boolean success = targetFile.exists() && targetFile.length() == sourceFile.length();
            if (success) {
                if (AiWallpaperThemeHelper.isUnderMiRootThemeWorkDir(targetPath)) {
                    afterAppWrite(targetPath);
                }
                LogHelper.d(TAG, "✅ 文件复制成功: " + sourcePath + " -> " + targetPath + " (大小: " + fileSize + " bytes)");
            } else {
                LogHelper.e(TAG, "文件复制失败: 大小不匹配 - 源=" + sourceFile.length() + ", 目标=" + targetFile.length());
            }
            return success;
        } catch (IOException e) {
            LogHelper.e(TAG, "复制文件失败", e);
            return false;
        } finally {
            // 确保资源被释放
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException e) {
                    LogHelper.w(TAG, "关闭输出流失败", e);
                }
            }
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException e) {
                    LogHelper.w(TAG, "关闭输入流失败", e);
                }
            }
        }
    }
    
    /**
     * 更新 manifest.xml 中的循环设置
     * 只修改 <Alternate> 标签内的 <VideoCommand> 标签的 loop 属性
     * 参考 beiping1 和 3.1.2 的实现，确保精确修改
     */
    private boolean updateManifestLoop(String manifestFile, boolean loopEnabled) {
        try {
            String manifestContent = AiWallpaperThemeHelper.themeShellResult(taskService,"cat \"" + manifestFile + "\" 2>/dev/null");
            if (manifestContent == null || manifestContent.trim().isEmpty()) {
                LogHelper.e(TAG, "manifest.xml文件为空或不存在");
                return false;
            }
            
            String loopValue = loopEnabled ? "1" : "0";
            LogHelper.d(TAG, "开始修改manifest.xml，目标loop值: " + loopValue);
            LogHelper.d(TAG, "原始内容长度: " + manifestContent.length());
            
            // 正则表达式：匹配<Alternate>标签及其内容（非贪婪匹配，匹配到</Alternate>为止）
            // 分组1：<Alternate>标签的开始部分（包括属性）
            // 分组2：<Alternate>标签内的内容
            java.util.regex.Pattern alternatePattern = java.util.regex.Pattern.compile(
                "(<Alternate[^>]*>)([\\s\\S]*?)(</Alternate>)",
                java.util.regex.Pattern.DOTALL
            );
            java.util.regex.Matcher alternateMatcher = alternatePattern.matcher(manifestContent);
            
            int alternateCount = 0;  // 统计找到的<Alternate>标签数量
            int modifiedCount = 0;  // 统计成功修改的数量
            StringBuffer result = new StringBuffer();
            
            // 循环匹配所有<Alternate>标签
            while (alternateMatcher.find()) {
                alternateCount++;
                String alternateStartTag = alternateMatcher.group(1) != null ? alternateMatcher.group(1) : "";
                String alternateContent = alternateMatcher.group(2) != null ? alternateMatcher.group(2) : "";
                
                LogHelper.d(TAG, "找到第" + alternateCount + "个<Alternate>标签");
                
                // 在<Alternate>标签内容中查找<VideoCommand>标签
                // 匹配格式：<VideoCommand ... loop="0" ...> 或 <VideoCommand ... loop="1" ...>
                java.util.regex.Pattern videoCommandPattern = java.util.regex.Pattern.compile(
                    "(<VideoCommand[^>]*\\s)loop=\"([01])\"([^>]*>)"
                );
                java.util.regex.Matcher videoCommandMatcher = videoCommandPattern.matcher(alternateContent);
                
                int videoCommandCount = 0;
                StringBuffer videoCommandResult = new StringBuffer();
                int lastEnd = 0;
                
                // 循环匹配<Alternate>内的所有<VideoCommand>标签
                while (videoCommandMatcher.find()) {
                    videoCommandCount++;
                    String oldLoopValue = videoCommandMatcher.group(2) != null ? videoCommandMatcher.group(2) : "";
                    
                    // 如果loop值需要修改
                    if (!oldLoopValue.equals(loopValue)) {
                        modifiedCount++;
                        LogHelper.d(TAG, "修改第" + alternateCount + "个<Alternate>标签内的第" + videoCommandCount + "个<VideoCommand>标签: loop=\"" + oldLoopValue + "\" -> loop=\"" + loopValue + "\"");
                        
                        // 添加匹配前的文本
                        videoCommandResult.append(alternateContent.substring(lastEnd, videoCommandMatcher.start()));
                        // 添加修改后的<VideoCommand>标签
                        videoCommandResult.append(videoCommandMatcher.group(1) != null ? videoCommandMatcher.group(1) : "");
                        videoCommandResult.append("loop=\"").append(loopValue).append("\"");
                        videoCommandResult.append(videoCommandMatcher.group(3) != null ? videoCommandMatcher.group(3) : "");
                        
                        lastEnd = videoCommandMatcher.end();
                    } else {
                        // loop值已经是目标值，不需要修改，直接添加原文本
                        LogHelper.d(TAG, "第" + alternateCount + "个<Alternate>标签内的第" + videoCommandCount + "个<VideoCommand>标签的loop值已经是" + loopValue + "，跳过修改");
                        videoCommandResult.append(alternateContent.substring(lastEnd, videoCommandMatcher.end()));
                        lastEnd = videoCommandMatcher.end();
                    }
                }
                
                // 添加剩余的文本
                if (lastEnd < alternateContent.length()) {
                    videoCommandResult.append(alternateContent.substring(lastEnd));
                }
                
                // 使用appendReplacement替换<Alternate>标签内容
                String replacement = java.util.regex.Matcher.quoteReplacement(alternateStartTag) +
                    videoCommandResult.toString().replace("$", "\\$") +
                    java.util.regex.Matcher.quoteReplacement("</Alternate>");
                alternateMatcher.appendReplacement(result, replacement);
                
                if (videoCommandCount > 0) {
                    LogHelper.d(TAG, "第" + alternateCount + "个<Alternate>标签内找到" + videoCommandCount + "个<VideoCommand>标签");
                } else {
                    LogHelper.w(TAG, "第" + alternateCount + "个<Alternate>标签内未找到<VideoCommand>标签");
                }
            }
            
            // 使用appendTail添加剩余内容
            alternateMatcher.appendTail(result);
            
            // 检查是否有修改
            String modifiedContent = result.toString();
            if (!modifiedContent.equals(manifestContent)) {
                // 有修改，写回文件
                String tempManifest = getExtractDir() + "manifest.xml.tmp";
                File tempFile = new File(tempManifest);
                File parentDir = tempFile.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }
                
                FileOutputStream fos = new FileOutputStream(tempFile);
                fos.write(modifiedContent.getBytes("UTF-8"));
                fos.flush();
                fos.close();
                afterAppWrite(tempManifest);

                // 替换原文件
                boolean cpSuccess = shellCmd("cp \"" + tempManifest + "\" \"" + manifestFile + "\"");
                if (cpSuccess) {
                    // 删除临时文件
                    AiWallpaperThemeHelper.themeShellCommand(taskService,"rm -f \"" + tempManifest + "\"");
                    LogHelper.d(TAG, "manifest.xml修改成功：找到" + alternateCount + "个<Alternate>标签，成功修改" + modifiedCount + "个loop属性为" + loopValue);
                    return true;
                } else {
                    LogHelper.e(TAG, "复制临时manifest文件到原位置失败");
                    return false;
                }
            } else {
                if (alternateCount == 0) {
                    LogHelper.w(TAG, "未找到<Alternate>标签");
                } else if (modifiedCount == 0) {
                    LogHelper.w(TAG, "找到" + alternateCount + "个<Alternate>标签，但未找到需要修改的loop属性");
                } else {
                    LogHelper.d(TAG, "未进行任何修改");
                }
                return true; // 即使没有修改，也算成功（可能已经是目标值）
            }
            
        } catch (Exception e) {
            LogHelper.e(TAG, "更新 manifest.xml 失败", e);
            return false;
        }
    }
    
    /**
     * 更新 manifest.xml 中的声音设置
     * 在 <VideoCommand target="aiVideo" command="config"...> 后添加或删除 setVolume 命令
     * @param manifestFile manifest.xml文件路径
     * @param soundEnabled 是否启用声音
     * @param volume 音量（0-100）
     * @return 是否成功
     */
    private boolean updateManifestSound(String manifestFile, boolean soundEnabled, int volume) {
        try {
            String manifestContent = AiWallpaperThemeHelper.themeShellResult(taskService,"cat \"" + manifestFile + "\" 2>/dev/null");
            if (manifestContent == null || manifestContent.trim().isEmpty()) {
                LogHelper.e(TAG, "manifest.xml文件为空或不存在");
                return false;
            }
            
            // 音量范围：0-100，转换为0.0-1.0（字符串格式，如"1"表示100%，"0.5"表示50%）
            double volumeFloat = Math.max(0.0, Math.min(100.0, volume)) / 100.0;
            // 如果音量是100%，使用"1"，否则保留两位小数
            String volumeValue = (volumeFloat == 1.0) ? "1" : String.format("%.2f", volumeFloat);
            
            LogHelper.d(TAG, "开始修改manifest.xml声音设置，soundEnabled=" + soundEnabled + ", volume=" + volumeValue);
            
            String modifiedContent = manifestContent;
            boolean hasModification = false;
            
            if (soundEnabled) {
                // 开启声音：只在 Trigger action="init" 的 IfCommand 中的 aiVideo config 命令后添加 setVolume 命令
                String beforeModify = modifiedContent;
                
                // 精确匹配：Trigger action="init" -> IfCommand -> Consequent/Alternate -> config命令后添加setVolume
                java.util.regex.Pattern initTriggerPattern = java.util.regex.Pattern.compile(
                    "(<Trigger[^>]*action=\"init\"[^>]*>)([\\s\\S]*?)(</Trigger>)",
                    java.util.regex.Pattern.DOTALL
                );
                java.util.regex.Matcher initTriggerMatcher = initTriggerPattern.matcher(modifiedContent);
                StringBuffer initTriggerResult = new StringBuffer();
                
                while (initTriggerMatcher.find()) {
                    String triggerStart = initTriggerMatcher.group(1);
                    String triggerContent = initTriggerMatcher.group(2);
                    String triggerEnd = initTriggerMatcher.group(3);
                    
                    // 检查是否包含 IfCommand 和 aiVideo config（必须是包含path="'assets/ai/ai.mp4'"的config）
                    if (triggerContent.contains("<IfCommand") && triggerContent.contains("target=\"aiVideo\"") && 
                        triggerContent.contains("command=\"config\"") && triggerContent.contains("path=\"'assets/ai/ai.mp4'\"")) {
                        
                        // 先删除该 trigger 内现有的 setVolume 命令（避免重复）
                        String originalTriggerContent = triggerContent;
                        triggerContent = triggerContent.replaceAll(
                            "\\s*<VideoCommand[^>]*target=\"aiVideo\"[^>]*command=\"setVolume\"[^>]*/>\\s*",
                            ""
                        );
                        
                        // 清理可能错误添加的 muted 属性（从 config 命令中删除）
                        // 处理两种情况：1) 正常格式：/> 前有 muted 2) 错误格式：/> 后有 muted
                        triggerContent = triggerContent.replaceAll(
                            "(<VideoCommand[^>]*target=\"aiVideo\"[^>]*command=\"config\"[^>]*path=\"'assets/ai/ai\\.mp4'\"[^>]*?)\\s+muted=\"[^\"]*\"([^>]*?/>)",
                            "$1$2"
                        );
                        // 处理错误格式：/> 后有空格和 muted 属性
                        triggerContent = triggerContent.replaceAll(
                            "(<VideoCommand[^>]*target=\"aiVideo\"[^>]*command=\"config\"[^>]*path=\"'assets/ai/ai\\.mp4'\"[^>]*?/>)\\s+muted=\"[^\"]*\"",
                            "$1"
                        );
                        // 处理错误格式：/> 后有空格和 muted 属性，且标签未正确闭合
                        triggerContent = triggerContent.replaceAll(
                            "(<VideoCommand[^>]*target=\"aiVideo\"[^>]*command=\"config\"[^>]*path=\"'assets/ai/ai\\.mp4'\"[^>]*?/)\\s+muted=\"[^\"]*\"([^>]*?>)",
                            "$1/>"
                        );
                        
                        // 只在 Consequent 和 Alternate 的 aiVideo config 命令后添加 setVolume
                        // 精确匹配：只在IfCommand的Consequent和Alternate分支中，每个config命令后添加setVolume（如果还没有的话）
                        String originalTriggerForConfig = triggerContent;
                        
                        // 匹配Consequent分支中的config命令（后面没有setVolume的）
                        java.util.regex.Pattern consequentPattern = java.util.regex.Pattern.compile(
                            "(<Consequent>\\s*<VideoCommand[^>]*target=\"aiVideo\"[^>]*command=\"config\"[^>]*path=\"'assets/ai/ai\\.mp4'\"[^>]*/>)(?!\\s*<VideoCommand[^>]*target=\"aiVideo\"[^>]*command=\"setVolume\")",
                            java.util.regex.Pattern.DOTALL
                        );
                        triggerContent = consequentPattern.matcher(triggerContent).replaceAll(
                            "$1\n          <VideoCommand target=\"aiVideo\" command=\"setVolume\" volume=\"" + volumeValue + "\"/>"
                        );
                        
                        // 匹配Alternate分支中的config命令（后面没有setVolume的）
                        java.util.regex.Pattern alternatePattern = java.util.regex.Pattern.compile(
                            "(<Alternate>\\s*<VideoCommand[^>]*target=\"aiVideo\"[^>]*command=\"config\"[^>]*path=\"'assets/ai/ai\\.mp4'\"[^>]*/>)(?!\\s*<VideoCommand[^>]*target=\"aiVideo\"[^>]*command=\"setVolume\")",
                            java.util.regex.Pattern.DOTALL
                        );
                        triggerContent = alternatePattern.matcher(triggerContent).replaceAll(
                            "$1\n          <VideoCommand target=\"aiVideo\" command=\"setVolume\" volume=\"" + volumeValue + "\"/>"
                        );
                        
                        boolean configModified = !originalTriggerForConfig.equals(triggerContent);
                        
                        if (configModified || !originalTriggerContent.equals(triggerContent)) {
                            hasModification = true;
                            initTriggerMatcher.appendReplacement(initTriggerResult,
                                java.util.regex.Matcher.quoteReplacement(triggerStart) +
                                triggerContent +
                                java.util.regex.Matcher.quoteReplacement(triggerEnd)
                            );
                            LogHelper.d(TAG, "开启声音：在init trigger的IfCommand中添加setVolume命令: volume=\"" + volumeValue + "\"");
                        } else {
                            initTriggerMatcher.appendReplacement(initTriggerResult, initTriggerMatcher.group(0));
                        }
                    } else {
                        initTriggerMatcher.appendReplacement(initTriggerResult, initTriggerMatcher.group(0));
                    }
                }
                initTriggerMatcher.appendTail(initTriggerResult);
                modifiedContent = initTriggerResult.toString();
                
                if (!beforeModify.equals(modifiedContent)) {
                    LogHelper.d(TAG, "开启声音：修改完成");
                }
            } else {
                // 关闭声音：只删除 Trigger action="init" 中的 setVolume 命令
                String beforeDelete = modifiedContent;
                
                // 精确匹配：Trigger action="init" -> IfCommand -> 删除setVolume
                java.util.regex.Pattern initTriggerPattern = java.util.regex.Pattern.compile(
                    "(<Trigger[^>]*action=\"init\"[^>]*>)([\\s\\S]*?)(</Trigger>)",
                    java.util.regex.Pattern.DOTALL
                );
                java.util.regex.Matcher initTriggerMatcher = initTriggerPattern.matcher(modifiedContent);
                StringBuffer initTriggerResult = new StringBuffer();
                
                while (initTriggerMatcher.find()) {
                    String triggerStart = initTriggerMatcher.group(1);
                    String triggerContent = initTriggerMatcher.group(2);
                    String triggerEnd = initTriggerMatcher.group(3);
                    
                    // 检查是否包含 IfCommand 和 setVolume（必须是包含aiVideo config的trigger）
                    if (triggerContent.contains("<IfCommand") && triggerContent.contains("command=\"setVolume\"") &&
                        triggerContent.contains("target=\"aiVideo\"") && triggerContent.contains("command=\"config\"")) {
                        // 删除该 trigger 内的 setVolume 命令
                        String originalTriggerContent = triggerContent;
                        triggerContent = triggerContent.replaceAll(
                            "\\s*<VideoCommand[^>]*target=\"aiVideo\"[^>]*command=\"setVolume\"[^>]*/>\\s*",
                            ""
                        );
                        
                        // 清理可能错误添加的 muted 属性（从 config 命令中删除）
                        // 处理两种情况：1) 正常格式：/> 前有 muted 2) 错误格式：/> 后有 muted
                        triggerContent = triggerContent.replaceAll(
                            "(<VideoCommand[^>]*target=\"aiVideo\"[^>]*command=\"config\"[^>]*path=\"'assets/ai/ai\\.mp4'\"[^>]*?)\\s+muted=\"[^\"]*\"([^>]*?/>)",
                            "$1$2"
                        );
                        // 处理错误格式：/> 后有空格和 muted 属性
                        triggerContent = triggerContent.replaceAll(
                            "(<VideoCommand[^>]*target=\"aiVideo\"[^>]*command=\"config\"[^>]*path=\"'assets/ai/ai\\.mp4'\"[^>]*?/>)\\s+muted=\"[^\"]*\"",
                            "$1"
                        );
                        // 处理错误格式：/> 后有空格和 muted 属性，且标签未正确闭合
                        triggerContent = triggerContent.replaceAll(
                            "(<VideoCommand[^>]*target=\"aiVideo\"[^>]*command=\"config\"[^>]*path=\"'assets/ai/ai\\.mp4'\"[^>]*?/)\\s+muted=\"[^\"]*\"([^>]*?>)",
                            "$1/>"
                        );
                        
                        if (!originalTriggerContent.equals(triggerContent)) {
                            hasModification = true;
                            initTriggerMatcher.appendReplacement(initTriggerResult,
                                java.util.regex.Matcher.quoteReplacement(triggerStart) +
                                triggerContent +
                                java.util.regex.Matcher.quoteReplacement(triggerEnd)
                            );
                            LogHelper.d(TAG, "关闭声音：在init trigger的IfCommand中删除setVolume命令");
                        } else {
                            initTriggerMatcher.appendReplacement(initTriggerResult, initTriggerMatcher.group(0));
                        }
                    } else {
                        initTriggerMatcher.appendReplacement(initTriggerResult, initTriggerMatcher.group(0));
                    }
                }
                initTriggerMatcher.appendTail(initTriggerResult);
                modifiedContent = initTriggerResult.toString();
                
                if (!beforeDelete.equals(modifiedContent)) {
                    LogHelper.d(TAG, "关闭声音：修改完成");
                }
            }
            
            // 如果有修改，写回文件
            if (hasModification) {
                String tempManifest = getExtractDir() + "manifest.xml.tmp";
                File tempFile = new File(tempManifest);
                File parentDir = tempFile.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }
                
                FileOutputStream fos = new FileOutputStream(tempFile);
                fos.write(modifiedContent.getBytes("UTF-8"));
                fos.flush();
                fos.close();
                afterAppWrite(tempManifest);

                // 替换原文件
                boolean cpSuccess = shellCmd("cp \"" + tempManifest + "\" \"" + manifestFile + "\"");
                if (cpSuccess) {
                    // 删除临时文件
                    AiWallpaperThemeHelper.themeShellCommand(taskService,"rm -f \"" + tempManifest + "\"");
                    LogHelper.d(TAG, "manifest.xml声音设置修改成功：" + (soundEnabled ? "添加" : "删除") + "了setVolume命令，volume=\"" + volumeValue + "\"");
                    return true;
                } else {
                    LogHelper.e(TAG, "复制临时manifest文件到原位置失败");
                    return false;
                }
            } else {
                LogHelper.d(TAG, "声音设置无需修改（已经是目标值）");
                return true;
            }
            
        } catch (Exception e) {
            LogHelper.e(TAG, "更新 manifest.xml 声音设置失败", e);
            return false;
        }
    }
    
    /**
     * 将任意 PNG（绝对路径，shell 可读）复制为指定 AI 壁纸目录下的
     * preview_rearscreen_0.png 与 preview_rearscreen_dark_0.png。
     *
     * @return 是否至少成功写入一个预览文件
     */
    public static boolean copySourcePngToRearscreenPreviews(
            ITaskService taskService,
            String directory,
            String sourcePngPath) {
        if (taskService == null || directory == null || directory.isEmpty()
                || sourcePngPath == null || sourcePngPath.isEmpty()) {
            return false;
        }
        String previewDir = BASE_DIR + directory + "/preview/";
        LogHelper.d(TAG, "复制绑定/主题预览源图到: " + previewDir + " 源: " + sourcePngPath);
        AiWallpaperThemeHelper.prepareThemeShellWorkDirs(taskService);
        AiWallpaperThemeHelper.ensureShellReadable(taskService, sourcePngPath);
        shellCmd(taskService, "mkdir -p \"" + previewDir + "\"");

        String[] previewFiles = {
            previewDir + "preview_rearscreen_0" + PNG_EXT,
            previewDir + "preview_rearscreen_dark_0" + PNG_EXT
        };

        boolean replaced = false;
        for (String previewFile : previewFiles) {
            try {
                AiWallpaperThemeHelper.themeShellCommand(taskService,"rm -f \"" + previewFile + "\"");
                try {
                    Thread.sleep(PREVIEW_DELETE_WAIT_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    continue;
                }

                String cpCmd = "cp -f \"" + sourcePngPath + "\" \"" + previewFile + "\"";
                boolean pngCpSuccess = AiWallpaperThemeHelper.themeShellCommand(taskService,cpCmd);
                if (!pngCpSuccess) {
                    String catCmd = "cat \"" + sourcePngPath + "\" > \"" + previewFile + "\"";
                    pngCpSuccess = AiWallpaperThemeHelper.themeShellCommand(taskService,catCmd);
                    if (!pngCpSuccess) {
                        String ddCmd = "dd if=\"" + sourcePngPath + "\" of=\"" + previewFile + "\" bs=4096 2>/dev/null";
                        pngCpSuccess = AiWallpaperThemeHelper.themeShellCommand(taskService,ddCmd);
                    }
                }

                if (pngCpSuccess) {
                    try {
                        Thread.sleep(PREVIEW_REPLACE_WAIT_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        continue;
                    }
                    String pngVerifyCmd = "test -f \"" + previewFile + "\" && echo 'exists' || echo 'not found'";
                    String pngVerifyResult = AiWallpaperThemeHelper.themeShellResult(taskService,pngVerifyCmd);
                    if (pngVerifyResult != null && pngVerifyResult.contains("exists")) {
                        LogHelper.d(TAG, "✅ 预览图已写入: " + previewFile);
                        replaced = true;
                    }
                }
            } catch (Exception e) {
                LogHelper.w(TAG, "写入预览失败: " + previewFile, e);
            }
        }

        if (!replaced) {
            String defaultPreviewPath = previewDir + "preview_rearscreen_0" + PNG_EXT;
            try {
                boolean copySuccess = AiWallpaperThemeHelper.themeShellCommand(taskService,
                        "cp -f \"" + sourcePngPath + "\" \"" + defaultPreviewPath + "\"");
                if (copySuccess) {
                    try {
                        Thread.sleep(PREVIEW_REPLACE_WAIT_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                    String verifyCmd = "test -f \"" + defaultPreviewPath + "\" && echo 'exists' || echo 'not found'";
                    String verifyResult = AiWallpaperThemeHelper.themeShellResult(taskService,verifyCmd);
                    if (verifyResult != null && verifyResult.contains("exists")) {
                        replaced = true;
                    }
                }
            } catch (Exception e) {
                LogHelper.w(TAG, "写入默认预览失败", e);
            }
        }
        return replaced;
    }

    /**
     * 更新预览图（使用 Android MediaMetadataRetriever 提取视频第一帧）
     * 优化：确保所有资源在 finally 块中释放
     */
    private void updatePreviewImage(String directory, String videoPath) {
        MediaMetadataRetriever retriever = null;
        Bitmap frame = null;
        FileOutputStream fos = null;
        
        try {
            LogHelper.d(TAG, "开始更新预览图 - 目录: " + directory + ", 视频路径: " + videoPath);

            // 预览始终来自视频抽帧；绑定封面仅在写入 preview 后再同步到用户路径（见 DirectoryCoverBindingHelper）
            
            // 使用 Android MediaMetadataRetriever 提取视频指定帧
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(videoPath);
            
            long targetTimeUs = getTimeUsForFrameIndex(retriever, PREVIEW_COVER_FRAME_INDEX);
            LogHelper.d(TAG, "预览封面目标帧=" + PREVIEW_COVER_FRAME_INDEX + ", timeUs=" + targetTimeUs);
            frame = retriever.getFrameAtTime(targetTimeUs, MediaMetadataRetriever.OPTION_CLOSEST);
            
            if (frame == null) {
                LogHelper.w(TAG, "无法从视频提取帧，尝试使用其他时间点");
                // 尝试获取中间帧
                String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (durationStr != null) {
                    try {
                        long duration = Long.parseLong(durationStr);
                        long timeUs = (duration / 2) * 1000; // 转换为微秒
                        frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    } catch (NumberFormatException e) {
                        LogHelper.w(TAG, "无法解析视频时长", e);
                    }
                }
            }
            
            if (frame == null) {
                LogHelper.w(TAG, "无法从视频提取任何帧，跳过预览图更新");
                return;
            }
            
            LogHelper.d(TAG, "✅ 成功提取视频帧，尺寸: " + frame.getWidth() + "x" + frame.getHeight());
            
            // 保存为 PNG 文件
            String previewPng = getWorkDir() + "preview_frame" + PNG_EXT;
            File previewPngFile = new File(previewPng);
            File parentDir = previewPngFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            
            fos = new FileOutputStream(previewPngFile);
            frame.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            
            LogHelper.d(TAG, "✅ 预览图已保存: " + previewPng + " (大小: " + previewPngFile.length() + " bytes)");
            
            // 验证文件是否存在
            if (!previewPngFile.exists() || previewPngFile.length() == 0) {
                LogHelper.e(TAG, "预览图文件保存失败或为空");
                return;
            }

            LogHelper.d(TAG, "准备将视频抽帧预览写入目录: " + directory);
            boolean replaced = copySourcePngToRearscreenPreviews(taskService, directory, previewPng);
            LogHelper.dDebug(
                    "DirCoverBind",
                    "updatePreviewImage copySourcePngToRearscreenPreviews ok="
                            + replaced
                            + " mamlDir="
                            + directory);
            if (replaced) {
                LogHelper.d(TAG, "✅ 预览图更新完成（视频帧）");
                try {
                    boolean boundSyncOk =
                            DirectoryCoverBindingHelper.syncBoundCoverFromRearscreenPreview(
                                    context, taskService, directory);
                    LogHelper.dDebug(
                            "DirCoverBind",
                            "updatePreviewImage boundCoverSyncOk="
                                    + boundSyncOk
                                    + " mamlDir="
                                    + directory);
                } catch (Exception e) {
                    LogHelper.w(TAG, "同步绑定封面失败（已忽略）", e);
                }
            } else {
                LogHelper.w(TAG, "视频帧预览写入目录失败");
            }
            
        } catch (Exception e) {
            LogHelper.e(TAG, "更新预览图失败", e);
        } finally {
            // 确保所有资源都被释放
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException e) {
                    LogHelper.w(TAG, "关闭预览图输出流失败", e);
                }
            }
            if (frame != null) {
                frame.recycle();
            }
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Exception e) {
                    LogHelper.w(TAG, "释放 MediaMetadataRetriever 失败", e);
                }
            }
            
            // 清理临时预览图文件 preview_frame.png
            try {
                String previewPng = getWorkDir() + "preview_frame" + PNG_EXT;
                AiWallpaperThemeHelper.themeShellCommand(taskService,"rm -f \"" + previewPng + "\"");
                LogHelper.d(TAG, "已清理临时预览图文件: " + previewPng);
            } catch (Exception e) {
                LogHelper.w(TAG, "清理临时预览图文件失败", e);
            }
        }
    }

    /**
     * 将“帧号”换算为 timeUs，供 getFrameAtTime 使用。
     * frameIndex 从 0 开始；优先读取视频帧率元数据，失败则使用兜底帧率。
     */
    private static long getTimeUsForFrameIndex(MediaMetadataRetriever retriever, int frameIndex) {
        int safeIndex = Math.max(0, frameIndex);
        float fps = PREVIEW_COVER_FPS_FALLBACK;
        try {
            String fpsStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
            if (fpsStr != null) {
                float parsed = Float.parseFloat(fpsStr);
                if (parsed > 1f && parsed < 240f) {
                    fps = parsed;
                }
            }
        } catch (Exception ignored) {
        }

        // timeSec = frameIndex / fps；转成微秒
        double timeSec = safeIndex / (double) fps;
        long timeUs = (long) Math.max(0, Math.round(timeSec * 1_000_000d));

        // 若能拿到时长，避免超过视频末尾（取靠近末尾一点点的帧）
        try {
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null && !durationStr.isEmpty()) {
                long durationMs = Long.parseLong(durationStr);
                long durationUs = Math.max(0, durationMs) * 1000L;
                if (durationUs > 0 && timeUs >= durationUs) {
                    timeUs = Math.max(0, durationUs - 1000L);
                }
            }
        } catch (Exception ignored) {
        }
        return timeUs;
    }

    /**
     * 从视频抽取与 AI 目录预览一致的帧，写入应用可写的 PNG 路径（供更新背屏 widget 缩略图等）。
     *
     * @return 是否成功写出非空文件
     */
    public static boolean extractPreviewFrameToPng(String videoPath, String outputPngPath) {
        if (videoPath == null || outputPngPath == null || videoPath.isEmpty()) {
            return false;
        }
        MediaMetadataRetriever retriever = null;
        Bitmap frame = null;
        FileOutputStream fos = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(videoPath);
            long targetTimeUs = getTimeUsForFrameIndex(retriever, PREVIEW_COVER_FRAME_INDEX);
            frame = retriever.getFrameAtTime(targetTimeUs, MediaMetadataRetriever.OPTION_CLOSEST);
            if (frame == null) {
                String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (durationStr != null) {
                    try {
                        long duration = Long.parseLong(durationStr);
                        long timeUs = (duration / 2) * 1000;
                        frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (frame == null) {
                return false;
            }
            File out = new File(outputPngPath);
            File parentDir = out.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            fos = new FileOutputStream(out);
            frame.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            return out.exists() && out.length() > 0;
        } catch (Exception e) {
            LogHelper.e(TAG, "extractPreviewFrameToPng", e);
            return false;
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException e) {
                    LogHelper.w(TAG, "extractPreviewFrameToPng close", e);
                }
            }
            if (frame != null) {
                frame.recycle();
            }
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Exception e) {
                    LogHelper.w(TAG, "extractPreviewFrameToPng release", e);
                }
            }
        }
    }
    
    /**
     * 压缩目录为 ZIP 文件（增强版：包含详细的验证和错误处理）
     */
    private boolean compressDirectory(String sourceDir, String targetFile) {
        try {
            LogHelper.d(TAG, "开始压缩目录: " + sourceDir + " -> " + targetFile);
            
            // 验证源目录是否存在
            String checkDirCmd = "test -d \"" + sourceDir + "\" && echo 'exists' || echo 'not found'";
            String checkDirResult = AiWallpaperThemeHelper.themeShellResult(taskService,checkDirCmd);
            LogHelper.d(TAG, "源目录验证结果: " + checkDirResult);
            
            if (checkDirResult == null || !checkDirResult.contains("exists")) {
                LogHelper.e(TAG, "源目录不存在: " + sourceDir);
                return false;
            }
            
            // 验证源目录中是否有文件
            String checkFilesCmd = "ls -A \"" + sourceDir + "\" 2>/dev/null | head -1";
            String checkFilesResult = AiWallpaperThemeHelper.themeShellResult(taskService,checkFilesCmd);
            LogHelper.d(TAG, "源目录文件检查结果: " + checkFilesResult);
            
            if (checkFilesResult == null || checkFilesResult.trim().isEmpty()) {
                LogHelper.e(TAG, "源目录为空，无法压缩: " + sourceDir);
                return false;
            }
            
            // 先删除可能存在的旧压缩文件
            AiWallpaperThemeHelper.themeShellCommand(taskService,"rm -f \"" + targetFile + ZIP_EXT + "\"");
            AiWallpaperThemeHelper.themeShellCommand(taskService,"rm -f \"" + targetFile + "\"");
            
            // 直接使用 Java 原生 API 压缩（不依赖系统 zip 命令，更可靠）
            String zipFilePath = targetFile + ZIP_EXT;
            try {
                File sourceDirFile = new File(sourceDir);
                zipDirectory(sourceDirFile, zipFilePath);
                LogHelper.d(TAG, "✅ Java原生压缩完成: " + zipFilePath);
                
                // 验证压缩文件是否存在
                String verifyZipCmd = "test -f \"" + zipFilePath + "\" && stat -c%s \"" + zipFilePath + "\" || echo '0'";
                String verifyZipResult = AiWallpaperThemeHelper.themeShellResult(taskService,verifyZipCmd);
                LogHelper.d(TAG, "压缩文件验证结果: " + verifyZipResult);
                
                if (verifyZipResult == null || verifyZipResult.trim().equals("0")) {
                    LogHelper.e(TAG, "压缩文件不存在或为空");
                    return false;
                }
                
                // 使用 Java File API 再次验证
                File zipFile = new File(zipFilePath);
                if (!zipFile.exists() || zipFile.length() == 0) {
                    LogHelper.e(TAG, "压缩文件验证失败: 文件不存在或大小为0");
                    return false;
                }
                LogHelper.d(TAG, "✅ 压缩文件验证通过: " + zipFilePath + " (大小: " + zipFile.length() + " bytes)");
                afterAppWrite(zipFilePath);
            } catch (IOException e) {
                LogHelper.e(TAG, "Java原生压缩失败", e);
                return false;
            }
            
            // 移除 .zip 扩展名
            boolean mvSuccess = AiWallpaperThemeHelper.themeShellCommand(taskService,"mv \"" + zipFilePath + "\" \"" + targetFile + "\"");
            LogHelper.d(TAG, "重命名命令执行结果: " + mvSuccess);
            
            if (!mvSuccess) {
                LogHelper.e(TAG, "重命名压缩文件失败");
                return false;
            }
            
            // 验证最终文件
            String verifyFinalCmd = "test -f \"" + targetFile + "\" && stat -c%s \"" + targetFile + "\" || echo '0'";
            String verifyFinalResult = AiWallpaperThemeHelper.themeShellResult(taskService,verifyFinalCmd);
            LogHelper.d(TAG, "最终文件验证结果: " + verifyFinalResult);
            
            if (verifyFinalResult == null || verifyFinalResult.trim().equals("0")) {
                LogHelper.e(TAG, "最终压缩文件不存在或为空");
                return false;
            }
            
            long fileSize = Long.parseLong(verifyFinalResult.trim());
            LogHelper.d(TAG, "✅ 目录压缩成功: " + targetFile + " (大小: " + fileSize + " bytes)");
            return true;
        } catch (Exception e) {
            LogHelper.e(TAG, "压缩目录失败", e);
            return false;
        }
    }
    
    /**
     * 替换原文件（带备份和详细验证）
     */
    private boolean replaceOriginalFile(String originalFile, String newFile) {
        afterAppWrite(newFile);
        return AiWallpaperThemeHelper.replaceRootOwnedFile(taskService, originalFile, newFile, true);
    }
    
    /**
     * 清理临时文件
     */
    private void cleanup() {
        try {
            AiWallpaperThemeHelper.themeShellCommand(taskService,"rm -rf \"" + getWorkDir() + "\"");
        } catch (Exception e) {
            LogHelper.w(TAG, "清理临时文件失败", e);
        }
    }
    
    /**
     * 更新进度
     */
    private void updateProgress(String message) {
        LogHelper.d(TAG, message);
        if (progressCallback != null) {
            progressCallback.onProgress(message);
        }
    }
    
    /**
     * 使用 Java 原生 API 压缩目录
     * @param sourceDir 源目录
     * @param zipFilePath ZIP文件路径
     * @throws IOException 压缩失败时抛出异常
     */
    private void zipDirectory(File sourceDir, String zipFilePath) throws IOException {
        LogHelper.d(TAG, "开始Java原生压缩: " + sourceDir + " -> " + zipFilePath);
        
        FileOutputStream fos = null;
        ZipOutputStream zos = null;
        
        try {
            fos = new FileOutputStream(zipFilePath);
            zos = new ZipOutputStream(fos);
            
            zipDirectoryRecursive(sourceDir, sourceDir, zos);
            
            zos.finish();
            LogHelper.d(TAG, "✅ Java原生压缩完成: " + zipFilePath);
            
        } finally {
            if (zos != null) {
                try {
                    zos.close();
                } catch (IOException e) {
                    LogHelper.w(TAG, "关闭ZipOutputStream失败", e);
                }
            }
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException e) {
                    LogHelper.w(TAG, "关闭FileOutputStream失败", e);
                }
            }
        }
    }
    
    /**
     * 递归压缩目录
     * @param sourceDir 源目录根目录
     * @param currentFile 当前处理的文件/目录
     * @param zos ZIP输出流
     * @throws IOException 压缩失败时抛出异常
     */
    private void zipDirectoryRecursive(File sourceDir, File currentFile, ZipOutputStream zos) throws IOException {
        byte[] buffer = new byte[ZIP_BUFFER_SIZE];
        
        if (currentFile.isDirectory()) {
            // 处理目录
            // 获取相对路径
            String relativePath = "";
            if (!currentFile.equals(sourceDir)) {
                relativePath = currentFile.getAbsolutePath().substring(sourceDir.getAbsolutePath().length() + 1);
            }
            
            // 添加目录条目（如果不是根目录）
            if (!relativePath.isEmpty()) {
                ZipEntry dirEntry = new ZipEntry(relativePath + "/");
                zos.putNextEntry(dirEntry);
                zos.closeEntry();
                LogHelper.d(TAG, "添加目录: " + relativePath + "/");
            }
            
            // 递归处理子文件
            File[] children = currentFile.listFiles();
            if (children != null) {
                for (File childFile : children) {
                    zipDirectoryRecursive(sourceDir, childFile, zos);
                }
            }
        } else {
            // 处理文件
            // 计算相对路径
            String relativePath = currentFile.getAbsolutePath().substring(sourceDir.getAbsolutePath().length() + 1);
            
            // 创建ZIP条目
            ZipEntry zipEntry = new ZipEntry(relativePath);
            zos.putNextEntry(zipEntry);
            
            // 写入文件内容
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(currentFile);
                int len;
                long totalBytes = 0;
                while ((len = fis.read(buffer)) > 0) {
                    zos.write(buffer, 0, len);
                    totalBytes += len;
                }
                LogHelper.d(TAG, "添加文件: " + relativePath + " (" + totalBytes + " bytes)");
            } finally {
                if (fis != null) {
                    try {
                        fis.close();
                    } catch (IOException e) {
                        LogHelper.w(TAG, "关闭文件输入流失败: " + currentFile, e);
                    }
                }
            }
            
            zos.closeEntry();
        }
    }
}

