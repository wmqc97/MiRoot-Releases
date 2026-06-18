package com.wmqc.miroot.ui.music

import android.content.Context
import com.wmqc.miroot.lyrics.LogHelper

object MusicAutoProjectionPrefs {
    private const val PREF = "music_auto_projection_v1"
    private const val TAG = "MusicAutoProjectionPrefs"
    private const val KEY_MUSIC_BLACKLIST = "music_blacklist_csv"

    private fun openPrefs(context: Context) =
        try {
            context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        } catch (e: IllegalStateException) {
            LogHelper.w(TAG, "prefs CE locked: ${e.message}")
            null
        } catch (e: Exception) {
            LogHelper.w(TAG, "prefs open failed: ${e.message}")
            null
        }

    fun blacklist(context: Context): Set<String> {
        val sp = openPrefs(context) ?: return emptySet()
        val raw = sp.getString(KEY_MUSIC_BLACKLIST, "").orEmpty()
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun setBlacklist(context: Context, packages: Set<String>) {
        val sp = openPrefs(context) ?: return
        sp.edit()
            .putString(KEY_MUSIC_BLACKLIST, packages.filter { it.isNotBlank() }.sorted().joinToString(","))
            .commit()
    }

    fun isBlacklisted(context: Context, packageName: String): Boolean {
        return packageName in blacklist(context)
    }

    fun addToBlacklist(context: Context, packageName: String) {
        val current = blacklist(context).toMutableSet()
        if (current.add(packageName)) {
            setBlacklist(context, current)
        }
    }

    fun removeFromBlacklist(context: Context, packageName: String) {
        val current = blacklist(context).toMutableSet()
        if (current.remove(packageName)) {
            setBlacklist(context, current)
        }
    }
}
