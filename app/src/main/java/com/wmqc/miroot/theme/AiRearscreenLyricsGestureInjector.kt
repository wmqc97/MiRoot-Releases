package com.wmqc.miroot.theme

import android.content.Context
import com.wmqc.miroot.lyrics.ITaskService
import com.wmqc.miroot.lyrics.LogHelper
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

enum class GestureInjectOutcome {
    OK,
    REARSCREEN_NOT_FOUND,
    ZIP_READ_FAILED,
    MANIFEST_MISSING,
    MANIFEST_INCOMPATIBLE,
    WRITE_FAILED,
}

object AiRearscreenLyricsGestureInjector {
    private const val TAG = "AiRearscreenInject"

    private enum class GesturePatchResult {
        OK,
        MANIFEST_MISSING,
        MANIFEST_INCOMPATIBLE,
        IO_ERROR,
    }

    private const val ZIP_ENTRY_MANIFEST = "manifest.xml"
    private const val ZIP_ENTRY_VAR_CONFIG = "var_config.xml"
    private const val ASSET_GESTURE_GROUP = "theme_inject/miroot_gesture_group.xml"

    // ── 预编译正则 ──────────────────────────────────────────────
    private val RE_GESTURE_GROUP = Regex(
        """(?is)<Group\b[^>]*\bname\s*=\s*["']miroot_gesture_inject["'][^>]*>.*?</Group>"""
    )
    private val RE_LEGACY_BUTTONS = Regex(
        """(?is)<Button\b[^>]*\bname\s*=\s*["'](?:MusicControl|miroot_bottom_gesture)["'][^>]*>.*?</Button>"""
    )
    private val RE_LEGACY_FUNCTIONS = Regex(
        """(?is)<Function\b[^>]*\bname\s*=\s*["'](?:openMusicProjection|toggleMusic|showSlider)["'][^>]*>.*?</Function>"""
    )
    private val GESTURE_ONOFF_NAMES = listOf(
        "musicBroadcastOn", "rearDesktopSwipeOn", "carControlSwipeOn",
        "miroot_bottom_swipe_up_on", "miroot_bottom_swipe_left_on",
        "miroot_bottom_swipe_right_on", "miroot_gesture_injected",
    )
    private val RE_GESTURE_VARS = Regex(
        """(?is)<Var\b[^>]*\bname\s*=\s*["']miroot_gs[123][ak]["'][^>]*/>\s*"""
    )

    // ── 公开 API ────────────────────────────────────────────────

    fun injectGestureIntoLocalZipFile(context: Context, inputZip: File, outputZip: File): Boolean {
        if (!inputZip.isFile || inputZip.length() == 0L) return false
        outputZip.parentFile?.takeIf { !it.exists() }?.mkdirs()
        return rebuildZip(context, inputZip, outputZip, inject = true) == GesturePatchResult.OK
    }

    fun removeGestureFromLocalZipFile(context: Context, inputZip: File, outputZip: File): Boolean {
        if (!inputZip.isFile || inputZip.length() == 0L) return false
        outputZip.parentFile?.takeIf { !it.exists() }?.mkdirs()
        return rebuildZip(context, inputZip, outputZip, inject = false) == GesturePatchResult.OK
    }

    fun inject(
        context: Context,
        taskService: ITaskService,
        aiDirectoryName: String,
        workDir: File,
    ): GestureInjectOutcome {
        if (aiDirectoryName.isBlank()) return GestureInjectOutcome.REARSCREEN_NOT_FOUND
        val srcRearscreen =
            AiWallpaperThemeHelper.resolveExistingMamlRearscreenPath(taskService, aiDirectoryName)
                ?: run {
                    LogHelper.w(TAG, "rearscreen not found for dir=$aiDirectoryName")
                    return GestureInjectOutcome.REARSCREEN_NOT_FOUND
                }
        workDir.mkdirs()
        val ts = System.currentTimeMillis()
        val inputZip = File(workDir, "rearscreen_in_$ts.zip")
        val outputZip = File(workDir, "rearscreen_out_$ts.zip")
        try {
            AiWallpaperThemeHelper.prepareThemeShellWorkDirs(taskService)
            if (!copyRearscreenToWorkDir(taskService, srcRearscreen, inputZip)) {
                return GestureInjectOutcome.ZIP_READ_FAILED
            }
            when (rebuildZip(context, inputZip, outputZip, inject = true)) {
                GesturePatchResult.OK -> Unit
                GesturePatchResult.MANIFEST_MISSING -> return GestureInjectOutcome.MANIFEST_MISSING
                GesturePatchResult.MANIFEST_INCOMPATIBLE -> return GestureInjectOutcome.MANIFEST_INCOMPATIBLE
                GesturePatchResult.IO_ERROR -> return GestureInjectOutcome.ZIP_READ_FAILED
            }
            if (!outputZip.isFile || outputZip.length() == 0L) {
                LogHelper.w(TAG, "patched output zip missing/empty")
                return GestureInjectOutcome.ZIP_READ_FAILED
            }
            AiWallpaperThemeHelper.ensureShellReadable(taskService, outputZip)
            val replaced = AiWallpaperThemeHelper.replaceRootOwnedFile(
                taskService, srcRearscreen, outputZip.absolutePath, false,
            )
            if (replaced) {
                LogHelper.d(TAG, "AI rearscreen: gesture inject written (dir=$aiDirectoryName)")
                return GestureInjectOutcome.OK
            }
            LogHelper.w(TAG, "replaceRootOwnedFile failed dest=$srcRearscreen")
            return GestureInjectOutcome.WRITE_FAILED
        } catch (e: Exception) {
            LogHelper.w(TAG, "inject failed", e)
            return GestureInjectOutcome.WRITE_FAILED
        } finally {
            cleanupWorkFiles(taskService, inputZip, outputZip)
        }
    }

    // ── 内部实现 ────────────────────────────────────────────────

    private fun copyRearscreenToWorkDir(
        taskService: ITaskService, srcRearscreen: String, destZip: File,
    ): Boolean {
        val tmp = destZip.absolutePath
        val ok = AiWallpaperThemeHelper.themeShellResultOk(
            taskService,
            "test -f \"$srcRearscreen\" && cp \"$srcRearscreen\" \"$tmp\" && chmod a+rw \"$tmp\" && echo ok || echo no",
        )
        if (!ok) {
            LogHelper.w(TAG, "copy rearscreen failed src=$srcRearscreen")
            return false
        }
        AiWallpaperThemeHelper.ensureAppReadable(tmp)
        if (!destZip.isFile || destZip.length() == 0L) {
            LogHelper.w(TAG, "work zip missing/empty path=$tmp")
            return false
        }
        return true
    }

    private fun cleanupWorkFiles(taskService: ITaskService, vararg files: File) {
        runCatching {
            val paths = files.joinToString(" ") { "\"${it.absolutePath}\"" }
            AiWallpaperThemeHelper.themeShellCommand(taskService, "rm -f $paths")
        }
        files.forEach { it.delete() }
    }

    /**
     * 流式重建 zip：仅将 manifest.xml / var_config.xml 读入内存做 patch，
     * 其余 entry（含大视频文件）直接流式拷贝，避免 OOM。
     */
    private fun rebuildZip(
        context: Context, inputZip: File, outputZip: File, inject: Boolean,
    ): GesturePatchResult {
        val zipFile = try {
            AiWallpaperThemeHelper.openZipFileForTheme(inputZip)
        } catch (e: Exception) {
            LogHelper.w(TAG, "rebuildZip: cannot open zip", e)
            return GesturePatchResult.IO_ERROR
        }
        try {
            // 第一轮：仅读取 manifest / var_config
            var manifestBytes: ByteArray? = null
            var varConfigBytes: ByteArray? = null
            val entries = zipFile.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                when (entry.name) {
                    ZIP_ENTRY_MANIFEST ->
                        zipFile.getInputStream(entry).use { manifestBytes = readAllBytes(it) }
                    ZIP_ENTRY_VAR_CONFIG ->
                        zipFile.getInputStream(entry).use { varConfigBytes = readAllBytes(it) }
                }
            }

            val mIn = manifestBytes
            if (mIn == null) {
                LogHelper.w(TAG, "zip missing manifest.xml")
                return GesturePatchResult.MANIFEST_MISSING
            }

            // 处理 var_config
            val patchedVarBytes: ByteArray? = if (inject) {
                val vIn = varConfigBytes
                val varText = if (vIn != null) {
                    String(vIn, StandardCharsets.UTF_8).trimStart('\uFEFF')
                } else {
                    LogHelper.d(TAG, "zip has no var_config.xml; injecting minimal WidgetConfig")
                    null
                }
                val patchedVarText = if (varText != null) patchVarConfigForGesture(varText)
                else newMinimalVarConfigXml()
                patchedVarText.toByteArray(StandardCharsets.UTF_8)
            } else {
                varConfigBytes?.let { vIn ->
                    val varText = String(vIn, StandardCharsets.UTF_8).trimStart('\uFEFF')
                    removeGestureFromVarConfig(varText).toByteArray(StandardCharsets.UTF_8)
                }
            }

            // 处理 manifest
            val manifestText = String(mIn, StandardCharsets.UTF_8).trimStart('\uFEFF')
            val patchedManifestText = if (inject) {
                patchManifestForGesture(context, manifestText)
                    ?: run {
                        LogHelper.w(TAG, "manifest gesture patch failed")
                        return GesturePatchResult.MANIFEST_INCOMPATIBLE
                    }
            } else {
                removeGestureFromManifest(manifestText)
            }
            val patchedManifestBytes = patchedManifestText.toByteArray(StandardCharsets.UTF_8)

            // 第二轮：流式写入
            var hasVarConfigEntry = false
            outputZip.parentFile?.takeIf { !it.exists() }?.mkdirs()
            ZipOutputStream(FileOutputStream(outputZip)).use { zos ->
                val entries2 = zipFile.entries()
                while (entries2.hasMoreElements()) {
                    val entry = entries2.nextElement()
                    val newEntry = ZipEntry(entry.name).apply { time = entry.time }
                    zos.putNextEntry(newEntry)
                    if (!entry.isDirectory) {
                        when (entry.name) {
                            ZIP_ENTRY_MANIFEST -> zos.write(patchedManifestBytes)
                            ZIP_ENTRY_VAR_CONFIG -> {
                                if (patchedVarBytes != null) zos.write(patchedVarBytes)
                                hasVarConfigEntry = true
                            }
                            else -> zipFile.getInputStream(entry).use { it.copyTo(zos) }
                        }
                    }
                    zos.closeEntry()
                }
                // 注入模式下，若原 zip 无 var_config 则追加
                if (inject && !hasVarConfigEntry && patchedVarBytes != null) {
                    val newEntry = ZipEntry(ZIP_ENTRY_VAR_CONFIG).apply {
                        time = System.currentTimeMillis()
                    }
                    zos.putNextEntry(newEntry)
                    zos.write(patchedVarBytes)
                    zos.closeEntry()
                }
                zos.flush()
            }
        } catch (e: Exception) {
            LogHelper.w(TAG, "rebuildZip failed", e)
            return GesturePatchResult.IO_ERROR
        } finally {
            runCatching { zipFile.close() }
        }
        AiWallpaperThemeHelper.ensureShellReadable(null, outputZip)
        return GesturePatchResult.OK
    }

    // ── Manifest patch ──

    private fun patchManifestForGesture(context: Context, xml: String): String? {
        val cleaned = removeGestureFromManifest(xml)
        tryInjectUniversalGesture(context, cleaned)?.let {
            LogHelper.d(TAG, "manifest: gesture inject applied (before </Widget>)")
            return it
        }
        return patchManifestLegacyAiVideo(context, cleaned)
    }

    private fun removeGestureFromManifest(xml: String): String {
        var s = xml
        s = runCatching { RE_GESTURE_GROUP.replace(s, "") }.getOrDefault(s)
        s = runCatching { RE_LEGACY_BUTTONS.replace(s, "") }.getOrDefault(s)
        s = runCatching { RE_LEGACY_FUNCTIONS.replace(s, "") }.getOrDefault(s)
        return s
    }

    private fun tryInjectUniversalGesture(context: Context, xml: String): String? {
        if (!xml.contains("<Widget", ignoreCase = true)) return null
        val closeTag = "</Widget>"
        val idx = xml.lastIndexOf(closeTag, ignoreCase = true)
        if (idx < 0) return null
        val tail = xml.substring(idx + closeTag.length)
        if (!isAcceptableTailAfterRootWidgetClose(tail)) {
            LogHelper.d(TAG, "manifest: skip universal anchor (non-empty tail)")
            return null
        }
        return xml.substring(0, idx) + loadAssetText(context, ASSET_GESTURE_GROUP) + "\n" + xml.substring(idx)
    }

    private fun patchManifestLegacyAiVideo(context: Context, xml: String): String? {
        if (!xml.contains("<ExternalCommands>", ignoreCase = true)) return null
        val idx = xml.indexOf("<ExternalCommands>", ignoreCase = true)
        if (idx < 0) return null
        return xml.substring(0, idx) + loadAssetText(context, ASSET_GESTURE_GROUP) + "\n" + xml.substring(idx)
    }

    private fun isAcceptableTailAfterRootWidgetClose(tail: String): Boolean {
        var s = tail.trim()
        if (s.isEmpty()) return true
        while (s.isNotEmpty()) {
            if (s.startsWith("<!--")) {
                val end = s.indexOf("-->")
                if (end < 0) return true
                s = s.substring(end + 3).trim()
                continue
            }
            return s.all { it.isWhitespace() }
        }
        return true
    }

    // ── VarConfig patch ──

    private fun patchVarConfigForGesture(xml: String): String {
        val stripped = removeGestureFromVarConfig(xml)
        val base = unwrapVarConfigOuterCommentIfNeeded(stripped)
        val closing = "</WidgetConfig>"
        val idx = base.lastIndexOf(closing, ignoreCase = true)
        if (idx < 0) return base
        val head = base.substring(0, idx).trimEnd()
        return head + "\n" + gestureInjectedMarkerBlock() + "\n" + closing
    }

    private fun removeGestureFromVarConfig(xml: String): String {
        var s = unwrapVarConfigOuterCommentIfNeeded(xml)
        for (name in GESTURE_ONOFF_NAMES) {
            s = runCatching {
                val re = Regex("""(?is)<OnOff\b[^>]*\bname\s*=\s*["$name["'][^>]*>.*?</OnOff>""")
                re.replace(s, "")
            }.getOrDefault(s)
        }
        s = runCatching { RE_GESTURE_VARS.replace(s, "") }.getOrDefault(s)
        return s
    }

    private fun unwrapVarConfigOuterCommentIfNeeded(xml: String): String {
        val t = xml.trim()
        if (!t.startsWith("<!--") || !t.endsWith("-->")) return xml
        val inner = t.substring(4, t.length - 3).trim()
        if (!inner.contains("<WidgetConfig", ignoreCase = true)) return xml
        return inner
    }

    private fun newMinimalVarConfigXml(): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<WidgetConfig version=\"1\" des=\"MiRoot\">\n" +
            gestureInjectedMarkerBlock() + "\n</WidgetConfig>\n"

    private fun gestureInjectedMarkerBlock(): String =
        """  <OnOff name="miroot_gesture_injected" displayTitle="MiRoot手势已注入" default="1">
    <Language displayTitle="MiRoot gesture injected" locale="bo_CN"/>
    <Language displayTitle="MiRoot gesture injected" locale="en_US"/>
    <Language displayTitle="MiRoot gesture injected" locale="ug_CN"/>
    <Language displayTitle="MiRoot手势已注入" locale="zh_CN"/>
    <Language displayTitle="MiRoot手勢已注入" locale="zh_HK"/>
    <Language displayTitle="MiRoot手勢已注入" locale="zh_TW"/>
  </OnOff>"""

    // ── 工具方法 ──

    private fun readAllBytes(input: InputStream): ByteArray {
        val buffer = ByteArray(8192)
        val baos = ByteArrayOutputStream()
        while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            baos.write(buffer, 0, n)
        }
        return baos.toByteArray()
    }

    private fun loadAssetText(context: Context, path: String): String {
        return context.assets.open(path).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }.trimEnd() + "\n"
    }
}