package com.wmqc.miroot.theme

import com.wmqc.miroot.lyrics.ITaskService
import com.wmqc.miroot.lyrics.LogHelper
import com.wmqc.miroot.rear.RearBottomGestureIntents
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object AiRearscreenLyricsGestureInjector {
    private const val TAG = "AiRearscreenInject"

    private const val ZIP_ENTRY_MANIFEST = "manifest.xml"
    private const val ZIP_ENTRY_VAR_CONFIG = "var_config.xml"

    /**
     * 在 rearscreen zip 内增量修改 [ZIP_ENTRY_MANIFEST] 与 [ZIP_ENTRY_VAR_CONFIG]。
     *
     * **Manifest（含 AI 目录「手势启动」路径）**
     * 1. 先移除本工具曾注入的旧版底栏 / 旧直发广播 / 旧 [miroot_gesture_inject]。
     * 2. 再写入**三槽位手势层**（`ACTION_REAR_BOTTOM_GESTURE` + 槽位 1/2/3）；若无法挂载则整次失败，不覆盖 zip（不会「只删不写」）。
     * 3. 挂载策略：优先在根 [Widget] 最后一个 [</Widget>] 前插入；若无合法锚点则在 [ExternalCommands] 之前插入。
     *
     * **var_config**
     * - 移除旧版 [musicBroadcastOn] 等三开关；写入三个 [OnOff]：`miroot_bottom_swipe_up_on` / `miroot_bottom_swipe_left_on` /
     *   `miroot_bottom_swipe_right_on`（展示标题：底部上滑、底部左滑、底部右滑），与旧版一样可在主题/背屏壁纸参数里开关。
     *   三个开关写入时 **default 均为开启**（用户可在主题里自行关闭）。手势触发后仅发三槽位通用广播；**与「功能 → 背屏手势配置」无关**，槽位收到广播后执行何种动作由 `RearGesturePrefs` 在运行时决定。
     * - 若无 [var_config.xml]：新建仅含手势变量的最小 [WidgetConfig]。
     */
    fun injectGestureIntoLocalZipFile(inputZip: File, outputZip: File): Boolean {
        if (!inputZip.isFile || inputZip.length() == 0L) return false
        outputZip.parentFile?.takeIf { !it.exists() }?.mkdirs()
        return rebuildZipWithGesturePatches(inputZip, outputZip)
    }

    fun removeGestureFromLocalZipFile(inputZip: File, outputZip: File): Boolean {
        if (!inputZip.isFile || inputZip.length() == 0L) return false
        outputZip.parentFile?.takeIf { !it.exists() }?.mkdirs()
        return rebuildZipWithGestureRemovePatches(inputZip, outputZip)
    }

    /**
     * AI 壁纸目录下 `rearscreen` zip 的**注入手势**（增量写入 manifest / var_config）。
     *
     * **非仅删除**：先去掉本工具曾写入的旧底栏 / 旧直发音乐·桌面·车控广播等，再写入**三槽位手势层**
     *（`com.wmqc.miroot.rear.ACTION_REAR_BOTTOM_GESTURE` 及槽位 1/2/3）。注入内容与「背屏手势配置」页无关；主题内自带三方向开关。
     * 收到广播后打开桌面、歌词、车控或应用等，由 `RearGesturePrefs`（「功能 → 背屏手势配置」）在运行时决定。
     *
     * 若 manifest 无法挂载新手势层（无合法 `</Widget>` 且无 `ExternalCommands` 锚点），整次注入失败并返回 `false`，**不会**用「只删不增」的结果覆盖原文件。
     */
    fun inject(taskService: ITaskService, aiDirectoryName: String): Boolean {
        if (aiDirectoryName.isBlank()) return false
        val srcRearscreen = AiWallpaperThemeHelper.AI_MAML_BASE + aiDirectoryName + "/rearscreen"
        val workDir = AiWallpaperThemeHelper.THEME_TEMP_DIR
        val ts = System.currentTimeMillis()
        val tmpIn = "${workDir}rearscreen_in_$ts.zip"
        val tmpOut = "${workDir}rearscreen_out_$ts.zip"

        try {
            taskService.executeShellCommand("mkdir -p \"$workDir\"")
            val ok =
                taskService.executeShellCommandWithResult(
                    "test -f \"$srcRearscreen\" && cp \"$srcRearscreen\" \"$tmpIn\" && echo ok || echo no",
                )?.trim() == "ok"
            if (!ok) return false

            val inputZip = File(tmpIn)
            val outputZip = File(tmpOut)
            if (!inputZip.isFile || inputZip.length() == 0L) return false

            if (!rebuildZipWithGesturePatches(inputZip, outputZip)) return false
            if (!outputZip.isFile || outputZip.length() == 0L) return false

            val replaced =
                AiWallpaperThemeHelper.replaceRootOwnedFile(
                    taskService,
                    srcRearscreen,
                    outputZip.absolutePath,
                    false,
                )
            if (replaced) {
                LogHelper.d(TAG, "AI rearscreen: gesture inject written (dir=$aiDirectoryName)")
            }
            return replaced
        } catch (e: Exception) {
            LogHelper.w(TAG, "inject failed", e)
            return false
        } finally {
            runCatching {
                taskService.executeShellCommand("rm -f \"$tmpIn\" \"$tmpOut\"")
            }
        }
    }

    fun remove(taskService: ITaskService, aiDirectoryName: String): Boolean {
        if (aiDirectoryName.isBlank()) return false
        val srcRearscreen = AiWallpaperThemeHelper.AI_MAML_BASE + aiDirectoryName + "/rearscreen"
        val workDir = AiWallpaperThemeHelper.THEME_TEMP_DIR
        val ts = System.currentTimeMillis()
        val tmpIn = "${workDir}rearscreen_in_$ts.zip"
        val tmpOut = "${workDir}rearscreen_out_$ts.zip"

        try {
            taskService.executeShellCommand("mkdir -p \"$workDir\"")
            val ok =
                taskService.executeShellCommandWithResult(
                    "test -f \"$srcRearscreen\" && cp \"$srcRearscreen\" \"$tmpIn\" && echo ok || echo no",
                )?.trim() == "ok"
            if (!ok) return false

            val inputZip = File(tmpIn)
            val outputZip = File(tmpOut)
            if (!inputZip.isFile || inputZip.length() == 0L) return false

            if (!rebuildZipWithGestureRemovePatches(inputZip, outputZip)) return false
            if (!outputZip.isFile || outputZip.length() == 0L) return false

            return AiWallpaperThemeHelper.replaceRootOwnedFile(
                taskService,
                srcRearscreen,
                outputZip.absolutePath,
                false,
            )
        } catch (e: Exception) {
            LogHelper.w(TAG, "remove failed", e)
            return false
        } finally {
            runCatching {
                taskService.executeShellCommand("rm -f \"$tmpIn\" \"$tmpOut\"")
            }
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

    private fun rebuildZipWithGesturePatches(inputZip: File, outputZip: File): Boolean {
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
                return false
            }
            if (vIn == null) {
                LogHelper.d(TAG, "zip has no root var_config.xml; injecting new minimal WidgetConfig (gesture vars)")
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
            val patchedManifest = patchManifestForGesture(manifestText)
            if (patchedManifest == null) {
                LogHelper.w(TAG, "manifest gesture patch failed (no universal </Widget> anchor and no ExternalCommands)")
                return false
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
        } finally {
            runCatching { zipFile.close() }
        }
        return true
    }

    private fun rebuildZipWithGestureRemovePatches(inputZip: File, outputZip: File): Boolean {
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
        } finally {
            runCatching { zipFile.close() }
        }
        return true
    }

    private fun patchManifestForGesture(xml: String): String? {
        val cleaned = removeGestureFromManifest(xml)
        tryInjectUniversalGesture(cleaned)?.let {
            LogHelper.d(TAG, "manifest: gesture inject applied (before </Widget>)")
            return it
        }
        return patchManifestLegacyAiVideo(cleaned)
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

    /**
     * 根 [Widget] 闭合后仅允许空白或尾部注释块；避免把内层 `</Widget>` 误当根闭合，同时兼容部分包尾带注释的写法。
     */
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

    private fun tryInjectUniversalGesture(xml: String): String? {
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
        return xml.substring(0, idx) + buildGestureGroupXml() + "\n" + xml.substring(idx)
    }

    /** 无合法 [</Widget>] 时：在 [ExternalCommands] 前插入新版手势层（不再依赖旧 vsy / openMusicProjection 片段）。 */
    private fun patchManifestLegacyAiVideo(xml: String): String? {
        if (!xml.contains("<ExternalCommands>", ignoreCase = true)) {
            return null
        }
        val idx = xml.indexOf("<ExternalCommands>", ignoreCase = true)
        if (idx < 0) {
            return null
        }
        return xml.substring(0, idx) + buildGestureGroupXml() + "\n" + xml.substring(idx)
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
        return head + "\n" + gestureVarConfigAppend() + "\n" + closing
    }

    private fun newMinimalVarConfigXml(): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<WidgetConfig version=\"1\" des=\"MiRoot\">\n" +
            gestureVarConfigAppend() +
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

    private fun gestureVarConfigAppend(): String =
        listOf(
            swipeOnOffBlock("miroot_bottom_swipe_up_on", "底部上滑", "Bottom swipe up", defaultOn = true),
            swipeOnOffBlock("miroot_bottom_swipe_left_on", "底部左滑", "Bottom swipe left", defaultOn = true),
            swipeOnOffBlock("miroot_bottom_swipe_right_on", "底部右滑", "Bottom swipe right", defaultOn = true),
        ).joinToString("\n")

    private fun swipeOnOffBlock(
        xmlName: String,
        zhTitle: String,
        enTitle: String,
        defaultOn: Boolean,
    ): String {
        val d = if (defaultOn) "1" else "0"
        return (
            """  <OnOff name="$xmlName" displayTitle="$zhTitle" default="$d">
    <Language displayTitle="$enTitle" locale="bo_CN"/>
    <Language displayTitle="$enTitle" locale="en_US"/>
    <Language displayTitle="$enTitle" locale="ug_CN"/>
    <Language displayTitle="$zhTitle" locale="zh_CN"/>
    <Language displayTitle="$zhTitle" locale="zh_HK"/>
    <Language displayTitle="$zhTitle" locale="zh_TW"/>
  </OnOff>"""
        )
    }

    private fun mamlBottomY(): String = "(#view_height - (80 * (#view_height / 572)))"

    private fun mamlH(): String = "(#view_height / 572)"

    private fun condSlotActive(slot: Int): String {
        val v =
            when (slot) {
                1 -> "miroot_bottom_swipe_up_on"
                2 -> "miroot_bottom_swipe_left_on"
                3 -> "miroot_bottom_swipe_right_on"
                else -> "miroot_bottom_swipe_up_on"
            }
        return "(#$v == 1)"
    }

    private fun geomUp(): String =
        "(#touch_begin_y }= (${mamlBottomY()})) ** ((#touch_begin_y - #touch_y) }= (50 * ${mamlH()})) ** ((#touch_begin_y - #touch_y) }= (abs(#touch_x - #touch_begin_x) + (24 * ${mamlH()})))"

    private fun geomLeft(): String =
        "(#touch_begin_y }= (${mamlBottomY()})) ** ((#touch_begin_x - #touch_x) }= (0.07 * #view_width)) ** ((#touch_begin_x - #touch_x) }= (abs(#touch_begin_y - #touch_y) + (24 * ${mamlH()})))"

    private fun geomRight(): String =
        "(#touch_begin_y }= (${mamlBottomY()})) ** ((#touch_x - #touch_begin_x) }= (0.07 * #view_width)) ** ((#touch_x - #touch_begin_x) }= (abs(#touch_begin_y - #touch_y) + (24 * ${mamlH()})))"

    private fun emitIf(slot: Int, geom: String): String =
        """                    <IfCommand ifCondition="(${condSlotActive(slot)} ** $geom)">
                        <Consequent>
                            <FunctionCommand target="miroot_emit_s${slot}"/>
                        </Consequent>
                    </IfCommand>"""

    private fun buildEmitFunctionsXml(): String =
        """
        <Function name="miroot_emit_s1">
            <IntentCommand action="${RearBottomGestureIntents.ACTION_REAR_BOTTOM_GESTURE}" broadcast="true" package="com.wmqc.miroot">
                <Extra name="${RearBottomGestureIntents.EXTRA_GESTURE_SLOT}" type="number" expression="1"/>
            </IntentCommand>
        </Function>
        <Function name="miroot_emit_s2">
            <IntentCommand action="${RearBottomGestureIntents.ACTION_REAR_BOTTOM_GESTURE}" broadcast="true" package="com.wmqc.miroot">
                <Extra name="${RearBottomGestureIntents.EXTRA_GESTURE_SLOT}" type="number" expression="2"/>
            </IntentCommand>
        </Function>
        <Function name="miroot_emit_s3">
            <IntentCommand action="${RearBottomGestureIntents.ACTION_REAR_BOTTOM_GESTURE}" broadcast="true" package="com.wmqc.miroot">
                <Extra name="${RearBottomGestureIntents.EXTRA_GESTURE_SLOT}" type="number" expression="3"/>
            </IntentCommand>
        </Function>""".trimIndent()

    private fun buildGestureGroupXml(): String {
        val triggers =
            listOf(
                emitIf(1, geomUp()),
                emitIf(2, geomLeft()),
                emitIf(3, geomRight()),
            ).joinToString("\n")
        return """
    <!-- MiRoot: 注入手势 — 槽位广播 1=上滑 2=左滑 3=右滑；收到广播后的动作由「背屏手势配置」决定，与注入内容无关 -->
    <Group name="miroot_gesture_inject" visibility="(((#miroot_bottom_swipe_up_on == 1) || (#miroot_bottom_swipe_left_on == 1)) || (#miroot_bottom_swipe_right_on == 1))">
${buildEmitFunctionsXml()}
        <Button name="miroot_bottom_gesture" x="0" y="${mamlBottomY()}" w="#view_width" h="(80 * ${mamlH()})" interceptTouch="true">
            <Normal>
            </Normal>
            <Pressed>
            </Pressed>
            <Triggers>
                <Trigger action="up,cancel">
$triggers
                </Trigger>
            </Triggers>
        </Button>
    </Group>""".trimIndent()
    }
}
