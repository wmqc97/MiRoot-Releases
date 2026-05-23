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
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import javax.net.ssl.SSLException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

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
    /**
     * 酷狗与 MiRoot-3.4 老版对齐：mobilecdn 搜歌、m.kugou.com krc 取词；
     * 候选 id 为音频 hash（非 lyrics.kugou.com 的 id::accesskey）。
     */
    private static final String KUGOU_SONG_SEARCH_URL =
        "http://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword=%s&page=1&pagesize=30";
    private static final int KUGOU_LYRIC_KEYWORD_MAX_ATTEMPTS = 3;
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
     * 通用HTTP GET请求
     */
    private static String httpGet(String urlString) {
        return httpGet(urlString, CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    private static String httpGet(String urlString, int connectTimeoutMs, int readTimeoutMs) {
        HttpURLConnection connection = null;
        BufferedReader reader = null;
        
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            // 设置User-Agent，模拟浏览器请求（酷狗API可能需要）
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36");
            connection.setRequestProperty("Accept", "application/json, text/plain, */*");
            connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            // 允许重定向
            connection.setInstanceFollowRedirects(true);
            
            int responseCode = connection.getResponseCode();
            LogHelper.d(TAG, "HTTP请求: " + urlString + " -> " + responseCode);
            
            // 处理重定向
            if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP || 
                responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                responseCode == HttpURLConnection.HTTP_SEE_OTHER) {
                String redirectUrl = connection.getHeaderField("Location");
                if (redirectUrl != null && !redirectUrl.isEmpty()) {
                    LogHelper.d(TAG, "重定向到: " + redirectUrl);
                    connection.disconnect();
                    return httpGet(redirectUrl, connectTimeoutMs, readTimeoutMs);
                }
            }
            
            if (responseCode == HttpURLConnection.HTTP_OK) {
                InputStream inputStream = connection.getInputStream();
                reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
                
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line).append("\n");
                }
                
                String result = response.toString();
                LogHelper.d(TAG, "HTTP响应长度: " + result.length() + " 字符");
                return result;
            } else {
                // 尝试读取错误流
                InputStream errorStream = connection.getErrorStream();
                if (errorStream != null) {
                    reader = new BufferedReader(new InputStreamReader(errorStream, StandardCharsets.UTF_8));
                    StringBuilder errorResponse = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        errorResponse.append(line).append("\n");
                    }
                    LogHelper.w(TAG, "HTTP请求失败: " + responseCode + ", 错误响应: " + errorResponse.toString());
                } else {
                    LogHelper.w(TAG, "HTTP请求失败: " + responseCode);
                }
                return null;
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
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
                if (connection != null) {
                    connection.disconnect();
                }
            } catch (Exception e) {
                LogHelper.w(TAG, "关闭连接失败", e);
            }
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

    /** 酷狗曲库响应可能为 JSONP，与 MiRoot-3.4 老版一致做剥离 */
    private static String stripKugouSearchJsonWrapper(String raw) {
        if (raw == null) {
            return "";
        }
        String jsonContent = raw.trim();
        if (jsonContent.startsWith("(") && jsonContent.endsWith(")")) {
            jsonContent = jsonContent.substring(1, jsonContent.length() - 1);
        }
        if (jsonContent.startsWith("callback(") && jsonContent.endsWith(")")) {
            int start = jsonContent.indexOf("(") + 1;
            int end = jsonContent.lastIndexOf(")");
            if (start > 0 && end > start) {
                jsonContent = jsonContent.substring(start, end);
            }
        }
        return jsonContent;
    }

    /**
     * 与 MiRoot-3.4 老版一致：mobilecdn 搜歌 → 候选 id 为音频 hash → krc.php 拉歌词。
     */
    public static List<LyricsMatcher.Candidate> searchLyrics(Context context, String title,
                                                             String artist, String album,
                                                             double duration, String musixmatchApiKey) {
        List<LyricsMatcher.Candidate> candidates = new ArrayList<>();

        if (title == null || title.isEmpty()) {
            LogHelper.w(TAG, "⚠️ 搜索歌词失败: 标题为空");
            return candidates;
        }

        if (artist == null) {
            artist = "";
        }

        if (!isNetworkAvailable(context)) {
            LogHelper.w(TAG, "⚠️ 搜索歌词失败: 网络不可用");
            return candidates;
        }

        LyricsMatcher.Query query = new LyricsMatcher.Query(
            title, artist, album != null ? album : "", duration
        );

        LogHelper.d(TAG, "🔍 搜索歌词: " + title + " - " + artist + " (时长: " + duration + "秒)");

        String finalTitle = title != null ? title.trim() : "";
        String finalArtist = artist != null ? artist.trim() : "";
        List<String> keywords = LyricsMatcher.generateSearchKeywords(finalTitle, finalArtist);
        if (keywords.isEmpty() && !finalTitle.isEmpty()) {
            keywords = new ArrayList<>();
            keywords.add(finalTitle);
        }
        int maxKeywords = Math.min(KUGOU_LYRIC_KEYWORD_MAX_ATTEMPTS, keywords.size());
        LogHelper.d(TAG, "🔍 生成搜索关键词: " + keywords + ", 尝试前 " + maxKeywords + " 组");

        for (int i = 0; i < maxKeywords; i++) {
            String keyword = keywords.get(i);
            try {
                String encodedKeyword = URLEncoder.encode(keyword, "UTF-8");
                String searchUrl = String.format(KUGOU_SONG_SEARCH_URL, encodedKeyword);

                LogHelper.d(TAG, "🔍 搜索关键词: " + keyword);
                String response = httpGet(searchUrl);

                if (response == null || response.isEmpty()) {
                    continue;
                }

                String jsonContent = stripKugouSearchJsonWrapper(response);
                org.json.JSONObject jsonResponse = new org.json.JSONObject(jsonContent);
                org.json.JSONObject data = jsonResponse.optJSONObject("data");

                if (data == null) {
                    org.json.JSONArray directInfo = jsonResponse.optJSONArray("info");
                    if (directInfo != null && directInfo.length() > 0) {
                        data = jsonResponse;
                    } else {
                        LogHelper.w(TAG, "JSON响应中没有data字段");
                        continue;
                    }
                }

                org.json.JSONArray info = data.optJSONArray("info");
                if (info == null || info.length() == 0) {
                    LogHelper.w(TAG, "JSON响应中没有info数组或数组为空");
                    continue;
                }

                List<LyricsMatcher.Candidate> tempCandidates = new ArrayList<>();

                for (int j = 0; j < info.length(); j++) {
                    org.json.JSONObject song = info.getJSONObject(j);
                    String songName = song.optString("songname", "");
                    String singerName = song.optString("singername", "");
                    String hash = song.optString("hash", "");

                    if (hash.isEmpty()) {
                        continue;
                    }

                    LyricsMatcher.Candidate candidate = new LyricsMatcher.Candidate(
                        hash, songName, singerName, "", 0.0,
                        new String[]{"lrc", "krc"}, "kugou"
                    );

                    int score = LyricsMatcher.scoreInt(query, candidate);
                    candidate.score = score;

                    tempCandidates.add(candidate);
                }

                if (tempCandidates.size() > 1) {
                    Collections.sort(tempCandidates, new Comparator<LyricsMatcher.Candidate>() {
                        @Override
                        public int compare(LyricsMatcher.Candidate a, LyricsMatcher.Candidate b) {
                            return Integer.compare((int)b.score, (int)a.score);
                        }
                    });
                }

                int scoreThreshold = (i == 0) ? 10 : 20;
                for (LyricsMatcher.Candidate candidate : tempCandidates) {
                    if (candidate.score >= scoreThreshold) {
                        candidates.add(candidate);
                        LogHelper.d(TAG, "✅ 添加候选: " + candidate.title + " - " + candidate.artist +
                            " (分数: " + candidate.score + ", hash: " + candidate.id + ")");
                        if (candidates.size() >= 3) {
                            break;
                        }
                    }
                }

                if (!candidates.isEmpty()) {
                    LogHelper.d(TAG, "✅ 找到 " + candidates.size() + " 个候选结果，停止搜索");
                    break;
                } else {
                    LogHelper.d(TAG, "⚠️ 关键词 '" + keyword + "' 未找到符合条件的候选（阈值: " + scoreThreshold + "）");
                }

            } catch (Exception e) {
                LogHelper.w(TAG, "搜索关键词失败: " + keyword, e);
            }
        }

        if (candidates.isEmpty()) {
            LogHelper.w(TAG, "❌ 搜索歌词失败: 未找到匹配的候选结果");
        }

        return candidates;
    }

    /**
     * 与 MiRoot-3.4 老版一致：id 为音频 hash，请求 m.kugou.com krc 接口。
     */
    public static LyricsResult getLyricsById(Context context, String id, String provider) {
        LyricsResult result = new LyricsResult();
        result.source = "酷狗音乐";

        if (!isNetworkAvailable(context)) {
            result.error = "网络不可用";
            LogHelper.w(TAG, result.error);
            return result;
        }

        if (id == null || id.isEmpty()) {
            result.error = "歌词ID（hash）为空";
            return result;
        }

        LogHelper.d(TAG, String.format("🔍 根据 hash 获取歌词: id=%s, provider=%s", id, provider));

        try {
            String lyricsUrl = String.format(
                "http://m.kugou.com/app/i/krc.php?cmd=100&hash=%s&timelength=999999",
                id
            );

            String response = httpGet(lyricsUrl);

            if (response == null || response.isEmpty()) {
                result.error = "歌词请求失败";
                return result;
            }

            if (response.trim().startsWith("[")) {
                result.lyrics = response;
                result.success = true;
                LogHelper.d(TAG, "✅ 成功获取LRC格式歌词，长度: " + response.length());
            } else {
                try {
                    org.json.JSONObject jsonResponse = new org.json.JSONObject(response.trim());
                    String lyricsContent = jsonResponse.optString("content", "");

                    if (!lyricsContent.isEmpty()) {
                        result.lyrics = lyricsContent;
                        result.success = true;
                        LogHelper.d(TAG, "✅ 成功获取JSON格式歌词，长度: " + lyricsContent.length());
                    } else {
                        result.error = "歌词内容为空";
                    }
                } catch (org.json.JSONException e) {
                    if (response.length() > 10) {
                        result.lyrics = response;
                        result.success = true;
                        LogHelper.d(TAG, "✅ 成功获取歌词（未知格式），长度: " + response.length());
                    } else {
                        result.error = "歌词格式不支持或内容为空";
                    }
                }
            }

        } catch (Exception e) {
            result.error = "获取歌词异常: " + e.getMessage();
            LogHelper.e(TAG, result.error, e);
        }

        return result;
    }

    /**
     * 兜底方案：lrclib 搜索歌词
     * 1) 先搜索「歌名 + 空格 + 歌手」
     * 2) 若无结果，再仅搜索「歌名」
     */
    public static LyricsResult searchLyricsFromLrclib(Context context, String title, String artist) {
        LyricsResult result = new LyricsResult();
        result.source = "lrclib";
        result = searchLyricsFromLrclibInternal(context, title, artist, result);
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

    private static LyricsResult searchLyricsFromLrclibInternal(Context context, String title, String artist, LyricsResult result) {
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

                String lyrics = pickBestLyricsFromLrclib(arr);
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
     */
    private static String pickBestLyricsFromLrclib(org.json.JSONArray arr) {
        if (arr == null || arr.length() == 0) {
            return "";
        }

        String plainFallback = "";
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject item = arr.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String synced = item.optString("syncedLyrics", "").trim();
            if (!synced.isEmpty()) {
                return synced;
            }
            if (plainFallback.isEmpty()) {
                plainFallback = item.optString("plainLyrics", "").trim();
            }
        }
        return plainFallback;
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

