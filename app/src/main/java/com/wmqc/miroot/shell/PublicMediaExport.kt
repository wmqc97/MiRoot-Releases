package com.wmqc.miroot.shell

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.wmqc.miroot.capability.EnvironmentProbe
import com.wmqc.miroot.capability.PrivilegedShell
import com.wmqc.miroot.capability.PrivilegedShellRoute
import java.io.File

/**
 * 公共媒体目录导出：Shizuku 下 shell `cp` 常产生 0 字节或应用不可见文件（见 SaveStrategyTest），
 * 优先 [MediaStore]；Root 下仍可先尝试 shell cp 并校验大小。
 *
 * 详细约束见 docs/Shizuku权限注意事项.md
 */
object PublicMediaExport {

    data class SaveResult(
        val ok: Boolean,
        /** 成品经 ContentResolver 写入，legacy 绝对路径可能不存在 */
        val viaContentResolver: Boolean = false,
    )

    /** Shizuku 且无 Root 时，公共目录应走 MediaStore。 */
    fun preferContentResolverForPublicSave(): Boolean =
        EnvironmentProbe.privilegedShellRouteSync() == PrivilegedShellRoute.SHIZUKU

    /**
     * 特权 shell 写入后修正属主/权限，使应用进程可读（screenrecord / screencap 中间文件）。
     */
    fun ensureAppReadable(path: String): Boolean {
        if (path.isBlank()) return false
        val uid = android.os.Process.myUid()
        PrivilegedShell.execCmd("chown $uid:$uid \"$path\" 2>/dev/null; chmod 644 \"$path\" 2>/dev/null")
        val f = File(path)
        return f.isFile && f.length() > 0L
    }

    fun saveImageToRearDisplay(context: Context, src: File, displayName: String): SaveResult {
        if (!src.isFile || src.length() <= 0L) return SaveResult(false)
        val minSize = src.length()
        val legacyImagePath = "/storage/emulated/0/Pictures/RearDisplay/$displayName"
        if (!preferContentResolverForPublicSave()) {
            if (copyViaShell(legacyImagePath, src.absolutePath) &&
                shellFileSize(legacyImagePath) >= minSize
            ) {
                return SaveResult(true, viaContentResolver = false)
            }
            deleteZeroByteLegacyGhost(legacyImagePath)
        } else {
            deleteZeroByteLegacyGhost(legacyImagePath)
        }
        return saveImageViaMediaStore(context, src, displayName, minSize)
    }

    fun saveVideoToMovies(context: Context, src: File, displayName: String): SaveResult {
        if (!src.isFile || src.length() <= 0L) return SaveResult(false)
        val minSize = src.length()
        val legacyMoviesPath = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_MOVIES,
            ),
            displayName,
        ).absolutePath
        if (!preferContentResolverForPublicSave()) {
            if (copyViaShell(legacyMoviesPath, src.absolutePath) &&
                shellFileSize(legacyMoviesPath) >= minSize
            ) {
                return SaveResult(true, viaContentResolver = false)
            }
            deleteZeroByteLegacyGhost(legacyMoviesPath)
        } else {
            // Shizuku：导出前仅清理 shell cp 遗留的 0 字节占位，勿在写入后删除同路径成品。
            deleteZeroByteLegacyGhost(legacyMoviesPath)
        }
        return saveVideoViaMediaStore(context, src, displayName, minSize)
    }

    fun deleteQuietly(file: File?) {
        if (file == null) return
        try {
            if (file.exists()) file.delete()
        } catch (_: Exception) {
        }
        deleteShellFile(file.absolutePath)
    }

    fun deleteQuietly(path: String?) {
        if (path.isNullOrBlank()) return
        deleteQuietly(File(path))
    }

    private fun copyViaShell(dstPath: String, srcPath: String): Boolean {
        return try {
            PrivilegedShell.execCmd(
                "mkdir -p \"${File(dstPath).parent}\" && " +
                    "cp \"$srcPath\" \"$dstPath\" && sync",
            )
        } catch (_: Exception) {
            false
        }
    }

    private fun deleteShellFile(path: String) {
        try {
            PrivilegedShell.execCmd("rm -f \"$path\"")
        } catch (_: Exception) {
        }
        try {
            File(path).delete()
        } catch (_: Exception) {
        }
    }

    fun shellFileSize(path: String): Long {
        val f = File(path)
        if (f.isFile) {
            val len = f.length()
            if (len > 0L) return len
        }
        return PrivilegedShell.captureOutput("stat -c %s \"$path\" 2>/dev/null")
            ?.trim()
            ?.toLongOrNull()
            ?: 0L
    }

    /** 仅删除 shell cp 失败留下的 0 字节占位；MediaStore 成品常落在同 legacy 路径，不可误删。 */
    fun deleteZeroByteLegacyGhost(path: String) {
        if (path.isBlank()) return
        if (shellFileSize(path) != 0L) return
        deleteShellFile(path)
        try {
            File(path).delete()
        } catch (_: Exception) {
        }
    }

    private fun saveImageViaMediaStore(
        context: Context,
        src: File,
        displayName: String,
        minSize: Long,
    ): SaveResult {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RearDisplay")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values,
            ) ?: return SaveResult(false)
            val written = context.contentResolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } ?: 0L
            if (written < minSize) {
                context.contentResolver.delete(uri, null, null)
                return SaveResult(false)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                    put(MediaStore.Images.Media.SIZE, written)
                }
                context.contentResolver.update(uri, done, null, null)
            }
            SaveResult(true, viaContentResolver = true)
        } catch (_: Exception) {
            SaveResult(false)
        }
    }

    private fun saveVideoViaMediaStore(
        context: Context,
        src: File,
        displayName: String,
        minSize: Long,
    ): SaveResult {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values,
            ) ?: return SaveResult(false)
            val written = context.contentResolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } ?: 0L
            if (written < minSize) {
                context.contentResolver.delete(uri, null, null)
                return SaveResult(false)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                    put(MediaStore.Video.Media.SIZE, written)
                }
                context.contentResolver.update(uri, done, null, null)
            }
            SaveResult(true, viaContentResolver = true)
        } catch (_: Exception) {
            SaveResult(false)
        }
    }
}
