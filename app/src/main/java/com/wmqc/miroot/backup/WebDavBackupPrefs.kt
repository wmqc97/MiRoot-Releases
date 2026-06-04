package com.wmqc.miroot.backup

import android.content.Context

/** WebDAV 连接配置（仅存本机，不参与配置备份包）。 */
object WebDavBackupPrefs {
    const val PREFS_NAME = "miroot_webdav_backup"

    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"

    fun serverUrl(context: Context): String =
        prefs(context).getString(KEY_SERVER_URL, "").orEmpty().trim()

    fun username(context: Context): String =
        prefs(context).getString(KEY_USERNAME, "").orEmpty().trim()

    fun password(context: Context): String =
        prefs(context).getString(KEY_PASSWORD, "").orEmpty()

    fun save(context: Context, serverUrl: String, username: String, password: String) {
        prefs(context).edit()
            .putString(KEY_SERVER_URL, serverUrl.trim())
            .putString(KEY_USERNAME, username.trim())
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun isConfigured(context: Context): Boolean {
        val url = serverUrl(context)
        return url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
