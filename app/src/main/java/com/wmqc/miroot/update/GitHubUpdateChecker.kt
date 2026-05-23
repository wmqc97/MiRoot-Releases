package com.wmqc.miroot.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class GitHubRelease(
    val tagName: String,
    val versionName: String,
    val downloadUrl: String,
    val body: String,
)

object GitHubUpdateChecker {

    private const val OWNER = "wmqc97"
    private const val REPO = "MiRoot"
    private const val API_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Fetch latest release info from GitHub API.
     * Returns null on failure (network, parse, or no release).
     */
    suspend fun fetchLatestRelease(): GitHubRelease? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(API_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                parseReleaseJson(body)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseReleaseJson(json: String): GitHubRelease? {
        return try {
            val root = JSONObject(json)
            val tagName = root.optString("tag_name", "").trim()
            val versionName = tagName.removePrefix("v")
            val body = root.optString("body", "")
            val assets = root.optJSONArray("assets")
            var downloadUrl = ""

            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk")) {
                        downloadUrl = asset.optString("browser_download_url", "")
                        break
                    }
                }
            }

            if (tagName.isEmpty() || downloadUrl.isEmpty()) return null

            GitHubRelease(
                tagName = tagName,
                versionName = versionName,
                downloadUrl = downloadUrl,
                body = body,
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Compare two version strings using semantic versioning.
     * Returns true if [latest] > [current].
     */
    fun hasUpdate(current: String, latest: String): Boolean {
        val curParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val latParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(curParts.size, latParts.size)

        for (i in 0 until maxLen) {
            val c = curParts.getOrElse(i) { 0 }
            val l = latParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    /**
     * Download APK from [url] to app cache directory, reporting progress via [onProgress].
     * [onProgress] receives (bytesRead, totalBytes).
     */
    suspend fun downloadApk(
        context: Context,
        url: String,
        onProgress: (Long, Long) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body ?: return@withContext null
                val totalBytes = body.contentLength()
                val fileName = "MiRoot-Update.apk"
                val targetFile = File(context.cacheDir, fileName)

                body.byteStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Long = 0
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            bytesRead += read
                            if (totalBytes > 0) {
                                onProgress(bytesRead, totalBytes)
                            }
                        }
                    }
                }
                targetFile
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Trigger APK installation via system package installer.
     */
    fun installApk(context: Context, apkFile: File): Boolean {
        return try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
