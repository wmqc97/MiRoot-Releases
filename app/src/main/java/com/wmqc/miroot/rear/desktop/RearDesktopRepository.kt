package com.wmqc.miroot.rear.desktop

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

object RearDesktopRepository {

    fun resolveApps(context: Context): List<RearDesktopAppEntry> {
        val appCtx = context.applicationContext
        val selfPkg = appCtx.packageName
        val mode = RearDesktopPrefs.listMode(appCtx)
        val pm = appCtx.packageManager
        val labelsByPkg = queryLauncherLabels(pm, selfPkg)

        return when (mode) {
            RearDesktopListMode.MANUAL -> {
                val order = RearDesktopPrefs.manualOrder(appCtx)
                order.mapNotNull { pkg ->
                    labelsByPkg[pkg]?.let { (label, _) ->
                        iconDrawable(pm, pkg)?.let { icon ->
                            RearDesktopAppEntry(pkg, label, icon)
                        }
                    }
                }
            }
            RearDesktopListMode.ALL_BY_FREQUENCY -> {
                val blacklist = RearDesktopPrefs.blacklist(appCtx)
                labelsByPkg.entries
                    .filter { it.key !in blacklist }
                    .mapNotNull { (pkg, pair) ->
                        val (label, _) = pair
                        val icon = iconDrawable(pm, pkg) ?: return@mapNotNull null
                        pkg to (label to icon)
                    }
                    .sortedWith(
                        compareByDescending<Pair<String, Pair<String, android.graphics.drawable.Drawable>>> {
                            RearDesktopPrefs.usageCount(appCtx, it.first)
                        }.thenBy { it.second.first.lowercase() },
                    )
                    .map {
                        RearDesktopAppEntry(it.first, it.second.first, it.second.second)
                    }
            }
        }
    }

    private fun iconDrawable(
        pm: PackageManager,
        pkg: String,
    ): android.graphics.drawable.Drawable? =
        try {
            val ai = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationIcon(ai)
        } catch (_: PackageManager.NameNotFoundException) {
            null
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
}
