package com.wmqc.miroot.rear.desktop

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.wmqc.miroot.lyrics.LogHelper

object RearDesktopRepository {

    private const val TAG = "RearDesktopRepo"
    private const val LAUNCHER_CACHE_TTL_MS = 15_000L
    private const val RESOLVE_CACHE_TTL_MS = 8_000L

    @Volatile
    private var launcherLabelsCache: LauncherLabelsCache? = null
    @Volatile
    private var resolvedAppsCache: ResolvedAppsCache? = null

    private data class LauncherLabelsCache(
        val timestampMs: Long,
        val labelsByPkg: Map<String, Pair<String, android.content.ComponentName>>,
    )

    private data class ResolvedAppsCache(
        val token: String,
        val timestampMs: Long,
        val apps: List<RearDesktopAppEntry>,
    )

    fun resolveApps(context: Context): List<RearDesktopAppEntry> {
        return try {
            resolveAppsInner(context)
        } catch (e: Exception) {
            LogHelper.w(TAG, "resolveApps failed: ${e.message}")
            emptyList()
        }
    }

    /** 模式/偏好切换后可主动清缓存，避免展示过期顺序。 */
    fun invalidateCache() {
        launcherLabelsCache = null
        resolvedAppsCache = null
    }

    private fun resolveAppsInner(context: Context): List<RearDesktopAppEntry> {
        val appCtx = context.applicationContext
        val selfPkg = appCtx.packageName
        val mode = RearDesktopPrefs.listMode(appCtx)
        val pm = appCtx.packageManager
        val modeToken = buildModeToken(appCtx, mode)
        val token = "${selfPkg}|${mode.name}|$modeToken"
        val now = System.currentTimeMillis()
        resolvedAppsCache?.let { cache ->
            if (cache.token == token && now - cache.timestampMs <= RESOLVE_CACHE_TTL_MS) {
                return cache.apps
            }
        }
        val labelsByPkg = queryLauncherLabelsCached(pm, selfPkg, now)

        val resolved =
            when (mode) {
            RearDesktopListMode.MANUAL -> {
                val order = RearDesktopPrefs.manualOrder(appCtx)
                order.mapNotNull { pkg ->
                    if (pkg == selfPkg) return@mapNotNull null
                    resolveManualDesktopEntry(pm, pkg, labelsByPkg)
                }
            }
            // 背屏 UI 见 RearDesktopHoneycombScreen；此处仅决定条目集合与排序。
            RearDesktopListMode.ALL_BY_FREQUENCY ->
                resolveAllByFrequencyEntries(appCtx, pm, labelsByPkg)
        }
        resolvedAppsCache = ResolvedAppsCache(token = token, timestampMs = now, apps = resolved)
        return resolved
    }

    /**
     * 与「全部应用」模式相同的蜂窝数据源（含黑名单与频次排序），**忽略**当前列表模式是否为自选；
     * 供 [RearDesktopHoneycombTestActivity] 复现完整蜂窝以排查平移手势（多在主屏打开）。
     */
    fun resolveAllHoneycombAppsIgnoringListMode(context: Context): List<RearDesktopAppEntry> {
        return try {
            val appCtx = context.applicationContext
            val selfPkg = appCtx.packageName
            val pm = appCtx.packageManager
            resolveAllByFrequencyEntries(appCtx, pm, queryLauncherLabelsCached(pm, selfPkg, System.currentTimeMillis()))
        } catch (e: Exception) {
            LogHelper.w(TAG, "resolveAllHoneycombAppsIgnoringListMode failed: ${e.message}")
            emptyList()
        }
    }

    private fun resolveAllByFrequencyEntries(
        appCtx: Context,
        pm: PackageManager,
        labelsByPkg: Map<String, Pair<String, android.content.ComponentName>>,
    ): List<RearDesktopAppEntry> {
        val blacklist = RearDesktopPrefs.blacklist(appCtx)
        val usageCounts =
            HashMap<String, Long>(labelsByPkg.size).apply {
                labelsByPkg.keys.forEach { pkg ->
                    this[pkg] = RearDesktopPrefs.usageCount(appCtx, pkg)
                }
            }
        return labelsByPkg.entries
            .filter { it.key !in blacklist }
            .map { (pkg, pair) ->
                val (label, _) = pair
                pkg to label
            }
            .sortedWith(
                compareByDescending<Pair<String, String>> {
                    usageCounts[it.first] ?: 0L
                }.thenBy { it.second.lowercase() },
            )
            .map {
                RearDesktopAppEntry(
                    packageName = it.first,
                    label = it.second,
                    icon = null,
                )
            }
    }

    /**
     * 自选列表以包名为准；若 [queryLauncherLabels] 因系统查询时机等原因未包含该包，则退回 [PackageManager.getApplicationInfo]。
     */
    private fun resolveManualDesktopEntry(
        pm: PackageManager,
        pkg: String,
        labelsByPkg: Map<String, Pair<String, android.content.ComponentName>>,
    ): RearDesktopAppEntry? {
        val labelFromLauncher = labelsByPkg[pkg]?.first
        val label =
            labelFromLauncher
                ?: try {
                    val ai = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationLabel(ai).toString()
                } catch (_: PackageManager.NameNotFoundException) {
                    return null
                }
        val icon = iconDrawable(pm, pkg) ?: return null
        return RearDesktopAppEntry(pkg, label, icon)
    }

    private fun iconDrawable(
        pm: PackageManager,
        pkg: String,
    ): Drawable? =
        try {
            val ai = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationIcon(ai)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    fun loadAppIcon(
        context: Context,
        packageName: String,
    ): Drawable? {
        val pm = context.applicationContext.packageManager
        return iconDrawable(pm, packageName)
    }

    private fun queryLauncherLabels(
        pm: PackageManager,
        selfPackage: String,
    ): Map<String, Pair<String, android.content.ComponentName>> {
        val launcherIntent =
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
        val out = HashMap<String, Pair<String, android.content.ComponentName>>()
        @Suppress("DEPRECATION")
        for (ri in pm.queryIntentActivities(launcherIntent, 0)) {
            val pkg = ri.activityInfo.packageName
            if (pkg == selfPackage) continue
            val cn = android.content.ComponentName(pkg, ri.activityInfo.name)
            try {
                val ai = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(ai).toString()
                if (!out.containsKey(pkg)) {
                    out[pkg] = label to cn
                }
            } catch (_: PackageManager.NameNotFoundException) {
                // skip
            }
        }
        return out
    }

    private fun queryLauncherLabelsCached(
        pm: PackageManager,
        selfPackage: String,
        nowMs: Long,
    ): Map<String, Pair<String, android.content.ComponentName>> {
        launcherLabelsCache?.let { cache ->
            if (nowMs - cache.timestampMs <= LAUNCHER_CACHE_TTL_MS) {
                return cache.labelsByPkg
            }
        }
        val fresh = queryLauncherLabels(pm, selfPackage)
        launcherLabelsCache = LauncherLabelsCache(timestampMs = nowMs, labelsByPkg = fresh)
        return fresh
    }

    private fun buildModeToken(
        appCtx: Context,
        mode: RearDesktopListMode,
    ): String =
        when (mode) {
            RearDesktopListMode.MANUAL -> {
                val order = RearDesktopPrefs.manualOrder(appCtx)
                "M|${order.joinToString(",")}"
            }
            RearDesktopListMode.ALL_BY_FREQUENCY -> {
                val blacklist = RearDesktopPrefs.blacklist(appCtx).toList().sorted()
                val usageHash = RearDesktopPrefs.usageSnapshotHash(appCtx)
                "A|${blacklist.joinToString(",")}|$usageHash"
            }
        }
}
