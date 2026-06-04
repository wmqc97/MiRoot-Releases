/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪
 * 
 * Co-developed with AI assistants:
 * - Cursor
 * - Claude-4.5-Sonnet
 * - GPT-5
 * - Gemini-2.5-Pro
 */

package com.wmqc.miroot.lyrics;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.icu.text.Transliterator;

import com.wmqc.miroot.AppExecutors;

import java.io.InterruptedIOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import javax.net.ssl.SSLException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 第三方歌词API客户端
 * 支持多个歌词服务提供商
 */
public class LyricsAPIClient {
    private static final String TAG = "LyricsAPIClient";
    private static final int CONNECT_TIMEOUT = 5000; // 5秒连接超时
    private static final int READ_TIMEOUT = 10000; // 10秒读取超时
    /**
     * 汽水 qsgc 接口偏慢：冷启动常 3～8s，移动网络偶发 >10s。
     * 自测链接：https://apiv1.yrain.top/qsgc.php?msg=歌名-歌手（例：晴天-周杰伦）
     * 建议 connect 6～8s、read 12～15s；当前 6s + 14s。
     */
    private static final int QSGC_CONNECT_TIMEOUT = 6000;
    private static final int QSGC_READ_TIMEOUT = 14000;
    // lrclib 兜底接口：服务器相对较慢，适当放宽超时，避免过早放弃
    private static final int LRCLIB_CONNECT_TIMEOUT = 5000; // 连接 5 秒
    private static final int LRCLIB_READ_TIMEOUT = 8000;    // 读取 8 秒
    private static final String LRCLIB_SEARCH_API = "https://lrclib.net/api/search?q=%s";
    private static final String LYRICS_OVH_API = "https://api.lyrics.ovh/v1/%s/%s";
    /** 汽水歌词聚合：msg=歌名-歌手，直接返回 LRC 文本 */
    private static final String QSGC_LYRICS_API = "https://apiv1.yrain.top/qsgc.php?msg=%s";
    
    /**
     * 歌词结果
     */
    public static class LyricsResult {
        public String lyrics = "";
        public String source = "";
        public boolean success = false;
        public String error = "";
        public long costMs = -1L;
        
        @Override
        public String toString() {
            return String.format("LyricsResult{success=%s, source='%s', lyricsLength=%d, costMs=%d, error='%s'}",
                success, source, lyrics.length(), costMs, error);
        }
    }
    
    /**
     * 检查网络连接
     */
    private static boolean isNetworkAvailable(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            NetworkInfo networkInfo = cm.getActiveNetworkInfo();
            return networkInfo != null && networkInfo.isConnected();
        } catch (Exception e) {
            LogHelper.e(TAG, "检查网络连接失败", e);
            return false;
        }
    }
    
    /**
     * 基于 AppExecutors.okHttpClient 的通用 GET 请求，继承其连接池减少 TCP 握手开销。
     */
    static String httpGetPublic(String urlString) {
        return httpGet(urlString, CONNECT_TIMEOUT, READ_TIMEOUT, null);
    }

    static String httpGetPublic(String urlString, Map<String, String> extraHeaders) {
        return httpGet(urlString, CONNECT_TIMEOUT, READ_TIMEOUT, extraHeaders);
    }

    static boolean isNetworkAvailablePublic(Context context) {
        return isNetworkAvailable(context);
    }

    static String toSimplifiedChinesePublic(String text) {
        return toSimplifiedChinese(text);
    }

    private static String httpGet(String urlString) {
        return httpGet(urlString, CONNECT_TIMEOUT, READ_TIMEOUT, null);
    }

    private static String httpGet(String urlString, int connectTimeoutMs, int readTimeoutMs) {
        return httpGet(urlString, connectTimeoutMs, readTimeoutMs, null);
    }

    private static String httpGet(String urlString, int connectTimeoutMs, int readTimeoutMs,
                                  Map<String, String> extraHeaders) {
        final long startNs = System.nanoTime();
        try {
            OkHttpClient client = AppExecutors.INSTANCE.getOkHttpClient().newBuilder()
                .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                .build();

            Request.Builder requestBuilder = new Request.Builder()
                .url(urlString)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36")
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            if (extraHeaders != null) {
                for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        requestBuilder.header(entry.getKey(), entry.getValue());
                    }
                }
            }
            Request request = requestBuilder.build();

            try (Response response = client.newCall(request).execute()) {
                int code = response.code();
                long costMs = (System.nanoTime() - startNs) / 1_000_000L;
                LogHelper.d(TAG, "HTTP请求: " + urlString + " -> " + code + " (" + costMs + "ms)");

                if (code >= 200 && code < 300) {
                    String body = response.body() != null ? response.body().string() : "";
                    LogHelper.d(TAG, "HTTP响应长度: " + body.length() + " 字符");
                    return body;
                } else {
                    String errorBody = response.body() != null ? response.body().string() : "";
                    if (!errorBody.isEmpty()) {
                        LogHelper.w(TAG, "HTTP请求失败: " + code + ", 响应: " + errorBody);
                    } else {
                        LogHelper.w(TAG, "HTTP请求失败: " + code);
                    }
                    return null;
                }
            }
        } catch (Exception e) {
            if (isExpectedNetworkException(e)) {
                LogHelper.w(
                    TAG,
                    "HTTP请求超时/中断，已跳过: " + urlString + " (" +
                        e.getClass().getSimpleName() +
                        (e.getMessage() != null && !e.getMessage().isEmpty() ? ": " + e.getMessage() : "") +
                        ")"
                );
                return null;
            }
            LogHelper.e(TAG, "HTTP请求异常: " + urlString, e);
            return null;
        }
    }

    private static boolean isExpectedNetworkException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException ||
                current instanceof InterruptedIOException ||
                current instanceof SocketException ||
                current instanceof SSLException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 按平台搜歌（默认酷狗）。provider 见 {@link LyricsApiEndpoints} 中 {@code PROVIDER_*} 常量。
     */
    public static List<LyricsMatcher.Candidate> searchLyrics(Context context, String title,
                                                             String artist, String album,
                                                             double duration, String musixmatchApiKey) {
        return searchLyrics(context, title, artist, album, duration, musixmatchApiKey,
            LyricsApiEndpoints.PROVIDER_KUGOU);
    }

    public static List<LyricsMatcher.Candidate> searchLyrics(Context context, String title,
                                                             String artist, String album,
                                                             double duration, String musixmatchApiKey,
                                                             String provider) {
        if (title == null || title.isEmpty()) {
            LogHelper.w(TAG, "⚠️ 搜索歌词失败: 标题为空");
            return Collections.emptyList();
        }
        LogHelper.d(TAG, "🔍 搜索歌词 [" + provider + "]: " + title + " - " + artist);
        return LyricsPlatformClients.search(context, provider, title, artist, album, duration);
    }

    /**
     * 按平台与候选 id 取词（酷狗 hash / 网易 songId / QQ songmid / 酷我 musicId）。
     */
    public static LyricsResult getLyricsById(Context context, String id, String provider) {
        LogHelper.d(TAG, String.format("🔍 获取歌词: id=%s, provider=%s", id, provider));
        return LyricsPlatformClients.fetchById(context, id, provider);
    }

    /**
     * 兜底方案：lrclib 搜索歌词
     * 1) 先搜索「歌名 + 空格 + 歌手」
     * 2) 若无结果，再仅搜索「歌名」
     */
    public static LyricsResult searchLyricsFromLrclib(Context context, String title, String artist) {
        return searchLyricsFromLrclib(context, title, artist, false);
    }

    public static LyricsResult searchLyricsFromLrclib(Context context,
                                                      String title,
                                                      String artist,
                                                      boolean strictTitleArtistMatch) {
        LyricsResult result = new LyricsResult();
        result.source = "lrclib";
        result = searchLyricsFromLrclibInternal(context, title, artist, result, strictTitleArtistMatch);
        return result;
    }

    /**
     * 汽水歌词聚合（qsgc）：按「歌名-歌手」拉取 LRC。
     */
    public static LyricsResult searchLyricsFromQsgc(Context context, String title, String artist) {
        return searchLyricsFromQsgc(context, title, artist, QSGC_CONNECT_TIMEOUT, QSGC_READ_TIMEOUT);
    }

    public static LyricsResult searchLyricsFromQsgc(Context context,
                                                    String title,
                                                    String artist,
                                                    int connectTimeoutMs,
                                                    int readTimeoutMs) {
        LyricsResult result = new LyricsResult();
        result.source = "qsgc";

        if (!isNetworkAvailable(context)) {
            result.error = "网络不可用";
            return result;
        }

        if (title == null || title.trim().isEmpty()) {
            result.error = "标题为空";
            return result;
        }

        String t = title.trim();
        String a = artist != null ? artist.trim() : "";
        String msg = a.isEmpty() ? t : (t + "-" + a);

        try {
            String encodedMsg = URLEncoder.encode(msg, "UTF-8");
            String url = String.format(QSGC_LYRICS_API, encodedMsg);
            long start = System.currentTimeMillis();
            LogHelper.d(TAG, "🔍 qsgc 搜索: " + msg);
            String response = httpGet(url, connectTimeoutMs, readTimeoutMs);
            long cost = System.currentTimeMillis() - start;
            result.costMs = cost;
            LogHelper.d(TAG, "⏱️ qsgc 耗时: " + cost + "ms");

            if (response == null || response.trim().isEmpty()) {
                result.error = "qsgc 无响应";
                return result;
            }

            String raw = response.trim();
            if (looksLikeApiErrorPayload(raw)) {
                result.error = "qsgc 返回错误: " + truncateForLog(raw, 120);
                return result;
            }

            String lyrics = QishuiLyricsJsonParser.INSTANCE.extractLyricContent(raw);
            if (lyrics == null || lyrics.trim().isEmpty()) {
                lyrics = raw;
            }
            lyrics = lyrics.trim();
            if (!looksLikeLrcLyrics(lyrics)) {
                result.error = "qsgc 返回非 LRC 内容";
                return result;
            }

            result.lyrics = toSimplifiedChinese(lyrics);
            result.success = true;
            LogHelper.d(TAG, "✅ qsgc 获取歌词成功，长度: " + lyrics.length());
            return result;
        } catch (Exception e) {
            LogHelper.w(TAG, "qsgc 搜索失败", e);
            result.error = "qsgc 请求失败: " + e.getMessage();
            return result;
        }
    }

    private static boolean looksLikeLrcLyrics(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return text.contains("[") && text.contains("]");
    }

    private static boolean looksLikeApiErrorPayload(String text) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        String lower = text.toLowerCase();
        if (lower.startsWith("{") || lower.startsWith("[")) {
            return lower.contains("\"error\"") || lower.contains("\"msg\"")
                || lower.contains("\"code\"");
        }
        return text.length() < 8 && !text.contains("[");
    }

    private static String truncateForLog(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }

    /**
     * 另一个兜底方案：lyrics.ovh 纯文本歌词
     */
    public static LyricsResult searchLyricsFromLyricsOvh(Context context, String title, String artist) {
        LyricsResult result = new LyricsResult();
        result.source = "lyrics.ovh";

        if (!isNetworkAvailable(context)) {
            result.error = "网络不可用";
            return result;
        }

        if (title == null || title.trim().isEmpty()) {
            result.error = "标题为空";
            return result;
        }

        String t = title.trim();
        String a = artist != null ? artist.trim() : "";
        if (a.isEmpty()) {
            result.error = "歌手为空";
            return result;
        }

        try {
            String encodedArtist = URLEncoder.encode(a, "UTF-8");
            String encodedTitle = URLEncoder.encode(t, "UTF-8");
            String url = String.format(LYRICS_OVH_API, encodedArtist, encodedTitle);
            long start = System.currentTimeMillis();
            LogHelper.d(TAG, "🔍 lyrics.ovh 搜索: " + a + " - " + t);
            String response = httpGet(url, CONNECT_TIMEOUT, READ_TIMEOUT);
            long cost = System.currentTimeMillis() - start;
            result.costMs = cost;
            LogHelper.d(TAG, "⏱️ lyrics.ovh 耗时: " + cost + "ms");

            if (response == null || response.trim().isEmpty()) {
                result.error = "lyrics.ovh 无响应";
                return result;
            }

            org.json.JSONObject json = new org.json.JSONObject(response.trim());
            String lyrics = json.optString("lyrics", "").trim();
            if (lyrics.isEmpty()) {
                result.error = "lyrics.ovh 返回空歌词";
                return result;
            }

            result.lyrics = toSimplifiedChinese(lyrics);
            result.success = true;
            LogHelper.d(TAG, "✅ lyrics.ovh 获取歌词成功，长度: " + lyrics.length());
            return result;
        } catch (Exception e) {
            LogHelper.w(TAG, "lyrics.ovh 搜索失败", e);
            result.error = "lyrics.ovh 请求失败: " + e.getMessage();
            return result;
        }
    }

    private static LyricsResult searchLyricsFromLrclibInternal(Context context,
                                                               String title,
                                                               String artist,
                                                               LyricsResult result,
                                                               boolean strictTitleArtistMatch) {
        if (!isNetworkAvailable(context)) {
            result.error = "网络不可用";
            return result;
        }

        if (title == null || title.trim().isEmpty()) {
            result.error = "标题为空";
            return result;
        }

        List<String> queries = new ArrayList<>();
        String t = title.trim();
        String a = artist != null ? artist.trim() : "";
        if (!a.isEmpty()) {
            queries.add(t + " " + a);
        }
        queries.add(t);

        for (String query : queries) {
            try {
                String encoded = URLEncoder.encode(query, "UTF-8");
                String url = String.format(LRCLIB_SEARCH_API, encoded);
                long start = System.currentTimeMillis();
                LogHelper.d(TAG, "🔍 lrclib 搜索: " + query);
                String response = httpGet(url, LRCLIB_CONNECT_TIMEOUT, LRCLIB_READ_TIMEOUT);
                long cost = System.currentTimeMillis() - start;
                result.costMs = cost;
                LogHelper.d(TAG, "⏱️ lrclib 耗时: " + cost + "ms");
                if (response == null || response.trim().isEmpty()) {
                    continue;
                }

                org.json.JSONArray arr = new org.json.JSONArray(response.trim());
                if (arr.length() == 0) {
                    continue;
                }

                String lyrics = pickBestLyricsFromLrclib(arr, title, artist, strictTitleArtistMatch);
                if (lyrics != null && !lyrics.trim().isEmpty()) {
                    result.lyrics = toSimplifiedChinese(lyrics);
                    result.success = true;
                    LogHelper.d(TAG, "✅ lrclib 获取歌词成功，长度: " + lyrics.length() + ", query=" + query);
                    return result;
                }
            } catch (Exception e) {
                LogHelper.w(TAG, "lrclib 搜索失败: " + query, e);
            }
        }

        result.error = "lrclib 未匹配到歌词";
        return result;
    }

    /**
     * 优先使用带时间轴的 syncedLyrics；否则退回 plainLyrics。
     * strictTitleArtistMatch 时校验返回项的 name/artistName 与请求是否匹配。
     */
    private static String pickBestLyricsFromLrclib(org.json.JSONArray arr,
                                                   String reqTitle,
                                                   String reqArtist,
                                                   boolean strictTitleArtistMatch) {
        if (arr == null || arr.length() == 0) {
            return "";
        }

        String plainFallback = "";
        String plainName = "";
        String plainArtist = "";
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject item = arr.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String synced = item.optString("syncedLyrics", "").trim();
            String name = item.optString("name", "").trim();
            String artistName = item.optString("artistName", "").trim();
            if (!synced.isEmpty()) {
                if (strictTitleArtistMatch
                    && !isLrclibItemStrictMatch(reqTitle, reqArtist, name, artistName)) {
                    LogHelper.w(TAG, "❌ lrclib 严格匹配失败: req=\""
                        + reqTitle + " - " + reqArtist
                        + "\" vs hit=\"" + name + " - " + artistName + "\"");
                    continue;
                }
                return synced;
            }
            if (plainFallback.isEmpty()) {
                plainFallback = item.optString("plainLyrics", "").trim();
                plainName = name;
                plainArtist = artistName;
            }
        }
        if (!plainFallback.isEmpty()) {
            if (strictTitleArtistMatch
                && !isLrclibItemStrictMatch(reqTitle, reqArtist, plainName, plainArtist)) {
                LogHelper.w(TAG, "❌ lrclib plainLyrics 严格匹配失败: req=\""
                    + reqTitle + " - " + reqArtist
                    + "\" vs hit=\"" + plainName + " - " + plainArtist + "\"");
                return "";
            }
            return plainFallback;
        }
        return "";
    }

    private static boolean isLrclibItemStrictMatch(String reqTitle,
                                                   String reqArtist,
                                                   String itemName,
                                                   String itemArtist) {
        String rt = LyricsMatcher.normalize(reqTitle);
        String ra = LyricsMatcher.normalize(reqArtist);
        String ct = LyricsMatcher.normalize(itemName);
        String ca = LyricsMatcher.normalize(itemArtist);
        if (rt.isEmpty() || ct.isEmpty()) {
            return false;
        }
        boolean titleOk = rt.equals(ct) || rt.contains(ct) || ct.contains(rt);
        if (!titleOk) {
            return false;
        }
        if (!ra.isEmpty() && !ca.isEmpty()) {
            boolean artistOk = ra.equals(ca) || ra.contains(ca) || ca.contains(ra);
            return artistOk;
        }
        return true;
    }

    /**
     * 将繁体歌词转换为简体，避免字体/字形显示不一致。
     */
    private static String toSimplifiedChinese(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return text;
        }
        try {
            Transliterator ts = Transliterator.getInstance("Traditional-Simplified");
            return ts.transliterate(text);
        } catch (Exception e) {
            LogHelper.w(TAG, "繁转简失败，保留原文: " + e.getMessage());
            return text;
        }
    }

}

