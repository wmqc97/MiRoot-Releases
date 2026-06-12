package com.wmqc.miroot.rear.truthdare

import android.content.Context
import org.json.JSONArray

/** 用户从出题池中移除的内置题 ID（assets 题目本身不可改，仅做隐藏）。 */
object TruthDareHiddenBuiltin {

    private const val KEY_HIDDEN_BUILTIN_IDS = "hidden_builtin_ids"

    fun load(context: Context): MutableSet<String> {
        val raw =
            TruthDarePrefs.prefs(context).getString(KEY_HIDDEN_BUILTIN_IDS, null)
                ?: return mutableSetOf()
        return runCatching {
            val arr = JSONArray(raw)
            buildSet {
                for (i in 0 until arr.length()) {
                    val id = arr.optString(i).trim()
                    if (id.isNotEmpty()) add(id)
                }
            }.toMutableSet()
        }.getOrDefault(mutableSetOf())
    }

    fun save(context: Context, ids: Set<String>) {
        val arr = JSONArray()
        ids.sorted().forEach { arr.put(it) }
        TruthDarePrefs.prefs(context)
            .edit()
            .putString(KEY_HIDDEN_BUILTIN_IDS, arr.toString())
            .apply()
    }

    fun hide(context: Context, id: String) {
        val set = load(context)
        if (set.add(id)) save(context, set)
    }

    fun hideAll(context: Context, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val set = load(context)
        if (set.addAll(ids)) save(context, set)
    }

    fun clear(context: Context) {
        TruthDarePrefs.prefs(context).edit().remove(KEY_HIDDEN_BUILTIN_IDS).apply()
    }
}
