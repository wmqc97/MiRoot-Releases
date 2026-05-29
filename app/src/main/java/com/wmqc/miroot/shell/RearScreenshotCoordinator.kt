package com.wmqc.miroot.shell

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Handler
import android.os.Looper
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import com.wmqc.miroot.AppExecutors
import com.wmqc.miroot.R
import com.wmqc.miroot.capability.PrivilegedShell
import com.wmqc.miroot.lyrics.RearScreenWakeService
import com.wmqc.miroot.rear.RearAssistPrefs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 背屏截图：临时文件在 [DeviceGeometry.screenshotWorkDir]，成品写入 `Pictures/RearDisplay/`（与旧版 TaskService 一致）。
 */
object RearScreenshotCoordinator {

    private val lock = Any()
    @Volatile
    private var busy = false

    private val mainHandler = Handler(Looper.getMainLooper())

    fun capture(
        context: Context,
        composite: Boolean,
        onResult: (ok: Boolean, message: String) -> Unit,
    ) {
        val app = context.applicationContext
        AppExecutors.runInBackground {
            synchronized(lock) {
                if (busy) {
                    mainHandler.post {
                        onResult(false, app.getString(R.string.screenshot_busy))
                    }
                    return@runInBackground
                }
                busy = true
            }
            try {
                val result = runCapture(app, composite)
                mainHandler.post { onResult(result.first, result.second) }
            } finally {
                synchronized(lock) { busy = false }
            }
        }
    }

    private fun runCapture(app: Context, composite: Boolean): Pair<Boolean, String> {
        PrivilegedShell.execCmd("mkdir -p /storage/emulated/0/Pictures/RearDisplay")
        val workDir = DeviceGeometry.screenshotWorkDir(app)
        workDir.mkdirs()

        try {
            if (RearAssistPrefs.isRecordScreenshotKeepScreenOnEnabled(app) &&
                !RearScreenWakeService.isWakeupLoopActive()
            ) {
                PrivilegedShell.execCmd("input -d 1 keyevent KEYCODE_WAKEUP")
            }
            Thread.sleep(RearAssistPrefs.wakeSettleDelayMsAfterKeyevent(app).toLong())
        } catch (_: Exception) {
        }

        var displayId = PrivilegedShell.captureOutput(
            "dumpsys SurfaceFlinger --display-id | grep -oE 'Display [0-9]+' | awk 'NR==2{print \$2}'",
        )?.trim().orEmpty()
        if (displayId.isEmpty()) displayId = "1"

        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val ms = String.format(Locale.US, "%03d", System.currentTimeMillis() % 1000L)
        val tempName = "RD_temp_${ts}_$ms.png"
        val tempPath = File(workDir, tempName).absolutePath

        val capCmd = "screencap -p -d $displayId $tempPath"
        if (!PrivilegedShell.execCmd(capCmd)) {
            File(tempPath).delete()
            return false to app.getString(R.string.screenshot_fail_cap)
        }
        val tempFile = File(tempPath)
        if (!tempFile.isFile || tempFile.length() == 0L) {
            tempFile.delete()
            return false to app.getString(R.string.screenshot_fail_cap)
        }

        val finalName = "RD_$ts.png"
        val finalPath = "/storage/emulated/0/Pictures/RearDisplay/$finalName"

        if (!composite) {
            if (!copyImageToPublicStorage(app, tempFile, finalPath)) {
                tempFile.delete()
                return false to app.getString(R.string.screenshot_fail_save)
            }
            tempFile.delete()
            scan(app, finalPath)
            return true to app.getString(R.string.screenshot_ok_saved)
        }

        val phonePath = DeviceGeometry.resolvePhoneBackPath(app)
        if (phonePath == null) {
            tempFile.delete()
            return false to app.getString(R.string.screenshot_fail_no_shell_asset)
        }

        val compositeOut = File(app.cacheDir, "RD_composite_${ts}_$ms.png")
        val compositeTemp = compositeOut.absolutePath
        if (!ShellScreenshotComposite.composite(app, phonePath, tempPath, compositeTemp)) {
            deleteQuietly(tempFile)
            deleteQuietly(compositeOut)
            return false to app.getString(R.string.screenshot_fail_composite)
        }
        deleteQuietly(tempFile)

        if (!copyImageToPublicStorage(app, compositeOut, finalPath)) {
            deleteQuietly(compositeOut)
            return false to app.getString(R.string.screenshot_fail_save)
        }
        deleteQuietly(compositeOut)
        scan(app, finalPath)
        return true to app.getString(R.string.screenshot_ok_saved)
    }


    /**
     * Shell cp first, then ContentResolver fallback for Shizuku+SELinux.
     */
    private fun copyImageToPublicStorage(context: Context, src: File, dstPath: String): Boolean {
        val dst = File(dstPath)
        // 1) Try shell cp
        try {
            dst.parentFile?.mkdirs()
            if (PrivilegedShell.execCmd("cp \"${src.absolutePath}\" \"$dstPath\" && sync")) {
                if (dst.isFile && dst.length() > 0L) return true
            }
        } catch (_: Exception) { }
        // 2) ContentResolver fallback (Shizuku SELinux-safe)
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, dst.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RearDisplay")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return false
            context.contentResolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } ?: run { context.contentResolver.delete(uri, null, null); return false }
            true
        } catch (_: Exception) {
            false
        }
    }
    private fun deleteQuietly(f: File) {
        try {
            if (f.exists()) f.delete()
        } catch (_: Exception) {
        }
    }

    /** 仅扫描成品路径；临时文件须先 [deleteQuietly] 再调用本方法，避免相册扫到中间文件。 */
    private fun scan(app: Context, path: String) {
        MediaScannerConnection.scanFile(
            app,
            arrayOf(path),
            arrayOf("image/png"),
            null,
        )
    }
}
