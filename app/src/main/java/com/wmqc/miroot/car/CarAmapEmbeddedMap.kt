package com.wmqc.miroot.car

import android.annotation.SuppressLint
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.wmqc.miroot.lyrics.LogHelper
import java.util.Locale

/**
 * 车控页内嵌高德 JS 地图（经 [WebViewAssetLoader] 以 https 域加载 assets，避免 file:// 白屏）。
 * 需在高德控制台为同一 Key 开通 **Web 端 (JS API)** 并配置包名与 SHA1。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CarAmapEmbeddedMap(
    lng: Double,
    lat: Double,
    refreshKey: Int,
    modifier: Modifier = Modifier,
    onLoadFailed: () -> Unit = {},
) {
    val mapUrl = remember(lng, lat, refreshKey) {
        buildAmapAssetMapUrl(lng, lat)
    }

    key(mapUrl) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                val assetLoader = WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                    .build()
                WebView(context).apply {
                    setBackgroundColor(0x00000000)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): android.webkit.WebResourceResponse? =
                            assetLoader.shouldInterceptRequest(request.url)

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?,
                        ) {
                            if (request?.isForMainFrame == true) {
                                LogHelper.w(TAG, "WebView map error: ${error?.description}")
                                onLoadFailed()
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onReceivedError(
                            view: WebView?,
                            errorCode: Int,
                            description: String?,
                            failingUrl: String?,
                        ) {
                            if (failingUrl == mapUrl) {
                                LogHelper.w(TAG, "WebView map error(legacy): $description")
                                onLoadFailed()
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            view?.evaluateJavascript(
                                String.format(Locale.US, "updatePosition(%f,%f)", lng, lat),
                                null,
                            )
                        }
                    }
                    loadUrl(mapUrl)
                }
            },
            update = { webView ->
                webView.evaluateJavascript(
                    String.format(Locale.US, "updatePosition(%f,%f)", lng, lat),
                    null,
                )
            },
        )
    }
}

private const val TAG = "CarAmapEmbeddedMap"

/** 通过 https 虚拟域访问 assets/car/amap_map.html */
fun buildAmapAssetMapUrl(lng: Double, lat: Double): String {
    val key = AmapApiService.AMAP_KEY
    return "https://appassets.androidplatform.net/assets/car/amap_map.html" +
        "?key=${java.net.URLEncoder.encode(key, Charsets.UTF_8.name())}" +
        "&lng=$lng&lat=$lat"
}
