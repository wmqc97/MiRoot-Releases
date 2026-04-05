package com.wmqc.miroot.theme

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
                val arr = JSONArray(json.trim())
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        add(fromJson(o))
                    }
                }
            } catch (_: Exception) {
                emptyList()
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
                mrcPath = o.optString("path", ""),
                configPath = o.optString("configPath", ""),
                resName = extra.optString("resName", "").ifEmpty { "—" },
                title = extra.optString("title", "").ifEmpty { "—" },
                snapshotPath = extra.optString("snapshotPath", ""),
                metaPath = extra.optString("metaPath", ""),
                supportAon = extra.optBoolean("supportAon", false),
                nfc = o.optBoolean("nfc", false),
            )
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
        val check = ts.executeShellCommandWithResult("test -f \"$p\" && echo ok || echo no")?.trim()
        if (check != "ok") return emptyList()
        val content = ts.executeShellCommandWithResult("cat \"$p\" 2>/dev/null") ?: return emptyList()
        return AppliedRearTheme.parseArray(content)
    }

    /** 用于加载列表缩略图：普通文件、无扩展名占位文件，或目录内首张图片。 */
    fun resolveSnapshotPreviewPath(ts: ITaskService, snapshotPath: String): String? {
        if (snapshotPath.isBlank()) return null
        val p = snapshotPath.trim()
        val kind =
            ts.executeShellCommandWithResult(
                    "if [ -f \"$p\" ]; then echo file; " +
                        "elif [ -d \"$p\" ]; then echo dir; " +
                        "elif [ -e \"$p\" ]; then echo other; " +
                        "else echo miss; fi",
                )
                ?.trim()
        return when (kind) {
            "file", "other" -> p
            "dir" -> {
                val first =
                    ts.executeShellCommandWithResult(
                            "find \"$p\" -maxdepth 1 -type f " +
                                "\\( -name '*.png' -o -name '*.jpg' -o -name '*.jpeg' -o -name '*.webp' \\) " +
                                "2>/dev/null | head -n 1",
                        )
                        ?.trim()
                        ?.lineSequence()
                        ?.firstOrNull()
                first?.takeIf { it.isNotEmpty() }
            }
            else -> null
        }
    }

    fun mrcFileExists(ts: ITaskService, mrcPath: String): Boolean {
        if (mrcPath.isBlank()) return false
        return ts.executeShellCommandWithResult("test -f \"$mrcPath\" && echo ok || echo no")?.trim() == "ok"
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
}
