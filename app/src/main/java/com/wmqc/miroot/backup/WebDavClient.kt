package com.wmqc.miroot.backup

import com.wmqc.miroot.AppExecutors
import com.wmqc.miroot.lyrics.LogHelper
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object WebDavClient {

    private const val TAG = "WebDavClient"

    const val REMOTE_DIR = "MiRoot"
    const val REMOTE_BACKUP_NAME = "miroot_backup.zip"

    fun remoteBackupUrl(baseUrl: String): String {
        val base = normalizeBaseUrl(baseUrl)
        return base + encodePathSegments(REMOTE_DIR, REMOTE_BACKUP_NAME)
    }

    fun normalizeBaseUrl(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return ""
        if (!s.startsWith("http://", ignoreCase = true) &&
            !s.startsWith("https://", ignoreCase = true)
        ) {
            s = "https://$s"
        }
        if (!s.endsWith('/')) s += '/'
        return s
    }

    fun ensureRemoteDir(baseUrl: String, username: String, password: String): Result<Unit> {
        val url = normalizeBaseUrl(baseUrl) + encodePathSegments(REMOTE_DIR) + "/"
        val auth = authHeader(username, password)
        LogHelper.d(TAG, "MKCOL url=$url")
        val request = Request.Builder()
            .url(url)
            .header("Authorization", auth)
            .method("MKCOL", byteArrayOf().toRequestBody(null))
            .build()
        return executeMkcol(request)
    }

    fun uploadFile(
        baseUrl: String,
        username: String,
        password: String,
        localFile: File,
    ): Result<Unit> {
        ensureRemoteDir(baseUrl, username, password)
        val url = remoteBackupUrl(baseUrl)
        val auth = authHeader(username, password)
        LogHelper.d(TAG, "PUT url=$url size=${localFile.length()}")
        val body = localFile.asRequestBody("application/zip".toMediaType())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", auth)
            .put(body)
            .build()
        return execute(request).also { result ->
            result.exceptionOrNull()?.let { LogHelper.e(TAG, "upload failed: ${it.message}", it) }
        }
    }

    fun downloadFile(
        baseUrl: String,
        username: String,
        password: String,
        destFile: File,
    ): Result<Unit> {
        val url = remoteBackupUrl(baseUrl)
        val auth = authHeader(username, password)
        LogHelper.d(TAG, "GET url=$url")
        val request = Request.Builder()
            .url(url)
            .header("Authorization", auth)
            .get()
            .build()
        return runCatching {
            AppExecutors.okHttpClient.newCall(request).execute().use { response ->
                logResponse("GET", response)
                if (!response.isSuccessful) {
                    throw httpError(response)
                }
                val body = response.body ?: throw IllegalStateException("empty body")
                destFile.parentFile?.mkdirs()
                destFile.outputStream().use { out -> body.byteStream().copyTo(out) }
                Unit
            }
        }.also { result ->
            result.exceptionOrNull()?.let { LogHelper.e(TAG, "download failed: ${it.message}", it) }
        }
    }

    fun formatErrorMessage(error: Throwable?): String {
        val msg = error?.message.orEmpty()
        return when {
            msg.contains("empty url", ignoreCase = true) -> "请填写服务器地址"
            msg.contains("auth failed", ignoreCase = true) -> "账号或密码错误（坚果云请使用应用密码，不是登录密码）"
            msg.contains("HTTP 401") || msg.contains("HTTP 403") ->
                "账号或密码错误（坚果云请使用应用密码）"
            msg.contains("HTTP 405") ->
                "服务器不支持当前 WebDAV 方法，请确认地址为 DAV 根路径（如 https://dav.jianguoyun.com/dav/）"
            msg.contains("HTTP 404") ->
                "路径不存在，请检查服务器地址是否以 /dav/ 结尾"
            msg.contains("HTTP 301") || msg.contains("HTTP 302") || msg.contains("HTTP 307") ->
                "地址重定向异常，请使用带 https:// 的完整 DAV 地址"
            msg.contains("SSL", ignoreCase = true) ||
                msg.contains("Certificate", ignoreCase = true) ->
                "SSL 证书校验失败：$msg"
            msg.contains("Unable to resolve host", ignoreCase = true) ||
                msg.contains("UnknownHost", ignoreCase = true) ->
                "无法解析服务器地址，请检查网络与 URL"
            msg.contains("timeout", ignoreCase = true) ||
                msg.contains("Timeout", ignoreCase = true) ->
                "连接超时，请检查网络"
            msg.isNotBlank() -> msg
            else -> error?.javaClass?.simpleName ?: "未知错误"
        }
    }

    private fun authHeader(username: String, password: String): String =
        Credentials.basic(username.trim(), password, StandardCharsets.UTF_8)

    private fun executeMkcol(request: Request): Result<Unit> = runCatching {
        AppExecutors.okHttpClient.newCall(request).execute().use { response ->
            logResponse("MKCOL", response)
            when (response.code) {
                in 200..299, 405, 409 -> Unit
                else -> throw httpError(response)
            }
        }
    }.onFailure {
        LogHelper.w(TAG, "MKCOL: ${it.message}")
    }

    private fun execute(request: Request): Result<Unit> = runCatching {
        AppExecutors.okHttpClient.newCall(request).execute().use { response ->
            logResponse(request.method, response)
            if (!response.isSuccessful) {
                throw httpError(response)
            }
        }
    }

    private fun httpError(response: Response): IllegalStateException {
        val body = response.body?.string()?.take(200)?.replace('\n', ' ')
        val detail = if (body.isNullOrBlank()) "" else " $body"
        return IllegalStateException("HTTP ${response.code}$detail")
    }

    private fun logResponse(method: String, response: Response) {
        LogHelper.d(
            TAG,
            "$method ${response.request.url} -> HTTP ${response.code} ${response.message}",
        )
    }

    private fun encodePathSegments(vararg segments: String): String =
        segments.joinToString("/") { segment ->
            URLEncoder.encode(segment, StandardCharsets.UTF_8.name()).replace("+", "%20")
        }
}
