package com.wmqc.miroot.lsp

import android.app.Application
import android.app.Instrumentation
import android.os.Build
import android.util.Log
import com.wmqc.miroot.BuildConfig
import com.wmqc.miroot.lyrics.LyricsIntents
import com.wmqc.miroot.lyrics.QishuiLyricsJsonParser
import com.wmqc.miroot.mv.MvIntents
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

/**
 * Modern Xposed (LSPosed / libxposed) module entry (API 101).
 *
 * Notes:
 * - Entry is registered in `META-INF/xposed/java_init.list`
 * - Scope is declared in `META-INF/xposed/scope.list`
 */
class MiRootLspModule : XposedModule() {

    companion object {
        private const val TAG = "MiRoot-LSP"

        private const val KUWO_PKG = "cn.kuwo.kwmusiccar"
        private const val KUWO_KW_VIDEO_PLAYER = "cn.kuwo.base.view.KwVideoPlayer"
        private const val QISHUI_PKG = "com.luna.music"
        private const val MIROOT_PKG = "com.wmqc.miroot"
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        log(Log.INFO, TAG, "module loaded: process=${param.processName}")
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        // Avoid repeating hooks for non-first package loads in the same process.
        if (!param.isFirstPackage) return

        val pkg = param.packageName ?: "?"
        log(Log.INFO, TAG, "package ready: $pkg")

        // Hook Instrumentation.callApplicationOnCreate(Application) as a stable entry point.
        tryHookApplicationOnCreate(pkg)
    }

    private fun tryHookApplicationOnCreate(targetPkg: String) {
        // Instrumentation is in boot classpath; a target app classloader is not required here.
        val m: Method = Instrumentation::class.java.getDeclaredMethod(
            "callApplicationOnCreate",
            Application::class.java,
        )

        hook(m)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
            .intercept { chain ->
                val app = chain.args?.getOrNull(0) as? Application
                val appPkg = runCatching { app?.packageName }.getOrNull()
                if (appPkg == targetPkg) {
                    log(
                        Log.INFO,
                        TAG,
                        "callApplicationOnCreate: pkg=$appPkg app=${app?.javaClass?.name} sdk=${Build.VERSION.SDK_INT}",
                    )

                    if (appPkg == KUWO_PKG) {
                        runCatching { tryHookKuwoMvUrl(app) }
                            .onFailure { e -> log(Log.WARN, TAG, "hook kuwo failed: ${e.message}") }
                    }
                    if (appPkg == QISHUI_PKG) {
                        runCatching { tryHookQishuiLyrics(app) }
                            .onFailure { e -> log(Log.WARN, TAG, "hook qishui failed: ${e.message}") }
                    }
                }
                chain.proceed()
            }
    }

    @Volatile
    private var kuwoHooked = false

    @Volatile
    private var lastMvUrl: String? = null

    @Volatile
    private var lastMvTs: Long = 0L

    private fun tryHookKuwoMvUrl(app: Application?) {
        if (app == null) return
        if (kuwoHooked) return
        kuwoHooked = true

        val cl = app.classLoader ?: return
        val klass = Class.forName(KUWO_KW_VIDEO_PLAYER, false, cl)
        val m = klass.getDeclaredMethod("prepare", String::class.java)
        log(Log.INFO, TAG, "hooking $KUWO_KW_VIDEO_PLAYER#prepare(String)")

        hook(m)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
            .intercept { chain ->
                val url = chain.args?.getOrNull(0) as? String
                if (!url.isNullOrBlank() && looksLikeVideoUrl(url)) {
                    val now = System.currentTimeMillis()
                    val lastUrlLocal = lastMvUrl
                    val shouldEmit =
                        (lastUrlLocal == null || lastUrlLocal != url) && (now - lastMvTs > 800)
                    if (shouldEmit) {
                        lastMvUrl = url
                        lastMvTs = now
                        log(Log.INFO, TAG, "kuwo mv url: $url")
                        runCatching { launchRearMvPlayer(app, url) }
                    }
                }
                chain.proceed()
            }
    }

    private fun looksLikeVideoUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.contains(".m3u8") || u.contains(".mp4") || u.contains(".flv")
    }

    private fun launchRearMvPlayer(app: Application, url: String) {
        val i = android.content.Intent(MvIntents.ACTION_OPEN_REAR_MV_PLAYER).apply {
            `package` = MIROOT_PKG
            putExtra(MvIntents.EXTRA_MV_URL, url)
        }
        app.sendBroadcast(i)
    }

    @Volatile
    private var qishuiHooked = false

    @Volatile
    private var qishuiJsonHooked = false

    @Volatile
    private var lastQishuiLyricFp: Int = 0

    private fun tryHookQishuiLyrics(app: Application?) {
        if (app == null) return
        if (qishuiHooked) return
        qishuiHooked = true

        // 主链路：拦截 okhttp ResponseBody#string()（性能开销低，优先）。
        val cl = app.classLoader ?: return
        val rbClass = runCatching { Class.forName("okhttp3.ResponseBody", false, cl) }.getOrNull()
        if (rbClass == null) {
            log(Log.WARN, TAG, "qishui: okhttp3.ResponseBody not found")
        } else {
            val m = runCatching { rbClass.getDeclaredMethod("string") }.getOrNull()
            if (m == null) {
                log(Log.WARN, TAG, "qishui: ResponseBody#string() not found")
            } else {
                log(Log.INFO, TAG, "hooking qishui okhttp3.ResponseBody#string()")

                hook(m)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept { chain ->
                        val result = chain.proceed()
                        val s = result as? String
                        if (!s.isNullOrBlank() && s.length in 64..2_000_000) {
                            tryExtractAndEmitQishuiLyrics(app, s, "qishui-hook-okhttp")
                        }
                        result
                    }
            }
        }

        // 兜底链路：汽水新版本可能不再直接调用 ResponseBody#string()，改在 JSON 反序列化路径取值。
        // 仅在 qishui 进程内生效，且通过内容特征 + 指纹去重，避免广播风暴。
        tryHookQishuiJsonDecoders(app)
    }

    private fun tryHookQishuiJsonDecoders(app: Application) {
        if (qishuiJsonHooked) return
        qishuiJsonHooked = true

        val jsonObjectCtor = runCatching {
            org.json.JSONObject::class.java.getDeclaredConstructor(String::class.java)
        }.getOrNull()
        if (jsonObjectCtor != null) {
            log(Log.INFO, TAG, "hooking qishui JSONObject(String)")
            hook(jsonObjectCtor)
                .setPriority(XposedInterface.PRIORITY_LOWEST)
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    val result = chain.proceed()
                    val raw = chain.args?.getOrNull(0) as? String
                    if (!raw.isNullOrBlank() && raw.length in 64..2_000_000 && looksLikeLyricsPayload(raw)) {
                        tryExtractAndEmitQishuiLyrics(app, raw, "qishui-hook-jsonobj")
                    }
                    result
                }
        }

        val jsonArrayCtor = runCatching {
            org.json.JSONArray::class.java.getDeclaredConstructor(String::class.java)
        }.getOrNull()
        if (jsonArrayCtor != null) {
            log(Log.INFO, TAG, "hooking qishui JSONArray(String)")
            hook(jsonArrayCtor)
                .setPriority(XposedInterface.PRIORITY_LOWEST)
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    val result = chain.proceed()
                    val raw = chain.args?.getOrNull(0) as? String
                    if (!raw.isNullOrBlank() && raw.length in 64..2_000_000 && looksLikeLyricsPayload(raw)) {
                        tryExtractAndEmitQishuiLyrics(app, raw, "qishui-hook-jsonarr")
                    }
                    result
                }
        }
    }

    private fun looksLikeLyricsPayload(raw: String): Boolean {
        // 低成本关键词门禁，减少在 JSON 构造路径上的无意义解析。
        val s = raw.lowercase()
        return s.contains("lyric")
            || s.contains("lyrics")
            || s.contains("\"content\"")
            || s.contains("krc")
            || s.contains("lrc")
            || s.contains("sentence")
            || s.contains("line")
    }

    private fun tryExtractAndEmitQishuiLyrics(app: Application, payload: String, source: String) {
        val lyric = QishuiLyricsJsonParser.extractLyricContent(payload)
        if (lyric.isNullOrBlank()) return
        val fp = lyric.hashCode()
        if (fp == lastQishuiLyricFp) return
        lastQishuiLyricFp = fp
        if (BuildConfig.DEBUG) {
            val preview = lyric.replace('\n', ' ').take(80)
            log(Log.INFO, TAG, "qishui lyric extracted: len=${lyric.length} source=$source preview=\"$preview\"")
        }
        emitExternalLyrics(app, lyric, source)
    }

    private fun emitExternalLyrics(app: Application, text: String, source: String) {
        try {
            if (BuildConfig.DEBUG) {
                log(Log.INFO, TAG, "emitExternalLyrics -> $MIROOT_PKG source=$source len=${text.length}")
            }
            val i = android.content.Intent(LyricsIntents.ACTION_PUSH_EXTERNAL_LYRICS).apply {
                `package` = MIROOT_PKG
                putExtra(LyricsIntents.EXTRA_EXTERNAL_LYRICS_TEXT, text)
                putExtra(LyricsIntents.EXTRA_EXTERNAL_LYRICS_SOURCE, source)
                putExtra(LyricsIntents.EXTRA_EXTERNAL_LYRICS_PACKAGE, app.packageName ?: "")
            }
            app.sendBroadcast(i)
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "emitExternalLyrics failed: ${t.message}")
        }
    }
}

