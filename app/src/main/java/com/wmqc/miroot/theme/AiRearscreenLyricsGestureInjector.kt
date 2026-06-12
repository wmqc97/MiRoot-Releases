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

    /**
     * 在 rearscreen zip 内增量修改 [ZIP_ENTRY_MANIFEST] 与 [ZIP_ENTRY_VAR_CONFIG]。
     *
     * **Manifest**
     * 1. 移除旧版底栏 / 旧直发广播 / 旧 [miroot_gesture_inject] / MusicControl+Slider 等。
     * 2. 写入三槽位手势层（仅用 #view_width/#view_height/#touch_* 写死几何，不注入自定义 Var、不读 var_config 开关）。
     *
     * **var_config**
     * - 仅追加 [miroot_gesture_injected] OnOff 标识注入成功。
     */
    fun injectGestureIntoLocalZipFile(context: Context, inputZip: File, outputZip: File): Boolean {
        if (!inputZip.isFile || inputZip.length() == 0L) return false
        outputZip.parentFile?.takeIf { !it.exists() }?.mkdirs()
        return rebuildZipWithGesturePatches(context, inputZip, outputZip) == GesturePatchResult.OK
    }

    fun removeGestureFromLocalZipFile(context: Context, inputZip: File, outputZip: File): Boolean {
        if (!inputZip.isFile || inputZip.length() == 0L) return false
        outputZip.parentFile?.takeIf { !it.exists() }?.mkdirs()
        return rebuildZipWithGestureRemovePatches(context, inputZip, outputZip)
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
        val tmpIn = inputZip.absolutePath
        val tmpOut = outputZip.absolutePath

        try {
            val copied =
                AiWallpaperThemeHelper.themeShellResultOk(
                    taskService,
                    "test -f \"$srcRearscreen\" && cp \"$srcRearscreen\" \"$tmpIn\" " +
                        "&& chmod a+rw \"$tmpIn\" && echo ok || echo no",
                )
            if (!copied) {
                LogHelper.w(TAG, "copy rearscreen to work dir failed src=$srcRearscreen")
                return GestureInjectOutcome.ZIP_READ_FAILED
            }
            if (!inputZip.isFile || inputZip.length() == 0L) {
                LogHelper.w(TAG, "work input zip missing/empty path=$tmpIn")
                return GestureInjectOutcome.ZIP_READ_FAILED
            }

            when (rebuildZipWithGesturePatches(context, inputZip, outputZip)) {
                GesturePatchResult.OK -> Unit
                GesturePatchResult.MANIFEST_MISSING -> return GestureInjectOutcome.MANIFEST_MISSING
                GesturePatchResult.MANIFEST_INCOMPATIBLE -> return GestureInjectOutcome.MANIFEST_INCOMPATIBLE
                GesturePatchResult.IO_ERROR -> return GestureInjectOutcome.ZIP_READ_FAILED
            }
            if (!outputZip.isFile || outputZip.length() == 0L) {
                LogHelper.w(TAG, "patched output zip missing/empty path=$tmpOut")
                return GestureInjectOutcome.ZIP_READ_FAILED
            }
            outputZip.setReadable(true, false)
            AiWallpaperThemeHelper.themeShellCommand(taskService, "chmod a+r \"$tmpOut\"")

            val replaced =
                AiWallpaperThemeHelper.replaceRootOwnedFile(
                    taskService,
                    srcRearscreen,
                    tmpOut,
                    false,
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
            runCatching {
                AiWallpaperThemeHelper.themeShellCommand(taskService, "rm -f \"$tmpIn\" \"$tmpOut\"")
            }
            inputZip.delete()
            outputZip.delete()
        }
    }

    fun remove(
        context: Context,
        taskService: ITaskService,
        aiDirectoryName: String,
        workDir: File,
    ): Boolean {
        if (aiDirectoryName.isBlank()) return false
        val srcRearscreen =
            AiWallpaperThemeHelper.resolveExistingMamlRearscreenPath(taskService, aiDirectoryName)
                ?: return false
        workDir.mkdirs()
        val ts = System.currentTimeMillis()
        val inputZip = File(workDir, "rearscreen_in_$ts.zip")
        val outputZip = File(workDir, "rearscreen_out_$ts.zip")
        val tmpIn = inputZip.absolutePath
        val tmpOut = outputZip.absolutePath

        try {
            val copied =
                AiWallpaperThemeHelper.themeShellResultOk(
                    taskService,
                    "test -f \"$srcRearscreen\" && cp \"$srcRearscreen\" \"$tmpIn\" " +
                        "&& chmod a+rw \"$tmpIn\" && echo ok || echo no",
                )
            if (!copied) return false

            if (!inputZip.isFile || inputZip.length() == 0L) return false

            if (!rebuildZipWithGestureRemovePatches(context, inputZip, outputZip)) return false
            if (!outputZip.isFile || outputZip.length() == 0L) return false

            outputZip.setReadable(true, false)
            AiWallpaperThemeHelper.themeShellCommand(taskService, "chmod a+r \"$tmpOut\"")

            return AiWallpaperThemeHelper.replaceRootOwnedFile(
                taskService,
                srcRearscreen,
                tmpOut,
                false,
            )
        } catch (e: Exception) {
            LogHelper.w(TAG, "remove failed", e)
            return false
        } finally {
            runCatching {
                AiWallpaperThemeHelper.themeShellCommand(taskService, "rm -f \"$tmpIn\" \"$tmpOut\"")
            }
            inputZip.delete()
            outputZip.delete()
        }
    }

    private fun readAllBytesCompat(input: InputStream): ByteArray {
        val buffer = ByteArray(8192)
        val baos = ByteArrayOutputStream()
        while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            baos.write(buffer, 0, n)
        }
        return baos.toByteArray()
    }

    private enum class ZipWriteKind {
        MANIFEST_PATCH,
        VAR_CONFIG_PATCH,
        PASS_THROUGH,
    }

    private data class ZipWriteSlot(
        val kind: ZipWriteKind,
        val entryName: String,
        val time: Long,
        val isDirectory: Boolean,
        val rawBytes: ByteArray?,
    )

    private fun rebuildZipWithGesturePatches(context: Context, inputZip: File, outputZip: File): GesturePatchResult {
        val zipFile = AiWallpaperThemeHelper.openZipFileForTheme(inputZip)
        try {
            var manifestBytes: ByteArray? = null
            var varConfigBytes: ByteArray? = null
            val slots = ArrayList<ZipWriteSlot>()

            val entries = zipFile.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                if (entry.isDirectory) {
                    slots.add(ZipWriteSlot(ZipWriteKind.PASS_THROUGH, name, entry.time, true, null))
                    continue
                }
                when (name) {
                    ZIP_ENTRY_MANIFEST -> {
                        zipFile.getInputStream(entry).use { manifestBytes = readAllBytesCompat(it) }
                        slots.add(ZipWriteSlot(ZipWriteKind.MANIFEST_PATCH, name, entry.time, false, null))
                    }
                    ZIP_ENTRY_VAR_CONFIG -> {
                        zipFile.getInputStream(entry).use { varConfigBytes = readAllBytesCompat(it) }
                        slots.add(ZipWriteSlot(ZipWriteKind.VAR_CONFIG_PATCH, name, entry.time, false, null))
                    }
                    else -> {
                        val data = zipFile.getInputStream(entry).use { readAllBytesCompat(it) }
                        slots.add(ZipWriteSlot(ZipWriteKind.PASS_THROUGH, name, entry.time, false, data))
                    }
                }
            }

            val mIn = manifestBytes
            var vIn = varConfigBytes
            if (mIn == null) {
                LogHelper.w(TAG, "zip missing manifest.xml")
                return GesturePatchResult.MANIFEST_MISSING
            }
            if (vIn == null) {
                LogHelper.d(TAG, "zip has no root var_config.xml; injecting new minimal WidgetConfig")
                vIn = newMinimalVarConfigXml().toByteArray(StandardCharsets.UTF_8)
                slots.add(
                    ZipWriteSlot(
                        ZipWriteKind.VAR_CONFIG_PATCH,
                        ZIP_ENTRY_VAR_CONFIG,
                        System.currentTimeMillis(),
                        false,
                        null,
                    ),
                )
            }

            val manifestText = String(mIn, StandardCharsets.UTF_8).trimStart('\uFEFF')
            val patchedManifest = patchManifestForGesture(context, manifestText)
            if (patchedManifest == null) {
                LogHelper.w(TAG, "manifest gesture patch failed (no universal </Widget> anchor and no ExternalCommands)")
                return GesturePatchResult.MANIFEST_INCOMPATIBLE
            }
            val patchedManifestBytes = patchedManifest.toByteArray(StandardCharsets.UTF_8)

            val varText = String(vIn, StandardCharsets.UTF_8).trimStart('\uFEFF')
            val patchedVarBytes = patchVarConfigForGesture(varText).toByteArray(StandardCharsets.UTF_8)

            outputZip.parentFile?.takeIf { !it.exists() }?.mkdirs()
            ZipOutputStream(FileOutputStream(outputZip)).use { zos ->
                for (slot in slots) {
                    val newEntry = ZipEntry(slot.entryName).apply { time = slot.time }
                    zos.putNextEntry(newEntry)
                    when (slot.kind) {
                        ZipWriteKind.MANIFEST_PATCH -> zos.write(patchedManifestBytes)
                        ZipWriteKind.VAR_CONFIG_PATCH -> zos.write(patchedVarBytes)
                        ZipWriteKind.PASS_THROUGH -> {
                            if (!slot.isDirectory && slot.rawBytes != null) {
                                zos.write(slot.rawBytes)
                            }
                        }
                    }
                    zos.closeEntry()
                }
                zos.flush()
            }
        } catch (e: Exception) {
            LogHelper.w(TAG, "rebuildZipWithGesturePatches failed", e)
            return GesturePatchResult.IO_ERROR
        } finally {
            runCatching { zipFile.close() }
        }
        return GesturePatchResult.OK
    }

    private fun rebuildZipWithGestureRemovePatches(context: Context, inputZip: File, outputZip: File): Boolean {
        val zipFile = AiWallpaperThemeHelper.openZipFileForTheme(inputZip)
        try {
            var manifestBytes: ByteArray? = null
            var varConfigBytes: ByteArray? = null
            val slots = ArrayList<ZipWriteSlot>()

            val entries = zipFile.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                if (entry.isDirectory) {
                    slots.add(ZipWriteSlot(ZipWriteKind.PASS_THROUGH, name, entry.time, true, null))
                    continue
                }
                when (name) {
                    ZIP_ENTRY_MANIFEST -> {
                        zipFile.getInputStream(entry).use { manifestBytes = readAllBytesCompat(it) }
                        slots.add(ZipWriteSlot(ZipWriteKind.MANIFEST_PATCH, name, entry.time, false, null))
                    }
                    ZIP_ENTRY_VAR_CONFIG -> {
                        zipFile.getInputStream(entry).use { varConfigBytes = readAllBytesCompat(it) }
                        slots.add(ZipWriteSlot(ZipWriteKind.VAR_CONFIG_PATCH, name, entry.time, false, null))
                    }
                    else -> {
                        val data = zipFile.getInputStream(entry).use { readAllBytesCompat(it) }
                        slots.add(ZipWriteSlot(ZipWriteKind.PASS_THROUGH, name, entry.time, false, data))
                    }
                }
            }

            val mIn = manifestBytes ?: return false
            val vIn = varConfigBytes

            val manifestText = String(mIn, StandardCharsets.UTF_8).trimStart('\uFEFF')
            val patchedManifestBytes =
                removeGestureFromManifest(manifestText).toByteArray(StandardCharsets.UTF_8)

            val patchedVarBytes =
                if (vIn != null) {
                    val varText = String(vIn, StandardCharsets.UTF_8).trimStart('\uFEFF')
                    removeGestureFromVarConfig(varText).toByteArray(StandardCharsets.UTF_8)
                } else {
                    null
                }

            outputZip.parentFile?.takeIf { !it.exists() }?.mkdirs()
            ZipOutputStream(FileOutputStream(outputZip)).use { zos ->
                for (slot in slots) {
                    val newEntry = ZipEntry(slot.entryName).apply { time = slot.time }
                    zos.putNextEntry(newEntry)
                    when (slot.kind) {
                        ZipWriteKind.MANIFEST_PATCH -> zos.write(patchedManifestBytes)
                        ZipWriteKind.VAR_CONFIG_PATCH -> {
                            if (patchedVarBytes != null) {
                                zos.write(patchedVarBytes)
                            } else if (!slot.isDirectory && slot.rawBytes != null) {
                                zos.write(slot.rawBytes)
                            }
                        }
                        ZipWriteKind.PASS_THROUGH -> {
                            if (!slot.isDirectory && slot.rawBytes != null) {
                                zos.write(slot.rawBytes)
                            }
                        }
                    }
                    zos.closeEntry()
                }
                zos.flush()
            }
        } catch (e: Exception) {
            LogHelper.w(TAG, "rebuildZipWithGestureRemovePatches failed", e)
            return false
        } finally {
            runCatching { zipFile.close() }
        }
        return true
    }

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
        s =
            runCatching {
                val re =
                    Regex(
                        """(?is)<Group\b[^>]*\bname\s*=\s*["']miroot_gesture_inject["'][^>]*>.*?</Group>""",
                    )
                s.replace(re, "")
            }.getOrDefault(s)

        s =
            runCatching {
                val re =
                    Regex(
                        """(?is)<Button\b[^>]*\bname\s*=\s*["']bottom_gesture_unified["'][^>]*>.*?</Button>""",
                    )
                s.replace(re, "")
            }.getOrDefault(s)

        s =
            runCatching {
                val re =
                    Regex(
                        """(?is)<Slider\b[^>]*\bname\s*=\s*["']swipe_up_broadcast["'][^>]*>.*?</Slider>""",
                    )
                s.replace(re, "")
            }.getOrDefault(s)

        s =
            runCatching {
                val re =
                    Regex(
                        """(?is)<MusicControl\b[^>]*\bname\s*=\s*["']music_control["'][^>]*/>""",
                    )
                s.replace(re, "")
            }.getOrDefault(s)

        val varNames =
            listOf(
                "music_state",
                "musicBroadcastOn",
                "miroot_bottom_swipe_up_on",
                "miroot_bottom_swipe_left_on",
                "miroot_bottom_swipe_right_on",
            )
        for (name in varNames) {
            s =
                runCatching {
                    val re =
                        Regex(
                            """(?is)<Var\b[^>]*\bname\s*=\s*["']$name["'][^>]*/>\s*""",
                        )
                    s.replace(re, "")
                }.getOrDefault(s)
        }

        val fnNames =
            listOf(
                "openMusicProjection",
                "openRearDesktop",
                "openCarControlProjection",
                "miroot_openMusicProjection",
                "miroot_openRearDesktop",
                "miroot_openCarControlProjection",
                "miroot_emit_s1",
                "miroot_emit_s2",
                "miroot_emit_s3",
            )
        for (fn in fnNames) {
            s =
                runCatching {
                    val re =
                        Regex(
                            """(?is)<Function\b[^>]*\bname\s*=\s*["']$fn["'][^>]*>.*?</Function>""",
                        )
                    s.replace(re, "")
                }.getOrDefault(s)
        }

        return s
    }

    private fun removeGestureFromVarConfig(xml: String): String {
        var s = unwrapVarConfigOuterCommentIfNeeded(xml)
        val onOffNames =
            listOf(
                "musicBroadcastOn",
                "rearDesktopSwipeOn",
                "carControlSwipeOn",
                "miroot_bottom_swipe_up_on",
                "miroot_bottom_swipe_left_on",
                "miroot_bottom_swipe_right_on",
                "miroot_gesture_injected",
            )
        for (name in onOffNames) {
            s =
                runCatching {
                    val re =
                        Regex(
                            """(?is)<OnOff\b[^>]*\bname\s*=\s*["']$name["'][^>]*>.*?</OnOff>""",
                        )
                    s.replace(re, "")
                }.getOrDefault(s)
        }
        s =
            runCatching {
                val re =
                    Regex(
                        """(?is)<Var\b[^>]*\bname\s*=\s*["']miroot_gs[123][ak]["'][^>]*/>\s*""",
                    )
                s.replace(re, "")
            }.getOrDefault(s)
        return s
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

    private fun tryInjectUniversalGesture(context: Context, xml: String): String? {
        if (!xml.contains("<Widget", ignoreCase = true)) {
            return null
        }
        val closeTag = "</Widget>"
        val idx = xml.lastIndexOf(closeTag, ignoreCase = true)
        if (idx < 0) {
            return null
        }
        val tail = xml.substring(idx + closeTag.length)
        if (!isAcceptableTailAfterRootWidgetClose(tail)) {
            LogHelper.d(TAG, "manifest: skip universal anchor (non-empty tail after last </Widget>)")
            return null
        }
        return xml.substring(0, idx) + loadAssetText(context, ASSET_GESTURE_GROUP) + "\n" + xml.substring(idx)
    }

    private fun patchManifestLegacyAiVideo(context: Context, xml: String): String? {
        if (!xml.contains("<ExternalCommands>", ignoreCase = true)) {
            return null
        }
        val idx = xml.indexOf("<ExternalCommands>", ignoreCase = true)
        if (idx < 0) {
            return null
        }
        return xml.substring(0, idx) + loadAssetText(context, ASSET_GESTURE_GROUP) + "\n" + xml.substring(idx)
    }

    private fun patchVarConfigForGesture(xml: String): String {
        val stripped = removeGestureFromVarConfig(xml)
        val base = unwrapVarConfigOuterCommentIfNeeded(stripped)
        val closing = "</WidgetConfig>"
        val idx = base.lastIndexOf(closing, ignoreCase = true)
        if (idx < 0) {
            return base
        }
        val head = base.substring(0, idx).trimEnd()
        return head + "\n" + gestureInjectedMarkerBlock() + "\n" + closing
    }

    private fun newMinimalVarConfigXml(): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<WidgetConfig version=\"1\" des=\"MiRoot\">\n" +
            gestureInjectedMarkerBlock() +
            "\n</WidgetConfig>\n"

    private fun unwrapVarConfigOuterCommentIfNeeded(xml: String): String {
        val t = xml.trim()
        if (!t.startsWith("<!--")) {
            return xml
        }
        if (!t.endsWith("-->")) {
            return xml
        }
        val inner = t.substring(4, t.length - 3).trim()
        if (!inner.contains("<WidgetConfig", ignoreCase = true)) {
            return xml
        }
        return inner
    }

    private fun gestureInjectedMarkerBlock(): String =
        """  <OnOff name="miroot_gesture_injected" displayTitle="MiRoot手势已注入" default="1">
    <Language displayTitle="MiRoot gesture injected" locale="bo_CN"/>
    <Language displayTitle="MiRoot gesture injected" locale="en_US"/>
    <Language displayTitle="MiRoot gesture injected" locale="ug_CN"/>
    <Language displayTitle="MiRoot手势已注入" locale="zh_CN"/>
    <Language displayTitle="MiRoot手勢已注入" locale="zh_HK"/>
    <Language displayTitle="MiRoot手勢已注入" locale="zh_TW"/>
  </OnOff>"""

    private fun loadAssetText(context: Context, path: String): String {
        return context.assets.open(path).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }.trimEnd() + "\n"
    }
}
