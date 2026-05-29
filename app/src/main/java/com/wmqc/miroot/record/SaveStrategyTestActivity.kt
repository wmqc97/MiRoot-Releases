package com.wmqc.miroot.record

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button
import com.wmqc.miroot.R
import com.wmqc.miroot.capability.EnvironmentProbe
import com.wmqc.miroot.capability.PrivilegedShell
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class SaveStrategyTestActivity : AppCompatActivity(R.layout.activity_save_strategy_test) {

    private lateinit var btnStart: Button
    private lateinit var btnShare: Button
    private lateinit var textStatus: TextView
    private lateinit var textLog: TextView
    private var logLines = mutableListOf<String>()
    private var isRunning = false

    companion object {
        private const val TEST_FILE_BYTES = 1024 * 1024 // 1MB
        private const val TAG = "MiRoot-SaveTest"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        btnStart = findViewById(R.id.btn_start_test)
        btnShare = findViewById(R.id.btn_share_log)
        textStatus = findViewById(R.id.text_status)
        textLog = findViewById(R.id.text_log)

        btnStart.setOnClickListener {
            if (isRunning) {
                toast("测试正在进行中")
                return@setOnClickListener
            }
            if (!EnvironmentProbe.shizukuServiceRunning()) {
                toast("Shizuku 服务未运行")
                return@setOnClickListener
            }
            if (!EnvironmentProbe.shizukuPermissionGranted()) {
                toast("Shizuku 未授权，请先在状态页授权")
                return@setOnClickListener
            }
            runTest()
        }

        btnShare.setOnClickListener {
            shareLog()
        }
    }

    private fun runTest() {
        isRunning = true
        btnStart.isEnabled = false
        textLog.text = ""
        logLines.clear()
        appendLog("=== 录制存储方案批量测试 ===")
        appendLog("设备: ${Build.MODEL} / ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLog("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLog("")

        thread {
            try {
                // Step 1: Create test file
                appendLog("[准备] 创建测试文件 (${TEST_FILE_BYTES / 1024}KB)...")
                updateStatus("正在创建测试文件...")
                val testFile = createTestFile() ?: run {
                    appendLog("[失败] 无法创建测试文件")
                    finishTest()
                    return@thread
                }
                val testSha = sha256sum(testFile)
                appendLog("[OK] 测试文件: $testFile (${File(testFile).length()} bytes, SHA256=$testSha)")
                appendLog("")

                // Step 2: Run each strategy
                val strategies = buildStrategies()
                var passed = 0
                var failed = 0

                for ((name, runner) in strategies) {
                    appendLog(">>> 测试: $name")
                    updateStatus("测试中: $name")
                    try {
                        val result = runner(testFile, testSha)
                        if (result.ok) {
                            appendLog("  [通过] ${result.detail}")
                            passed++
                        } else {
                            appendLog("  [失败] ${result.detail}")
                            failed++
                        }
                    } catch (e: Exception) {
                        appendLog("  [异常] ${e.javaClass.simpleName}: ${e.message}")
                        failed++
                    }
                    appendLog("")
                }

                // Step 3: Summary
                appendLog("=== 测试完成 ===")
                appendLog("通过: $passed / 失败: $failed / 总计: ${strategies.size}")
                updateStatus("测试完成: 通过 $passed / 失败 $failed")

                // Write log to file
                writeLogFile()

            } catch (e: Exception) {
                appendLog("[严重异常] ${e.javaClass.simpleName}: ${e.message}")
                e.stackTrace.forEach { appendLog("  $it") }
                updateStatus("测试异常: ${e.message}")
            } finally {
                finishTest()
            }
        }
    }

    private data class TestResult(val ok: Boolean, val detail: String)
    private data class Strategy(val name: String, val runner: (testFile: String, testSha: String) -> TestResult)

    private fun buildStrategies(): List<Strategy> {
        val ctx = this
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).absolutePath
        val filesDir = filesDir.absolutePath
        val cacheDir = cacheDir.absolutePath
        val appCtx = applicationContext

        return listOf(
            Strategy("1. Shell cp -> Movies/") { tf, _ ->
                val dst = "$moviesDir/miroot_test_cp.mp4"
                val ok = PrivilegedShell.execCmd("cp \"$tf\" \"$dst\" && sync")
                val exists = File(dst).isFile
                val size = if (exists) File(dst).length() else -1
                PrivilegedShell.execCmd("rm -f \"$dst\"")
                TestResult(ok && exists && size > 0L, "cp exit=$ok exists=$exists size=$size")
            },

            Strategy("2. Shell cp -> Movies/ + mediaScan") { tf, _ ->
                val dst = "$moviesDir/miroot_test_cp_scan.mp4"
                val ok = PrivilegedShell.execCmd("cp \"$tf\" \"$dst\" && sync")
                if (ok) mediaScanFile(dst)
                val exists = File(dst).isFile
                val size = if (exists) File(dst).length() else -1
                TestResult(ok && exists && size > 0L, "cp=$ok exists=$exists size=$size scan=yes")
            },

            Strategy("3. ContentResolver -> Movies/ (saveViaMediaStore)") { tf, _ ->
                val srcFile = File(tf)
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, "miroot_test_cr.mp4")
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies")
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    val uri = appCtx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri == null) return@Strategy TestResult(false, "insert returned null")
                    appCtx.contentResolver.openOutputStream(uri)?.use { out ->
                        srcFile.inputStream().use { inp -> inp.copyTo(out) }
                    } ?: run {
                        appCtx.contentResolver.delete(uri, null, null)
                        return@Strategy TestResult(false, "openOutputStream returned null")
                    }
                    TestResult(true, "write via ContentResolver OK")
                } catch (e: Exception) {
                    TestResult(false, "${e.javaClass.simpleName}: ${e.message}")
                }
            },

            Strategy("4. ContentResolver -> Movies/ (IS_PENDING=1 then 0)") { tf, _ ->
                val srcFile = File(tf)
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, "miroot_test_cr_pend.mp4")
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                    val uri = appCtx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri == null) return@Strategy TestResult(false, "insert returned null")
                    appCtx.contentResolver.openOutputStream(uri)?.use { out ->
                        srcFile.inputStream().use { inp -> inp.copyTo(out) }
                    } ?: run {
                        appCtx.contentResolver.delete(uri, null, null)
                        return@Strategy TestResult(false, "openOutputStream null")
                    }
                    // Set IS_PENDING = 0 to make visible
                    val updateValues = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    val updated = appCtx.contentResolver.update(uri, updateValues, null, null)
                    TestResult(updated > 0, "write+pending OK, updated=$updated rows")
                } catch (e: Exception) {
                    TestResult(false, "${e.javaClass.simpleName}: ${e.message}")
                }
            },

            Strategy("5. ContentResolver -> Downloads/") { tf, _ ->
                val srcFile = File(tf)
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, "miroot_test_cr_download.mp4")
                        put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                        put(MediaStore.Downloads.RELATIVE_PATH, "Download")
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    val uri = appCtx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri == null) return@Strategy TestResult(false, "insert Downloads returned null")
                    appCtx.contentResolver.openOutputStream(uri)?.use { out ->
                        srcFile.inputStream().use { inp -> inp.copyTo(out) }
                    } ?: run {
                        appCtx.contentResolver.delete(uri, null, null)
                        return@Strategy TestResult(false, "openOutputStream null")
                    }
                    TestResult(true, "write to Downloads OK")
                } catch (e: Exception) {
                    TestResult(false, "${e.javaClass.simpleName}: ${e.message}")
                }
            },

            Strategy("6. Shell cp -> app private files/") { tf, _ ->
                val dst = "$filesDir/miroot_test_private.mp4"
                val ok = PrivilegedShell.execCmd("cp \"$tf\" \"$dst\" && sync && chmod 644 \"$dst\"")
                val exists = File(dst).isFile
                val size = if (exists) File(dst).length() else -1
                TestResult(ok && exists && size > 0L, "cp=$ok exists=$exists size=$size")
            },

            Strategy("7. Shell cp -> app cache/") { tf, _ ->
                val dst = "$cacheDir/miroot_test_cache.mp4"
                val ok = PrivilegedShell.execCmd("cp \"$tf\" \"$dst\" && sync")
                val exists = File(dst).isFile
                val size = if (exists) File(dst).length() else -1
                TestResult(ok && exists && size > 0L, "cp=$ok exists=$exists size=$size")
            },

            Strategy("8. FileOutputStream -> app private files/") { tf, _ ->
                val srcFile = File(tf)
                val dstFile = File(filesDir, "miroot_test_fos.mp4")
                try {
                    FileOutputStream(dstFile).use { out ->
                        srcFile.inputStream().use { inp -> inp.copyTo(out) }
                    }
                    TestResult(dstFile.isFile && dstFile.length() > 0L,
                        "write OK size=${dstFile.length()}")
                } catch (e: Exception) {
                    TestResult(false, "${e.javaClass.simpleName}: ${e.message}")
                }
            },

            Strategy("9. FileOutputStream -> app cache/") { tf, _ ->
                val srcFile = File(tf)
                val dstFile = File(cacheDir, "miroot_test_fos_c.mp4")
                try {
                    FileOutputStream(dstFile).use { out ->
                        srcFile.inputStream().use { inp -> inp.copyTo(out) }
                    }
                    TestResult(dstFile.isFile && dstFile.length() > 0L,
                        "write OK size=${dstFile.length()}")
                } catch (e: Exception) {
                    TestResult(false, "${e.javaClass.simpleName}: ${e.message}")
                }
            },

            Strategy("10. Shell cp -> Movies/ (verify SHA256)") { tf, sha ->
                val dst = "$moviesDir/miroot_test_verify.mp4"
                val ok = PrivilegedShell.execCmd("cp \"$tf\" \"$dst\" && sync")
                if (!ok) return@Strategy TestResult(false, "cp failed")
                val dstSha = sha256sum(dst)
                val match = dstSha == sha
                PrivilegedShell.execCmd("rm -f \"$dst\"")
                TestResult(match, "cp=OK sha_match=$match expected=$sha got=$dstSha")
            },
        )
    }

    private fun createTestFile(): String? {
        val tmpPath = "$cacheDir/miroot_test_source.bin"
        return try {
            val ok = PrivilegedShell.execCmd("dd if=/dev/zero of=\"$tmpPath\" bs=1024 count=1024 2>/dev/null && sync")
            if (!ok || !File(tmpPath).isFile) {
                // Fallback: create via FileOutputStream
                File(tmpPath).outputStream().use { out ->
                    out.write(ByteArray(TEST_FILE_BYTES))
                }
            }
            if (File(tmpPath).isFile && File(tmpPath).length() > 0L) tmpPath else null
        } catch (e: Exception) {
            try {
                File(tmpPath).outputStream().use { out ->
                    out.write(ByteArray(TEST_FILE_BYTES))
                }
                if (File(tmpPath).isFile) tmpPath else null
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun sha256sum(path: String): String {
        val out = PrivilegedShell.captureOutput("sha256sum \"$path\" 2>/dev/null | cut -d' ' -f1")
        if (!out.isNullOrBlank()) return out.trim()
        // fallback: just use file size as a simple check
        return "size:${File(path).length()}"
    }

    private fun mediaScanFile(path: String) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DATA, path)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            applicationContext.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        } catch (_: Exception) {}
    }

    private fun writeLogFile() {
        try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val logDir = File(filesDir, "test_logs")
            logDir.mkdirs()
            val logFile = File(logDir, "SaveStrategyTest_$ts.log")
            logFile.writeText(logLines.joinToString("\n"))
            appendLog("[日志已保存] ${logFile.absolutePath}")
            appendLog("使用分享按钮或手动从文件管理器取出该文件")
        } catch (e: Exception) {
            appendLog("[日志保存失败] ${e.message}")
        }
    }

    private fun shareLog() {
        try {
            val logDir = File(filesDir, "test_logs")
            val latest = logDir.listFiles()?.maxByOrNull { it.lastModified() }
            if (latest != null && latest.isFile) {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this, "${packageName}.fileprovider", latest
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "分享测试日志"))
            } else {
                toast("没有可分享的日志文件")
            }
        } catch (e: Exception) {
            toast("分享失败: ${e.message}")
        }
    }

    private fun appendLog(line: String) {
        logLines.add(line)
        runOnUiThread {
            textLog.append(line + "\n")
            // Auto-scroll to bottom
            val scroll = textLog.parent as? android.widget.ScrollView
            scroll?.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    private fun updateStatus(msg: String) {
        runOnUiThread { textStatus.text = msg }
    }

    private fun toast(msg: String) {
        runOnUiThread {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun finishTest() {
        isRunning = false
        runOnUiThread { btnStart.isEnabled = true }
    }
}