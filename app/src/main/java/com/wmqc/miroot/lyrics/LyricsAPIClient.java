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
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
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
     * 歌词结果
     */
    public static class LyricsResult {
        public String lyrics = "";
        public String source = "";
        public boolean success = false;
        public String error = "";
        
        @Override
        public String toString() {
            return String.format("LyricsResult{success=%s, source='%s', lyricsLength=%d, error='%s'}",
                success, source, lyrics.length(), error);
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
        HttpURLConnection connection = null;
        BufferedReader reader = null;
        
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
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
                    return httpGet(redirectUrl);
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
    
    /**
     * V3.16: 使用酷狗音乐API搜索歌词（基于小张桌面的实现）
     * 搜索歌词（返回多个候选结果，按分数排序）
     */
    public static List<LyricsMatcher.Candidate> searchLyrics(Context context, String title, 
                                                             String artist, String album, 
                                                             double duration, String musixmatchApiKey) {
        List<LyricsMatcher.Candidate> candidates = new ArrayList<>();
        
        if (title == null || title.isEmpty()) {
            LogHelper.w(TAG, "⚠️ 搜索歌词失败: 标题为空");
            return candidates;
        }
        
        if (artist == null) artist = "";
        
        // 检查网络连接
        if (!isNetworkAvailable(context)) {
            LogHelper.w(TAG, "⚠️ 搜索歌词失败: 网络不可用");
            return candidates;
        }
        
        // 创建查询对象
        LyricsMatcher.Query query = new LyricsMatcher.Query(
            title, artist, album != null ? album : "", duration
        );
        
        LogHelper.d(TAG, "🔍 搜索歌词: " + title + " - " + artist + " (时长: " + duration + "秒)");
        
        // 生成搜索关键词列表
        List<String> keywords = LyricsMatcher.generateSearchKeywords(title, artist);
        LogHelper.d(TAG, "🔍 生成搜索关键词: " + keywords);
        
        // 尝试每个关键词（最多尝试前3个）
        int maxKeywords = Math.min(3, keywords.size());
        for (int i = 0; i < maxKeywords; i++) {
            String keyword = keywords.get(i);
            try {
                // 使用酷狗音乐搜索API（注意：使用http，不是https）
                String encodedKeyword = URLEncoder.encode(keyword, "UTF-8");
                String searchUrl = String.format(
                    "http://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword=%s&page=1&pagesize=30",
                    encodedKeyword
                );
                
                LogHelper.d(TAG, "🔍 搜索关键词: " + keyword);
                String response = httpGet(searchUrl);
                
                if (response == null || response.isEmpty()) {
                    continue;
                }
                
                // 解析JSON响应（酷狗API可能返回JSONP格式，需要处理）
                String jsonContent = response.trim();
                // 如果是JSONP格式，提取JSON部分
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
                
                org.json.JSONObject jsonResponse = new org.json.JSONObject(jsonContent);
                org.json.JSONObject data = jsonResponse.optJSONObject("data");
                
                if (data == null) {
                    LogHelper.w(TAG, "JSON响应中没有data字段，尝试直接解析");
                    // 如果没有data字段，尝试直接解析info数组
                    org.json.JSONArray directInfo = jsonResponse.optJSONArray("info");
                    if (directInfo != null && directInfo.length() > 0) {
                        data = jsonResponse;
                    } else {
                        continue;
                    }
                }
                
                org.json.JSONArray info = data.optJSONArray("info");
                if (info == null || info.length() == 0) {
                    LogHelper.w(TAG, "JSON响应中没有info数组或数组为空");
                    continue;
                }
                
                // 解析搜索结果
                List<LyricsMatcher.Candidate> tempCandidates = new ArrayList<>();
                
                for (int j = 0; j < info.length(); j++) {
                    org.json.JSONObject song = info.getJSONObject(j);
                    String songName = song.optString("songname", "");
                    String singerName = song.optString("singername", "");
                    String hash = song.optString("hash", "");
                    
                    if (hash.isEmpty()) {
                        continue;
                    }
                    
                    // 计算匹配分数
                    LyricsMatcher.Candidate candidate = new LyricsMatcher.Candidate(
                        hash, songName, singerName, "", 0.0,
                        new String[]{"lrc", "krc"}, "kugou"
                    );
                    
                    // 使用新的整数评分算法
                    int score = LyricsMatcher.scoreInt(query, candidate);
                    candidate.score = score;
                    
                    tempCandidates.add(candidate);
                }
                
                // 按分数排序
                if (tempCandidates.size() > 1) {
                    Collections.sort(tempCandidates, new Comparator<LyricsMatcher.Candidate>() {
                        @Override
                        public int compare(LyricsMatcher.Candidate a, LyricsMatcher.Candidate b) {
                            return Integer.compare((int)b.score, (int)a.score);
                        }
                    });
                }
                
                // 选择分数最高的候选（分数阈值：降低阈值以提高匹配率）
                // 第一个关键词阈值较低，后续关键词阈值稍高
                int scoreThreshold = (i == 0) ? 10 : 20; // 降低阈值，提高匹配成功率
                for (LyricsMatcher.Candidate candidate : tempCandidates) {
                    if (candidate.score >= scoreThreshold) {
                        candidates.add(candidate);
                        LogHelper.d(TAG, "✅ 添加候选: " + candidate.title + " - " + candidate.artist + 
                                  " (分数: " + candidate.score + ", hash: " + candidate.id + ")");
                        // 最多返回3个高分候选（增加候选数量）
                        if (candidates.size() >= 3) {
                            break;
                        }
                    }
                }
                
                // 如果找到高分候选，停止搜索
                if (!candidates.isEmpty()) {
                    LogHelper.d(TAG, "✅ 找到 " + candidates.size() + " 个候选结果，停止搜索");
                    break;
                } else {
                    LogHelper.d(TAG, "⚠️ 关键词 '" + keyword + "' 未找到符合条件的候选（阈值: " + scoreThreshold + "）");
                }
                
            } catch (Exception e) {
                LogHelper.w(TAG, "搜索关键词失败: " + keyword, e);
                continue;
            }
        }
        
        if (candidates.isEmpty()) {
            LogHelper.w(TAG, "❌ 搜索歌词失败: 未找到匹配的候选结果");
        }
        
        return candidates;
    }
    
    /**
     * V3.16: 使用酷狗音乐API根据ID获取歌词（基于小张桌面的实现）
     * GET /lyrics/by-id?id=...&provider=...
     */
    public static LyricsResult getLyricsById(Context context, String id, String provider) {
        LyricsResult result = new LyricsResult();
        result.source = "酷狗音乐";
        
        // 检查网络连接
        if (!isNetworkAvailable(context)) {
            result.error = "网络不可用";
            LogHelper.w(TAG, result.error);
            return result;
        }
        
        if (id == null || id.isEmpty()) {
            result.error = "歌词ID（hash）为空";
            return result;
        }
        
        LogHelper.d(TAG, String.format("🔍 根据ID获取歌词: id=%s, provider=%s", id, provider));
        
        try {
            // V3.16: 使用酷狗音乐歌词API（基于小张桌面的实现）
            // URL格式：http://m.kugou.com/app/i/krc.php?cmd=100&hash={hash}&timelength=999999
            String lyricsUrl = String.format(
                "http://m.kugou.com/app/i/krc.php?cmd=100&hash=%s&timelength=999999",
                id
            );
            
            String response = httpGet(lyricsUrl);
            
            if (response == null || response.isEmpty()) {
                result.error = "歌词请求失败";
                return result;
            }
            
            // V3.16: 检查是否是LRC格式（以[开头）
            if (response.trim().startsWith("[")) {
                // 是LRC格式，直接返回
                result.lyrics = response;
                result.success = true;
                LogHelper.d(TAG, "✅ 成功获取LRC格式歌词，长度: " + response.length());
            } else {
                // 可能是JSON格式，尝试解析
                try {
                    org.json.JSONObject jsonResponse = new org.json.JSONObject(response);
                    String lyricsContent = jsonResponse.optString("content", "");
                    
                    if (!lyricsContent.isEmpty()) {
                        result.lyrics = lyricsContent;
                        result.success = true;
                        LogHelper.d(TAG, "✅ 成功获取JSON格式歌词，长度: " + lyricsContent.length());
                    } else {
                        result.error = "歌词内容为空";
                    }
                } catch (org.json.JSONException e) {
                    // 不是JSON格式，尝试直接使用
                    if (response.length() > 10) { // 至少有一些内容
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
}

