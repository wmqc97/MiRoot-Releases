package com.wmqc.miroot.shell

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Handler
import android.os.Looper
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
        PublicMediaExport.ensureAppReadable(tempPath)
        val tempFile = File(tempPath)
        if (!tempFile.isFile || tempFile.length() == 0L) {
            tempFile.delete()
            return false to app.getString(R.string.screenshot_fail_cap)
        }

        val finalName = "RD_$ts.png"

        if (!composite) {
            val saved = PublicMediaExport.saveImageToRearDisplay(app, tempFile, finalName)
            tempFile.delete()
            if (!saved.ok) {
                return false to app.getString(R.string.screenshot_fail_save)
            }
            if (!saved.viaContentResolver) {
                scan(app, "/storage/emulated/0/Pictures/RearDisplay/$finalName")
            }
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

        val saved = PublicMediaExport.saveImageToRearDisplay(app, compositeOut, finalName)
        deleteQuietly(compositeOut)
        if (!saved.ok) {
            return false to app.getString(R.string.screenshot_fail_save)
        }
        if (!saved.viaContentResolver) {
            scan(app, "/storage/emulated/0/Pictures/RearDisplay/$finalName")
        }
        return true to app.getString(R.string.screenshot_ok_saved)
    }

    private fun deleteQuietly(f: File) {
        PublicMediaExport.deleteQuietly(f)
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
