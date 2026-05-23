package com.wmqc.miroot.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

data class GitHubRelease(
    val tagName: String,
    val versionName: String,
    val downloadUrl: String,
    val body: String,
)

/** Result of checking for a latest release on GitHub. */
sealed class UpdateCheckResult {
    data class Success(val release: GitHubRelease) : UpdateCheckResult()
    data class Error(val reason: ErrorReason) : UpdateCheckResult()
}

enum class ErrorReason {
    NETWORK_ERROR,
    RATE_LIMITED,
    NOT_FOUND,
    NO_RELEASE,
    NO_APK_ASSET,
    PARSE_ERROR,
    UNKNOWN,
}

/** Result of downloading an APK. */
sealed class DownloadResult {
    data class Success(val file: File) : DownloadResult()
    data object Cancelled : DownloadResult()
    data class Error(val reason: DownloadErrorReason) : DownloadResult()
}

enum class DownloadErrorReason {
    NETWORK_ERROR,
    HTTP_ERROR,
    NO_CONTENT,
    FILE_WRITE_ERROR,
    DISK_FULL,
    UNKNOWN,
}

object GitHubUpdateChecker {

    private const val OWNER = "wmqc97"
    private const val REPO = "MiRoot"
    private const val API_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val USER_AGENT = "MiRoot-Android/1.0"

    /** 手动检测最小间隔（防按钮连点，毫秒）。 */
    private const val MANUAL_COOLDOWN_MS = 30_000L

    /** GitHub 认证令牌。私仓须在 [local.properties] 中配置 [GITHUB_TOKEN]，由 [init] 在 Application.onCreate 注入。 */
    private var authToken: String? = null

    /** 上次调用 [fetchLatestRelease] 的时间戳（进程内，用于手动检测节流）。 */
    @Volatile
    private var lastFetchTimeMs: Long = 0L

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 在 [Application.onCreate] 中调用，注入 [BuildConfig.GITHUB_TOKEN]。 */
    fun init(token: String?) {
        authToken = token?.takeIf { it.isNotBlank() }
    }

    /** 手动检测节流：距上次检测不足 [MANUAL_COOLDOWN_MS] 时返回 true。 */
    fun isManualCheckThrottled(): Boolean =
        System.currentTimeMillis() - lastFetchTimeMs < MANUAL_COOLDOWN_MS

    /** 若已配置令牌，返回 "token xxx"，否则 null。 */
    private fun authHeaderValue(): String? =
        authToken?.let { "token $it" }

    /**
     * Fetch latest release info from GitHub API.
     * Returns [UpdateCheckResult.Success] on success,
     * or [UpdateCheckResult.Error] with a specific reason on failure.
     */
    suspend fun fetchLatestRelease(): UpdateCheckResult = withContext(Dispatchers.IO) {
        lastFetchTimeMs = System.currentTimeMillis()
        try {
            val req = Request.Builder()
                .url(API_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", USER_AGENT)
                .apply { authHeaderValue()?.let { header("Authorization", it) } }
                .build()

            client.newCall(req).execute().use { resp ->
                val code = resp.code
                when {
                    code in 200..299 -> {
                        val body = resp.body?.string() ?: return@withContext ErrorReason.NETWORK_ERROR.toResult()
                        parseReleaseJson(body)
                    }
                    code == 403 || code == 429 -> ErrorReason.RATE_LIMITED.toResult()
                    code == 404 -> ErrorReason.NOT_FOUND.toResult()
                    else -> ErrorReason.NETWORK_ERROR.toResult()
                }
            }
        } catch (_: Exception) {
            ErrorReason.NETWORK_ERROR.toResult()
        }
    }

    private fun parseReleaseJson(json: String): UpdateCheckResult {
        return try {
            val root = JSONObject(json)
            val tagName = root.optString("tag_name", "").trim()
            if (tagName.isEmpty()) return ErrorReason.NO_RELEASE.toResult()

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

            if (downloadUrl.isEmpty()) return ErrorReason.NO_APK_ASSET.toResult()

            UpdateCheckResult.Success(
                GitHubRelease(
                    tagName = tagName,
                    versionName = versionName,
                    downloadUrl = downloadUrl,
                    body = body,
                )
            )
        } catch (_: Exception) {
            ErrorReason.PARSE_ERROR.toResult()
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
     * Return the cached APK file for [versionName] if it exists.
     */
    fun getCachedApk(context: Context, versionName: String): File? {
        val f = File(context.cacheDir, "MiRoot-${versionName}.apk")
        return f.takeIf { it.exists() }
    }

    /**
     * Download APK from [url] to app cache directory, reporting progress via [onProgress].
     *
     * Uses versioned filenames so re-checking the same version skips the download.
     * Writes to a `.tmp` file first, then renames on success.
     * Supports cancellation via parent coroutine [Job.cancel].
     *
     * [onProgress] receives (bytesRead, totalBytes).
     */
    suspend fun downloadApk(
        context: Context,
        url: String,
        versionName: String,
        onProgress: (Long, Long) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        // Return cached file if already downloaded
        val cached = getCachedApk(context, versionName)
        if (cached != null) return@withContext DownloadResult.Success(cached)

        val targetFile = File(context.cacheDir, "MiRoot-${versionName}.apk")
        val tempFile = File(context.cacheDir, "MiRoot-${versionName}.apk.tmp")

        try {
            val req = Request.Builder().url(url)
                .apply { authHeaderValue()?.let { header("Authorization", it) } }
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    tempFile.delete()
                    return@withContext DownloadResult.Error(DownloadErrorReason.HTTP_ERROR)
                }
                val body = resp.body ?: run {
                    tempFile.delete()
                    return@withContext DownloadResult.Error(DownloadErrorReason.NO_CONTENT)
                }
                val totalBytes = body.contentLength()

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Long = 0
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            ensureActive() // support cancellation
                            output.write(buffer, 0, read)
                            bytesRead += read
                            if (totalBytes > 0) {
                                onProgress(bytesRead, totalBytes)
                            }
                        }
                    }
                }

                // Rename .tmp to final filename
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.delete()
                    return@withContext DownloadResult.Error(DownloadErrorReason.FILE_WRITE_ERROR)
                }
                DownloadResult.Success(targetFile)
            }
        } catch (e: CancellationException) {
            tempFile.delete()
            DownloadResult.Cancelled
        } catch (e: IOException) {
            tempFile.delete()
            DownloadResult.Error(DownloadErrorReason.FILE_WRITE_ERROR)
        } catch (_: Exception) {
            tempFile.delete()
            DownloadResult.Error(DownloadErrorReason.NETWORK_ERROR)
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

    // ---- internal helpers ----

    private fun ErrorReason.toResult(): UpdateCheckResult = UpdateCheckResult.Error(this)
}
