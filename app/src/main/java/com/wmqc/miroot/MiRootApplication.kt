package com.wmqc.miroot

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.wmqc.miroot.BuildConfig
import com.wmqc.miroot.lyrics.LyricsWordTokenizer
import com.wmqc.miroot.lyrics.RootTaskServiceConnector
import com.wmqc.miroot.lyrics.SuperLyricApi
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class MiRootApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 修复：限制 Dispatchers.IO 最大线程数，防止 CoroutineScheduler 创建过多工作线程，
        // 导致 pthread_create (1040KB stack) failed（虚拟内存地址空间耗尽）。
        // 必须在首次访问 Dispatchers.IO 前设置：本项目 onCreate 内所有初始化均不依赖 Dispatchers.IO，
        // 故此处安全。默认值 max(64, cores) 在高核数与多 raw thread 并发场景下易触发 native OOM。
        System.setProperty("kotlinx.coroutines.io.parallelism", "16")
        LyricsWordTokenizer.initialize(this)
        RootTaskServiceConnector.prewarm(this)
        // 与 HChenX/SuperLyricApi 接收端一致：进程启动即注册 Binder，减少首句逐字等待。
        SuperLyricApi.ensureReceiverRegistered()
        verifyReleaseSignatureOrExit()
        enforceNoProxyOrVpnOrExit()
    }

    /**
     * Release 包签名校验（仅日志，不杀进程）。
     * 构建时 [BuildConfig.RELEASE_CERT_SHA256] 来自 release keystore；二次签名、Play 重签等会导致不匹配，
     * 旧逻辑直接 killProcess 会被用户感知为「一打开就闪退」。
     */
    private fun verifyReleaseSignatureOrExit() {
        if (BuildConfig.DEBUG) return
        val expected = BuildConfig.RELEASE_CERT_SHA256.trim()
        if (expected.isEmpty()) {
            Log.w(TAG, "签名校验已跳过：BuildConfig.RELEASE_CERT_SHA256 为空（可能未配置 release keystore）")
            return
        }

        val actuals = runCatching { collectInstalledCertSha256List() }.getOrElse { emptyList() }
        if (actuals.isEmpty()) {
            Log.e(TAG, "签名校验：无法解析安装包证书指纹，已跳过（避免误杀）expected=$expected")
            return
        }

        val normExpected = normalizeHex(expected)
        val matched = actuals.any { normalizeHex(it).equals(normExpected, ignoreCase = true) }
        if (!matched) {
            Log.e(
                TAG,
                "签名校验：证书与构建时不一致（可能二次签名或分发渠道重签），" +
                    "expected=$normExpected actuals=${actuals.joinToString()}",
            )
        }
    }

    private fun enforceNoProxyOrVpnOrExit() {
        if (BuildConfig.DEBUG) return
        if (isProxyConfigured()) {
            Log.w(TAG, "检测到系统代理配置：已记录告警（不再强制退出）")
            return
        }
        if (isVpnActive()) {
            Log.w(TAG, "检测到 VPN 传输：已记录告警（不再强制退出）")
        }
    }

    private fun isProxyConfigured(): Boolean {
        return try {
            val httpHost = System.getProperty("http.proxyHost")?.orEmpty().orEmpty()
            val httpPort = System.getProperty("http.proxyPort")?.toIntOrNull() ?: -1
            val httpsHost = System.getProperty("https.proxyHost")?.orEmpty().orEmpty()
            val httpsPort = System.getProperty("https.proxyPort")?.toIntOrNull() ?: -1
            (httpHost.isNotBlank() && httpPort > 0) || (httpsHost.isNotBlank() && httpsPort > 0)
        } catch (_: Throwable) {
            false
        }
    }

    private fun isVpnActive(): Boolean {
        return try {
            val cm =
                getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val nc = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } catch (_: Throwable) {
            false
        }
    }

    private fun collectInstalledCertSha256List(): List<String> {
        return readInstalledSignatures(packageManager, packageName)
            .mapNotNull { sha256HexFromSignature(it) }
            .distinct()
    }

    private fun readInstalledSignatures(pm: PackageManager, pkg: String): List<Signature> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            val signers = info.signingInfo?.apkContentsSigners ?: return emptyList()
            return signaturesToList(signers)
        }
        @Suppress("DEPRECATION")
        val legacy = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
            ?: return emptyList()
        return signaturesToList(legacy)
    }

    /** Java Signature[] 平台类型在 Kotlin 中勿直接 .toList()，用下标拷贝。 */
    private fun signaturesToList(signers: Array<Signature>): List<Signature> =
        List(signers.size) { signers[it] }

    /** 与 build.gradle 中 releaseCertSha256（keystore 证书 DER 的 SHA-256）对齐。 */
    private fun sha256HexFromSignature(sig: Signature): String? {
        return try {
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(sig.toByteArray())) as X509Certificate
            sha256Hex(cert.encoded)
        } catch (_: Throwable) {
            runCatching { sha256Hex(sig.toByteArray()) }.getOrNull()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString("") { b -> "%02X".format(b) }
    }

    private fun normalizeHex(s: String): String =
        s.uppercase().replace(":", "").replace(" ", "")

    companion object {
        private const val TAG = "MiRootApplication"
    }
}

