package com.wmqc.miroot.theme

import android.content.Context
import com.wmqc.miroot.lyrics.ITaskService
import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** 系统背屏已应用主题列表（见 theme_magic …/subscreencenter/config/widget.json），结构与主题商店写入一致。 */
data class AppliedRearTheme(
    val id: Long,
    val type: Int,
    val changed: Boolean,
    val isCurrentMarked: Boolean,
    val mrcPath: String,
    val configPath: String,
    val resName: String,
    val title: String,
    val snapshotPath: String,
    val metaPath: String,
    val supportAon: Boolean,
    val nfc: Boolean,
) {
    val isPrecust: Boolean
        get() =
            metaPath.contains("/precust_theme/") ||
                metaPath.contains("/product/etc/precust")

    fun mrcFileName(): String = mrcPath.substringAfterLast('/').ifEmpty { mrcPath }

    fun configFolderName(): String = configPath.trimEnd('/').substringAfterLast('/').ifEmpty { "—" }

    companion object {
        fun parseArray(json: String): List<AppliedRearTheme> {
            if (json.isBlank()) return emptyList()
            return try {
                val raw = json.trim()
                if (raw.startsWith("[")) {
                    parseFromArray(JSONArray(raw))
                } else {
                    parseFromObject(JSONObject(raw))
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        private fun parseFromArray(arr: JSONArray): List<AppliedRearTheme> {
            return buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(fromJson(o))
                }
            }
        }

        private fun parseFromObject(root: JSONObject): List<AppliedRearTheme> {
            val arr =
                root.optJSONArray("list")
                    ?: root.optJSONArray("items")
                    ?: root.optJSONArray("data")
                    ?: root.optJSONArray("widgets")
                    ?: root.optJSONArray("wallpapers")
                    ?: root.optJSONArray("themes")
                    ?: JSONArray()
            val list =
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        add(fromJson(o))
                    }
                }
            if (list.isEmpty()) return list

            val currentId =
                optLongNullable(root, "currentWallpaperId")
                    ?: optLongNullable(root, "currentId")
                    ?: optLongNullable(root, "selectedId")
                    ?: optLongNullable(root, "activeId")
            val currentPath =
                optStringNullable(root, "currentPath")
                    ?: optStringNullable(root, "selectedPath")
                    ?: optStringNullable(root, "activePath")
            val currentIndex =
                when {
                    root.has("currentIndex") -> root.optInt("currentIndex", -1)
                    root.has("selectedIndex") -> root.optInt("selectedIndex", -1)
                    root.has("activeIndex") -> root.optInt("activeIndex", -1)
                    else -> -1
                }
            if (currentId == null && currentPath == null && currentIndex !in list.indices) {
                return list
            }
            return list.mapIndexed { index, item ->
                val byIndex = index == currentIndex
                val byId = currentId != null && item.id == currentId
                val byPath = !currentPath.isNullOrBlank() && item.mrcPath == currentPath
                if (byIndex || byId || byPath) item.copy(isCurrentMarked = true) else item
            }
        }

        private fun fromJson(o: JSONObject): AppliedRearTheme {
            val extra = o.optJSONObject("extra") ?: JSONObject()
            val idVal =
                try {
                    o.getLong("id")
                } catch (_: Exception) {
                    o.optInt("id").toLong()
                }
            return AppliedRearTheme(
                id = idVal,
                type = o.optInt("type", 0),
                changed = o.optBoolean("changed", false),
                isCurrentMarked = detectCurrentMark(o, extra),
                mrcPath = o.optString("path", ""),
                configPath = o.optString("configPath", ""),
                resName = extra.optString("resName", "").ifEmpty { "—" },
                title = extra.optString("title", "").ifEmpty { "—" },
                snapshotPath =
                    firstNonBlank(
                        extra.optString("snapshotPath", ""),
                        extra.optString("snapshotPreviewPath", ""),
                        o.optString("snapshotPath", ""),
                        o.optString("snapshotPreviewPath", ""),
                    ),
                metaPath = extra.optString("metaPath", ""),
                supportAon = extra.optBoolean("supportAon", false),
                nfc = o.optBoolean("nfc", false),
            )
        }

        private fun detectCurrentMark(o: JSONObject, extra: JSONObject): Boolean {
            val direct =
                o.optBoolean("current", false) ||
                    o.optBoolean("isCurrent", false) ||
                    o.optBoolean("selected", false) ||
                    o.optBoolean("isSelected", false) ||
                    o.optBoolean("active", false) ||
                    o.optBoolean("isActive", false)
            val inExtra =
                extra.optBoolean("current", false) ||
                    extra.optBoolean("isCurrent", false) ||
                    extra.optBoolean("selected", false) ||
                    extra.optBoolean("isSelected", false) ||
                    extra.optBoolean("active", false) ||
                    extra.optBoolean("isActive", false)
            return direct || inExtra
        }

        private fun optLongNullable(o: JSONObject, key: String): Long? {
            if (!o.has(key)) return null
            val v = o.opt(key) ?: return null
            return when (v) {
                is Number -> v.toLong()
                is String -> v.toLongOrNull()
                else -> null
            }
        }

        private fun optStringNullable(o: JSONObject, key: String): String? {
            if (!o.has(key)) return null
            val v = o.optString(key, "").trim()
            return v.takeIf { it.isNotEmpty() }
        }

        private fun firstNonBlank(vararg values: String): String {
            for (v in values) {
                val t = v.trim()
                if (t.isNotEmpty()) return t
            }
            return ""
        }
    }
}

object AppliedRearThemeHelper {
    /** 官方背屏中心配置目录下的 widget.json（用户提供路径）。 */
    const val WIDGET_JSON_PATH =
        "/data/system/theme_magic/users/0/subscreencenter/config/widget.json"

    /** 背屏主题在 theme_magic 下的实例目录前缀（configPath 指向其下 editConfig）。 */
    private const val REAR_SCREEN_MAGIC_BASE = "/data/system/theme_magic/users/0/rearScreen/"

    /** 已应用 .mrc 等资源路径前缀，删除时仅允许该前缀下文件，避免误删。 */
    private const val THEME_DATA_PREFIX = "/data/system/theme/"

    private const val PKG_OFFICIAL_SUBSCREEN = "com.xiaomi.subscreencenter"
    private const val CMP_SUBSCREEN_LAUNCHER = "$PKG_OFFICIAL_SUBSCREEN/.SubScreenLauncher"

    /** widget.json 中 extra.title：个性签名类为 signature（亦偶见中文）。 */
    fun isSignatureCategory(title: String): Boolean {
        val raw = title.trim()
        if (raw.isEmpty() || raw == "—") return false
        if (raw.equals("个性签名", ignoreCase = false)) return true
        return raw.lowercase(Locale.ROOT) == "signature"
    }

    fun loadAppliedThemes(ts: ITaskService?): List<AppliedRearTheme> {
        if (ts == null) return emptyList()
        val p = WIDGET_JSON_PATH
        val check = shellResultRootFirst(ts, "test -f \"$p\" && echo ok || echo no").trim()
        if (check != "ok") return emptyList()
        val content = shellResultRootFirst(ts, "cat \"$p\" 2>/dev/null")
        if (content.isBlank()) return emptyList()
        return AppliedRearTheme.parseArray(content)
    }

    /**
     * 解析列表缩略图可读路径：优先 widget.json 的 snapshot 字段，再尝试 theme_magic/rearScreen 实例目录。
     */
    fun resolveThemeThumbnailPreviewPath(ts: ITaskService, theme: AppliedRearTheme): String? {
        val snap = theme.snapshotPath.trim()
        if (snap.isNotEmpty()) {
            resolveSnapshotPreviewPath(ts, snap)?.let { return it }
        }
        val instance = rearMagicInstanceDirForTheme(theme) ?: return null
        for (candidate in
            listOf(
                "$instance/thumb.png",
                "$instance/preview.png",
                "$instance/snapshot.png",
                "$instance/editConfig/preview.png",
            )) {
            resolveSnapshotPreviewPath(ts, candidate)?.let { return it }
        }
        return resolveSnapshotPreviewPath(ts, instance)
    }

    /** 用于加载列表缩略图：普通文件、无扩展名占位文件，或目录内首张图片。 */
    fun resolveSnapshotPreviewPath(ts: ITaskService, snapshotPath: String): String? {
        if (snapshotPath.isBlank()) return null
        val p = snapshotPath.trim()
        if (p.contains("..")) return null
        val kind =
            shellResultRootFirst(
                    ts,
                    "if [ -f \"$p\" ]; then echo file; " +
                        "elif [ -d \"$p\" ]; then echo dir; " +
                        "elif [ -e \"$p\" ]; then echo other; " +
                        "else echo miss; fi",
                )
                .trim()
        return when (kind) {
            "file", "other" -> p
            "dir" -> {
                val first =
                    shellResultRootFirst(
                            ts,
                            "find \"$p\" -type f " +
                                "\\( -iname '*.png' -o -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.webp' \\) " +
                                "2>/dev/null | head -n 1",
                        )
                        .trim()
                        .lineSequence()
                        .firstOrNull()
                first?.takeIf { it.isNotEmpty() }
            }
            else -> null
        }
    }

    fun mrcFileExists(ts: ITaskService, mrcPath: String): Boolean {
        if (mrcPath.isBlank()) return false
        return shellResultRootFirst(ts, "test -f \"$mrcPath\" && echo ok || echo no").trim() == "ok"
    }

    /** 与 [AiWallpaperThemeHelper] 一致：优先 root 读 /data/system 下快照与配置。 */
    private fun shellResultRootFirst(ts: ITaskService, cmd: String): String {
        return try {
            val root = ts.executeShellCommandWithResultAsRoot(cmd)?.trim().orEmpty()
            if (root.isNotEmpty()) {
                root
            } else {
                ts.executeShellCommandWithResult(cmd)?.trim().orEmpty()
            }
        } catch (_: Exception) {
            try {
                ts.executeShellCommandWithResult(cmd)?.trim().orEmpty()
            } catch (_: Exception) {
                ""
            }
        }
    }

    /** 从 configPath（…/rearScreen/某实例/editConfig）得到 theme_magic 下该实例目录，且必须位于官方前缀下。 */
    fun rearMagicInstanceDirForTheme(theme: AppliedRearTheme): String? {
        val cfg = theme.configPath.trim().trimEnd('/')
        if (cfg.isEmpty()) return null
        val parent = cfg.removeSuffix("/editConfig").trimEnd('/')
        if (parent.isEmpty() || !parent.startsWith(REAR_SCREEN_MAGIC_BASE)) return null
        if (parent.length <= REAR_SCREEN_MAGIC_BASE.length) return null
        if (parent.contains("..")) return null
        return parent
    }

    private fun jsonEntryMatchesTheme(o: JSONObject, theme: AppliedRearTheme): Boolean {
        val idVal =
            try {
                o.getLong("id")
            } catch (_: Exception) {
                o.optInt("id").toLong()
            }
        if (idVal != theme.id) return false
        val path = o.optString("path", "")
        return if (theme.mrcPath.isBlank()) path.isBlank() else path == theme.mrcPath
    }

    /**
     * 与 {@link com.wmqc.miroot.lyrics.TaskService#enableSubScreenLauncher} 同类操作：确保包与 Launcher 组件可用后
     * force-stop 再拉起背屏桌面，使 widget.json / 主题列表变更尽快生效。
     */
    fun restartOfficialSubscreenCenter(ts: ITaskService) {
        val cmd =
            "pm enable $PKG_OFFICIAL_SUBSCREEN 2>/dev/null; " +
                "pm enable $CMP_SUBSCREEN_LAUNCHER 2>/dev/null; " +
                "am force-stop $PKG_OFFICIAL_SUBSCREEN; " +
                "sleep 0.25; " +
                "am start --display 1 -n $CMP_SUBSCREEN_LAUNCHER || true"
        runCatching { ts.executeShellCommand(cmd) }
    }

    private fun copyTempToWidgetJsonPreservingMeta(ts: ITaskService, tempFile: String, targetPath: String): Boolean {
        val stat =
            ts.executeShellCommandWithResult("stat -c '%u:%g %a' \"$targetPath\" 2>/dev/null")?.trim().orEmpty()
        val parts = stat.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (!ts.executeShellCommand("cp \"$tempFile\" \"$targetPath\"")) {
            return false
        }
        if (parts.size >= 2) {
            val owner = parts[0]
            val mode = parts[1]
            ts.executeShellCommand("chown $owner \"$targetPath\"")
            ts.executeShellCommand("chmod $mode \"$targetPath\"")
        }
        return true
    }

    /**
     * 删除一条已应用背屏主题：从 widget.json 移除对应对象、删除 theme_magic …/rearScreen/ 下该实例目录、删除
     * /data/system/theme/ 下的 .mrc（若 path 指向该前缀）；成功后 root 重启官方背屏中心（com.xiaomi.subscreencenter）。
     */
    fun deleteAppliedTheme(ts: ITaskService, theme: AppliedRearTheme): Boolean {
        val widgetPath = WIDGET_JSON_PATH
        val check = ts.executeShellCommandWithResult("test -f \"$widgetPath\" && echo ok || echo no")?.trim()
        if (check != "ok") return false
        val content = ts.executeShellCommandWithResult("cat \"$widgetPath\" 2>/dev/null") ?: return false
        if (content.isBlank()) return false
        val arr =
            try {
                JSONArray(content.trim())
            } catch (_: Exception) {
                return false
            }
        val newArr = JSONArray()
        var removed = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (jsonEntryMatchesTheme(o, theme)) {
                removed = true
                continue
            }
            newArr.put(o)
        }
        if (!removed) return false

        val outText = newArr.toString()
        val tmpDir = AiWallpaperThemeHelper.THEME_TEMP_DIR
        File(tmpDir).mkdirs()
        val tmpFile = File(tmpDir, "widget_${System.currentTimeMillis()}.json")
        try {
            tmpFile.writeText(outText, Charsets.UTF_8)
        } catch (_: Exception) {
            return false
        }
        if (!tmpFile.isFile || tmpFile.length() == 0L) return false
        val tmpPath = tmpFile.absolutePath
        if (!copyTempToWidgetJsonPreservingMeta(ts, tmpPath, widgetPath)) {
            tmpFile.delete()
            return false
        }
        tmpFile.delete()

        rearMagicInstanceDirForTheme(theme)?.let { dir ->
            ts.executeShellCommand("rm -rf \"$dir\"")
        }

        val mrc = theme.mrcPath.trim()
        if (mrc.startsWith(THEME_DATA_PREFIX) && !mrc.contains("..")) {
            ts.executeShellCommand("rm -f \"$mrc\" \"$mrc.backup\"")
        }

        restartOfficialSubscreenCenter(ts)
        return true
    }

    /**
     * 将「启动手势层」注入到已应用主题的 .mrc（zip）中。
     *
     * - 仅在具备 root/shizuku 权限且 mrcPath 可读时工作。
     * - 通过复制到 /sdcard 临时目录处理，再覆盖写回（保留 .backup 行为由 replaceRootOwnedFile 实现）。
     */
    fun injectLaunchGesture(ts: ITaskService, theme: AppliedRearTheme, @Suppress("UNUSED_PARAMETER") context: Context): Boolean {
        val mrc = theme.mrcPath.trim()
        if (mrc.isEmpty()) return false
        val workDir = AiWallpaperThemeHelper.THEME_TEMP_DIR
        val now = System.currentTimeMillis()
        val tmpIn = "${workDir}applied_mrc_in_$now.zip"
        val tmpOut = "${workDir}applied_mrc_out_$now.zip"
        return try {
            ts.executeShellCommand("mkdir -p \"$workDir\"")
            val ok =
                ts.executeShellCommandWithResult(
                    "test -f \"$mrc\" && cp \"$mrc\" \"$tmpIn\" && echo ok || echo no",
                )?.trim() == "ok"
            if (!ok) return false
            val inputZip = File(tmpIn)
            val outputZip = File(tmpOut)
            if (!AiRearscreenLyricsGestureInjector.injectGestureIntoLocalZipFile(inputZip, outputZip)) return false
            if (!outputZip.isFile || outputZip.length() == 0L) return false
            AiWallpaperThemeHelper.replaceRootOwnedFile(ts, mrc, outputZip.absolutePath, true)
        } catch (_: Exception) {
            false
        } finally {
            runCatching { ts.executeShellCommand("rm -f \"$tmpIn\" \"$tmpOut\"") }
        }
    }

    /** 删除已应用主题 .mrc 中 MiRoot 注入的启动手势层。 */
    fun removeInjectedLaunchGesture(ts: ITaskService, theme: AppliedRearTheme): Boolean {
        val mrc = theme.mrcPath.trim()
        if (mrc.isEmpty()) return false
        val workDir = AiWallpaperThemeHelper.THEME_TEMP_DIR
        val now = System.currentTimeMillis()
        val tmpIn = "${workDir}applied_mrc_in_$now.zip"
        val tmpOut = "${workDir}applied_mrc_out_$now.zip"
        return try {
            ts.executeShellCommand("mkdir -p \"$workDir\"")
            val ok =
                ts.executeShellCommandWithResult(
                    "test -f \"$mrc\" && cp \"$mrc\" \"$tmpIn\" && echo ok || echo no",
                )?.trim() == "ok"
            if (!ok) return false
            val inputZip = File(tmpIn)
            val outputZip = File(tmpOut)
            if (!AiRearscreenLyricsGestureInjector.removeGestureFromLocalZipFile(inputZip, outputZip)) return false
            if (!outputZip.isFile || outputZip.length() == 0L) return false
            AiWallpaperThemeHelper.replaceRootOwnedFile(ts, mrc, outputZip.absolutePath, true)
        } catch (_: Exception) {
            false
        } finally {
            runCatching { ts.executeShellCommand("rm -f \"$tmpIn\" \"$tmpOut\"") }
        }
    }

    private fun resolveOneConfigPath(ts: ITaskService, theme: AppliedRearTheme): String? {
        val dir = theme.configPath.trim().trimEnd('/')
        if (dir.isEmpty()) return null
        // MIUI/主题侧常见保存名，未知时允许用户在 UI 中手填路径。
        val candidates = listOf("one_config.json", "oneConfig.json", "config.json")
        for (name in candidates) {
            val p = "$dir/$name"
            val ok = ts.executeShellCommandWithResult("test -f \"$p\" && echo ok || echo no")?.trim()
            if (ok == "ok") return p
        }
        return null
    }

    fun readOneConfigJson(ts: ITaskService, theme: AppliedRearTheme): String? {
        val p = resolveOneConfigPath(ts, theme) ?: return null
        return ts.executeShellCommandWithResult("cat \"$p\" 2>/dev/null")
    }

    fun writeOneConfigJson(ts: ITaskService, theme: AppliedRearTheme, json: String): Boolean {
        val dirBase = theme.configPath.trim().trimEnd('/')
        if (dirBase.isEmpty()) return false
        val p = resolveOneConfigPath(ts, theme) ?: "$dirBase/one_config.json"
        val dir = File(p).parent ?: return false
        val tmpDir = AiWallpaperThemeHelper.THEME_TEMP_DIR
        File(tmpDir).mkdirs()
        val tmpFile = File(tmpDir, "one_config_${System.currentTimeMillis()}.json")
        return try {
            tmpFile.writeText(json, Charsets.UTF_8)
            if (!tmpFile.isFile || tmpFile.length() == 0L) return false
            ts.executeShellCommand("mkdir -p \"$dir\"")
            // Copy then restore owner/mode if possible.
            val stat =
                ts.executeShellCommandWithResult("stat -c '%u:%g %a' \"$p\" 2>/dev/null")?.trim().orEmpty()
            val ok = ts.executeShellCommand("cp \"${tmpFile.absolutePath}\" \"$p\"")
            if (!ok) return false
            val parts = stat.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.size >= 2) {
                ts.executeShellCommand("chown ${parts[0]} \"$p\"")
                ts.executeShellCommand("chmod ${parts[1]} \"$p\"")
            }
            restartOfficialSubscreenCenter(ts)
            true
        } catch (_: Exception) {
            false
        } finally {
            runCatching { tmpFile.delete() }
        }
    }
}
