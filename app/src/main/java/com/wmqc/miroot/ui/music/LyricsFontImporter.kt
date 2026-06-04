package com.wmqc.miroot.ui.music

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * 将用户选择的字体文件复制到应用私有目录，供背屏歌词 [com.wmqc.miroot.lyrics.LyricsFontHelper] 加载。
 */
object LyricsFontImporter {

    enum class Slot(val destBaseName: String) {
        PROJECTION("projection_lyrics"),
        ABYSSAL("abyssal_lyrics"),
        CHARGING("charging_display"),
    }

    fun copyImportedFont(context: Context, uri: Uri, slot: Slot): String? {
        return try {
            val dir = File(context.filesDir, "lyrics_fonts").apply { mkdirs() }
            val ext = sanitizeExtension(resolveExtension(context, uri))
            val dest = File(dir, "${slot.destBaseName}.$ext")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            dest.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveExtension(context: Context, uri: Uri): String {
        val name = queryDisplayName(context, uri) ?: return "ttf"
        val dot = name.lastIndexOf('.')
        if (dot in 1 until name.length - 1) {
            return name.substring(dot + 1)
        }
        return "ttf"
    }

    private fun sanitizeExtension(raw: String): String {
        val e = raw.lowercase().filter { it.isLetterOrDigit() }
        return when (e) {
            "ttf", "otf", "ttc" -> e
            else -> "ttf"
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            var c: Cursor? = null
            try {
                c = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                if (c != null && c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) return c.getString(i)
                }
            } finally {
                c?.close()
            }
        }
        val path = uri.path
        if (!path.isNullOrBlank()) {
            val slash = path.lastIndexOf('/')
            if (slash >= 0 && slash < path.length - 1) return path.substring(slash + 1)
        }
        return null
    }
}
