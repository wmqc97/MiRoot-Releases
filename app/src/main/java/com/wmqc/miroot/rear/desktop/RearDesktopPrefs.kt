package com.wmqc.miroot.rear.desktop

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.wmqc.miroot.lyrics.LogHelper
import org.json.JSONObject

/**
 * 背屏桌面：列表模式（自选应用 / 全部应用）、自选应用顺序、黑名单、使用频次（全部应用模式排序）。
 */
object RearDesktopPrefs {
    private const val PREF = "rear_desktop_v1"
    private const val TAG = "RearDesktopPrefs"
    private const val KEY_LIST_MODE = "list_mode"
    private const val KEY_ACTIVE_LAYOUT_ID = "active_layout_id"
    private const val KEY_LAYOUTS_JSON = "layouts_json"
    private const val KEY_BLACKLIST = "blacklist_csv"
    private const val KEY_USAGE_JSON = "usage_v1_json"

    const val DEFAULT_LAYOUT_ID = "default"

    /** 未写入过 [KEY_LIST_MODE] 时采用（首次安装 / 老数据无该键）。 */
    private val defaultListModeName: String = RearDesktopListMode.ALL_BY_FREQUENCY.name

    /** 应用列表刷新背屏勾选状态（模式切换等）。仅包内广播。 */
    const val ACTION_REAR_DESKTOP_PREFS_CHANGED =
        "com.wmqc.miroot.rear.desktop.ACTION_PREFS_CHANGED"

    fun notifyPrefsChanged(context: Context) {
        val app = context.applicationContext
        app.sendBroadcast(
            Intent(ACTION_REAR_DESKTOP_PREFS_CHANGED).setPackage(app.packageName),
        )
    }

    /**
     * 默认 SharedPreferences 位于 CE 存储；Direct Boot / 用户未解锁时读取会抛 [IllegalStateException]，
     * 若不处理会导致背屏桌面等界面直接崩溃。
     */
    private fun openPrefs(context: Context): SharedPreferences? =
        try {
            context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        } catch (e: IllegalStateException) {
            LogHelper.w(TAG, "prefs CE locked: ${e.message}")
            null
        } catch (e: Exception) {
            LogHelper.w(TAG, "prefs open failed: ${e.message}")
            null
        }

    fun listMode(context: Context): RearDesktopListMode {
        val sp = openPrefs(context) ?: return RearDesktopListMode.ALL_BY_FREQUENCY
        return when (sp.getString(KEY_LIST_MODE, defaultListModeName)) {
            RearDesktopListMode.ALL_BY_FREQUENCY.name -> RearDesktopListMode.ALL_BY_FREQUENCY
            else -> RearDesktopListMode.MANUAL
        }
    }

    fun setListMode(context: Context, mode: RearDesktopListMode) {
        val sp = openPrefs(context) ?: return
        sp.edit()
            .putString(KEY_LIST_MODE, mode.name)
            .commit()
    }

    /** 自选应用模式下的启动顺序（包名）。旧版多套方案会在首次读取时合并到单一列表。 */
    fun manualOrder(context: Context): List<String> {
        val app = context.applicationContext
        migrateLayoutsToDefaultIfNeeded(app)
        val layouts = readLayoutsJson(app)
        val arr = layouts.optJSONArray(DEFAULT_LAYOUT_ID) ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "").trim()
                if (s.isNotEmpty()) add(s)
            }
        }
    }

    fun setManualOrder(context: Context, packages: List<String>) {
        val app = context.applicationContext
        migrateLayoutsToDefaultIfNeeded(app)
        val layouts = readLayoutsJson(app)
        val arr = org.json.JSONArray()
        val seen = mutableSetOf<String>()
        for (p in packages) {
            val pkg = p.trim()
            if (pkg.isEmpty() || !seen.add(pkg)) continue
            arr.put(pkg)
        }
        val keys = layouts.keys().asSequence().toList()
        for (k in keys) {
            if (k != DEFAULT_LAYOUT_ID) layouts.remove(k)
        }
        layouts.put(DEFAULT_LAYOUT_ID, arr)
        writeLayoutsJson(app, layouts)
    }

    /**
     * 将旧版「多套方案」JSON 合并为仅 [DEFAULT_LAYOUT_ID] 一条列表，并移除 [KEY_ACTIVE_LAYOUT_ID]。
     */
    private fun migrateLayoutsToDefaultIfNeeded(app: Context) {
        val sp = openPrefs(app) ?: return
        val raw = sp.getString(KEY_LAYOUTS_JSON, null)
        val layouts =
            when {
                raw.isNullOrBlank() -> return
                else ->
                    try {
                        JSONObject(raw)
                    } catch (_: Exception) {
                        return
                    }
            }
        val keys = layouts.keys().asSequence().toList()
        if (keys.isEmpty()) return
        if (keys.size == 1 && keys[0] == DEFAULT_LAYOUT_ID) return

        fun orderFor(id: String): List<String> {
            val arr = layouts.optJSONArray(id) ?: return emptyList()
            return buildList {
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i, "").trim()
                    if (s.isNotEmpty()) add(s)
                }
            }
        }

        val activeId =
            (sp.getString(KEY_ACTIVE_LAYOUT_ID, null) ?: DEFAULT_LAYOUT_ID).trim().ifEmpty {
                DEFAULT_LAYOUT_ID
            }
        val merged = LinkedHashSet<String>()
        orderFor(activeId).forEach { merged.add(it) }
        orderFor(DEFAULT_LAYOUT_ID).forEach { merged.add(it) }
        keys.filter { it != activeId && it != DEFAULT_LAYOUT_ID }.sorted().forEach { id ->
            orderFor(id).forEach { merged.add(it) }
        }

        val out = JSONObject()
        val arr = org.json.JSONArray()
        merged.forEach { arr.put(it) }
        out.put(DEFAULT_LAYOUT_ID, arr)
        sp.edit()
            .putString(KEY_LAYOUTS_JSON, out.toString())
            .remove(KEY_ACTIVE_LAYOUT_ID)
            .commit()
    }

    fun blacklist(context: Context): Set<String> {
        val sp = openPrefs(context) ?: return emptySet()
        val raw = sp.getString(KEY_BLACKLIST, "").orEmpty()
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun setBlacklist(context: Context, packages: Set<String>) {
        val sp = openPrefs(context) ?: return
        val csv = packages.filter { it.isNotBlank() }.sorted().joinToString(",")
        sp.edit()
            .putString(KEY_BLACKLIST, csv)
            .commit()
    }

    fun recordLaunch(context: Context, packageName: String) {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return
        val app = context.applicationContext
        val sp = openPrefs(app) ?: return
        val usage = try {
            JSONObject(sp.getString(KEY_USAGE_JSON, "{}").orEmpty())
        } catch (_: Exception) {
            JSONObject()
        }
        val key = pkg
        val n = usage.optLong(key, 0L) + 1L
        usage.put(key, n)
        try {
            sp.edit().putString(KEY_USAGE_JSON, usage.toString()).apply()
        } catch (e: Exception) {
            LogHelper.w(TAG, "recordLaunch write failed: ${e.message}")
        }
    }

    fun usageCount(context: Context, packageName: String): Long {
        val sp = openPrefs(context) ?: return 0L
        val usage =
            try {
                JSONObject(sp.getString(KEY_USAGE_JSON, "{}").orEmpty())
            } catch (_: Exception) {
                JSONObject()
            }
        return usage.optLong(packageName, 0L)
    }

    /** 频次快照哈希：用于列表缓存 key，避免每次全量读取 launcher 后再排序。 */
    fun usageSnapshotHash(context: Context): Int {
        val sp = openPrefs(context) ?: return 0
        return sp.getString(KEY_USAGE_JSON, "{}").orEmpty().hashCode()
    }

    private fun readLayoutsJson(context: Context): JSONObject {
        val sp = openPrefs(context) ?: return JSONObject()
        val raw = sp.getString(KEY_LAYOUTS_JSON, null)
        return try {
            if (raw.isNullOrBlank()) JSONObject() else JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun writeLayoutsJson(context: Context, layouts: JSONObject) {
        val sp = openPrefs(context) ?: return
        sp.edit()
            .putString(KEY_LAYOUTS_JSON, layouts.toString())
            .commit()
    }
}
